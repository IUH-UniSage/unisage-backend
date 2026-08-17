# Backend Java

Service master-data (Auth, User, RBAC, Department, Category, ChatModel, Document, Conversation, Message) của hệ thống Unisage, chạy ở context-path `/api/v1`, cổng mặc định **8401**. Được gọi qua API Gateway ở prefix `/api/v1/master/**`, hoặc gọi thẳng khi dev.

## Cấu hình môi trường

Copy `.env.example` thành `.ENV` rồi chỉnh giá trị cho phù hợp (DB, JWT, mật khẩu superadmin mặc định, ...).

```bash
cp .env.example .ENV
```

## Chạy bằng IDE (chỉ chạy Database bằng Docker)

1. Trong `.ENV`, để trống profile Docker:
```ini
COMPOSE_PROFILES=
```

2. Khởi chạy Database:
```bash
docker compose up -d
```

3. Chạy ứng dụng bằng IDE hoặc chạy lệnh:
```bash
./mvnw spring-boot:run
```

## Chạy hoàn toàn bằng Docker

1. Trong `.ENV`, bật profile `app`:
```ini
COMPOSE_PROFILES=app
```

2. Build ứng dụng:
```bash
./mvnw clean package -DskipTests
```

3. Khởi chạy Docker:
```bash
docker compose up -d --build
```

## Chạy bằng Dev Container (VS Code)

Mở thư mục này bằng "Reopen in Container" — devcontainer chỉ khởi động service `unisage-db`, không chạy sẵn app. `postCreateCommand` chỉ tải dependency, sau khi container mở xong chạy thủ công:

```bash
sh ./mvnw spring-boot:run
```

App sẽ nghe ở cổng `8401` (đã khai báo trong `forwardPorts` để VS Code tự forward ra `localhost`).
