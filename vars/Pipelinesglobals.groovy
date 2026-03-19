// vars/pipelinesglobals.groovy
def getAccountID(String environment){
    switch(environment) { 
        case 'dev': 
            return "315069654700"
        case 'qa':
            return "315069654700"
        case 'uat':
            return "315069654700"
        case 'pre-prod':
            return "315069654700"
        case 'prod':
            return "315069654700"
        default:
            error "Invalid environment: ${environment}"
    } 
}

def getRegion(String environment){
    switch(environment) {
        case 'dev':
        case 'qa':
        case 'uat':
        case 'pre-prod':
            return "us-east-1"
        case 'prod':
            return "us-east-1"
        default:
            error "Invalid environment: ${environment}"
    }
}

def getEcrRepo(String project, String environment, String component){
    return "${project}/${environment}/${component}"
}

def getCreds(String environment){
    return "aws-creds-${environment}"
}

// Optional: helper to standardize image tags
def getImageTag(String version){
    if(!version?.trim()){
        error "IMAGE_TAG cannot be empty"
    }
    return version.trim()
}

// Optional: validate environment
def validateEnv(String environment){
    def allowed = ['dev', 'qa', 'uat', 'pre-prod', 'prod']
    if(!allowed.contains(environment)){
        error "Environment ${environment} not valid. Allowed: ${allowed}"
    }
    return environment
}
