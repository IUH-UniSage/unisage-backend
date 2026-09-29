# Spec: Cost Tracking + Budget Management

Spec sống cho tính năng chi phí AI của UniSage (ticket UNISAGE-90). Chi tiết thiết kế và tiến độ
nằm ở:
- `plan.md`, `todo.md` cùng thư mục: đo usage, ngân sách, cảnh báo, trang Chi phí AI.
- `../29-09-2026-Model-Pricing-Sync/plan.md`, `todo.md`: giá model trong DB và gỡ provider
  Mistral/Groq.

Khi quyết định đổi, sửa spec này trước rồi mới sửa code.

## Objective

Super Admin (SA) cần biết hệ thống AI tốn bao nhiêu tiền, vì sao, và chặn được việc vượt ngân
sách mà không làm sập luồng chat.

User stories:
- SA xem tổng chi phí theo tháng, theo mục đích (Chat/Embedding/Trích xuất), nhà cung cấp, model,
  ngày và top người dùng/IP.
- SA đặt ngân sách theo hệ thống, nhà cung cấp hoặc mục đích, theo ngày hoặc tháng, với hành động
  khi vượt: chỉ cảnh báo, giới hạn đồng thời, hoặc từ chối request mới.
- SA nhận cảnh báo khi vượt ngưỡng (50/80/100% và ngưỡng tuỳ chỉnh) hoặc chi phí tăng đột biến,
  qua In-app, Email, Slack.
- SA xem lịch sử từng request: ai gọi, model nào, bao nhiêu token, bao nhiêu tiền, có failover
  không, câu hỏi/câu trả lời (nếu chưa bị xoá).
- SA xem và chỉnh giá model; giá tự đồng bộ từ LiteLLM mỗi ngày; xem lịch sử thay đổi giá.

Nhà cung cấp cloud được hỗ trợ: `openai`, `google`, cộng `SELF_HOSTED` (server tương thích
OpenAI, luôn miễn phí). `groq`, `mistral` đã bị gỡ.

## Tech Stack

| Repo | Vai trò | Stack |
|---|---|---|
| `unisage-backend` | Control plane: lưu usage, budget, cảnh báo, giá; API cho web; API `/internal/**` cho agent | Spring Boot 3.4, Java 17, PostgreSQL + Flyway, Redis pub/sub, `spring-boot-starter-mail` |
| `unisage-agent` | Đo usage mỗi lượt gọi provider, giữ chỗ ngân sách, tính cost | FastAPI, Python 3.12, `uv`, pydantic-ai, Celery (worker + beat), Redis (Lua) |
| `unisage-web` | Trang "Chi phí AI" trong workspace system-admin | React + Vite, TanStack Query/Table, zod, recharts, pnpm |
| `unisage-gateway` | Proxy `/api/v1/master/**` → backend, `/api/v1/ai/**` → agent | Spring Cloud Gateway |

## Commands

```bash
# Backend (unisage-backend)
./mvnw spring-boot:run                     # cổng 8401, đọc .env qua spring-dotenv
./mvnw test                                # full suite, Testcontainers PostgreSQL (cần Docker)
./mvnw test -Dtest=RequestUsageLogServiceImplTest

# Agent (unisage-agent)
task db:up                                 # alembic upgrade head
task be:dev                                # uvicorn cổng 8402
.venv/bin/python -m celery -A app.worker.celery_app worker --loglevel=info --pool=solo
.venv/bin/python -m celery -A app.worker.celery_app beat --loglevel=info
task test                                  # pytest
task code:check                            # ruff lint + format check

# Web (unisage-web)
pnpm dev                                   # cổng 5173
pnpm typecheck && pnpm lint && pnpm build
pnpm test                                  # vitest run
```

Thứ tự chạy local: infra (Postgres 5433, MinIO 9100, Redis 6379, Qdrant 6333) → backend → agent
(app + worker + beat) → gateway (8400) → web. Xem `/home/huy/Main/dev-onboard.md`.

## Project Structure

```
unisage-backend/
  src/main/java/com/unisage/backend/
    controller/                 RequestUsageLogController, BudgetController, ModelPricingController...
    controller/internal/        /internal/usage-logs, /internal/budgets/snapshot, /internal/model-pricing/snapshot
    service/usagelog|budget|pricing/   interface + *Impl
    scheduler/                  BudgetAlertJob, ModelPricingSyncJob
    integration/                SlackWebhookClient
  src/main/resources/db/migration/     V25-V27 cost tracking, V28 gỡ provider, V29 giá model
  changes/<dd-MM-yyyy>-<Title>/        plan.md, todo.md, specs.md
unisage-agent/
  app/core/usage/               cost_calculator, usage_recorder, usage_outbox
  app/core/budget/              snapshot, tracker, Lua reserve/acquire/release/settle
  app/core/pricing/             snapshot giá (thay bảng litellm offline)
  app/worker/                   drain outbox, đối soát budget, refresh snapshot
  docs/product/                 PRODUCT.md (luật sản phẩm), DECISIONS.md (vì sao)
unisage-web/src/features/cost-management/
  api/ queries/ schemas/ hooks/ utils/ constants/
  components/{overview,budgets,alerts,pricing,history}/
```

## Code Style

Theo đúng convention sẵn có của từng repo, không tự đặt pattern mới. Ví dụ backend (controller
mỏng, service giữ logic, lỗi nghiệp vụ là `AppException(ErrorCode)`):

```java
@GetMapping
public ResponseEntity<ApiResponse<PageResponse<List<UsageLogListItemResponse>>>> search(
        @RequestParam(required = false) UsagePurpose purpose,
        @RequestParam(required = false) String provider,
        Pageable pageable) {
    var filter = new UsageLogSearchFilter(purpose, null, null, null, provider, null, null);
    return ResponseEntity.ok(ApiResponse.success(requestUsageLogService.search(filter, pageable)));
}
```

Quy ước chung:
- Thời gian usage/budget/cảnh báo/giá lưu **UTC** trong `timestamp without time zone`; mốc kỳ
  (ngày/tháng) tính theo `app.timezone` (`Asia/Ho_Chi_Minh`). FE hiển thị qua
  `formatUtcDateTime`. API mới trả thời gian kèm `Z`.
- Tiền: `NUMERIC(18,8)` ↔ `BigDecimal` (Java), `Decimal` (Python), micro-USD integer trong Redis.
  Hiển thị: dưới $1 giữ chữ số có nghĩa (`formatUsd`).
- Comment ít và ngắn, chỉ giải thích "vì sao".
- Commit: conventional commit tiếng Anh kèm `[UNISAGE 90]`, message ≤ 2 dòng, không chứa tên
  task/phase/plan/tên file, không co-author.

## Testing Strategy

| Tầng | Công cụ | Nơi | Bắt buộc |
|---|---|---|---|
| Backend service/repository/migration | JUnit 5 + Testcontainers PostgreSQL (không H2) | `src/test/java/...` | Mọi query mới, race (claim alert, budget trùng), biên timezone, bảo mật `/internal/**` |
| Agent | pytest; Redis thật cho Lua (fakeredis không chạy Lua đủ) | `tests/core/...` | Reserve/acquire/release/settle đồng thời, outbox, cost calculator |
| Web | vitest cho util/schema; Playwright với server thật cho luồng UI | `src/**/*.test.ts`, script Playwright | Parse response thật, hiển thị giờ, số tiền nhỏ |
| End-to-end | Chạy đủ stack theo dev-onboard, seed usage qua `POST /internal/usage-logs` | thủ công, ghi kết quả vào checkpoint todo | Mỗi checkpoint phase |

Bài học từ lần chạy thật 29-09-2026: typecheck/lint/build không bắt được lỗi parse schema, lệch
múi giờ, làm tròn tiền. Mỗi tab UI phải được mở trên trình duyệt với dữ liệu thật trước khi đánh
dấu xong.

Test bắt buộc xuyên tính năng: xem bảng "Test bắt buộc" trong `plan.md`.

## Boundaries

- **Always:**
  - Chạy test của file đang sửa trong lúc làm; full suite một lần khi xong mỗi task, trước commit.
  - Migration Flyway mới cho mọi thay đổi schema (`ddl-auto=validate`).
  - Endpoint mới cần `Permission` seed (migration cho DB cũ + `DataInitializer` cho DB mới).
  - Endpoint `/internal/**` mới phải vào `InternalEndpointCoverageTest`.
  - Budget fail-open: lỗi Redis/snapshot không bao giờ chặn Chat.
  - Cập nhật `unisage-agent/docs/product/PRODUCT.md` và `DECISIONS.md` trong cùng task đổi
    hành vi; ADR trong `unisage-backend/docs/adr/` cho quyết định kiến trúc khó đảo ngược
    (vd nguồn giá model).
- **Ask first:**
  - Thêm/gỡ dependency (dùng `uv add/remove`, `pnpm add`; không sửa tay mảng dependency).
  - Đổi ngữ nghĩa budget (soft limit, ma trận scope × action) hay công thức cost.
  - Đổi shape response API mà web/agent đang dùng.
  - Push, tạo PR, khởi động lại dịch vụ dùng chung ngoài phạm vi dev local.
- **Never:**
  - Commit secret hay giá trị `.env`; log URL webhook Slack, API key, nội dung message.
  - Gọi provider qua LiteLLM SDK (ADR 0005); agent không tự tải dữ liệu từ internet.
  - Cào HTML trang giá của provider làm nguồn giá.
  - Sửa cost đã ghi của request cũ khi giá đổi.
  - Làm việc ngoài `/home/huy/Main`.

## Success Criteria

Usage và cost:
- [ ] Mỗi request nghiệp vụ có đúng 1 bản ghi cha + N dòng (mỗi lần gọi provider, kể cả
      failover); gửi trùng `requestId` không nhân đôi.
- [ ] Java down → chat vẫn chạy, outbox giữ payload, Java lên lại thì đủ bản ghi.
- [ ] Cost mỗi dòng tính từ giá trong DB theo (provider, model); không có giá → UNPRICED + ước
      tính, hiển thị riêng; SELF_HOSTED → miễn phí.

Budget:
- [ ] SYSTEM/PURPOSE hết ngân sách với BLOCK → từ chối request mới; PROVIDER hết → failover sang
      provider khác; THROTTLE giới hạn số request đồng thời.
- [ ] 50 request đồng thời không vượt ngân sách quá tổng chênh lệch actual − estimate.
- [ ] % đã dùng trên Tổng quan và Ngân sách là cùng một con số (có tính phần ước tính).

Cảnh báo:
- [ ] Mỗi ngưỡng × kênh gửi đúng 1 lần mỗi kỳ, kể cả 2 instance chạy cùng lúc; lỗi 3 lần →
      GAVE_UP; SMTP/Slack chưa cấu hình → SKIPPED, health vẫn UP.
- [ ] Banner in-app hiện khi có cảnh báo chưa tắt, tắt được.

Giá model:
- [x] Job hằng ngày đồng bộ giá openai/google từ LiteLLM vào DB; lỗi tải → giữ giá cũ.
- [x] SA sửa giá → nguồn "Chỉnh tay", sync không ghi đè; khôi phục → quay về giá LiteLLM.
- [x] Mọi thay đổi giá (sync và tay) có trong lịch sử giá, lọc được; agent dùng giá mới ≤ 60s.

Provider:
- [x] Chỉ tạo được model cloud với `openai`, `google`; model groq/mistral cũ đã INACTIVE.

UI:
- [ ] 5 tab (Tổng quan, Ngân sách, Cảnh báo, Bảng giá, Lịch sử) render dữ liệu thật, giờ đúng
      múi giờ VN, số tiền nhỏ không bị làm tròn về $0.00; user thiếu quyền không thấy menu.

## Open Questions

- Chưa có môi trường prod/staging: các mục manual (Slack thật, restart Redis giữ AOF, K8s CIDR)
  sẽ kiểm khi có môi trường.
