class SfdxProjectBuilder implements Serializable {

  private final def _ // the member variable that contains the jenkinsFileScript

  private def SFDX_SCRATCH_ORG_DEF_FILE = "config/project-scratch-def.json"

  // private def toolbelt

  private def RUN_ARTIFACT_DIR 

  private def SFDX_SCRATCH_ORG_ALIAS

  private def SFDX_NEW_PACKAGE

  private def SFDX_NEW_PACKAGE_VERSION

  private def SFDX_NEW_PACKAGE_VERSION_ID

  private def installationKeys

  private def packageInstallationKey

  private def alwaysBuildPackage = false

  private def doNotBuildPackage = false

  private def slackChannelName = '#ci-alerts'

  private def notifyOnSuccessfulBuilds = false 

  private def slackNotificationsIsActive = false

  private def scratchOrgWasCreated = false

  private def scratchOrgShouldBeDeleted = true

  private def branchesToBuildPackageFromList = ['master', 'devops/master-2gmp-variant']

  private def upstreamProjectsToTriggerFrom = []

  private boolean dependencyBuildsBranchMasterAndBranchNullAreTheSame = true

  // the parsed contents of the SFDX project's configuration
  private def SFDX_PROJECT

  SfdxProjectBuilder(def jenkinsFileScript) {
    _ = jenkinsFileScript
    // initializeBuildClass() // if you call the private method from the constructor, it has to be @NonCPS annotated
  }

  public void execute() {
    initializeBuildClass()
    _.node {
      // checkout the main source code for the project.
      _.checkout _.scm
      // start the pipeline
      _.pipeline {
        _.agent {
          _.docker { image 'salesforce/salesforcedx' }
        }
        
        _.properties([
          // ensure that concurrent builds on the same project is not possible
          _.disableConcurrentBuilds(),
          // 
          _.buildDiscarder(_.logRotator(numToKeepStr: '5')),

          _.pipelineTriggers(
            processProjectTriggers()
          )
          
        ])
        //this.toolbelt = _.tool 'sfdx-toolbelt'

        // _.stages {
        try {
          _.stage('Validate') {
            validateStage()          
          }
          _.stage('Initialize') {
            // _.steps { // apparently not needed in a script
            initializeStage()
            // } // steps 
          }  // stage: Initialize

          _.stage('Process Resources') {
            processResourcesStage()
          } // stage: Process Resources

          _.stage('Compile') {
            compileStage()
          } // stage: Compile

          _.stage('Test') {
            testStage()
          } // stage: Test

          _.stage('Package') {
            packageStage()
          } // stage: Package

          _.stage('Artifact Recording') {
            artifactRecordingStage()
          } // stage: Artifact Recording

          postSuccess()
        }
        catch (ex) {
          postFailure(ex)
        }
        finally {
          postAlways()
        }
        //} // stages
      } // pipeline
    } // node
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
       this.SFDX_SCRATCH_ORG_DEF_FILE = scratchOrgDefFile
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

  public SfdxProjectBuilder alwaysNotifyOnSuccess() {
    this.notifyOnSuccessfulBuilds = true
    _.echo('SfdxProjectBuilder Parameter set : Notify on successful builds')
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

  // vo id setBuildDescription(Map args) {
  //   jenkinsFileScript.currentBuild.displayName = args.title
  //   jenkinsFileScript.currentBuild.description = args.description
  // }

  void initializeStage() {
    // setup this build's unique artifact directory
    _.sh "mkdir -p ${RUN_ARTIFACT_DIR}"

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
  }

  void processResourcesStage() {
    installAllDependencies()
  }

  void compileStage() {
    compileCode()
  }

  void testStage() {
    // Give the code time to settle down before the unit tests begin
    _.sleep time: 2, unit: 'MINUTES'
    // need to a the parallel tage here along with PMD task

    // _.failFast true // this is part of the declarative syntax.  Is there an equivalent in the scripted model?
    
    // _.parallel { // not sure why this is not working.  Need to find equivalent in teh scripted model.
      executeUnitTests()
    // } // parallel
  }

  void packageStage() {
    packageTheProject()
  }

  void artifactRecordingStage() {
    archiveAllBuildArtifacts()
  }

  void postSuccess() {
    if ( this.notifyOnSuccessfulBuilds || ( _.currentBuild.previousBuild != null && _.currentBuild.resultIsBetterOrEqualTo( _.currentBuild.previousBuild.currentResult ) ) ) {
      sendSlackMessage(
        color: 'good',
        message: "Build completed ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)"
      )
    }
  }

  void postFailure(def ex) {
    _.echo(ex.getMessage())
  
    sendSlackMessage(
      color: 'danger',
      message: "Build failed ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)"
    )

  }

  void postAlways() {
    deleteScratchOrg()

    // temporary workaround pending resolution to this issue https://github.com/forcedotcom/cli/issues/81.  Also, see reference in authenticateToDevHub() method
    _.fileOperations([_.fileDeleteOperation(excludes: '', includes: 'server.key')])
  }

  private void sendSlackMessage(Map args) {
    if ( this.slackNotificationsIsActive ) {
      def slackResponse = _.slackSend channel: "${this.slackChannelName}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false, tokenCredentialId: "slack-integration-token-credential"
    } else {
      _.echo("Slack notifications are currently off")
    }

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
  }

  private void initializeBuildScriptVariables() {
    RUN_ARTIFACT_DIR = "target/${_.env.BUILD_NUMBER}"
    SFDX_SCRATCH_ORG_ALIAS = "bluesphere-${_.env.BUILD_TAG.replaceAll("/", "_")}"
    // _.echo("_.env.TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_AND_NULL_THE_SAME == ${_.env.TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_AND_NULL_THE_SAME}")
    if ( _.env.TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_AND_NULL_THE_SAME != null ) {
      // _.echo("TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_AND_NULL_THE_SAME is not null")
      this.dependencyBuildsBranchMasterAndBranchNullAreTheSame = _.env.TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_AND_NULL_THE_SAME.toBoolean()
      // _.echo("this.dependencyBuildsBranchMasterAndBranchNullAreTheSame == ${this.dependencyBuildsBranchMasterAndBranchNullAreTheSame}")
    // } else {
      //_.echo("TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_AND_NULL_THE_SAME is null")
    }
    // _.echo("this.dependencyBuildsBranchMasterAndBranchNullAreTheSame == ${this.dependencyBuildsBranchMasterAndBranchNullAreTheSame}")
  }

  private void readAndParseSFDXProjectFile() {
    _.echo('Deserialize the sfdx-project.json ')
    SFDX_PROJECT = jsonParse( _.readFile('sfdx-project.json') )
  }

  private void authenticateToDevHub() {
    _.echo('Authenticate to the Dev Hub ')
    // _.echo(_.env.JWT_CRED_ID_DH)
    _.withCredentials( [ _.file( credentialsId: _.env.JWT_CRED_ID_DH, variable: 'jwt_key_file') ] ) {
        // temporary workaround pending resolution to this issue https://github.com/forcedotcom/cli/issues/81
        _.sh returnStatus: true, script: "cp ${_.jwt_key_file} ./server.key"
        // _.fileOperations([_.fileCopyOperation(excludes: '', flattenFiles: false, includes: _.jwt_key_file, targetLocation: './server.key')])  // some issue with the masking of the file name.  Need to sort it out

        _.echo("Authenticating To Dev Hub...")
        // script {
        def rc = _.sh returnStatus: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST}"
        if (rc != 0) { _.error "hub org authorization failed" }
        // }
    }
  }

  private void createScratchOrg() {
    _.echo('Creating scratch org')

    def commandScriptString = "sfdx force:org:create --definitionfile ${this.SFDX_SCRATCH_ORG_DEF_FILE} --json --durationdays 1 --setalias ${SFDX_SCRATCH_ORG_ALIAS} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME} --wait 30"

    def response

    try {
      def rmsg = _.sh returnStdout: true, script: commandScriptString

      response = jsonParse( rmsg )
    }
    catch (ex) {
      _.echo('------------------------------------------------------')
      _.echo(ex.getMessage())
      _.echo('------------------------------------------------------')
      if (ex.getMessage().contains('OPERATION_TOO_LARGE')) {
        _.echo('exception message contains OPERATION_TOO_LARGE')
        _.echo('------------------------------------------------------')
      }
      _.echo(ex.printStackTrace())
      _.error('scratch org creation failed')
    }

    if (response.status != 0 ) {
      if (response.name.equals('genericTimeoutMessage')) {
        // try one more time to create the scratch org
        _.echo('Original attempt to create scratch org timed out.  Trying to create one again.')
        rmsg = _.sh returnStdout: true, script: commandScriptString
        response = jsonParse( rmsg )
        if ( response.status != 0 ) {
          _.error "Failed to create Scratch Org -- ${response.message}"
        }
      }
      else {
        _.error "Failed to create Scratch Org -- ${response.message}"
      }
    }
    this.scratchOrgWasCreated = true
  }

  private void deleteScratchOrg() {
    if (this.scratchOrgWasCreated && this.scratchOrgShouldBeDeleted) {
      _.echo('Deleting scratch org')
      def rc = _.sh returnStatus: true, script: "sfdx force:org:delete --noprompt --targetusername ${SFDX_SCRATCH_ORG_ALIAS} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
      if (rc != 0) { 
        _.error "deletion of scratch org ${SFDX_SCRATCH_ORG_ALIAS} failed"
      }
    }
  }

  private void installAllDependencies() {
    // _.echo("env.BRANCH_NAME == ${_.env.BRANCH_NAME}")
    // _.echo("this.dependencyBuildsBranchMasterAndBranchNullAreTheSame == ${this.dependencyBuildsBranchMasterAndBranchNullAreTheSame}")
    // if ( _.env.BRANCH_NAME == 'master' ) {
    //   _.echo('branch_name == master')
    // }
    // if ( _.env.BRANCH_NAME != 'master' ) {
    //   _.echo('branch_name != master')
    // }

    // if ( !this.dependencyBuildsBranchMasterAndBranchNullAreTheSame ) {
    //   _.echo('!this.dependencyBuildsBranchMasterAndBranchNullAreTheSame == true')
    // } else {
    //   _.echo('!this.dependencyBuildsBranchMasterAndBranchNullAreTheSame == false')
    // }

    // if ( _.env.BRANCH_NAME == 'master' && ( !this.dependencyBuildsBranchMasterAndBranchNullAreTheSame ) ) {
    //   _.echo('secondary condition true')
    // } else {
    //   _.echo('secondary condition false')
    // }

    // if ( _.env.BRANCH_NAME != 'master' || ( _.env.BRANCH_NAME == 'master' && !this.dependencyBuildsBranchMasterAndBranchNullAreTheSame ) ) {
    //   _.echo('complete condition true')
    // } else {
    //   _.echo('complete condition false')
    // }

    def commandScriptString = "sfdx toolbox:package:dependencies:install --wait 240 --targetusername ${SFDX_SCRATCH_ORG_ALIAS} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME} --json"
    
    if ( _.env.BRANCH_NAME != 'master' || ( _.env.BRANCH_NAME == 'master' && !this.dependencyBuildsBranchMasterAndBranchNullAreTheSame ) ) {
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    }

    if ( this.installationKeys != null ) {
      // 1:MyPackage1Key 2: 3:MyPackage3Key
      commandScriptString = commandScriptString + " --installationkeys '" + this.installationKeys + "'"
    }

    // _.echo ("commandScriptString == ${commandScriptString}")
    
    def rmsg = _.sh returnStdout: true, script: commandScriptString

    if ( rmsg.isEmpty() ) {
      // then this means that the toolbox plugin has not been installed on this server.
      installRequiredCLIPlugins()
      _.echo ("retrying the toolbox:package:dependencies:install command")
      rmsg = _.sh returnStdout: true, script: commandScriptString
    }
  
    def response = jsonParse( rmsg )

    if (response.status != 0) {
      _.echo rmsg
      _.error "package dependency installed failed -- ${response.message}"
    }
    
  }

  private void installRequiredCLIPlugins() {
      // echo y | sfdx plugin:install @dx-cli-toolbox/sfdx-toolbox-package-utils
      _.echo ("installing the toolbox plugins")
      def rmsgInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install @dx-cli-toolbox/sfdx-toolbox-package-utils"
  }

  private void compileCode() {
    _.echo("Push To Scratch Org And Compile")
    def rmsg = _.sh returnStdout: true, script: "sfdx force:source:push --json --targetusername ${SFDX_SCRATCH_ORG_ALIAS}"
    // printf rmsg

    def response = jsonParse( rmsg )

    if (response.status != 0) {
        _.error "push failed -- ${response.message}"
    }
  }

  private void executeUnitTests() {
    _.echo( 'Run All Local Apex Tests' )
    _.timeout(time: 120, unit: 'MINUTES') {
      // script {
      def rmsg 
      
      try {
        rmsg = _.sh returnStdout: true, label: 'Executing force:apex:test:run...', script: "sfdx force:apex:test:run --testlevel RunLocalTests --outputdir ${RUN_ARTIFACT_DIR} --resultformat tap --codecoverage --wait 60 --json --targetusername ${SFDX_SCRATCH_ORG_ALIAS}"
      }
      catch (ex) {
        _.echo(ex.getMessage())
        _.echo('Restarting unit test run')
        // remove the test files from the previous run
        _.fileOperations([_.folderDeleteOperation( RUN_ARTIFACT_DIR ), _.folderCreateOperation( RUN_ARTIFACT_DIR )])

        // execute all unit tests a second time.  There is a bug with snapshots and CMDT-based 
        //      Dependency injection and Apex Unit Tests.  The workaround is to simply
        //      re-run the unit tests again.
        rmsg = _.sh returnStdout: true, label: 'Executing force:apex:test:run...', script: "sfdx force:apex:test:run --testlevel RunLocalTests --outputdir ${RUN_ARTIFACT_DIR} --resultformat junit --codecoverage --wait 60 --json --targetusername ${SFDX_SCRATCH_ORG_ALIAS}"
      }
      finally {
        collectTestResults()
      }
      
      // Process all unit test reports
      def response = jsonParse( rmsg )
      if (response.status != 0) {
        _.echo(response)
        _.error "apex test run failed -- ${response.message}"
      }
      // } // script tag
    }
  }

  private void collectTestResults() {
    _.echo( "Collect All Test Results")
    _.junit keepLongStdio: true, testResults: "${RUN_ARTIFACT_DIR}/**/*-junit.xml"
  }

  private void packageTheProject() {
    if ( ( ! alwaysBuildPackage 
          && ! branchesToBuildPackageFromList.contains(_.env.BRANCH_NAME) )
          || doNotBuildPackage
          ) {
      return
    }
    _.echo('Starting packaging process')

    def pathToUseForPackageVersionCreation

    // What is the default package and what is its directory?
    for ( packageDirectory in SFDX_PROJECT.packageDirectories ) {
      _.echo("packageDirectory == ${packageDirectory}")
      if ( packageDirectory.default ) {
          _.echo("packageDirectory is default")
          pathToUseForPackageVersionCreation = packageDirectory.path 

          if (packageDirectory.package == null) {
            // there is no package specified in the SFDX_PROJECT.  Simple exit out of this method
            _.echo('No package information configured on this project.')
            return
          }

          SFDX_NEW_PACKAGE = this.resolveAliasToId( packageDirectory.package, SFDX_PROJECT )
          break 
      }
    }

    if ( SFDX_NEW_PACKAGE == null ) {
      _.error  "unable to determine SFDX_NEW_PACKAGE in stage:package"
    }

    if ( pathToUseForPackageVersionCreation == null ) {
      _.error  "unable to determine pathToUseForPackageVersionCreation in stage:package"
    }

    def commandScriptString = "sfdx force:package:version:create --path ${pathToUseForPackageVersionCreation} --json --codecoverage --tag ${_.env.BUILD_TAG.replaceAll(' ','-')} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"

    // use the branch command flag only when the branch is not "master" or when it is "master" and the environment is not set to operate as "master == null"
    if ( _.env.BRANCH_NAME != 'master' ||  (_.env.BRANCH_NAME == 'master' && !dependencyBuildsBranchMasterAndBranchNullAreTheSame) ) {
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    }

    if ( this.packageInstallationKey == null ) {
      commandScriptString = commandScriptString + ' --installationkeybypass'
    } else {
      commandScriptString = commandScriptString + " --installationkey '${this.packageInstallationKey}'"
    }

    _.echo ("commandScriptString == ${commandScriptString}")

    def rmsg = _.sh returnStdout: true, script: commandScriptString
    // printf rmsg

    def packageVersionCreationResponse = jsonParse(rmsg)

    // _.echo ("packageVersionCreationResponse == ${packageVersionCreationResponse}")

    if ( packageVersionCreationResponse.status != 0 ) {
        _.echo( packageVersionCreationResponse )
        _.error "package version creation has failed -- ${packageVersionCreationResponse.result.Error}"
    } else {

        SFDX_NEW_PACKAGE_VERSION = packageVersionCreationResponse.result

        if( SFDX_NEW_PACKAGE_VERSION.Status != 'Success') {
            // The package version creation is still underway
            def packageVersionCreationCheckResponseResult = ''

            _.timeout(360) {
                _.waitUntil {
                    // script {
                        // use the SFDX_NEW_PACKAGE_VERSION.Id for this command verses SFDX_NEW_PACKAGE_VERSION_ID because we are yet
                        //  certain that the package was created correctly
                        rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:create:report --packagecreaterequestid ${SFDX_NEW_PACKAGE_VERSION.Id} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
                        // printf rmsg

                        def packageVersionCreationCheckResponse = jsonParse(rmsg) 

                        if ( packageVersionCreationCheckResponse.status != 0 ) {
                          _.error "force:package:version:create:report failed -- ${packageVersionCreationCheckResponse.message}"
                        }

                        // _.echo ("packageVersionCreationCheckResponse == ${packageVersionCreationCheckResponse}")

                        // The JSON "result" is currently an array.  That is a SFDX bug -- W-4621618
                        // Refer to Salesforce DX Success Community post for details https://success.salesforce.com/0D53A00003OTsAD
                        SFDX_NEW_PACKAGE_VERSION = packageVersionCreationCheckResponse.result[0]
                        
                        if ( packageVersionCreationCheckResponse.status != 0 || SFDX_NEW_PACKAGE_VERSION.Status == 'Error' ) {
                          _.echo ("packageVersionCreationCheckResponse == ${packageVersionCreationCheckResponse}")
                          _.error "force:package:version:create:report failed -- ${SFDX_NEW_PACKAGE_VERSION.Error}"
                        }

                        def isPackageVersionCreationCompleted

                        // _.echo ( "SFDX_NEW_PACKAGE_VERSION.Status == ${SFDX_NEW_PACKAGE_VERSION.Status}" )
                        
                        if ( packageVersionCreationCheckResponse.status == 0 
                            && SFDX_NEW_PACKAGE_VERSION.Status == "Success") {
                            isPackageVersionCreationCompleted = true 
                        } else {
                            isPackageVersionCreationCompleted = false 
                        }
                        _.echo( "Current status == ${SFDX_NEW_PACKAGE_VERSION.Status}")

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
    // _.echo( "packageVersionCreationResponse == ${packageVersionCreationResponse}")

    SFDX_NEW_PACKAGE_VERSION_ID = SFDX_NEW_PACKAGE_VERSION.SubscriberPackageVersionId

    // tagging the build
    tagTheBuild()

    // _.echo( "SFDX_NEW_PACKAGE_VERSION == ${SFDX_NEW_PACKAGE_VERSION}")

    _.echo( "SFDX_NEW_PACKAGE_VERSION_ID == ${SFDX_NEW_PACKAGE_VERSION_ID}")
  }

  private void tagTheBuild() {
    _.echo("Tagging the build as '${_.env.BUILD_TAG}'")
    _.sh returnStdout: true, script: "git tag -m '${_.env.BUILD_TAG}' ${_.env.BUILD_TAG} "
    // _.sh returnStdout: true, script: "git push --tags"
    _.echo('Tagging successful')
  }

  private void archiveAllBuildArtifacts() {
    _.echo("finding all package versions dependencies and recording them for the build")

    // Get the list of package versions that are currently installed in the default scratch org
    def rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${SFDX_SCRATCH_ORG_ALIAS}"
    def allPackageVersionsInstalledInScratchOrg = jsonParse(rmsg).result

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
    if ( SFDX_NEW_PACKAGE_VERSION_ID != null ) {

        // then a package was created.  Record its finger prints
        _.echo("finding all package versions for package ids found")
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:list --packages ${SFDX_NEW_PACKAGE} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
        //printf rmsg

        def response = jsonParse( rmsg )
        
        def allPackageVersionsAvailable = response.result

        // loop through all allPackageVersionsAvailable until you find the new one with the SFDX_NEW_PACKAGE_VERSION_ID
        for ( packageVersionAvailable in allPackageVersionsAvailable ) {
            _.echo ("packageVersionAvailable == ${packageVersionAvailable}")
            _.echo ("SFDX_NEW_PACKAGE == ${SFDX_NEW_PACKAGE}")
            _.echo ("packageVersionAvailable.Package2Id == ${packageVersionAvailable.Package2Id}")
            _.echo ("SFDX_NEW_PACKAGE_VERSION_ID == ${SFDX_NEW_PACKAGE_VERSION_ID}")
            _.echo ("packageVersionAvailable.SubscriberPackageVersionId == ${packageVersionAvailable.SubscriberPackageVersionId}")
            if ( SFDX_NEW_PACKAGE == packageVersionAvailable.Package2Id && SFDX_NEW_PACKAGE_VERSION_ID == packageVersionAvailable.SubscriberPackageVersionId) {
                _.echo ("found a match")
                recordPackageVersionArtifact( packageVersionAvailable )
                break
            }
        }
    }
    
    _.archiveArtifacts allowEmptyArchive: true, artifacts: "${RUN_ARTIFACT_DIR}/*.packageVersion", fingerprint: true, onlyIfSuccessful: true
  }

  // @NonCPS
  private Object jsonParse(def json) {
      new groovy.json.JsonSlurperClassic().parseText(json)
  }

  // @NonCPS
  private String resolveAliasToId( def alias, def SFDX_PROJECT ) {
    _.echo("resolveAliasToId starting")
    _.echo("alias == ${alias}")
    if ( alias.empty || SFDX_PROJECT == null || SFDX_PROJECT.packageAliases == null ) {
      return null
    }
    for ( packageAliasKey in SFDX_PROJECT.packageAliases.keySet() ) {
        _.echo("packageAliasKey == ${packageAliasKey}")
        // _.echo("packageAlias.containsKey(alias) == ${packageAlias.containsKey(alias)}")
        if ( alias == packageAliasKey ) {
            _.echo ("found a match")
            return SFDX_PROJECT.packageAliases.get(alias)
        }
    }
  }

  private void recordPackageVersionArtifact( def packageVersion ) {
    def fileToFingerprint = "${RUN_ARTIFACT_DIR}/${packageVersion.Package2Name.replaceAll(" ", "_")}-${packageVersion.Package2Id}--v${packageVersion.Version}"

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

      for ( anUpstreamProjectToTriggerFrom in this.upstreamProjectsToTriggerFrom ) {
        if ( !anUpstreamProjectToTriggerFrom.empty ) {
          // _.echo("adding upstream dependency on project ${anUpstreamProjectToTriggerFrom}")
          result << _.upstream(	upstreamProjects: anUpstreamProjectToTriggerFrom + "/" + _.env.BRANCH_NAME.replaceAll("/", "%2F"),  threshold: hudson.model.Result.SUCCESS )
        }
      } 
    }

    return result
  }        

}
