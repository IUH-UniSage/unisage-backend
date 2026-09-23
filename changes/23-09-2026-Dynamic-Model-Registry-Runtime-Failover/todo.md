# Todo: Dynamic Model Registry + Runtime Active Switch + Failover

Xem `plan.md` trong cùng thư mục để biết bối cảnh, architecture decisions và risk.
MVP bắt buộc = Phase 0-6. Phase 7 (SSRF) bắt buộc trước khi cho SA nhập Custom URL
ở production, không được coi là "làm sau". Phase 8 (UI) có thể làm song song với
Phase 4-6 sau khi API ở Phase 1/3 đã ổn định.

---

## Phase 0: Quyết định & ADR

### Task 0: Viết ADR cho Dynamic Model Registry

**Description:** Ghi lại quyết định kiến trúc trước khi code: LiteLLM SDK nhúng
(không Proxy riêng), Embedding không auto-failover, Redis chỉ dùng để phát tín
hiệu chứ không phải nguồn cấu hình, circuit breaker ở cấp credential. Theo
CLAUDE.md của unisage-backend, đây là thay đổi cross-cutting và tốn kém để đảo
ngược nên cần ADR trước khi implement.

**Acceptance criteria:**
- [ ] File mới `unisage-backend/docs/adr/000X-dynamic-model-registry.md` (copy từ
      `0000-template.md`, đánh số tiếp theo số ADR lớn nhất hiện có)
- [ ] Nêu rõ 2 phương án đã cân nhắc (LiteLLM SDK vs LiteLLM Proxy) và lý do chọn
- [ ] Nêu rõ constraint "Embedding không auto-failover" và lý do (vector space)

**Verification:**
- [ ] Manual check: một người chưa tham gia thảo luận đọc ADR hiểu được "tại sao"
      chỉ bằng đọc file, không cần hỏi lại

**Dependencies:** None

**Files likely touched:**
- `unisage-backend/docs/adr/000X-dynamic-model-registry.md`

**Estimated scope:** XS (1 file)

---

## Phase 1: Java — Mở rộng Model Registry

### Task 1: Thêm `modelPurpose` và `status` vào `ChatModel` + migration

**Description:** `ChatModel` hiện không phân biệt được model dùng cho Chat,
Embedding, hay Extraction (3 config riêng đang nằm rải rác trong `.env` của
Python). Thêm enum `ModelPurpose` (CHAT/EMBEDDING/EXTRACTION) và enum
`ModelStatus` (PENDING/ACTIVE/INACTIVE/DISABLED) làm field mới, cùng migration
Flyway tương ứng (schema hiện ở `V9__...`, file mới là `V10__...`).
`DISABLED` dùng cho case key bị thu hồi/hết credit vĩnh viễn (khác `INACTIVE` là
SA chủ động tắt).

**Acceptance criteria:**
- [ ] Enum `ChatModelPurpose` mới ở `entity/enums/`, enum `ChatModelStatus` mới
      (hoặc field `status` kiểu String nếu đơn giản hơn — theo convention
      `sourceType` hiện có là enum, nên dùng enum cho nhất quán)
- [ ] `ChatModel` có thêm field `modelPurpose` (not-null, default không áp dụng —
      bắt buộc chọn khi tạo) và `status` (default `PENDING`)
- [ ] Migration `V10__add_chat_model_purpose_and_status.sql` thêm cột, có giá trị
      mặc định hợp lý cho các row hiện có (vd `modelPurpose = 'CHAT'` cho toàn bộ
      row cũ, `status = 'ACTIVE'` để không phá luồng hiện tại)
- [ ] `ChatModelServiceImpl.validateBySourceType` (hoặc tương đương) cập nhật nếu
      `modelPurpose` kéo theo rule validate riêng (xem Open Questions trong plan.md)

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Build succeeds: `./mvnw clean package -DskipTests`
- [ ] Manual check: chạy migration trên DB dev, xác nhận row cũ vẫn `isActive`/
      dùng được như trước khi migration

**Dependencies:** None

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/entity/ChatModel.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/enums/ChatModelPurpose.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/enums/ChatModelStatus.java`
- `unisage-backend/src/main/resources/db/migration/V10__add_chat_model_purpose_and_status.sql`

**Estimated scope:** S (3-4 files)

---

### Task 2: API cập nhật health state — nội bộ, gọi bởi Python

**Description:** Python cần báo lại cho Java khi một credential lỗi (tăng
`errorCount`, set `lastErrorAt`), và khi lỗi permanent thì chuyển `status` sang
`DISABLED`. Thêm endpoint nội bộ (không phải SA-facing) để Python gọi, tách biệt
khỏi CRUD công khai của `ChatModelController`.

**Acceptance criteria:**
- [ ] Endpoint mới, ví dụ `PATCH /chat-models/{id}/health` nhận
      `{ errorType: TRANSIENT|PERMANENT, message }`, tăng `errorCount`, set
      `lastErrorAt`, và nếu `PERMANENT` thì set `status = DISABLED`
- [ ] Endpoint này không nằm trong `PredefinedPublicPaths` (không public), nhưng
      cũng không cần permission SA — xác thực bằng cơ chế nội bộ giữa service
      (vd secret header có sẵn `X-Internal-Secret` giữa Gateway↔Python, hoặc
      tương đương) — quyết định cụ thể theo hạ tầng internal-call hiện có, không
      tạo cơ chế auth mới riêng cho việc này
- [ ] `PERMANENT` health update trigger được ghi nhận để Task 15 (Slack alert)
      có thể lấy dữ liệu

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check: gọi endpoint bằng Postman collection (`docs/postman/`), xác
      nhận `errorCount` tăng và `status` đổi đúng theo `errorType`

**Dependencies:** Task 1

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/ChatModelController.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/request/ChatModelHealthUpdateRequest.java`

**Estimated scope:** S (3 files)

---

### Task 3: API kích hoạt/vô hiệu hoá + đổi priority cho SA

**Description:** SA-facing: đổi `status` (kích hoạt sau khi PENDING đã verify
xong ở Task 6, tắt thủ công), và đổi `priority` để sắp lại thứ tự fallback. Đây
là phần API mà trang admin ở Task 17 sẽ gọi.

**Acceptance criteria:**
- [ ] `PATCH /chat-models/{id}/status` — chỉ cho phép chuyển `PENDING → ACTIVE`
      (chặn SA tự set ACTIVE khi còn PENDING chưa verify — trả lỗi rõ ràng nếu cố
      làm vậy) và `ACTIVE ⇄ INACTIVE`
- [ ] `PATCH /chat-models/{id}/priority` cập nhật priority
- [ ] `GET /chat-models?modelPurpose=X&status=ACTIVE` hỗ trợ filter theo 2 field
      mới, sắp xếp theo `priority` — đây là API mà Python (Task 4) sẽ gọi

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check: filter + sort trả đúng thứ tự priority qua Postman

**Dependencies:** Task 1

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/ChatModelController.java`
- `unisage-backend/src/main/java/com/unisage/backend/repository/ChatModelRepository.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`

**Estimated scope:** M (3-4 files)

---

## Checkpoint: Phase 1
- [ ] `./mvnw test` pass toàn bộ
- [ ] `GET /chat-models?modelPurpose=CHAT&status=ACTIVE` trả đúng danh sách theo priority
- [ ] Review với human trước khi đụng Python

---

## Phase 2: Python — Đọc registry thay vì .env

### Task 4: Client gọi Java `/chat-models` + cache snapshot trong bộ nhớ

**Description:** Thêm HTTP client trong Python gọi
`GET /chat-models?modelPurpose=X&status=ACTIVE` của Java, build thành 1 snapshot
in-memory (danh sách credential theo priority, cho từng purpose). Đây là bước nền
cho fast-path — request Chat/Ingest bình thường **không** gọi Java trực tiếp, chỉ
đọc snapshot này.

**Acceptance criteria:**
- [ ] Module mới (vd `app/core/model_registry.py`) load snapshot 1 lần lúc
      startup từ Java, giữ trong biến module-level tương tự `Settings` hiện tại
- [ ] Snapshot bao gồm cả 3 purpose: CHAT, EMBEDDING, EXTRACTION
- [ ] API key giải mã đúng cách (Java trả về đã giải mã qua kênh nội bộ, hoặc
      Python nhận encrypted và không tự giải mã — quyết định theo: **Java giữ
      quyền giải mã, chỉ trả plaintext key qua kênh nội bộ tin cậy**, Python
      không lưu trữ key lâu dài ngoài snapshot in-memory)
- [ ] Startup fail rõ ràng (không im lặng fallback về `.env`) nếu Java không trả
      được ít nhất 1 credential ACTIVE cho CHAT — tránh chạy "âm thầm không có
      model nào"

**Verification:**
- [ ] Tests pass: pytest cho `model_registry.py` với Java response giả lập (mock)
- [ ] Manual check: start Python với Java đã có 1 ChatModel ACTIVE, log xác nhận
      snapshot load đúng thông tin

**Dependencies:** Task 3

**Files likely touched:**
- `unisage-agent/app/core/model_registry.py`
- `unisage-agent/app/integrations/backend_java_client.py`
- `unisage-agent/tests/core/test_model_registry.py`

**Estimated scope:** M (3 files)

---

### Task 5: Tích hợp LiteLLM SDK trong `get_graph_models()`

**Description:** Thay việc build `OpenAIChatModel`/`OpenAIProvider` trực tiếp
trong `app/api/deps.py::get_graph_models()` bằng model đến từ snapshot (Task 4),
gọi qua LiteLLM SDK thay vì `pydantic-ai`'s `OpenAIProvider`. Ở task này **chưa
có failover** — chỉ dùng credential priority cao nhất, mục tiêu là chứng minh
đường đi từ registry Java đến 1 lần gọi LLM thật hoạt động.

**Acceptance criteria:**
- [ ] `litellm` thêm vào `pyproject.toml`
- [ ] `get_graph_models()` build model từ snapshot CHAT purpose (credential
      priority cao nhất) thay vì `settings.OPENAI_API_KEY`/`settings.OPENAI_MODEL`
- [ ] 4 node hiện dùng chung 1 model (`classification`, `direct_llm`,
      `query_transformation`, `generation`) vẫn hoạt động y hệt hành vi cũ khi
      chỉ có 1 credential trong registry — không có regression
- [ ] `settings.OPENAI_API_KEY`/`OPENAI_MODEL` trong `config.py` được đánh dấu
      deprecated (giữ lại cho tới khi migration hoàn tất toàn bộ, xoá ở 1 task
      dọn dẹp riêng sau này, không xoá ở task này)

**Verification:**
- [ ] Tests pass: `pytest tests/graph/` và `tests/api/`
- [ ] Manual check: chạy 1 request chat thật với 1 ChatModel ACTIVE trong Java,
      xác nhận response bình thường

**Dependencies:** Task 4

**Files likely touched:**
- `unisage-agent/app/api/deps.py`
- `unisage-agent/pyproject.toml`
- `unisage-agent/tests/api/test_deps.py`

**Estimated scope:** M (3-4 files)

---

### Task 6: Verify-before-active — Python xác minh credential PENDING

**Description:** Khi SA tạo ChatModel mới (`status = PENDING` theo Task 1),
không được để nó vào snapshot cho tới khi verify thành công. Chốt cơ chế cụ thể
(xem Open Question trong plan.md) — khuyến nghị: Python expose 1 endpoint nội bộ
`POST /internal/model-registry/verify/{chatModelId}` mà Java gọi ngay sau khi tạo
record PENDING; Python thực hiện 1 request nhỏ (vd list models hoặc 1 completion
rất ngắn) qua LiteLLM, trả kết quả; Java nhận kết quả và tự chuyển `status` sang
`ACTIVE` hoặc giữ `PENDING`/báo lỗi cho SA.

**Acceptance criteria:**
- [ ] Endpoint mới ở Python nhận `chatModelId`, tự lấy thông tin credential từ
      Java (hoặc Java gửi kèm trong request — tránh double round-trip), thử gọi
      thật 1 request nhỏ qua LiteLLM
- [ ] Kết quả trả về Java gồm: `success: bool`, `errorType` nếu fail (dùng chung
      taxonomy TRANSIENT/PERMANENT với Task 2)
- [ ] Java cập nhật `status` dựa trên kết quả (nối vào API Task 3)
- [ ] Timeout hợp lý cho bước verify (không để SA chờ vô hạn nếu endpoint mới
      không phản hồi) — connect timeout ngắn, không retry nhiều lần

**Verification:**
- [ ] Tests pass ở cả Java và Python cho case verify thành công / thất bại
- [ ] Manual check: nhập 1 API key sai qua Postman → `status` không chuyển
      `ACTIVE`, SA thấy lý do lỗi

**Dependencies:** Task 5 (Java: Task 3)

**Files likely touched:**
- `unisage-agent/app/api/routes/internal_model_registry.py`
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/integration/AgentVerificationClient.java`

**Estimated scope:** L (5+ files, xem xét tách thành 2 task nếu bắt đầu code mà thấy quá lớn — 1 task cho endpoint Python, 1 task cho phần Java gọi + xử lý kết quả)

---

## Checkpoint: Phase 2
- [ ] SA tạo 1 ChatModel mới trong Java → verify chạy tự động → chuyển ACTIVE →
      Python thực sự dùng nó cho 1 lần chat (chấp nhận cần restart Python thủ
      công 1 lần ở checkpoint này, hot reload chưa làm tới)
- [ ] Tests pass ở cả 2 repo
- [ ] Review với human trước khi làm Phase 3

---

## Phase 3: Python — Hot reload không cần restart

### Task 7: Redis pub/sub phát `config_version` khi Java đổi registry

**Description:** Mỗi khi Java thay đổi `ChatModel` theo hướng ảnh hưởng routing
(status, priority, credential), tăng 1 `config_version` (global hoặc theo
purpose) và publish message qua Redis (đã chạy sẵn cho Celery — dùng cùng Redis
instance, channel riêng, không đụng channel ingestion hiện có).

**Acceptance criteria:**
- [ ] Java publish message `{ purpose, version }` lên Redis channel
      `model-registry:updates` sau mỗi thay đổi ở Task 2/3/6
- [ ] Version là số tăng đơn điệu, lưu cùng bảng hoặc bảng riêng
      (`model_registry_version`), không chỉ tính từ `updatedAt` (tránh race
      condition 2 update cùng timestamp)

**Verification:**
- [ ] Tests pass: `./mvnw test` với Redis test container/embedded
- [ ] Manual check: đổi priority 1 model → thấy message xuất hiện qua
      `redis-cli SUBSCRIBE model-registry:updates`

**Dependencies:** Task 3

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/config/RedisConfig.java`

**Estimated scope:** S (2 files)

---

### Task 8: Python worker subscribe + poll định kỳ, atomic swap snapshot

**Description:** Mỗi gunicorn worker chạy 1 background subscriber cho
`model-registry:updates`; khi nhận version mới, gọi lại Java lấy snapshot mới,
build router mới, rồi atomic swap con trỏ đang dùng (không sửa snapshot đang
chạy tại chỗ — tránh race giữa request đang xử lý và việc reload). Thêm poll
định kỳ (vd mỗi 60s) so version với Java để tự đồng bộ nếu bỏ lỡ pub/sub message.

**Acceptance criteria:**
- [ ] Background task (thread/asyncio task riêng trong mỗi worker) subscribe
      Redis, không chặn event loop chính xử lý request
- [ ] Swap snapshot là atomic (gán lại 1 reference, không mutate list/dict đang
      dùng) — request đang chạy hoàn thành bằng snapshot cũ, request mới dùng
      snapshot mới
- [ ] Poll định kỳ độc lập với pub/sub, log rõ khi phát hiện version lệch do bỏ
      lỡ message

**Verification:**
- [ ] Tests pass: pytest với Redis giả lập, xác nhận swap không làm mất request
      đang chạy
- [ ] Manual check: chạy `gunicorn -w 2` cục bộ, đổi model active trong Java,
      xác nhận cả 2 worker đều nhận version mới trong vài giây

**Dependencies:** Task 7, Task 4

**Files likely touched:**
- `unisage-agent/app/core/model_registry.py`
- `unisage-agent/app/worker/registry_subscriber.py`

**Estimated scope:** M (2-3 files)

---

## Checkpoint: Phase 3
- [ ] SA đổi model active trong Java → trong vòng vài giây, request chat mới
      dùng model mới; request đang chạy dùng config cũ
- [ ] Xác nhận với ≥2 worker process, cả 2 đều đồng bộ đúng

---

## Phase 4: Python — Failover cho Chat & Extraction

### Task 9: Phân loại lỗi provider permanent vs transient

**Description:** Dựa trên exception typed của LiteLLM (`RateLimitError`,
`AuthenticationError`, `BadRequestError`, v.v. — LiteLLM đã map các exception
này theo chuẩn OpenAI-style qua nhiều provider), viết logic phân loại: hết
credit/key revoked → PERMANENT; 429 có `Retry-After`/5xx thoáng qua → TRANSIENT.
Đây là input cho cả Task 10 (failover) và Task 2 (health report về Java) và Task
15 (Slack alert).

**Acceptance criteria:**
- [ ] Hàm `classify_llm_error(exc) -> ErrorType` xử lý ít nhất: OpenAI
      `AuthenticationError`, `RateLimitError` (check thêm message/`error.code`
      chứ không chỉ HTTP status vì cùng 429 có thể là 2 tình huống khác nhau),
      `APIConnectionError`, provider 5xx
- [ ] Test case cho từng loại exception giả lập, xác nhận map đúng
      PERMANENT/TRANSIENT
- [ ] Case không nhận diện được → mặc định TRANSIENT (an toàn hơn là tắt nhầm 1
      credential còn tốt)

**Verification:**
- [ ] Tests pass: pytest với exception giả lập cho từng case

**Dependencies:** Task 5

**Files likely touched:**
- `unisage-agent/app/core/llm_error_classifier.py`
- `unisage-agent/tests/core/test_llm_error_classifier.py`

**Estimated scope:** S (2 files)

---

### Task 10: Circuit breaker + cooldown theo credential, fallback theo priority

**Description:** Khi 1 credential bị lỗi TRANSIENT, đặt cooldown (dùng
`Retry-After` nếu có, mặc định backoff ngắn nếu không) và chọn credential
priority kế tiếp cho request hiện tại + các request tiếp theo trong lúc cooldown.
Khi PERMANENT, loại khỏi routing ngay và gọi Task 2 (health API) để Java set
`DISABLED`.

**Acceptance criteria:**
- [ ] State cooldown lưu theo credential id, chia sẻ giữa các worker qua Redis
      (không lưu riêng từng worker — tránh worker A đã cooldown còn worker B vẫn
      gọi credential đang lỗi)
- [ ] Chọn credential tiếp theo theo `priority` trong snapshot, bỏ qua credential
      đang cooldown hoặc DISABLED
- [ ] Nếu hết sạch credential khả dụng cho purpose đó → raise lỗi rõ ràng lên
      trên (không loop vô hạn), và đây là điều kiện trigger Slack alert (Task 15)

**Verification:**
- [ ] Tests pass: pytest giả lập chuỗi lỗi TRANSIENT → xác nhận chuyển credential
      đúng thứ tự priority, và tự phục hồi sau cooldown
- [ ] Tests pass: giả lập PERMANENT → xác nhận gọi Task 2 health API và loại
      khỏi routing ngay lập tức, không đợi hết cooldown

**Dependencies:** Task 9, Task 8

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/tests/core/test_model_router.py`

**Estimated scope:** M (2 files, logic phức tạp — cân nhắc tách circuit breaker
state ra khỏi router nếu code vượt quá ~300 dòng)

---

### Task 11: Rule streaming — fallback chỉ trước chunk đầu tiên

**Description:** Áp rule đã chốt trong intent: tự động fallback chỉ được phép
xảy ra trước khi gửi chunk đầu tiên cho client. Nếu lỗi xảy ra giữa stream, phải
gửi 1 sự kiện lỗi/ngắt rõ ràng cho client (không nối tiếp bằng model khác).

**Acceptance criteria:**
- [ ] Điểm gọi model trong streaming path (graph nodes dùng `generation`) bọc
      logic: lỗi trước chunk đầu tiên → dùng `model_router` (Task 10) để retry
      với credential khác; lỗi sau chunk đầu tiên → propagate 1 error event rõ
      ràng qua stream, kết thúc response tại đó
- [ ] Không có test/case nào tạo ra response bị "trộn" nội dung từ 2 model khác
      nhau trong cùng 1 lần trả lời

**Verification:**
- [ ] Tests pass: pytest giả lập lỗi xảy ra ở chunk thứ N (N>1) → xác nhận stream
      kết thúc bằng error event, không có chunk nào sau đó
- [ ] Tests pass: giả lập lỗi trước chunk đầu tiên → xác nhận fallback xảy ra và
      client vẫn nhận được response bình thường

**Dependencies:** Task 10

**Files likely touched:**
- `unisage-agent/app/graph/nodes/generation_synthesis.py`
- `unisage-agent/app/graph/streaming_state.py`
- `unisage-agent/tests/graph/test_generation_synthesis.py`

**Estimated scope:** M (2-3 files)

---

### Task 12: Circuit breaker + failover cho Extraction

**Description:** Áp cùng cơ chế Task 10 cho luồng Extraction/multi-representation
(`app/rag/enrichment/multi_representation.py`, hiện dùng raw `openai.OpenAI()`
trực tiếp — cần chuyển qua LiteLLM + `model_router` như Chat).

**Acceptance criteria:**
- [ ] `multi_representation.py` không còn gọi `openai.OpenAI()` trực tiếp, đi
      qua `model_router` với purpose EXTRACTION
- [ ] Failover hoạt động tương tự Chat (dùng lại `model_router`, không viết logic
      circuit breaker riêng lần 2)
- [ ] Note: nếu Extraction dùng model OCR/VLM khác định dạng output giữa các
      provider (như bản đề xuất gốc có cảnh báo), thêm 1 bước validate format
      output sau khi fallback — nếu không validate được, coi như lỗi PERMANENT
      cho credential đó thay vì trả kết quả sai định dạng cho luồng ingest

**Verification:**
- [ ] Tests pass: pytest cho `multi_representation.py` với credential giả lập
      lỗi, xác nhận failover và validate output

**Dependencies:** Task 10

**Files likely touched:**
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/tests/rag/test_multi_representation.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 4
- [ ] Test giả lập: credential Chat chính trả lỗi hết credit → request mới tự
      chuyển sang credential dự phòng, request cũ không bị ảnh hưởng
- [ ] Test giả lập: lỗi 429 có `Retry-After` → cooldown đúng thời gian, không bị
      loại khỏi routing vĩnh viễn
- [ ] Test streaming: lỗi sau chunk đầu tiên → client nhận thông báo ngắt rõ ràng
- [ ] Review với human trước khi làm Phase 5-6

---

## Phase 5: Python — Embedding (không failover)

### Task 13: Embedding chỉ đọc đúng 1 credential active, lỗi thì dừng

**Description:** Khác hẳn Chat/Extraction — Embedding **không** đi qua
`model_router`/circuit breaker. Snapshot Embedding (từ Task 4) chỉ có đúng 1
credential active tại một thời điểm (đã đảm bảo ở Java vì mỗi purpose EMBEDDING
chỉ nên có 1 row ACTIVE — cân nhắc constraint này ở Task 1/3 nếu chưa có). Lỗi
thì Celery task fail rõ ràng, không thử credential/model khác.

**Acceptance criteria:**
- [ ] `openai_embedder.py` dùng credential EMBEDDING active duy nhất từ snapshot
      qua LiteLLM, không còn đọc `settings.OPENAI_EMBEDDING_MODEL` trực tiếp
- [ ] Lỗi khi gọi embedding → raise exception rõ ràng, Celery task fail (không
      catch rồi thử lại với credential khác), gọi Task 2 health API để Java biết
- [ ] Nếu Java có >1 row ACTIVE cho EMBEDDING (vi phạm invariant) → Python log
      warning và dùng row priority cao nhất, không crash

**Verification:**
- [ ] Tests pass: pytest cho `openai_embedder.py` với credential lỗi giả lập →
      xác nhận job fail, không có lần gọi thứ 2 với credential khác

**Dependencies:** Task 4

**Files likely touched:**
- `unisage-agent/app/rag/embeddings/openai_embedder.py`
- `unisage-agent/tests/rag/test_openai_embedder.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 5
- [ ] Vô hiệu hoá credential embedding đang active → ingest job mới fail với lỗi
      rõ ràng, không tự đổi model

---

## Phase 6: Slack alert

### Task 14: Slack Incoming Webhook client trong Python

**Description:** Client gửi message tới Slack Incoming Webhook URL (secret, lưu
trong env/registry mã hoá — xem Risk trong plan.md). Chưa có tích hợp Slack nào
trong repo — dựng mới từ đầu.

**Acceptance criteria:**
- [ ] Module `app/integrations/slack_notifier.py` gửi POST tới webhook URL với
      message text đơn giản (không cần block kit phức tạp cho MVP)
- [ ] Webhook URL đọc từ env var mới (`SLACK_APIKEY_ALERT_WEBHOOK_URL` hoặc tương
      tự), thêm placeholder vào `.env.example`
- [ ] Lỗi gọi Slack (network fail) không được làm crash luồng chính (chat/ingest
      vẫn tiếp tục dù Slack down) — log lỗi, không raise

**Verification:**
- [ ] Tests pass: pytest với `httpx`/`requests` mock cho webhook call
- [ ] Manual check: tạo 1 Slack webhook test, gửi thử 1 message thấy xuất hiện
      đúng channel

**Dependencies:** None (độc lập, có thể làm song song Phase 3-5)

**Files likely touched:**
- `unisage-agent/app/integrations/slack_notifier.py`
- `unisage-agent/app/core/config.py`
- `unisage-agent/.env.example`

**Estimated scope:** S (2-3 files)

---

### Task 15: Bắn alert đúng điều kiện

**Description:** Nối `slack_notifier` vào 3 điểm trigger đã chốt trong intent:
lỗi PERMANENT (hết credit/key revoked) từ Task 9, và "hết sạch fallback khả dụng"
từ Task 10. Không bắn cho lỗi TRANSIENT tự phục hồi.

**Acceptance criteria:**
- [ ] Gọi `slack_notifier` tại điểm Task 10 raise "hết credential khả dụng" và
      tại điểm Task 9/2 phát hiện PERMANENT
- [ ] Message có đủ thông tin để SA hành động: purpose (CHAT/EMBEDDING/
      EXTRACTION), provider, model, lý do, thời điểm
- [ ] Debounce cơ bản: cùng 1 credential lỗi PERMANENT nhiều lần liên tiếp trong
      thời gian ngắn chỉ bắn 1 alert (tránh spam nếu nhiều request cùng lúc đụng
      credential vừa chết) — dùng Redis set-with-TTL đơn giản, không cần hàng đợi
      phức tạp

**Verification:**
- [ ] Tests pass: pytest giả lập PERMANENT error → xác nhận gọi
      `slack_notifier.send` đúng 1 lần dù có nhiều request đồng thời lỗi
- [ ] Tests pass: giả lập TRANSIENT error → xác nhận không gọi `slack_notifier`

**Dependencies:** Task 14, Task 10, Task 9

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/core/llm_error_classifier.py`
- `unisage-agent/tests/core/test_model_router.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 6
- [ ] Test giả lập lỗi permanent → có 1 message xuất hiện đúng channel Slack
- [ ] Test giả lập lỗi transient tự phục hồi → không có message nào
- [ ] Review với human — đây là điểm kết thúc MVP bắt buộc

---

## Phase 7: Bảo mật Custom URL

### Task 16: SSRF guard cho `apiBaseUrl`

**Description:** SA có thể nhập Custom Base URL tuỳ ý khi tạo `ChatModel`
(SELF_HOSTED hoặc CLOUD_API trỏ endpoint khác). Cần chặn localhost/private IP/
link-local/metadata endpoint (vd `169.254.169.254`) ở cả bước Java lưu và bước
Python verify-before-active (2 lớp, vì Python là nơi thực sự gửi request ra
ngoài).

**Acceptance criteria:**
- [ ] Java: validate `apiBaseUrl` khi tạo/sửa `ChatModel` — reject nếu resolve
      về loopback/private range (RFC1918)/link-local, trừ khi có allowlist domain
      rõ ràng (nếu hệ thống cần hỗ trợ self-hosted nội bộ hợp lệ — xác nhận với
      SA/team về allowlist cụ thể khi bắt tay code, không tự quyết định danh sách)
- [ ] Python: `verify-before-active` (Task 6) áp cùng guard trước khi thực hiện
      request thật, không tin tưởng validate ở Java là đủ (defense in depth)
- [ ] Test case: URL trỏ `127.0.0.1`, `169.254.169.254`, `10.x.x.x` đều bị từ
      chối; URL public hợp lệ đi qua bình thường

**Verification:**
- [ ] Tests pass: `./mvnw test` và pytest cho cả 2 phía
- [ ] Manual check: thử tạo ChatModel với `apiBaseUrl=http://127.0.0.1:8401` bị
      từ chối rõ ràng

**Dependencies:** Task 3, Task 6

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/utils/SsrfGuard.java`
- `unisage-agent/app/core/ssrf_guard.py`

**Estimated scope:** M (3 files)

---

## Phase 8: unisage-web — Trang quản lý

### Task 17: Trang admin quản lý Provider/Model

**Description:** Thêm 1 trang mới vào khu admin đã có sẵn ở unisage-web: danh
sách `ChatModel` theo purpose (tab hoặc filter CHAT/EMBEDDING/EXTRACTION), form
thêm/sửa (provider, model, API key — masked, base URL, priority), hiển thị
status/errorCount/lastErrorAt, nút activate/deactivate, kéo-thả hoặc input số để
đổi priority.

**Acceptance criteria:**
- [ ] Trang mới dùng layout/permission pattern admin hiện có (không dựng
      layout/auth riêng)
- [ ] API key luôn hiển thị dạng che bớt (vd `sk-...abcd`), không bao giờ hiện
      plaintext trên UI sau khi đã lưu
- [ ] Form tạo mới hiển thị trạng thái PENDING → ACTIVE/lỗi verify theo polling
      hoặc refresh thủ công (verify chạy bất đồng bộ ở Task 6)
- [ ] Embedding tab/section chỉ cho phép 1 model active tại một thời điểm — UI
      phản ánh đúng constraint này (vd chọn active mới tự động deactivate cái cũ,
      có cảnh báo rõ "đổi embedding model không tự re-embed dữ liệu cũ")

**Verification:**
- [ ] Manual check trong browser: luồng thêm 1 API key mới, xem nó chuyển
      PENDING → ACTIVE, đổi priority, deactivate — toàn bộ qua UI
- [ ] Existing e2e suite (`unisage-web/e2e`) vẫn pass, không regression các trang
      admin khác

**Dependencies:** Task 3, Task 6, Task 16

**Files likely touched:**
- `unisage-web/src/features/` (thư mục mới cho model-registry, theo convention
  hiện có của `src/features/`)
- `unisage-web/src/routes/`

**Estimated scope:** L (5+ files — cân nhắc tách nhỏ theo: list view / form
create-edit / activate-deactivate action khi bắt đầu code nếu thấy quá lớn cho 1
session)

---

## Phase 9: Routing Policy nâng cao

> Bắt đầu phase này **sau khi** phase Cost Tracking + Budget Management
> (`changes/<ngày>-Cost-Tracking-Budget-Management/`) đã ghi nhận cost/latency ổn
> định — `LOWEST_COST` và `QUALITY_FIRST` không có dữ liệu đáng tin cậy để chọn
> nếu implement trước.

### Task 18: `routingPolicy` + điều kiện bắt buộc ở Java

**Description:** Thêm cấu hình routing policy theo `modelPurpose`: SA chọn 1
trong 4 chiến lược — `PRIORITY` (giữ hành vi hiện tại, theo field `priority`),
`LOWEST_COST` (ưu tiên credential rẻ nhất theo cost/1K token đã ghi nhận từ Cost
Tracking phase), `BALANCED` (kết hợp cost + latency theo trọng số), `QUALITY_FIRST`
(ưu tiên model mạnh nhất/latency thấp nhất, bỏ qua cost). Kèm điều kiện bắt buộc:
model tối thiểu, độ trễ tối đa cho phép, ngân sách còn lại tối thiểu.

**Acceptance criteria:**
- [ ] Entity/bảng mới `routing_policy` (hoặc field trên 1 bảng cấu hình purpose-
      level nếu đã có) lưu: `modelPurpose`, `strategy` (enum 4 giá trị trên),
      `maxLatencyMs`, `minRequiredModel` (tuỳ chọn), cập nhật qua API SA-facing
- [ ] `GET` endpoint để Python đọc cấu hình này (nối vào snapshot đã có ở Task 4)
- [ ] Migration Flyway mới tương ứng

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check: SA đổi strategy qua Postman, Python snapshot phản ánh đúng
      sau 1 chu kỳ hot-reload (Task 7/8)

**Dependencies:** Task 3, Task 7; và cần bảng cost/latency từ Cost Tracking phase
đã tồn tại (tối thiểu là schema, không cần đủ dữ liệu lịch sử để bắt đầu code)

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/entity/RoutingPolicy.java`
- `unisage-backend/src/main/java/com/unisage/backend/controller/RoutingPolicyController.java`
- `unisage-backend/src/main/resources/db/migration/V1X__add_routing_policy.sql`

**Estimated scope:** M (3-4 files)

---

### Task 19: Python áp policy khi chọn credential

**Description:** `model_router` (Task 10) hiện chọn credential kế tiếp theo
`priority` tĩnh khi credential hiện tại lỗi. Mở rộng để đọc `routingPolicy` từ
snapshot: `LOWEST_COST` sort theo cost/1K token (lấy từ dữ liệu Cost Tracking
phase, không phải tính lại), `BALANCED` dùng công thức trọng số cost+latency đơn
giản (vd `score = w1*normalized_cost + w2*normalized_latency`, trọng số cấu hình
được hoặc mặc định 0.5/0.5), `QUALITY_FIRST` sort theo latency trung bình tích
luỹ tăng dần, `PRIORITY` giữ nguyên hành vi cũ.

**Acceptance criteria:**
- [ ] `model_router` nhận thêm `strategy` từ snapshot, có 1 hàm sort riêng cho
      mỗi strategy, không phá vỡ logic circuit breaker/cooldown đã có (Task 10)
      — strategy chỉ quyết định **thứ tự ưu tiên trong tập credential khả dụng**,
      không thay thế việc loại credential đang cooldown/DISABLED
- [ ] Điều kiện bắt buộc (`maxLatencyMs`, `minRequiredModel`) lọc credential trước
      khi áp strategy — credential không thoả điều kiện bị loại khỏi danh sách
      ứng viên hoàn toàn, không chỉ xếp hạng thấp
- [ ] Test riêng cho từng 4 strategy với dữ liệu cost/latency giả lập

**Verification:**
- [ ] Tests pass: pytest cho `model_router` với từng strategy

**Dependencies:** Task 18, Task 10

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/tests/core/test_model_router.py`

**Estimated scope:** M (2 files, logic sort có thể phức tạp — tách hàm riêng
theo strategy để dễ test độc lập)

---

### Task 20: Từ chối request khi ngân sách toàn hệ thống hết

**Description:** Khi Cost Tracking phase báo ngân sách **toàn hệ thống** (không
phải theo provider/nhóm hẹp hơn) đã hết, `model_router` phải từ chối request
ngay — không được coi đây là 1 lỗi credential rồi thử fallback sang provider
khác (vì fallback sẽ tiếp tục phát sinh chi phí ở model rẻ hơn nhưng vẫn tốn
tiền, đi ngược mục đích chặn ngân sách). Ngân sách hẹp hơn (theo 1 provider/nhóm)
hết thì vẫn fallback bình thường sang provider khác còn ngân sách.

**Acceptance criteria:**
- [ ] `model_router` kiểm tra cờ "system budget exhausted" (đọc từ running-total
      Redis của Cost Tracking phase) **trước** khi chọn credential, tách biệt
      hoàn toàn khỏi vòng lặp chọn fallback theo lỗi provider
- [ ] Khi cờ này bật → raise lỗi riêng (vd `SystemBudgetExhaustedError`), API trả
      về client 1 mã lỗi rõ ràng (không phải lỗi 500 chung chung), không có lệnh
      gọi provider nào được thực hiện
- [ ] Test xác nhận: khi cờ bật, dù có N credential ACTIVE khả dụng, không có
      credential nào được gọi

**Verification:**
- [ ] Tests pass: pytest giả lập cờ budget-exhausted → xác nhận 0 lời gọi
      provider, lỗi trả về đúng loại

**Dependencies:** Task 19; và cơ chế running-total budget từ Cost Tracking phase

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/api/exceptions.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 9
- [ ] SA chọn `LOWEST_COST` cho purpose CHAT → router ưu tiên credential có cost
      thấp nhất trong số credential ACTIVE thoả điều kiện bắt buộc
- [ ] SA chọn `QUALITY_FIRST` → router ưu tiên credential latency thấp nhất, bỏ
      qua cost
- [ ] Test giả lập: ngân sách hệ thống = 0 → request bị từ chối, không phát sinh
      lệnh gọi provider nào

---

## Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ acceptance criteria ở mục Success trong intent đã xác nhận đều pass
- [ ] SA có thể tự thêm key mới, thấy nó chuyển PENDING → ACTIVE, và thấy Chat
      dùng key mới — toàn bộ qua UI, không cần dev can thiệp
- [ ] Ready for review
