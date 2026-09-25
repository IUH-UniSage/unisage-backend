# Todo: Cost Tracking + Budget Management

Xem `plan.md` cùng thư mục cho data model, "Budget semantics" (soft limit +
reservation, ma trận scope × action), internal API và thiết kế UI. Không bắt
đầu Phase 2 khi Task 0 chưa nghiệm thu xong.

Quyết định đã chốt với product owner:
- Budget là **soft limit**, có **reservation mỗi request**.
- Tham số Slack nằm trong **env** backend-java (`BUDGET_ALERT_SLACK_*`, đã thêm
  placeholder vào `.env.example` và `.ENV`), không lưu DB, không nhập qua UI.
- SMTP cũng qua env (`SPRING_MAIL_*`, `BUDGET_ALERT_MAIL_FROM`, placeholder đã
  thêm). `APP_TIMEZONE` + `BUDGET_*` đã thêm vào `unisage-agent/.env.example`.
- Migration: V17-V19 (xem "Migration versions" trong plan.md).

---

## Phase 0: Gate

### Task 0: Nghiệm thu Model Registry + spike chốt nguồn usage/token

**Description:** Plan này cần biết chính xác provider/model/credential và
usage thật của từng call. Code hiện tại chưa có (`OpenAIChatModel` trong
`app/api/deps.py`, OpenAI SDK trực tiếp trong `multi_representation.py` và
`openai_embedder.py`, chưa có `litellm`/`model_router`).

**Acceptance criteria:**
- [ ] Model Registry Phase 0-6 (`changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/`)
      đã tick xong và được human nghiệm thu
- [ ] Ghi vào `unisage-agent/docs/product/DECISIONS.md` bảng "nguồn usage" cho 4
      loại call, mỗi dòng có: điểm hook trong code, field input/output/cached
      token, cách lấy provider/model/`chatModelId` thực tế sau failover, cách lấy
      latency — PydanticAI agent run (`result.usage()`), LiteLLM completion
      (`response.usage` + `completion_cost`), embedding (`response.usage.prompt_tokens`),
      extraction
- [ ] Xác nhận streaming Chat trả usage ở chunk cuối (hoặc cách thay thế) cho
      provider đang dùng
- [ ] Xác nhận 1 hook duy nhất trong `model_router` gọi được callback "trước
      mỗi attempt" và "sau mỗi attempt" (cần cho Task 6 và Task 10)

**Verification:**
- [ ] Human đọc bảng trong DECISIONS.md và approve

**Dependencies:** Model Registry Phase 0-6

**Files likely touched:**
- `unisage-agent/docs/product/DECISIONS.md`

**Estimated scope:** S

---

## Phase 1: Java — Data model, internal API, RBAC

### Task 1: Entity `RequestUsageLog` + `RequestUsageLine` + migration

**Description:** Parent/child theo `plan.md` mục "Data Model". Repo đang ở V15,
Model Registry dùng V16 → file của task này là `V17__add_request_usage_logs.sql`
(kiểm tra lại bằng `sort -V` trước khi tạo).

**Acceptance criteria:**
- [ ] `RequestUsageLog` đủ field theo plan, có `userMessageId` +
      `assistantMessageId`, tổng denormalized, `unpricedLineCount`
- [ ] `RequestUsageLine` đủ field theo plan, snapshot `provider`/`modelName`/
      `sourceType`, `costStatus` (PRICED/UNPRICED/FREE), `attempt`
- [ ] Enum mới: `UsagePurpose`, `UsageRequestStatus`, `UsageCostStatus`
- [ ] `V17__add_request_usage_logs.sql`: FK `conversation_id`, `user_message_id`,
      `assistant_message_id`, `user_id`, `chat_model_id` là **ON DELETE SET NULL**;
      `usage_log_id` ON DELETE CASCADE; UNIQUE `request_id`; đủ index ở plan
- [ ] Cột thời gian `timestamp(6) without time zone` chứa UTC, entity dùng
      `LocalDateTime`; `startedAt`/`finishedAt`/`occurredAt` set từ `Clock` bean
      hoặc parse ISO-8601 UTC, không dựa vào `createdAt` của auditing
- [ ] Tiền `NUMERIC(18,8)` ↔ `BigDecimal`
- [ ] `ddl-auto=validate` khởi động được (entity khớp migration)
- [ ] Thêm Testcontainers PostgreSQL (`org.testcontainers:postgresql`,
      `junit-jupiter`) + base class `PostgresIntegrationTest` chạy Flyway thật —
      repo chưa có test DB nào, mà các ràng buộc của plan (partial index, `ON
      CONFLICT`, `SKIP LOCKED`, `ON DELETE SET NULL`) chỉ kiểm được trên
      PostgreSQL

**Verification:**
- [ ] `./mvnw test`
- [ ] Test: xoá message/conversation theo đúng luồng
      `GuestSessionServiceImpl.purgeExpiredBatch` → usage log còn, 2 message id = null

**Dependencies:** Task 0

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/entity/RequestUsageLog.java`
- `backend-java/src/main/java/com/unisage/backend/entity/RequestUsageLine.java`
- `backend-java/src/main/java/com/unisage/backend/entity/enums/Usage*.java`
- `backend-java/src/main/resources/db/migration/V17__add_request_usage_logs.sql`
- `backend-java/pom.xml`
- `backend-java/src/test/java/com/unisage/backend/support/PostgresIntegrationTest.java`
- `backend-java/src/test/java/com/unisage/backend/usagelog/GuestSessionCleanupUsageLogTest.java`

**Estimated scope:** M

---

### Task 2: Internal auth whitelist + `POST /internal/usage-logs` idempotent

**Description:** Python (worker outbox) gửi 1 payload/request gồm parent + list
line. Endpoint phải qua được `DynamicAuthorizationManager` mà không có JWT, và
chỉ khi có secret hợp lệ.

**Acceptance criteria:**
- [ ] Thêm `POST /internal/usage-logs`, `GET /internal/budgets/snapshot`,
      `GET /internal/usage-logs/period-totals` vào **cả**
      `InternalSecretFilter.INTERNAL_ONLY_PATHS` và `PredefinedPublicPaths.PUBLIC_PATHS`
      (method + path tường minh, không `/internal/**` wildcard)
- [ ] Insert parent + lines trong 1 transaction; Java tự tính tổng từ lines, không
      tin tổng do client gửi
- [ ] `requestId` trùng → `INSERT ... ON CONFLICT (request_id) DO NOTHING`, trả
      200 với id có sẵn, không ghi thêm line
- [ ] Validate: `lines` không rỗng (request không có LLM call thì Python không
      gửi — xem plan), `seq` không trùng, token ≥ 0, `costStatus = PRICED` thì
      `costUsd` bắt buộc, timestamp có offset `Z`
- [ ] Controller/Service đi qua checklist `api-review-checklist`

**Verification:**
- [ ] `./mvnw test` có 5 case auth: không secret → 403; sai secret → 403; đúng
      secret → 200; đúng secret gọi `GET /budgets` không JWT → bị từ chối; JWT SA
      gọi `/internal/usage-logs` không secret → 403
- [ ] Test gửi cùng payload 2 lần → 1 parent, đúng số line
- [ ] Test 2 thread gửi cùng `requestId` đồng thời → 1 parent

**Dependencies:** Task 1

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/security/InternalSecretFilter.java`
- `backend-java/src/main/java/com/unisage/backend/predefined/PredefinedPublicPaths.java`
- `backend-java/src/main/java/com/unisage/backend/controller/InternalUsageLogController.java`
- `backend-java/src/main/java/com/unisage/backend/service/usagelog/RequestUsageLogServiceImpl.java`
- `backend-java/src/main/java/com/unisage/backend/dto/request/UsageLogIngestRequest.java`
- `backend-java/src/main/java/com/unisage/backend/repository/RequestUsageLogRepository.java`

**Estimated scope:** M

---

### Task 3: `Budget` + `BudgetAlertSetting` + `BudgetAlertLog` + CRUD + RBAC

**Description:** Cấu hình budget, cấu hình alert toàn cục, lịch sử alert, và
quyền truy cập cho SA.

**Acceptance criteria:**
- [ ] `Budget` theo plan: scope SYSTEM/PROVIDER/PURPOSE, `scopeProvider`,
      `scopePurpose`, `throttleMaxConcurrency`; validate ở service **và** `CHECK`
      constraint ở DB theo scope/action
- [ ] 3 partial unique index riêng `ux_budgets_system`, `ux_budgets_provider`
      (`lower(scope_provider)`), `ux_budgets_purpose` — không dùng 1 index gộp
      có cột nullable; vi phạm unique map sang `ErrorCode`, không trả 500
- [ ] `BudgetAlertSetting` singleton: `id SMALLINT PRIMARY KEY DEFAULT 1 CHECK
      (id = 1)`, seed trong migration (`[50,80,100]`, spike tắt, in-app bật); chỉ
      có API `GET`/`PUT`; validate email và ngưỡng 1-200
- [ ] `BudgetAlertLog` có `dedupeKey` UNIQUE, `attemptCount`, `lastAttemptAt`,
      `nextAttemptAt`, status gồm `GAVE_UP`, index (`status`, `next_attempt_at`),
      `dismissedAt`/`dismissedBy`
- [ ] API: `GET/POST/PUT/DELETE /budgets` (response kèm "đã dùng kỳ hiện tại"
      tính từ DB), `GET/PUT /budget-alert-settings` (response có
      `slackConfigured` + `slackChannelLabel` đọc từ env, không bao giờ trả URL),
      `GET /budget-alerts?filters&page=`, `GET /budget-alerts/active`,
      `POST /budget-alerts/{id}/dismiss`
- [ ] CRUD budget publish `config_version` mới (kênh pub/sub của Model Registry)
      để Python reload snapshot
- [ ] `ResourceType.USAGE_LOG` + `ResourceType.BUDGET`; permission trong
      `PredefinedPermissions`; `DataInitializer` gán SYSTEM_ADMIN; migration insert
      permission + role_permission cho DB đã có
- [ ] `ErrorCode` mới cho validate budget/alert setting

**Verification:**
- [ ] `./mvnw test` (Testcontainers): budget SYSTEM MONTHLY thứ 2 enabled → lỗi
      validate; tạo đồng thời 2 cái → đúng 1 thành công; `INSERT` dòng
      `BudgetAlertSetting` id=2 → DB từ chối; user không có permission → 403
- [ ] Manual: app khởi động trên DB cũ, migration chạy, SA thấy permission mới

**Dependencies:** None

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/entity/{Budget,BudgetAlertSetting,BudgetAlertLog}.java`
- `backend-java/src/main/java/com/unisage/backend/entity/enums/{BudgetScope,BudgetPeriod,BudgetAction,AlertChannel,AlertType,AlertStatus,ResourceType}.java`
- `backend-java/src/main/java/com/unisage/backend/controller/{BudgetController,BudgetAlertController}.java`
- `backend-java/src/main/java/com/unisage/backend/service/budget/*`
- `backend-java/src/main/java/com/unisage/backend/predefined/PredefinedPermissions.java`
- `backend-java/src/main/java/com/unisage/backend/config/DataInitializer.java`
- `backend-java/src/main/java/com/unisage/backend/exception/ErrorCode.java`
- `backend-java/src/main/resources/db/migration/V18__add_budget_tables.sql`
- `backend-java/src/main/resources/db/migration/V19__seed_cost_permissions.sql`

**Estimated scope:** L — tách 3a (entity + migration + RBAC) và 3b (API) nếu dài

---

### Task 4: API dashboard/lịch sử/chi tiết + internal snapshot/period-totals

**Description:** Dữ liệu tổng hợp cho UI và dữ liệu cho Python. Mọi mốc ngày/
tháng tính theo `app.timezone`.

**Acceptance criteria:**
- [ ] `GET /usage-logs/summary?from=&to=&groupBy=purpose|provider|model|user|day`
      — `provider`/`model` group trên `request_usage_lines` (snapshot); trả riêng
      `pricedCostUsd` và `estimatedUnpricedCostUsd`
- [ ] `GET /usage-logs?filters&page=` — danh sách parent, có cờ `hasFailover`
- [ ] `GET /usage-logs/{id}` — parent + lines + query (user message) + answer và
      `citations` (assistant message); message null → field null, không lỗi
- [ ] `GET /internal/budgets/snapshot` — budget enabled + `configVersion`
- [ ] `GET /internal/usage-logs/period-totals?period=&periodKey=` — committed
      theo SYSTEM/PURPOSE/PROVIDER cho kỳ đó, trả **micro-USD integer** (line
      PRICED lấy `costUsd`, UNPRICED lấy `estimatedCostUsd`, FREE bỏ qua — khớp
      quy tắc settle ở Python; làm tròn half-up từng line trước khi cộng)
- [ ] Mốc kỳ: tính đầu/cuối kỳ ở `app.timezone` rồi đổi sang UTC `LocalDateTime`
      để lọc `started_at`/`occurred_at`; group theo ngày bằng
      `(col AT TIME ZONE 'UTC') AT TIME ZONE :tz`
- [ ] Query dùng index đã tạo, không full scan theo khoảng thời gian

**Verification:**
- [ ] `./mvnw test`, gồm test biên timezone: record 23:59:59 và 00:00:01
      (Asia/Ho_Chi_Minh) nằm ở 2 ngày khác nhau trong `groupBy=day`
- [ ] Manual: seed ~100k line, summary theo tháng < 1s; `EXPLAIN` dùng index

**Dependencies:** Task 1, Task 3

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/controller/{RequestUsageLogController,InternalUsageLogController,InternalBudgetController}.java`
- `backend-java/src/main/java/com/unisage/backend/repository/{RequestUsageLogRepository,RequestUsageLineRepository}.java`
- `backend-java/src/main/java/com/unisage/backend/dto/response/Usage*.java`

**Estimated scope:** M

---

## Checkpoint: Phase 1
- [ ] `./mvnw test` pass
- [ ] Postman: gửi payload usage (có 2 line failover) 2 lần → 1 record; summary
      theo provider tách đúng 2 provider
- [ ] Review với human trước khi đụng Python

---

## Phase 2: Python — Đo usage & outbox

### Task 5: `cost_calculator` (actual + estimate)

**Acceptance criteria:**
- [ ] `calculate_actual(response, model_info) -> CostResult` (`cost_usd`,
      `estimated_cost_usd`, `cost_status`); LiteLLM không có giá → `UNPRICED`,
      `cost_usd=None`, log warning kèm tên model
- [ ] `estimate(model_info, input_tokens, max_output_tokens) -> Decimal` dùng
      `litellm.cost_per_token`; không có giá → `BUDGET_RESERVATION_FALLBACK_USD`
- [ ] SELF_HOSTED → `FREE`, cost 0, không gọi LiteLLM

**Verification:**
- [ ] pytest: model có giá, không có giá, SELF_HOSTED, embedding

**Dependencies:** Task 0

**Files likely touched:**
- `unisage-agent/app/core/cost_calculator.py`
- `unisage-agent/app/core/config.py`
- `unisage-agent/tests/core/test_cost_calculator.py`

**Estimated scope:** S

---

### Task 6: `UsageRecorder` theo request, ghi line cho mọi call Chat

**Description:** Mỗi request Chat có 1 `UsageRecorder` gắn vào state của graph
(không global mutable state). Hook "sau mỗi attempt" của `model_router` (Task
0) append 1 line — cả attempt lỗi trước khi failover.

**Acceptance criteria:**
- [ ] `requestId` sinh ở đầu `chat.py`, truyền xuyên graph
- [ ] `chat.py` giữ lại id của USER message (hiện đang bỏ qua kết quả
      `create_message` role USER) + `assistant_message_id` đã có → đưa vào payload
- [ ] Mỗi line: `seq`, `nodeName` (tên node, vd `GenerationSynthesisNode`),
      `attempt`, `chatModelId`, snapshot provider/model/sourceType, tokens, cost,
      latency, status, `errorCode`
- [ ] Kết thúc graph (thành công, lỗi, client huỷ stream) → đóng recorder đúng 1
      lần: settle budget (Task 9) rồi đẩy payload vào outbox (Task 7)
- [ ] Không có line nào (request không gọi provider) → chỉ settle để trả
      reservation, **không** đẩy payload
- [ ] Thời gian trong payload là ISO-8601 UTC (`...Z`)
- [ ] Parent `status`: SUCCESS / ERROR / PARTIAL (có line lỗi nhưng request vẫn
      trả lời được)

**Verification:**
- [ ] pytest: request có classification + transformation + generation → 3 line;
      failover generation → 4 line, line lỗi `attempt=0`, line thành công `attempt=1`
      khác provider
- [ ] pytest: client disconnect giữa stream → payload vẫn được đẩy vào outbox

**Dependencies:** Task 5

**Files likely touched:**
- `unisage-agent/app/core/usage_recorder.py`
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/api/v1/chat.py`
- `unisage-agent/tests/core/test_usage_recorder.py`

**Estimated scope:** M

---

### Task 7: Outbox Redis + worker drain về Java

**Acceptance criteria:**
- [ ] `enqueue(payload)` = `LPUSH usage:outbox` — không gọi HTTP trong request
- [ ] Celery task `drain_usage_outbox` (beat mỗi 5s) giữ lock
      `usage:outbox:lock` (`SET NX EX 60`) → chỉ 1 drainer; đầu lượt đưa toàn bộ
      `usage:outbox:processing` về outbox (reclaim item kẹt do worker chết)
- [ ] Mỗi item: `LMOVE usage:outbox → usage:outbox:processing`, gửi
      `POST /internal/usage-logs` kèm `X-Internal-Secret`; 2xx → `LREM` khỏi
      processing; lỗi mạng/5xx → trả lại outbox, dừng lượt (thử lại ở nhịp beat
      sau); 4xx → `usage:outbox:dead` + log error; gửi lại an toàn nhờ
      idempotency `requestId`
- [ ] Metric/log: độ dài outbox, số dead

**Verification:**
- [ ] pytest: Java down → Chat trả lời bình thường, outbox tăng; Java lên lại →
      outbox rỗng, Java nhận đủ; gửi trùng không sinh record trùng

**Dependencies:** Task 2, Task 6

**Files likely touched:**
- `unisage-agent/app/core/usage_outbox.py`
- `unisage-agent/app/worker/tasks/usage_outbox.py`
- `unisage-agent/app/integrations/backend_java_client.py`
- `unisage-agent/tests/core/test_usage_outbox.py`

**Estimated scope:** M

---

### Task 8: Extraction + Embedding ghi usage

**Acceptance criteria:**
- [ ] Extraction: 1 request nghiệp vụ = 1 lần enrich 1 chunk/batch, `purpose =
      EXTRACTION`, line theo từng attempt (có failover)
- [ ] Embedding: 1 request = 1 batch embed, `purpose = EMBEDDING`, message id null
- [ ] Hook tại điểm đã chốt ở Task 0 (sau khi 2 file đã chuyển sang registry/LiteLLM)

**Verification:**
- [ ] pytest cho cả 2 luồng: đúng purpose, đúng token từ usage của response

**Dependencies:** Task 7

**Files likely touched:**
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/app/rag/embeddings/openai_embedder.py` (hoặc embedder mới của Model Registry)

**Estimated scope:** S

---

## Checkpoint: Phase 2
- [ ] 1 request chat thật → 1 parent + N line ở Java, token khớp usage provider
      trả về (đối chiếu thủ công)
- [ ] Tắt Java 2 phút trong lúc chat → không mất record sau khi Java lên lại
- [ ] Review với human trước khi làm Phase 3

---

## Phase 3: Python — Budget enforcement (soft limit + reservation)

### Task 9: Lua reserve/settle request + acquire/release provider + snapshot

**Description:** Hiện thực đúng mục "Budget semantics" trong plan.

**Acceptance criteria:**
- [ ] `BudgetSnapshot` load từ `GET /internal/budgets/snapshot`, refresh theo
      `BUDGET_SNAPSHOT_REFRESH_SECONDS` và khi nhận `config_version` mới; lỗi load
      → giữ snapshot cũ (fail-open, vì soft limit)
- [ ] `Settings` thêm `APP_TIMEZONE` và các biến `BUDGET_*` (placeholder đã có
      trong `.env.example`)
- [ ] Mọi số tiền trong Redis là **micro-USD integer** (`INCRBY`/`DECRBY`),
      helper `to_micro_usd(Decimal)` làm tròn half-up
- [ ] `reserve_request.lua`: atomic check BLOCK/THROTTLE cho SYSTEM + PURPOSE,
      cộng `reserved`/`inflight`, ghi field `req` vào `budget:resv:{requestId}` +
      ZSET expiry; trả `OK` / `REJECT_EXCEEDED` / `REJECT_THROTTLED`
- [ ] `acquire_provider.lua(requestId, seq, provider, estimate)`: atomic check
      BLOCK/THROTTLE của PROVIDER, cộng `reserved`/`inflight` provider, ghi field
      `p:{seq}` (ghi cả khi không có budget PROVIDER, số 0); trả `OK` /
      `DENY_EXCEEDED` / `DENY_THROTTLED`
- [ ] `release_provider.lua(requestId, seq, actual)`: trừ đúng số trong `p:{seq}`,
      cộng `committed` provider bằng cost thực, xoá field; field không còn → no-op
- [ ] `settle_request.lua`: release mọi `p:*` còn sót, trừ field `req`, cộng
      `committed` SYSTEM/PURPOSE; UNPRICED cộng estimate; marker
      `budget:settled:{requestId}` → gọi lại no-op
- [ ] `periodKey` sinh theo `Settings.APP_TIMEZONE`; TTL key = hết kỳ + 3 ngày
- [ ] Lỗi Redis → fail-open (cho qua, log error), không chặn Chat

**Verification:**
- [ ] pytest với Redis thật (container test): 50 request đồng thời SYSTEM BLOCK
      đủ ~10 estimate → ≤ 10 `OK`; 50 acquire đồng thời PROVIDER BLOCK đủ ~10 →
      ≤ 10 `OK`; PROVIDER THROTTLE cap 3 → `inflight` không bao giờ > 3 và về 0
      khi xong; release/settle 2 lần không cộng đôi; biên 23:59:59/00:00:01 giờ
      VN ra 2 `periodKey`; cộng 10.000 lần $0.0000015 không drift

**Dependencies:** Task 4, Task 5

**Files likely touched:**
- `unisage-agent/app/core/budget/{snapshot.py,tracker.py}`
- `unisage-agent/app/core/budget/lua/{reserve_request,settle_request,acquire_provider,release_provider}.lua`
- `unisage-agent/app/core/config.py`
- `unisage-agent/tests/core/budget/test_tracker.py`

**Estimated scope:** M

---

### Task 10: Gắn reservation vào luồng + acquire/release trong router

**Acceptance criteria:**
- [ ] Chat: reserve trước node LLM đầu tiên với estimate × multiplier Chat;
      `REJECT_EXCEEDED` → lỗi `BUDGET_EXCEEDED`, `REJECT_THROTTLED` →
      `BUDGET_THROTTLED` (429), **0** call provider; message lỗi thân thiện
      cho user
- [ ] Extraction/Embedding: reserve mỗi call/batch; bị từ chối → job ingest đánh
      dấu lỗi retry được, không retry ngay
- [ ] `model_router` gọi `acquire_provider` trước mỗi candidate; `DENY_*` → bỏ
      credential, thử candidate kế tiếp; hết candidate → lỗi budget tương ứng
- [ ] Mỗi attempt kết thúc (thành công, lỗi, huỷ stream) → `release_provider`
      với cost thực của line đó, **trước** khi failover acquire provider mới
- [ ] Mọi nhánh kết thúc (kể cả exception) đều gọi settle — dùng
      `try/finally` trong `UsageRecorder`

**Verification:**
- [ ] pytest đủ ma trận scope × action trong plan (9 ô)
- [ ] pytest: budget PROVIDER openai BLOCK, SYSTEM còn → request đi Anthropic
- [ ] pytest: failover openai → anthropic giữa chừng → `reserved`/`inflight`
      openai về 0 ngay sau attempt lỗi, anthropic được acquire

**Dependencies:** Task 6, Task 8, Task 9

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/core/usage_recorder.py`
- `unisage-agent/app/api/exceptions.py`
- `unisage-agent/app/api/v1/chat.py`

**Estimated scope:** M

---

### Task 11: Đối soát + release reservation treo

**Acceptance criteria:**
- [ ] `release_expired_reservations` (beat mỗi 1 phút): release reservation có
      score ZSET quá hạn — trả `reserved`/`inflight` của `req` và mọi `p:*`, không
      cộng `committed`, xoá hash
- [ ] `reconcile_budget_committed` (beat mỗi 1 giờ): Lua đọc atomic độ dài
      outbox/processing + `committed` (`C0`); còn item → bỏ lượt; lấy `dbTotal` từ
      `period-totals`; `INCRBY committed (dbTotal − C0)` (không `SET`); lệch > 5%
      → log warning

**Verification:**
- [ ] pytest: reservation treo được release; outbox còn item → không ghi đè

**Dependencies:** Task 9, Task 7

**Files likely touched:**
- `unisage-agent/app/worker/tasks/budget_reconciliation.py`
- `unisage-agent/tests/worker/test_budget_reconciliation.py`

**Estimated scope:** S

---

### Task 11b: Deploy Celery worker/beat, Redis AOF, health outbox, replay dead-letter

**Description:** Agent hiện chỉ có `celery_app.py` và hướng dẫn chạy worker
trong README, chưa có beat, chưa có task định kỳ nào, Redis devcontainer không
bật persistence.

**Acceptance criteria:**
- [ ] `celery_app.py` cấu hình `beat_schedule` cho `drain_usage_outbox` (5s),
      `release_expired_reservations` (1 phút), `reconcile_budget_committed` (1 giờ)
- [ ] Taskfile thêm `worker` và `beat`; README cập nhật lệnh chạy (Windows +
      Linux)
- [ ] `.devcontainer/docker-compose.yml`: service `celery-worker`, `celery-beat`;
      Redis `command: redis-server --appendonly yes --appendfsync everysec` +
      volume `redis_data`
- [ ] `GET /api/v1/health` trả `usageOutbox.pending`/`usageOutbox.dead`;
      `dead > 0` → `status = degraded`
- [ ] Lệnh `task usage:replay-dead` (script) chuyển toàn bộ
      `usage:outbox:dead` về outbox, in số item đã chuyển

**Verification:**
- [ ] `docker compose restart redis` → outbox, dead-letter, counter còn nguyên
- [ ] Tắt beat → outbox tăng; bật lại → drain hết
- [ ] Đẩy 1 payload sai schema → vào dead, health `degraded`; replay sau khi sửa
      → Java nhận

**Dependencies:** Task 7, Task 11

**Files likely touched:**
- `unisage-agent/app/worker/celery_app.py`
- `unisage-agent/Taskfile.yml`, `unisage-agent/taskfiles/*.yml`
- `unisage-agent/README.md`
- `unisage-agent/.devcontainer/docker-compose.yml`
- `unisage-agent/app/api/v1/health.py`
- `unisage-agent/scripts/replay_usage_dead_letter.py`

**Estimated scope:** M

---

## Checkpoint: Phase 3
- [ ] Budget SYSTEM BLOCK = $0 → request Chat bị từ chối ngay, không call provider
- [ ] Budget PROVIDER = $0 BLOCK, SYSTEM còn → request thành công qua provider khác
- [ ] Kill process giữa request → sau TTL, `reserved`/`inflight` về đúng
- [ ] Restart Redis → không mất outbox/counter
- [ ] Review với human trước khi làm Phase 4

---

## Phase 4: Cảnh báo (Java)

### Task 12: Job phát hiện ngưỡng + spike, claim atomic

**Acceptance criteria:**
- [ ] `@Scheduled` (cron env `BUDGET_ALERT_CHECK_CRON`, mặc định mỗi 2 phút): mỗi
      budget enabled, tính spend kỳ hiện tại từ DB (cùng quy tắc period-totals),
      mỗi ngưỡng trong `BudgetAlertSetting` đã đạt × mỗi kênh đang bật → claim
- [ ] Spike (chạy 1 lần/ngày sau 00:05 giờ VN): spend hôm qua so trung bình 7
      ngày trước đó, vượt `spikeThresholdPercent` → claim `SPIKE:{date}:{channel}`
- [ ] Claim = `INSERT ... ON CONFLICT (dedupe_key) DO NOTHING` với status
      `PENDING`, `attemptCount = 0`, `nextAttemptAt = now`
- [ ] Bước gửi lấy dòng `PENDING`/`FAILED` có `next_attempt_at <= now` bằng
      `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n` → 2 instance không gửi trùng
- [ ] Thêm ngưỡng mới giữa kỳ → chỉ gửi ngưỡng mới, không gửi lại ngưỡng cũ

**Verification:**
- [ ] `./mvnw test` (Testcontainers): 2 thread chạy job cùng lúc → mỗi
      `dedupeKey` 1 dòng và gửi đúng 1 lần; qua kỳ mới → gửi lại được

**Dependencies:** Task 3, Task 4

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/scheduler/BudgetAlertJob.java`
- `backend-java/src/main/java/com/unisage/backend/service/budget/BudgetAlertServiceImpl.java`
- `backend-java/src/main/java/com/unisage/backend/repository/BudgetAlertLogRepository.java`

**Estimated scope:** M

---

### Task 13: Gửi In-app/Email/Slack

**Acceptance criteria:**
- [ ] IN_APP: bản ghi claim chính là alert, status `SENT` ngay
- [ ] EMAIL: dùng `spring-boot-starter-mail` (đã có trong pom, chưa dùng) —
      `application.properties` map `spring.mail.*` từ `SPRING_MAIL_HOST`,
      `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` và
      `app.budget-alert.mail.from` từ `BUDGET_ALERT_MAIL_FROM` (placeholder đã có
      trong `.env.example`/`.ENV`)
- [ ] Host hoặc from trống → dispatcher không gọi `JavaMailSender`, đánh `SKIPPED`
      (lấy sender qua `ObjectProvider`, kiểm `StringUtils.hasText`)
- [ ] `management.health.mail.enabled=false` — không để SMTP trống/lỗi kéo
      `/actuator/health` (public, System Health đang dùng) sang DOWN
- [ ] SLACK: `SlackWebhookClient` đọc `app.budget-alert.slack.*` ←
      `BUDGET_ALERT_SLACK_ENABLED`, `BUDGET_ALERT_SLACK_WEBHOOK_URL`,
      `BUDGET_ALERT_SLACK_CHANNEL_LABEL`, `BUDGET_ALERT_SLACK_TIMEOUT_MS` (placeholder
      đã có trong `.env.example`/`.ENV`); disabled hoặc URL trống → `SKIPPED`;
      không log URL
- [ ] Gửi lỗi → `FAILED`, `attemptCount += 1`, `lastAttemptAt`, `nextAttemptAt =
      now + 2^attemptCount phút`, `errorMessage`; `attemptCount = 3` vẫn lỗi →
      `GAVE_UP`, không retry nữa
- [ ] Nội dung: budget/scope, kỳ, spend/limit, %, link tới trang Cost Management

**Verification:**
- [ ] `./mvnw test` với mock mail sender/mock HTTP: env trống → `SKIPPED` và
      health UP; lỗi 3 lần → `GAVE_UP`
- [ ] Manual: điền webhook thật vào `.ENV`, hạ limit → thấy đúng 1 message Slack

**Dependencies:** Task 12

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/integration/SlackWebhookClient.java`
- `backend-java/src/main/java/com/unisage/backend/service/budget/BudgetAlertDispatcher.java`
- `backend-java/src/main/resources/application.properties`

**Estimated scope:** M

---

## Checkpoint: Phase 4
- [ ] Spend vượt 80% → alert đúng các kênh bật, đúng 1 lần/kênh/kỳ
- [ ] Review với human trước khi làm Phase 5

---

## Phase 5: unisage-web — Trang AI Cost Management

### Task 14: Wiring

**Acceptance criteria:**
- [ ] `ROUTE_SEGMENTS.costManagement = "cost-management"`
- [ ] Entry `FEATURE_REGISTRY` workspace `system-admin`, label "Chi phí AI",
      icon `Wallet`, `requiredPermissions: PERMISSION_POLICIES.costManagement`,
      fallback `AccessDeniedPage`
- [ ] `PERMISSION_POLICIES.costManagement` và `costManagementBudgets`
- [ ] `features/cost-management/`: `api/` (client theo pattern feature khác),
      `query-keys.ts`, `schemas.ts` (zod cho summary, usage list/detail, budget,
      alert setting, alert log), trang có 5 tab (tab lưu trên URL search param)

**Verification:**
- [ ] `npm run typecheck` + `npm run lint`; user thiếu permission không thấy menu

**Dependencies:** Task 3, Task 4

**Files likely touched:**
- `unisage-web/src/routes/feature-registry.tsx`
- `unisage-web/src/constants/paths.ts`
- `unisage-web/src/features/auth/utils/permission-policies.ts`
- `unisage-web/src/features/cost-management/{api,query-keys.ts,schemas.ts}`
- `unisage-web/src/pages/system-admin/cost-management-page.tsx`

**Estimated scope:** M

---

### Task 15: Tab Tổng quan

**Acceptance criteria:**
- [ ] 4 KPI card + card "Chưa định giá"; progress đổi màu 50/80%
- [ ] Donut purpose, bar provider/model, line theo ngày, top 10 user/IP; filter
      thời gian/purpose/provider

**Verification:** Manual trong browser với dữ liệu seed

**Dependencies:** Task 14

**Files likely touched:** `unisage-web/src/features/cost-management/overview/`

**Estimated scope:** M

---

### Task 16: Tab Ngân sách & Giới hạn

**Acceptance criteria:**
- [ ] Bảng budget có % đã dùng; form Scope → hiện dropdown provider (từ Model
      Registry) hoặc purpose; Action THROTTLE → hiện input concurrency
- [ ] Hiển thị lỗi validate từ Java (trùng budget enabled)
- [ ] Ghi chú "giới hạn mềm" và hành vi fallback/từ chối theo scope

**Verification:** Manual tạo/sửa/xoá budget, đối chiếu DB

**Dependencies:** Task 14

**Files likely touched:** `unisage-web/src/features/cost-management/budgets/`

**Estimated scope:** M

---

### Task 17: Tab Cảnh báo + `BudgetAlertBanner`

**Acceptance criteria:**
- [ ] Form `BudgetAlertSetting`: ngưỡng (thêm/xoá custom), spike, in-app, email
      recipients, Slack toggle (disabled + tooltip nếu `slackConfigured=false`,
      hiển thị `slackChannelLabel` nếu có)
- [ ] Bảng lịch sử alert có filter + phân trang
- [ ] `BudgetAlertBanner` trong layout system-admin, poll `GET /budget-alerts/active`
      60s, dismiss được

**Verification:** Manual trong browser

**Dependencies:** Task 13, Task 14

**Files likely touched:**
- `unisage-web/src/features/cost-management/alerts/`
- `unisage-web/src/features/cost-management/components/budget-alert-banner.tsx`
- layout system-admin

**Estimated scope:** M

---

### Task 18: Tab Bảng giá

**Acceptance criteria:**
- [ ] Bảng tĩnh các model đang đăng ký trong Model Registry; SELF_HOSTED "Không
      tính phí"; ghi chú "chỉ tham khảo"

**Verification:** Manual

**Dependencies:** Task 14

**Files likely touched:**
- `unisage-web/src/features/cost-management/pricing/`

**Estimated scope:** S

---

### Task 19: Tab Lịch sử sử dụng + drawer

**Acceptance criteria:**
- [ ] Bảng parent có filter + phân trang, badge failover
- [ ] Drawer: query, answer, chunks trích dẫn, bảng line (node, provider/model,
      attempt, tokens, cost/costStatus, latency, lỗi), tổng cost, user/IP,
      Request ID; message đã xoá → "Nội dung đã bị xoá"

**Verification:** Manual: 1 request chat có failover → drawer thấy 2 line đúng

**Dependencies:** Task 14

**Files likely touched:** `unisage-web/src/features/cost-management/usage-history/`

**Estimated scope:** L — tách bảng và drawer nếu quá lớn

---

## Checkpoint: Phase 5
- [ ] 5 tab render dữ liệu thật; banner xuất hiện khi có alert IN_APP chưa dismiss

---

## Ngoài scope (đã chốt)
- **USER_GROUP budget** — code chưa có khái niệm nhóm người dùng.
- **Notification center** — `features/notifications` vẫn là placeholder; chỉ làm
  banner budget.
- **`/profile`** — giữ `UsageLimitCard` hiện có, không gộp với `RequestUsageLog`.

---

## Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ bảng "Test bắt buộc" trong `plan.md` pass
- [ ] SA xem dashboard, set budget, nhận cảnh báo, xem chi tiết request qua UI
- [ ] Ready for review
