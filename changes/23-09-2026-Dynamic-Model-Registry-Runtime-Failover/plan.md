# Implementation Plan: Dynamic Model Registry + Runtime Active Switch + Failover

## Overview

SA (Super Admin) hiện chỉ có thể đăng ký LLM model/API key qua `ChatModel` (Java,
`unisage-backend`), nhưng `unisage-agent` (Python) không hề đọc bảng này — nó dùng
`OPENAI_API_KEY`/`OPENAI_MODEL`/`OPENAI_EMBEDDING_MODEL`/`MULTI_REP_LLM_MODEL` tĩnh
từ `.env`, load một lần lúc khởi động. Khi key hết credit, cách duy nhất để khắc phục
là sửa `.env` và restart. Các field `priority`/`errorCount`/`lastErrorAt` trên
`ChatModel` đã tồn tại trong schema nhưng chưa được ghi hay đọc ở bất kỳ đâu.

Feature này nối hai phía lại: mở rộng `ChatModel` để phân biệt 3 vai trò (CHAT /
EMBEDDING / EXTRACTION), cho Python đọc registry này qua LiteLLM SDK, đồng bộ
runtime qua Redis (đã có sẵn cho Celery) để 4 worker process của gunicorn thấy config
mới mà không cần restart, và triển khai auto-failover **chỉ cho Chat và Extraction**
(Embedding cố tình không auto-failover — xem Architecture Decisions). Sự cố cần SA
can thiệp (hết credit, key thu hồi, hết fallback) bắn cảnh báo qua Slack Incoming
Webhook.

Phạm vi trải trên 3 repo: `unisage-backend` (Java, control plane), `unisage-agent`
(Python, data plane), `unisage-web` (trang admin). Vì 3 repo là 3 codebase độc lập
(không phải monorepo với build chung), các task được nhóm theo repo/dependency thay
vì vertical slice chặt như 1 app đơn — nhưng thứ tự triển khai vẫn đi theo vertical
slice ở cấp tính năng: "Chat dùng được registry" xong trước rồi mới thêm
"Chat tự failover", chứ không làm xong toàn bộ Java rồi mới đụng tới Python.

## Architecture Decisions

- **LiteLLM SDK nhúng trực tiếp vào Python, không dùng LiteLLM Proxy riêng.** Giữ
  request path `Python → Provider` thay vì thêm `Python → Proxy → Provider`, vì
  Java đã là control plane duy nhất và Chat+Ingest đều nằm trong cùng 1 service
  Python — thêm Proxy là thêm 1 service vận hành không cần thiết ở giai đoạn này.
- **Embedding không auto-failover, không auto-switch model.** Đổi embedding model
  (kể cả cùng số chiều vector) làm vector nằm ở không gian ngữ nghĩa khác, khiến
  cosine similarity giữa query mới và document cũ vô nghĩa — retrieval degrade âm
  thầm, khó phát hiện hơn cả lỗi 500. Vì vậy Embedding tại một thời điểm chỉ có
  đúng 1 credential active; lỗi thì dừng ingest job và báo SA, không tự chuyển.
  Đổi hẳn embedding model là thao tác migration có chủ đích (collection mới +
  re-embed), nằm ngoài scope feature này.
- **Redis pub/sub cho hot-reload, không phải nguồn dữ liệu cấu hình.** Java/DB vẫn
  là nguồn sự thật; Redis chỉ phát tín hiệu "có bản config mới, version N". Mỗi
  Python worker định kỳ so version để tự đồng bộ lại nếu bỏ lỡ event — tránh 1
  worker bị lệch config vĩnh viễn nếu message pub/sub bị rớt.
- **Circuit breaker ở cấp credential/deployment, không phải cấp model.** Hai API
  key cùng 1 tổ chức có thể cùng chịu rate limit; disable đúng credential lỗi và
  thử credential/provider khác, không khoá toàn bộ model.
- **Verify trước khi Active.** Khi SA thêm credential mới, trạng thái ban đầu là
  `PENDING`; Python phải xác minh bằng 1 request nhỏ thành công thì Java mới cho
  chuyển `ACTIVE`. Việc này cần một cơ chế Java gọi Python (hoặc Python poll job
  PENDING) — xem Task 6.
- Việc này đủ "cross-cutting và tốn kém để đảo ngược" (đổi cách toàn bộ hệ thống
  gọi LLM, thêm dependency LiteLLM, đổi cơ chế đồng bộ config liên service) nên
  **cần 1 ADR** ở `unisage-backend/docs/adr/000X-dynamic-model-registry.md` trước
  khi implement Phase 1 — xem Task 1.
- **Routing Policy (Phase 9, làm sau cùng) phụ thuộc dữ liệu cost/latency từ phase
  Cost Tracking riêng** (`changes/<ngày>-Cost-Tracking-Budget-Management/`).
  `LOWEST_COST` cần cost thực tế đã ghi nhận theo credential, `QUALITY_FIRST` cần
  latency tích luỹ — cả hai không đáng tin nếu implement trước khi Cost Tracking
  chạy ổn định. Vì vậy Phase 9 nằm cuối plan này về mặt thứ tự triển khai, dù được
  liệt kê ở đây để giữ toàn bộ vòng đời "chọn model" trong 1 tài liệu duy nhất.

## Task List

### Phase 0: Quyết định & ADR
- [ ] Task 0: Viết ADR cho Dynamic Model Registry

### Phase 1: Java — Mở rộng Model Registry (nền tảng, chặn mọi việc sau)
- [ ] Task 1: Thêm `modelPurpose` (CHAT/EMBEDDING/EXTRACTION) và `status`
      (PENDING/ACTIVE/INACTIVE/DISABLED) vào `ChatModel` + migration
- [ ] Task 2: API cập nhật health state (`errorCount`/`lastErrorAt`/`status`) —
      nội bộ, gọi bởi Python
- [ ] Task 3: API kích hoạt/vô hiệu hoá + đổi priority cho SA

### Checkpoint: Phase 1
- [ ] `./mvnw test` pass
- [ ] `GET /chat-models?modelPurpose=CHAT&status=ACTIVE` trả đúng danh sách theo priority
- [ ] Review với human trước khi đụng Python

### Phase 2: Python — Đọc registry thay vì .env (vertical slice đầu tiên chạy được)
- [ ] Task 4: Client gọi Java `/chat-models` + cache snapshot trong bộ nhớ
- [ ] Task 5: Tích hợp LiteLLM SDK, thay `pydantic-ai` model instantiation trong
      `get_graph_models()` bằng snapshot (chỉ Chat, chưa failover)
- [ ] Task 6: Verify-before-active — Python xác minh credential PENDING

### Checkpoint: Phase 2
- [ ] SA tạo 1 ChatModel mới trong Java → Python thực sự dùng nó cho 1 lần chat
      (kiểm thủ công qua restart Python 1 lần — hot reload chưa có, chấp nhận ở
      checkpoint này)
- [ ] Tests pass ở cả 2 repo

### Phase 3: Python — Hot reload không cần restart
- [ ] Task 7: Redis pub/sub phát `config_version` khi Java đổi registry
- [ ] Task 8: Python worker subscribe + poll định kỳ, atomic swap snapshot

### Checkpoint: Phase 3
- [ ] SA đổi model active trong Java → trong vòng vài giây, request chat mới
      (không restart Python) dùng model mới; request đang chạy dùng config cũ
- [ ] Test với ít nhất 2 worker process (gunicorn `-w 2` cục bộ) xác nhận cả 2
      đều thấy config mới

### Phase 4: Python — Failover cho Chat & Extraction
- [ ] Task 9: Phân loại lỗi provider (permanent: hết credit/key revoked vs
      transient: 429/5xx) dựa trên exception typed của LiteLLM
- [ ] Task 10: Circuit breaker + cooldown theo credential, chọn fallback theo
      priority khi credential hiện tại bị loại
- [ ] Task 11: Rule streaming — chỉ fallback trước chunk đầu tiên; lỗi giữa
      stream thì báo ngắt cho client, không đổi model giữa response
- [ ] Task 12: Áp dụng circuit breaker + failover cho Extraction (multi-
      representation)

### Checkpoint: Phase 4
- [ ] Test giả lập: credential Chat chính trả lỗi hết credit → request mới tự
      chuyển sang credential dự phòng, request cũ không bị ảnh hưởng
- [ ] Test giả lập: lỗi 429 có `Retry-After` → cooldown đúng thời gian, không bị
      loại khỏi routing vĩnh viễn
- [ ] Test streaming: lỗi xảy ra sau chunk đầu tiên → client nhận thông báo ngắt,
      không nhận nội dung trộn từ 2 model

### Phase 5: Python — Embedding (không failover)
- [ ] Task 13: Embedding chỉ đọc đúng 1 credential active; lỗi thì raise và dừng
      ingest job đó (Celery task fail rõ ràng, không retry sang credential khác)

### Checkpoint: Phase 5
- [ ] Test: vô hiệu hoá credential embedding đang active → ingest job mới fail
      với lỗi rõ ràng, không tự đổi model

### Phase 6: Slack alert
- [ ] Task 14: Slack Incoming Webhook client trong Python
- [ ] Task 15: Bắn alert khi: hết credit, key bị thu hồi, hoặc hết sạch fallback
      cho 1 loại model — không bắn cho lỗi transient tự phục hồi

### Checkpoint: Phase 6
- [ ] Test giả lập lỗi permanent → có 1 message xuất hiện đúng channel Slack
- [ ] Test giả lập lỗi transient (429 có cooldown, tự phục hồi) → không có message nào

### Phase 7: Bảo mật Custom URL
- [ ] Task 16: SSRF guard khi SA nhập `apiBaseUrl` tuỳ ý (chặn localhost/private
      IP/link-local) — chạy ở cả bước Java validate và bước Python verify-before-active

### Phase 8: unisage-web — Trang quản lý
- [ ] Task 17: Trang admin: danh sách model theo purpose, thêm/sửa credential,
      xem status/errorCount/lastErrorAt, nút activate/deactivate, kéo priority

### Phase 9: Routing Policy nâng cao (làm sau Cost Tracking, xem Open Questions)
- [ ] Task 18: Thêm `routingPolicy` cấp modelPurpose (LOWEST_COST/BALANCED/
      PRIORITY/QUALITY_FIRST) + điều kiện bắt buộc (model, độ trễ tối đa, ngân
      sách) trong Java
- [ ] Task 19: Python áp policy khi chọn credential trong `model_router`
      (Task 10/Phase 4), thay vì luôn chọn theo `priority` tĩnh
- [ ] Task 20: Khi ngân sách toàn hệ thống hết (đọc từ Cost Tracking phase) →
      router từ chối request thay vì fallback sang provider khác tiếp tục phát
      sinh chi phí

### Checkpoint: Phase 9
- [ ] SA chọn `LOWEST_COST` cho purpose CHAT → router ưu tiên credential có cost/
      1K token thấp nhất trong số các credential ACTIVE thoả điều kiện bắt buộc
- [ ] SA chọn `QUALITY_FIRST` → router ưu tiên credential có latency trung bình
      thấp nhất/model mạnh nhất theo cấu hình, bỏ qua cost
- [ ] Test giả lập: ngân sách hệ thống = 0 → request bị từ chối với lỗi rõ ràng,
      không có lệnh gọi provider nào được thực hiện (không phát sinh chi phí)

### Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ acceptance criteria ở mục Success trong intent đã xác nhận đều pass
- [ ] SA có thể tự thêm key mới, xem nó chuyển PENDING → ACTIVE, và thấy Chat
      dùng key mới — toàn bộ qua UI, không cần dev can thiệp
- [ ] Ready for review

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| LiteLLM error taxonomy không khớp 100% với phân loại permanent/transient mong muốn (vd OpenAI trả cùng 1 code cho 2 tình huống khác nhau) | High — failover sai hướng, hoặc alert spam | Task 9 kiểm tra kỹ `error.code`/`type` thực tế của từng provider đang dùng, không chỉ dựa HTTP status; viết test với response giả lập cho từng case |
| 4 worker gunicorn đọc snapshot lệch nhau nếu bỏ lỡ Redis pub/sub message | Medium — 1 worker dùng key đã hết credit lâu hơn cần thiết | Task 8 bắt buộc poll định kỳ version, không chỉ dựa vào pub/sub |
| SSRF qua Custom Base URL | High (bảo mật) | Task 16 chặn ở cả Java (khi lưu) và Python (khi verify-before-active gọi thử) — 2 lớp |
| Đổi cách gọi LLM ảnh hưởng toàn bộ luồng Chat đang chạy production | High | Triển khai theo checkpoint tăng dần (Phase 2 chưa có failover vẫn phải chạy ổn định trước khi làm Phase 4); giữ khả năng rollback về đọc `.env` bằng feature flag tạm thời trong lúc rollout |
| Slack webhook URL là secret nhưng dễ bị commit nhầm | Medium | Lưu trong biến môi trường/registry mã hoá như API key, không hardcode, thêm vào `.env.example` chỉ dưới dạng placeholder |

## Open Questions

- Java gọi Python để verify credential PENDING (webhook/callback), hay Python tự
  poll các bản ghi PENDING định kỳ? Task 6 cần chốt cơ chế cụ thể khi bắt đầu code
  (ảnh hưởng: có cần thêm 1 endpoint mới ở Python để Java gọi hay không).
- `ChatModelServiceImpl.validateBySourceType` hiện validate theo `sourceType`
  (CLOUD_API/SELF_HOSTED) — cần xác nhận field `modelPurpose` mới (Task 1) có kéo
  theo rule validate riêng nào không (vd Embedding bắt buộc CLOUD_API?) khi bắt
  tay viết Task 1.
