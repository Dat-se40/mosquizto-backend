# Mosquizto Backend

Backend cho ứng dụng Mosquizto, xây dựng bằng Spring Boot để phục vụ học bằng flashcard, bộ sưu tập, lớp học, thư mục, phiên học, thông báo thời gian thực và gợi ý nội dung.

Cách build, chạy và deploy nằm ở [README.md](README.md).

## Tính năng

- Xác thực bằng email/mật khẩu, JWT và Google Sign-In.
- Quản lý người dùng, hồ sơ, avatar, theo dõi người dùng khác.
- Quản lý `collection` (bộ flashcard) và `collection item`.
- Quản lý thư mục (`folder`) và khóa học (`course`).
- Chia sẻ bộ sưu tập, lời mời tham gia, duyệt yêu cầu tham gia.
- Tiến độ học, phiên học (`study session`), streak và achievement.
- Tìm kiếm collection bằng Meilisearch.
- Gợi ý collection bằng vector search với Qdrant và embedding ONNX. Image deploy mặc định không kèm model.
- Email xác thực, quên mật khẩu, thông báo chia sẻ và report.
- Thông báo thời gian thực qua WebSocket/STOMP.
- Ký upload media lên Cloudinary.

## Công nghệ

- Java 17
- Spring Boot 3.2.4
- Spring Web, Validation, Security, Data JPA, Redis, WebSocket, Mail, Thymeleaf, AOP
- PostgreSQL và Flyway
- Redis cho cache, token và rate limit
- Meilisearch
- Qdrant
- ONNX Runtime và HuggingFace tokenizer
- Cloudinary
- Docker Compose
- Swagger / OpenAPI (`springdoc-openapi`)

## Thành phần khi chạy đủ

- `app`: ứng dụng Spring Boot
- `postgres`: cơ sở dữ liệu chính
- `redis`: cache, token, rate limit. `FLUSHDB` và `FLUSHALL` bị vô hiệu hóa trong Compose.
- `meilisearch`: tìm kiếm collection
- `qdrant`: vector database cho recommendation

Ngoài ra ứng dụng dùng SMTP hoặc SendGrid, Cloudinary, và Google OAuth client ID.

## Nhóm API

Route không có prefix `/api`. Chi tiết request, response và auth xem trên Swagger UI sau khi server chạy: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html).

### Xác thực

- `/auth/register`
- `/auth/login`
- `/auth/refresh-token`
- `/auth/logout`
- `/auth/forgot-password`
- `/auth/verify-code-forgot-password`
- `/auth/reset-password`
- `/auth/google`

### Người dùng

- `/user/profile`
- `/user/update`
- `/user/change-password`
- `/user/avatar`
- `/user/search`
- `/user/profile/{username}`
- `/user/follow/{username}`
- `/user/followers`
- `/user/following`
- `/user/follow/notifications`

### Collection và item

- `/collection`
- `/collection/my-list`
- `/collection/public`
- `/collection/search`
- `/collection/recent-opened`
- `/collection/create_index`
- `/collection/item`
- `/collection/item/{id}/star`
- `/collection/item/starred`

### Chia sẻ collection

- `/user-collection/share/{collectionId}`
- `/user-collection/join/{collectionId}`
- `/user-collection/members/{collectionId}`
- `/user-collection/invitations`

### Folder

- `/folder/create`
- `/folder/{folderId}`
- `/folder/{folderId}/collection/{collectionId}`
- `/folder/{folderId}/share`
- `/folder/{folderId}/members`

### Course

- `/course`
- `/course/{courseId}`
- `/course/public`
- `/course/{courseId}/collection/{collectionId}`
- `/course/{courseId}/join`
- `/course/{courseId}/join-requests/pending`
- `/course/{courseId}/members`
- `/course/{courseId}/stats/best-collections-learnt`

### Study session

- `/study-session/start`
- `/study-session/{sessionId}/answer`
- `/study-session/{sessionId}/complete`
- `/study-session/{sessionId}/complete-batch`
- `/study-session/history`
- `/study-session/stats/{collectionId}`
- `/study-session/get-jump-back-in`

### Notification, report, media, recommendation

- `/notifications`
- `/notifications/unread-count`
- `/reports/collections/{collectionId}`
- `/reports/users/{username}`
- `/media/cloudinary/sign`
- `/recommendation/collections`
- `/recommendation/sync/collections`

## WebSocket

STOMP over WebSocket.

- Endpoint kết nối mặc định: `/ws-connection`
- Simple broker prefix: `/topic`, `/queue`
- Application destination prefix: `/{spring.application.name}`

`spring.application.name` hiện là `Mosquizto-Backend`, nên prefix gửi message vào app là `/Mosquizto-Backend`.

Client gửi JWT access token trong header `Authorization: Bearer <token>` khi `CONNECT`.

## Database

Flyway chạy khi ứng dụng khởi động. Script nằm ở `src/main/resources/db/migration`:

- Schema khởi tạo
- Seed role mặc định
- Seed dữ liệu mô phỏng lớp học/workspace
- Bảng collection, study session, folder, course, notification, follow, report
- Soft delete, index tìm kiếm và các thay đổi cho recommendation

## Cấu trúc thư mục

```text
.
|-- src/main/java/com/mosquizto/api
|   |-- configuration   # Security, Swagger, Redis, Meilisearch, Qdrant, WebSocket
|   |-- controller
|   |-- service
|   |-- service/impl
|   |-- repository
|   |-- model
|   |-- dto
|   |-- mapper
|   |-- security
|   |-- exception
|   `-- util
|-- src/main/resources
|   |-- application.yml
|   |-- db/migration
|   `-- templates       # Email templates
|-- src/test
|-- Dockerfile
|-- docker-compose.yml
|-- .env.example
`-- pom.xml
```
