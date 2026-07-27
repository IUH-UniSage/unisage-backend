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

---

## Kết nối UniSage Agent

Backend là API public chịu trách nhiệm JWT/RBAC. Agent chỉ xử lý pipeline RAG và
được backend gọi qua `WebClient`.

```ini
AI_AGENT_URL=http://localhost:8000
AI_AGENT_SECRET_KEY=change-me
```

Khởi chạy agent trước, sau đó chạy backend. Các API ingestion public của backend:

| Method | Endpoint | Mục đích |
|---|---|---|
| `POST` | `/api/v1/documents/ingestions` | Upload, chunk, embed và lưu ngay |
| `POST` | `/api/v1/documents/ingestions/drafts` | Tạo preview chunks |
| `POST` | `/api/v1/documents/ingestions/confirm` | Xác nhận preview để embed và lưu |

Backend forward các request này sang collection `/api/v1/ingestions` của agent.
Endpoint ingestion yêu cầu JWT có quyền `DOCUMENT_CREATE` hoặc quyền ingestion
tương ứng.
