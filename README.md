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

## Database migration (Flyway)

Schema được quản lý bằng Flyway thay vì để Hibernate tự sinh (`spring.jpa.hibernate.ddl-auto=validate`
— Hibernate chỉ kiểm tra mapping có khớp DB không, không tự sửa gì). Migration nằm ở
`src/main/resources/db/migration/`, đặt tên `V{n}__mo_ta.sql` theo thứ tự tăng dần.

**Không cần lệnh riêng để chạy migrate** — Flyway tự chạy khi app khởi động (`./mvnw spring-boot:run`
hoặc chạy qua Docker/IDE như bình thường), áp mọi migration còn thiếu rồi mới tới bước Hibernate
validate. Log sẽ hiện dòng kiểu:

```
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "1 - baseline schema"
o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "2 - audit fields user relation"
o.f.core.internal.command.DbMigrate : Successfully applied 2 migrations to schema "public", now at version v2
```

**Thêm migration mới**: tạo file `V{n+1}__mo_ta_ngan.sql` trong `src/main/resources/db/migration/`,
viết SQL thuần, rồi chạy app như bình thường — Flyway tự phát hiện và áp dụng, ghi lại vào bảng
`flyway_schema_history`. Không sửa lại migration đã áp dụng rồi (Flyway sẽ báo lỗi checksum lệch) —
luôn tạo file version mới.

**Nếu máy bạn đang có DB dev từ trước khi dự án dùng Flyway** (DB được tạo hoàn toàn bằng
`ddl-auto=update` cũ, chưa từng có bảng `flyway_schema_history`): lần chạy app đầu tiên sau khi
pull thay đổi này, Flyway sẽ tự tạo `flyway_schema_history` và **baseline** DB đó ở version được
cấu hình sẵn (`spring.flyway.baseline-version` trong `application.properties`) — coi như DB đã ở
đúng version đó, không chạy lại migration cũ. Điều này chỉ đúng nếu schema DB của bạn khớp với
đúng version baseline; nếu không chắc, cách an toàn nhất là xóa DB dev và tạo lại từ đầu
(`docker compose down -v` rồi `docker compose up -d` — xem `docs/adr/0002-audit-fields-user-relation.md`
và `docs/adr/0003-adopt-flyway-migrations.md` để biết lý do đây là thay đổi chấp nhận mất dữ liệu
dev).

## Chạy bằng Dev Container (VS Code)

Mở thư mục này bằng "Reopen in Container" — devcontainer chỉ khởi động service `unisage-db`, không chạy sẵn app. `postCreateCommand` chỉ tải dependency, sau khi container mở xong chạy thủ công:

```bash
sh ./mvnw spring-boot:run
sh ./mvnw test
```

App sẽ nghe ở cổng `8401` (đã khai báo trong `forwardPorts` để VS Code tự forward ra `localhost`).
