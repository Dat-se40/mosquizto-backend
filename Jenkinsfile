pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timeout(time: 90, unit: 'MINUTES')
        buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '30'))
    }

    environment {
        AWS_REGION = 'us-east-1'
        ECR_REGISTRY = '821278618825.dkr.ecr.us-east-1.amazonaws.com'
        ECR_REPOSITORY = 'mosquizto-backend'
        IMAGE_NAME = "${ECR_REGISTRY}/${ECR_REPOSITORY}"
        GITHUB_CREDENTIALS_ID = 'github-token'
        NVD_CREDENTIALS_ID = 'nvd-api-key'
        GITOPS_REPO_URL = 'https://github.com/Dat-se40/mosquizto-gitops.git'
        GITOPS_BRANCH = 'main'
        DC_FAIL_ON_CVSS = '9'
        TRIVY_FAIL_SEVERITY = 'CRITICAL'
        SECURITY_POLICY = 'mosquizto-security-v1'
        TRIVY_VERSION = '0.76.2'
    }

    stages {
        stage('Checkout Code') {
            steps {
                script {
                    if (!isUnix()) {
                        error('This pipeline requires a Linux agent.')
                    }
                    // Start clean so older reports cannot be archived as a new scan.
                    deleteDir()
                    def source = checkout scm
                    env.SOURCE_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    env.SOURCE_BRANCH = source.GIT_BRANCH ?: env.BRANCH_NAME ?: ''
                    env.CAN_PUBLISH = (!env.CHANGE_ID &&
                        ['main', 'origin/main', 'refs/heads/main'].contains(env.SOURCE_BRANCH)).toString()
                }
            }
        }

        stage('Verify Agent') {
            steps {
                sh '''#!/usr/bin/env bash
set -euo pipefail
for tool in git docker aws python3 trivy kustomize; do
    command -v "$tool" >/dev/null || { echo "Missing required agent tool: $tool" >&2; exit 2; }
done
: "${JAVA_HOME:?Configure JAVA_HOME on the agent to a JDK 17 directory}"
version=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 |
    awk -F'= ' '/java.specification.version =/ { print $2 }')
[[ $version == 17 ]] || { echo 'The Maven stages require JDK 17.' >&2; exit 2; }
"$JAVA_HOME/bin/javac" -version
docker version
trivy --version
trivy --version | grep -Fx "Version: $TRIVY_VERSION"
kustomize version
python3 --version
'''
                script {
                    // A per-run suffix prevents collisions across jobs/build counter resets.
                    def runSuffix = sh(script: 'python3 -c "import uuid; print(uuid.uuid4().hex[:12])"',
                                       returnStdout: true).trim()
                    env.IMAGE_TAG = "git-${env.SOURCE_COMMIT}-b${env.BUILD_NUMBER}-${runSuffix}"
                }
            }
        }

        stage('Maven Build & Test') {
            steps {
                sh 'bash ./mvnw clean test --batch-mode --no-transfer-progress'
                script {
                    sh '''
                        mkdir -p target/security-reports
                        cp security/exceptions.json target/security-reports/exception-policy.json
                    '''
                    writeFile file: 'target/security-reports/build-metadata.json',
                        text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                            policy: env.SECURITY_POLICY,
                            commit: env.SOURCE_COMMIT,
                            branch: env.SOURCE_BRANCH,
                            buildUrl: env.BUILD_URL,
                            image: "${env.IMAGE_NAME}:${env.IMAGE_TAG}",
                            dependencyCvssThreshold: env.DC_FAIL_ON_CVSS,
                            imageSeverityThreshold: env.TRIVY_FAIL_SEVERITY,
                            buildStartedAtMillis: currentBuild.startTimeInMillis
                        ])) + '\n'
                }
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('SonarQube Pending Integration') {
            steps {
                echo 'Sonar analysis is not configured. No Sonar Quality Gate result is claimed.'
            }
        }

        stage('Dependency Scan') {
            steps {
                withCredentials([string(credentialsId: env.NVD_CREDENTIALS_ID, variable: 'NVD_API_KEY')]) {
                    // Collect both scanners' reports before enforcing the release gate.
                    withEnv(['DC_FAIL_ON_CVSS=11']) {
                        sh 'bash security/dependency-check.sh'
                    }
                }
            }
        }

        stage('Docker Build') {
            steps {
                sh '''#!/usr/bin/env bash
set -euo pipefail
docker build --platform linux/amd64 --build-arg INCLUDE_EMBEDDING_MODEL=true \
    --label "org.opencontainers.image.revision=$SOURCE_COMMIT" \
    -t "$IMAGE_NAME:$IMAGE_TAG" .
docker image inspect --format '{{.Id}}' "$IMAGE_NAME:$IMAGE_TAG" \
    > target/security-reports/built-image-id.txt
'''
            }
        }

        stage('Container Scan') {
            steps {
                withEnv(['TRIVY_FAIL_SEVERITY=']) {
                    sh 'bash security/trivy-scan.sh "${IMAGE_NAME}:${IMAGE_TAG}"'
                }
            }
        }

        stage('Security Gate') {
            steps {
                script {
                    def dependencies = sh(returnStatus: true, script:
                        'python3 security/gate.py dependency-check target/security-reports/dependency-check/dependency-check-report.json --cvss "$DC_FAIL_ON_CVSS"')
                    def image = sh(returnStatus: true, script:
                        'python3 security/gate.py trivy target/security-reports/trivy/trivy-report.json --severity "$TRIVY_FAIL_SEVERITY" --expected-image-id "$(cat target/security-reports/built-image-id.txt)"')
                    archiveArtifacts artifacts: 'target/security-reports/**/*', allowEmptyArchive: false
                    if (dependencies != 0 || image != 0) {
                        error("Security gate blocked delivery (dependency=${dependencies}, image=${image}). See archived reports.")
                    }
                }
            }
        }

        stage('Push to AWS ECR') {
            when { expression { env.CAN_PUBLISH == 'true' } }
            steps {
                sh '''#!/usr/bin/env bash
set -euo pipefail
expected=$(cat target/security-reports/built-image-id.txt)
actual=$(docker image inspect --format '{{.Id}}' "$IMAGE_NAME:$IMAGE_TAG")
[[ $expected == "$actual" ]] || { echo 'Image changed after build/scan.' >&2; exit 1; }
aws ecr get-login-password --region "$AWS_REGION" |
    docker login --username AWS --password-stdin "$ECR_REGISTRY"
docker push "$IMAGE_NAME:$IMAGE_TAG"
aws ecr describe-images --region "$AWS_REGION" --repository-name "$ECR_REPOSITORY" \
    --image-ids "imageTag=$IMAGE_TAG" --query 'imageDetails[0].imageDigest' --output text \
    > target/security-reports/pushed-image-digest.txt
grep -Eq '^sha256:[a-f0-9]{64}$' target/security-reports/pushed-image-digest.txt
'''
                script {
                    env.IMAGE_DIGEST = readFile('target/security-reports/pushed-image-digest.txt').trim()
                }
                archiveArtifacts artifacts: 'target/security-reports/pushed-image-digest.txt', allowEmptyArchive: false
            }
        }

        stage('Update GitOps Repo') {
            when { expression { env.CAN_PUBLISH == 'true' } }
            steps {
                sh 'chmod +x ci/git-askpass.sh'
                withCredentials([string(credentialsId: env.GITHUB_CREDENTIALS_ID, variable: 'GITHUB_PAT')]) {
                    withEnv(["GIT_ASKPASS=${pwd()}/ci/git-askpass.sh", 'GIT_TERMINAL_PROMPT=0']) {
                        dir('gitops-temp') {
                            deleteDir()
                            sh '''#!/usr/bin/env bash
set -euo pipefail
set +x
export LC_ALL=C
git -c credential.helper= clone --single-branch --branch "$GITOPS_BRANCH" "$GITOPS_REPO_URL" .
git config credential.helper ''
cd overlays/eks
kustomize edit set image "mosquizto-backend=$IMAGE_NAME@$IMAGE_DIGEST"
kustomize build . > /dev/null
git config user.name 'jenkins-bot'
git config user.email 'jenkins@uit.edu.vn'
if git diff --quiet -- kustomization.yaml; then
    echo 'GitOps already references this digest.'
else
    git add kustomization.yaml
    git commit -m "ci: deploy $IMAGE_TAG ($SOURCE_COMMIT) [skip ci]"
    # Concurrent remote changes must fail safely; never force-push deployment state.
    git push origin "HEAD:$GITOPS_BRANCH"
fi
'''
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'target/security-reports/**/*', allowEmptyArchive: true
            cleanWs()
        }
        success {
            echo "CI and configured security gates passed. Publish enabled for this branch: ${env.CAN_PUBLISH}."
            echo 'GitOps delivery requires Argo CD sync according to its Application configuration; rollout is not verified here.'
        }
        failure {
            echo 'Build/scan/gate/delivery failed. Inspect the console log and archived reports.'
        }
    }
}
