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
- Migration: **V25-V27** (đã đổi từ V17-V19 ở vòng review 3 — xem "Migration
  versions" trong plan.md; repo thực tế đã ở V24, migration state machine của
  Model Registry lên DB là V18 chứ không phải V16).
- `litellm` trong plan này **chỉ** dùng để tra giá offline
  (`completion_cost`/`cost_per_token`), không dùng để gọi provider — không mâu
  thuẫn với ADR 0005 của plan Model Registry (cấm LiteLLM SDK làm đường gọi
  provider). Xem Overview trong plan.md.
- Internal API `/internal/usage-logs`, `/internal/budgets/snapshot`,
  `/internal/usage-logs/period-totals` cấp quyền theo cùng mô hình
  `TRUSTED_INTERNAL_CALLER_ATTRIBUTE` của plan Model Registry, **không** qua
  `PredefinedPublicPaths` (đổi ở vòng review 3 — xem "Internal API & bảo mật"
  trong plan.md).
- **Prerequisite Model Registry Phase 0-6:** product owner xác nhận đã
  implement và test xong ở mức đủ dùng, **cho phép bỏ qua** yêu cầu "implementation
  approved" hình thức của plan Model Registry — xem Task 0 bên dưới đã sửa.

---

## Phase 0: Gate

### Task 0: Spike chốt nguồn usage/token (prerequisite Model Registry: xem ghi chú)

**Description:** Plan này cần biết chính xác provider/model/credential và
usage thật của từng call, dựa trên model native PydanticAI theo provider (ADR
0005 của plan Model Registry) — **không** phải LiteLLM SDK làm đường gọi.

**Prerequisite (đã sửa ở vòng review 3):** bản gốc của task này yêu cầu Model
Registry Phase 0-6 phải đạt "implementation approved" (mục "Gate: implementation
approved" của plan Model Registry) trước khi bắt đầu. Product owner xác nhận
Dynamic Model Registry **đã được implement và test xong** ở mức đủ để plan này
dựa vào — quyết định này **ghi đè** yêu cầu approve hình thức, task 0 không
còn chặn vào trạng thái checklist của plan kia. Điều vẫn bắt buộc: spike dưới
đây phải chạy trên code Model Registry **thật đang có** (không phải giả định
lý thuyết), vì Task 5-10 của plan này gọi thẳng vào `model_router`/factory của
Model Registry.

**Acceptance criteria:**
- [x] Ghi vào `unisage-agent/docs/product/DECISIONS.md` bảng "nguồn usage" cho 4
      loại call, mỗi dòng có: điểm hook trong code, field input/output/cached
      token, cách lấy provider/model/`chatModelId` thực tế sau failover, cách lấy
      latency — PydanticAI agent run (`result.usage()`), embedding
      (`response.usage.prompt_tokens`), extraction. Với LiteLLM: chỉ ghi cách
      dùng `litellm.cost_per_token`/`completion_cost` để **định giá** kết quả
      usage đã lấy được từ model native, không phải cách LiteLLM tự lấy usage
      (plan này không gọi provider qua LiteLLM)
- [x] Test xác nhận `litellm.completion_cost()`/`litellm.cost_per_token()`
      **không** phát sinh network call (mock transport/socket ở tầng thấp nhất
      có thể, assert 0 request) — bằng chứng bằng test chạy được, không chỉ đọc
      tài liệu LiteLLM, để không vi phạm ADR 0005 và test kiến trúc
      `test_no_raw_provider_clients.py` của plan Model Registry.
      **Phát hiện quan trọng:** `import litellm` mặc định fetch bảng giá từ
      GitHub qua mạng thật khi gặp model lạ hoặc lúc import — phải set
      `LITELLM_LOCAL_MODEL_COST_MAP=True` **trước khi import** để tắt hẳn
      (verify bằng test spy socket, xem `test_cost_calculator.py::test_cost_per_token_never_opens_a_network_connection`
      và DECISIONS.md)
- [x] Xác nhận streaming Chat trả usage ở chunk cuối (hoặc cách thay thế) cho
      provider đang dùng — `result.usage()` bên trong `async with
      active_agent.run_stream(prompt) as result:` (`streaming.py:114-126`),
      ngay sau vòng `async for` kết thúc, trước khi context manager thoát
- [x] Xác nhận **1 hook duy nhất** trong `model_router` gọi được callback
      "trước mỗi attempt" và "sau mỗi attempt" (cần cho Task 6 và Task 10), và
      hook đó cung cấp đủ toàn bộ: usage ở chunk cuối cùng của stream (không
      phải usage tích luỹ giữa chừng), provider/model **sau khi** failover xảy
      ra (không phải provider/model lúc bắt đầu attempt nếu 2 giá trị này khác
      nhau), latency đo được của đúng attempt đó (không phải latency toàn
      request), và `chatModelId` snapshot tại thời điểm gọi. Nếu hook hiện có
      của Model Registry thiếu bất kỳ trường nào ở trên, spike phải đề xuất mở
      rộng chữ ký callback đó (đổi 1 chỗ, không thêm hook thứ hai) và ghi vào
      DECISIONS.md — **kết luận:** không có hook như vậy (chỉ có `on_failover`/
      `record_failure`, cả hai chỉ chạy trên nhánh lỗi); quyết định mở rộng
      `stream_agent_text()`/`run_agent_text_with_failover()` với tham số
      `on_attempt` mới ở Task 6, xem DECISIONS.md mục "Vì sao không có hook..."

**Verification:**
- [ ] Human đọc bảng trong DECISIONS.md và approve

**Dependencies:** Dynamic Model Registry đã implement (không chờ "implementation
approved" hình thức — xem "Prerequisite" ở trên)

**Files likely touched:**
- `unisage-agent/docs/product/DECISIONS.md`
- `unisage-agent/pyproject.toml` (thêm dependency `litellm`)
- `unisage-agent/.env.example` (`LITELLM_LOCAL_MODEL_COST_MAP=True`)

**Estimated scope:** S

---

## Phase 1: Java — Data model, internal API, RBAC

### Task 1: Entity `RequestUsageLog` + `RequestUsageLine` + migration

**Description:** Parent/child theo `plan.md` mục "Data Model". Repo thực tế đã
ở V24 (không phải V15 như bản trước), migration state machine của Model
Registry đã lên DB là V18 → file của task này là
`V25__add_request_usage_logs.sql` (**bắt buộc kiểm tra lại bằng
`ls db/migration | sort -V | tail -1` trước khi tạo** — số này chỉ đúng tại
thời điểm viết bản sửa này, `main` có thể đã thêm migration mới).

**Acceptance criteria:**
- [x] `RequestUsageLog` đủ field theo plan, có `userMessageId` +
      `assistantMessageId`, tổng denormalized, `unpricedLineCount`
- [x] `RequestUsageLine` đủ field theo plan, snapshot `provider`/`modelName`/
      `sourceType`, `costStatus` (PRICED/UNPRICED/FREE), `attempt`
- [x] Enum mới: `UsagePurpose`, `UsageRequestStatus`, `UsageCostStatus`
- [x] `V25__add_request_usage_logs.sql`: FK `conversation_id`, `user_message_id`,
      `assistant_message_id`, `user_id`, `chat_model_id` là **ON DELETE SET NULL**;
      `usage_log_id` ON DELETE CASCADE; UNIQUE `request_id`; **UNIQUE
      (`usage_log_id`, `seq`)** trên `request_usage_lines` ở tầng DB (không chỉ
      validate ở service); đủ index ở plan. Thêm `request_usage_lines_cost_usd_matches_status_check`
      (CHECK `costStatus = PRICED ⇔ costUsd IS NOT NULL`) không có trong plan
      gốc nhưng khớp quy tắc "costUsd = null" — DB chặn thay vì chỉ service
- [x] Cột thời gian `timestamp(6) without time zone` chứa UTC, entity dùng
      `LocalDateTime`; `startedAt`/`finishedAt`/`occurredAt` không có giá trị
      mặc định DB (Java/Task 6 phải set tường minh từ `Clock`/parse ISO-8601
      UTC), không dựa vào `createdAt` (dùng `@CreationTimestamp`, JVM-clock,
      chỉ để bookkeeping — không dùng cho period query)
- [x] Tiền `NUMERIC(18,8)` ↔ `BigDecimal`
- [x] `ddl-auto=validate` khởi động được (entity khớp migration) — xác nhận
      bằng `./mvnw test` chạy full suite dưới `ddl-auto=validate` (cấu hình
      chuẩn của `application.properties`), pass toàn bộ
- [x] Thêm Testcontainers PostgreSQL (đã có sẵn trong `pom.xml` từ Model
      Registry, không cần thêm dependency mới) + base class
      `PostgresIntegrationTest` (`src/test/java/.../support/`) chạy Flyway thật

**Verification:**
- [x] `./mvnw test` — full suite pass (904 test trước đó + các test mới của
      Task 1, không có regression)
- [x] Test: xoá message/conversation theo đúng luồng
      `GuestSessionServiceImpl.purgeExpiredBatch` → usage log còn, 2 message id = null
      (`GuestSessionCleanupUsageLogTest`, Postgres thật qua Testcontainers)

**Dependencies:** Task 0

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/entity/RequestUsageLog.java`
- `backend-java/src/main/java/com/unisage/backend/entity/RequestUsageLine.java`
- `backend-java/src/main/java/com/unisage/backend/entity/enums/Usage*.java`
- `backend-java/src/main/java/com/unisage/backend/repository/RequestUsageLogRepository.java`
- `backend-java/src/main/java/com/unisage/backend/repository/RequestUsageLineRepository.java`
- `backend-java/src/main/resources/db/migration/V25__add_request_usage_logs.sql`
- `backend-java/src/test/java/com/unisage/backend/support/PostgresIntegrationTest.java`
- `backend-java/src/test/java/com/unisage/backend/migration/V25MigrationTest.java`
- `backend-java/src/test/java/com/unisage/backend/usagelog/GuestSessionCleanupUsageLogTest.java`
- (`pom.xml` không cần sửa — Testcontainers đã có sẵn từ Model Registry)

**Estimated scope:** M

---

### Task 2: Internal auth whitelist + `POST /internal/usage-logs` idempotent

**Description:** Python (worker outbox) gửi 1 payload/request gồm parent + list
line. Endpoint phải qua được `DynamicAuthorizationManager` mà không có JWT, và
chỉ khi có secret hợp lệ — theo **đúng mô hình đã dùng bởi 7 endpoint nội bộ
của Model Registry** (không phải `PredefinedPublicPaths`, đã sửa ở vòng review 3).

**Acceptance criteria:**
- [x] ~~Thêm 3 endpoint vào `InternalSecretFilter.INTERNAL_ONLY_PATHS`~~ —
      **phát hiện khi implement:** không cần, hạ tầng `/internal/**` của Model
      Registry (`InternalSecretFilter`, `InternalCallerCidrFilter`,
      `InternalResponseHeadersFilter`, và nhánh `/internal/**` trong
      `DynamicAuthorizationManager`) đã match theo **wildcard** `/internal/**`
      từ trước, không phải danh sách method+path tường minh từng endpoint như
      plan giả định — bất kỳ controller mới nào dưới `/internal/**` tự động
      được 3 filter + authorization manager bảo vệ, không cần sửa filter.
      Không đụng `PredefinedPublicPaths` (đúng như plan)
- [x] `InternalCallerCidrFilter` + `InternalResponseHeadersFilter` áp dụng tự
      động cho endpoint mới (wildcard, xem trên) — không cần thay đổi 2 filter
      này
- [x] Mở rộng `InternalEndpointCoverageTest` và `InternalNoStoreTest` — đổi
      `contracts/internal-endpoints.json` từ 1 `basePath` dùng chung sang
      `basePath` theo từng entry (endpoint Cost Tracking nằm ở `/internal`,
      không phải `/internal/model-registry` như Model Registry), 2 test đọc
      field mới; `/internal/usage-logs` đã `"implemented": true`, 2 endpoint
      còn lại (`budgets/snapshot`, `usage-logs/period-totals`) để `false` tới
      khi Task 3/4 implement
- [x] Insert parent + lines trong 1 transaction; Java tự tính tổng từ lines, không
      tin tổng do client gửi
- [x] `requestId` trùng → `INSERT ... ON CONFLICT (request_id) DO NOTHING`, trả
      200 với id có sẵn, không ghi thêm line — dùng `JdbcTemplate` native SQL,
      không phải JPA save + catch `DataIntegrityViolationException` (catch
      exception sẽ abort transaction Postgres, không thể SELECT lại trong
      cùng transaction)
- [x] Validate: `lines` không rỗng (`@NotEmpty`), `seq` không trùng, token ≥ 0
      (`@Min(0)`), `costStatus = PRICED` thì `costUsd` bắt buộc (ngược lại phải
      null, khớp DB CHECK), timestamp có offset `Z` (`OffsetDateTime`, JSR-380
      parse tự từ chối thiếu offset)
- [x] Controller/Service đi qua checklist `api-review-checklist` — internal
      controller theo đúng convention plain-DTO (không `ApiResponse`-wrap) của
      `InternalModelRegistryController` cùng package, không phải convention
      REST API SA-facing; service không `throw new` gì ngoài `AppException`

**Verification:**
- [x] `./mvnw test` có 5 case auth: không secret → 403; sai secret → 403; đúng
      secret → 200; đúng secret gọi `GET /users` không JWT → bị từ chối; JWT
      giả gọi `/internal/usage-logs` không secret → 403
      (`InternalUsageLogControllerTest`)
- [x] Test gửi cùng payload 2 lần → 1 parent, đúng số line
- [x] Test 2 thread gửi cùng `requestId` đồng thời → 1 parent (8 thread, đúng 1
      parent + 1 line, mọi response đều 200)

**Dependencies:** Task 1

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/controller/internal/InternalUsageLogController.java`
- `backend-java/src/main/java/com/unisage/backend/service/usagelog/RequestUsageLogService{,Impl}.java`
- `backend-java/src/main/java/com/unisage/backend/dto/request/internal/UsageLog{,Line}IngestRequest.java`
- `backend-java/src/main/java/com/unisage/backend/dto/response/internal/UsageLogIngestResponse.java`
- `backend-java/src/main/java/com/unisage/backend/exception/ErrorCode.java` (`USAGE_LOG_INVALID_PAYLOAD`)
- `backend-java/contracts/internal-endpoints.json`
- `backend-java/src/test/java/com/unisage/backend/controller/internal/{InternalEndpointCoverageTest,InternalNoStoreTest,InternalUsageLogControllerTest}.java`
- (`InternalSecretFilter.java` không cần sửa — xem phát hiện ở trên)
- `backend-java/src/test/java/com/unisage/backend/internal/InternalEndpointCoverageTest.java` (mở rộng lên 10 endpoint, sở hữu bởi Model Registry)
- `backend-java/src/test/java/com/unisage/backend/internal/InternalNoStoreTest.java` (mở rộng tương tự)

**Estimated scope:** M

---

### Task 3: `Budget` + `BudgetAlertSetting` + `BudgetAlertLog` + CRUD + RBAC

**Description:** Cấu hình budget, cấu hình alert toàn cục, lịch sử alert, và
quyền truy cập cho SA.

**Acceptance criteria:**
- [x] `Budget` theo plan: scope SYSTEM/PROVIDER/PURPOSE, `scopeProvider`,
      `scopePurpose`, `throttleMaxConcurrency`; validate ở service **và** `CHECK`
      constraint ở DB theo scope/action
- [x] 3 partial unique index riêng `ux_budgets_system`, `ux_budgets_provider`
      (`lower(scope_provider)`), `ux_budgets_purpose` — không dùng 1 index gộp
      có cột nullable; vi phạm unique map sang `ErrorCode`, không trả 500
      (`BUDGET_ALREADY_ENABLED_FOR_PERIOD`, qua `saveAndFlush` + catch
      `DataIntegrityViolationException` — an toàn vì không có query nào khác
      chạy trong cùng transaction sau đó, khác với Task 2's ON CONFLICT case)
- [x] `BudgetAlertSetting` singleton: `id SMALLINT PRIMARY KEY DEFAULT 1 CHECK
      (id = 1)`, seed trong migration (`[50,80,100]`, spike tắt, in-app bật); chỉ
      có API `GET`/`PUT`; validate email và ngưỡng 1-200 (service-level, map
      lỗi theo field vào `errors`)
- [x] `BudgetAlertLog` có `dedupeKey` UNIQUE, `attemptCount`, `lastAttemptAt`,
      `nextAttemptAt`, status gồm `GAVE_UP`, index (`status`, `next_attempt_at`),
      `dismissedAt`/`dismissedBy`
- [x] API: `GET/POST/PUT/DELETE /budgets` (response kèm "đã dùng kỳ hiện tại"
      tính từ DB qua `UsagePeriodTotalsService`, tự tính theo scope: SYSTEM/PURPOSE
      đọc log-level, PROVIDER đọc line-level vì provider là snapshot theo line),
      `GET/PUT /budget-alert-settings` (response có
      `slackConfigured` + `slackChannelLabel` đọc từ env, không bao giờ trả URL),
      `GET /budget-alerts?filters&page=`, `GET /budget-alerts/active`,
      `POST /budget-alerts/{id}/dismiss`
- [x] CRUD budget publish `config_version` mới (kênh pub/sub của Model Registry)
      để Python reload snapshot — gọi lại `ModelRegistryVersionService.bump()`
      có sẵn từ Model Registry, không cần cơ chế publish riêng
- [x] `ResourceType.USAGE_LOG` + `ResourceType.BUDGET`; permission trong
      `PredefinedPermissions`; `DataInitializer` gán SUPER_ADMIN (tên role thật
      trong DB, không phải "SYSTEM_ADMIN" như mô tả ban đầu); migration insert
      permission + role_permission cho DB đã có (V27, cũng phải nới CHECK
      `resource_type` trên `permissions`/`audit_logs` — phát hiện khi implement,
      không có trong plan gốc)
- [x] `ErrorCode` mới cho validate budget/alert setting (2600-2606, block
      "Cost Tracking (26xx)")

**Verification:**
- [x] `./mvnw test` (Testcontainers): budget SYSTEM MONTHLY thứ 2 enabled → lỗi
      validate; tạo đồng thời 2 cái → đúng 1 thành công (10 thread, đúng 1
      `OK` + 9 `BUDGET_ALREADY_ENABLED_FOR_PERIOD`); `INSERT` dòng
      `BudgetAlertSetting` id=2 → DB từ chối
- [x] **Phạm vi thu hẹp cho "user không có permission → 403":** verify bằng
      `CostTrackingPermissionSeedingTest` (Task 1) — xác nhận permission row
      đúng path/method đã seed và gán SUPER_ADMIN — thay vì dựng test JWT
      end-to-end cho riêng resource này, vì codebase chưa có tiền lệ test kiểu
      đó cho bất kỳ resource nào khác (cơ chế chung `DynamicAuthorizationManager`
      đã có test riêng ở nơi khác); nếu cần test 403 thật cho Budget cụ thể,
      làm ở một task test-hardening riêng, không chặn Task 3
- [ ] Manual: app khởi động trên DB cũ, migration chạy, SA thấy permission mới
      (chưa làm — cần môi trường DB cũ thật để verify thủ công)

**Dependencies:** None

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/entity/{Budget,BudgetAlertSetting,BudgetAlertLog}.java`
- `backend-java/src/main/java/com/unisage/backend/entity/enums/{BudgetScope,BudgetPeriod,BudgetAction,AlertChannel,AlertType,AlertStatus,ResourceType}.java`
- `backend-java/src/main/java/com/unisage/backend/controller/{BudgetController,BudgetAlertSettingController,BudgetAlertController}.java`
- `backend-java/src/main/java/com/unisage/backend/service/budget/*`
- `backend-java/src/main/java/com/unisage/backend/service/usagelog/{UsagePeriodCalculator,UsagePeriodTotalsService}.java`
- `backend-java/src/main/java/com/unisage/backend/predefined/PredefinedPermissions.java`
- `backend-java/src/main/java/com/unisage/backend/config/DataInitializer.java`
- `backend-java/src/main/java/com/unisage/backend/exception/ErrorCode.java`
- `backend-java/src/main/resources/application.properties` (Slack env mapping)
- `backend-java/src/main/resources/db/migration/V26__add_budget_tables.sql`
- `backend-java/src/main/resources/db/migration/V27__seed_cost_permissions.sql`
- `backend-java/src/test/java/com/unisage/backend/migration/V26MigrationTest.java`
- `backend-java/src/test/java/com/unisage/backend/config/CostTrackingPermissionSeedingTest.java`
- `backend-java/src/test/java/com/unisage/backend/service/budget/{BudgetServiceImplTest,BudgetAlertSettingServiceImplTest}.java`

**Estimated scope:** L — tách 3a (entity + migration + RBAC) và 3b (API) nếu dài

---

### Task 4: API dashboard/lịch sử/chi tiết + internal snapshot/period-totals

**Description:** Dữ liệu tổng hợp cho UI và dữ liệu cho Python. Mọi mốc ngày/
tháng tính theo `app.timezone`.

**Acceptance criteria:**
- [x] `GET /usage-logs/summary?from=&to=&groupBy=purpose|provider|model|user|day`
      — `provider`/`model` group trên `request_usage_lines` (snapshot); trả riêng
      `pricedCostUsd` và `estimatedUnpricedCostUsd`
- [x] `GET /usage-logs?filters&page=` — danh sách parent, có cờ `hasFailover`
      (1 batch query cho cả trang, không N+1); filter hiện có: `purpose`,
      `status`, `from`, `to` — `provider`/`model` (line-level) chưa filter được
      ở list, để dành khi Task 19 (web) thực sự cần, tránh join sớm không dùng
- [x] `GET /usage-logs/{id}` — parent + lines + query (user message) + answer và
      `citations` (assistant message); message null → field null, không lỗi
- [x] `GET /internal/budgets/snapshot` — budget enabled + `configVersion` (tái
      dùng `ModelRegistryVersionService.currentVersion()` có sẵn)
- [x] `GET /internal/usage-logs/period-totals?period=&periodKey=` — committed
      theo SYSTEM/PURPOSE/PROVIDER cho kỳ đó, trả **micro-USD integer** (line
      PRICED lấy `costUsd`, UNPRICED lấy `estimatedCostUsd`, FREE bỏ qua — khớp
      quy tắc settle ở Python; làm tròn half-up từng line trước khi cộng, dùng
      `ROUND(... * 1000000)` ngay trong SQL trước khi `SUM`)
- [x] Mốc kỳ: tính đầu/cuối kỳ ở `app.timezone` rồi đổi sang UTC `LocalDateTime`
      để lọc `started_at`/`occurred_at`; group theo ngày bằng
      `(col AT TIME ZONE 'UTC') AT TIME ZONE :tz`
- [x] Query dùng index đã tạo (`started_at`, `purpose+started_at`,
      `provider+occurred_at`, `chat_model_id+occurred_at` từ V25) — không thêm
      index mới, không full scan theo khoảng thời gian

**Verification:**
- [x] `./mvnw test`, gồm test biên timezone: record 23:59:59 và 00:00:01
      (Asia/Ho_Chi_Minh) nằm ở 2 ngày khác nhau trong `groupBy=day`
      (`RequestUsageLogServiceImplTest.dayGroupBy_splitsRecordsAcrossTheTimezoneBoundary`)
- [ ] Manual: seed ~100k line, summary theo tháng < 1s; `EXPLAIN` dùng index
      (chưa làm — cần seed dữ liệu lớn thủ công)

**Dependencies:** Task 1, Task 3

**Files likely touched:**
- `backend-java/src/main/java/com/unisage/backend/controller/{RequestUsageLogController,internal/InternalUsageLogController,internal/InternalBudgetController}.java`
- `backend-java/src/main/java/com/unisage/backend/repository/{RequestUsageLogRepository,RequestUsageLineRepository,BudgetRepository}.java`
- `backend-java/src/main/java/com/unisage/backend/dto/response/Usage*.java`, `dto/response/internal/Internal{BudgetSnapshot,UsagePeriodTotals}Response.java`
- `backend-java/src/main/java/com/unisage/backend/service/usagelog/{UsagePeriodCalculator,InternalPeriodTotalsService,RequestUsageLogServiceImpl}.java`
- `backend-java/src/test/java/com/unisage/backend/service/usagelog/*`

**Estimated scope:** M

---

## Checkpoint: Phase 1
- [x] `./mvnw test` pass (full suite, Testcontainers Postgres, no regressions
      across Task 0-4)
- [ ] Postman: gửi payload usage (có 2 line failover) 2 lần → 1 record; summary
      theo provider tách đúng 2 provider (đã có test tự động tương đương —
      `InternalUsageLogControllerTest`, `RequestUsageLogServiceImplTest` — chưa
      làm thủ công qua Postman)
- [ ] Review với human trước khi đụng Python

---

## Phase 2: Python — Đo usage & outbox

### Task 5: `cost_calculator` (actual + estimate)

**Acceptance criteria:**
- [x] `calculate_actual(*, model_name, source_type, input_tokens, output_tokens,
      cached_tokens=0) -> CostResult` (`cost_usd`, `estimated_cost_usd`,
      `cost_status`) — nhận field rời thay vì object `response`/`model_info`
      (Chat dùng PydanticAI `RunUsage`, Embedding/Extraction dùng OpenAI SDK
      `response.usage`, hai hình dạng khác nhau — Task 6/8 tự chuẩn hoá trước
      khi gọi); LiteLLM không có giá → `UNPRICED`, `cost_usd=None`, log warning
      kèm tên model
- [x] `estimate(*, model_name, source_type, input_tokens, max_output_tokens) ->
      Decimal` dùng `litellm.cost_per_token`; không có giá →
      `BUDGET_RESERVATION_FALLBACK_USD` (`Settings.BUDGET_RESERVATION_FALLBACK_USD`,
      mới thêm vào `app/core/config.py`)
- [x] SELF_HOSTED → `FREE`, cost 0, không gọi LiteLLM (test khẳng định bằng
      `monkeypatch` raise nếu `litellm.cost_per_token` bị gọi)

**Verification:**
- [x] pytest: model có giá, không có giá, SELF_HOSTED, embedding (đủ 8 test
      trong `test_cost_calculator.py`, gồm cả test không-network-call của Task 0)

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
- [x] `requestId` sinh ở đầu `chat.py`, truyền xuyên graph (qua `UsageRecorder`,
      không truyền riêng thành tham số thứ 2)
- [x] `chat.py` giữ lại id của USER message (hiện đang bỏ qua kết quả
      `create_message` role USER) + `assistant_message_id` đã có → đưa vào payload
- [x] Mỗi line: `seq`, `nodeName` (tên node, vd `GenerationSynthesisNode`),
      `attempt`, `chatModelId`, snapshot provider/model/sourceType, tokens, cost,
      latency, status, `errorCode`
- [x] Kết thúc graph (thành công, lỗi, client huỷ stream) → đóng recorder đúng 1
      lần (idempotent), đẩy payload vào outbox (Task 7). **Settle budget (Task 9)
      chưa nối** — Task 9 chưa implement, `close()` chỉ enqueue, chưa reserve/settle
      Redis; sẽ nối khi làm Task 9/10
- [x] Không có line nào (request không gọi provider) → **không** đẩy payload
      (test `test_no_lines_recorded_means_nothing_is_enqueued`)
- [x] Thời gian trong payload là ISO-8601 UTC (`...Z`)
- [x] Parent `status`: SUCCESS / ERROR / PARTIAL (có line lỗi nhưng request vẫn
      trả lời được — downgrade tự động trong `close()`)
- [x] **Phát hiện khi implement:** `result.usage()` trong spike Task 0 sai —
      `usage` là **property**, không phải method, trên cả `AgentRunResult` lẫn
      `StreamedRunResult` của bản `pydantic-ai-slim` đang cài; gọi như hàm ném
      `TypeError`, bắt được ngay bởi test suite hiện có (`test_message_classification_node.py`
      đỏ). Đã sửa `streaming.py` dùng `.usage` (không ngoặc) và cập nhật
      DECISIONS.md

**Verification:**
- [x] pytest: request có classification + transformation + generation → 3 line;
      failover generation → 4 line, line lỗi `attempt=0`, line thành công `attempt=1`
      khác provider (`tests/graph/test_usage_recorder_wiring.py`)
- [x] pytest: client disconnect giữa stream → payload vẫn được đẩy vào outbox
      (mô phỏng bằng queue không ai đọc — `run_and_persist` chạy độc lập với
      SSE consumer theo thiết kế sẵn có, xem `test_payload_still_enqueued_when_the_sse_consumer_never_reads_the_queue`)

**Dependencies:** Task 5

**Files likely touched:**
- `unisage-agent/app/core/usage_recorder.py`
- `unisage-agent/app/core/usage_outbox.py` (enqueue-side của Task 7, làm cùng
  vì `UsageRecorder.close()` cần chỗ để gửi payload tới)
- `unisage-agent/app/graph/streaming.py` (thêm `on_attempt`/`AttemptOutcome`,
  không sửa `model_router.py` như dự kiến ban đầu — hook nằm ở streaming.py,
  không phải model_router.py)
- `unisage-agent/app/graph/streaming_graph.py`, `streaming_session.py`
- `unisage-agent/app/graph/nodes/{message_classification,query_transformation,ticket_fallback,generation_synthesis}.py`
- `unisage-agent/app/api/v1/chat.py`
- `unisage-agent/tests/core/{test_usage_recorder,test_usage_outbox}.py`
- `unisage-agent/tests/graph/test_usage_recorder_wiring.py`

**Estimated scope:** M

---

### Task 7: Outbox Redis + worker drain về Java

**Acceptance criteria:**
- [x] `enqueue(payload)` = `LPUSH usage:outbox` — không gọi HTTP trong request
      (đã làm cùng Task 6, xem `app/core/usage_outbox.py`)
- [x] Celery task `drain_usage_outbox` (beat mỗi
      `USAGE_OUTBOX_DRAIN_INTERVAL_SECONDS`, mặc định 5s) giữ lock
      `usage:outbox:lock` (`SET NX EX 60`) → chỉ 1 drainer; đầu lượt đưa toàn bộ
      `usage:outbox:processing` về outbox (reclaim item kẹt do worker chết).
      Lock này degrade khác lock verification: mất Redis → **bỏ qua lượt**
      (không "chạy tiếp không khoá" như verification), vì 2 drainer chạy song
      song không khoá có thể reclaim `processing` không nhất quán
- [x] Mỗi item: `LMOVE usage:outbox → usage:outbox:processing`, gửi
      `POST /internal/usage-logs` (qua `BackendJavaClient.ingest_usage_log`,
      tự kèm `X-Internal-Secret`); 2xx → `LREM` khỏi processing; lỗi mạng/5xx →
      trả lại outbox ngay, dừng lượt (thử lại ở nhịp beat sau); 4xx →
      `usage:outbox:dead` + log error; gửi lại an toàn nhờ idempotency
      `requestId`
- [x] Metric/log: `outbox_health()` trả `{pending, dead}` — chưa nối vào
      `GET /api/v1/health` (đó là Task 11b), hàm đã sẵn sàng để nối

**Verification:**
- [x] pytest: 6 test với fake Redis (list ops thuần, không cần Lua nên không
      cần Redis thật) — gửi thành công, 4xx → dead-letter, 5xx → trả lại outbox
      + dừng lượt, reclaim item kẹt, 2 drainer đồng thời chỉ 1 chạy, health đếm
      đúng. **Chưa làm:** kịch bản "Java down → Chat vẫn trả lời, outbox tăng;
      Java lên lại → outbox rỗng" end-to-end thật (cần cả 2 service chạy) —
      để dành cho Checkpoint Phase 2/harness tích hợp

**Dependencies:** Task 2, Task 6

**Files likely touched:**
- `unisage-agent/app/core/usage_outbox.py`
- `unisage-agent/app/worker/usage_outbox_tasks.py` (không phải
  `app/worker/tasks/usage_outbox.py` như dự kiến — codebase này để task Celery
  phẳng trong `app/worker/`, không có thư mục con `tasks/`, giống
  `verification_tasks.py`)
- `unisage-agent/app/worker/celery_app.py` (đăng ký task + beat_schedule)
- `unisage-agent/app/integrations/backend_java_client.py` (`ingest_usage_log`)
- `unisage-agent/app/core/config.py` (`USAGE_OUTBOX_DRAIN_INTERVAL_SECONDS`)
- `unisage-agent/tests/worker/test_usage_outbox_tasks.py`

**Estimated scope:** M

---

### Task 8: Extraction + Embedding ghi usage

**Acceptance criteria:**
- [x] Extraction: 1 request nghiệp vụ = 1 lần enrich 1 chunk/batch, `purpose =
      EXTRACTION`, line theo từng attempt (có failover) — response malformed
      nhưng call thành công vẫn ghi line SUCCESS (lỗi dữ liệu, không phải lỗi
      call)
- [x] Embedding: 1 request = 1 batch embed, `purpose = EMBEDDING`, message id null
- [x] Hook tại điểm đã chốt ở Task 0 — cả 2 file vẫn dùng OpenAI SDK trực tiếp
      (chưa migrate sang PydanticAI, đúng như DECISIONS.md ghi), nên không tái
      dùng `on_attempt`/`AttemptOutcome` của Task 6 mà gọi thẳng
      `UsageRecorder.record()` (method mới, nhận token thô thay vì `RunUsage`)
      — mỗi hàm `embed()`/`enrich()` tự tạo và tự đóng `UsageRecorder` của
      riêng nó, người gọi (`embed_chunks` Celery task, vòng lặp trong đó) không
      cần sửa gì

**Verification:**
- [x] pytest cho cả 2 luồng: đúng purpose, đúng token từ usage của response
      (`tests/test_embedding_usage_recording.py`, thêm 3 test trong
      `tests/test_multi_representation.py` — bao gồm cả kịch bản failover 2
      line như Chat)

**Dependencies:** Task 7

**Files likely touched:**
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/app/rag/embeddings/openai_embedder.py`
- `unisage-agent/app/core/usage_recorder.py` (thêm method `record()` dùng
  chung cho cả 2 luồng, tách khỏi `_record_attempt()` chỉ dành cho PydanticAI)
- `unisage-agent/tests/test_embedding_usage_recording.py`,
  `unisage-agent/tests/test_multi_representation.py`

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
- [x] `BudgetSnapshot` load từ `GET /internal/budgets/snapshot`, refresh theo
      `BUDGET_SNAPSHOT_REFRESH_SECONDS` và khi nhận `config_version` mới; lỗi load
      → giữ snapshot cũ (fail-open, vì soft limit)
- [x] `Settings` thêm `APP_TIMEZONE` và các biến `BUDGET_*` (placeholder đã có
      trong `.env.example`)
- [x] Mọi số tiền trong Redis là **micro-USD integer** (`INCRBY`/`DECRBY`),
      helper `to_micro_usd(Decimal)` làm tròn half-up
- [x] `reserve_request.lua`: atomic check BLOCK/THROTTLE cho SYSTEM + PURPOSE,
      cộng `reserved`/`inflight`, ghi field `req` vào `budget:resv:{requestId}` +
      ZSET expiry; trả `OK` / `REJECT_EXCEEDED` / `REJECT_THROTTLED`
- [x] `acquire_provider.lua(requestId, seq, provider, estimate)`: atomic check
      BLOCK/THROTTLE của PROVIDER, cộng `reserved`/`inflight` provider, ghi field
      `p:{seq}` (ghi cả khi không có budget PROVIDER, số 0); trả `OK` /
      `DENY_EXCEEDED` / `DENY_THROTTLED`
- [x] `release_provider.lua(requestId, seq, actual)`: trừ đúng số trong `p:{seq}`,
      cộng `committed` provider bằng cost thực, xoá field; field không còn → no-op
- [x] `settle_request.lua`: release mọi `p:*` còn sót, trừ field `req`, cộng
      `committed` SYSTEM/PURPOSE; UNPRICED cộng estimate; marker
      `budget:settled:{requestId}` → gọi lại no-op
- [x] `periodKey` sinh theo `Settings.APP_TIMEZONE`; TTL key = hết kỳ + 3 ngày
- [x] Lỗi Redis → fail-open (cho qua, log error), không chặn Chat

**Implementation notes:**
- Một scope (SYSTEM, một PURPOSE, hay một PROVIDER) có thể có ĐỒNG THỜI cả
  budget DAILY lẫn MONTHLY đang bật (unique index của Java là theo
  (scope[,ref], period), không phải theo scope). Cả 4 script Lua vì vậy nhận
  một **danh sách biến-độ-dài** các cặp scope-period thay vì cố định 1-2 cặp,
  mã hoá `reserved`/`inflight`/`committed` bằng `cjson` list trong field hash.
- `inflight` key chỉ phụ thuộc scope, không phụ thuộc period — một scope có cả
  DAILY và MONTHLY sẽ dùng chung một `inflight` key cho cả 2 cặp. Các script
  dedupe để chỉ INCR/DECR key đó một lần mỗi lệnh gọi, tránh đếm đôi khi có 2
  cặp cùng scope.

**Verification:**
- [x] pytest với Redis thật (container test): 50 request đồng thời SYSTEM BLOCK
      đủ ~10 estimate → ≤ 10 `OK`; SYSTEM THROTTLE cap → third request bị
      `REJECT_THROTTLED` đúng lúc; release/settle 2 lần không cộng đôi; scope có
      cả DAILY+MONTHLY chỉ tăng `inflight` một lần; biên 23:59:59/00:00:01 giờ
      VN ra 2 `periodKey` khác nhau (DAILY và MONTHLY); cộng 10.000 lần
      $0.0000015 không drift

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
- [x] Chat: reserve trước node LLM đầu tiên với estimate × multiplier Chat;
      `REJECT_EXCEEDED` → lỗi `BUDGET_EXCEEDED`, `REJECT_THROTTLED` →
      `BUDGET_THROTTLED` (429), **0** call provider; message lỗi thân thiện
      cho user
- [x] Extraction/Embedding: reserve mỗi call/batch; bị từ chối → job ingest đánh
      dấu lỗi retry được, không retry ngay
- [x] `model_router` gọi `acquire_provider` trước mỗi candidate; `DENY_*` → bỏ
      credential, thử candidate kế tiếp; hết candidate → lỗi budget tương ứng
- [x] Mỗi attempt kết thúc (thành công, lỗi, huỷ stream) → `release_provider`
      với cost thực của line đó, **trước** khi failover acquire provider mới
- [x] Mọi nhánh kết thúc (kể cả exception) đều gọi settle — dùng
      `try/finally` trong `UsageRecorder`

**Implementation notes:**
- Acquire/release cho PROVIDER sống trong `stream_agent_text`/
  `run_agent_text_with_failover` (`streaming.py`) qua `BudgetContext` mới,
  chứ không chỉ trong `model_router.get_next_credential` — vì credential
  dùng chung cho cả 3 node/request nên phải acquire tại đúng điểm gọi
  provider thật (mỗi attempt), không phải một lần lúc chọn credential.
  `model_router.select_credential_with_budget` là helper dùng chung cho cả
  Chat (qua `streaming.py`) và Extraction (`multi_representation.py`), thử
  từng candidate cho tới khi acquire OK hoặc hết candidate.
- Budget gate trong `streaming.py` được canh đúng cùng điều kiện
  all-or-nothing mà `has_failover_wiring` đã dùng (purpose/credential/
  agent_factory/snapshot_version đều phải có) — nếu không, một test double
  model không có registry cũng bị bắt gọi `model_router`, vỡ bất biến cũ.
- `UsageRecorder.close()` trước đây có comment nói sẽ settle kể cả khi
  0 lines, nhưng code lại return sớm không settle gì — sửa lại cho đúng
  với comment.
- Estimate cho PROVIDER acquire (`per_attempt_estimate_usd`) và estimate
  cho reserve request-level (nhân với multiplier) là hai giá trị khác nhau
  — dùng chung một giá trị sẽ over-reserve PROVIDER scope theo đúng hệ số
  multiplier mỗi attempt.

**Verification:**
- [x] pytest với Redis thật: PROVIDER bị BLOCK → fail over sang candidate
      khác; hết candidate → `NoBudgetAvailableError`; một call thành công
      release đúng, không còn gì outstanding trong `reserved`/`inflight`
- [ ] Chưa viết đủ ma trận scope × action 9 ô hay kịch bản failover
      openai→anthropic giữa chừng chi tiết như plan mô tả - phần còn lại
      dựa vào Task 9's coverage cho tầng Lua và 3 test trên cho tầng gọi.

**Dependencies:** Task 6, Task 8, Task 9

**Files likely touched:**
- `unisage-agent/app/core/registry/model_router.py`
- `unisage-agent/app/core/usage/usage_recorder.py`
- `unisage-agent/app/graph/streaming.py`
- `unisage-agent/app/graph/streaming_graph.py`
- `unisage-agent/app/graph/streaming_session.py`
- `unisage-agent/app/graph/stream_error_codes.py`
- `unisage-agent/app/api/v1/chat.py`
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/app/rag/embeddings/openai_embedder.py`

**Estimated scope:** M

---

### Task 11: Đối soát + release reservation treo

**Acceptance criteria:**
- [x] `release_expired_reservations` (beat mỗi 1 phút): release reservation có
      score ZSET quá hạn — trả `reserved`/`inflight` của `req` và mọi `p:*`, không
      cộng `committed`, xoá hash
- [x] `reconcile_budget_committed` (beat mỗi 1 giờ): Lua đọc atomic độ dài
      outbox/processing + `committed` (`C0`); còn item → bỏ lượt; lấy `dbTotal` từ
      `period-totals`; `INCRBY committed (dbTotal − C0)` (không `SET`); lệch > 5%
      → log warning

**Implementation notes:**
- Phát hiện `refresh_budget_snapshot()` (Task 9) chưa từng được gọi ở đâu cả —
  toàn bộ hệ thống budget im lặng không hoạt động (snapshot rỗng → mọi
  reserve/acquire fail-open thành OK). Đã nối load lần đầu + poll định kỳ vào
  cả FastAPI lifespan (asyncio task nền) lẫn Celery worker (load lúc worker
  start + beat task), cùng cờ `MODEL_REGISTRY_ENABLED` như model registry
  đang dùng.
- `GET /internal/usage-logs/period-totals` trả về totals của TẤT CẢ scope
  (SYSTEM + mọi PURPOSE + mọi PROVIDER có budget bật) trong MỘT response cho
  một cặp (period, periodKey) — `reconcile_budget_committed_once` group các
  entry trong snapshot theo (period, periodKey) để gọi Java một lần cho mỗi
  cặp thay vì một lần cho mỗi scope.

**Verification:**
- [x] pytest với Redis thật: reservation quá hạn được release đúng
      reserved/inflight; reservation chưa hết hạn không bị đụng vào; outbox
      còn item → bỏ qua, không gọi Java; drift được sửa đúng và log warning

**Dependencies:** Task 9, Task 7

**Files likely touched:**
- `unisage-agent/app/worker/budget_reconciliation_tasks.py`
- `unisage-agent/app/core/budget/lua/{release_expired_reservations,reconcile_check}.lua`
- `unisage-agent/app/core/budget/poller.py`
- `unisage-agent/app/main.py`, `unisage-agent/app/worker/celery_app.py`
- `unisage-agent/tests/worker/test_budget_reconciliation_tasks.py`

**Estimated scope:** S

---

### Task 11b: Deploy Celery worker/beat, Redis AOF, health outbox, replay dead-letter

**Description:** Agent hiện chỉ có `celery_app.py` và hướng dẫn chạy worker
trong README, chưa có beat, chưa có task định kỳ nào, Redis devcontainer không
bật persistence.

**Acceptance criteria:**
- [x] `celery_app.py` cấu hình `beat_schedule` cho `drain_usage_outbox` (5s),
      `release_expired_reservations` (1 phút), `reconcile_budget_committed` (1 giờ)
- [x] Taskfile thêm `worker` và `beat`; README cập nhật lệnh chạy (Windows +
      Linux)
- [x] `.devcontainer/docker-compose.yml`: service `celery-worker`, `celery-beat`;
      Redis `command: redis-server --appendonly yes --appendfsync everysec` +
      volume `redis_data`
- [x] `GET /api/v1/health` trả `usageOutbox.pending`/`usageOutbox.dead`;
      `dead > 0` → `status = degraded`
- [x] Lệnh `task usage:replay-dead` (script) chuyển toàn bộ
      `usage:outbox:dead` về outbox, in số item đã chuyển

**Implementation notes:**
- Taskfile dùng namespace có sẵn của repo (`be:worker`/`be:beat`, không phải
  `worker`/`beat` trần) để nhất quán với `be:dev`/`be:prod` đang có.
- `celery-worker`/`celery-beat` trong devcontainer dùng `sleep infinity`
  giống service `app` hiện tại, không tự chạy lệnh celery thật — venv
  Windows/Linux khác layout (`Scripts/` và `bin/`) nên không có đường dẫn
  nào an toàn để hardcode; dev tự attach và chạy `task be:worker`/`be:beat`
  bên trong, đúng cách `app` đang yêu cầu `task be:dev`.

**Verification:**
- [x] pytest: health trả `degraded` đúng khi `usage_outbox.dead > 0` (mock
      `outbox_health`); `task usage:replay-dead`/`replay_dead_letter()` di
      chuyển toàn bộ item và là no-op an toàn khi hàng rỗng (Redis thật)
- [ ] Không chạy được trên môi trường hiện tại (không có Docker daemon):
      `docker compose restart redis` giữ nguyên outbox/dead-letter/counter,
      tắt/bật beat làm outbox tăng rồi drain hết, và luồng
      "payload sai schema → dead → health degraded → sửa → replay → Java
      nhận" đầu-cuối qua container thật — cần verify thủ công khi có
      Docker.

**Dependencies:** Task 7, Task 11

**Files likely touched:**
- `unisage-agent/app/worker/celery_app.py`
- `unisage-agent/Taskfile.yml`, `unisage-agent/taskfiles/*.yml`
- `unisage-agent/README.md`
- `unisage-agent/.devcontainer/docker-compose.yml`
- `unisage-agent/app/api/v1/health.py`
- `unisage-agent/app/tools/replay_dead_letter.py`

**Estimated scope:** M

---

## Checkpoint: Phase 3
- [x] Budget SYSTEM BLOCK = $0 → request Chat bị từ chối ngay, không call provider
      (pytest với Redis thật, `test_block_budget_rejects_once_exhausted` + reserve
      request-level trong `streaming_session.py`)
- [x] Budget PROVIDER = $0 BLOCK, SYSTEM còn → request thành công qua provider khác
      (`test_provider_budget_denial_falls_over_to_the_next_candidate`)
- [x] Kill process giữa request → sau TTL, `reserved`/`inflight` về đúng
      (`test_release_expired_reservations_frees_reserved_and_inflight`, mô phỏng
      expiry bằng cách ghi score ZSET vào quá khứ)
- [ ] Restart Redis → không mất outbox/counter — chưa verify được: môi trường làm
      việc không có Docker daemon thật để chạy `docker compose restart redis`.
      AOF (`appendonly yes --appendfsync everysec`) đã cấu hình ở Task 11b; user đã
      quyết định cho pass mục này để tiếp tục Phase 4, tự verify bằng Docker sau.
- [x] Review với human trước khi làm Phase 4 — user xác nhận tiếp tục Task 12 luôn

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
