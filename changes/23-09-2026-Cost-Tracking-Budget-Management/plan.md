# Implementation Plan: Cost Tracking + Budget Management

## Overview

Phase này nối tiếp **sau khi** Dynamic Model Registry + Runtime Active Switch
(`changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/`) đã triển khai
xong Phase 0-8 (bắt buộc, vì phase này cần Model Registry đã hoạt động để biết
credential/provider/model nào đang được gọi cho từng request). SA hiện không có
cách nào biết hệ thống đang tốn bao nhiêu tiền LLM mỗi ngày/tháng, theo chức
năng nào (Chat/Ingest/Embedding), hay dừng lại khi vượt ngân sách. Phase này bổ
sung: đo cost mỗi request, dashboard, budget & giới hạn chi tiêu, cảnh báo, lịch
sử/đối soát, trang chi tiết từng request, và section usage trong `/profile`.

**Routing Policy nâng cao (LOWEST_COST/BALANCED/PRIORITY/QUALITY_FIRST) không
nằm trong phase này** — đã được thêm làm Phase 9 trong plan Active Switch, vì nó
tiêu thụ dữ liệu cost/latency mà phase này tạo ra, nên phải làm sau.

## Architecture Decisions

- **Không tự xây bảng giá trong DB, không fetch giá tự động từ web provider.**
  LiteLLM đã có sẵn bảng giá nội bộ cho hầu hết model CLOUD_API phổ biến và hàm
  `litellm.completion_cost(completion_response=response)` tính cost ($) trực
  tiếp từ response — không cần SA nhập tay, không cần scrape (scrape dễ vỡ âm
  thầm vì provider không có API giá chính thức, và giá thực SA trả có thể khác
  giá public do hợp đồng riêng).
- **`SELF_HOSTED` model = cost $0, không tính vào budget.** Đây là chi phí hạ
  tầng cố định (GPU/server), khác bản chất với ngân sách "trả theo API call" mà
  phase này theo dõi. Không đưa vào dashboard chi phí, không trừ vào budget.
- **Bảng giá hiển thị cho SA (tab Pricing) là dữ liệu tĩnh, hardcode ở frontend
  — chỉ mang tính tham khảo, không phải nguồn tính cost thật.** Cost thật luôn
  đến từ `litellm.completion_cost()` ở Python, ghi vào `RequestUsageLog` (Java).
  Rủi ro: bảng FE có thể lệch giá thật nếu provider đổi giá và không ai cập nhật
  — chấp nhận rủi ro này vì đây chỉ là thông tin tham khảo, không ảnh hưởng số
  liệu dashboard/budget (số liệu dashboard luôn lấy từ `RequestUsageLog` thật).
- **Cost tính ở Python, gửi kết quả đã tính về Java để lưu.** 1 pipeline Chat có
  nhiều node LLM (classification, direct_llm, query_transformation, generation)
  — Python tổng hợp cost của toàn bộ node trong 1 lần xử lý request thành 1 con
  số cuối cùng trước khi gửi về Java, không gửi từng node riêng lẻ (tránh Java
  phải biết chi tiết cấu trúc pipeline nội bộ của Python).
- **Budget enforcement real-time dùng running-total trong Redis, không gọi Java
  đồng bộ trên mỗi request** (giữ đúng fast-path constraint đã chốt ở phase Model
  Registry). Java là nguồn cấu hình budget chính; Python giữ running-total được
  cập nhật mỗi khi ghi 1 record cost, và định kỳ đối soát lại với Java để tránh
  lệch tích luỹ (cùng pattern version-check đã dùng cho hot-reload Model Registry).
- **Reuse Slack Incoming Webhook đã dựng ở phase trước** cho kênh cảnh báo budget,
  không dựng tích hợp Slack thứ 2.
- **Request detail (query/answer/chunks) join với `Message`/`Conversation` đã có
  sẵn ở Java, không lưu trùng nội dung text hay chunk.** `Message.citations`
  (JSON, đã tồn tại — build bởi `app/rag/prompting/citations.py`, chứa
  `documentId`/`title`/`section`/`pageStart`/`pageEnd`/`sourceType` cho các
  chunk thực sự được trích dẫn `[n]` trong câu trả lời) **đã là đúng dữ liệu
  cần cho phần "chunks đã dùng"** ở Tab Lịch sử sử dụng — không thêm field mới.
  `RequestUsageLog` chỉ cần `messageId` (FK, nullable) + số liệu cost/token/
  latency; nội dung câu hỏi/câu trả lời và chunks đọc qua `Message` khi join.
  Lưu ý: `citations` chỉ chứa chunk **được trích dẫn trong câu trả lời cuối**,
  không phải toàn bộ chunk RAG đã retrieve (có thể có chunk retrieve về nhưng
  không được model dùng) — nếu sau này cần audit cả tập retrieve đầy đủ, đó là
  1 field/bảng bổ sung riêng, ngoài scope hiện tại.

## Data Model (Java, control plane)

- **`RequestUsageLog`** (bảng mới): `id`, `requestId` (UUID, để correlate log
  Python↔Java), `purpose` (CHAT/EMBEDDING/EXTRACTION), `messageId` (FK tới
  `Message`, nullable — null cho Embedding/Extraction không gắn với 1 message
  chat cụ thể), `userId` (FK, nullable), `guestIp` (nullable, khi chưa đăng
  nhập), `chatModelId` (FK tới `ChatModel`), `inputTokens`, `outputTokens`,
  `cachedTokens`, `costUsd`, `latencyMs`, `status` (SUCCESS/ERROR), `createdAt`.
  Chunks đã dùng đọc qua `Message.citations` (join theo `messageId`), không
  lưu trùng ở đây.
- **`Budget`**: `id`, `scope` (SYSTEM/PROVIDER/USER_GROUP/PURPOSE), `scopeRefId`
  (nullable — id provider/group/purpose cụ thể tuỳ scope), `period`
  (DAILY/MONTHLY), `limitUsd`, `action` (ALERT/THROTTLE/BLOCK), `isEnabled`.
- **`BudgetAlertLog`**: lịch sử alert đã gửi (budget nào, ngưỡng nào, kênh nào,
  thời điểm) — để hiển thị tab Cảnh báo và tránh gửi trùng (debounce).

## Giao diện quản lý ngân sách (unisage-web) — Tabs & nội dung

Trang mới `AI Cost Management` trong khu admin, chia 5 tab:

### Tab 1 — Tổng quan (Overview)
- 4 KPI card đầu trang: **Chi phí tháng này** ($ + % thay đổi so tháng trước),
  **Ngân sách tháng** (limit đã cấu hình cho scope SYSTEM), **% ngân sách đã
  dùng** (progress bar: xanh <50%, vàng 50-80%, đỏ >80%), **Còn lại** ($).
- **Phân bổ chi phí theo chức năng**: donut/bar chart Chat/Ingest/Embedding, kèm
  $ và % (giống mock-up bạn đưa: Chat 51.4%, Ingest 35%, Embedding 13.6%).
- **Phân bổ theo provider/model**: bar chart ngang, top provider/model tốn nhiều
  nhất trong kỳ.
- **Biểu đồ xu hướng theo ngày**: line chart chi phí/ngày trong tháng hiện tại,
  để thấy ngày nào tăng đột biến.
- **Top người dùng/API key tốn nhiều nhất**: bảng nhỏ top 5-10.
- Filter chung của tab: khoảng thời gian (hôm nay/tuần/tháng/tuỳ chọn), purpose,
  provider — áp dụng cho toàn bộ chart trong tab.

### Tab 2 — Ngân sách & Giới hạn (Budget & Limits)
- Bảng danh sách `Budget` đã cấu hình: cột Scope, Period, Limit, Đã dùng (kỳ hiện
  tại), % , Action khi vượt ngưỡng, Enabled/Disabled, nút Sửa/Xoá.
- Form thêm/sửa 1 budget: chọn Scope (System-wide / Provider / Nhóm người dùng /
  Purpose: Chat-Ingest-Embedding), chọn Period (Daily/Monthly), nhập Limit ($),
  chọn Action (Cảnh báo / Giới hạn (throttle) / Dừng request (block)).
- Cảnh báo rõ trên UI: chỉ budget scope **SYSTEM** khi hết mới **từ chối** request
  thay vì fallback provider khác; budget scope hẹp hơn hết thì hệ thống tự
  fallback sang provider/credential khác (liên kết với Phase 9 - Routing Policy
  ở plan Active Switch).

### Tab 3 — Cảnh báo (Alerts)
- Cấu hình ngưỡng: checkbox/input cho 50%, 80%, 100% (mặc định bật cả 3, có thể
  thêm ngưỡng tuỳ chỉnh), toggle "Phát hiện chi phí tăng đột biến" kèm % ngưỡng
  spike so với trung bình 7 ngày trước.
- Cấu hình kênh nhận cảnh báo: In-app (mặc định bật), Email (danh sách địa chỉ),
  Slack (reuse webhook URL đã cấu hình ở phase Active Switch — 1 dropdown chọn
  dùng chung channel API key incident hay tạo channel riêng cho budget).
- Bảng **lịch sử cảnh báo đã gửi**: thời điểm, budget/scope liên quan, ngưỡng đã
  đạt, kênh đã gửi, trạng thái gửi (thành công/lỗi).

### Tab 4 — Bảng giá (Pricing, chỉ xem)
- Bảng tĩnh (hardcode ở FE): Provider, Model, Loại token (Input/Output/Cached),
  Đơn vị tính, Giá/1K token. Nhóm theo provider.
- Model SELF_HOSTED hiển thị dòng riêng ghi "Không tính phí (hạ tầng nội bộ)".
- Ghi chú cố định đầu bảng: "Bảng giá chỉ mang tính tham khảo tại thời điểm cập
  nhật gần nhất. Chi phí thực tế trong Dashboard được hệ thống tính tự động qua
  LiteLLM ngay tại thời điểm gọi model."

### Tab 5 — Lịch sử sử dụng (Usage History)
- Bảng có filter (thời gian, purpose, provider/model, user/IP, status): cột
  Thời điểm, User/IP (guest), Purpose, Provider/Model, Input/Output/Cached
  tokens, Cost, Latency, Trạng thái.
- Click 1 dòng → **Drawer/trang chi tiết request**:
  - Query (câu hỏi user, đọc từ `Message` liên kết)
  - Answer (câu trả lời, đọc từ `Message` liên kết)
  - Chunks đã dùng để trả lời (đọc từ `Message.citations` đã có sẵn — nguồn tài
    liệu, section, trang; đây là chunk được trích dẫn trong câu trả lời, không
    phải toàn bộ chunk RAG đã retrieve)
  - Model đã dùng (provider + model name thực tế tại thời điểm đó — quan trọng
    vì có thể đã failover sang model khác so với model mặc định)
  - Token breakdown (input/output/cached) và Cost ($)
  - IP (nếu request từ guest chưa đăng nhập) hoặc thông tin user
  - Request ID (để đối chiếu log kỹ thuật khi cần debug)

### `/profile` — đã có sẵn, KHÔNG cần xây mới
`unisage-web` đã có `UsageLimitCard` (`src/features/usage-limits/`), lấy dữ liệu
từ `GET /usage-limits/me` (`UsageLimitController`/`UsageLimitServiceImpl`, Java),
hiển thị 2 window `DAILY`/`WEEKLY` (entity `UsageLimit`, enum `UsageLimitWindow`
— không có `MONTHLY`). Đây là **hệ thống rate-limit/quota theo gói (plan)**,
không phải billing: `checkAndConsumeQuestion`/`consumeAnswer` đếm token bằng
**ước lượng độ dài text** câu hỏi/câu trả lời, không phải token thật do provider
trả về — khác nguồn dữ liệu với `RequestUsageLog` (token thật, dùng để tính cost
$) mà phase này xây dựng.

Vì "user xem token tuần/tháng ở profile" **đã tồn tại** (dù thiếu window
MONTHLY và dùng số ước lượng thay vì số thật), **bỏ hẳn task xây section mới** —
xem "Open Questions" bên dưới cho quyết định cần chốt về việc có nối 2 hệ thống
này lại hay để riêng.

## Task List

### Phase 1: Java — Data model & Budget API (nền tảng)
- [ ] Task 1: Entity `RequestUsageLog` + migration
- [ ] Task 2: API ghi nhận usage log (nội bộ, gọi bởi Python)
- [ ] Task 3: Entity `Budget` + `BudgetAlertLog` + migration + CRUD API cho SA
- [ ] Task 4: API tổng hợp dashboard (theo ngày/tháng/provider/model/user/purpose)

### Checkpoint: Phase 1
- [ ] `./mvnw test` pass
- [ ] Tạo thử 1 budget + insert usage log giả lập qua Postman → API dashboard trả
      đúng số liệu tổng hợp

### Phase 2: Python — Tính cost & ghi log
- [ ] Task 5: Tích hợp `litellm.completion_cost()` sau mỗi lần gọi model (CLOUD_API)
- [ ] Task 6: Tổng hợp cost nhiều node trong 1 pipeline Chat thành 1 con số, gửi
      `RequestUsageLog` về Java (async, không chặn response trả cho user)
- [ ] Task 7: Áp dụng tương tự cho Extraction và Embedding (Embedding: chỉ ghi
      log, không có multi-node tổng hợp)

### Checkpoint: Phase 2
- [ ] Gửi 1 request chat thật → 1 record `RequestUsageLog` xuất hiện đúng ở Java
      với cost/token khớp thực tế

### Phase 3: Python — Budget enforcement real-time
- [ ] Task 8: Running-total budget trong Redis, cập nhật mỗi khi ghi usage log
- [ ] Task 9: Kiểm tra budget trước khi gọi provider (fast-path, không gọi Java
      đồng bộ); scope SYSTEM hết → từ chối; scope hẹp hơn hết → theo `action`
      cấu hình (ALERT/THROTTLE/BLOCK) cho đúng scope đó
- [ ] Task 10: Định kỳ đối soát running-total Redis với Java (tránh lệch tích luỹ)

### Checkpoint: Phase 3
- [ ] Set budget SYSTEM = $0 → request Chat mới bị từ chối ngay, không gọi provider
- [ ] Set budget 1 provider cụ thể = $0, budget SYSTEM còn → request vẫn thành
      công qua provider khác (nếu Model Registry có fallback khả dụng)

### Phase 4: Cảnh báo
- [ ] Task 11: Job kiểm tra ngưỡng 50/80/100% + spike detection
- [ ] Task 12: Gửi alert qua kênh cấu hình (in-app/email/Slack), ghi `BudgetAlertLog`
- [ ] Task 13: unisage-web hiển thị in-app alert (banner/notification)

### Checkpoint: Phase 4
- [ ] Chi phí vượt 80% ngân sách → alert xuất hiện đúng kênh đã cấu hình, đúng 1
      lần (không lặp lại liên tục)

### Phase 5: unisage-web — Trang AI Cost Management
- [ ] Task 14: Tab Tổng quan (KPI + charts)
- [ ] Task 15: Tab Ngân sách & Giới hạn (CRUD budget)
- [ ] Task 16: Tab Cảnh báo (cấu hình ngưỡng/kênh + lịch sử)
- [ ] Task 17: Tab Bảng giá (bảng tĩnh, chỉ đọc)
- [ ] Task 18: Tab Lịch sử sử dụng + drawer chi tiết request

### Checkpoint: Phase 5 (checkpoint cuối — không còn Phase 6, xem "/profile" ở trên)
- [ ] Toàn bộ 5 tab render đúng dữ liệu thật từ API Phase 1/4
- [ ] Drawer chi tiết request hiển thị đúng query/answer/chunks/model/cost

### Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ acceptance criteria ở mục Success đã xác nhận đều pass
- [ ] SA xem được dashboard, set budget, nhận cảnh báo, xem chi tiết từng request
      — toàn bộ qua UI
- [ ] Ready for review

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Bảng giá FE hardcode lệch giá thật khi provider đổi giá | Low (chỉ ảnh hưởng tab tham khảo, không ảnh hưởng số liệu dashboard) | Ghi chú rõ trên UI "chỉ tham khảo"; số liệu dashboard luôn từ `RequestUsageLog` thật (Task 5, dùng `litellm.completion_cost()` trực tiếp, không phụ thuộc bảng FE) |
| Ghi `RequestUsageLog` đồng bộ làm chậm response Chat | Medium | Task 6 gửi log về Java bất đồng bộ (fire-and-forget hoặc qua queue nhẹ), không chặn response trả cho user |
| Running-total Redis lệch với tổng thật ở Java (double count, mất update do crash) | Medium — budget enforcement sai | Task 10 đối soát định kỳ; chấp nhận sai số nhỏ trong khoảng đối soát, ưu tiên availability hơn chính xác tuyệt đối cho mục đích cảnh báo (không phải hoá đơn chính thức) |
| `litellm.completion_cost()` không hỗ trợ đầy đủ mọi model provider đang dùng (model mới ra, chưa có trong bảng giá nội bộ LiteLLM) | Medium — thiếu cost cho 1 số request | Task 5 xử lý case `completion_cost()` trả None/lỗi: ghi log với `costUsd = null` + flag "không xác định được giá", hiển thị rõ trên dashboard thay vì mặc định 0 (tránh hiểu lầm miễn phí) |
| Alert spam nếu nhiều budget cùng vượt ngưỡng cùng lúc | Low | Task 12 debounce theo `BudgetAlertLog` — không gửi lại cùng 1 ngưỡng trong cùng kỳ (ngày/tháng) đã gửi rồi |

## Open Questions

- **`/profile` đã có `UsageLimitCard` (DAILY/WEEKLY, token ước lượng cho mục
  đích quota) — có nên thay số ước lượng bằng token thật từ `RequestUsageLog`,
  và/hoặc thêm window `MONTHLY`, hay để 2 hệ thống độc lập** (`UsageLimit` tiếp
  tục phục vụ rate-limit thời gian thực bằng ước lượng nhanh, không phụ thuộc
  Python phải gọi về; `RequestUsageLog`/dashboard SA phục vụ billing chính xác)?
  Đề xuất: **để riêng, không gộp** — `checkAndConsumeQuestion` cần chạy đồng bộ,
  nhanh, ngay trong Java trước khi cho phép câu hỏi tiếp theo (không thể chờ
  Python tính cost xong rồi báo về); gộp 2 nguồn sẽ làm quota check chậm hoặc
  phức tạp hoá luồng chặn request. Nếu SA/product muốn dashboard cost cũng hiển
  thị lại cho end-user, làm ở 1 task bổ sung riêng sau, đọc `RequestUsageLog`
  qua API `me` (đã có ở Task 4/19 cũ) mà không đụng vào `UsageLimit`.
- Cost có nên hiển thị bằng $ cho end-user ở đâu đó trong `/profile` (ngoài
  `UsageLimitCard` hiện có, vốn không hiển thị $) — mặc định plan này **không**
  thêm hiển thị $ cho end-user ở đợt này.
- `BALANCED`/`QUALITY_FIRST` (Phase 9 ở plan Active Switch) cần latency trung
  bình tích luỹ theo credential — phase này (Task 1) chỉ lưu `latencyMs` mỗi
  request; cần xác nhận khi bắt đầu Phase 9 xem có cần thêm 1 bảng
  materialized/aggregate riêng cho latency trung bình hay tính on-the-fly từ
  `RequestUsageLog` là đủ nhanh.
