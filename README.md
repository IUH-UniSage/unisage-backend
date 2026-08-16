# Backend Java

## Chạy bằng IDE (Chỉ chạy Database bằng Docker)

1. Tạo cấu hình local:
```powershell
Copy-Item .env.example .env
```

2. Khởi chạy Database:
```bash
docker compose up -d unisage-db
```

3. Chạy ứng dụng bằng IDE hoặc chạy lệnh:
```powershell
.\mvnw.cmd spring-boot:run
```

Backend chạy tại `http://localhost:8081/api/v1`.

---

## Chạy hoàn toàn bằng Docker

1. Tạo cấu hình local:
```powershell
Copy-Item .env.example .env
```

2. Build ứng dụng:
```bash
./mvnw clean package -DskipTests
```

3. Khởi chạy Docker:
```bash
docker compose up -d --build
```
