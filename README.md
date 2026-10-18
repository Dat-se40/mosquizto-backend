# Mosquizto Backend

Hướng dẫn build, chạy và deploy. Giới thiệu tính năng, API và cấu trúc code: [OVERVIEW.md](OVERVIEW.md).

## 1. Cần có gì

- **JDK 17**. JDK 21 hoặc 25 build fail vì Lombok của Spring Boot 3.2.4 không sinh getter/`builder()`.
- Không cần cài Maven riêng. Project có `mvnw` và `mvnw.cmd`. Lần chạy đầu wrapper tự tải Maven.
- Docker Desktop nếu chạy service hoặc cả app bằng container.
- Internet lần đầu để Maven tải dependency. Model ONNX chỉ tải khi build image với `INCLUDE_EMBEDDING_MODEL=true`.

## 2. Cài JDK 17

### 2.1. Kiểm tra

```powershell
java -version
```

| Kết quả | Việc cần làm |
| --- | --- |
| Không nhận lệnh `java` | Máy chưa có JDK. Cài theo mục 2.2. |
| Bản 17 | Sang mục 3. |
| Bản khác, ví dụ 21 hoặc 25 | Cài thêm JDK 17, rồi trỏ terminal theo mục 2.3. Không cần gỡ JDK đang có. |

### 2.2. Cài trên Windows

Dùng Eclipse Temurin 17.

**Cách 1: winget**

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-source-agreements --accept-package-agreements
```

Đóng terminal, mở terminal mới, chạy `java -version`. Phải thấy bản 17.

JDK thường nằm tại `C:\Program Files\Eclipse Adoptium\jdk-17.0.xx-hotspot`. Kiểm tra tên thư mục:

```powershell
Get-ChildItem "C:\Program Files\Eclipse Adoptium"
```

Nếu `winget` lỗi nguồn (`Data required by the source is missing`, `No packages were found`), dùng cách 2.

**Cách 2: tải zip, không cần winget**

```powershell
$destRoot = Join-Path $env:LOCALAPPDATA "Programs"
New-Item -ItemType Directory -Force -Path $destRoot | Out-Null
$zip = Join-Path $env:TEMP "temurin17-jdk.zip"
Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $destRoot -Force
Get-ChildItem $destRoot -Directory | Where-Object { $_.Name -like "jdk-17*" }
```

Dòng in ra là thư mục JDK, dạng `C:\Users\<ten-user>\AppData\Local\Programs\jdk-17.0.xx+x`. Zip khoảng 180 MB, lệnh có thể chạy vài phút và không in tiến trình.

Có thể tải tay file Windows x64 JDK `.zip` tại [https://adoptium.net/temurin/releases/?version=17](https://adoptium.net/temurin/releases/?version=17), giải nén, rồi lấy thư mục có `bin\java.exe`.

**macOS**

```bash
brew install --cask temurin@17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

**Ubuntu/Debian**

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

`export` chỉ có hiệu lực ở terminal hiện tại. Muốn giữ, thêm hai dòng đó vào `~/.bashrc` hoặc `~/.zshrc`.

### 2.3. Trỏ terminal vào JDK 17

Làm trong mỗi terminal mới nếu `java -version` chưa ra 17. Bước này không đổi Java mặc định của máy.

```powershell
$env:JAVA_HOME = "C:\Users\<ten-user>\AppData\Local\Programs\jdk-17.0.xx+x"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
java -version
```

Nếu cài bằng winget, đường dẫn thường là `C:\Program Files\Eclipse Adoptium\jdk-17.0.xx-hotspot`.

Chỉ chạy `.\mvnw.cmd` sau khi `java -version` in bản **17**. Wrapper đọc `JAVA_HOME`. Biến này trống thì Maven dùng `java` trên `PATH`.

## 3. Cấu hình

Từ thư mục gốc project:

```powershell
Copy-Item .env.example .env
```

```bash
cp .env.example .env
```

| Biến | Bắt buộc | Mô tả |
| --- | --- | --- |
| `DB_NAME` | Có | Tên database PostgreSQL |
| `DB_USER` | Có | User PostgreSQL |
| `DB_PASSWORD` | Có | Password PostgreSQL |
| `REDIS_HOST` | Có | `redis` khi app chạy trong Compose. `localhost` khi app chạy trên máy |
| `REDIS_PORT` | Có | Mặc định `6379` |
| `APP_MAIL_PROVIDER` | Có | `smtp` hoặc `sendgrid` |
| `MAIL_ADDRESS` | Có | Email gửi đi |
| `MAIL_USERNAME` | Có | Username SMTP |
| `MAIL_PASSWORD` | Có | Password SMTP hoặc Gmail App Password |
| `MAIL_FROM` | Nên có | Email hiển thị ở người gửi |
| `MAIL_NAME` | Nên có | Tên người gửi, ví dụ `Mosquizto Support` |
| `SEND_GRID` | Khi dùng SendGrid | API key SendGrid |
| `JWT_ACCESS_KEY` | Có | Secret access token |
| `JWT_REFRESH_KEY` | Có | Secret refresh token |
| `JWT_RESET_KEY` | Có | Secret reset password token |
| `GOOGLE_CLIENT_ID` | Khi dùng Google login | Client ID từ Google Console |
| `MEILI_MASTER_KEY` | Có | Master key Meilisearch |
| `MEILI_SEARCH_HOST` | Có | `http://meilisearch:7700` trong Compose, `http://localhost:7700` khi app chạy trên máy |
| `VPS_IP` | Khi chạy Docker | Dùng để tạo `SERVER_URL`. Local để `localhost` |
| `SERVER_NAME` | Nên có | Tên server trên Swagger |
| `WS_MESSAGE_TIME_OUT` | Có | Timeout WebSocket message |
| `WS_CONNECT_ENDPOINT` | Có | Mặc định `/ws-connection` |
| `CLOUDINARY_NAME` | Khi upload media | Cloudinary cloud name |
| `CLOUDINARY_API_KEY` | Khi upload media | Cloudinary API key |
| `CLOUDINARY_API_SECRET` | Khi upload media | Cloudinary API secret |
| `INCLUDE_EMBEDDING_MODEL` | Không | `true` thì image build kèm model ONNX. Mặc định bỏ qua |

Sinh secret, mỗi biến một giá trị:

```bash
openssl rand -base64 48
```

Dùng cho `JWT_ACCESS_KEY`, `JWT_REFRESH_KEY`, `JWT_RESET_KEY`, `MEILI_MASTER_KEY`.

Gmail cần App Password, không dùng mật khẩu đăng nhập thường: [https://myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords).

## 4. Deploy bằng Docker Compose

Compose dựng `postgres`, `redis`, `meilisearch`, `qdrant` và `app`. App đọc biến từ `.env`. Flyway migrate PostgreSQL lúc app start.

Build mặc định không tải model ONNX, để CI không kéo khoảng 90 MB từ Hugging Face mỗi lần runner mới.

```bash
docker compose up -d --build
```

Kiểm tra:

```bash
docker compose ps
docker compose logs -f app
```

- Swagger: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
- OpenAPI: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- Meilisearch: [http://localhost:7700](http://localhost:7700)
- Qdrant: [http://localhost:6333](http://localhost:6333)

`docker-compose.yml` đặt `SERVER_URL` thành `http://${VPS_IP}:8080/`. Deploy thật thì `VPS_IP` phải là IP hoặc domain public.

Bật recommendation trong image:

```bash
INCLUDE_EMBEDDING_MODEL=true docker compose up -d --build
```

```powershell
$env:INCLUDE_EMBEDDING_MODEL = "true"
docker compose up -d --build
```

Thiếu model thì server vẫn lên. Recommendation không tạo vector.

Dừng:

```bash
docker compose down
```

Xóa luôn volume dữ liệu:

```bash
docker compose down -v
```

Khởi động lại riêng app:

```bash
docker compose restart app
```

## 5. Build và chạy app trên máy

Các service (`postgres`, `redis`, `meilisearch`, `qdrant`) có thể để trong Docker. App trên máy không tự đọc `.env`, và hostname trong `.env` kiểu `redis` / `meilisearch` không phân giải được ngoài container. Phải trỏ về `localhost`.

Trước tiên `java -version` phải ra 17 (mục 2.3).

Tải model chỉ khi cần recommendation. Bỏ qua thì server vẫn chạy:

```powershell
New-Item -ItemType Directory -Force src\main\resources\models | Out-Null
Invoke-WebRequest "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx" -OutFile "src\main\resources\models\model.onnx"
Invoke-WebRequest "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json" -OutFile "src\main\resources\models\tokenizer.json"
Invoke-WebRequest "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer_config.json" -OutFile "src\main\resources\models\tokenizer_config.json"
```

Build:

```powershell
.\mvnw.cmd clean package -DskipTests -B
```

```bash
./mvnw clean package -DskipTests -B
```

`-DskipTests` bỏ qua lúc chạy test. Maven vẫn compile test.

Chạy jar, sau khi đã set `JAVA_HOME` ở mục 2.3. Đoạn dưới đọc `.env` rồi đổi host sang localhost:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
  $name, $value = $_.Split('=', 2)
  Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/${env:DB_NAME}?options=-c%20timezone=Asia/Ho_Chi_Minh"
$env:SPRING_DATASOURCE_USERNAME = $env:DB_USER
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
$env:SPRING_MAIL_ADDRESS = $env:MAIL_ADDRESS
$env:SPRING_MAIL_USERNAME = $env:MAIL_USERNAME
$env:SPRING_MAIL_PASSWORD = $env:MAIL_PASSWORD
$env:REDIS_HOST = "localhost"
$env:MEILI_SEARCH_HOST = "http://localhost:7700"
$env:QDRANT_HOST = "localhost"
$env:QDRANT_PORT = "6334"
$env:SERVER_URL = "http://localhost:8080/"
if (-not $env:SERVER_NAME) { $env:SERVER_NAME = "local" }

java -jar target\mosquizto-api-0.0.1-SNAPSHOT.jar
```

Hoặc để Maven tự build và chạy, với cùng các biến môi trường ở trên:

```powershell
.\mvnw.cmd spring-boot:run
```

Test (H2 in-memory, Flyway tắt, không cần Docker):

```powershell
.\mvnw.cmd test
```

## 6. Sự cố thường gặp

**`cannot find symbol` cho `builder()`, `getId()`, `getUsername()`**

Terminal đang dùng JDK khác 17. `java -version` và `.\mvnw.cmd -version` phải cùng báo Java 17. Nếu không, làm lại mục 2.2 và 2.3.

**Recommendation không tạo vector**

Image hoặc classpath không có `model.onnx`. Đây là mặc định. Bật bằng `INCLUDE_EMBEDDING_MODEL=true` khi build Docker, hoặc tải model ở mục 5 rồi build lại.

**Không gửi được email Gmail**

`MAIL_PASSWORD` phải là App Password, không phải mật khẩu Gmail.

**Swagger hiện server URL sai**

Kiểm tra `VPS_IP`, `SERVER_URL`, `SERVER_NAME`.

**Tìm kiếm hoặc recommendation không nối được service**

Kiểm tra `MEILI_SEARCH_HOST`, `MEILI_MASTER_KEY`, `QDRANT_HOST`, `QDRANT_PORT`, và container `meilisearch` / `qdrant` đã chạy. App trên máy phải dùng `localhost`, không dùng tên service Docker.
