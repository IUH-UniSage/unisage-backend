# Backend Java

## Chạy bằng IDE (Chỉ chạy Database bằng Docker)

1. Cấu hình `.ENV`:
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

---

## Chạy hoàn toàn bằng Docker

1. Cấu hình `.ENV`:
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
