# DevSecOps

Phần này giúp trả lời hai câu hỏi: dependency hoặc image có finding nào cần
chú ý, và những finding đó có khiến pipeline dừng không?

Dependency-Check và Trivy tạo report. Sau đó `gate.py` đọc report, xét các
ngoại lệ trong `exceptions.json` và quyết định cho đi tiếp hay chặn. Jenkins
tự chạy các bước này; trên máy cá nhân có thể chạy từng bước để xem kết quả.

Các lệnh dưới đây chạy từ thư mục gốc `mosquizto-backend`, bằng Bash trên
Linux hoặc WSL. Nếu chỉ muốn hiểu một build bị chặn, có thể bắt đầu ngay ở
mục **Đọc kết quả**, dùng report đã có.

## 1. Scan dependency của backend

Cần JDK 17 và Python 3 từ 3.8 trở lên. Kiểm tra trong chính terminal sắp chạy:

```bash
java -version
javac -version
bash ./mvnw -version
python3 --version
```

Java mà Maven sử dụng cần là bản 17. Nếu `JAVA_HOME` đang trỏ sai, sửa lại
trước khi scan; cài Java trên Windows chưa đồng nghĩa WSL đã có Java phù hợp.

Dependency-Check cần tải dữ liệu lỗ hổng từ NVD về máy. Bản đang dùng là
`13.0.0`, có [lỗi khi chạy không có API key](https://github.com/dependency-check/DependencyCheck/issues/8715),
nên cần [đăng ký và kích hoạt NVD key](https://nvd.nist.gov/developers/request-an-api-key).
Nhập key vào terminal bằng cách sau để giá trị không nằm trong lịch sử lệnh:

```bash
read -r -s -p 'NVD API key: ' NVD_API_KEY
printf '\n'
export NVD_API_KEY
```

Rồi chạy:

```bash
bash security/dependency-check.sh
```

Lần đầu có thể mất nhiều thời gian vì phải tải dữ liệu NVD và dependency.
Kết quả được ghi vào `target/security-reports/dependency-check/`:

- Mở `dependency-check-report.html` để đọc theo thư viện và CVE.
- `dependency-check-report.json` chứa dữ liệu để gate xử lý.
- `gate-summary.json` giải thích quyết định của gate.

Khi dùng xong key trong terminal, có thể chạy `unset NVD_API_KEY`.

## 2. Scan image Docker

Cần Docker hoạt động trong terminal hiện tại, Python 3 và Trivy. Jenkinsfile
đang chọn Trivy `0.76.2`; nên dùng cùng phiên bản khi đối chiếu kết quả local.

```bash
docker version
trivy --version
```

`docker version` cần hiển thị được cả Client và Server. Nếu dùng WSL với
Docker Desktop, bật distro ở **Settings > Resources > WSL Integration**.
Hướng dẫn cài Trivy có ở [tài liệu chính thức](https://trivy.dev/docs/latest/getting-started/installation/).

Để thử với cách đóng gói giống pipeline EKS, build image có model ONNX rồi scan:

```bash
docker build --platform linux/amd64 --build-arg INCLUDE_EMBEDDING_MODEL=true \
  -t mosquizto-backend:security-demo . &&
  bash security/trivy-scan.sh mosquizto-backend:security-demo
```

Thay `mosquizto-backend:security-demo` bằng tên image đã build nếu dùng tag khác.
Script tìm image trên Docker local, lấy ID rồi scan đúng ID đó. Các lệnh trên
chỉ build và kiểm tra image trên máy.

Kết quả nằm trong `target/security-reports/trivy/`: `trivy-report.txt` để đọc,
`trivy-report.json` cho gate và `gate-summary.json` ghi quyết định. Trivy hiện
chạy scanner lỗ hổng (`vuln`); secret scan và kiểm tra cấu hình chưa được bật.

## 3. Đọc report và kết quả gate

Một **finding** là trường hợp scanner nhận diện dependency/package có liên
quan đến lỗ hổng đã biết. Cần đọc điều kiện ảnh hưởng trước khi kết luận
Mosquizto khai thác được lỗ hổng đó.

Ở gate v1, Dependency-Check chặn finding có CVSS cao nhất từ 9 trở lên;
Trivy chặn Critical. Finding chưa có điểm CVSS hoặc có severity Unknown
cũng khiến gate dừng để xem xét. [POLICY.md](POLICY.md) giải thích cách chọn
ngưỡng và từng ngoại lệ.

Trong `gate-summary.json`, đọc các mục này trước:

- `status`: `passed`, `blocked` hoặc `error`.
- `report_only`: nếu là `true`, lần chạy chỉ thu thập kết quả, chưa áp ngưỡng chặn.
- `blocked_count`: số lượt finding khiến gate chặn.
- `exception_count`: số lượt finding khớp ngoại lệ.
- `findings`: chi tiết từng CVE; `decision` là `block`, `exception` hoặc `report`.

Ví dụ, `decision: exception` nghĩa là finding vẫn có trong report gốc, nhưng
đã được gate miễn theo một lý do cụ thể. `decision: report` nghĩa là finding
được giữ để theo dõi và chưa chặn ở ngưỡng đang dùng.

Muốn thử lại một ngưỡng trên report Dependency-Check đã có, chạy:

```bash
python3 security/gate.py dependency-check \
  target/security-reports/dependency-check/dependency-check-report.json --cvss 9
```

Lệnh này chỉ đọc JSON và cập nhật `gate-summary.json`, không gọi lại NVD.
Report HTML/JSON do scanner tạo vẫn giữ nguyên.

## 4. Xử lý lỗi scan và gate

Nếu lỗi xảy ra khi tải NVD, build image hoặc chạy scanner, đọc log công cụ
trước. Chẳng hạn `Invalid API Key` hoặc `NoDataException` cho biết dữ liệu
scan chưa sẵn sàng; chưa thể dùng lần chạy đó để kết luận về lỗ hổng.

Nếu đã có report và gate trả mã `10`, mở `gate-summary.json` để xem các
finding có `decision: block`. Chọn cách xử lý theo [policy](POLICY.md): nâng
dependency/image, kiểm chứng điều kiện ảnh hưởng hoặc xác minh false positive.

Các mã trả về giúp phân biệt hai tình huống:

- `0`: gate cho đi tiếp, hoặc lần chạy đang ở chế độ chỉ báo cáo. Xem thêm `report_only`.
- `10`: scan có kết quả, nhưng còn finding bị gate chặn.
- `2` từ `gate.py`: input, report, policy hoặc image ID không hợp lệ.

Maven, Docker và Trivy có thể trả mã lỗi riêng. Nếu scan dừng sớm thì có thể
chưa có summary mới; đọc log, không lấy report cũ làm kết quả của lần vừa chạy.

Khi cần lấy report để điều tra trước, có thể chọn chế độ chỉ báo cáo:

```bash
DC_FAIL_ON_CVSS=11 bash security/dependency-check.sh
TRIVY_FAIL_SEVERITY='' bash security/trivy-scan.sh mosquizto-backend:security-demo
```

Hai lệnh này vẫn báo lỗi nếu scanner không chạy được. Kết quả chỉ báo cáo
không thay thế lần đánh giá gate trước khi phát hành.

## 5. Tích hợp Jenkins

Trước lần chạy đầu, chuẩn bị agent Linux có Bash, Git, JDK 17, Python 3,
Docker, AWS CLI, Trivy `0.76.2` và Kustomize. Đặt `JAVA_HOME` của agent trỏ
tới JDK 17; Java chạy Jenkins controller có thể là phiên bản khác.

Trong Jenkins Credentials, cấu hình hai Secret Text mà Jenkinsfile đang dùng:

- `nvd-api-key`: NVD key hợp lệ cho bước scan dependency.
- `github-token`: PAT có quyền ghi repo GitOps.

Agent cũng cần AWS identity có quyền login/push ECR và `ecr:DescribeImages`.
Các plugin Jenkins dùng trong luồng này là Pipeline, Git, Credentials Binding,
JUnit và Workspace Cleanup. Repository không tự cài công cụ hoặc tạo credentials.

Jenkins chạy hai scanner ở chế độ chỉ báo cáo để lấy được cả hai kết quả,
rồi stage **Security Gate** gọi `gate.py` với ngưỡng v1. Bước gate chỉ đọc
report, không scan lại image hay tải lại NVD.

Stage này nhận exit code bằng `sh(returnStatus: true, ...)`. Nó lưu report,
rồi gọi `error(...)` nếu một trong hai gate trả mã khác 0. Khi đó Push ECR
và Update GitOps phía sau bị bỏ qua. Cả hai gate pass thì pipeline mới xét
điều kiện nhánh `main` để phát hành.

Để xem kết quả, mở **build tương ứng > Artifacts** trên Jenkins. Report gốc,
summary, policy và metadata của build được archive trước `cleanWs()`, kể cả
khi có lỗi. Jenkinsfile hiện giữ tối đa 30 build/30 ngày. Report sinh ra nằm
trong `target/`, đã được gitignore; không cần đưa chúng vào commit.

Chi tiết tag image, digest và cách cập nhật GitOps nằm trong
[hướng dẫn pipeline](../docs/pipeline-flow.md). Sonar hiện vẫn chờ tích hợp.

## Cấu trúc code và kiểm thử

Bắt đầu bằng `exceptions.json` để xem các ngoại lệ, rồi đọc `gate.py` để
theo dõi cách quyết định được đưa ra. Hai script `.sh` lo gọi scanner và
chuyển report cho gate. `Jenkinsfile` ghép các bước đó thành pipeline;
`ci/git-askpass.sh` giúp Git lấy credential từ Jenkins khi clone/push.

Nếu sửa gate hoặc wrapper, chạy bộ kiểm tra offline bằng:

```bash
python3 -B -m unittest discover -s security/tests -v
```

Bộ kiểm tra dùng dữ liệu/công cụ giả lập, không gọi NVD, Jenkins hay AWS.
