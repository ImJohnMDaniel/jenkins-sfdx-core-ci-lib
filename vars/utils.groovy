#!groovy
import groovy.json.JsonSlurperClassic
// @NonCPS
def jsonParse(def json) {
    // echo( "Calling the bluefish jsonParse")
    new groovy.json.JsonSlurperClassic().parseText(json)
}

def call()
{
    sh "echo Hello World"
}

// @NonCPS
def recordPackageVersionArtifact( def packageVersion ) {
    def fileToFingerprint = "${RUN_ARTIFACT_DIR}/${packageVersion.Package2Name}-${packageVersion.Package2Id}--v${packageVersion.Version}"

    // echo("packageVersion == ${packageVersion}")

    if ( packageVersion.Branch != null ) {
        fileToFingerprint += "-branch-${packageVersion.Branch.replaceAll("/", "-")}"
    } 

    fileToFingerprint += "-${packageVersion.SubscriberPackageVersionId}.packageVersion"
    
    echo("creating package version artifact for ${fileToFingerprint}")

    writeFile file: fileToFingerprint, text: "${packageVersion}"
}

// @NonCPS
def resolveAliasToId( def alias, def SFDX_PROJECT ) {
    // echo("resolveAliasToId starting")
    // echo("alias == ${alias}")
    for ( packageAliasKey in SFDX_PROJECT.packageAliases.keySet() ) {
        // echo("packageAliasKey == ${packageAliasKey}")
        // echo("packageAlias.containsKey(alias) == ${packageAlias.containsKey(alias)}")
        if ( alias == packageAliasKey ) {
            // echo ("found a match")
            return SFDX_PROJECT.packageAliases.get(alias)
        }
    }
}

// @NonCPS
def resolvePackageVersion( def packageVersionsInstalledInScratchOrg, def allPackageVersionsAvailableInDevHub ) {

    def result // this will be the allPackageVersionsAvailableInDevHub structure mentioned above.

    for ( packageVersionAvailableInDevHub in allPackageVersionsAvailableInDevHub ) {
        // echo ("packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId == ${packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId}")
        // echo ("packageVersionAvailableInDevHub.SubscriberPackageVersionId == ${packageVersionAvailableInDevHub.SubscriberPackageVersionId}")
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

    echo ("result = ${result}")

    // the last line works as the return value
    return result
}