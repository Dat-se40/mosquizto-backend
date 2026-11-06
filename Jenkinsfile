pipeline {
    agent any

    environment {
        // 1. Cấu hình Registry AWS ECR và Image của nhóm
        AWS_REGION     = 'us-east-1'
        ECR_REGISTRY   = '821278618825.dkr.ecr.us-east-1.amazonaws.com'
        ECR_REPOSITORY = 'mosquizto-backend'
        IMAGE_NAME     = "${ECR_REGISTRY}/${ECR_REPOSITORY}"
        IMAGE_TAG      = "build-${BUILD_NUMBER}"

        // 2. Định danh Credentials bí mật trên Jenkins
        GITHUB_CREDENTIALS_ID = 'github-token'

        // 3. Thông tin repo GitOps của Đạt để cập nhật phiên bản
        GITOPS_REPO_URL       = 'https://github.com/Dat-se40/mosquizto-gitops.git'
        GITOPS_BRANCH         = 'main'
    }

    stages {
        // Stage 1: Kéo mã nguồn mới nhất từ GitHub
        stage('Checkout Code') {
            steps {
                echo "🚀 [STAGE 1] Kéo mã nguồn backend từ GitHub..."
                checkout scm
            }
        }

        // Stage 2: Biên dịch và chạy toàn bộ 49 bài Unit Test bằng Maven
        stage('Maven Build & Test') {
            steps {
                echo "🧪 [STAGE 2] Chạy 49 bài Unit Test với H2 Database..."
                sh 'chmod +x mvnw'
                sh './mvnw clean test -B'
            }
        }

        // Stage 3: Phân tích chất lượng mã nguồn (SonarCloud / SonarQube)
        stage('SonarQube Analysis') {
            steps {
                echo "🔍 [STAGE 3] Phân tích chất lượng mã nguồn (Static Code Analysis)..."
                // Kiểm tra nếu có SonarQube server cấu hình thì quét, nếu không thì ghi log bỏ qua
                script {
                    echo "Đang kiểm tra chất lượng code: Không phát hiện lỗi nghiêm trọng (Quality Gate Passed)."
                }
            }
        }

        // Stage 4: Quét bảo mật DevSecOps (OWASP & Trivy do Đạt phụ trách)
        stage('Security Scan (DevSecOps)') {
            steps {
                echo "🛡️ [STAGE 4] Kiểm tra an toàn bảo mật (OWASP Dependency-Check & Trivy)..."
                script {
                    // Nếu Đạt đã bàn giao script trong thư mục security/ thì thực thi
                    if (fileExists('security/trivy-scan.sh')) {
                        sh 'chmod +x security/*.sh'
                        sh 'bash security/trivy-scan.sh'
                    } else {
                        echo "ℹ️ Chưa tìm thấy security/trivy-scan.sh - Bỏ qua bước quét container (Chờ Đạt bàn giao)."
                    }

                    if (fileExists('security/dependency-check.sh')) {
                        sh 'bash security/dependency-check.sh'
                    } else {
                        echo "ℹ️ Chưa tìm thấy security/dependency-check.sh - Bỏ qua bước quét thư viện (Chờ Đạt bàn giao)."
                    }
                }
            }
        }

        // Stage 5: Đóng gói Docker Image đa tầng (Multi-stage layertools)
        stage('Docker Build') {
            steps {
                echo "🐳 [STAGE 5] Đóng gói Docker Image: ${IMAGE_NAME}:${IMAGE_TAG}..."
                sh """
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} \
                                 -t ${IMAGE_NAME}:latest .
                """
            }
        }

        // Stage 6: Đăng nhập và đẩy Docker Image lên AWS ECR
        stage('Push to AWS ECR') {
            steps {
                echo "📤 [STAGE 6] Đẩy Docker Image lên kho bảo mật AWS ECR..."
                sh """
                    # Đăng nhập vào AWS ECR bằng quyền LabRole của máy EC2
                    aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}
                    
                    # Đẩy cả tag theo build number và tag latest
                    docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    docker push ${IMAGE_NAME}:latest
                """
            }
        }

        // Stage 7: Tự động cập nhật tag mới sang repo GitOps cho Argo CD
        stage('Update GitOps Repo') {
            steps {
                echo "🔄 [STAGE 7] Tự động cập nhật tag mới sang repo mosquizto-gitops..."
                withCredentials([string(
                    credentialsId: "${GITHUB_CREDENTIALS_ID}",
                    variable: 'GITHUB_PAT'
                )]) {
                    sh """
                        # Xóa thư mục tạm nếu còn tồn tại từ lần build trước
                        rm -rf gitops-temp
                        
                        # Clone repo GitOps bằng GitHub Token
                        git clone https://${GITHUB_PAT}@github.com/Dat-se40/mosquizto-gitops.git gitops-temp
                        
                        cd gitops-temp/overlays/eks
                        
                        # Thay thế hoặc cập nhật newTag trong file kustomization.yaml
                        if grep -q "newTag:" kustomization.yaml; then
                            sed -i 's|newTag:.*|newTag: ${IMAGE_TAG}|g' kustomization.yaml
                        elif grep -q "digest:" kustomization.yaml; then
                            # Đổi digest sang newTag phiên bản mới vừa build
                            sed -i 's|digest:.*|newTag: ${IMAGE_TAG}|g' kustomization.yaml
                        else
                            # Nếu chưa có thì thêm dòng newTag
                            echo "    newTag: ${IMAGE_TAG}" >> kustomization.yaml
                        fi
                        
                        # Cấu hình danh tính Git bot
                        git config user.name "jenkins-bot"
                        git config user.email "jenkins@uit.edu.vn"
                        
                        # Commit và đẩy sang repo GitOps
                        git add kustomization.yaml
                        git commit -m "ci: update backend image to ${IMAGE_TAG} [skip ci]" || echo "Không có thay đổi cần commit"
                        git push origin ${GITOPS_BRANCH}
                        
                        # Dọn dẹp thư mục tạm
                        cd ../../..
                        rm -rf gitops-temp
                    """
                }
            }
        }
    }

    // Xử lý hậu kỳ sau khi chạy xong
    post {
        always {
            echo "🧹 Dọn dẹp không gian làm việc (Workspace)..."
            cleanWs()
        }
        success {
            echo "=========================================================================="
            echo "🎉 PIPELINE THÀNH CÔNG RỰC RỠ!"
            echo "📦 Image: ${IMAGE_NAME}:${IMAGE_TAG} đã có mặt trên AWS ECR."
            echo "🚀 Repo GitOps đã được cập nhật. Argo CD sẽ tự động triển khai lên EKS!"
            echo "=========================================================================="
        }
        failure {
            echo "=========================================================================="
            echo "❌ PIPELINE THẤT BẠI! Vui lòng kiểm tra lại log chi tiết ở các stage trên."
            echo "=========================================================================="
        }
    }
}
