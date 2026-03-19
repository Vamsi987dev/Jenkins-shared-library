def call(Map configMap){

    def AWS_REGION = configMap.AWS_REGION
    def ACCOUNT_ID = configMap.ACCOUNT_ID
    def PROJECT    = configMap.PROJECT
    def COMPONENT  = configMap.COMPONENT
    def GIT_URL    = configMap.GIT_URL
    def GIT_BRANCH = (configMap.BRANCH && configMap.BRANCH != "null") ? configMap.BRANCH : "main"
    def ENVIRONMENT = configMap.ENVIRONMENT ?: "dev"
    def CD_JOB     = configMap.CD_JOB ?: "${COMPONENT}-cd"

    pipeline {
        agent { label 'agent' }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        parameters {
            booleanParam(name: 'deploy', defaultValue: false, description: 'Trigger CD?')
        }

        environment {
            AWS_REGION = "${AWS_REGION}"
            ACCOUNT_ID = "${ACCOUNT_ID}"
            PROJECT    = "${PROJECT}"
            COMPONENT  = "${COMPONENT}"
            GIT_URL    = "${GIT_URL}"
            GIT_BRANCH = "${GIT_BRANCH}"
            ENV        = "${ENVIRONMENT}"

            ECR_REPO   = "${PROJECT}/${ENV}/${COMPONENT}"
            IMAGE_TAG  = ""
        }

        stages {

            stage('Checkout Code') {
                steps {
                    echo "Checking out branch: ${GIT_BRANCH}"
                    git branch: "${GIT_BRANCH}", url: "${GIT_URL}"
                }
            }
            // stage('SonarQube Scan') {
            //     environment {
            //         scannerHome = tool 'sonar-8.0'
            //     }
            //     steps {
            //         withSonarQubeEnv('sonar-8.0') {
            //             sh """
            //             ${scannerHome}/bin/sonar-scanner
            //             """
            //         }
            //     }
            // }
            // stage('Quality Gate') {
            //     steps {
            //         timeout(time: 5, unit: 'MINUTES') {
            //             waitForQualityGate abortPipeline: true
            //         }
            //     }
            // }

            stage('Prepare Version') {
                steps {
                    script {
                        if (!fileExists('package.json')) {
                            error "package.json not found. Failing pipeline."
                        }

                        def packageJson = readJSON file: 'package.json'

                        if (!packageJson.version?.trim()) {
                            error "Version missing in package.json. Failing pipeline."
                        }

                        def version = packageJson.version
                        env.IMAGE_TAG = version

                        echo "Building ${PROJECT}-${COMPONENT}"
                        echo "Environment: ${ENV}"
                        echo "Image Tag: ${version}"
                    }
                }
            }

            stage('Install Dependencies') {
                when {
                    expression { fileExists('package.json') }
                }
                steps {
                    sh 'npm install'
                }
            }

            stage('Login to ECR') {
                steps {
                    withAWS(region: "${AWS_REGION}", credentials: "aws-creds-${ENV}") {
                        sh '''
                        aws sts get-caller-identity

                        aws ecr get-login-password --region $AWS_REGION | \
                        docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com
                        '''
                    }
                }
            }

            stage('Ensure ECR Repo Exists') {
                steps {
                    sh '''
                    aws ecr describe-repositories --repository-names $ECR_REPO || \
                    aws ecr create-repository --repository-name $ECR_REPO
                    '''
                }
            }

            stage('Docker Build') {
                steps {
                    sh '''
                    docker system prune -f || true

                    docker build -t $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG .

                    docker tag $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG \
                               $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest
                    '''
                }
            }

            stage('Push Image to ECR') {
                steps {
                    sh '''
                    docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG
                    docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest
                    '''
                }
            }

            stage('Trigger CD Pipeline') {
                when {
                    allOf {
                        branch 'main'
                        expression { params.deploy }
                    }
                }
                steps {
                    build job: "${CD_JOB}", parameters: [
                        string(name: 'IMAGE_TAG', value: "${IMAGE_TAG}"),
                        string(name: 'ENVIRONMENT', value: "${ENV}")
                    ], wait: true
                }
            }

            stage('Cleanup') {
                steps {
                    sh '''
                    docker rmi $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG || true
                    docker rmi $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest || true
                    '''
                }
            }
        }

        post {
            always {
                deleteDir()
            }
            success {
                echo ' Docker image built & pushed successfully!'
            }
            failure {
                echo ' Pipeline failed. Check logs.'
            }
        }
    }
}