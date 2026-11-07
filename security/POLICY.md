# Chính sách security gate

Gate v1 của Mosquizto bắt đầu với ngưỡng **CVSS từ 9 cho dependency** và
**Critical cho image Docker**. Ngưỡng này giúp tập trung xử lý những finding
có điểm cao trước, trong khi các finding còn lại vẫn được lưu để theo dõi.
Đây là lựa chọn cho giai đoạn tích hợp hiện tại; sau khi xử lý dần kết quả,
có thể cân nhắc chặn thêm High.

Điểm số là tín hiệu để ưu tiên. Quyết định một CVE có áp dụng cho Mosquizto
hay không cần dựa thêm vào đúng package, phiên bản và điều kiện sử dụng.
Gate không tự suy ra điều đó từ việc dependency có xuất hiện trong project.

## Ngưỡng chặn và cách đánh giá

Với Dependency-Check, gate lấy điểm cao nhất trong các điểm CVSS v2/v3/v4
có trong finding. Nếu điểm đó từ 9 trở lên và không khớp ngoại lệ, finding
sẽ chặn pipeline. Vì vậy, đôi khi report ghi severity High nhưng gate vẫn
chặn do một điểm CVSS khác trong cùng finding đạt ngưỡng.

Với Trivy, gate dùng severity của finding và chặn Critical. High, Medium
và Low vẫn nằm trong report để đọc và xử lý sau. Finding chưa có bản vá
vẫn được xét như các finding khác.

Nếu Dependency-Check chưa có CVSS hoặc Trivy trả severity Unknown, gate
dừng để xem xét thay vì tự coi finding đó là nhẹ. Nếu scanner lỗi hoặc
report thiếu/sai, cần sửa lần scan trước; lúc đó chưa có cơ sở cho phép
phát hành. Sonar chưa tích hợp nên chưa có quyết định Sonar trong gate này.

## Các ngoại lệ và cơ sở chấp nhận

Lần rà report ngày 07/11/2026 tìm được bốn finding có đủ bằng chứng để miễn
trong phạm vi artifact đã scan. Chúng được ghi trong [exceptions.json](exceptions.json).
Một trường hợp là ghép nhầm sản phẩm; ba trường hợp còn lại thiếu thành
phần cần để lỗ hổng xảy ra.

### ONNX Runtime Java — CVE-2024-7776

Report gắn CVE này với `com.microsoft.onnxruntime:onnxruntime:1.11.0`.
Tuy nhiên, [advisory](https://github.com/advisories/GHSA-h36j-8vv3-cj52) mô tả
lỗi trong hàm Python `download_model` của package `onnx`. Đây là hai sản phẩm
khác nhau, nên finding trên đúng artifact ONNX Runtime Java này được xem là
false positive.

Ngoại lệ chỉ miễn CVE đó trên artifact đã đối chiếu. Các finding khác của
ONNX Runtime vẫn được gate xét bình thường.

### Spring và JMS — CVE-2026-41855

[Lỗi này](https://spring.io/security/cve-2026-41855/) nằm ở các Jackson JMS
converter trong môi trường JMS không đáng tin cậy. Dependency inventory đã
scan không có `spring-jms`, nên các converter bị ảnh hưởng không có trong
artifact Maven đang xét. Vì vậy, CVE này được miễn cho Spring Core `6.1.5`
đã đối chiếu.

Nếu thêm `spring-jms` vào dependency, gate sẽ ngừng áp ngoại lệ này.

### Spring WebFlux và Aalto — CVE-2026-47891

[Điều kiện ảnh hưởng](https://spring.io/security/cve-2026-47891/) là ứng dụng
WebFlux dùng Aalto XML processor. Inventory hiện không có cả `spring-webflux`
lẫn `aalto-xml`. Ngoại lệ được giữ khi cả hai thành phần vẫn vắng mặt;
thêm một trong hai sẽ làm gate yêu cầu xem xét lại finding.

### Spring WebFlux functional endpoints — CVE-2026-47892

[CVE này](https://spring.io/security/cve-2026-47892/) cần WebFlux functional
endpoints. Artifact đã scan không có `spring-webflux`, nên hiện chưa có
thành phần cần để phát sinh lỗi đó. Thêm WebFlux sẽ làm ngoại lệ mất hiệu lực.

## Thời hạn và điều kiện rà soát ngoại lệ

Gate khớp chính xác CVE, package/version và SHA-256 của artifact. Với ba
ngoại lệ Spring, nó còn đọc các dependency được gom trong `relatedDependencies`
để kiểm tra các module cần vắng mặt. Đổi artifact hoặc thêm module liên quan
có thể khiến finding bị chặn trở lại.

Cả bốn ngoại lệ có ngày rà lại là **07/12/2026**. Từ **07:00 giờ Việt Nam**
ngày đó, gate ngừng áp dụng chúng. Muốn giữ tiếp cần kiểm tra lại bằng chứng
và cập nhật ngày trong `exceptions.json` cùng lý do xem xét.

Ngoài kiểm tra tự động, cũng cần đọc lại ngoại lệ khi thay đổi cách đóng gói
hoặc thêm thư viện ngoài inventory Maven. Report không quan sát được mọi
thành phần có thể được nạp thêm lúc runtime.

## Xử lý finding mới

Đầu tiên đọc advisory để xác nhận đúng sản phẩm và phiên bản. Sau đó tìm
thành phần hoặc cấu hình cần cho lỗ hổng trong backend:

- Nếu có thể áp dụng, ưu tiên bản vá dependency/base image và kiểm tra lại ứng dụng.
- Nếu ghép nhầm hoặc thiếu điều kiện ảnh hưởng, ghi bằng chứng cụ thể trước khi thêm ngoại lệ.
- Nếu cần chấp nhận rủi ro tạm thời, ghi rõ rủi ro còn lại, phạm vi sử dụng,
  cách giảm thiểu và ngày xử lý/xem xét lại. Hiện chưa có ngoại lệ loại này.

Mỗi ngoại lệ nên trả lời được: miễn CVE nào, trên artifact nào, vì sao có
thể miễn và khi nào quyết định đó cần xem lại. Dependency gián tiếp hoặc
chưa có bản vá, tự chúng, chưa đủ để kết luận finding không ảnh hưởng.

Hiện chưa miễn trừ các finding Kotlin, Thymeleaf hay toàn bộ Netty/Tomcat.
Bốn ngoại lệ phía trên cũng không bảo đảm report hiện tại sẽ qua gate.

## Tra cứu kết quả theo build

Mở `gate-summary.json` đi cùng report của build. `decision: exception` có
ID để tra lại lý do trong bản policy được lưu cùng build; `decision: block`
là finding cần xử lý trước khi pipeline đi tiếp.

Report scanner gốc vẫn giữ nguyên. Summary ghi checksum report/policy để
truy lại dữ liệu đã dùng. Trên Jenkins, các file này cùng metadata commit và
image nằm trong Artifacts của build.

Chế độ chỉ báo cáo (`report_only: true`) hữu ích khi điều tra kết quả. Trong
Jenkins, hai scanner lấy report trước rồi stage Security Gate xét lại ở
ngưỡng v1, trước khi Push ECR hoặc Update GitOps.
