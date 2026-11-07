# Luồng Jenkins CI và GitOps

Đây là hành vi trong Jenkinsfile và điều kiện cần cấu hình, không phải biên bản
đã chạy thành công trên Jenkins/AWS. Xem [hướng dẫn DevSecOps](../security/README.md)
và [policy v1](../security/POLICY.md) để chuẩn bị agent, credentials và đọc report.

## Luồng thực thi

1. Checkout revision của job vào workspace sạch, ghi nhận commit và nhánh.
2. Kiểm tra agent Linux, JDK 17, Docker, AWS CLI, Python, Trivy 0.76.2, Kustomize.
3. Maven build/test; publish JUnit; ghi metadata build/image/policy.
4. Stage Sonar hiện chỉ báo chưa tích hợp, không báo Quality Gate Passed.
5. Dependency-Check xuất HTML/JSON; lỗi cập nhật dữ liệu hoặc scan dừng pipeline.
6. Docker build image Linux amd64, kèm model ONNX đã kiểm chứng bằng cơ chế Dockerfile.
7. Trivy scan đúng local image ID, xuất JSON/TXT.
8. Gate v1 xét cả hai report và archive trước phát hành. Gate lỗi/chặn thì không
   chạy Push ECR hoặc Update GitOps.
9. Chỉ nhánh main và không phải PR: login ECR, push một tag, lấy manifest digest.
10. Clone đúng nhánh GitOps, cập nhật image bằng Kustomize, validate, commit/push.
11. `post/always` archive cả report một phần/lỗi đã có, sau đó dọn workspace.

## Image reference

Tag là `git-<full-commit-SHA>-b<BUILD_NUMBER>-<random-run-suffix>`.
Commit nhận diện source; build number và suffix phân biệt lần build/job.
Không push `latest`. Quy tắc immutable của ECR vẫn phải được duy trì ở registry;
pipeline không thay đổi cấu hình registry và không ghi đè tag đã tồn tại.

Sau push, pipeline lấy `imageDetails[0].imageDigest` từ ECR bằng đúng tag vừa
push. Image GitOps có dạng `<registry>/<repository>@sha256:<digest>`.
Không thay `digest` bằng `newTag`; luồng Sync của EKS yêu cầu digest.
Pipeline kiểm tra tag vẫn trỏ cùng local image ID trước push.

## GitOps và credentials

Jenkins không deploy workload bằng `kubectl apply`. Nó chỉ cập nhật
`overlays/eks/kustomization.yaml` của `mosquizto-gitops` bằng `kustomize edit set image`,
validate `kustomize build`, rồi commit/push. Thay đổi đồng thời trên remote làm
push bị từ chối; cần build/review lại, không force-push.

GitHub PAT lấy từ Secret Text `github-token` bằng Credentials Binding và
`ci/git-askpass.sh`. Token không nằm trong clone URL hoặc Git remote config.
NVD key lấy từ Secret Text `nvd-api-key`. AWS identity/quyền ECR của agent phải
được chuẩn bị riêng, không được giả định từ comment về LabRole.

Argo CD sync theo Application configuration thực tế. Application EKS trong Git
hiện chưa bật automated sync; pipeline không cam kết rollout tự động hoặc zero
downtime. Rollback thực hiện bằng Git revert về digest/cấu hình tương thích,
sau đó sync Argo CD; phải xét tương thích migration database và dữ liệu search.

## Giới hạn nghiệm thu

- Gate v1 đã có policy và kiểm tra offline; scan/deploy end-to-end trên Jenkins
  phải được nghiệm thu riêng sau khi cấu hình agent/credentials.
- Report hiện tại vẫn có Critical chưa được miễn trừ. Build đỏ vì gate là kết
  quả có thể dự kiến; tích hợp gate không tự nâng dependency hoặc xử lý finding.
- Không đưa report/Secret/generated manifests vào GitOps.
