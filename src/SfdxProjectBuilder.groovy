class SfdxProjectBuilder implements Serializable {

  private final def _ // the member variable that contains the jenkinsFileScript

  private def sfdxScratchOrgDefinitionFile = "config/project-scratch-def.json"

  private def dockerImage

  private def usingDockerPipelinePlugin = false

  private def usingKubernetesContainerPlugin = false

  private def dockerImageName = 'salesforce/salesforcedx:latest-full'

  private def workingArtifactDirectory 

  private def sfdxScratchOrgAlias

  private def sfdxNewPackage

  private def sfdxNewPackageVersion

  private def packageVersionCreationResponseResult

  private def sfdxNewPackageVersionId

  private def packageCodeCoverage

  private def installationKeys

  private def packageInstallationKey

  private def loggingDebugMessages = false

  private def alwaysBuildPackage = false

  private def doNotBuildPackage = false

  private def slackChannelName

  private def slackResponseThreadId

  private def slackResponseTimestamp

  private def jiraSite

  private def jiraNotificationIsActive = false

  private def sendThreadedSlackMessages = true

  private def notifyOnSuccessfulBuilds = false 

  private def notifyOnReleaseBranchBuilds = false

  private def slackNotificationsIsActive = false

  private def scratchOrgWasCreated = false

  private def scratchOrgShouldBeDeleted = true

  private def releaseBranchList = ['master', 'main']

  private def dataLoadsToProcess = []

  private def permissionSetsToAssign = []

  private def upstreamProjectsToTriggerFrom = []

  private def upstreamProjectsToTriggerFromPrefix

  private boolean releaseBranchesShouldBeTreatedAsNull = true

  private def numberOfBuildsToKeep = '30'

  private def stageToStopBuildAt = 99

  private def buildTagName 

  private def buildGITCommitHash 

  private def methodDesignateAsReleaseBranchHasNotBeenCalled = true

  // Community related variables
  private def communityName
  private def urlPathPrefix
  private def templateName
  private boolean isCreatingCommunity = false
  private boolean isPublishingCommunity = false

  // the parsed contents of the SFDX project's configuration
  private def sfdxPackage

  SfdxProjectBuilder(def jenkinsFileScript) {
    _ = jenkinsFileScript
    this.buildTagName = _.env.BUILD_TAG.replaceAll(' ','-')
  }

  public void execute() {
    _.withFolderProperties {
      initializeBuildClass()

      if ( this.usingKubernetesContainerPlugin ) {
        _.node('salesforcedx') {
            processInnerNode()
        } // node
      } else {
        _.node {

          processInnerNode()

        } // node
      }
    }
  }

  public SfdxProjectBuilder setSlackChannelToNotify(def slackChannelName) {
    if ( ! slackChannelName.empty ) {
      this.slackChannelName = slackChannelName
      _.echo("SfdxProjectBuilder Parameter : Slack notifications will go to change ${slackChannelName}")
    }
    return this
  }

  public SfdxProjectBuilder setScratchOrgDefFile(def scratchOrgDefFile) {
    if ( ! scratchOrgDefFile.empty ) {
       _.echo("SfdxProjectBuilder Parameter : scratchOrgDefFile has been set to ${scratchOrgDefFile}")
       this.sfdxScratchOrgDefinitionFile = scratchOrgDefFile
    }
    return this
  }

  public SfdxProjectBuilder setDependencyInstallationKeys(def keysString) {
    if ( keysString != null ) {
      this.installationKeys = keysString
      _.echo('SfdxProjectBuilder Parameter : installationKeys has been set')
    }
    return this
  }

  public SfdxProjectBuilder setPackageInstallationKey( def packageInstallationKey ) {
    if ( packageInstallationKey != null && !packageInstallationKey.empty ) {
      this.packageInstallationKey = packageInstallationKey
      _.echo('SfdxProjectBuilder Parameter : packageInstallationKey has been set')
    }
    return this
  }

  public SfdxProjectBuilder alwaysBuildPackage() {
    this.alwaysBuildPackage = true
    _.echo('SfdxProjectBuilder Parameter set : Always building a package')
    if ( this.doNotBuildPackage ) {
      _.error('alwaysBuildPackage() and doNotBuildPackage() cannot both be specified')
    }
    return this
  }

  public SfdxProjectBuilder doNotBuildPackage() {
    this.doNotBuildPackage = true
    _.echo('SfdxProjectBuilder Parameter set : No package will be built.  Overrides all other considerations.')
    if ( this.alwaysBuildPackage ) {
      _.error('alwaysBuildPackage() and doNotBuildPackage() cannot both be specified')
    }
    return this
  }

  public SfdxProjectBuilder designateAsReleaseBranch( def branchName ) {

    if ( branchName != null && !branchName.empty && !this.releaseBranchList.contains(branchName)) {

      // if you are designating a different release branch, then you won't want "master" and "main" as the release branches
      // So remove "master" and "main" from the list.  but be careful doing so because the "designateAsReleaseBranch(string)" method 
      //  may have been called already, so you don't want to remove previous entries
      if ( this.methodDesignateAsReleaseBranchHasNotBeenCalled ) {
        // reset the this.releaseBranchList
        this.releaseBranchList = []
        this.methodDesignateAsReleaseBranchHasNotBeenCalled = false
      }

      def valueToAdd
      if ( branchName.contains('*') ) {
        // branchName is a regex expression
        // for now this only supports "startsWith"
        _.echo("branchName ${branchName} does contain an astericks ")
        if ( _.env.BRANCH_NAME.startsWith( branchName.replaceAll('\\*','') ) ) {
          _.echo("env.BRANCH_NAME '${_.env.BRANCH_NAME}' does startWith branchName ${branchName} ")
          valueToAdd = _.env.BRANCH_NAME
        } else {
          _.echo("env.BRANCH_NAME '${_.env.BRANCH_NAME}' does NOT startWith branchName ${branchName} ")
        }

      } else {
        _.echo("branchName ${branchName} does NOT contain an astericks ")
        // branchName is a standard string name
        valueToAdd = branchName
      }
      _.echo("valueToAdd == ${valueToAdd}")
      if ( valueToAdd ) {
        _.echo("SfdxProjectBuilder Parameter set : designating ${valueToAdd} as a release branch.")
        this.releaseBranchList.add( valueToAdd )
      } else {
        // echoing this out just for announcement even though it doesn't resolve to a real branch
        _.echo("SfdxProjectBuilder Parameter set : designating ${branchName} as a release branch.")
      }
    } else {
      _.error('designateAsReleaseBranch() value cannot be null')
    }

    return this
/*
******** - Setter == designateAsReleaseBranch('foobar')
      - sets the value to the this.releaseBranchList
      - should allow for regex here
        - How will this be managed?
          - In the setter method, if it is a regex expression, it should be evaluated against _.env.BRANCH_NAME.  If it is a match, then _.env.BRANCH_NAME should be added to this.releaseBranchList
          - In the setter method, ignore entries of "master" and "main" because they are already part of this.releaseBranchList
*/  

  }

  public SfdxProjectBuilder alwaysNotifyOnSuccess() {
    this.notifyOnSuccessfulBuilds = true
    _.echo('SfdxProjectBuilder Parameter set : Notify on successful builds')
    return this
  }

  public SfdxProjectBuilder alwaysNotifyOnReleaseBranch() {
    this.notifyOnReleaseBranchBuilds = true
    _.echo('SfdxProjectBuilder Parameter set : Notify on successful release branch builds')
    return this
  }

  public SfdxProjectBuilder setSlackNotificationsOff() {
    this.slackNotificationsIsActive = false
    _.echo('SfdxProjectBuilder Parameter set : Slack Notifications turned off')
    return this
  }

  public SfdxProjectBuilder setSlackNotificationsOn() {
    this.slackNotificationsIsActive = true
    _.echo('SfdxProjectBuilder Parameter set : Slack Notifications turned on')
    return this
  }

  public SfdxProjectBuilder preserveScratchOrg() {
    this.scratchOrgShouldBeDeleted = false
    _.echo('SfdxProjectBuilder Parameter set : Scratch Org will be preserved')
    return this
  }

  public SfdxProjectBuilder setUpstreamProjectToTriggerBuildFrom( String jenkinsBuildJobName ) {
    if ( jenkinsBuildJobName != null && !jenkinsBuildJobName.empty ) {
      this.upstreamProjectsToTriggerFrom.add( jenkinsBuildJobName )
      _.echo("SfdxProjectBuilder Parameter set : Added ${jenkinsBuildJobName} to the upstream project build triggers")
    }
    return this
  }

  public SfdxProjectBuilder setDataLoadFolderToProcess( String dataLoadFolder ) {
    if ( dataLoadFolder != null && !dataLoadFolder.empty ) {
      this.dataLoadsToProcess.add( dataLoadFolder )
      _.echo("SfdxProjectBuilder Parameter set : Added ${dataLoadFolder} to list of data load folders to process")
    }
    return this
  }

  public SfdxProjectBuilder setPermissionSetToAssign( String permissionSetName ) {
    if ( permissionSetName != null && !permissionSetName.empty ) {
      this.permissionSetsToAssign.add( permissionSetName )
      _.echo("SfdxProjectBuilder Parameter set : Added ${permissionSetName} to list of permission sets to assign")
    }
    return this
  }

  public SfdxProjectBuilder setDockerImageName( String dockerImageName ) {
    if ( dockerImageName != null && !dockerImageName.empty ) {
      this.dockerImageName = dockerImageName
      _.echo("SfdxProjectBuilder Parameter set : Setting docker image to be ${dockerImageName}")
    }
    return this
  }

  public SfdxProjectBuilder setJiraSite( String jiraSite ) {
    if ( jiraSite != null && !jiraSite.empty ) {
      this.jiraSite = jiraSite
      this.jiraNotificationIsActive = true
      _.echo("SfdxProjectBuilder Parameter set : Setting JIRA site to be ${jiraSite}")
    }
  }

  public SfdxProjectBuilder setupCommunity( String communityName, String urlPathPrefix, String templateName ) {
    if ( communityName == null || communityName.empty || urlPathPrefix == null || urlPathPrefix.empty || templateName == null || templateName.empty ) {
      
      if ( communityName == null || communityName.empty ) {
        _.echo("SfdxProjectBuilder Parameter ERROR : setupCommunity() method communityName parameter cannot be null.")
      }
      if ( urlPathPrefix == null || urlPathPrefix.empty ) {
        _.echo("SfdxProjectBuilder Parameter ERROR : setupCommunity() method urlPathPrefix parameter cannot be null.")
      }
      if ( templateName == null || templateName.empty ) {
        _.echo("SfdxProjectBuilder Parameter ERROR : setupCommunity() method templateName parameter cannot be null.")
      }
      _.error("PARAMETER ERROR")
    }

    this.communityName = communityName
    _.echo("SfdxProjectBuilder Parameter set : Setting community name to be ${communityName}")
    
    this.urlPathPrefix = urlPathPrefix
    _.echo("SfdxProjectBuilder Parameter set : Setting community URL path prefix to be ${urlPathPrefix}")
    
    this.templateName = templateName
    _.echo("SfdxProjectBuilder Parameter set : Setting community template name to be ${templateName}")

    this.isCreatingCommunity = true

    return this
  }

  public SfdxProjectBuilder publishCommunity( String communityName ) {
  
    if ( communityName == null || communityName.empty ) {
      _.echo("SfdxProjectBuilder Parameter ERROR : publishCommunity() method communityName parameter cannot be null.")
      _.error("PARAMETER ERROR")
    }
    
    this.communityName = communityName
    _.echo("SfdxProjectBuilder Parameter set : Setting community name to be ${communityName}")
    
    this.isPublishingCommunity = true

    return this
  }

  public SfdxProjectBuilder setNumberOfBuildsToKeep( Integer numberOfBuildsToKeep ) {
    if ( numberOfBuildsToKeep != null ) {
      this.numberOfBuildsToKeep = numberOfBuildsToKeep.toString()
      _.echo("SfdxProjectBuilder Parameter set : Setting number of builds to keep to be ${numberOfBuildsToKeep.toString()}")
    }
    return this
  }

  public SfdxProjectBuilder stopBuildAtStage( Integer stageToStopBuildAt ) {
    if ( stageToStopBuildAt != null ) {
      this.stageToStopBuildAt = stageToStopBuildAt
      _.echo("SfdxProjectBuilder Parameter set : Stopping build after stage ${stageToStopBuildAt.toString()}")
    }
    return this
  }

  public SfdxProjectBuilder setDebugOn() {
    this.loggingDebugMessages = true
    _.echo('SfdxProjectBuilder Parameter set : Logging of debug messages is turned on')
    return this
  }

  // vo id setBuildDescription(Map args) {
  //   jenkinsFileScript.currentBuild.displayName = args.title
  //   jenkinsFileScript.currentBuild.description = args.description
  // }

  private void processInnerNode() {
      sendSlackMessage(
        color: 'good',
        message: "Build ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)",
        isHeaderMessage: true
      )

      // checkout the main source code for the project.
      this.buildGITCommitHash = _.checkout(_.scm).GIT_COMMIT
      _.echo("buildGITCommitHash == ${this.buildGITCommitHash}") 

      // start the pipeline
      _.pipeline {

        _.properties([
          // ensure that concurrent builds on the same project is not possible
          _.disableConcurrentBuilds(),

          _.buildDiscarder(_.logRotator(numToKeepStr: this.numberOfBuildsToKeep)),

          _.pipelineTriggers(
            processProjectTriggers()
          )
          
        ])

        if ( usingDockerPipelinePlugin ) {
          _.echo('About to setup dockerImage')
          // ensure that we have the latest
          this.dockerImage.pull()
          this.dockerImage.inside('-e HOME=/tmp -e NPM_CONFIG_PREFIX=/tmp/.npm') {
            processStages() 
          }
        }
        else if ( usingKubernetesContainerPlugin ) {
          // Setup Kubernetes POD here
          _.container('salesforcedx') {  // salesforcedx
            processStages()
          }
        }
        else {
          _.echo("No docker image specified")
          processStages()
        }
        
      } // pipeline
  }

  private void processStages() {
    try {
      if ( this.stageToStopBuildAt >= 1 ) {
        _.stage('Validate') {
          sendSlackMessage(
            color: 'good',
            message: "Validation stage"
          )
          validateStage()
        } // stage: Validate
      }

      if ( this.stageToStopBuildAt >= 2 ) {
        _.stage('Initialize') {
          sendSlackMessage(
            color: 'good',
            message: "Initialization stage"
          )
          initializeStage()
        } // stage: Initialize
      }

      if ( this.stageToStopBuildAt >= 3 ) {
        _.stage('Process Resources') {
          sendSlackMessage(
            color: 'good',
            message: "Processing Resources stage"
          )
          processResourcesStage()
        } // stage: Process Resources
      }

      if ( this.stageToStopBuildAt >= 4 ) {
        _.stage('Compile') {
          sendSlackMessage(
            color: 'good',
            message: "Compilation stage"
          )
          compileStage()
        } // stage: Compile
      }

      if ( this.stageToStopBuildAt >= 5 ) {
        _.stage('Test') {
          sendSlackMessage(
            color: 'good',
            message: "Testing stage"
          )
          testStage()
        } // stage: Test
      }

      if ( this.stageToStopBuildAt >= 6 ) {
        _.stage('Package') {
          sendSlackMessage(
            color: 'good',
            message: "Packaging stage"
          )
          packageStage()
        } // stage: Package
      }

      if ( this.stageToStopBuildAt >= 7 ) {
        _.stage('Artifact Recording') {
          sendSlackMessage(
            color: 'good',
            message: "Artifact Recording stage"
          )
          artifactRecordingStage()
        } // stage: Artifact Recording
      } 

      postSuccess()
    }
    catch (ex) {
      postFailure(ex)
    }
    finally {
      sendJiraBuildInformation()
      postAlways()
    }
  }

  void initializeStage() {

    // record CLI version
    def rmsg = _.sh returnStdout: true, script: "sfdx version --json"
    _.echo(rmsg)
    // the following doesn't work on very old SFDX CLI versions
    // def responseMsg = jsonParse( rmsg )
    // _.echo(responseMsg)
    // _.echo("sfdx version == ${responseMsg.cliVersion}")

    installRequiredCLIPlugins()
    // setup this build's unique artifact directory
    _.sh "mkdir -p ${this.workingArtifactDirectory}"

    readAndParseSFDXProjectFile()
    authenticateToDevHub()
    createScratchOrg()

  }

  void validateStage() {
    isProjectFileExisting()
    isEnvVarPopulatedConnectedAppConsumerKeyDH()
    isEnvVarPopulatedSFDXDevHubUsername()
    isEnvVarPopulatedSFDXDevHubHost()
    isEnvVarPopulatedJWTCredIdDH()

    // def rmsg = _.sh returnStdout: true, script: "pwd"
    // _.echo(rmsg)
    // // rmsg = _.sh returnStdout: true, script: "ls -lap /.local/"
    // // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap $HOME'
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx'
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx/node_modules'
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx/node_modules/@salesforce'
    // _.echo(rmsg)
    
    
    // rmsg = _.sh returnStdout: true, script: 'chown -R root /usr/local/lib/sfdx'
    // _.echo(rmsg)

    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx'
    // _.echo(rmsg)

    // // _.sh returnStdout: true, script: "ls -lap /root/.local/share/sfdx/node_modules/"
    // def rmsgForPluginCheck = _.sh returnStdout: true, script: "sfdx plugins"
    // _.echo(rmsgForPluginCheck)
    // def rmsgInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install @dx-cli-toolbox/sfdx-toolbox-package-utils"
    // _.echo(rmsgInstall)
    // rmsg = _.sh returnStdout: true, script: "sfdx plugins"
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap $HOME'
    // _.echo(rmsg)
  }

  void processResourcesStage() {
    // resetAllDependenciesToLatestWherePossible();
    installAllDependencies()
    setupCommunityIfNeeded()
  }

  void compileStage() {
    compileCode()
    publishCommunityIfNeeded()
  }

  void testStage() {
    // Give the code time to settle down before the unit tests begin
    _.sleep time: 1, unit: 'MINUTES'

    assignPermissionSets()

    // _.failFast true // this is part of the declarative syntax.  Is there an equivalent in the scripted model?

    _.parallel(
      'Dataload Verification': { executeDataLoads() } ,
      'Unit Tests': { 
        executeUnitTests()
        // evaluateTestResults() 
      }
    ) // parallel
  }

  void packageStage() {
    packageTheProject()
  }

  void artifactRecordingStage() {
    archiveAllBuildArtifacts()
  }

  void postSuccess() {
    // _.echo("postSuccess desicison point 1 == ${this.notifyOnSuccessfulBuilds}" )
    // _.echo("postSuccess desicison point 2 == ${( this.notifyOnReleaseBranchBuilds && this.releaseBranchList.contains(_.env.BRANCH_NAME) )}" )
    // _.echo("postSuccess desicison point 3 == ${( _.currentBuild.previousBuild != null && _.currentBuild.resultIsBetterOrEqualTo( _.currentBuild.previousBuild.currentResult ) )}" )
    if ( this.notifyOnSuccessfulBuilds 
        || ( this.notifyOnReleaseBranchBuilds && this.releaseBranchList.contains(_.env.BRANCH_NAME) )
        || ( _.currentBuild.previousBuild != null && _.currentBuild.resultIsBetterOrEqualTo( _.currentBuild.previousBuild.currentResult ) ) ) {
      sendSlackMessage(
        color: 'good',
        message: "Build completed ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)",
        isFooterMessage: true
      )
    }

    _.echo("this.sfdxNewPackageVersion == ${sfdxNewPackageVersion}")

    if ( this.sfdxNewPackageVersion != null ) {

      def packageVersionName = this.sfdxNewPackageVersion.Package2Name + '@' + this.sfdxNewPackageVersion.MajorVersion + '.' + this.sfdxNewPackageVersion.MinorVersion + '.' + this.sfdxNewPackageVersion.PatchVersion + '-' + this.sfdxNewPackageVersion.BuildNumber

      if ( this.sfdxNewPackageVersion.Branch != null ) {
        packageVersionName += '-' + this.sfdxNewPackageVersion.Branch
      }

      def message = "New package version available: ${packageVersionName} (${this.sfdxNewPackageVersion.SubscriberPackageVersionId})\n" 

      if ( this.packageCodeCoverage != null ) {
        message += "Code coverage for this version is ${this.packageCodeCoverage}%"
      }
      
      sendSlackMessage(
        color: 'good',
        message: message,
        isFooterMessage: true
      )
    }
  }

  void postFailure(def ex) {
    debug('start of postFailure method')
    _.echo(ex.getMessage())

    _.currentBuild.result = 'FAILURE'

    // def userIds = _.slackUserIdsFromCommitters()
    // def userIdsString = userIds.collect { "<@$it>" }.join(' ')
    // debug("Slack userIds == ${userIds}")
    // debug("Slack userIdsString == ${userIdsString}")
    // slackSend(color: "good", message: "$userIdsString Message from Jenkins Pipeline")

    sendSlackMessage(
      color: 'danger',
      message: "Build failed ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)",
      isFooterMessage: true,
      isBroadcastingReply: true,
      isNotifyingCommitters: true
    )

  }

  void postAlways() {
    deleteScratchOrg()

    // temporary workaround pending resolution to this issue https://github.com/forcedotcom/cli/issues/81.  Also, see reference in authenticateToDevHub() method
    _.fileOperations([_.fileDeleteOperation(excludes: '', includes: 'server.key')])

    def userIds = _.slackUserIdsFromCommitters()
    debug(userIds)

  }

  void sendJiraBuildInformation() {
    debug("sendJiraBuildInformation starts")
    if ( this.jiraNotificationIsActive ) {
      _.jiraSendBuildInfo site: "${this.jiraSite}"
    }
  }

  private void sendSlackMessage(Map args) {
    
    debug("sendSlackMessage with message of '${args.message}'")
    debug("sendThreadedSlackMessages == ${sendThreadedSlackMessages}")

    if ( this.slackNotificationsIsActive ) {

      // header messages -- should be shown if system allows it 
      // thread messages -- should be shown only if slackResponseThreadId != null
      // footer messages -- should always be shown -- if slackResponseThreadId != null, then footer message should be part of thread  else should be in slackChannelName

      def shouldSendMessage = false 
      def slackChannelToSendMessageTo
      def shouldReplyBroadcast = 'false'
      def shouldNotifyCommitters = 'false'

      if ( args.isHeaderMessage && this.sendThreadedSlackMessages) {
      
        if ( this.slackChannelName ) {
          slackChannelToSendMessageTo = this.slackChannelName
          // this messages is the start of a Slack thread in the Slack channel specified
          // slackResponse = _.slackSend channel: "${this.slackChannelName}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false 
      
        // } else {
          // this messages is the start of a Slack thread in the default Slack channel specified in the Global Config of Jenkins
          // slackResponse = _.slackSend color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false
        }
        shouldSendMessage = true
      } else if ( args.isFooterMessage ) {
        if ( this.slackResponseThreadId ) {
          slackChannelToSendMessageTo = this.slackResponseThreadId
        } else if ( this.slackChannelName ) {
          slackChannelToSendMessageTo = this.slackChannelName
        }
        if ( args.isBroadcastingReply ) {
          shouldReplyBroadcast = 'true'
        }
        shouldSendMessage = true
      } else if ( this.sendThreadedSlackMessages && this.slackResponseThreadId ) {
        shouldSendMessage = true
        slackChannelToSendMessageTo = this.slackResponseThreadId
      }

      if ( args.isNotifyingCommitters ) {
        shouldNotifyCommitters = 'true'
      }

      debug("shouldSendMessage == ${shouldSendMessage}")

      if ( shouldSendMessage ) {

        debug("slackChannelToSendMessageTo == ${slackChannelToSendMessageTo}")
        debug("shouldReplyBroadcast == ${shouldReplyBroadcast}")
  
        def slackResponse

        if ( args.fileToSend ) {
          if ( slackChannelToSendMessageTo ) {
            // slackUploadFile channel: 'blue', credentialId: 'slack-steampunklife-bot-token', filePath: '${this.workingArtifactDirectory}/**/test-result-707*.json', initialComment: 'Test Results JSON file'
            slackResponse = _.slackUploadFile channel: "${slackChannelToSendMessageTo}", initialComment: "${args.message}", filePath: "${args.fileToSend}"
          } else {
            slackResponse = _.slackUploadFile initialComment: "${args.message}", filePath: "${args.fileToSend}"
          }
        } else {
          if ( slackChannelToSendMessageTo ) {
            slackResponse = _.slackSend channel: "${slackChannelToSendMessageTo}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: "${shouldNotifyCommitters}", replyBroadcast: "${shouldReplyBroadcast}"
          } else {
            slackResponse = _.slackSend color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: "${shouldNotifyCommitters}", replyBroadcast: "${shouldReplyBroadcast}"
          }
          debug("slackResponse == ${slackResponse}")
          debug("slackResponse.threadId == ${slackResponse.threadId}")
          debug("slackResponseThreadId == ${slackResponseThreadId}")
        }
        if ( this.slackResponseThreadId == null && slackResponse && slackResponse.threadId ) {
          // set the Slack Thread Id for future updates
          this.slackResponseThreadId = slackResponse.threadId
          this.slackResponseTimestamp = slackResponse.ts
        }
      }

      // } else if ( this.slackResponseThreadId ) {
      //   // this message should be appended to an existing Slack thread
      //   slackResponse = _.slackSend channel: "${this.slackResponseThreadId}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false
      // }       
    } else {
      _.echo("Slack notifications are currently off")
    }
    // def rmsg =  _.sh returnStdout: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST} --json"
    // Potential enhancement -- multi-threaded slack messages
    // def slackResponse = slackSend(channel: "cool-threads", message: "Here is the primary message")
    // slackSend(channel: slackResponse.threadId, message: "Thread reply #1")
    // slackSend(channel: slackResponse.threadId, message: "Thread reply #2")
  }

  private void isProjectFileExisting()
  {
    def sfdxProjectFileExists = _.fileExists 'sfdx-project.json'
    if ( ! sfdxProjectFileExists ) {
        _.error 'SFDX project file (sfdx-project.json) not found.'
    }
  }

  private void isEnvVarPopulatedConnectedAppConsumerKeyDH()
  {
    isEnvVarPopulated(_.env.CONNECTED_APP_CONSUMER_KEY_DH, 'CONNECTED_APP_CONSUMER_KEY_DH')
  }

  private void isEnvVarPopulatedSFDXDevHubUsername()
  {
    isEnvVarPopulated(_.env.SFDX_DEV_HUB_USERNAME, 'SFDX_DEV_HUB_USERNAME')
  }

  private void isEnvVarPopulatedSFDXDevHubHost()
  {
    isEnvVarPopulated(_.env.SFDX_DEV_HUB_HOST, 'SFDX_DEV_HUB_HOST')
  }

  private void isEnvVarPopulatedJWTCredIdDH()
  {
    isEnvVarPopulated(_.env.JWT_CRED_ID_DH, 'JWT_CRED_ID_DH')
  }

  private void isEnvVarPopulated(enironmentVariable, enironmentVariableName)
  {
    // _.echo( enironmentVariable )
    // _.echo( enironmentVariableName )
    if ( ! enironmentVariable ) {
      _.error "Environment Variable ${enironmentVariableName} is null"
    }
  }

  private void initializeBuildClass() {
    initializeBuildScriptVariables()
    initializeDockerImage()
  }

  private void initializeDockerImage() {
    debug("usingDockerPipelinePlugin == ${usingDockerPipelinePlugin}")
    debug("dockerImageName == ${dockerImageName}")
    if ( this.usingDockerPipelinePlugin ) {
      this.dockerImage = _.docker.image(this.dockerImageName)
      debug("Using dockerImage ${this.dockerImageName} with Docker Pipeline Plugin")
    }
    else if ( this.usingKubernetesContainerPlugin ) {
      // WATCH - Kubernetes sets the docker image as part of the podTemplate
      // this.dockerImage = _.docker.image(this.dockerImageName)
      debug("Using dockerImage ${this.dockerImageName} with Kubernetes Container Plugin")
    }
  }

  private void initializeBuildScriptVariables() {
    this.workingArtifactDirectory = "target/${_.env.BUILD_NUMBER}"
    this.sfdxScratchOrgAlias = "bluesphere-${_.env.BUILD_TAG.replaceAll("/", "_").replaceAll(" ","_")}"

// TODO: Change env TREAT_RELEASE_BRANCHES_AS_NULL_FOR_DEPENDENCIES_AND_PACKAGE_VERSION_CREATION to be TREAT_RELEASE_BRANCHES_AS_NULL_FOR_DEPENDENCIES_AND_PACKAGE_VERSION_CREATION

    if ( _.env.TREAT_RELEASE_BRANCHES_AS_NULL_FOR_DEPENDENCIES_AND_PACKAGE_VERSION_CREATION != null ) {
      this.releaseBranchesShouldBeTreatedAsNull = _.env.TREAT_RELEASE_BRANCHES_AS_NULL_FOR_DEPENDENCIES_AND_PACKAGE_VERSION_CREATION.toBoolean()
    }
    // TODO: Figure out way to use env vars to drive the container configuration

    if ( _.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION ) {
      this.usingDockerPipelinePlugin = false
      this.usingKubernetesContainerPlugin = false
      if ( _.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION == 'docker-workflow' ) {
        this.usingDockerPipelinePlugin = true 
      } else if ( _.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION == 'kubernetes' ) {
        this.usingKubernetesContainerPlugin = true
      } else {
        _.error( "Environment variable JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION set to ${_.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION} but not a valid option" )
      }
    }

    if ( _.env.DEFAULT_DOCKER_IMAGE_NAME ) {
      this.dockerImageName = _.env.DEFAULT_DOCKER_IMAGE_NAME
    }

    if ( _.env.UPSTREAM_PROJECT_PREFIX ) {
      this.upstreamProjectsToTriggerFromPrefix = _.env.UPSTREAM_PROJECT_PREFIX
    }

    if ( _.env.DEACTIVATE_SLACK_NOTIFICATIONS_OVERRIDE ) {
      this.slackNotificationsIsActive = false
    }

    if ( _.env.JIRA_SITE ) {
      this.jiraSite = _.env.JIRA_SITE 
      this.jiraNotificationIsActive = true
    }    
  }

  private void readAndParseSFDXProjectFile() {
    _.echo('Deserialize the sfdx-project.json ')
    this.sfdxPackage = jsonParse( _.readFile('sfdx-project.json') )
  }

  private void authenticateToDevHub() {
    _.echo('Authenticate to the Dev Hub ')
    // _.echo(_.env.JWT_CRED_ID_DH)
    _.withCredentials( [ _.file( credentialsId: _.env.JWT_CRED_ID_DH, variable: 'jwt_key_file') ] ) {
        // temporary workaround pending resolution to this issue https://github.com/forcedotcom/cli/issues/81
        _.sh returnStatus: true, script: "cp ${_.jwt_key_file} ./server.key"
        // _.fileOperations([_.fileCopyOperation(excludes: '', flattenFiles: false, includes: _.jwt_key_file, targetLocation: './server.key')])  // some issue with the masking of the file name.  Need to sort it out

        _.echo("Authenticating To Dev Hub...")
        

        // def rc = _.sh returnStatus: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST}"
        // if (rc != 0) { 
        //   _.error "hub org authorization failed" 
        // }

      try {
        def rmsg =  _.sh returnStdout: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST} --json"
        // _.echo('mark C')
        def response = jsonParse( rmsg )
        // _.echo('mark D')
        // _.echo(response)
        // _.echo('mark E')
      }
      catch (ex) {
        _.echo('------------------------------------------------------')
        // _.echo('mark F')
        _.echo(ex.getMessage())
        // _.echo('mark G')
        _.echo('------------------------------------------------------')
        _.error "hub org authorization failed" 
      }
    }
  }

  private void createScratchOrg() {
    _.echo('Creating scratch org')

    def commandScriptString = "sfdx force:org:create --definitionfile ${this.sfdxScratchOrgDefinitionFile} --json --durationdays 1 --setalias ${this.sfdxScratchOrgAlias} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME} --wait 30"

    def response

    try {
      debug('before call to sh command to create org')
      def rmsg = _.sh returnStdout: true, script: commandScriptString
      debug('after the call to sh command to create org')
      debug(rmsg)
      response = jsonParse( rmsg )
      debug('after the parsing of rmsg')
      debug('------------------------------------------------------')
      debug('response == ')
      debug(response)
      debug('------------------------------------------------------')
    }
    catch (ex) {
      // printf ex
      _.echo('------------------------------------------------------')
      // _.echo(ex)
      // _.echo(ex.status)
      // _.echo(ex.name)
      // _.echo(ex.message)
      _.echo('------------------------------------------------------')
      // if (ex.getMessage().contains('OPERATION_TOO_LARGE')) {
      if (ex.message.contains('OPERATION_TOO_LARGE')) {
        _.echo('exception message contains OPERATION_TOO_LARGE')
        _.echo('------------------------------------------------------')
        _.echo(ex.printStackTrace())
        _.error "Failed to create Scratch Org -- ${response.message}"
      } else if (ex.name.equals('genericTimeoutMessage') || ex.name.equals('RemoteOrgSignupFailed')) {
        // try one more time to create the scratch org
        _.echo('Original attempt to create scratch org timed out.  Trying to create one again.')
        rmsg = _.sh returnStdout: true, script: commandScriptString
        response = jsonParse( rmsg )
        if ( response.status != 0 ) {
          _.error "Failed to create Scratch Org -- ${response.message}"
        }
      }
      else {
        _.echo(ex.printStackTrace())
        _.error "Failed to create Scratch Org -- ${response.message}"
      }
    }
    this.scratchOrgWasCreated = true
  }

  private void deleteScratchOrg() {
    if (this.scratchOrgWasCreated && this.scratchOrgShouldBeDeleted) {
      _.echo('Deleting scratch org')
      def rc = _.sh returnStatus: true, script: "sfdx force:org:delete --noprompt --targetusername ${this.sfdxScratchOrgAlias} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
      if (rc != 0) { 
        _.error "deletion of scratch org ${this.sfdxScratchOrgAlias} failed"
      }
    }
    else if ( this.scratchOrgWasCreated && ! this.scratchOrgShouldBeDeleted ) {
      // find the scratch org sfdxAuthUrl
      def rmsg = _.sh returnStdout: true, script: "sfdx force:org:display --verbose --json --targetusername ${this.sfdxScratchOrgAlias}"

      def response = jsonParse( rmsg )

      if (response.status == 0) {
        _.echo("Scratch sfdxAuthUrl: ${response.result.sfdxAuthUrl}")
        _.echo("Scratch Org Info: ${response.result}")
      }
    }
  }

  private void resetAllDependenciesToLatestWherePossible() {
    def commandScriptString = "sfdx toolbox:package:dependencies:manage --updatetolatest --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME} --json"

    // TODO: Make adjustments here as well
    if ( !this.releaseBranchList.contains(_.env.BRANCH_NAME)
        || ( this.releaseBranchList.contains(_.env.BRANCH_NAME)
            && !this.releaseBranchesShouldBeTreatedAsNull ) 
        ) 
    {
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    }

    def rmsg = _.sh returnStdout: true, script: commandScriptString

    def response = jsonParse( rmsg )

    if (response.status != 0) {
      _.echo rmsg
      _.error "resetting all dependencies to latest failed -- ${response.message}"
    }

    _.sleep time: 5, unit: 'SECONDS'
  } 

  private void installAllDependencies() {
    // _.echo("env.BRANCH_NAME == ${_.env.BRANCH_NAME}")
    // _.echo("this.releaseBranchesShouldBeTreatedAsNull == ${this.releaseBranchesShouldBeTreatedAsNull}")
    // if ( _.env.BRANCH_NAME == 'master' ) {
    //   _.echo('branch_name == master')
    // }
    // if ( _.env.BRANCH_NAME != 'master' ) {
    //   _.echo('branch_name != master')
    // }

    // if ( !this.releaseBranchesShouldBeTreatedAsNull ) {
    //   _.echo('!this.releaseBranchesShouldBeTreatedAsNull == true')
    // } else {
    //   _.echo('!this.releaseBranchesShouldBeTreatedAsNull == false')
    // }

    // if ( _.env.BRANCH_NAME == 'master' && ( !this.releaseBranchesShouldBeTreatedAsNull ) ) {
    //   _.echo('secondary condition true')
    // } else {
    //   _.echo('secondary condition false')
    // }

    // if ( _.env.BRANCH_NAME != 'master' || ( _.env.BRANCH_NAME == 'master' && !this.releaseBranchesShouldBeTreatedAsNull ) ) {
    //   _.echo('complete condition true')
    // } else {
    //   _.echo('complete condition false')
    // }

    def commandScriptString = "sfdx toolbox:package:dependencies:install --wait 240 --noprecheck --targetusername ${this.sfdxScratchOrgAlias} --json"
    
    // TODO: Make adjustments here as well
    if ( !this.releaseBranchList.contains(_.env.BRANCH_NAME) || ( this.releaseBranchList.contains(_.env.BRANCH_NAME) && !this.releaseBranchesShouldBeTreatedAsNull ) ) {
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    }

    if ( this.installationKeys != null ) {
      // 1:MyPackage1Key 2: 3:MyPackage3Key
      commandScriptString = commandScriptString + " --installationkeys '" + this.installationKeys + "'"
    }

    // _.echo ("commandScriptString == ${commandScriptString}")
    
    def response 

    try {
      debug('before call to sh command to install dependencies')
      def rmsg = _.sh returnStdout: true, script: commandScriptString
      debug('after the call to sh command to install dependencies')
      debug(rmsg)
      response = jsonParse( rmsg )
      debug('after the parsing of rmsg') installAllDependencies
      debug('-----------------------------------------------------')
      debug('response == ')
      debug(response)
      debug('------------------------------------------------------')
    }
    catch (ex) {
      debug( 'catch section of toolbox:apex:codecoverage:install' )
      debug( "${ex}")
      debug('------------------------------------------------------')
      // printf ex
      debug('------------------------------------------------------')
    }    
    
    // if ( rmsg.isEmpty() ) {
    //   // then this means that the toolbox plugin has not been installed on this server.
    //   installRequiredCLIPlugins()
    //   _.echo ("retrying the toolbox:package:dependencies:install command")
    //   rmsg = _.sh returnStdout: true, script: commandScriptString
    // }
  
   

    if (response.status != 0) {
      _.echo rmsg
      _.error "package dependency installed failed -- ${response.message}"
    }
    
  }

  private void installRequiredCLIPlugins() {
      _.echo ("installing the @dx-cli-toolbox/sfdx-toolbox-package-utils plugin")
      def rmsgToolboxPackageUtilsInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install @dx-cli-toolbox/sfdx-toolbox-package-utils"
      _.echo rmsgToolboxPackageUtilsInstall

      _.echo ("installing the @dx-cli-toolbox/sfdx-toolbox-utils plugin")
      def rmsgToolboxUtilsInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install @dx-cli-toolbox/sfdx-toolbox-utils"
      _.echo rmsgToolboxUtilsInstall

      _.echo ("installing the sfdmu plugins")
      def rmsgSFDMUInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install sfdmu"
      _.echo rmsgSFDMUInstall

      // _.echo ("installing the shane-sfdx-plugins  plugins")
      // def rmsgShaneSFDXPluginInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install shane-sfdx-plugins "
      // _.echo rmsgShaneSFDXPluginInstall

      // _.echo ("installing the sfpowerkit plugins")
      // def rmsgSFPowerKitInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install sfpowerkit"
      // _.echo rmsgSFPowerKitInstall
  }

  private void setupCommunityIfNeeded() {

    if (this.isCreatingCommunity) {
      _.echo("Creating community ${this.communityName}...")
      def rmsg = _.sh returnStdout: true, script: "sfdx force:community:create --name \"${this.communityName}\" --urlpathprefix ${this.urlPathPrefix} --templatename \"${this.templateName}\" --json --targetusername ${this.sfdxScratchOrgAlias}"
      
      def response = jsonParse( rmsg )

      if (response.status != 0) {
          _.error "community creation failed -- ${response.message}"
      }

      // wait some time to allow the community setup to complete
      _.sleep(45)
    }
  }

  private void publishCommunityIfNeeded() {
    if (this.isPublishingCommunity) {
      _.echo("Publish community ${this.communityName}...")
  
      try {
        _.sh script: "sfdx force:community:publish --name \"${this.communityName}\" --json --targetusername ${this.sfdxScratchOrgAlias} > ${this.workingArtifactDirectory}/force-community-publish.json"
        
        // wait some time to allow the community publishing to complete
        _.sleep(45)
      } 
      catch(ex) {
        debug( 'catch section of force:community:publish' )

        _.echo(ex.getMessage())

        sendSlackMessage(
            color: 'danger',
            message: "${ex.getMessage()}",
            isFooterMessage: true
          )

        def forceCommunityPublishResultsFileExists = _.fileExists "${this.workingArtifactDirectory}/force-community-publish.json"

        if ( forceCommunityPublishResultsFileExists ) {

          debug( 'before force-community-publish.json file read')
          def sourcePushResults = jsonParse( _.readFile("${this.workingArtifactDirectory}/force-community-publish.json") )
          debug( 'after force-community-publish.json file read')
          sendSlackMessage(
              color: 'danger',
              message: "${sourcePushResults}",
              isFooterMessage: true
            )

        }

        _.error( ex )
      }
    }

  }

  private void compileCode() {
    _.echo("Push To Scratch Org And Compile")
    def rmsg
    try {
      // _.echo( _.env.SFDX_JSON_TO_STDOUT )
      // rmsg = _.sh returnStdout: true, script: "sfdx force:source:push --forceoverwrite --json --targetusername ${this.sfdxScratchOrgAlias}"

      // def response = jsonParse( rmsg )
      // debug( response )

      // if (response.status != 0) {
      //     _.error "push failed -- ${response.message}"
      // }

      // debug( rmsg )
      // if ( rmsg > 0 ) {
      //   _.error('force:source:push failed')
      // }

      _.sh script: "sfdx force:source:push --forceoverwrite --json --wait 90 --targetusername ${this.sfdxScratchOrgAlias} > ${this.workingArtifactDirectory}/force-source-push.json"

    }
    catch(ex) {
      debug( 'catch section of force:source:push' )
      // debug( ex.getMessage() )
      // debug( 'does ex have a status?')

      // check if force-source-push.json exists
        // read the file as a JSON file
      
      processForceSourcePushFailure()      

      _.error( ex )
    } 
    finally {
      debug( 'finally section of force:source:push')
    }
  }

  private void processForceSourcePushFailure() {
    debug('processForceSourcePushFailure method called')

    def forceSourcePushResultsFileExists = _.fileExists "${this.workingArtifactDirectory}/force-source-push.json"

    if ( forceSourcePushResultsFileExists ) {

      // def sourcePushResultFile = _.findFiles( glob: "${this.workingArtifactDirectory}/force-source-push.json") 
      
      // _.echo(sourcePushResultFile) // doing this produces an exception
      // printf sourcePushResultFile
      // def sourcePushResults = _.readJSON file: "${sourcePushResultFile[0].path}", returnPojo: true
      debug( 'before force-source-push.json file read')
      def sourcePushResults = jsonParse( _.readFile("${this.workingArtifactDirectory}/force-source-push.json") )
      debug( 'after force-source-push.json file read')

      def sourcePushFailureDetails = "Compilation stage failed with error : ${sourcePushResults.name}\n\n"
      
      if ( 'DeployFailed'.equals(sourcePushResults.name)) {
        sourcePushFailureDetails += "Metadata that failed to compile:\n\n"
        debug('before sourcePushResults.result.each ')
        sourcePushResults.result.each { result -> 
          sourcePushFailureDetails += "* ${result.type} ${result.fullName} -- ${result.error}\n"
        }
        debug('after sourcePushResults.result.each and before sendSlackMessage call')
      } 
      else {
        sourcePushFailureDetails += sourcePushResults.message
      }
      
      // sourcePushFailureDetails += "```"

      _.echo(sourcePushFailureDetails)

      sendSlackMessage(
          color: 'danger',
          message: "${sourcePushFailureDetails}",
          isFooterMessage: true
        )
    }
    else {
      _.echo ('file force-source-push.json not found')
    }

  }

  private void executeUnitTests() {
    if ( _.findFiles( glob: '**/*Test.cls' ) ) {
      _.echo( 'Run All Local Apex Tests' )
      _.timeout(time: 120, unit: 'MINUTES') {
        
        def rmsg 
        def unitTestsHaveFailed = false
        
        try {
          debug('before the force:apex:test:run execution')
          rmsg = _.sh returnStdout: true, label: 'Executing force:apex:test:run...', script: "sfdx force:apex:test:run --testlevel RunLocalTests --outputdir ${this.workingArtifactDirectory} --resultformat tap --codecoverage --wait 60 --json --targetusername ${this.sfdxScratchOrgAlias}"
          // debug( rmsg )
          // if ( rmsg == 0 ) {
          //   unitTestsHaveFailed = false
          // }
          // else if ( rmsg == 100 ) {
          //   unitTestsHaveFailed = true
          // }
          // else {
          //   _.error('unexpected error : ' + rmsg)
          // }
          debug('after the force:apex:test:run execution')
          def response = jsonParse( rmsg )
          debug('after the jsonParse( rmsg ) execution')

          debug("response.status == ${response.status}")

          if ( response.status == 0 ) {
            debug('response.status == 0')
            unitTestsHaveFailed = false
          }
          else if ( response.status == 100 ) {
            debug('response.status == 100')
            unitTestsHaveFailed = true
          }
          else {
            debug('response.status is something else')
            _.error('unexpected error : ' + rmsg)
          }
        }
        catch (ex) {
          debug('exception from force:apex:test:run')
          debug(ex.getMessage())
          unitTestsHaveFailed = true

          // if (ex.status != 100 ) {
          //   // somehting is wrong
          //   _.echo(ex.getMessage())
          //   _.echo('Restarting unit test run')
          //   // remove the test files from the previous run
          //   _.fileOperations([_.folderDeleteOperation( this.workingArtifactDirectory ), _.folderCreateOperation( this.workingArtifactDirectory )])

          //   // execute all unit tests a second time.  There is a bug with snapshots and CMDT-based 
          //   //      Dependency injection and Apex Unit Tests.  The workaround is to simply
          //   //      re-run the unit tests again.
          //   rmsg = _.sh returnStdout: true, label: 'Executing force:apex:test:run...', script: "sfdx force:apex:test:run --testlevel RunLocalTests --outputdir ${this.workingArtifactDirectory} --resultformat junit --codecoverage --wait 60 --json --targetusername ${this.sfdxScratchOrgAlias}"
          // }
        }
        finally {
          debug('beginning of finally')
          collectTestResults()
          debug( "unitTestsHaveFailed == ${unitTestsHaveFailed}")
          if ( unitTestsHaveFailed ) {
            sendTestResultsBySlack()
            _.error('Apex Unit Tests Failed')
          }
        }
      }
    } else {
      _.echo( 'No local Apex Tests found.' )  
    }
  }

  private void assignPermissionSets() {
    if ( this.permissionSetsToAssign != null ) {
      _.echo ("assignPermissionSets is called")
      for ( aPermissionSetToAssign in this.permissionSetsToAssign ) {
        _.echo ("now assigning permission set '${aPermissionSetToAssign}'")
        try {
          def rmsg =  _.sh returnStdout: true, script: "sfdx force:user:permset:assign --permsetname ${aPermissionSetToAssign} --targetusername ${this.sfdxScratchOrgAlias} --json"
          def response = jsonParse( rmsg )

          _.echo("response object is")
          _.echo("${response}")

          if ( response.status != 0) {
            _.error( response )
          }
          if ( response.failures != null && response.failures.size() > 0 ) {
            _.error( response.failures[0].message )
          }
        }
        catch (ex) {
          _.echo('------------------------------------------------------')
          _.echo(ex.getMessage())
          _.echo('------------------------------------------------------')
          sendSlackMessage(
            color: 'danger',
            message: "Error assigning permission set ${aPermissionSetToAssign}. \n\n${ex.getMessage()}",
            isFooterMessage: true
          )
          _.error "Failed to assigning permission set ${aPermissionSetToAssign}" 
        }
      }
    }
  }

  private void evaluateTestResults() {
    debug('evaluateTestResults method called')
    if ( _.findFiles( glob: "${this.workingArtifactDirectory}/**/test-result-707*.json", excludes: "${this.workingArtifactDirectory}/**/test-result-707*-codecoverage.json") ) {
      def testResultFiles = _.findFiles( glob: "${this.workingArtifactDirectory}/**/test-result-707*.json", excludes: "${this.workingArtifactDirectory}/**/test-result-707*-codecoverage.json")

      try {
        // evaluate the test results
        _.sh script: "sfdx toolbox:apex:codecoverage:check --json -f ${testResultFiles[0].path} > ${this.workingArtifactDirectory}/toolbox-apex-codecoverage-check.json"

        def evaluationResults = _.readJSON file: "${this.workingArtifactDirectory}/toolbox-apex-codecoverage-check.json", returnPojo: true

        def evaluateTestResultsMessage = "Code coverage insufficient:\n\n----------------------------------\n"
        def warningSlackMessageShouldBeSent = false 

        _.echo("evaluationResults.result.coverage.org.coveredPercent == ${evaluationResults.result.coverage.org.coveredPercent}")

        if ( evaluationResults.result.coverage.org && !evaluationResults.result.coverage.org.success ) {
          evaluateTestResultsMessage += "Org Wide Code Coverage insufficient\n"
          evaluateTestResultsMessage += "    - Required percentage: ${evaluationResults.result.coverage.org.converageRequirementForOrg}\n"
          evaluateTestResultsMessage += "    - Current percentage: ${evaluationResults.result.coverage.org.coveredPercent}\n\n----------------------------------\n"

          warningSlackMessageShouldBeSent = true
        }

        if ( evaluationResults.result.coverage.classes && !evaluationResults.result.coverage.classes.success ) {
          evaluateTestResultsMessage += "Class Code Coverage insufficient\n"

          evaluationResults.result.coverage.classes.classDetailCoverage.each { classDetailInfo -> 
            if ( !classDetailInfo.success ) {
              evaluateTestResultsMessage += "    - The code coverage for ${classDetailInfo.name} is ${classDetailInfo.coveredPercent}% and less than the required minimum amount of ${evaluationResults.result.coverage.classes.converageRequirementForClasses}%\n"
            }
          }

          warningSlackMessageShouldBeSent = true
        }

        if ( warningSlackMessageShouldBeSent ) {
          sendSlackMessage(
            color: 'yellow',
            message: "${evaluateTestResultsMessage}",
            isFooterMessage: true
          )
        }

      } 
      catch(ex) {
        debug( 'catch section of toolbox:apex:codecoverage:check' )
        debug( "${ex}")

        def evaluationResults = _.readJSON file: "${this.workingArtifactDirectory}/toolbox-apex-codecoverage-check.json", returnPojo: true

        def evaluateTestResultsMessage = "Code coverage insufficient:\n\n"

        evaluationResults.actions.each { requestedAction -> 
          evaluateTestResultsMessage += "    - ${requestedAction}\n"  
        }

        sendSlackMessage(
          color: 'danger',
          message: "${evaluateTestResultsMessage}",
          isFooterMessage: true
        )

        debug( 'end of catch section of toolbox:apex:codecoverage:check' )

        _.error( ex )
      }
    }
  }

  private void collectTestResults() {
    _.echo( "Collect All Test Results")
    _.junit keepLongStdio: true, testResults: "${this.workingArtifactDirectory}/**/*-junit.xml"
  }

  private void sendTestResultsBySlack() {
    debug('sendTestResultsBySlack method called')

    if ( _.findFiles( glob: "${this.workingArtifactDirectory}/test-result-707*.json" ) ) {

      def testRunId = _.readFile( file: "${this.workingArtifactDirectory}/test-run-id.txt" )
      debug("testRunId == ${testRunId}")

      // def testResultFiles = _.findFiles( glob: "${this.workingArtifactDirectory}/**/test-result-707*.json" )
      def testResultFiles = _.findFiles( glob: "${this.workingArtifactDirectory}/test-result-${testRunId}.json" )
      debug(testResultFiles[0].name)
      debug(testResultFiles[0].path)

      def testResults = _.readJSON file: "${testResultFiles[0].path}", returnPojo: true

      debug("testResults.summary.failing == ${testResults.summary.failing}")
      if ( testResults.summary.failing > 0 ) {
        _.echo( "${testResults.summary.failing} failed tests" )

        def testFailureDetails = "Apex Unit Tests that failed include:\n\n```"

        testResults.tests.each { test -> 
          if ( test.Outcome.equals('Fail') ) {
            testFailureDetails += "${test.FullName}\n"
            testFailureDetails += "    - message: ${test.Message}\n"
            testFailureDetails += "    - stacktrace: ${test.StackTrace}\n\n--------------------------\n"
          }
        }

        testFailureDetails += "```"
        // sendSlackMessage(
        //   color: 'danger',
        //   message: "Apex Unit Test Results <@here>",
        //   isFooterMessage: true,
        //   fileToSend: "${this.workingArtifactDirectory}/**/test-result-707*.json"
        // )
        sendSlackMessage(
          color: 'danger',
          message: "${testFailureDetails}",
          isFooterMessage: true
        )
      }
    }
  }

  private void packageTheProject() {
    // if " not alwaysBuildPackage and releaseBranchList does not contain current branch name" or "doNotBuildPackage is true"....
    if ( ( ! this.alwaysBuildPackage 
              && ! this.releaseBranchList.contains(_.env.BRANCH_NAME) )
        || this.doNotBuildPackage
        ) {
      // then exit out of the packaging process
      return
    }
    _.echo('Starting packaging process')

    def pathToUseForPackageVersionCreation

    // What is the default package and what is its directory?
    for ( packageDirectory in this.sfdxPackage.packageDirectories ) {
      _.echo("packageDirectory == ${packageDirectory}")
      if ( packageDirectory.default ) {
          _.echo("packageDirectory is default")
          pathToUseForPackageVersionCreation = packageDirectory.path 

          if (packageDirectory.package == null) {
            // there is no package specified in the this.sfdxPackage.  Simple exit out of this method
            _.echo('No package information configured on this project.')
            return
          }

          this.sfdxNewPackage = this.resolveAliasToId( packageDirectory.package, this.sfdxPackage )
          break 
      }
    }

    if ( this.sfdxNewPackage == null ) {
      _.error  "unable to determine this.sfdxNewPackage in stage:package"
    }

    if ( pathToUseForPackageVersionCreation == null ) {
      _.error  "unable to determine pathToUseForPackageVersionCreation in stage:package"
    }

    def commandScriptString = "sfdx force:package:version:create --path ${pathToUseForPackageVersionCreation} --json --codecoverage --tag ${this.buildGITCommitHash} --versiondescription ${this.buildTagName} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"

/*
    GOAL: FEATURE: treat “rc/*” branches the same as “main” for package version builds

    There are at least two questions:
    - What branches should automatically build packages on?
      --- variable  this.branchesToBuildPackageFromList
    - What branches should be considered "release branches" and thus should have the branch tag as null during the package:version:create phase?
      --- new variable this.releaseBranchList
XXXXXXXX -- private def this.releaseBranchList == this.branchesToBuildPackageFromList
    
    
XXXXXXXX Change current "releaseBranchesShouldBeTreatedAsNull" variable to be "releaseBranchesShouldBeTreatedAsNull"

    "release branches" is subset of "branches to always build package from"
    

XXXXXXXX There should be a flag to set that says "release branches should have package version create branch set to null"
    - 

    Default branches include "main" and "master"
    - How do I include other branches?
      - JenkinsFile setter? *****
      - sfdx-project.json attribute?
        - this would only have relevance with the toolbox plugin
XXXXXXXX - Setter == designateAsReleaseBranch('foobar')
      - sets the value to the this.releaseBranchList
      - should allow for regex here
        - How will this be managed?
          - In the setter method, if it is a regex expression, it should be evaluated against _.env.BRANCH_NAME.  If it is a match, then _.env.BRANCH_NAME should be added to this.releaseBranchList
          - In the setter method, ignore entries of "master" and "main" because they are already part of this.releaseBranchList
    

*/

    // use the branch command flag only when the branch is not "master" or when it is "master" and the environment is not set to operate as "master == null"
    // if ( (_.env.BRANCH_NAME != 'master' && _.env.BRANCH_NAME != 'main') || ( (_.env.BRANCH_NAME == 'master' || _.env.BRANCH_NAME == 'main') && !this.releaseBranchesShouldBeTreatedAsNull ) ) {
    //   commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    // }

    if ( !this.releaseBranchList.contains(_.env.BRANCH_NAME)
       || ( this.releaseBranchList.contains(_.env.BRANCH_NAME)
          && !this.releaseBranchesShouldBeTreatedAsNull 
          ) 
        ) 
    {
      debug( 'using the branch name for the package version create' )
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    } else {
      debug( 'NOT USING branch name for the package version create' )
    }


    if ( this.packageInstallationKey == null ) {
      commandScriptString = commandScriptString + ' --installationkeybypass'
    } else {
      commandScriptString = commandScriptString + " --installationkey '${this.packageInstallationKey}'"
    }

    _.echo ("commandScriptString == ${commandScriptString}")

    def rmsg = _.sh returnStdout: true, script: commandScriptString

    def packageVersionCreationResponse = jsonParse(rmsg)

    if ( packageVersionCreationResponse.status != 0 ) {
        _.echo( packageVersionCreationResponse )
        _.error "package version creation has failed -- ${packageVersionCreationResponse.result.Error}"
    } else {

        this.packageVersionCreationResponseResult = packageVersionCreationResponse.result

        if( this.packageVersionCreationResponseResult.Status != 'Success') {
            // The package version creation is still underway
            def packageVersionCreationCheckResponseResult = ''

            _.echo("To check on package version creation -- sfdx force:package:version:create:report --packagecreaterequestid ${this.packageVersionCreationResponseResult.Id} ")

            _.timeout(360) {
                _.waitUntil {
                    // script {
                        // use the this.packageVersionCreationResponseResult.Id for this command verses this.sfdxNewPackageVersionId because we are yet
                        //  certain that the package was created correctly
                        def isPackageVersionCreationCompleted = false
                        def packageVersionCreationCheckResponse 
                        try {
                          rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:create:report --packagecreaterequestid ${this.packageVersionCreationResponseResult.Id} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
                          // printf rmsg

                          packageVersionCreationCheckResponse = jsonParse(rmsg) 

                          _.echo ("packageVersionCreationCheckResponse.status == ${packageVersionCreationCheckResponse.status}")

                          if ( packageVersionCreationCheckResponse.status != 0 ) {
                            _.error "force:package:version:create:report failed -- ${packageVersionCreationCheckResponse.message}"
                          }

                          // _.echo ("packageVersionCreationCheckResponse == ${packageVersionCreationCheckResponse}")

                          // The JSON "result" is currently an array.  That is a SFDX bug -- W-4621618
                          // Refer to Salesforce DX Success Community post for details https://success.salesforce.com/0D53A00003OTsAD
                          this.packageVersionCreationResponseResult = packageVersionCreationCheckResponse.result[0]
                          
                          if ( packageVersionCreationCheckResponse.status != 0 || this.packageVersionCreationResponseResult.Status == 'Error' ) {
                            _.echo ("packageVersionCreationCheckResponse == ${packageVersionCreationCheckResponse}")
                            _.error "force:package:version:create:report failed -- ${this.packageVersionCreationResponseResult.Error}"
                          }


                          // _.echo ( "this.packageVersionCreationResponseResult.Status == ${this.packageVersionCreationResponseResult.Status}" )
                          if ( packageVersionCreationCheckResponse.status == 0 
                              && this.packageVersionCreationResponseResult.Status == "Success") {
                              isPackageVersionCreationCompleted = true 
                          }
                          _.echo( "Current status == ${this.packageVersionCreationResponseResult.Status}")

                        }
                        catch (ex) {
                          _.echo('------------------------------------------------------')
                          _.echo(ex.getMessage())
                          _.echo('------------------------------------------------------')

                          sendSlackMessage(
                            color: '#FFA500',
                            message: "Packaging packagecreaterequestid: ${this.packageVersionCreationResponseResult.Id}"
                          )

                          // if ( packageVersionCreationCheckResponse.status != 0 ) {
                          //   _.error "force:package:version:report failed -- ${packageVersionCreationCheckResponse.message}"
                          // }
                          sendSlackMessage(
                            color: '#FFA500',
                            message: "${packageVersionCreationCheckResponse}",
                            isFooterMessage: true
                          )
                          sendSlackMessage(
                            color: '#FFA500',
                            message: "${ex.getMessage()}",
                            isFooterMessage: true
                          )
                          sendSlackMessage(
                            color: '#FFA500',
                            message: "${ex}",
                            isFooterMessage: true
                          )
                          _.error ex
                        }
                        
                        // _.echo( "packageVersionCreationResponse == ${packageVersionCreationResponse}")
                        // _.echo( "packageVersionCreationResponse first result == ${packageVersionCreationCheckResponse.result[0]}")

                        return isPackageVersionCreationCompleted
                    // } // script
                }
                _.echo("Exited the waitUntil phase")
            }
            _.echo("Exited the timeout phase")
        }
    }
    _.echo( "Exited the creation/check phase")
    // failure point is probably in this area

    this.sfdxNewPackageVersionId = this.packageVersionCreationResponseResult.SubscriberPackageVersionId

    // tagging the build
    tagTheBuild()

    _.echo( "this.sfdxNewPackageVersionId == ${this.sfdxNewPackageVersionId}")

    captureCodeCoverage()
  }

  private void captureCodeCoverage() {
    _.echo("Discovering code coverage for sfdxNewPackageVersionId == ${this.sfdxNewPackageVersionId}")

    def rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:report --package ${this.sfdxNewPackageVersionId} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
    
    def packageVersionReportResponse = jsonParse(rmsg) 

    if ( packageVersionReportResponse.status != 0 ) {
      _.error "force:package:version:report failed -- ${packageVersionReportResponse.message}"
    }

    _.echo ("packageVersionReportResponse == ${packageVersionReportResponse}")
    // _.echo ("packageVersionReportResponse.result == ${packageVersionReportResponse.result}")
    // _.echo ("packageVersionReportResponse.result.CodeCoverage == ${packageVersionReportResponse.result.CodeCoverage}")
    // _.echo ("packageVersionReportResponse.result.CodeCoverage.apexCodeCoveragePercentage == ${packageVersionReportResponse.result.CodeCoverage.apexCodeCoveragePercentage}")

    this.packageCodeCoverage = packageVersionReportResponse.result.CodeCoverage.apexCodeCoveragePercentage
                        
  }

  private void tagTheBuild() {
    _.echo("Tagging the build as '${_.env.BUILD_TAG}'")

//  for this to work, the GIT identiy must be established.  The following commands need to be run
      // git config user.email "you@example.com"
      // git config user.name "Your Name"
//     _.sh returnStdout: true, script: "git tag -m '${_.env.BUILD_TAG}' ${_.env.BUILD_TAG} "

    // _.sh returnStdout: true, script: "git push --tags"
    _.echo('Tagging successful')
  }

  private void archiveAllBuildArtifacts() {
    _.echo("finding all package versions dependencies and recording them for the build")

    // Get the list of package versions that are currently installed in the default scratch org
    def rmsg
    def jsonParsedResponse
    try {
      rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${this.sfdxScratchOrgAlias}"
      _.echo( rmsg )
      if ( rmsg != null ) {
        jsonParsedResponse = jsonParse(rmsg)
        _.echo("jsonParsedResponse.exitCode == " + jsonParsedResponse.exitCode)
        _.echo("jsonParsedResponse.name == " + jsonParsedResponse.name)
      }
      if ( rmsg == null || (jsonParsedResponse != null && jsonParsedResponse.exitCode == 1 && jsonParsedResponse.name.equals("QUERY_TIMEOUT") ) ) {
        _.echo("Sleeping for 2 minutes and will try again")
        _.sleep time: 1, unit: 'MINUTES'
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${this.sfdxScratchOrgAlias}"
        jsonParsedResponse = jsonParse(rmsg)
      }
    }
    catch (ex) {
      _.echo( ex.getMessage() )
      _.echo( rmsg )
      if ( rmsg != null ) {
        jsonParsedResponse = jsonParse(rmsg)
        _.echo("jsonParsedResponse.exitCode == " + jsonParsedResponse.exitCode)
        _.echo("jsonParsedResponse.name == " + jsonParsedResponse.name)
      }
      if ( rmsg == null || (jsonParsedResponse != null && jsonParsedResponse.exitCode == 1 && jsonParsedResponse.name.equals("QUERY_TIMEOUT") ) ) {
        _.echo("Sleeping for 2 minutes and will try again")
        _.sleep time: 1, unit: 'MINUTES'
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${this.sfdxScratchOrgAlias}"
        jsonParsedResponse = jsonParse(rmsg)
      }

    }

    def allPackageVersionsInstalledInScratchOrg = jsonParsedResponse.result

    // Get the complete list of package versions that are currently available in the DevHub
    rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:list --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
    def allPackageVersionsAvailableInDevHub = jsonParse(rmsg).result

    def packageVersion

    for ( packageVersionsInstalledInScratchOrg in allPackageVersionsInstalledInScratchOrg ) {
        _.echo("packageVersionsInstalledInScratchOrg == ${packageVersionsInstalledInScratchOrg}")
        
        packageVersion = resolvePackageVersion( packageVersionsInstalledInScratchOrg, allPackageVersionsAvailableInDevHub )

        _.echo("dependency packageVersion resolved == ${packageVersion}")

        recordPackageVersionArtifact ( packageVersion )
    }

    // This is where the new package version that was just created will be captured as an artifact for Jenkins
    // this will be where the fingerprints of the build are created and then stored in Jenkins
    if ( this.sfdxNewPackageVersionId != null ) {

        // then a package was created.  Record its finger prints
        _.echo("finding all package versions for package ids found")
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:list --packages ${this.sfdxNewPackage} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
        //printf rmsg

        def response = jsonParse( rmsg )
        
        def allPackageVersionsAvailable = response.result

        // loop through all allPackageVersionsAvailable until you find the new one with the this.sfdxNewPackageVersionId
        for ( packageVersionAvailable in allPackageVersionsAvailable ) {
            _.echo ("packageVersionAvailable == ${packageVersionAvailable}")
            _.echo ("this.sfdxNewPackage == ${this.sfdxNewPackage}")
            _.echo ("packageVersionAvailable.Package2Id == ${packageVersionAvailable.Package2Id}")
            _.echo ("this.sfdxNewPackageVersionId == ${this.sfdxNewPackageVersionId}")
            _.echo ("packageVersionAvailable.SubscriberPackageVersionId == ${packageVersionAvailable.SubscriberPackageVersionId}")
            if ( this.sfdxNewPackage == packageVersionAvailable.Package2Id && this.sfdxNewPackageVersionId == packageVersionAvailable.SubscriberPackageVersionId) {
                _.echo ("found a match")
                recordPackageVersionArtifact( packageVersionAvailable )

                this.sfdxNewPackageVersion = packageVersionAvailable

                break
            }
        }
    }
    
    _.archiveArtifacts allowEmptyArchive: true, artifacts: "${this.workingArtifactDirectory}/*.packageVersion", fingerprint: true, onlyIfSuccessful: true
  }

  // @NonCPS
  private Object jsonParse(def json) {
      new groovy.json.JsonSlurperClassic().parseText(json)
  }

  // @NonCPS
  private String resolveAliasToId( def alias, def sfdxPackage ) {
    _.echo("resolveAliasToId starting")
    _.echo("alias == ${alias}")
    if ( alias.empty || sfdxPackage == null || sfdxPackage.packageAliases == null ) {
      return null
    }
    for ( packageAliasKey in sfdxPackage.packageAliases.keySet() ) {
        _.echo("packageAliasKey == ${packageAliasKey}")
        // _.echo("packageAlias.containsKey(alias) == ${packageAlias.containsKey(alias)}")
        if ( alias == packageAliasKey ) {
            _.echo ("found a match")
            return sfdxPackage.packageAliases.get(alias)
        }
    }
  }

  private void recordPackageVersionArtifact( def packageVersion ) {
    def fileToFingerprint = "${this.workingArtifactDirectory}/${packageVersion.Package2Name.replaceAll(" ", "_")}-${packageVersion.Package2Id}--v${packageVersion.Version}"

    _.echo("packageVersion == ${packageVersion}")

    if ( packageVersion.Branch != null ) {
        fileToFingerprint += "-branch-${packageVersion.Branch.replaceAll("/", "-")}"
    } 

    fileToFingerprint += "-${packageVersion.SubscriberPackageVersionId}.packageVersion"
    
    _.echo("creating package version artifact for ${fileToFingerprint}")

    _.writeFile file: fileToFingerprint, text: "${packageVersion}"
  }

  private Object resolvePackageVersion( def packageVersionsInstalledInScratchOrg, def allPackageVersionsAvailableInDevHub ) {

    def result // this will be the allPackageVersionsAvailableInDevHub structure mentioned above.

    for ( packageVersionAvailableInDevHub in allPackageVersionsAvailableInDevHub ) {
        // _.echo ("packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId == ${packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId}")
        // _.echo ("packageVersionAvailableInDevHub.SubscriberPackageVersionId == ${packageVersionAvailableInDevHub.SubscriberPackageVersionId}")
        if ( packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId != null && packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId.equals(packageVersionAvailableInDevHub.SubscriberPackageVersionId) ) {
            result = packageVersionAvailableInDevHub
            break
        }
    } 
    
    // if packageVersionsInstalledInScratchOrg is not found in DevHub, then assemble as much of a response as possible.
    if ( result == null ) {
        result = [:]
        result.Package2Id = packageVersionsInstalledInScratchOrg.SubscriberPackageId
        result.Branch = null
        result.Version = packageVersionsInstalledInScratchOrg.SubscriberPackageVersionNumber
        result.SubscriberPackageVersionId = packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId
        result.Package2Name = packageVersionsInstalledInScratchOrg.SubscriberPackageName
        result.NamespacePrefix = packageVersionsInstalledInScratchOrg.SubscriberPackageNamespace
    }

    _.echo ("result = ${result}")

    // the last line works as the return value
    return result
  }

  private void executeDataLoads() {
    if ( this.dataLoadsToProcess != null ) {
      _.echo ("executeDataLoads is called")
      for ( aDataLoadToProcess in this.dataLoadsToProcess ) {
        _.echo ("now processing data load folder '${aDataLoadToProcess}'")
        try {
          def rmsg =  _.sh returnStdout: true, script: "sfdx sfdmu:run --sourceusername csvfile --path ${aDataLoadToProcess} --targetusername ${this.sfdxScratchOrgAlias} --json --quiet"

          _.echo('_______________________________________________________')
          _.echo('raw message returned __________________________________')
          _.echo(rmsg)
          _.echo('_______________________________________________________')
          _.echo('before the jsonParse')
          def response = jsonParse( rmsg )
          _.echo('after the jsonParse')

          if ( response.status != 0) {
            _.error( response )
          }

          // Are any SFDMU report files present?
          def missingParentRecordsReportFileExists = _.fileExists "${aDataLoadToProcess}/MissingParentRecordsReport.csv"

          if ( missingParentRecordsReportFileExists ) {
            _.echo('missingParentRecordsReportFile exists')
            // sendSlackMessage(
            //   color: 'danger',
            //   message: "${testFailureDetails}",
            //   isFooterMessage: true
            // )
            _.error("MissingParentRecordsReport.csv report was found")
          }

          def csvIssuesReportFileExists = _.fileExists "${aDataLoadToProcess}/CSVIssuesReport.csv"
          if ( csvIssuesReportFileExists ) {
            _.echo('csvIssuesReportFile exists')
            // sendSlackMessage(
            //   color: 'danger',
            //   message: "${testFailureDetails}",
            //   isFooterMessage: true
            // )
            _.error("CSVIssuesReport.csv report was found")
          }

        }
        catch (ex) {
          _.echo('------------------------------------------------------')
          // _.echo('mark F')
          _.echo(ex.getMessage())
          // _.echo('mark G')
          _.echo('------------------------------------------------------')
          sendSlackMessage(
            color: 'danger',
            message: "Error with data load of ${aDataLoadToProcess} folder. \n\n${ex.getMessage()}",
            isFooterMessage: true
          )
          _.error "Failed to load ${aDataLoadToProcess}" 
        }
      }
    } else {
      _.echo('No data loads requested')
    }
  }


          //  THIS DEFINITELY WORKS 
          // _.pipelineTriggers(
          //   [
          //     _.upstream(	
          //       upstreamProjects: "someUpstreamBuildProject/" + _.env.BRANCH_NAME.replaceAll("/", "%2F"),  threshold: hudson.model.Result.SUCCESS	
          //     )
          //   ]
          // )
  private Object processProjectTriggers() {
    def result = []

    if ( this.upstreamProjectsToTriggerFrom != null ) {

      def upstreamProjectName
      def projectNamesStrings = []
      def fullStringOfProjectNames 

      for ( anUpstreamProjectToTriggerFrom in this.upstreamProjectsToTriggerFrom ) {
        if ( !anUpstreamProjectToTriggerFrom.empty ) {
          upstreamProjectName = (this.upstreamProjectsToTriggerFromPrefix != null ? (this.upstreamProjectsToTriggerFromPrefix + '/') : '') + anUpstreamProjectToTriggerFrom
          // _.echo("adding upstream dependency on project ${anUpstreamProjectToTriggerFrom}")
          // result << _.upstream(	upstreamProjects: upstreamProjectName + "/" + _.env.BRANCH_NAME.replaceAll("/", "%2F"),  threshold: hudson.model.Result.SUCCESS )

          if ( anUpstreamProjectToTriggerFrom.contains('/') ) {
            // ...then the anUpstreamProjectToTriggerFrom specifies both the project and the branch
            projectNamesStrings.add( upstreamProjectName )
          } else {
            // ...the anUpstreamProjectToTriggerFrom specifies just the branch and the env.BRANCH_NAME needs to be appended.
            projectNamesStrings.add( upstreamProjectName + "/" + _.env.BRANCH_NAME.replaceAll("/", "%2F") )
          }
        }
      } 

      fullStringOfProjectNames = projectNamesStrings.join(',')
      debug('processProjectTriggers')
      debug(fullStringOfProjectNames)
      result << _.upstream(	upstreamProjects: fullStringOfProjectNames,  threshold: hudson.model.Result.SUCCESS )

    }

    return result
  }        

  private void debug( def message ) {
    if ( loggingDebugMessages ) {
      _.echo("DEBUG: ${message}")
    }
  }
}
