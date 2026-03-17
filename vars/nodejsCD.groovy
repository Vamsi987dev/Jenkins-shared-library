def call(Map configMap){

    pipeline {
        agent { label 'agent' }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        parameters {
            choice(name: 'ENVIRONMENT', choices: ['dev','qa','prod'], description: 'Select Environment')
            string(name: 'IMAGE_TAG', description: 'Docker image tag to deploy')
        }

        environment {
            AWS_REGION = configMap.get("Aws_Region")
            ACCOUNT_ID = configMap.get("Account_ID")

            PROJECT = configMap.get("Project")
            COMPONENT = configMap.get("Component")

            ENVIRONMENT = "${params.ENVIRONMENT}"

            ECR_REPO = "${PROJECT}/${COMPONENT}"

            CLUSTER = "${PROJECT}-${ENVIRONMENT}"
        }

        stages {

            stage('Authenticate to EKS') {
                steps {
                    sh '''
                    aws eks update-kubeconfig \
                    --region $AWS_REGION \
                    --name $CLUSTER
                    '''
                }
            }

            stage('Deploy to Kubernetes') {
                steps {
                    sh '''
                    helm upgrade --install $COMPONENT ./helm \
                    --namespace $PROJECT \
                    --create-namespace \
                    --set deployment.tag=$IMAGE_TAG \
                    --set environment=$ENVIRONMENT \
                    --wait --timeout 5m
                    '''
                }
            }

        }

        post {
            success {
                echo "Deployment successful: ${COMPONENT}:${IMAGE_TAG} → ${ENVIRONMENT}"
            }

            failure {
                echo "Deployment failed. Check logs."
            }
        }

    }
}