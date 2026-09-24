# Todo: Cost Tracking + Budget Management

Xem `plan.md` trong cùng thư mục cho bối cảnh, data model, và thiết kế tab UI
đầy đủ. Bắt đầu phase này **sau khi** Phase 0-8 của
`changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/` đã xong (cần
Model Registry hoạt động để biết credential nào đang gọi mỗi request). Toàn bộ
6 phase dưới đây làm trong 1 lượt (không cắt MVP nhỏ hơn), trừ Routing Policy đã
tách sang plan Active Switch (Phase 9 ở đó).

---

## Phase 1: Java — Data model & Budget API

### Task 1: Entity `RequestUsageLog` + migration

**Description:** Bảng lưu từng lần gọi model đã hoàn tất (thành công hoặc lỗi),
là nguồn dữ liệu duy nhất cho dashboard, lịch sử, và budget enforcement. Theo
convention Flyway hiện có, kiểm tra số migration lớn nhất hiện tại trước khi đặt
tên file mới.

**Acceptance criteria:**
- [ ] Entity `RequestUsageLog` extends `BaseEntity`, fields: `requestId` (UUID,
      unique, dùng để correlate log Python↔Java khi debug), `purpose`
      (CHAT/EMBEDDING/EXTRACTION), `messageId` (FK `Message`, nullable),
      `chatModel` (FK `ChatModel`), `userId` (FK `User`, nullable), `guestIp`
      (String, nullable), `inputTokens`, `outputTokens`, `cachedTokens`
      (Integer), `costUsd` (BigDecimal, nullable — null nghĩa là không xác định
      được giá, xem Risk trong plan.md), `latencyMs` (Integer), `status`
      (SUCCESS/ERROR) — KHÔNG có field chunk riêng: chunks đã dùng đọc qua
      `Message.citations` (đã tồn tại) khi join theo `messageId`, tránh lưu trùng
- [ ] Migration mới thêm bảng `request_usage_logs`, index trên
      (`purpose`, `created_at`), (`chat_model_id`, `created_at`), (`user_id`)
      để phục vụ query dashboard theo khoảng thời gian

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Build succeeds: `./mvnw clean package -DskipTests`

**Dependencies:** None (nhưng chỉ nên bắt đầu code sau khi Model Registry Phase
0-8 đã xong theo mô tả ở đầu file)

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/entity/RequestUsageLog.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/enums/RequestStatus.java`
- `unisage-backend/src/main/resources/db/migration/V1X__add_request_usage_logs.sql`

**Estimated scope:** S (3 files)

---

### Task 2: API ghi nhận usage log — nội bộ, gọi bởi Python

**Description:** Endpoint nội bộ (cùng cơ chế xác thực nội bộ như health-update
API ở phase Model Registry, Task 2) để Python gửi 1 `RequestUsageLog` sau khi đã
tính cost xong. Phải nhanh và không blocking vì Python gọi bất đồng bộ ngay sau
mỗi request.

**Acceptance criteria:**
- [ ] `POST /internal/usage-logs` nhận đúng field như Task 1, insert 1 dòng
- [ ] Trả về nhanh (không có xử lý nặng đồng bộ trong request này — mọi tổng hợp
      cho dashboard tính ở Task 4, không tính lại ở đây)
- [ ] Không public, dùng cơ chế internal-secret như Task 2 của phase trước

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check: gọi qua Postman với payload mẫu, xác nhận record xuất hiện
      đúng trong DB

**Dependencies:** Task 1

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/RequestUsageLogController.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/usagelog/RequestUsageLogServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/request/RequestUsageLogCreateRequest.java`

**Estimated scope:** S (3 files)

---

### Task 3: Entity `Budget` + `BudgetAlertLog` + CRUD API cho SA

**Description:** Cho phép SA cấu hình budget theo scope/period/limit/action, và
bảng ghi lịch sử alert đã gửi (dùng ở Task 12 để debounce, và Tab Cảnh báo để
hiển thị).

**Acceptance criteria:**
- [ ] Entity `Budget`: `scope` (SYSTEM/PROVIDER/USER_GROUP/PURPOSE), `scopeRefId`
      (nullable), `period` (DAILY/MONTHLY), `limitUsd`, `action`
      (ALERT/THROTTLE/BLOCK), `isEnabled`
- [ ] Entity `BudgetAlertLog`: `budgetId` (FK), `thresholdPercent` (50/80/100),
      `channel` (IN_APP/EMAIL/SLACK), `sentAt`, `status` (SUCCESS/FAILED)
- [ ] Migration mới cho cả 2 bảng
- [ ] CRUD API: `POST/GET/PUT/DELETE /budgets`, `GET /budgets/{id}/alert-history`
- [ ] Validate: chỉ 1 budget `SYSTEM` + cùng `period` được `isEnabled=true` tại 1
      thời điểm (tránh 2 budget SYSTEM mâu thuẫn nhau)

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check qua Postman: tạo budget SYSTEM, thử tạo budget SYSTEM thứ 2
      cùng period → bị từ chối theo đúng validate

**Dependencies:** None

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/entity/Budget.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/BudgetAlertLog.java`
- `unisage-backend/src/main/java/com/unisage/backend/controller/BudgetController.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/budget/BudgetServiceImpl.java`
- `unisage-backend/src/main/resources/db/migration/V1X__add_budget_tables.sql`

**Estimated scope:** M (5 files)

---

### Task 4: API tổng hợp dashboard

**Description:** Endpoint(s) trả dữ liệu đã tổng hợp cho Tab Tổng quan và Tab
Lịch sử sử dụng — group theo ngày/tháng/provider/model/user/purpose, tránh để
FE tự tổng hợp từ raw log (dữ liệu có thể rất lớn).

**Acceptance criteria:**
- [ ] `GET /usage-logs/summary?from=&to=&groupBy=purpose|provider|model|user|day`
      trả tổng cost/tokens/số request theo nhóm được chọn
- [ ] `GET /usage-logs?filters...&page=` trả danh sách phân trang cho Tab Lịch
      sử sử dụng (kèm filter theo purpose/provider/model/user/status/thời gian)
- [ ] `GET /usage-logs/{id}` trả chi tiết 1 record kèm join `Message` (query,
      answer, và `citations` có sẵn cho phần chunks) cho drawer chi tiết
- [ ] Query có index phù hợp (dùng index đã tạo ở Task 1), không full table scan
      trên khoảng thời gian rộng

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check: insert vài trăm record giả lập, xác nhận response time hợp
      lý (< 1s) cho query theo tháng

**Dependencies:** Task 1, Task 2

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/RequestUsageLogController.java`
- `unisage-backend/src/main/java/com/unisage/backend/repository/RequestUsageLogRepository.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/response/UsageSummaryResponse.java`

**Estimated scope:** M (3-4 files)

---

## Checkpoint: Phase 1
- [ ] `./mvnw test` pass
- [ ] Insert usage log giả lập + tạo budget qua Postman → API summary trả đúng
      số liệu tổng hợp
- [ ] Review với human trước khi đụng Python

---

## Phase 2: Python — Tính cost & ghi log

### Task 5: Tích hợp `litellm.completion_cost()`

**Description:** Sau mỗi lần gọi model qua LiteLLM (đã tích hợp ở phase Model
Registry, Task 5/12 của plan kia) cho CLOUD_API, tính cost bằng
`litellm.completion_cost(completion_response=response)`. Xử lý case model không
có trong bảng giá nội bộ LiteLLM (trả None/raise) — không mặc định thành 0.

**Acceptance criteria:**
- [ ] Hàm wrapper `calculate_cost(response, chat_model) -> Decimal | None` — trả
      `None` rõ ràng nếu LiteLLM không tính được (log warning kèm tên model)
- [ ] `SELF_HOSTED` model luôn trả cost = `Decimal("0")` ngay từ đầu, không gọi
      `completion_cost()` (không có ý nghĩa với model tự host)
- [ ] Test case: model có giá trong LiteLLM, model không có giá, model SELF_HOSTED

**Verification:**
- [ ] Tests pass: pytest cho `calculate_cost` với response giả lập

**Dependencies:** Phase Model Registry hoàn tất (LiteLLM đã tích hợp)

**Files likely touched:**
- `unisage-agent/app/core/cost_calculator.py`
- `unisage-agent/tests/core/test_cost_calculator.py`

**Estimated scope:** S (2 files)

---

### Task 6: Tổng hợp cost nhiều node → 1 `RequestUsageLog`, gửi Java bất đồng bộ

**Description:** 1 request Chat chạy qua nhiều node LLM (classification,
direct_llm, query_transformation, generation). Tổng hợp cost/token của tất cả
node trong 1 lần xử lý thành 1 record duy nhất, gửi về Java qua API Task 2 —
**bất đồng bộ, không chặn response trả cho user** (fire-and-forget hoặc qua
background task, chấp nhận mất 1 vài log nếu Java tạm thời down thay vì làm
chậm/lỗi trải nghiệm Chat).

**Acceptance criteria:**
- [ ] 1 object tích luỹ cost/token qua toàn bộ graph execution của 1 request
      (gắn vào state hiện có của graph, không tạo global mutable state)
- [ ] Sau khi graph hoàn tất (thành công hoặc lỗi), gửi 1 lần duy nhất tới Java
      qua `POST /internal/usage-logs`, không block response
- [ ] Gửi thất bại (Java down) → log lỗi, không raise lên làm fail request của
      user
- [ ] `requestId` sinh ở đầu request, giữ nhất quán xuyên suốt graph để
      correlate log khi cần debug

**Verification:**
- [ ] Tests pass: pytest xác nhận tổng cost = tổng cost từng node, và response
      trả về user không bị trễ bởi việc gửi log

**Dependencies:** Task 5

**Files likely touched:**
- `unisage-agent/app/graph/streaming_state.py`
- `unisage-agent/app/integrations/backend_java_client.py`
- `unisage-agent/tests/graph/test_streaming_state.py`

**Estimated scope:** M (3 files)

---

### Task 7: Áp dụng cho Extraction và Embedding

**Description:** Extraction (`multi_representation.py`) và Embedding
(`openai_embedder.py`) cũng ghi `RequestUsageLog`, nhưng đơn giản hơn Chat (1
lần gọi = 1 record, không cần tổng hợp nhiều node).

**Acceptance criteria:**
- [ ] `multi_representation.py` ghi log sau mỗi lần gọi, `purpose = EXTRACTION`
- [ ] `openai_embedder.py` ghi log sau mỗi lần gọi, `purpose = EMBEDDING`,
      `messageId = null` (không gắn với 1 message chat cụ thể)

**Verification:**
- [ ] Tests pass: pytest cho cả 2 file xác nhận log được gửi đúng `purpose`

**Dependencies:** Task 6

**Files likely touched:**
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/app/rag/embeddings/openai_embedder.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 2
- [ ] Gửi 1 request chat thật → 1 record `RequestUsageLog` xuất hiện đúng ở Java
      với cost/token khớp thực tế (đối chiếu thủ công với usage trả về từ provider)
- [ ] Review với human trước khi làm Phase 3

---

## Phase 3: Python — Budget enforcement real-time

### Task 8: Running-total budget trong Redis

**Description:** Mỗi khi ghi 1 `RequestUsageLog` (Task 6/7), cộng dồn cost vào 1
key Redis theo scope (system-wide, theo provider, theo purpose) và theo kỳ hiện
tại (ngày/tháng — dùng key có suffix ngày/tháng để tự "reset" khi sang kỳ mới,
không cần job dọn dẹp riêng).

**Acceptance criteria:**
- [ ] Key Redis dạng `budget:running:{scope}:{scopeRefId}:{period}:{periodKey}`
      (vd `budget:running:SYSTEM:*:MONTHLY:2026-09`), tăng bằng `INCRBYFLOAT`
      ngay sau khi ghi usage log thành công
- [ ] TTL hợp lý cho mỗi key (vd hết kỳ + vài ngày buffer) để tự dọn, không tích
      luỹ key vô hạn

**Verification:**
- [ ] Tests pass: pytest với Redis giả lập, xác nhận cộng dồn đúng qua nhiều
      request liên tiếp

**Dependencies:** Task 6

**Files likely touched:**
- `unisage-agent/app/core/budget_tracker.py`
- `unisage-agent/tests/core/test_budget_tracker.py`

**Estimated scope:** S (2 files)

---

### Task 9: Kiểm tra budget trước khi gọi provider

**Description:** Trước khi `model_router` (phase Model Registry) gọi provider,
kiểm tra running-total so với `limitUsd` của budget SYSTEM đang active (đọc từ
snapshot config, đồng bộ cùng cơ chế hot-reload Model Registry). Nếu vượt →
`action = BLOCK` cho SYSTEM luôn nghĩa là từ chối (theo quyết định đã chốt: hết
ngân sách hệ thống → từ chối, không fallback). Budget scope hẹp hơn (provider/
purpose) áp đúng `action` đã cấu hình cho scope đó.

**Acceptance criteria:**
- [ ] Check chạy trong fast-path (đọc Redis + snapshot in-memory, không gọi Java
      đồng bộ)
- [ ] Budget SYSTEM vượt ngưỡng với `action=BLOCK` → raise lỗi ngay, 0 lệnh gọi
      provider nào được thực hiện
- [ ] Budget scope PROVIDER vượt ngưỡng → áp đúng `action` cấu hình cho scope đó
      (ALERT: vẫn cho qua + trigger Task 12; THROTTLE: delay/giảm rate — định
      nghĩa cụ thể "throttle" khi bắt đầu code, ví dụ giới hạn concurrent request
      cho scope đó; BLOCK: loại credential thuộc scope đó khỏi routing, để
      `model_router` tự fallback sang scope khác nếu có)
- [ ] Test riêng cho từng combination scope × action

**Verification:**
- [ ] Tests pass: pytest cho từng scope × action

**Dependencies:** Task 8, Model Registry `model_router` đã có (phase trước)

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/core/budget_tracker.py`
- `unisage-agent/app/api/exceptions.py`

**Estimated scope:** M (3 files)

---

### Task 10: Đối soát định kỳ running-total với Java

**Description:** Vì running-total Redis có thể lệch (crash giữa chừng, mất
update), định kỳ (vd mỗi giờ) tính lại tổng thật từ `RequestUsageLog` (qua API
summary Task 4) và ghi đè lại giá trị Redis — tương tự pattern version-check của
Model Registry hot-reload.

**Acceptance criteria:**
- [ ] Background job (Celery beat hoặc tương đương đã có sẵn cho ingestion) gọi
      API summary định kỳ, cập nhật lại key Redis cho scope SYSTEM tối thiểu
- [ ] Log rõ khi phát hiện lệch đáng kể giữa running-total và tổng thật (để phát
      hiện bug sớm)

**Verification:**
- [ ] Tests pass: pytest giả lập lệch running-total → xác nhận job sửa lại đúng

**Dependencies:** Task 9, Task 4

**Files likely touched:**
- `unisage-agent/app/worker/budget_reconciliation.py`

**Estimated scope:** S (1-2 files)

---

## Checkpoint: Phase 3
- [ ] Set budget SYSTEM = $0 → request Chat mới bị từ chối ngay, không gọi provider
- [ ] Set budget 1 provider cụ thể = $0, budget SYSTEM còn → request vẫn thành
      công qua provider khác (nếu có fallback khả dụng ở Model Registry)
- [ ] Review với human trước khi làm Phase 4

---

## Phase 4: Cảnh báo

### Task 11: Job kiểm tra ngưỡng + spike detection

**Description:** Định kỳ (hoặc ngay sau mỗi lần cộng running-total ở Task 8) so
% đã dùng với các ngưỡng 50/80/100% đã cấu hình, và so chi phí ngày hiện tại với
trung bình 7 ngày trước để phát hiện tăng đột biến.

**Acceptance criteria:**
- [ ] Kiểm tra ngưỡng chạy sau mỗi lần `INCRBYFLOAT` (Task 8) thay vì poll định
      kỳ riêng — phát hiện ngay khi vừa vượt ngưỡng
- [ ] Spike detection: job định kỳ hàng ngày so sánh, không chạy mỗi request
- [ ] Kết quả (ngưỡng nào vừa đạt, budget nào) đưa vào hàng đợi để Task 12 xử lý
      gửi alert, tách biệt việc "phát hiện" và "gửi"

**Verification:**
- [ ] Tests pass: pytest cho cả 2 loại kiểm tra với dữ liệu giả lập

**Dependencies:** Task 8

**Files likely touched:**
- `unisage-agent/app/core/budget_tracker.py`
- `unisage-agent/app/worker/budget_reconciliation.py`

**Estimated scope:** S (2 files)

---

### Task 12: Gửi alert qua kênh cấu hình + ghi `BudgetAlertLog`

**Description:** Nhận kết quả từ Task 11, gửi qua kênh SA đã cấu hình (in-app/
email/Slack — reuse `slack_notifier` đã dựng ở phase Model Registry), ghi
`BudgetAlertLog` qua Java để debounce (không gửi lại cùng ngưỡng trong cùng kỳ).

**Acceptance criteria:**
- [ ] Trước khi gửi, check `BudgetAlertLog` (qua Java) xem ngưỡng này đã gửi
      trong kỳ hiện tại chưa — có thì bỏ qua
- [ ] Gửi xong, ghi lại `BudgetAlertLog` (thành công/thất bại)
- [ ] Slack message tái sử dụng `slack_notifier.send` (Task 14 ở phase Model
      Registry), không viết client Slack mới

**Verification:**
- [ ] Tests pass: pytest xác nhận không gửi trùng ngưỡng trong cùng kỳ

**Dependencies:** Task 11, Slack notifier (phase Model Registry)

**Files likely touched:**
- `unisage-agent/app/core/budget_alerting.py`
- `unisage-agent/tests/core/test_budget_alerting.py`

**Estimated scope:** S (2 files)

---

### Task 13: unisage-web hiển thị in-app alert

**Description:** Banner/notification trong khu admin khi có budget vượt ngưỡng,
đọc từ `BudgetAlertLog`/API mới nếu cần polling.

**Acceptance criteria:**
- [ ] Banner hiển thị ở trang admin (không chỉ trong trang Cost Management) khi
      có alert `IN_APP` chưa đọc, dismiss được

**Verification:**
- [ ] Manual check trong browser

**Dependencies:** Task 12

**Files likely touched:**
- `unisage-web/src/features/cost-management/` (mới)

**Estimated scope:** S (1-2 files)

---

## Checkpoint: Phase 4
- [ ] Chi phí vượt 80% ngân sách → alert xuất hiện đúng kênh đã cấu hình, đúng 1
      lần (không lặp lại liên tục)
- [ ] Review với human trước khi làm Phase 5

---

## Phase 5: unisage-web — Trang AI Cost Management

### Task 14: Tab Tổng quan (KPI + charts)

**Description:** Theo thiết kế ở `plan.md` mục "Tab 1 — Tổng quan": 4 KPI card,
donut chart theo purpose, bar chart theo provider/model, line chart xu hướng
theo ngày, bảng top user/API key.

**Acceptance criteria:**
- [ ] 4 KPI card đúng số liệu từ API summary (Task 4), progress bar đổi màu theo
      ngưỡng 50/80%
- [ ] Donut/bar/line chart render đúng dữ liệu, có filter thời gian/purpose/provider

**Verification:**
- [ ] Manual check trong browser với dữ liệu thật/seed

**Dependencies:** Task 4

**Files likely touched:**
- `unisage-web/src/features/cost-management/overview/`

**Estimated scope:** M (3-5 files)

---

### Task 15: Tab Ngân sách & Giới hạn

**Description:** CRUD UI cho `Budget` — bảng danh sách + form thêm/sửa theo thiết
kế "Tab 2" trong `plan.md`.

**Acceptance criteria:**
- [ ] Form đủ 4 field: Scope, Period, Limit, Action, validate scope SYSTEM
      trùng lặp theo rule Task 3
- [ ] Bảng danh sách hiển thị % đã dùng trực quan (progress bar) cho mỗi budget

**Verification:**
- [ ] Manual check: tạo/sửa/xoá 1 budget qua UI, xác nhận đồng bộ với Java

**Dependencies:** Task 3

**Files likely touched:**
- `unisage-web/src/features/cost-management/budgets/`

**Estimated scope:** M (3-4 files)

---

### Task 16: Tab Cảnh báo

**Description:** Cấu hình ngưỡng/kênh + bảng lịch sử alert, theo "Tab 3" trong
`plan.md`.

**Acceptance criteria:**
- [ ] Form cấu hình ngưỡng (50/80/100 + custom), toggle kênh in-app/email/Slack
- [ ] Bảng lịch sử alert từ `BudgetAlertLog`

**Verification:**
- [ ] Manual check trong browser

**Dependencies:** Task 3, Task 12

**Files likely touched:**
- `unisage-web/src/features/cost-management/alerts/`

**Estimated scope:** S (2-3 files)

---

### Task 17: Tab Bảng giá (tĩnh, chỉ đọc)

**Description:** Bảng giá hardcode ở FE theo "Tab 4" trong `plan.md` — dữ liệu
tĩnh trong code FE, không gọi API.

**Acceptance criteria:**
- [ ] Danh sách giá cho các model đang thực sự dùng trong Model Registry (không
      cần liệt kê toàn bộ model LiteLLM hỗ trợ, chỉ các model SA đã đăng ký)
- [ ] Ghi chú "chỉ tham khảo" hiển thị rõ đầu bảng
- [ ] Model SELF_HOSTED hiển thị "Không tính phí"

**Verification:**
- [ ] Manual check trong browser

**Dependencies:** None

**Files likely touched:**
- `unisage-web/src/features/cost-management/pricing/`
- `unisage-web/src/constants/` (dữ liệu giá tĩnh)

**Estimated scope:** S (2 files)

---

### Task 18: Tab Lịch sử sử dụng + drawer chi tiết request

**Description:** Bảng lịch sử với filter + drawer chi tiết theo "Tab 5" trong
`plan.md`.

**Acceptance criteria:**
- [ ] Bảng có filter (thời gian/purpose/provider/model/user/status), phân trang
- [ ] Click 1 dòng mở drawer hiển thị đủ: Query, Answer, Chunks đã dùng (từ
      `Message.citations`), Model đã dùng, Token breakdown, Cost, IP/user,
      Request ID

**Verification:**
- [ ] Manual check trong browser: 1 request chat thật → mở drawer thấy đúng nội
      dung khớp với conversation thật

**Dependencies:** Task 4

**Files likely touched:**
- `unisage-web/src/features/cost-management/usage-history/`

**Estimated scope:** L (5+ files — cân nhắc tách bảng và drawer thành 2 task nếu
quá lớn cho 1 session)

---

## Checkpoint: Phase 5 (checkpoint cuối)
- [ ] Toàn bộ 5 tab render đúng dữ liệu thật từ API Phase 1/4
- [ ] Drawer chi tiết request hiển thị đúng query/answer/chunks/model/cost

---

## Đã bỏ: "/profile — Usage section"

Ban đầu dự kiến 1 Task 19 thêm section "Mức sử dụng" (token tuần/tháng) vào
`/profile`. Sau khi kiểm tra code, **tính năng này đã tồn tại**:
`unisage-web/src/features/usage-limits/components/usage-limit-card.tsx` render
sẵn trong `user-profile.tsx`, đọc từ `GET /usage-limits/me`
(`UsageLimitController` → `UsageLimitServiceImpl`, Java), hiển thị 2 window
DAILY/WEEKLY dựa trên token **ước lượng từ độ dài text** (`checkAndConsumeQuestion`/
`consumeAnswer`) — phục vụ rate-limit theo gói (plan), khác nguồn dữ liệu với
`RequestUsageLog` (token thật, dùng để tính cost $) mà phase này xây.

**Quyết định: để 2 hệ thống độc lập, không làm task gộp trong phase này** — xem
"Open Questions" trong `plan.md` để biết lý do (quota check cần chạy đồng bộ,
nhanh, không thể phụ thuộc Python báo cost về). Không có task nào thay thế Task
19; nếu sau này cần đổi, mở 1 task riêng khi có yêu cầu cụ thể.

---

## Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ acceptance criteria ở mục Success trong intent đã xác nhận đều pass
- [ ] SA xem được dashboard, set budget, nhận cảnh báo, xem chi tiết từng request
      — toàn bộ qua UI, không cần dev can thiệp
- [ ] Ready for review
