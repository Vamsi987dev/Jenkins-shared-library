def call(Map configMap){
    pipeline {
        agent { label 'agent' }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }
        
        environment {
            AWS_REGION = configMap.get("Aws_Region")
            ACCOUNT_ID = configMap.get("Account_ID")
            IMAGE_TAG = "${BUILD_NUMBER}"

            PROJECT = configMap.get("Project")
            COMPONENT = configMap.get("Component")

            GIT_URL     = configMap.get("GitRepo")
            GIT_BRANCH  = configMap.get("Branch") ?: "main"

            ECR_REPO = "${PROJECT}/${COMPONENT}"
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
