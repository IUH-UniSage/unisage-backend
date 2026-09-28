# Implementation Plan: Cost Tracking + Budget Management

## Overview

Phase này nối tiếp **sau khi** Dynamic Model Registry + Runtime Active Switch
(`changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/`) đã triển khai
xong Phase 0-6 (model native PydanticAI trong `get_graph_models()`,
`model_router`, failover Chat/Extraction, Embedding đọc registry — xem
"Phản hồi review" mục 6 bên dưới cho quyết định về prerequisite gate).

**Quyết định kiến trúc gọi provider (ADR 0005 của plan Model Registry): dùng
model native của PydanticAI theo từng provider, không dùng LiteLLM SDK để gọi
provider.** Plan này **không mâu thuẫn** với quyết định đó — `litellm` ở đây
chỉ được dùng như **thư viện tra cứu giá offline** (`litellm.completion_cost()`,
`litellm.cost_per_token()`), đọc bảng giá tĩnh đóng gói sẵn trong package, không
mở kết nối mạng, không phải là đường gọi provider. Test kiến trúc của plan
Model Registry (`test_no_raw_provider_clients.py`, mục "SSRF policy" của plan
đó) quét `litellm.*completion(`/`embedding(` — tức các hàm **gọi provider thật**
(`litellm.completion`, `litellm.acompletion`, `litellm.embedding`) — không quét
`completion_cost`/`cost_per_token`, nên 2 hàm định giá này không vi phạm rào
SSRF/factory duy nhất. Task 0 của plan này (spike) phải xác nhận lại bằng code
thật: gọi `litellm.cost_per_token(model=..., prompt_tokens=..., completion_tokens=...)`
không phát sinh network call (mock `httpx`/socket, assert 0 request), rồi mới
được coi là chốt.

**Cập nhật (vòng review 3):** Dynamic Model Registry đã được triển khai —
`model_router`/factory provider (Task 0.6, Task 5 của plan đó) và model native
PydanticAI theo provider đã tồn tại trong code, thay cho `OpenAIChatModel`
(`app/api/deps.py`) và OpenAI SDK trực tiếp (`multi_representation.py`,
`openai_embedder.py`) mà bản plan gốc mô tả. Điều **chưa** tồn tại là móc nối
Cost Tracking vào các điểm đó: `UsageRecorder` (Task 6), hook usage trong
`model_router` đủ trường cho Cost (Task 0 của plan này), `cost_calculator`
(Task 5) — đây là lý do **Phase 0 của plan này vẫn là cổng chặn**: không bắt
đầu Phase 2 khi chưa chốt được điểm lấy usage/token thật cho từng loại call
*từ code registry thật đang có*, không phải từ giả định lý thuyết.

SA hiện không có cách nào biết hệ thống đang tốn bao nhiêu tiền LLM mỗi
ngày/tháng, theo chức năng nào (Chat/Extraction/Embedding), hay dừng lại khi
vượt ngân sách. Phase này bổ sung: đo cost từng LLM call, dashboard, budget &
giới hạn chi tiêu (soft limit + reservation), cảnh báo, lịch sử/đối soát, trang
chi tiết từng request.

**Routing Policy nâng cao (LOWEST_COST/BALANCED/PRIORITY/QUALITY_FIRST) không
nằm trong phase này** — là Phase 9 trong plan Active Switch, tiêu thụ dữ liệu
cost/latency theo credential mà `RequestUsageLine` của phase này tạo ra.

## Phản hồi review

**Ghi chú:** các bảng "Vòng 1"/"Vòng 2" bên dưới là **lịch sử** — quyết định ở
đó có thể đã bị **superseded** bởi vòng sau (vd blocker 5 ở Vòng 1 và blocker 4
ở Vòng 2 đã bị Vòng 3 thay thế). Quyết định **hiện hành** luôn là bảng có số
vòng **lớn nhất**; không lấy quyết định ở vòng cũ làm căn cứ implement nếu vòng
mới hơn có ghi đè.

**Vòng 1 (lịch sử — SUPERSEDED một phần, xem dòng ⚠):**

| Blocker trong review | Quyết định |
|---|---|
| 1. 1 record/1 `chatModel` không đủ cho multi-model/failover | Parent `RequestUsageLog` + child `RequestUsageLine` (1 dòng/1 LLM hoặc embedding call, kể cả attempt failover lỗi), snapshot provider/model/sourceType tại thời điểm gọi |
| 2. Budget chưa phải hard limit, race khi concurrent | **Soft limit** (đã chốt) + **reservation mỗi request** bằng Redis Lua atomic; THROTTLE = giới hạn concurrency; ma trận hành vi theo scope ở mục "Budget semantics" |
| 3. USER_GROUP/`scopeRefId` không có domain | Bỏ USER_GROUP khỏi phase này (code chưa có khái niệm nhóm). Thay `scopeRefId` bằng 2 cột có kiểu: `scopeProvider` (String), `scopePurpose` (enum) |
| 4. Thiếu persistence cho alert config, debounce có race | Entity `BudgetAlertSetting`; `BudgetAlertLog.dedupeKey` UNIQUE + claim bằng `INSERT ... ON CONFLICT DO NOTHING`; Slack cấu hình qua **env** (đã chốt) |
| ⚠ 5. Endpoint nội bộ không qua được RBAC | ~~Whitelist tường minh trong `InternalSecretFilter` + `PredefinedPublicPaths`~~ — **SUPERSEDED bởi Vòng 3 blocker 3**: đổi sang mô hình `TRUSTED_INTERNAL_CALLER_ATTRIBUTE` của plan Model Registry, không dùng `PredefinedPublicPaths` |
| 6. Prerequisite chưa có | Phase 0 = gate nghiệm thu Model Registry + spike chốt nguồn usage cho PydanticAI/LiteLLM/embedding/extraction (⚠ điều kiện "nghiệm thu" bị nới lỏng ở Vòng 3 blocker 4) |
| Thiếu sót khác | Lưu cả `userMessageId` + `assistantMessageId`; FK `ON DELETE SET NULL`; `requestId` idempotent; quy tắc `costUsd = null`; timezone `app.timezone`; index bổ sung; RBAC `USAGE_LOG`/`BUDGET`; spec đầy đủ cho UI |

**Vòng 2 (lịch sử — SUPERSEDED một phần, xem dòng ⚠):**

| Blocker | Quyết định |
|---|---|
| 1. PROVIDER budget không có reservation atomic | Mỗi provider attempt gọi `acquire_provider.lua` (check + tăng `reserved`/`inflight` atomic) và `release_provider.lua` khi attempt xong; failover = release provider cũ, acquire provider mới |
| 2. Unique index với cột NULL không chặn trùng SYSTEM | 3 partial unique index riêng cho SYSTEM/PROVIDER/PURPOSE + CHECK constraint theo scope |
| 3. `BudgetAlertSetting` 1 dòng chưa enforce; retry alert không có trạng thái | PK cố định `id = 1` + `CHECK (id = 1)`, seed bằng migration, API chỉ GET/PUT; `BudgetAlertLog` thêm `attemptCount`, `nextAttemptAt`, `lastAttemptAt`, status `GAVE_UP` |
| ⚠ 4. Version migration sai (repo đang ở V15) | ~~Chốt số: Model Registry V16, plan này V17-V19, Routing Policy V20~~ — **SUPERSEDED bởi Vòng 3 blocker 2**: giả định "repo ở V15" sai; số đúng hiện hành là V25-V27 (plan này) / V28 (Routing Policy) |
| 5. `timestamptz` không khớp `LocalDateTime` | Giữ `timestamp(6) without time zone` chứa giá trị **UTC** (convention của `Clock.systemUTC()` / `UsageLimitServiceImpl`); cắt kỳ theo `APP_TIMEZONE` rồi đổi sang UTC khi query |
| 6. Outbox worker, scheduler, Redis persistence chưa có deploy | Task 11b: Celery beat + task drain/reclaim/release/reconcile, Taskfile + README, Redis AOF + volume, health báo outbox/dead-letter, lệnh replay dead-letter |
| 7. Mail env chưa có | Đã thêm `SPRING_MAIL_*` + `BUDGET_ALERT_MAIL_FROM` vào `.env.example`/`.ENV`; thiếu host/from → `SKIPPED`; tắt `management.health.mail` |
| Bổ sung | Request không có LLM call → không tạo parent; `conversationId` FK ON DELETE SET NULL; Redis lưu **micro-USD integer**; `APP_TIMEZONE` + biến budget đã thêm vào `unisage-agent/.env.example` |

**Vòng 3 (⚠ blocker 1 được bổ sung ở Vòng 4, xem dòng đó):**

| Blocker | Quyết định |
|---|---|
| ⚠ 1. Mâu thuẫn LiteLLM với ADR 0005 (model native PydanticAI, cấm LiteLLM SDK gọi provider) | `litellm` trong plan này chỉ là **thư viện tra giá offline** (`completion_cost`/`cost_per_token`), không gọi provider — xem Overview + Task 0 acceptance criteria (test không network call). Không dùng `litellm.acompletion`/`litellm.aembedding` ở đâu trong plan này. **Bổ sung ở Vòng 4:** rule kiến trúc `test_no_raw_provider_clients.py` phía plan Model Registry vẫn cấm `import litellm` tuyệt đối — đã sửa để whitelist đúng module `cost_calculator.py` |
| 2. Migration version sai, đụng thực tế (repo đã ở V24, migration state machine của Model Registry đã lên DB dưới tên `V18__add_chat_model_purpose_and_status.sql`, không phải V16 như plan Model Registry ghi) | Chốt lại: plan này dùng **V25-V27**, Routing Policy (Phase 9 của Model Registry) dời sang **V28** — xem "Migration versions" đã cập nhật bên dưới; luôn chạy `ls db/migration \| sort -V \| tail -1` trước khi tạo file vì đây là dự án dùng chung với các thay đổi khác trên `main` |
| 3. Internal API không nhất quán với mô hình bảo mật đã chốt ở plan Model Registry (secret set request attribute, `DynamicAuthorizationManager` cấp quyền theo attribute + pattern path, **không** qua `PredefinedPublicPaths`) | Đổi theo đúng mô hình Model Registry — xem "Internal API & bảo mật" đã sửa bên dưới; 3 endpoint của plan này được thêm vào cùng 1 danh sách `INTERNAL_ONLY_PATHS`/coverage test với 7 endpoint của Model Registry (tổng 10) |
| 4. Prerequisite Model Registry Phase 0-6 chưa đạt "implementation approved" | **Chủ động bỏ qua theo quyết định của product owner**: Dynamic Model Registry đã được triển khai và test xong ở mức đủ dùng cho plan này (dù chưa đạt mọi tiêu chí ở mục "Gate: implementation approved" của plan đó); Task 0 không còn chặn cứng vào trạng thái approve hình thức của plan kia, chỉ chặn vào việc spike thực sự xác nhận được nguồn usage/token — xem Task 0 đã sửa |
| Bổ sung | UNIQUE `(usage_log_id, seq)` ở DB cho `RequestUsageLine`, không chỉ validate ở service; làm rõ điều kiện kích hoạt THROTTLE (mục "Budget semantics"); quy tắc đối soát khi outbox rỗng nhưng vẫn còn item trong `usage:outbox:dead`; hook native duy nhất trước/sau mỗi attempt bao gồm usage ở chunk cuối stream, provider/model sau failover, latency, `chatModelId` |

**Vòng 4 (quyết định hiện hành, chỉ sửa nốt phần còn sót của Vòng 3):**

| Blocker | Quyết định |
|---|---|
| 1. Rule kiến trúc của plan Model Registry (`test_no_raw_provider_clients.py`) vẫn cấm mọi `import litellm` trong agent, mâu thuẫn với việc plan này cần import litellm để tra giá | Sửa rule ở phía plan Model Registry (`todo.md` Task 0.6): whitelist đúng 1 module `app/core/cost_calculator.py` được phép `import litellm`; **trong chính module đó** vẫn quét và cấm tuyệt đối `litellm.completion(`/`acompletion(`/`embedding(`/`aembedding(` và mọi cách tạo provider transport (httpx/AsyncOpenAI/`client=`/`api_base=` ra ngoài) — module chỉ được làm phép tính giá thuần, không mở kết nối mạng. Mọi file khác trong `app/` vẫn cấm `import litellm` hoàn toàn |
| 2. Task 18 (Routing Policy) của plan Model Registry vẫn ghi `V20__add_routing_policy.sql` trong cả plan.md và todo.md — tham chiếu **active**, không phải lịch sử | Đã sửa cả 2 file của plan Model Registry thành `V28__add_routing_policy.sql`, khớp với bảng "Migration version" đã chốt |
| 3. Plan này yêu cầu coverage 10 endpoint, nhưng plan Model Registry vẫn mô tả coverage là đúng 7 — hai tiêu chí nghiệm thu khác nhau nếu không ghi rõ | Sửa plan Model Registry, mục "Internal API contract": checkpoint gốc (Phase 0.5, độc lập) vẫn là **7** đúng như trước; sau khi Cost Tracking Task 2 merge, coverage test (bản mở rộng) phải là **10** — không đổi số 7 ở checkpoint gốc, chỉ ghi thêm mốc sau khi tích hợp |
| 4. Mô tả cũ còn gây nhiễu | Sửa Overview (không còn nói "code chưa có model_router/factory" — Model Registry đã triển khai xong, chỉ Cost Tracking chưa móc nối vào); sửa risk table dùng đúng V25-V28 thay vì V16-V20; bỏ `PredefinedPublicPaths.java` khỏi "Files likely touched" của Task 2 (todo.md); gắn nhãn "lịch sử — SUPERSEDED một phần" cho các bảng Vòng 1/Vòng 2/Vòng 3 ở trên |

## Architecture Decisions

- **Không tự xây bảng giá trong DB, không scrape giá.** Cost thật tính bằng
  `litellm.completion_cost()` (chat) / `litellm.cost_per_token()` (embedding,
  và ước tính cho reservation). Giá thực SA trả có thể khác giá public do hợp
  đồng riêng — chấp nhận, dashboard ghi rõ "theo giá niêm yết LiteLLM".
- **`SELF_HOSTED` = cost $0**, vẫn ghi `RequestUsageLine` (để có token/latency
  cho routing) nhưng `costStatus = FREE`, không reserve, không trừ budget.
- **Bảng giá tab Pricing là dữ liệu tĩnh ở FE, chỉ tham khảo.** Số liệu
  dashboard/budget luôn từ `RequestUsageLog`/`RequestUsageLine`.
- **Cost tính ở Python, gửi về Java theo từng call (line), không gộp thành 1
  con số.** Python gửi 1 payload/request gồm parent + danh sách line. Java không
  cần biết cấu trúc graph — `nodeName` trên line chỉ là nhãn hiển thị. Tổng của
  request được Java tính khi insert và lưu denormalized trên parent để dashboard
  query nhanh.
- **Ghi usage qua outbox Redis, không gọi Java trực tiếp trong request.** Python
  `LPUSH` payload vào `usage:outbox` (nhanh, không phụ thuộc Java sống); worker
  drain gửi `POST /internal/usage-logs` với retry/backoff. Java down → payload
  nằm chờ trong outbox, không mất và không làm chậm Chat.
- **Idempotency theo `requestId`.** `request_usage_logs.request_id` UNIQUE;
  Java insert bằng `ON CONFLICT (request_id) DO NOTHING` và trả 200 kèm id có sẵn
  → worker retry an toàn. First-write-wins, không merge.
- **Budget là soft limit, enforcement ở Python qua Redis, không gọi Java đồng
  bộ** (giữ fast-path constraint của Model Registry). Chi tiết ở "Budget
  semantics".
- **Alert do Java phát hiện và gửi** (nguồn sự thật là DB; SMTP và in-app đều ở
  Java; 1 chỗ duy nhất để dedupe). Slack gửi bằng 1 client webhook nhỏ ở Java,
  URL đọc từ env `BUDGET_ALERT_SLACK_*` (không lưu DB, không nhập qua UI). Có thể
  trỏ cùng webhook với `SLACK_APIKEY_ALERT_WEBHOOK_URL` của Python nếu SA muốn
  chung channel — đó là việc cấu hình env, không phải code dùng chung.
- **Mọi kỳ DAILY/MONTHLY tính theo `app.timezone`** (`APP_TIMEZONE`, mặc định
  `Asia/Ho_Chi_Minh`, đã dùng bởi `UsageLimitServiceImpl`). Python đọc cùng
  biến (`Settings.APP_TIMEZONE`) để sinh `periodKey` Redis khớp với Java.
- **Thời gian lưu `timestamp(6) without time zone`, giá trị luôn là UTC** — cùng
  convention hiện có (`TimeConfig` cung cấp `Clock.systemUTC()`, migration V15
  dùng `timestamp without time zone`). Entity dùng `LocalDateTime`. Mốc kỳ tính
  ở `APP_TIMEZONE` rồi đổi sang UTC trước khi so sánh; group theo ngày dùng
  `(started_at AT TIME ZONE 'UTC') AT TIME ZONE :tz`. Query kỳ **không** dựa vào
  `BaseEntity.createdAt` (auditing lấy giờ theo default zone của JVM, không đảm
  bảo UTC) mà dùng `startedAt`/`occurredAt` do Java set từ `Clock` hoặc parse từ
  ISO-8601 UTC trong payload.
- **Tiền:** DB `NUMERIC(18,8)`; Redis lưu **micro-USD integer** (`INCRBY`,
  `round_half_up(usd × 1_000_000)`), không dùng `INCRBYFLOAT` để tránh drift.
- **Request không có LLM call** (bị chặn trước graph, lỗi validate, cache hit...)
  → **không tạo parent**, chỉ settle để trả reservation. API `POST
  /internal/usage-logs` giữ yêu cầu `lines` không rỗng; dashboard đếm request
  có phát sinh gọi provider.
- **Request detail join `Message` có sẵn, không lưu trùng text/chunk.** Query
  đọc qua `userMessageId`, answer + `citations` qua `assistantMessageId`.
  `citations` chỉ chứa chunk được trích dẫn trong câu trả lời, không phải toàn bộ
  chunk đã retrieve — audit tập retrieve đầy đủ nằm ngoài scope.

## Data Model (Java, control plane)

### `RequestUsageLog` (parent — 1 dòng/1 request nghiệp vụ)
`id`, `requestId` (UUID, UNIQUE), `purpose` (CHAT/EMBEDDING/EXTRACTION),
`conversationId` (FK `conversations`, nullable, ON DELETE SET NULL), `userMessageId` + `assistantMessageId` (FK
`messages`, nullable, **ON DELETE SET NULL**), `userId` (FK, nullable, ON DELETE
SET NULL), `guestIp` (nullable), `status` (SUCCESS/ERROR/PARTIAL),
`totalInputTokens`, `totalOutputTokens`, `totalCachedTokens`, `totalCostUsd`
(tổng các line đã định giá), `estimatedUnpricedCostUsd` (tổng ước tính của line
không định giá được), `unpricedLineCount`, `lineCount`, `latencyMs` (end-to-end),
`startedAt`, `finishedAt`, `createdAt`.

### `RequestUsageLine` (child — 1 dòng/1 lần gọi provider)
`id`, `usageLogId` (FK parent, ON DELETE CASCADE), `seq` (thứ tự trong request),
`nodeName` (vd `GenerationSynthesisNode`, `embed_batch`), `attempt` (0 = lần đầu,
≥1 = failover), `chatModelId` (FK `chat_models`, nullable, ON DELETE SET NULL),
**snapshot**: `provider`, `modelName`, `sourceType` (CLOUD_API/SELF_HOSTED) —
giữ nguyên dù `ChatModel` bị sửa/xoá sau này; `inputTokens`, `outputTokens`,
`cachedTokens`, `costUsd` (nullable), `estimatedCostUsd` (luôn có khi
CLOUD_API), `costStatus` (PRICED/UNPRICED/FREE), `latencyMs`, `status`
(SUCCESS/ERROR), `errorCode` (nullable), `occurredAt` (UTC, lúc attempt bắt đầu).

### Index
- `request_usage_logs`: UNIQUE(`request_id`); (`started_at`);
  (`purpose`, `started_at`); (`user_id`, `started_at`); (`status`,
  `started_at`); (`user_message_id`); (`assistant_message_id`);
  (`conversation_id`).
- `request_usage_lines`: **UNIQUE(`usage_log_id`, `seq`)** — chặn trùng `seq`
  ở tầng DB, không chỉ validate ở service (`RequestUsageLogServiceImpl` vẫn
  validate trước để trả `ErrorCode` rõ ràng thay vì lộ constraint violation, nhưng
  DB là chốt chặn cuối khi có bug/race ở tầng service); (`occurred_at`);
  (`provider`, `occurred_at`); (`chat_model_id`, `occurred_at`).

### `Budget`
`id`, `scope` (SYSTEM/PROVIDER/PURPOSE), `scopeProvider` (String, bắt buộc khi
PROVIDER, so khớp chuẩn hoá lowercase với `ChatModel.provider`), `scopePurpose`
(enum, bắt buộc khi PURPOSE), `period` (DAILY/MONTHLY), `limitUsd`, `action`
(ALERT/THROTTLE/BLOCK), `throttleMaxConcurrency` (bắt buộc khi THROTTLE),
`isEnabled`. Ràng buộc DB (không dùng 1 index gộp vì PostgreSQL coi các NULL là
khác nhau trong unique index):
- `CHECK`: SYSTEM → cả 2 ref NULL; PROVIDER → `scope_provider` NOT NULL và
  `scope_purpose` NULL; PURPOSE → ngược lại; `action = 'THROTTLE'` ⇔
  `throttle_max_concurrency` NOT NULL và > 0.
- `ux_budgets_system (period) WHERE is_enabled AND scope = 'SYSTEM'`
- `ux_budgets_provider (lower(scope_provider), period) WHERE is_enabled AND scope = 'PROVIDER'`
- `ux_budgets_purpose (scope_purpose, period) WHERE is_enabled AND scope = 'PURPOSE'`
- Service map vi phạm unique (`DataIntegrityViolationException`) sang `ErrorCode`
  riêng thay vì 500.

### `BudgetAlertSetting` (singleton, cấu hình toàn cục)
Không kế thừa id UUID của `BaseEntity`: `id SMALLINT PRIMARY KEY DEFAULT 1
CHECK (id = 1)`, dòng duy nhất seed trong migration, API chỉ `GET`/`PUT` (không
có `POST`/`DELETE`) → không thể tạo dòng thứ 2 kể cả khi request đồng thời.
`thresholdsPercent` (mảng int, mặc định `[50, 80, 100]`, cho phép thêm ngưỡng
tuỳ chỉnh 1-200), `spikeDetectionEnabled`, `spikeThresholdPercent` (mặc định
50 = cao hơn 50% so với trung bình 7 ngày), `inAppEnabled` (mặc định true),
`emailEnabled`, `emailRecipients` (mảng email, validate), `slackEnabled`
(toggle phía DB; chỉ có hiệu lực khi env `BUDGET_ALERT_SLACK_ENABLED=true` và có
webhook URL).

### `BudgetAlertLog`
`id`, `alertType` (THRESHOLD/SPIKE), `budgetId` (FK, nullable cho SPIKE, ON
DELETE SET NULL), `periodStart` (date, theo `app.timezone`), `thresholdPercent`
(nullable cho SPIKE), `channel` (IN_APP/EMAIL/SLACK), `dedupeKey` (UNIQUE — vd
`THRESHOLD:{budgetId}:{periodStart}:{threshold}:{channel}`,
`SPIKE:{date}:{channel}`), `spentUsd`, `limitUsd`, `status`
(PENDING/SENT/FAILED/GAVE_UP/SKIPPED), `attemptCount`, `lastAttemptAt`,
`nextAttemptAt`, `errorMessage`, `sentAt`, `dismissedAt`, `dismissedBy` (chỉ
dùng cho IN_APP; dismiss là trạng thái chung cho mọi SA, không theo từng user).
Index (`status`, `next_attempt_at`) cho job retry.

**Vòng đời gửi:** claim → `PENDING` (`attemptCount = 0`, `nextAttemptAt = now`).
Job lấy dòng `PENDING`/`FAILED` có `next_attempt_at <= now` bằng
`SELECT ... FOR UPDATE SKIP LOCKED` (2 instance không gửi cùng 1 dòng), gửi,
`attemptCount += 1`. Thành công → `SENT`; lỗi → `FAILED`, `nextAttemptAt = now
+ 2^attemptCount phút`; `attemptCount = 3` vẫn lỗi → `GAVE_UP`. Kênh chưa cấu
hình (thiếu SMTP/Slack env) → `SKIPPED`, không retry.

## Budget semantics

**Soft limit.** Budget có thể bị vượt, nhưng mức vượt bị chặn trên bởi phần
chênh giữa cost thực và cost đã reserve của các request đang chạy dở. BLOCK chỉ
ngăn request **mới** khi `committed + reserved + estimate > limit`; không huỷ
request đang chạy. Đây là giới hạn vận hành, không phải hoá đơn.

Reservation có **2 tầng**, đều là Lua atomic trên Redis, đơn vị micro-USD
integer:

| Tầng | Khi nào | Scope | Script |
|---|---|---|---|
| Request | 1 lần ở đầu request (Chat: trước node LLM đầu tiên; Extraction: mỗi lần enrich; Embedding: mỗi batch) | SYSTEM + PURPOSE | `reserve_request.lua` / `settle_request.lua` |
| Attempt | Trước **mỗi** lần gọi provider (mỗi node, mỗi attempt failover) | PROVIDER của credential sắp gọi | `acquire_provider.lua` / `release_provider.lua` |

**Ước tính (`estimate`):**
- CLOUD_API: `litellm.cost_per_token(model, input_tokens_est, max_output_tokens)`;
  `input_tokens_est` ước lượng từ độ dài text.
- Request-level: estimate của model primary cho purpose × multiplier
  (`BUDGET_RESERVATION_MULTIPLIER_CHAT` mặc định 1.5 để phủ các node phụ; 1.0 cho
  Extraction/Embedding).
- Attempt-level: estimate của đúng model/credential sắp gọi, không nhân.
- Không có giá trong LiteLLM → `BUDGET_RESERVATION_FALLBACK_USD`.
- SELF_HOSTED → 0 (vẫn gọi script để giữ counter `inflight` nếu có THROTTLE).

**1. `reserve_request.lua`** — với các budget SYSTEM và PURPOSE đang enabled:
- BLOCK và `committed + reserved + estimate > limit` → `REJECT_EXCEEDED`, không ghi gì.
- THROTTLE, `committed + reserved ≥ limit` và `inflight ≥ throttleMaxConcurrency`
  → `REJECT_THROTTLED`, không ghi gì.
- Ngược lại: `reserved += estimate`, `inflight += 1` cho mọi scope áp dụng; ghi
  hash `budget:resv:{requestId}` (field `req` = danh sách key + số đã cộng) và
  `ZADD budget:resv:expiry now+TTL {requestId}`.

**2. `acquire_provider.lua(requestId, seq, provider, estimate)`** — `model_router`
gọi trước mỗi candidate credential:
- PROVIDER BLOCK và `committed + reserved + estimate > limit` → `DENY_EXCEEDED`.
- PROVIDER THROTTLE, đã vượt limit và `inflight ≥ cap` → `DENY_THROTTLED`.
- Router nhận DENY → bỏ credential đó, thử candidate kế tiếp (acquire lại với
  provider mới); hết candidate → lỗi `BUDGET_EXCEEDED`/`BUDGET_THROTTLED`.
- `OK` → `reserved += estimate`, `inflight += 1` của provider; ghi field
  `p:{seq}` vào hash reservation. Không có budget PROVIDER nào → vẫn ghi field
  với số 0 (để release có chỗ commit cost thực).

**3. `release_provider.lua(requestId, seq, actualMicroUsd)`** — ngay khi attempt
kết thúc (thành công, lỗi, huỷ stream): trừ `reserved`/`inflight` đúng số trong
field `p:{seq}`, cộng `committed` của provider bằng cost thực của line, xoá
field. Field không còn → no-op (idempotent). **Failover** = release attempt cũ
(cost thực của attempt lỗi, thường 0) rồi acquire provider mới.

**4. `settle_request.lua(requestId, lines)`** — cuối request: release mọi field
`p:*` còn sót (an toàn khi code quên release), trừ `reserved`/`inflight` của
field `req`, cộng `committed` SYSTEM + PURPOSE = tổng cost thực. Line UNPRICED
dùng `estimatedCostUsd` (thận trọng, không coi là $0). Xoá hash + `ZREM`; set
marker `budget:settled:{requestId}` (TTL 1 ngày) → gọi lại là no-op.

**5. Reservation treo** (process crash): job release theo `budget:resv:expiry`
quá hạn — trả `reserved`/`inflight` của cả `req` và mọi `p:*`, không cộng
`committed` (cost thực sẽ được đối soát từ DB nếu log đã tới Java).

**Key Redis:** `budget:{committed|reserved|inflight}:{scopeKey}:{period}:{periodKey}`,
`scopeKey` = `SYSTEM` | `PURPOSE:CHAT` | `PROVIDER:openai` (lowercase);
`periodKey` = `2026-09-25` / `2026-09` theo `APP_TIMEZONE`. TTL = hết kỳ + 3
ngày. `inflight` không có kỳ: `budget:inflight:{scopeKey}` (TTL an toàn 1 giờ,
làm mới mỗi lần tăng).

**Ma trận hành vi khi vượt limit:**

**Làm rõ điều kiện kích hoạt THROTTLE (vòng 3):** THROTTLE **không** kích hoạt
ngay khi `committed + reserved` vượt `limitUsd` — nó chỉ giới hạn **số request
đang chạy đồng thời** (`inflight`) khi ngân sách của kỳ **đã** vượt. Điều kiện
từ chối là **AND** của cả hai vế (khớp với Lua `reserve_request`/
`acquire_provider` ở mục "Budget semantics" bước 1-2, không phải OR):
`(committed + reserved ≥ limit) AND (inflight ≥ throttleMaxConcurrency)`. Nói
cách khác: nếu ngân sách còn dư (`committed + reserved < limit`) thì THROTTLE
cho qua **vô điều kiện về concurrency** dù `inflight` đang cao — THROTTLE chỉ
là van giới hạn tốc độ chi tiêu *sau khi* đã chạm limit, không phải giới hạn
concurrency chung. Ngược lại nếu limit đã bị chạm nhưng `inflight <
throttleMaxConcurrency` thì vẫn cho qua thêm request tới khi đạt cap. Đây là
lý do action này tên là THROTTLE (giảm tốc khi đã vượt) chứ không phải một cơ
chế rate-limit độc lập với budget.

| Scope | ALERT | THROTTLE | BLOCK |
|---|---|---|---|
| SYSTEM | Cho qua, Java gửi alert | Đã vượt limit **và** đang có ≥ `throttleMaxConcurrency` request in-flight toàn hệ thống → từ chối `BUDGET_THROTTLED` (HTTP 429, không queue, không delay); chưa vượt limit thì cho qua bất kể inflight | Từ chối `BUDGET_EXCEEDED` trước mọi call provider |
| PURPOSE | Như SYSTEM, chỉ cho purpose đó | Như SYSTEM, đếm in-flight theo purpose, cùng điều kiện AND ở trên | Như SYSTEM — **không fallback được** vì mọi provider của purpose đều dùng chung budget |
| PROVIDER | Cho qua | Đã vượt limit của provider **và** `inflight` của provider ≥ cap → `acquire_provider` DENY → router fallback; hết candidate → `BUDGET_THROTTLED` | `acquire_provider` DENY → router fallback; hết candidate → `BUDGET_EXCEEDED` |

Ingest (Extraction/Embedding) bị từ chối vì budget → job ingest đánh dấu lỗi
retry được (không mất tài liệu), không retry dồn dập ngay.

**Redis lỗi/không kết nối được** → fail-open (cho qua, log error), vì đây là
soft limit; không chặn Chat.

**Cấu hình Python (env, có mặc định, placeholder đã có trong
`unisage-agent/.env.example`):** `APP_TIMEZONE`,
`BUDGET_RESERVATION_MULTIPLIER_CHAT`, `BUDGET_RESERVATION_FALLBACK_USD`,
`BUDGET_RESERVATION_TTL_SECONDS`, `BUDGET_SNAPSHOT_REFRESH_SECONDS`.

**Snapshot budget ở Python:** đọc `GET /internal/budgets/snapshot` định kỳ +
khi nhận `config_version` qua pub/sub của Model Registry; Java publish version
mới khi CRUD budget. Snapshot được truyền vào Lua qua `ARGV` (limit, action,
cap) — Lua không đọc DB.

**Đối soát `committed`:** job định kỳ, cho từng scope của kỳ hiện tại:
1. Lua đọc atomic `LLEN usage:outbox` + `LLEN usage:outbox:processing` +
   `LLEN usage:outbox:dead` + `committed` hiện tại (`C0`); **bất kỳ danh sách
   nào trong 3 danh sách trên khác rỗng → bỏ qua lượt này**, không chỉ
   outbox/processing.
2. Lấy `dbTotal` từ `GET /internal/usage-logs/period-totals`.
3. `INCRBY committed (dbTotal − C0)` — cộng delta thay vì `SET`, nên settle chạy
   xen giữa bước 1-3 không bị ghi đè. Sai lệch còn lại (payload vừa vào DB trong
   khoảng đó) tự triệt tiêu ở lượt sau.

**Quy tắc khi payload đã vào `usage:outbox:dead` nhưng DB chưa có record
tương ứng (vòng 3):** đây chính xác là lý do bước 1 phải kiểm cả `dead`, không
chỉ outbox/processing — payload trong `usage:outbox:dead` là cost **đã phát
sinh thật** (provider đã bị gọi, tiền đã tốn) nhưng chưa tới được DB (lỗi
schema, Java từ chối 4xx...), nên `dbTotal` hiện tại **thấp hơn** committed
thực tế của kỳ. Nếu job đối soát bỏ qua điều kiện này và chạy `INCRBY committed
(dbTotal − C0)`, nó sẽ **trừ nhầm** `committed` xuống thấp hơn số tiền thực đã
chi — sai theo hướng nguy hiểm hơn (đánh giá thấp mức tiêu, có thể để budget
BLOCK cho qua thêm request trong khi thực ra đã vượt). Vì vậy: còn item trong
`dead` → job đối soát **không** cộng delta cho scope liên quan, chỉ log
cảnh báo kèm số item trong `dead` và tuổi của item cũ nhất (giống cách health
agent báo `usageOutbox.dead > 0` → `degraded`), buộc SA chạy
`task usage:replay-dead` sau khi sửa nguyên nhân trước khi đối soát tiếp tục
chạy bình thường. Committed không bị hạ sai chỉ vì có 1 payload lỗi schema kẹt
trong dead.

## Migration versions

**Cập nhật (vòng 3):** giả định ban đầu "repo đang ở V15" đã sai — các migration
khác trên `main` đã chen vào trước khi plan Model Registry migrate xong, nên
migration state machine của Model Registry (`add_chat_model_purpose_and_status`)
thực tế lên DB dưới tên `V18__add_chat_model_purpose_and_status.sql`, không
phải `V16` như văn bản gốc của plan Model Registry ghi (plan đó đã được sửa lại
để phản ánh đúng, xem file `plan.md` của Model Registry, mục "Migration
versions"). Tại thời điểm viết bản sửa này, `db/migration` đã có tới
`V24__drop_doc_from_allowed_extensions.sql`. Chốt số mới:

| Version | Plan | Nội dung |
|---|---|---|
| V18 | Model Registry (đã áp dụng) | `V18__add_chat_model_purpose_and_status.sql` |
| V25 | Plan này | `V25__add_request_usage_logs.sql` |
| V26 | Plan này | `V26__add_budget_tables.sql` |
| V27 | Plan này | `V27__seed_cost_permissions.sql` |
| V28 | Model Registry Phase 9 | `V28__add_routing_policy.sql` |

Trước khi tạo file, chạy `ls db/migration | sort -V | tail -1`; nếu đã có
migration khác chen vào thì dời cả dải V25-V28 lên và cập nhật bảng này ở cả 2
plan trong cùng commit. Không giả định số phiên bản cố định trong code (test,
tên class Java...) — chỉ dùng chúng trong tên file migration và tài liệu.

## Deployment (worker, scheduler, Redis)

- **Celery beat** (chưa có trong agent) chạy các task định kỳ:
  - `drain_usage_outbox` mỗi 5 giây — giữ lock `usage:outbox:lock` (`SET NX EX
    60`) nên chỉ 1 drainer chạy; đầu mỗi lượt có lock, đưa toàn bộ
    `usage:outbox:processing` về outbox (reclaim item kẹt do worker chết).
  - `release_expired_reservations` mỗi 1 phút.
  - `reconcile_budget_committed` mỗi 1 giờ.
- Taskfile thêm `worker` và `beat`; README cập nhật lệnh chạy; devcontainer
  compose thêm service `celery-worker` và `celery-beat`.
- **Redis persistence là acceptance criterion:** compose chạy
  `redis-server --appendonly yes --appendfsync everysec` + volume `redis_data`.
  Test: `docker compose restart redis` → outbox và counter còn nguyên.
- **Giám sát:** `GET /api/v1/health` của agent trả `usageOutbox.pending`,
  `usageOutbox.dead`; `dead > 0` → `status = degraded` (trang System Health của
  Java đã poll health agent). Lệnh `task usage:replay-dead` đẩy lại dead-letter
  về outbox sau khi sửa nguyên nhân.

## Internal API & bảo mật

**Cập nhật (vòng 3):** đổi theo đúng mô hình đã chốt ở plan Model Registry
(`changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/plan.md`, mục
"Internal API contract" / "Security flow") — **không** dùng
`PredefinedPublicPaths` cho `/internal/**` (bản trước của plan này yêu cầu vậy,
sai và không nhất quán với plan kia).

- Prefix `/internal/**`. **Cập nhật khi implement Task 2 (thực tế khác giả định
  ban đầu ở trên):** `InternalSecretFilter.INTERNAL_ONLY_PATHS` **đã** dùng
  wildcard `("*", "/internal/**")` từ Model Registry Task 0.1, không phải danh
  sách method+path tường minh từng endpoint — bất kỳ controller mới nào dưới
  `/internal/**` tự động được bảo vệ, **không cần sửa filter** khi thêm
  endpoint. Hành vi: thiếu/sai secret → 403 `INTERNAL_SECRET_INVALID` ngay tại
  filter; secret đúng → filter set request attribute
  `TRUSTED_INTERNAL_CALLER_ATTRIBUTE = TRUE` cho **mọi** request (không riêng
  `/internal/**`). `DynamicAuthorizationManager` có nhánh riêng: path khớp
  `/internal/**` **và** attribute đó `TRUE` → grant, **không** cần path nằm
  trong `PredefinedPublicPaths` và không cần JWT. Path `/internal/**` thiếu
  attribute (vd filter chưa chạy, bug thứ tự filter) → deny, kể cả JWT SA hợp
  lệ.
- Áp dụng chung với 7 endpoint của Model Registry: `InternalCallerCidrFilter`
  (CIDR fail-closed) và `InternalResponseHeadersFilter` (`Cache-Control:
  no-store` + `Pragma: no-cache` cho mọi response `/internal/**`, kể cả 3
  endpoint không trả secret của plan này).
- Endpoint nội bộ của plan này (thêm vào **cùng một** bảng danh sách endpoint
  nội bộ duy nhất với 7 endpoint của Model Registry, tổng 10 — xem
  "Contract coverage" bên dưới): `POST /internal/usage-logs`,
  `GET /internal/budgets/snapshot`, `GET /internal/usage-logs/period-totals`.
- Secret hợp lệ **không** mở được endpoint user-facing: attribute chỉ được
  kiểm trong nhánh `/internal/**` của `DynamicAuthorizationManager`,
  `/usage-logs`, `/budgets`... vẫn đi qua nhánh RBAC bằng JWT như bình thường.
- **Contract coverage:** `InternalEndpointCoverageTest` của Model Registry
  (Task 0.1 plan đó) phải mở rộng để assert đúng **10** endpoint (7 Model
  Registry + 3 plan này), không phải 7; `InternalNoStoreTest` mở rộng tương tự
  cho 3 endpoint mới. Sửa 2 test này là một phần của Task 2 plan này, trong
  cùng commit với việc thêm endpoint (tránh coverage test bị lệch ngầm).
- RBAC: thêm `ResourceType.USAGE_LOG` ("Nhật ký chi phí AI"), `BUDGET` ("Ngân
  sách AI"); permission trong `PredefinedPermissions`; `DataInitializer` gán cho
  SYSTEM_ADMIN; migration Flyway insert permission + role_permission cho DB đã
  tồn tại (vì `DataInitializer` chỉ seed DB mới).

## Giao diện quản lý ngân sách (unisage-web)

**Wiring:** route segment `cost-management` trong `ROUTE_SEGMENTS`; entry
`FEATURE_REGISTRY` (workspace `system-admin`, label "Chi phí AI", icon
`Wallet`); `PERMISSION_POLICIES.costManagement` (theo permission `USAGE_LOG`
read) và `costManagementBudgets` (theo `BUDGET` write, ẩn nút sửa nếu thiếu);
feature folder `src/features/cost-management/` với `api/` (client), `query-keys.ts`,
`schemas.ts` (zod cho response + form), `components/`, tabs con.

### Tab 1 — Tổng quan
- 4 KPI card: Chi phí tháng này (+% so tháng trước), Ngân sách tháng (budget
  SYSTEM MONTHLY), % đã dùng (xanh <50, vàng 50-80, đỏ >80), Còn lại.
- Card phụ "Chưa định giá": số call UNPRICED + tổng ước tính, không cộng lẫn vào
  chi phí thực.
- Phân bổ theo purpose (donut), theo provider/model (bar ngang — từ line), xu
  hướng theo ngày (line chart), top user/IP (bảng top 10).
- Filter: khoảng thời gian, purpose, provider.

### Tab 2 — Ngân sách & Giới hạn
- Bảng: Scope (+ provider/purpose), Period, Limit, Đã dùng kỳ hiện tại, %,
  Action, Enabled, Sửa/Xoá.
- Form: Scope (Hệ thống / Provider — dropdown provider lấy từ Model Registry /
  Purpose), Period, Limit ($), Action; Action = THROTTLE thì hiện input "Số
  request đồng thời tối đa".
- Ghi chú trên UI: budget là **giới hạn mềm** (có thể vượt nhẹ bởi request đang
  chạy); PROVIDER hết thì fallback provider khác, SYSTEM/PURPOSE hết thì từ chối.

### Tab 3 — Cảnh báo
- Ngưỡng 50/80/100% + thêm ngưỡng tuỳ chỉnh; toggle spike + % spike.
- Kênh: In-app, Email (danh sách địa chỉ), Slack (toggle + hiển thị trạng thái
  "Đã cấu hình: #label" / "Chưa cấu hình webhook trong env" — không có ô nhập
  URL).
- Bảng lịch sử: thời điểm, loại, budget/scope, ngưỡng, kênh, trạng thái, lỗi.

### Tab 4 — Bảng giá (chỉ xem)
Bảng tĩnh ở FE theo provider/model, dòng SELF_HOSTED "Không tính phí", ghi chú
"chỉ tham khảo".

### Tab 5 — Lịch sử sử dụng
- Bảng request (parent) có filter thời gian/purpose/provider/model/user-IP/
  status; cột: thời điểm, user/IP, purpose, model(s), tokens, cost, latency,
  status, badge "failover" nếu có line `attempt ≥ 1`.
- Drawer: Query (từ `userMessageId`), Answer + chunks trích dẫn (từ
  `assistantMessageId.citations`), **bảng line** (node, provider/model snapshot,
  attempt, tokens, cost/costStatus, latency, lỗi), tổng cost, user/IP, Request ID.
  Message đã bị xoá (guest cleanup) → hiển thị "Nội dung đã bị xoá", số liệu cost
  vẫn còn.

### In-app alert
`features/notifications` hiện chỉ là placeholder — phase này **không** xây
notification center. Chỉ thêm `BudgetAlertBanner` trong layout system-admin,
poll `GET /budget-alerts/active` (60s), dismiss qua
`POST /budget-alerts/{id}/dismiss`.

### `/profile` — giữ nguyên
`UsageLimitCard` (DAILY/WEEKLY, token ước lượng) là quota theo gói, khác nguồn
với `RequestUsageLog`. Để 2 hệ thống độc lập (xem Open Questions).

## Task List

### Phase 0: Gate
- [x] Task 0: Nghiệm thu Model Registry + spike chốt nguồn usage/token

### Phase 1: Java — Data model, internal API, RBAC
- [x] Task 1: Entity `RequestUsageLog` + `RequestUsageLine` + migration
- [x] Task 2: Internal auth whitelist + `POST /internal/usage-logs` idempotent
- [x] Task 3: `Budget` + `BudgetAlertSetting` + `BudgetAlertLog` + CRUD + RBAC
- [x] Task 4: API dashboard/lịch sử/chi tiết + internal snapshot/period-totals

### Phase 2: Python — Đo usage & outbox
- [x] Task 5: `cost_calculator` (actual + estimate)
- [x] Task 6: `UsageRecorder` theo request, ghi line cho mọi call Chat
- [x] Task 7: Outbox Redis + worker drain về Java
- [x] Task 8: Extraction + Embedding ghi usage

### Phase 3: Python — Budget enforcement (soft limit + reservation)
- [x] Task 9: Lua reserve/settle request + acquire/release provider + budget snapshot
- [ ] Task 10: Gắn reservation vào Chat/Extraction/Embedding + acquire/release trong router
- [ ] Task 11: Đối soát + release reservation treo
- [ ] Task 11b: Deploy Celery worker/beat, Redis AOF, health outbox, replay dead-letter

### Phase 4: Cảnh báo (Java)
- [ ] Task 12: Job phát hiện ngưỡng + spike, claim atomic
- [ ] Task 13: Gửi In-app/Email/Slack (env)

### Phase 5: unisage-web
- [ ] Task 14: Wiring (route, registry, permission, api client, query keys, schema)
- [ ] Task 15: Tab Tổng quan
- [ ] Task 16: Tab Ngân sách & Giới hạn
- [ ] Task 17: Tab Cảnh báo + `BudgetAlertBanner`
- [ ] Task 18: Tab Bảng giá
- [ ] Task 19: Tab Lịch sử sử dụng + drawer

### Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ test ở mục "Test bắt buộc" pass
- [ ] SA xem dashboard, set budget, nhận cảnh báo, xem chi tiết request qua UI
- [ ] Ready for review

## Test bắt buộc (xuyên phase)

| Tình huống | Nơi test |
|---|---|
| 50 request đồng thời, budget SYSTEM BLOCK còn đủ cho ~10 estimate → số `OK` ≤ 10; overshoot ≤ tổng chênh lệch actual−estimate | pytest + Redis thật (fakeredis không chạy Lua đủ) |
| 50 request đồng thời, budget PROVIDER openai BLOCK đủ cho ~10 attempt → ≤ 10 attempt openai được acquire, phần còn lại fallback sang provider khác | pytest + Redis thật |
| 50 request đồng thời, PROVIDER openai THROTTLE cap 3 (đã vượt limit) → tại mọi thời điểm `inflight` openai ≤ 3; kết thúc thì `inflight` = 0, `reserved` = 0 | pytest + Redis thật |
| Failover giữa chừng → attempt cũ đã release, attempt mới đã acquire; gọi release/settle 2 lần không trừ/cộng đôi | pytest + Redis thật |
| Cost cộng dồn 10.000 lần giá nhỏ (vd $0.0000015) → `committed` micro-USD khớp tổng Decimal, không drift | pytest |
| `docker compose restart redis` → outbox, dead-letter và counter còn nguyên (AOF) | manual, ghi kết quả vào checkpoint |
| SMTP/Slack env trống → alert `SKIPPED`, `/actuator/health` vẫn UP | `./mvnw test` |
| Tạo đồng thời 2 budget SYSTEM MONTHLY enabled → 1 thành công, 1 lỗi validate (không 500) | `./mvnw test` trên PostgreSQL thật (Testcontainers), không H2 |
| Alert gửi lỗi 3 lần → `GAVE_UP`, `attemptCount = 3`; 2 instance không gửi trùng 1 dòng retry | `./mvnw test` |
| Request không có LLM call → không có parent ở Java, reservation đã trả | pytest |
| Worker gửi cùng `requestId` 2 lần → 1 parent, không nhân đôi line | `./mvnw test` |
| Java down 5 phút → Chat vẫn trả lời, outbox giữ payload, Java lên lại → đủ record; đối soát không hạ `committed` khi outbox còn | pytest |
| Model không có giá → line UNPRICED, budget cộng estimate, dashboard hiển thị riêng | pytest + `./mvnw test` |
| Failover OpenAI → Anthropic trong 1 request → 2 line, provider đúng, cost cộng vào PROVIDER đúng của từng line | pytest |
| Guest cleanup xoá message/conversation → usage log còn, `userMessageId`/`assistantMessageId` = null | `./mvnw test` |
| Request lúc 23:59:59 và 00:00:01 giờ VN → rơi vào 2 kỳ DAILY khác nhau ở cả Redis và query Java | pytest + `./mvnw test` |
| Internal endpoint: không secret → 403; sai secret → 403; đúng secret → 2xx; secret đúng gọi `/budgets` không JWT → bị RBAC từ chối; JWT SA gọi `/internal/*` không secret → 403 | `./mvnw test` |
| 2 instance chạy job alert cùng lúc → mỗi `dedupeKey` gửi đúng 1 lần | `./mvnw test` |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Estimate reservation thấp hơn cost thực nhiều (câu trả lời dài, nhiều node) → vượt budget | Medium | Soft limit đã chấp nhận; multiplier chỉnh qua env; dashboard hiển thị overshoot; settle luôn dùng cost thực |
| Reservation treo khi process crash làm "khoá" budget | Medium | TTL + job `release_expired_reservations` mỗi phút |
| Outbox Redis mất dữ liệu nếu Redis không bật persistence | Medium | AOF + volume là acceptance criterion của Task 11b, có test restart |
| Dead-letter tích tụ không ai thấy | Medium | Health agent `degraded` khi `dead > 0`, hiện ở trang System Health; `task usage:replay-dead` |
| Số migration đụng với plan Model Registry | Medium | Bảng "Migration versions" chốt V25-V28 (V18 Model Registry đã áp dụng, V25-V27 plan này, V28 Routing Policy), kiểm tra `sort -V` trước khi tạo file |
| LiteLLM không có giá model mới | Medium | UNPRICED + estimate fallback, không coi là $0 |
| Bảng giá FE lệch giá thật | Low | Chỉ tham khảo, ghi chú trên UI |
| Slack webhook URL lộ | Medium | Chỉ trong env (`.ENV` không commit), không lưu DB, không trả về API |
| Email không gửi được vì chưa cấu hình SMTP | Low | Task 13 thêm cấu hình `spring.mail.*` qua env; thiếu cấu hình → log `SKIPPED`, không crash |

## Open Questions

- **`/profile`**: để `UsageLimit` (quota, ước lượng, đồng bộ trong Java) và
  `RequestUsageLog` (billing, token thật, bất đồng bộ) độc lập — đề xuất giữ
  nguyên; nếu cần hiển thị cost cho end-user làm task riêng sau.
- **USER_GROUP budget**: bỏ khỏi phase này. Nếu cần sau, phải chốt trước "nhóm"
  là `Role`, `Department` hay `UsageLimitPlan`.
- **Latency trung bình cho Routing Phase 9**: tính on-the-fly từ
  `request_usage_lines` (index `chat_model_id, created_at`) hay bảng aggregate —
  chốt khi bắt đầu Phase 9.
