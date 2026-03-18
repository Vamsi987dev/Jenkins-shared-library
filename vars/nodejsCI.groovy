def call(Map configMap){

    def AWS_REGION = configMap.AWS_REGION
    def ACCOUNT_ID = configMap.ACCOUNT_ID
    def PROJECT    = configMap.PROJECT
    def COMPONENT  = configMap.COMPONENT
    def GIT_URL    = configMap.GIT_URL
    def GIT_BRANCH = configMap.BRANCH ?: "main"

    pipeline {
        agent { label 'agent' }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }
        
        environment {
            AWS_REGION = "${AWS_REGION}"
            ACCOUNT_ID = "${ACCOUNT_ID}"
            IMAGE_TAG  = ""
            PROJECT    = "${PROJECT}"
            COMPONENT  = "${COMPONENT}"
            GIT_URL    = "${GIT_URL}"
            GIT_BRANCH = "${GIT_BRANCH}"
            ECR_REPO   = "${PROJECT}/${COMPONENT}"   
        }
        

        stages {
            stage('Checkout Code') {
                steps {
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

            stage('Login to ECR') {
                steps {
                    sh '''
                    aws ecr get-login-password --region $AWS_REGION | \
                    docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com
                    '''
                }
            }

            stage('Get Image Tag') {
                steps {
                    script {
                        env.IMAGE_TAG = sh(
                            script: "git rev-parse --short HEAD",
                            returnStdout: true
                        ).trim()
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    sh '''
                    docker build -t $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG .
                    '''
                }
            }

            stage('Push Image to ECR') {
                steps {
                    sh '''
                    docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG
                    '''
                }
            }
            stage('Trigger CD Pipeline') {
                when {
                    branch 'main'
                }
                steps {
                    build job: "${COMPONENT}-cd", parameters: [
                        string(name: 'IMAGE_TAG', value: "${IMAGE_TAG}")
                    ]
                }
            }
            
            stage('Cleanup Docker Images') {
                steps {
                    sh '''
                    docker rmi $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG || true
                    '''
                }
            }
        }

        post {
            success {
                echo 'Docker image built and pushed successfully!'
            }

            failure {
                echo 'Pipeline failed. Check logs.'
            }
        }

    }
}
