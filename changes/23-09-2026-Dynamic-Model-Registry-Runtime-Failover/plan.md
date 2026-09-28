# Implementation Plan: Dynamic Model Registry + Runtime Active Switch + Failover

## Overview

SA (Super Admin) hiện chỉ có thể đăng ký LLM model/API key qua `ChatModel` (Java,
`unisage-backend`), nhưng `unisage-agent` (Python) không hề đọc bảng này — nó dùng
`OPENAI_API_KEY`/`OPENAI_MODEL`/`OPENAI_EMBEDDING_MODEL`/`MULTI_REP_LLM_MODEL` tĩnh
từ `.env`, load một lần lúc khởi động. Khi key hết credit, cách duy nhất để khắc phục
là sửa `.env` và restart. Các field `priority`/`errorCount`/`lastErrorAt` trên
`ChatModel` đã tồn tại trong schema nhưng chưa được ghi hay đọc ở bất kỳ đâu.

Feature này nối hai phía lại: mở rộng `ChatModel` để phân biệt 3 vai trò (CHAT /
EMBEDDING / EXTRACTION), cho Python đọc registry này qua 1 API nội bộ riêng, gọi
provider qua model native của PydanticAI theo từng provider (xem Architecture
Decisions, ADR 0005), đồng bộ runtime qua Redis để mọi worker process (gunicorn + Celery)
thấy config mới mà không cần restart, và triển khai auto-failover **chỉ cho Chat
và Extraction** (Embedding cố tình không auto-failover). Sự cố cần SA can thiệp
(hết credit, key thu hồi, hết fallback) bắn cảnh báo qua Slack Incoming Webhook.

Phạm vi trải trên 4 repo: `unisage-backend` (Java, control plane), `unisage-agent`
(Python, data plane), `api-gateway` (chặn đường `/internal/**` từ bên ngoài),
`unisage-web` (trang admin). Các task nhóm theo repo/dependency, nhưng thứ tự triển
khai vẫn đi theo vertical slice ở cấp tính năng: "Chat dùng được registry" xong
trước rồi mới thêm "Chat tự failover".

### Lịch sử review

Bản đầu bị review chặn với 8 blocker. Phase 0.5 được thêm để gỡ từng blocker
trước khi code tính năng:

| # | Blocker | Gỡ ở |
|---|---------|------|
| 1 | Internal API bị `DynamicAuthorizationManager` trả 403 dù secret đúng | Task 0.1 + mục "Internal API contract" |
| 2 | `GET /chat-models` chỉ trả `hasApiKey`, phân trang, dành cho RBAC | Endpoint riêng `/internal/model-registry/snapshot` (Task 0.1, Task 4) |
| 3 | Chưa có thiết kế LiteLLM ↔ PydanticAI (`Agent.run_stream()`) | Task 0.2 (spike + decision gate) |
| 4 | Java chưa có Redis dependency/config/deployment | Task 0.4 + mục "Hot-reload consistency" |
| 5 | `isActive` xung đột `status`, thiếu state machine + constraint embedding | Task 0.3 + mục "State machine" |
| 6 | Verify-before-active vòng lặp Java→Python→Java, thiếu job lifecycle | Mục "Verification lifecycle" (pull-based, không còn Java→Python) |
| 7 | SSE chỉ có `token`/`done`; Celery embedding nuốt lỗi từng chunk | Mục "SSE error contract", Task 11, Task 13 |
| 8 | SSRF nằm sau MVP | Chuyển thành Task 0.6, gate trước Task 5/6/17 |

Review vòng 2 — điều kiện bổ sung trước khi Checkpoint Phase 0.5 được pass:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R2.1 | Claim/result verify thiếu fencing token | `leaseToken` trong claim + result (mục "Verification lifecycle") |
| R2.2 | Chặn gateway phải có test tự động, không phụ thuộc thứ tự với `AuthenticationFilter` | `InternalPathBlockFilter` chạy trước auth + test MockWebServer (Task 0.1) |
| R2.3 | Harness thiếu Celery Beat + dữ liệu seed | Task 0.5 |
| R2.4 | Port Java 8401 bị expose trực tiếp, secret mặc định | Mục "Security flow" bước 4-5, Task 0.1 |
| R2.5 | Sửa credential làm ACTIVE → PENDING gây outage | Staged rotation (mục "Credential rotation") |
| R2.6 | Health report cũ từ snapshot cũ | `credentialRevision` trong health request |
| R2.7 | Phương án dự phòng native PydanticAI chưa chắc SSRF-safe | Tiêu chí bắt buộc ở decision gate Task 0.2 |

Review vòng 3:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R3.1 | Lease phải kiểm cả thời hạn; result lặp phải idempotent | `lease_until > now()` trong CAS + `last_result_lease_token` |
| R3.2 | Race giữa 2 lần sửa credential | `candidate_generation` + `base_revision`, job `SUPERSEDED` |
| R3.3 | Không chỉ dựa vào gateway để bảo vệ key | Security flow bước 4-8: port, CIDR fail-closed, bỏ qua XFF, secret ở cả 3 service, constant-time, TLS/mạng mã hoá |
| R3.4 | Gateway đã có `AuthenticationFilterTest`; cần test thứ tự filter thật | Unit + integration test `InternalPathBlockFilter` (Task 0.1) |
| R3.5 | Reset harness race với Beat/worker; smoke schema cũ chỉ là hạ tầng | Quy trình reset Task 0.5 + "Checkpoint: Registry lifecycle" |
| R3.6 | SSRF cho mọi đường gọi provider | Factory HTTP client duy nhất, pin IP ở socket layer, `trust_env=False`, từ chối 3xx, loại provider không hỗ trợ |

Review vòng 4 — chi tiết implementation/test bắt buộc trước khi Phase 0.5 PASS:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R4.1 | Idempotency cùng transaction với promote; result lặp không tăng revision/không phát event lần 2 | Bước 4 "Verification lifecycle": 1 transaction, khoá model → job |
| R4.2 | Job `SUPERSEDED` không bao giờ promote dù token/lease còn hợp lệ | SA sửa → job dở `SUPERSEDED`; điều kiện `status = 'RUNNING'` kiểm trên row đã khoá |
| R4.3 | Cô lập Redis cho Celery | "Hot-reload consistency": tách DB broker/backend/registry, queue + channel có prefix |
| R4.4 | Gateway kiểm raw path | Security flow bước 3: `getRawPath()`, từ chối `%2F`/`%2E`/`%25`/`;`/`\` |
| R4.5 | CIDR chỉ dùng địa chỉ kết nối thật | Security flow bước 6: filter không đọc header forward |
| R4.6 | SSRF giữ Host/SNI, bật cert validation, tắt proxy, DNS rebinding thật, pin `httpcore` | "SSRF policy" |

Review vòng 5:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R5.1 | Key lộ qua error message/traceback/Celery result/Slack | Mục "Secret redaction" + test canary |
| R5.2 | Enum `SUPERSEDED` thiếu trong todo Task 1 | Task 1 liệt kê đủ 6 trạng thái |
| R5.3 | Update API key cần tri-state thật | `ChatModelUpdateRequest` + `JsonNullable<String>` |
| R5.4 | Chốt chỗ chạy pytest; reset endpoint không tồn tại ở production | Task 0.5: service `test-runner` trong network |
| R5.5 | Counter gateway bị control case làm nhiễu | Task 0.1: MockWebServer riêng cho block và control |
| R5.6 | Kiểm cú pháp URL | "SSRF policy": userinfo, query/fragment, port, scheme, control char |

Review vòng 6:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R6.1 | `claim` cũng trả key plaintext → cần `no-store` | Filter gắn `no-store` cho mọi `/internal/**` (Task 0.1) |
| R6.2 | `BackendJavaClient` tắt redirect tường minh | Security flow bước 9 + test (Task 0.1) |
| R6.3 | Gateway production phải gọi Java qua service nội bộ | Security flow bước 4 + startup check (Task 0.1) |

Review vòng 7:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R7.1 | **Blocker:** staged rotation có thể tự promote EMBEDDING đổi model → sai vector space | Mục "Embedding identity guard": đổi danh tính → `REINDEX_REQUIRED`; key-only phải khớp fingerprint. Guard áp cả cho activate/swap — đường này review chưa nêu nhưng cũng đổi model âm thầm |
| R7.2 | `claim` cần `no-store` | Đã có từ vòng 6 (filter cho mọi `/internal/**`) |
| R7.3 | `BackendJavaClient` `follow_redirects=False` | Đã có từ vòng 6 (Security flow bước 9) |
| R7.4 | `prod` thiếu `INTERNAL_ALLOWED_CIDRS` phải có test startup fail | Task 0.1 verification |
| R7.5 | Kubernetes: NetworkPolicy, ClusterIP, IP thật mà `getRemoteAddr()` thấy | Security flow bước 4 (áp dụng khi thêm manifest K8s) |

Review vòng 8:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R8.1 | Contract nội bộ không nhất quán (ghi "5 endpoint", path identity lệch base path) | Bảng 7 endpoint là danh sách duy nhất; path chuẩn `/internal/model-registry/embedding-index/{collection}/identity` |
| R8.2 | Danh sách trạng thái verification không nhất quán | Bảng chuẩn 7 trạng thái + file contract `verification-statuses.json` + test chống lệch |
| R8.3 | Race khởi tạo danh tính lần đầu; không nên singleton toàn cục | Khoá chính `collection_name`, `INSERT ... ON CONFLICT DO NOTHING`, trigger chặn UPDATE/DELETE, test đồng thời |
| R8.4 | CIDR K8s không mặc nhiên là pod CIDR | Quy trình staging đo `remoteAddr` thực tế rồi mới điền |
| R8.5 | `no-store` cho claim + `follow_redirects=False` phải nằm trong acceptance test | Test ở Task 0.1, lặp lại ở Task 4 (snapshot) và Task 6 (claim) |

Review vòng 9:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R9.1 | Frontend không được giữ bản copy tay `verification-statuses.json` | Mục "Contract files dùng chung" + Task 0.8: sinh từ enum, web sinh type theo SHA khoá, CI chặn sửa tay |
| R9.2 | Contract coverage đọc route mapping thực tế, phủ MVC/WebFlux | Task 0.1: duyệt mọi `HandlerMapping` trong context, assert không có reactive mapping |
| R9.3 | K8s: CIDR không phân biệt được caller → bắt buộc NetworkPolicy hoặc mesh identity | Security flow bước 4: gate chặn deploy, phải có test chứng minh đang thực thi |
| R9.4 | Test identity race đủ transaction semantics | Task 0.3: 201/409, delta UPDATE/DELETE = 0, đọc lại đúng, không upsert bằng danh tính sai |
| R9.5 | Mọi checkpoint phải chạy pass | "Gate: implementation approved" bên dưới |

Review vòng 10:

| # | Điều kiện | Gỡ ở |
|---|-----------|------|
| R10.1 | Plan chưa nói rõ phải xoá `OPENAI_*`/`MULTI_REP_LLM_MODEL` khỏi runtime và cấm fallback về chúng khi bật registry | Mục "Cutover khỏi cấu hình `.env` tĩnh" (mới) + acceptance criteria Task 5, Checkpoint Phase 2 |

**Trạng thái approve:**

| Mức | Trạng thái | Điều kiện |
|-----|-----------|-----------|
| Design approved | **Đạt** | Plan + todo hiện tại |
| Được triển khai Phase 0.5 | **Đạt** | — |
| Implementation approved | **Chưa** | Mọi dòng trong "Gate: implementation approved" có test chạy pass, kèm link CI/report |

### Gate: implementation approved

Chỉ chuyển sang "implementation approved" khi **toàn bộ** nhóm test dưới đây chạy
pass (không skip, không pending), có link CI run hoặc report JUnit đính kèm:

| # | Nhóm | Test/kịch bản bắt buộc | Nằm ở |
|---|------|------------------------|-------|
| 1 | Staged rotation + activate/swap embedding | Rotation không downtime, rotation race (`SUPERSEDED`), swap cùng danh tính OK, khác danh tính 409 | Task 0.3/3/6, "Checkpoint: Registry lifecycle", Checkpoint Phase 5 |
| 2 | `REINDEX_REQUIRED` | Đổi model/provider/baseUrl/dimension, key-only fingerprint lệch → `REINDEX_REQUIRED`, snapshot không đổi | Task 0.3/3/6/13 |
| 3 | Concurrent identity registration | Java 201/409 + delta UPDATE/DELETE = 0 + đọc lại đúng; cross-repo không upsert bằng danh tính sai | Task 0.3/1/13 |
| 4 | Contract coverage | `InternalEndpointCoverageTest` bằng đúng 7 endpoint; `ContractExportTest` + `contracts:check` ở web/agent xanh | Task 0.1/0.8 |
| 5 | Gateway / no-store / redirect | Ma trận raw path → 404 trước auth, 0 request tới backend; `InternalNoStoreTest` cho cả 7 endpoint; redirect test cho mọi method `BackendJavaClient` | Task 0.1/4/6 |
| 6 | SSRF | Vector cú pháp URL chung, DNS rebinding thật, Host/SNI/cert, redirect bị từ chối, `trust_env`, test kiến trúc không client thô | Task 0.6 |
| 7 | Secret redaction | Vector redaction chung, test canary quét DB/Redis/log/response/Slack | Task 0.7 |
| 8 | Kubernetes source-IP | `scripts/k8s/verify-internal-access.sh` xanh trên staging + `remoteAddr` thực tế đã ghi lại — **chỉ bắt buộc khi deploy lên K8s**; không deploy K8s thì ghi rõ "N/A — deploy bằng compose" và test CIDR/port của compose production thay thế | Task 0.1, Security flow bước 4 |

## Architecture Decisions

- **Model native của PydanticAI per provider (`OpenAIChatModel`, `AnthropicModel`,
  ...), không dùng LiteLLM SDK.** Đã đổi từ kế hoạch ban đầu (adapter
  `LiteLLMModel(pydantic_ai.models.Model)`) sau khi spike Task 0.2 chạy thật:
  LiteLLM SDK không cho inject `httpx.AsyncClient`/transport theo cách
  `PinnedNetworkBackend` (Task 0.6, SSRF) cần — `litellm.acompletion(client=...)`
  đòi một object dạng OpenAI SDK client hoặc `aiohttp.ClientSession`, không nhận
  `httpx.AsyncClient` trần. Model native (`OpenAIProvider(http_client=...)`) thì
  nhận đúng transport này — đã xác nhận bằng spike (kèm test đối chứng: transport
  giả nhận đúng 1 request). Streaming, structured output (kể cả khi stream),
  usage, exception typed đều PASS ở cả 2 phương án — quyết định chỉ dựa trên tiêu
  chí SSRF, đúng nhánh đã chốt sẵn ở gate Task 0.2. Chi tiết + bảng PASS/FAIL:
  `unisage-backend/docs/adr/0005-dynamic-model-registry.md`.
  `get_graph_models()` map `llmProvider` → cặp (Model class, Provider class);
  provider ngoài danh sách → từ chối như provider không hỗ trợ SSRF (đã có sẵn
  trong thiết kế "SSRF guard là gate"). Toàn bộ graph node/`stream_agent_text()`/
  `FunctionModel` test doubles giữ nguyên — quyết định này chỉ đổi cách build
  `Model` instance, không đổi cách gọi nó.
- **Embedding không auto-failover, không auto-switch model.** Đổi embedding model
  (kể cả cùng số chiều) làm vector nằm ở không gian ngữ nghĩa khác — retrieval
  degrade âm thầm. Tại một thời điểm chỉ có đúng 1 credential EMBEDDING active
  (ép bằng unique partial index, không chỉ bằng code); lỗi thì dừng ingest job và
  báo SA. Rotation key và activate/swap cũng không được đổi model: mọi đường đổi
  embedding đang chạy đều bị so với danh tính của index (mục "Embedding identity
  guard"). Đổi hẳn embedding model là migration có chủ đích, ngoài scope.
- **Registry đi qua namespace `/internal/**` riêng của Java, không dùng lại API
  SA.** DTO nội bộ chứa API key plaintext và không bao giờ được trả qua controller
  SA-facing. Xác thực bằng `X-Internal-Secret` sẵn có, nhưng quyền được cấp theo
  namespace (không qua RBAC, không qua `PredefinedPublicPaths`). Gateway chặn
  `/api/v1/master/internal/**` từ bên ngoài.
- **Python không expose endpoint nội bộ mới.** Gateway gắn `X-Internal-Secret` cho
  mọi request `/api/v1/ai/**`, nên một endpoint "nội bộ" ở Python thực chất ai qua
  gateway cũng gọi được. Mọi luồng Java↔Python của feature này đều là **Python gọi
  Java** (pull), kể cả verify-before-active.
- **Redis pub/sub chỉ là tín hiệu, không phải nguồn cấu hình.** DB là nguồn sự
  thật; version tăng trong cùng transaction với thay đổi, publish **sau commit**.
  Mỗi worker poll version định kỳ để tự sửa nếu mất message. Java và Python dùng
  **cùng 1 Redis instance** (cùng `REDIS_URL`).
- **Circuit breaker ở cấp credential, không phải cấp model.** Disable đúng
  credential lỗi, thử credential/provider khác.
- **SSRF guard là gate, không phải hardening sau MVP.** Không có code Python nào
  được gọi URL lấy từ registry trước khi guard (Task 0.6) chạy ở cả 2 lớp.
- Thay đổi đủ cross-cutting nên **cần ADR** `unisage-backend/docs/adr/0005-dynamic-model-registry.md`
  (Task 0), chốt sau spike Task 0.2.
- **Routing Policy (Phase 9, làm sau cùng) phụ thuộc dữ liệu cost/latency từ plan
  Cost Tracking** (`changes/23-09-2026-Cost-Tracking-Budget-Management/`). Số phase
  0-9 và số task 0-20 được giữ nguyên vì plan đó tham chiếu "Model Registry Phase
  0-6", "Phase 9".
  **Migration version (cập nhật vòng review chung):** giả định ban đầu "V16 cho
  Model Registry, V20 cho Routing Policy" đã sai vì các migration khác trên
  `main` chen vào trước — migration state machine của plan này thực tế lên DB
  dưới tên `V18__add_chat_model_purpose_and_status.sql` (không phải `V16`, xem
  file thật trong `db/migration/`), và plan Cost Tracking đã chốt lại dùng
  V25-V27 (repo hiện ở V24 tại thời điểm sửa). Routing Policy (Phase 9) vì vậy
  dùng **V28**, không phải V20 — luôn xác nhận bằng
  `ls db/migration | sort -V | tail -1` trước khi tạo file thật, số ở đây chỉ
  đúng tại thời điểm viết.

## Cutover khỏi cấu hình `.env` tĩnh

Sau khi registry bật (từ Task 5 trở đi), `unisage-agent` **không còn nguồn credential
nào khác ngoài snapshot của Java**. Luồng đúng duy nhất:

```
Java lưu credential mã hoá trong DB
        ↓
Python lấy snapshot từ Java (GET /internal/model-registry/snapshot)
        ↓
Python chọn provider/model/key từ snapshot (theo purpose + priority)
        ↓
Tạo client qua provider factory (Task 0.6, build_provider_http_client)
```

`.env` của `unisage-agent` sau cutover chỉ còn giữ:
- Secret + URL kết nối Python ↔ Java (`APP_INTERNAL_SECRET_KEY`, `BACKEND_JAVA_BASE_URL`,
  `INTERNAL_NETWORK_ENCRYPTED`).
- Redis, DB, Qdrant, MinIO — hạ tầng của chính Python, không phải của provider LLM.
- Cấu hình bảo mật/SSRF (`MODEL_REGISTRY_URL_ALLOWLIST`...).
- Biến chỉ dùng cho công cụ migrate/bootstrap một lần (vd
  `register_embedding_index_identity` đọc `.env` cũ **trước khi** registry bật cho
  embedding — xem "Embedding identity guard").
- Biến của profile test/fake provider (harness Task 0.5).

**Bị xoá khỏi runtime, không chỉ khỏi `.env.example`:** `OPENAI_API_KEY`,
`OPENAI_MODEL`, `OPENAI_EMBEDDING_MODEL`, `MULTI_REP_LLM_MODEL` — khỏi
`docker-compose`, deployment manifest, và secret production. Ràng buộc bắt buộc,
kiểm bằng test kiến trúc (Task 5):
- Không route nào của chat/ingest/retrieval/Celery đọc `settings.OPENAI_*` hay
  `settings.MULTI_REP_LLM_MODEL` nữa — `openai_embedder.py`,
  `multi_representation.py`, `get_graph_models()` (đã chuyển sang factory ở Task
  0.6) đều phải nhận credential từ snapshot, không đọc `Settings` cho phần này.
- **Không fallback về OpenAI** khi registry lỗi hoặc không có credential cho một
  purpose — lỗi phải rõ ràng (xem "SSE error contract"/Task 13 FAILED), không âm
  thầm dùng key `.env` cũ.
- **Không hardcode** `OpenAI(...)`/`AsyncOpenAI(...)` làm client mặc định ở bất kỳ
  đường gọi provider nào ngoài factory (đã có test kiến trúc
  `test_no_raw_provider_clients.py` ở Task 0.6 — quét luôn 4 biến trên trong
  `app/core/config.py` sau khi bị xoá, không chỉ quét lệnh gọi client).
- **Giữ được chuỗi `"openai"` trong dữ liệu registry** (giá trị `llmProvider` của
  một `ChatModel` cụ thể) — đây là cấu hình động do SA nhập, khác hẳn việc code
  đọc biến môi trường `OPENAI_*` làm mặc định cứng.

Trong giai đoạn Phase 2-3 (trước khi mọi purpose đều có credential ACTIVE ổn định),
Python vẫn có thể fail startup rõ ràng nếu registry rỗng (xem "Checkpoint: Phase
2") thay vì âm thầm dùng `.env` — không có trạng thái lưng chừng "vừa đọc registry
vừa đọc `.env`".

## Internal API contract

Base path Java: `/api/v1/internal/model-registry` (context path `/api/v1`). Mọi
request bắt buộc header `X-Internal-Secret`; không cần JWT.

Đây là **danh sách đầy đủ và duy nhất** các endpoint nội bộ **của riêng feature
này** (7 endpoint) — đây là checkpoint gốc của plan Model Registry, đạt được
độc lập với Cost Tracking. Mọi chỗ khác (security flow, DTO isolation,
gateway-block test, test `no-store`) tham chiếu bảng này; thêm endpoint mới
thì sửa ở đây trước.

**Sau khi tích hợp plan Cost Tracking** (`changes/23-09-2026-Cost-Tracking-Budget-Management/`,
mục "Internal API & bảo mật" của plan đó), 3 endpoint nội bộ của Cost
(`POST /internal/usage-logs`, `GET /internal/budgets/snapshot`,
`GET /internal/usage-logs/period-totals`) dùng chung cơ chế xác thực này và
được thêm vào **cùng** `InternalEndpointCoverageTest`/`InternalNoStoreTest`.
Từ thời điểm đó, **có hai mốc nghiệm thu khác nhau, không được lẫn lộn**:
- Checkpoint Phase 0.5 của plan này (độc lập, không phụ thuộc Cost Tracking):
  coverage test phải bằng đúng **7**.
- Checkpoint chung sau khi Cost Tracking Task 2 merge: coverage test (bản đã
  được Cost Tracking mở rộng) phải bằng đúng **10** (7 của plan này + 3 của
  Cost Tracking). Nếu Cost Tracking chưa merge, con số đúng vẫn là 7 — không
  tự ý sửa số 7 ở đây chỉ vì có kế hoạch mở rộng trong tương lai.

| # | Method | Path (sau base path) | Gọi bởi | Trả secret? | Mục đích |
|---|--------|------|---------|---|----------|
| 1 | GET | `/snapshot` | Python startup + reload | **Có** (key plaintext) | Toàn bộ credential ACTIVE theo purpose, kèm `version` |
| 2 | GET | `/version` | Python poll định kỳ | Không | `{ "version": 42 }` |
| 3 | POST | `/credentials/{id}/health` | Python sau lỗi provider | Không | Ghi `errorCount`/`lastErrorAt`, PERMANENT → `DISABLED` (chỉ khi đúng revision) |
| 4 | POST | `/verifications/claim` | Python verifier | **Có** (key ứng viên plaintext) | Claim tối đa N job QUEUED (lease), trả `leaseToken` + credential ứng viên |
| 5 | POST | `/verifications/{jobId}/result` | Python verifier | Không | Ghi kết quả verify kèm `leaseToken`; token lệch → 409, không đổi gì. EMBEDDING gửi kèm `embeddingDimension` + `embeddingFingerprint` |
| 6 | GET | `/embedding-index/{collection}/identity` | Python | Không | Danh tính của vector trong collection (404 nếu chưa có) |
| 7 | PUT | `/embedding-index/{collection}/identity` | Lệnh bootstrap Python / lần upsert đầu | Không | Xác lập danh tính, **only-if-absent**: đã có → 409 `EMBEDDING_INDEX_IDENTITY_EXISTS`, không bao giờ ghi đè |

Không có endpoint nào sửa hay xoá danh tính index. Đổi danh tính chỉ xảy ra qua
plan re-index/migration riêng (ngoài scope), bằng collection mới.

Áp dụng chung cho cả 7 endpoint: đi qua `InternalSecretFilter` + CIDR fail-closed
+ `InternalResponseHeadersFilter` (`no-store`, kể cả endpoint không trả secret);
DTO ở `dto/request/internal/` + `dto/response/internal/`; nằm trong ma trận
gateway-block test (Task 0.1).

Snapshot response (`InternalModelRegistrySnapshotResponse`, package
`dto/response/internal/`):

```json
{
  "version": 42,
  "generatedAt": "2026-09-25T03:00:00Z",
  "purposes": {
    "CHAT":       [ { "id": "uuid", "revision": 3, "sourceType": "CLOUD_API", "provider": "openai",
                      "modelName": "gpt-4o-mini", "apiBaseUrl": "https://api.openai.com/v1",
                      "apiKey": "sk-...", "priority": 1, "maxRpm": 500 } ],
    "EMBEDDING":  [ ... đúng 1 phần tử ... ],
    "EXTRACTION": [ ... ]
  },
  "embeddingIndexIdentity": { "provider": "openai", "modelName": "text-embedding-3-small",
                              "dimension": 1536, "fingerprint": [[...], [...], [...]] }
}
```

Quy tắc:
- Chỉ chứa row `is_active = true AND status = 'ACTIVE'`, sort theo `priority`.
- `version` và dữ liệu đọc trong **cùng 1 transaction read-only `REPEATABLE_READ`**
  → không bao giờ trả version mới kèm dữ liệu cũ (hoặc ngược lại).
- `toString()` của DTO che `apiKey`; không log body.
- **Mọi** response `/internal/**` (không chỉ snapshot — `claim` cũng trả key
  plaintext) có `Cache-Control: no-store` + `Pragma: no-cache`, gắn bằng 1 filter
  cho cả namespace thay vì từng controller, để endpoint mới thêm sau không quên.
- `revision` là số nguyên tăng mỗi khi field credential của row thay đổi (xem
  "Credential rotation"). Python gắn nó vào mọi health report và mọi key
  cooldown/circuit breaker.
- Health body: `{ credentialRevision, snapshotVersion, errorType: TRANSIENT|PERMANENT, errorCode, message, occurredAt }`.
  `credentialRevision` khác revision hiện tại của row → Java bỏ qua toàn bộ report
  (không tăng counter, không đổi status), trả `200 { applied: false }` và log
  debug. Chỉ tăng version khi `status` thực sự đổi (tránh reload snapshot mỗi lần 429).

Security flow:
1. `InternalSecretFilter`: thêm `("*", "/internal/**")` vào `INTERNAL_ONLY_PATHS`
   → thiếu/sai secret trả 403 `INTERNAL_SECRET_INVALID` ngay tại filter.
2. `DynamicAuthorizationManager`: nhánh mới trước bước lấy `Authentication` —
   path khớp `/internal/**` **và** request attribute
   `TRUSTED_INTERNAL_CALLER_ATTRIBUTE == TRUE` → grant. Path `/internal/**` không
   có attribute → deny (kể cả user SA có JWT hợp lệ).
3. `api-gateway`: **không** dùng route để chặn. `AuthenticationFilter` là
   `GlobalFilter` order `-1` và chạy trước route, nên route chặn sẽ trả 401 khi
   thiếu JWT và chỉ trả 404 khi đã có JWT hợp lệ. Thay vào đó dùng
   `InternalPathBlockFilter` (`GlobalFilter`, order `Ordered.HIGHEST_PRECEDENCE`)
   trả 404 cho `/api/v1/master/internal/**` và `/api/v1/ai/internal/**` **trước
   mọi xử lý auth** → phản hồi giống nhau dù có hay không có JWT.
   Match trên **raw path** (`request.getURI().getRawPath()`), không chỉ
   `getPath()` — `getPath()` đã decode một lần nên `%252F` (double-encoding) chỉ
   còn `%2F`, và kết quả phụ thuộc cách Netty/Spring normalize trước khi filter
   chạy. Quy tắc:
   - Raw path dưới `/api/v1/master/` hoặc `/api/v1/ai/` chứa `%2F`, `%5C`, `%2E`,
     `%25` (không phân biệt hoa thường), `\`, hoặc `;` → **từ chối luôn** (404),
     không cố decode. Không có route hợp lệ nào cần các ký tự này.
   - Phần còn lại: decode lặp tới khi ổn định (tối đa 3 lần), gộp `//`, resolve
     `.`/`..`, lowercase, rồi match `/api/v1/{master,ai}/internal/**`.
   - Test gửi đúng byte raw path lên socket (không để client HTTP tự encode/
     normalize) và kiểm backend giả nhận 0 request.
Gateway chỉ là 1 lớp. API key plaintext trong snapshot được bảo vệ bởi **tất cả**
các lớp dưới đây, không lớp nào được coi là đủ một mình:

4. Mạng: production **không** publish port Java ra host/public network. Hiện
   `backend-java/docker-compose.yml` publish `8401:8401` — giữ cho dev, còn
   production dùng compose override/manifest riêng chỉ `expose` trong network nội
   bộ (hoặc security group/ACL chỉ cho gateway + agent).
   Đóng port host thì mọi caller phải đổi sang địa chỉ nội bộ, nếu không gateway
   sẽ không gọi được Java: `api-gateway/docker-compose.yml` hiện mặc định
   `JAVA_BACKEND_URI=http://host.docker.internal:8401` (và `PYTHON_AI_URI` tương
   tự). Manifest production đặt gateway, Java, agent vào cùng 1 network nội bộ
   và dùng tên service: `JAVA_BACKEND_URI=http://backend-java:8401`,
   `PYTHON_AI_URI=http://unisage-agent:8402`, agent
   `BACKEND_JAVA_BASE_URL=http://backend-java:8401/api/v1` (hoặc `https://` theo
   bước 8). Gateway và agent fail startup ở profile `prod` nếu URI upstream có host
   `host.docker.internal`, `localhost`, `127.0.0.1`. `host.docker.internal` chỉ
   còn là mặc định cho dev.
5. CIDR **fail-closed**: `/internal/**` kiểm `request.getRemoteAddr()` với
   `INTERNAL_ALLOWED_CIDRS`. Danh sách rỗng hoặc không parse được → **từ chối mọi**
   request `/internal/**` (không có chế độ "rỗng = tắt kiểm"). Dev đặt giá trị tường
   minh trong `.env` (vd `127.0.0.1/32,::1/128,172.16.0.0/12`).
6. Không tin `X-Forwarded-For`/`Forwarded`: đặt tường minh
   `server.forward-headers-strategy=NONE` để `getRemoteAddr()` luôn là IP socket
   thật. Filter kiểm CIDR (`InternalCallerCidrFilter`) chỉ dùng
   `request.getRemoteAddr()` — **không đọc** `X-Forwarded-For`, `Forwarded`,
   `X-Real-IP` hay header tương tự, và không dùng lại logic
   `MessageController#extractClientIp` (logic đó cố ý tin XFF khi có secret, phục
   vụ mục đích khác). Config `NONE` là lớp thứ 2, không thay cho việc filter tự
   không đọc header. Nếu sau này có proxy/LB
   đứng trước Java thì phải khai báo trusted proxy (`RemoteIpValve`
   `internalProxies`) trước khi đổi strategy — thay đổi đó cần review riêng.
7. Secret: Java, Python **và api-gateway** fail startup ở profile `prod` nếu
   `INTERNAL_SECRET_KEY` bằng giá trị mặc định đang hardcode
   (`unisage-internal-secret-key-2026` ở `backend-java/application.properties`,
   `api-gateway/application.yml`, `unisage-agent/app/core/config.py`) hoặc ngắn hơn
   32 ký tự. So sánh constant-time ở mọi nơi: `MessageDigest.isEqual` (Java),
   `hmac.compare_digest` (Python — thay `!=` hiện có ở `app/core/security.py` và
   `app/api/v1/ingestion.py`).
8. Mã hoá đường truyền: `X-Internal-Secret` chỉ **xác thực**, không mã hoá.
   Snapshot và claim response chứa key plaintext nên đường Python ↔ Java ở
   production phải là TLS (hoặc mTLS) **hoặc** private network đã mã hoá (vd
   overlay network encrypted, WireGuard/VPC private link). Python fail startup ở
   profile `prod` nếu `BACKEND_JAVA_URL` là `http://` mà không có
   `INTERNAL_NETWORK_ENCRYPTED=true` (cờ này là cam kết hạ tầng, phải ghi trong
   tài liệu deploy). mTLS thay thế shared secret là hướng nâng cấp, ngoài scope.
   **Nếu deploy lên Kubernetes** (repo hiện chưa có manifest K8s nào — áp dụng
   khi thêm):
   - Service của Java kiểu `ClusterIP` (không `NodePort`/`LoadBalancer`), không
     có Ingress nào route tới `/api/v1/internal/**`.
   - `NetworkPolicy` default-deny ingress cho pod Java; chỉ cho pod có label của
     gateway và agent (gồm Celery worker/beat) vào port 8401.
   - **Không giả định** `INTERNAL_ALLOWED_CIDRS` là pod CIDR. Giá trị đúng là dải
     của source IP mà Java **thực sự nhìn thấy** trong `request.getRemoteAddr()`,
     và nó phụ thuộc hạ tầng:

     | Hạ tầng | `remoteAddr` Java có thể thấy |
     |---------|------------------------------|
     | CNI không SNAT, gọi qua ClusterIP trong cluster | pod IP của caller (pod CIDR) |
     | CNI/kube-proxy có SNAT/masquerade | node IP (node CIDR) |
     | Traffic đi qua egress gateway / NAT | IP của egress gateway |
     | Service mesh có sidecar (Istio, Linkerd...) | `127.0.0.1` hoặc IP sidecar |

   - Quy trình bắt buộc trên staging, **trước** khi điền CIDR production:
     1. Bật log 1 dòng `remoteAddr` cho `/internal/**` (level INFO, không log header
        hay body).
     2. Gọi từ pod agent, Celery worker và Beat → ghi lại `remoteAddr` thực tế.
     3. Đặt `INTERNAL_ALLOWED_CIDRS` hẹp nhất phủ đúng các giá trị đó, ghi lại
        trong tài liệu deploy kèm cấu hình CNI/mesh đã quan sát.
     4. Gọi từ 1 pod không nằm trong NetworkPolicy → bị chặn ở mạng; gọi từ pod
        agent → 200.
   - **Gate chặn deploy** khi CIDR không phân biệt được caller: nếu giá trị quan
     sát được là `127.0.0.1`/IP sidecar, hoặc node CIDR/egress IP dùng chung với pod
     khác, thì **không được deploy** cho tới khi có ít nhất 1 lớp định danh caller
     **đã được chứng minh đang thực thi**:
     - `NetworkPolicy` ingress cho pod Java chỉ cho pod gateway/agent/Celery, **và**
       CNI thực sự enforce NetworkPolicy (Calico, Cilium...; flannel thuần thì
       không) — chứng minh bằng test: pod thử không có label được phép → kết nối
       tới 8401 bị timeout/refused; **hoặc**
     - Mesh mTLS `STRICT` + `AuthorizationPolicy` theo service identity (vd Istio
       principal của service account gateway/agent) chỉ cho phép gọi
       `/api/v1/internal/**` — chứng minh bằng test: workload identity khác → 403
       từ mesh.
     Kết quả 2 test này đính kèm vào checklist deploy; thiếu → release bị chặn.
     Không được thêm `127.0.0.1` hay dải dùng chung vào `INTERNAL_ALLOWED_CIDRS`
     để "cho qua", và không có ngoại lệ "chấp nhận CIDR suy yếu".
9. Redirect ở chiều Python → Java: `BackendJavaClient` gửi `X-Internal-Secret`
   trên mọi request và nhận key plaintext. Client hiện dựa vào mặc định của httpx
   (không truyền `follow_redirects`) — phải đặt **tường minh**
   `follow_redirects=False`, và coi mọi 3xx từ Java là lỗi (không đọc body, không
   gọi `Location`). Nhờ vậy nếu Java/proxy bị cấu hình sai hoặc bị chiếm quyền,
   secret và key không bị chuyển tiếp sang host khác.

## Credential rotation

Sửa field credential của một row đang ACTIVE **không** được đưa row ra khỏi
snapshot trước khi giá trị mới đã verify xong. Downtime khi rotate key là hành vi
**không** được chấp nhận.

- Field credential (`apiKey`, `apiBaseUrl`, `llmModelName`, `llmProvider`,
  `modelSourceRef`) không ghi thẳng vào `chat_models` khi SA sửa. Giá trị mới được
  lưu làm **ứng viên** trong job verify (`chat_model_verifications.candidate_*`,
  key mã hoá bằng cùng `ApiKeyConverter`).
- Hai bộ đếm trên `chat_models`:
  - `revision` — phiên bản credential **đang chạy**; tăng khi ứng viên được promote.
  - `candidate_generation` — tăng **mỗi lần** SA tạo ứng viên mới (sửa credential
    hoặc re-verify). Job lưu `candidate_generation` và `base_revision` tại thời
    điểm tạo.
- Row giữ nguyên `status` và giá trị cũ trong suốt quá trình verify → Python vẫn
  chạy bằng credential cũ.
- Verify OK → promote bằng compare-and-set trong 1 transaction:
  `UPDATE chat_models SET <ứng viên>, revision = revision + 1, verified_at = now() WHERE id = :id AND candidate_generation = :jobGeneration AND revision = :jobBaseRevision`.
  0 row → job `SUPERSEDED`, row không đổi. Kịch bản được chặn: worker A đang
  verify key 1 (generation 5), SA đổi sang key 2 (generation 6) → kết quả OK của A
  không thể promote key 1, dù lease token của A vẫn hợp lệ. Fencing token chống
  worker cũ; `candidate_generation` chống thay đổi credential chồng nhau — cần cả hai.
  Promote thành công thì bump version; row đang ACTIVE vẫn ACTIVE; row PENDING/
  DISABLED chuyển trạng thái theo ma trận "Verify OK".
- Verify FAIL → row không đổi; SA thấy "thay đổi chưa được áp dụng" kèm lỗi.
- SA sửa tiếp khi đang có ứng viên → `candidate_generation += 1`, job cũ
  `SUPERSEDED`, job mới thay thế (cùng transaction).
- Tạo mới: row tạo với field đầy đủ, `status = PENDING`, `revision = 0` (chưa
  từng được verify, không bao giờ vào snapshot), `candidate_generation = 1`; job
  verify có ứng viên trùng giá trị row, `base_revision = 0` — promote đưa
  `revision` lên 1.
- Field không phải credential (`priority`, `maxRpm`, `modelPurpose` không được sửa
  sau khi tạo) áp dụng ngay, không cần verify.

Quy tắc update API key — DTO phải phân biệt được 4 trạng thái, không dùng
`String` nullable (không tách được "vắng mặt" với `null`). Hiện create và `PUT`
dùng chung `ChatModelRequest`; tách ra `ChatModelUpdateRequest` với
`JsonNullable<String> apiKey` (`org.openapitools:jackson-databind-nullable` +
đăng ký `JsonNullableModule` vào `ObjectMapper`):

| JSON | Giá trị DTO | Kết quả |
|------|-------------|---------|
| không có field `apiKey` | `JsonNullable.undefined()` | giữ key cũ |
| `"apiKey": null` | `JsonNullable.of(null)` | giữ key cũ |
| `"apiKey": ""` hoặc toàn khoảng trắng | `JsonNullable.of("")` | 400 validation |
| `"apiKey": "sk-new"` | `JsonNullable.of("sk-new")` | key mới thành ứng viên |

- Xử lý 4 trạng thái ở **1 chỗ duy nhất** (service), không để mapper/converter
  tự đoán.
- Xoá key chỉ bằng `clearApiKey: true`, và chỉ hợp lệ với `SELF_HOSTED`.
- **Đổi host của `apiBaseUrl` mà không nhập lại `apiKey` → 400**
  (`CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST`): không bao giờ gửi key đã lưu tới
  một host mới mà SA chưa xác nhận bằng cách nhập lại key.

## Embedding identity guard

Staged rotation và thao tác activate **không được** làm đổi không gian vector của
EMBEDDING. Nếu không, vector cũ trong Qdrant sẽ bị truy vấn bằng embedding của
model khác: retrieval sai âm thầm (kể cả khi cùng số chiều). Guard áp cho **mọi
đường** có thể đổi embedding đang chạy: promote ứng viên, SA activate/swap, bật
lại sau DISABLED, và cả query embedding lúc chat (`app/rag/retrieval/service.py`),
không chỉ ingest.

**Danh tính của index** — bảng `embedding_index_identity`, **khoá chính
`collection_name`** (không phải singleton toàn cục): `collection_name`, `provider`,
`model_name`, `model_source_ref`, `api_base_url`, `dimension`, `fingerprint`
(vector của 3 câu probe cố định, lưu `real[]`), `established_at`,
`established_by` (`bootstrap-cli` / `first-upsert`). Đây là danh tính của **vector
đang nằm trong collection**, không phải của credential đang ACTIVE — nên vẫn đúng
khi không có credential nào ACTIVE (vd sau khi bị DISABLED).
- Hiện hệ thống chỉ có 1 collection (`QDRANT_COLLECTION = "unisage_chunks"`) và
  không có khái niệm tenant, nên thực tế chỉ có 1 row. Khoá theo collection để
  plan re-index sau này (tạo collection mới) và tenant (nếu có) không phải đổi
  schema. Snapshot gửi danh tính của collection mà Python đang cấu hình.
- **Không bao giờ ghi đè** — bảo đảm ở 3 lớp:
  1. Endpoint PUT chỉ chạy
     `INSERT INTO embedding_index_identity (...) VALUES (...) ON CONFLICT (collection_name) DO NOTHING RETURNING collection_name`;
     0 row → 409 `EMBEDDING_INDEX_IDENTITY_EXISTS`. Không có nhánh UPDATE, không
     "đọc rồi ghi" (tránh race check-then-insert).
  2. Repository không có method update/delete cho entity này (entity
     `@Immutable`).
  3. V18 thêm trigger `BEFORE UPDATE OR DELETE ON embedding_index_identity` raise
     exception — kể cả SQL tay hay bug sau này cũng không sửa được. Plan re-index
     sau này dùng collection mới, không cần xoá row cũ.
- Race khởi tạo lần đầu (collection rỗng, 2 worker cùng upsert đầu tiên, hoặc
  bootstrap CLI chạy song song với worker): cả hai gọi PUT; `ON CONFLICT DO
  NOTHING` trên khoá chính bảo đảm đúng 1 request thắng, request kia nhận 409.
  Bên nhận 409 **đọc lại** danh tính (GET) và so với credential của mình: khớp →
  tiếp tục upsert; lệch → dừng, không upsert vector nào (ingest job FAILED).

Phân loại thay đổi của credential EMBEDDING:

| Thay đổi | Ví dụ | Xử lý |
|----------|-------|-------|
| Chỉ đổi `apiKey` | rotate key cùng tài khoản | Staged rotation bình thường, **nhưng** verify phải đo `dimension` + `fingerprint` của ứng viên; khớp danh tính index (cùng chiều, cosine mỗi probe ≥ 0.999) → được promote; lệch (provider âm thầm đổi model phía sau) → `REINDEX_REQUIRED` |
| Đổi `llmProvider`/`llmModelName`/`modelSourceRef`/`apiBaseUrl`, hoặc `dimension` đo được khác | đổi sang `text-embedding-3-large`, đổi server self-hosted | Verify vẫn chạy (để SA biết ứng viên dùng được) nhưng **không bao giờ tự promote** vào row đang ACTIVE: job kết thúc ở `REINDEX_REQUIRED`, row giữ giá trị cũ và tiếp tục chạy |

- Job status mới `REINDEX_REQUIRED` (trạng thái cuối): ứng viên hợp lệ nhưng cần
  re-embed toàn bộ dữ liệu trước khi dùng. Re-embed/migration collection là
  **plan riêng, ngoài scope**; plan này chỉ chặn và báo rõ.
- Row EMBEDDING **không ACTIVE** (PENDING/INACTIVE/DISABLED) được promote ứng viên
  đổi danh tính bình thường, vì không nằm trong snapshot. Danh tính được kiểm lại
  ở lúc activate.
- **SA activate** EMBEDDING (kể cả swap từ credential khác, và bật lại sau
  DISABLED): Java so danh tính row (provider/model/sourceRef/baseUrl + `dimension`
  + `fingerprint` đo lúc verify gần nhất) với `embedding_index_identity`. Lệch →
  409 `EMBEDDING_REINDEX_REQUIRED` kèm các field khác nhau. Không còn chuyện "UI
  cảnh báo rồi cho đổi".
- Khởi tạo danh tính index:
  - Index hiện có (vector tạo bằng `OPENAI_EMBEDDING_MODEL` trong `.env`) → bước
    rollout bắt buộc ở Task 13: chạy lệnh
    `python -m app.tools.register_embedding_index_identity` **bằng cấu hình `.env`
    cũ** trước khi bật registry cho embedding. Lệnh đo fingerprint, đọc dimension
    của collection Qdrant, rồi
    `PUT /internal/model-registry/embedding-index/{collection}/identity`
    (only-if-absent; đã có → 409).
  - Collection rỗng → credential EMBEDDING đầu tiên được activate xác lập danh tính
    ở lần upsert đầu, **trước** khi ghi vector nào (PUT thành công hoặc 409 +
    danh tính khớp thì mới upsert).
  - Python từ chối embed/query (lỗi rõ ràng, không fallback) khi collection đã có
    vector mà chưa có danh tính.
- Python phòng thủ lớp 2: snapshot kèm `embeddingIndexIdentity`. Khi load
  snapshot, Python so credential EMBEDDING với danh tính đó và với dimension của
  collection Qdrant. Lệch → không dùng credential đó cho cả ingest lẫn retrieval,
  log error + alert. Retrieval trả lỗi rõ thay vì kết quả sai.

## State machine

`BaseEntity.isActive` giữ đúng nghĩa hiện tại: **soft-delete** (`DELETE` /
`POST /recover`). Vòng đời vận hành nằm ở `status` mới. Routing chỉ dùng row
`is_active = true AND status = 'ACTIVE'`.

| Từ \ Sự kiện | Tạo mới | Verify OK† | Verify FAIL (hết retry) | SA activate | SA deactivate | Health PERMANENT (đúng revision) | SA sửa credential* | SA re-verify | Delete | Recover |
|---|---|---|---|---|---|---|---|---|---|---|
| — | PENDING, rev 0 | | | | | | | | | |
| PENDING | | ACTIVE (CHAT/EXTRACTION) · INACTIVE (EMBEDDING) | giữ PENDING | ✗ 409 | INACTIVE | — | giữ PENDING, job mới | job mới | INACTIVE + is_active=false | — |
| ACTIVE | | giữ ACTIVE, áp ứng viên | giữ ACTIVE, ứng viên bị bỏ | — | INACTIVE | DISABLED | **giữ ACTIVE**, job mới | job mới, giữ ACTIVE | INACTIVE + is_active=false | — |
| INACTIVE | | giữ INACTIVE, áp ứng viên | giữ INACTIVE | ACTIVE nếu `verified_at IS NOT NULL`, ngược lại ✗ 409 | — | — | giữ INACTIVE, job mới | job mới | is_active=false | — |
| DISABLED | | ACTIVE (CHAT/EXTRACTION) · INACTIVE (EMBEDDING) | giữ DISABLED | ✗ 409 (phải verify lại) | INACTIVE | — | giữ DISABLED, job mới | job mới | is_active=false | — |
| (is_active=false) | | | | ✗ | ✗ | — | ✗ | ✗ | — | is_active=true, status giữ INACTIVE |

\* "Sửa credential" = đổi `apiKey`, `apiBaseUrl`, `llmModelName`, `llmProvider`,
`modelSourceRef`. Giá trị mới là **ứng viên**, chưa ghi vào row (mục "Credential
rotation"). Đổi `priority`/`maxRpm` áp dụng ngay, không đổi trạng thái.

† Verify OK chép ứng viên vào row, `revision += 1`, `verified_at = now()` — **trừ**
row EMBEDDING đang ACTIVE có ứng viên đổi danh tính: job `REINDEX_REQUIRED`, row
không đổi (mục "Embedding identity guard"). "SA activate" với EMBEDDING còn phải
khớp danh tính index, lệch → 409 `EMBEDDING_REINDEX_REQUIRED`.
Health report mang revision cũ bị bỏ qua, nên không thể DISABLE nhầm row vừa
được rotate.

Ràng buộc DB (migration V18 — tên file thật trong repo, không phải V16 như
dự kiến ban đầu, vì migration khác trên `main` đã chen số V16/V17 trước):
- `CHECK (status IN ('PENDING','ACTIVE','INACTIVE','DISABLED'))`,
  `CHECK (model_purpose IN ('CHAT','EMBEDDING','EXTRACTION'))`.
- `CREATE UNIQUE INDEX ux_chat_models_single_active_embedding ON chat_models (model_purpose) WHERE model_purpose = 'EMBEDDING' AND status = 'ACTIVE' AND is_active = true;`
- `CHECK (NOT (is_active = false AND status = 'ACTIVE'))`.
- Cột `revision integer NOT NULL DEFAULT 0`,
  `candidate_generation integer NOT NULL DEFAULT 0`.
- Unique partial index: tối đa 1 job verify chưa kết thúc (`QUEUED`/`RUNNING`)
  cho mỗi `chat_model_id`.
- Row cũ: `model_purpose = 'CHAT'`; `status = 'ACTIVE'` nếu `is_active`, ngược lại
  `INACTIVE`; `verified_at = now()` cho row ACTIVE (coi như đã chạy thật);
  `revision = 1`.

Xử lý race:
- Mọi chuyển trạng thái đi qua 1 method service dùng compare-and-set
  (`UPDATE chat_models SET status = :to WHERE id = :id AND status = :from`),
  0 row → `ErrorCode.CHAT_MODEL_STATUS_CONFLICT` (409).
- Activate EMBEDDING: kiểm danh tính với `embedding_index_identity` trước (lệch →
  409 `EMBEDDING_REINDEX_REQUIRED`, không đổi gì); khớp thì trong 1 transaction,
  `SELECT ... FOR UPDATE` row EMBEDDING
  đang ACTIVE → set INACTIVE → flush → set row mới ACTIVE. Hai SA activate đồng
  thời: request thua bị unique index chặn → map `DataIntegrityViolationException`
  sang `ErrorCode.EMBEDDING_ACTIVE_CONFLICT` (409), không phải 500.

## Verification lifecycle

Pull-based, chỉ có chiều Python → Java. Java giữ job state trong bảng
`chat_model_verifications`:

| Cột | Ý nghĩa |
|-----|---------|
| `id`, `chat_model_id` (FK) | |
| `status` | 1 trong 7 giá trị ở bảng "Trạng thái verification" bên dưới |
| `embedding_dimension`, `embedding_fingerprint` | Chỉ EMBEDDING: đo lúc verify, dùng cho "Embedding identity guard" |
| `candidate_generation`, `base_revision`, `candidate_*` (key mã hoá) | Ứng viên cần verify và điều kiện CAS khi promote |
| `attempt`, `max_attempts` (mặc định 3) | |
| `next_attempt_at`, `lease_until` | backoff + lease khi Python đang chạy |
| `lease_token` (UUID) | Fencing token, sinh mới ở **mỗi** lần claim |
| `last_result_lease_token` | Token của result gần nhất đã được chấp nhận — dùng cho idempotency |
| `error_type`, `error_code`, `error_message` | kết quả lần thử cuối, UI hiển thị trực tiếp |
| `created_at`, `started_at`, `finished_at` | |

**Trạng thái verification — danh sách chuẩn duy nhất (7 giá trị).** Java enum
`ChatModelVerificationStatus`, CHECK constraint V18, DTO SA-facing
(`latestVerification.status`), type TypeScript của `unisage-web` và mọi chỗ trong
plan/todo phải dùng **đúng** danh sách này:

| # | Giá trị | Cuối? | Ai đặt | Ý nghĩa | Nhãn UI |
|---|---------|-------|--------|---------|---------|
| 1 | `QUEUED` | Không | Java (tạo job, TRANSIENT còn lượt) | Chờ Python claim | Đang chờ xác minh |
| 2 | `RUNNING` | Không | Java (claim) | Python đang verify, có lease | Đang xác minh |
| 3 | `SUCCEEDED` | Có | Java (result OK + promote thành công) | Ứng viên đã áp dụng | Đã xác minh |
| 4 | `FAILED` | Có | Java (PERMANENT hoặc hết lượt) | Ứng viên không dùng được | Xác minh thất bại + lỗi |
| 5 | `SUPERSEDED` | Có | Java (SA sửa/re-verify khi job dở, hoặc CAS promote thất bại) | Bị thay bởi ứng viên mới hơn; không bao giờ promote | Đã bị thay bằng thay đổi mới hơn |
| 6 | `CANCELLED` | Có | Java (soft-delete credential khi job dở) | Huỷ | Đã huỷ |
| 7 | `REINDEX_REQUIRED` | Có | Java (result OK nhưng EMBEDDING đổi danh tính index) | Ứng viên dùng được nhưng cần re-index; không promote | Cần re-index — chưa áp dụng |

- Chỉ Java chuyển trạng thái. Python/Celery **không** đọc hay rẽ nhánh theo 7 giá
  trị này; verifier chỉ xử lý theo HTTP code của `claim`/`result` (200 applied /
  200 duplicate / 409), nên Python không cần bản sao enum.
- Chỉ `RUNNING` được nhận result. Mọi trạng thái cuối (3-7) và `QUEUED` đều rơi
  vào nhánh duplicate/409 ở bước 4.
- Chống lệch: xem mục "Contract files dùng chung" — enum Java là nguồn sự thật,
  `verification-statuses.json` được **sinh** từ enum, còn type TypeScript của web
  được **sinh** từ file đó. Không có bản copy nào sửa tay.

## Contract files dùng chung

Backend, agent và web là 3 repo GitHub riêng (`IUH-UniSage/unisage-backend`,
`unisage-agent`, `unisage-web`), không có monorepo hay OpenAPI (không có springdoc).
Mọi dữ liệu phải khớp giữa các repo đi theo 1 luồng **sinh + kiểm tự động**, không
duy trì bản copy tay:

| Contract | Nguồn sự thật | Repo tiêu thụ | Sinh ra ở phía tiêu thụ |
|----------|---------------|---------------|-------------------------|
| `verification-statuses.json` (7 trạng thái) | Enum Java `ChatModelVerificationStatus` | `unisage-web` | `src/generated/contracts/verification-status.ts` (union type + mảng hằng) |
| `internal-endpoints.json` (7 endpoint) | Handler mapping thực tế của Spring | chỉ backend | — |
| `ssrf-url-vectors.json` | `unisage-backend/contracts/` (viết tay, có review) | `unisage-agent` | dùng trực tiếp làm fixture pytest |
| `redaction-vectors.json` | `unisage-backend/contracts/` (viết tay, có review) | `unisage-agent` | dùng trực tiếp làm fixture pytest |

Cơ chế:
1. **Phía nguồn (backend):** thư mục `unisage-backend/contracts/` được commit.
   File sinh từ code (vd `verification-statuses.json`) do test
   `ContractExportTest` sinh ra; CI chạy test đó rồi `git diff --exit-code contracts/`
   → đổi enum mà quên sinh lại file thì CI đỏ.
2. **Phía tiêu thụ (web, agent):** file `contracts.lock` ghi commit SHA của
   `unisage-backend` đang dùng. Script `contracts:sync` (web: `pnpm contracts:sync`,
   agent: `python -m tools.contracts_sync`) tải đúng các file ở SHA đó (GitHub API
   với `GITHUB_TOKEN` trong CI, `gh` ở máy dev), lưu vào `contracts/vendor/` và sinh
   code (web). File sinh có header `// GENERATED — DO NOT EDIT`.
3. **Kiểm trong CI phía tiêu thụ:**
   - `contracts:check` chạy lại sync ở SHA trong lock → `git diff --exit-code` →
     ai sửa tay file vendored/generated thì CI đỏ.
   - Web: `Record<VerificationStatus, string>` cho bảng nhãn → thiếu giá trị mới là
     lỗi `tsc -b`.
   - Job so SHA trong lock với HEAD `main` của backend: contract ở HEAD khác bản
     đang khoá → CI cảnh báo (PR của backend đổi contract phải kèm PR bump lock ở
     web/agent).
4. Lint chặn sửa tay: `eslint` ignore + pre-commit hook (husky đã có ở web) từ
   chối commit thay đổi `src/generated/**` nếu không đi kèm thay đổi `contracts.lock`.

Luồng:
1. SA tạo/sửa credential/re-verify → trong 1 transaction (khoá row `chat_models`
   trước, rồi mới tới job — cùng thứ tự khoá với bước 4 để tránh deadlock): Java
   ghi job `QUEUED` kèm ứng viên (row tạo mới thì `PENDING`, row có sẵn giữ nguyên
   trạng thái), chuyển job cũ còn dở (`QUEUED`/`RUNNING`) sang **`SUPERSEDED`**,
   publish `verification-requested` sau commit (chỉ để đánh thức, không bắt buộc).
   Soft-delete credential → job dở sang `CANCELLED`.
2. Python verifier (Celery Beat task mỗi 15s, và chạy ngay khi nhận event) gọi
   `claim`: Java chọn job `QUEUED` có `next_attempt_at <= now` hoặc `RUNNING` có
   `lease_until < now` bằng `FOR UPDATE SKIP LOCKED`, set `RUNNING`,
   `lease_until = now + 60s`, `attempt += 1`, **`lease_token = random UUID`**.
   Response: `{ jobId, leaseToken, attempt, leaseUntil, credential: {...ứng viên} }`.
3. Python chạy SSRF guard → 1 request nhỏ qua adapter (timeout 15s, không retry
   trong lần thử) → gửi `result { leaseToken, success, errorType, errorCode, message }`.
4. Java áp result trong **đúng 1 transaction** — chấp nhận result, ghi idempotency
   marker, promote, bump version và phát event cùng thành công hoặc cùng rollback:
   1. Đọc `chat_model_id` của job (không khoá). Khoá row `chat_models` bằng
      `SELECT ... FOR UPDATE`, **rồi** khoá row job bằng `SELECT ... FOR UPDATE`
      (thứ tự khoá cố định: model → job, trùng bước 1).
   2. Kiểm trên row job đã khoá, dùng đồng hồ DB (`now()`, không dùng đồng hồ
      JVM/Python): `status = 'RUNNING' AND lease_token = :leaseToken AND lease_until > now()`.
      Job `QUEUED` và mọi trạng thái cuối (`SUCCEEDED`/`FAILED`/`SUPERSEDED`/
      `CANCELLED`/`REINDEX_REQUIRED`) đều không qua được
      điều kiện `status = 'RUNNING'` → **job SUPERSEDED không bao giờ promote**, kể
      cả khi token đúng và lease còn hạn.
   3. Không qua → phân loại, **không** ghi gì, commit rỗng:
      - `last_result_lease_token = :leaseToken` → result lặp của lần đã được chấp
        nhận → `200 { applied: false, duplicate: true }`. Không tăng revision,
        không bump version, không phát event.
      - Còn lại (token khác, lease hết hạn dù token đúng, job đã
        `SUPERSEDED`/`CANCELLED`) → 409 `VERIFICATION_LEASE_LOST`.
   4. Qua → ghi `last_result_lease_token = :leaseToken` rồi:
      - OK → kiểm tiếp trên row model đã khoá:
        `candidate_generation = job.candidate_generation AND revision = job.base_revision`.
        Với row EMBEDDING đang ACTIVE, kiểm thêm danh tính ứng viên (field +
        `dimension` + `fingerprint` trong result) với `embedding_index_identity`;
        lệch → job `REINDEX_REQUIRED`, row không đổi, không event.
        Đúng → chép ứng viên, `revision += 1`, trạng thái theo cột "Verify OK",
        bump version, đăng ký `ModelRegistryChangedEvent` (chỉ publish sau commit),
        job `SUCCEEDED`. Sai → job `SUPERSEDED`, row không đổi, không event.
      - TRANSIENT và `attempt < max_attempts` → job `QUEUED`, xoá `lease_token`,
        backoff 30s/2m/10m.
      - PERMANENT hoặc hết attempt → job `FAILED`, row không đổi.

   Hai request result giống hệt nhau tới đồng thời: request thứ hai chờ khoá row
   model, tới lượt thì thấy job đã hết `RUNNING` và `last_result_lease_token` khớp →
   `duplicate`. Revision tăng đúng 1 lần, event phát đúng 1 lần.

   Kịch bản được chặn: worker A quá lease, worker B claim lại (token mới), A gửi
   result cũ; worker A quá lease mà chưa ai claim lại — result vẫn bị từ chối, job
   được claim lại ở lượt sau; SA sửa credential khi A đang verify — job của A đã
   `SUPERSEDED`. Python đặt timeout request provider (15s) nhỏ hơn nhiều so với
   lease (60s) để trường hợp quá lease hiếm.
5. Agent down: job nằm ở `QUEUED`; SA response có `latestVerification`, UI báo
   "đang chờ agent xác minh" khi job `QUEUED` quá 5 phút.

`ChatModelResponse` (SA-facing) thêm `status`, `modelPurpose`, `revision`,
`verifiedAt`, `hasPendingChange`,
`latestVerification { status, attempt, errorType, errorCode, errorMessage, finishedAt }`.
Response SA không bao giờ chứa giá trị ứng viên (kể cả `apiBaseUrl` mới chỉ hiện
dạng host).

## Hot-reload consistency

- **Dependency/deploy:** `spring-boot-starter-data-redis`,
  `spring.data.redis.url=${REDIS_URL:redis://localhost:6379/0}`. Không dựng Redis
  thứ 2: `backend-java/docker-compose.yml` truyền `REDIS_URL` trỏ Redis của
  `unisage-agent` (`.devcontainer/docker-compose.yml`), `dev-onboard.md` cập nhật.
  Redis down không được làm hỏng thao tác ghi của SA — chỉ log + Actuator health
  `DOWN` cho Redis.
- **Tách không gian Redis:** hiện Celery broker, result backend và event
  publisher của Python đều dùng chung `REDIS_URL` (DB 0). Tách thành
  `CELERY_BROKER_URL` (DB 1), `CELERY_RESULT_BACKEND` (DB 2), `REDIS_URL` cho
  registry/circuit breaker/lock (DB 0, key prefix `mr:`). Tên queue Celery và tên
  channel pub/sub cấu hình qua env (`CELERY_QUEUE_PREFIX`,
  `MODEL_REGISTRY_CHANNEL`) — pub/sub của Redis không tách theo DB nên phải tách
  bằng tên. Harness đặt prefix riêng mỗi lần chạy, và chỉ purge đúng queue của nó
  (`celery purge -Q <queue>`), không bao giờ purge toàn broker.
- **Version:** bảng singleton `model_registry_version (id = 1, version bigint)`.
  Mọi thay đổi ảnh hưởng snapshot chạy
  `UPDATE model_registry_version SET version = version + 1 WHERE id = 1 RETURNING version`
  trong **cùng transaction** — row lock tuần tự hoá các writer nên version tăng
  đơn điệu, không phụ thuộc timestamp.
- **Publish after commit:** service phát `ModelRegistryChangedEvent(version)`,
  listener `@TransactionalEventListener(phase = AFTER_COMMIT)` publish
  `{"version": N}` lên channel `model-registry:updates`. Rollback → không publish.
- **Python:** mỗi process (gunicorn worker **và** Celery worker) giữ 1 snapshot +
  `version`. Nhận message/poll (`/version` mỗi 30s) → nếu `N > current` thì gọi
  `/snapshot`, build router mới, swap reference. Message có `N <= current` bị bỏ
  qua (chống out-of-order). Reload lỗi → giữ snapshot cũ, thử lại lần poll sau.

## SSE error contract

Hiện `_sse_token_generator` chỉ phát `token` và `done`; lỗi graph được
`run_and_persist` ghi `MsgStatus.ERROR` nhưng client không biết. Bổ sung:

```
event: error
data: {"code": "LLM_STREAM_INTERRUPTED", "message": "...", "retryable": true}

event: done
data: {}
```

- Queue đổi từ `str | None` sang item có kiểu (token / error / end); `done` luôn là
  event cuối, `error` (nếu có) đứng ngay trước `done`.
- Mã lỗi: `LLM_STREAM_INTERRUPTED` (lỗi sau chunk đầu), `LLM_UNAVAILABLE` (hết
  credential trước chunk đầu), `SYSTEM_BUDGET_EXHAUSTED` (dành cho Task 20).
- `unisage-web` xử lý `event: error` (hiện thông báo, giữ phần text đã nhận, đánh
  dấu message lỗi) — nằm trong Task 11.

## Secret redaction

API key có thể lọt qua đường phụ: message của exception provider (SDK đôi khi
kèm header/body request), `error_message` lưu DB, log, traceback, Celery result
backend, Slack, response SA. Quy tắc:

- Redactor dùng chung ở cả 2 phía (`SecretRedactor` Java, `app/core/redaction.py`
  Python), chạy **trước** khi lưu DB, ghi log, trả UI, gửi Slack, gửi health/result
  về Java:
  - thay **chính xác** chuỗi key của credential đang xử lý (và mọi chuỗi con ≥ 8
    ký tự của nó) bằng `[REDACTED]` — lớp mạnh nhất, không phụ thuộc định dạng key;
  - pattern chung: `Authorization: ...`, `Bearer ...`, `x-api-key`, `api-key`,
    `sk-...`, `sk-ant-...`, query `?key=`/`api_key=`/`token=`, userinfo trong URL;
  - cắt tối đa 500 ký tự sau khi redact.
- Không bao giờ lưu/gửi `str(exc)` hay `repr(exc)` thô; message lỗi dựng từ
  `errorCode` + text đã redact. Không log request body/headers của lời gọi provider.
- Logging: filter redaction gắn vào root logger ở Python (áp cho cả phần
  traceback của `exc_info`) và vào pattern layout ở Java.
- Celery: verifier task `ignore_result=True` (+ `store_errors_even_if_ignored=False`),
  không nhận argument chứa credential — task tự gọi `claim`, claim response chỉ sống
  trong biến cục bộ, không bao giờ đi qua broker hay result backend. Task khác của
  feature này cũng không nhận credential làm argument.
- Java redact lại `error_message`/`lastErrorMessage` trước khi lưu (defense in
  depth dù Python đã redact).
- Nghiệm thu bằng **canary**: seed key dạng `sk-canary-<uuid>`, fake provider có
  chế độ echo `Authorization` header + body vào error message; sau các kịch bản
  quét DB (cột text, trừ cột ciphertext), mọi DB Redis (gồm result backend), log
  tất cả container, response SA đã ghi lại, payload Slack giả → không được thấy
  canary.

## SSRF policy

- Kiểm cú pháp URL **trước** mọi kiểm IP/DNS; Java và Python dùng chung 1 file
  test vector (`ssrf-url-vectors.json`, nguồn ở `unisage-backend/contracts/`, agent
  sync theo mục "Contract files dùng chung") để không có lệch parser:
  - scheme chỉ `https` (và `http` cho `SELF_HOSTED` host trong allowlist); mọi
    scheme khác → reject;
  - có userinfo (`user:pass@host`, kể cả `@host` rỗng) → reject;
  - có query (`?`) hoặc fragment (`#`) → reject — base URL không cần;
  - port ngoài `1-65535`, port rỗng (`host:`), hoặc port không phải số → reject;
  - ký tự control (`\x00-\x1f`, `\x7f`), khoảng trắng, `\` ở bất kỳ đâu → reject;
  - host rỗng; host non-ASCII phải chuyển IDNA hợp lệ sang A-label trước khi kiểm,
    IDNA lỗi → reject; bỏ dấu `.` cuối host trước khi so allowlist;
  - độ dài URL > 2048 → reject.
- Scheme: `CLOUD_API` chỉ `https`; `SELF_HOSTED` cho `http` chỉ khi host khớp
  allowlist.
- Chặn sau khi resolve **tất cả** A/AAAA: loopback (`127.0.0.0/8`, `::1`),
  RFC1918, link-local (`169.254.0.0/16`, `fe80::/10`), CGNAT `100.64.0.0/10`,
  `0.0.0.0/8`, multicast, ULA `fc00::/7`, IPv4-mapped IPv6 (`::ffff:0:0/96` —
  unwrap rồi kiểm lại), hostname metadata (`metadata.google.internal`...). Parse
  IP bằng thư viện chuẩn (`InetAddress`/`ipaddress`), không regex — chặn cả dạng
  decimal/octal/hex.
- Allowlist cho self-hosted nội bộ: biến môi trường
  `MODEL_REGISTRY_URL_ALLOWLIST` (host hoặc CIDR), **không** lưu DB, không sửa qua
  UI → SA không tự nới được. Production mặc định rỗng. Dev/test đặt
  `localhost,127.0.0.1,host.docker.internal` trong `.env`/profile test (test Java
  hiện có dùng `http://localhost:8000/v1`).
- DNS rebinding: Java validate lúc lưu chỉ là lớp chặn sớm. Lớp thực thi là Python
  **tại thời điểm kết nối, ở socket layer**: custom `httpcore.AsyncNetworkBackend`
  (bọc backend mặc định) override `connect_tcp(host, port)` → resolve 1 lần, kiểm
  mọi IP, mở socket tới đúng IP đã kiểm. URL request **không** bị đổi sang IP, nên:
  - Header `Host` vẫn là hostname gốc (provider/virtual host route đúng).
  - TLS SNI và kiểm hostname của certificate vẫn theo hostname gốc (httpcore
    truyền `server_hostname` riêng khi `start_tls`).
  - Certificate validation **luôn bật**: không bao giờ `verify=False`, không
    `CERT_NONE`, không `check_hostname=False`. SSL context tạo tường minh từ
    `certifi` (không phụ thuộc `SSL_CERT_FILE` của môi trường); test harness chỉ
    được **thêm** CA test vào context, không được tắt kiểm.
  Kiểm ở tầng URL/transport phía trên là không đủ vì resolve lần 2 ở socket có
  thể trả IP khác.
- `httpcore` là chi tiết triển khai (API `AsyncNetworkBackend`/`connect_tcp` không
  phải public contract ổn định của httpx) → pin **tường minh** trong
  `pyproject.toml`: khai báo `httpcore` là dependency trực tiếp với dải hẹp (vd
  `httpcore>=1.0.x,<1.1`) và siết `httpx` (hiện chỉ `>=0.27.0`) về dải minor đã
  test. Test "backend giả đếm `connect_tcp`" và test DNS rebinding là guard khi
  nâng version: nâng mà hook không còn được gọi → test fail.
- Không proxy ngoài ý muốn: mọi client gọi provider tạo với `trust_env=False` và
  không truyền `proxy=`/`mounts=` → bỏ qua `HTTP(S)_PROXY`/`ALL_PROXY`/`NO_PROXY`/
  `.netrc`/`SSL_CERT_FILE` từ môi trường (proxy sẽ nhận hostname và tự resolve, vô
  hiệu hoá DNS pinning). Cần proxy egress thì cấu hình tường minh trong factory và
  review riêng.
- DNS rebinding phải có **test thật**, không chỉ mock resolver: harness chạy 1 DNS
  server test (vd `dnslib`) trả IP public ở lần hỏi đầu và `127.0.0.1`/IP nội bộ
  ở lần sau, TTL 0; container agent dùng DNS server đó (`dns:` trong compose) để
  resolver hệ điều hành thật được dùng. Kiểm: socket chỉ mở tới IP đã được kiểm,
  service nội bộ đích nhận 0 kết nối.
- Redirect **bị từ chối**, không chỉ "không đi theo": `follow_redirects=False`, và
  mọi response 3xx từ provider bị coi là lỗi `PROVIDER_REDIRECT_REJECTED`
  (PERMANENT với credential đó). SDK `openai` bật follow-redirect mặc định — client
  do ta tạo ghi đè hành vi này.
- **Một factory duy nhất** `app/core/llm/http_client.py::build_provider_http_client(credential)`
  là nơi duy nhất tạo HTTP client gọi provider. Mọi đường gọi provider phải dùng nó:
  - Provider native PydanticAI theo từng `llmProvider` (`OpenAIProvider`,
    `AnthropicProvider`, provider OpenAI-compatible cho `SELF_HOSTED`) — quyết định
    ADR 0005, truyền qua `http_client=` của từng `Provider` class;
  - Embedding (`openai_embedder.py`, hiện `OpenAI(api_key=...)` tự tạo client);
  - Multi-representation (`multi_representation.py`, hiện tương tự);
  - Verifier (Task 6).
  Một test kiến trúc quét `app/` và fail nếu thấy `OpenAI(`, `AsyncOpenAI(`,
  `httpx.Client(`, `httpx.AsyncClient(` hoặc `litellm.*completion(`/`embedding(`
  không truyền client ở ngoài factory/adapter.
- Provider không inject được client/transport tuỳ biến thì **bị loại khỏi
  registry**, không phải "fallback về client mặc định":
  - Java giữ danh sách `llmProvider` được hỗ trợ (`SUPPORTED_LLM_PROVIDERS`, chốt
    theo kết quả spike Task 0.2); tạo/sửa với provider ngoài danh sách → 400.
  - Python khi build snapshot bỏ qua credential có provider không có trong
    registry transport của mình, log error và báo health PERMANENT
    (`PROVIDER_TRANSPORT_UNSUPPORTED`). Không bao giờ gọi URL đó bằng client mặc định.
- Áp dụng cho **mọi** đường gọi provider, kể cả phương án dự phòng native
  PydanticAI của Task 0.2 — decision gate coi "không inject được" là FAIL, không
  phải ngoại lệ chấp nhận được.

## Task List

### Phase 0: Quyết định & ADR
- [x] Task 0: Viết ADR cho Dynamic Model Registry — `unisage-backend/docs/adr/0005-dynamic-model-registry.md`

### Phase 0.5: Gỡ blocker trước khi code tính năng
- [ ] Task 0.1: Internal API security flow `/internal/**` (Java + gateway)
- [x] Task 0.2: Spike adapter LiteLLM → PydanticAI (streaming, structured output,
      usage, custom transport) — kết luận: bỏ LiteLLM, dùng model native theo
      provider (ADR 0005)
- [ ] Task 0.3: State machine + ràng buộc DB (chốt thiết kế, test matrix)
- [ ] Task 0.4: Redis cho Java: dependency, config, deploy, version table,
      publish-after-commit
- [ ] Task 0.5: Khung test tích hợp cross-repo (Java + Python ×2 worker + Celery +
      Redis + fake provider)
- [ ] Task 0.6: SSRF guard 2 lớp (chuyển lên từ Phase 7)
- [ ] Task 0.7: Secret redaction (Java + Python) + hạ tầng test canary
- [ ] Task 0.8: Contract files dùng chung + công cụ sync (không bản copy tay)

### Checkpoint: Phase 0.5
- [ ] Request `/internal/**` có secret, không JWT → 200; không secret → 403; JWT
      SA không secret → 403; IP ngoài `INTERNAL_ALLOWED_CIDRS` → 403; CIDR rỗng →
      403 (fail-closed); `X-Forwarded-For` giả IP hợp lệ không đổi được kết quả
- [ ] Gateway: unit test `InternalPathBlockFilter` + test integration
      `WebTestClient` với context thật chứng minh filter chạy **trước**
      `AuthenticationFilter` (thiếu JWT → 404, không phải 401) và backend giả nhận
      **0** request, cho mọi biến thể encode/normalize
- [ ] Profile `prod` fail startup với secret mặc định (Java + Python + gateway),
      và Python fail với `BACKEND_JAVA_URL=http://` khi chưa khai báo mạng mã hoá;
      manifest production không publish port Java
- [ ] Python so sánh secret bằng `hmac.compare_digest`
- [ ] Spike có kết luận ghi trong ADR; phương án được chọn (kể cả dự phòng) đã
      chứng minh inject được transport SSRF
- [ ] Java start được với Redis; publish chỉ xảy ra sau commit (test rollback)
- [ ] Khung test cross-repo (có Celery Beat + seed ChatModel trỏ fake provider)
      chạy xanh với **smoke hạ tầng** — chỉ chứng minh các service lên và nói
      chuyện được với nhau; vòng đời purpose/status/revision được nghiệm thu ở
      "Checkpoint: Registry lifecycle" sau Task 1-3 + phần Java của Task 4 và 6
- [ ] Reset giữa các test không race với Beat/worker (dừng Beat, purge đúng queue
      của harness, đợi task đang chạy xong rồi mới reset); Redis tách DB/queue/channel
- [ ] Result verify lặp (kể cả đồng thời) → revision/version/event đúng 1 lần; job
      `SUPERSEDED` không promote dù token/lease hợp lệ
- [ ] Gateway test gửi đúng raw path; CIDR filter không đọc header forward
- [ ] SSRF: Host/SNI gốc, cert validation bật, DNS rebinding thật, `httpcore` đã
      pin; bộ test vector cú pháp URL chung xanh ở cả Java và Python
- [ ] Redactor Java + Python có test đơn vị; verifier task `ignore_result=True`,
      không nhận credential làm argument
- [ ] pytest integration chạy trong service `test-runner` cùng network; endpoint
      reset không tồn tại khi không có profile `integration`
- [ ] SSRF guard pass bộ test IPv4/IPv6/rebinding (socket layer)/redirect/
      `trust_env` ở cả 2 repo; test kiến trúc "chỉ factory được tạo HTTP client
      provider" xanh
- [ ] Thiết kế fencing token (token + hạn lease + idempotent), staged rotation
      (`candidate_generation`), `credentialRevision` đã review và có test skeleton
- [ ] Review với human

### Checkpoint: Registry lifecycle (sau Task 1-3 + phần Java của Task 4 và Task 6)
- [ ] Seeder mở rộng theo schema mới; harness gọi thẳng internal API của Java
      (đóng vai Python) và pass: stale lease (token đúng nhưng hết hạn → 409),
      result lặp → `duplicate`, rotation race (generation cũ → `SUPERSEDED`), stale
      health report (`applied: false`), snapshot không chứa key của row
      PENDING/ứng viên
- [ ] Test canary: không thấy key plaintext trong DB (cột text), Redis (mọi DB,
      gồm Celery result backend), log mọi container, response SA, payload Slack giả
      — kể cả khi fake provider echo header/body vào lỗi
- [ ] Update API key 4 trạng thái (vắng / `null` / `""` / key mới) đúng bảng
- [ ] Embedding identity guard: đổi model/provider/baseUrl của EMBEDDING đang
      ACTIVE → job `REINDEX_REQUIRED`, snapshot không đổi; đổi key với fingerprint
      lệch → `REINDEX_REQUIRED`; đổi key với fingerprint khớp → promote; activate
      EMBEDDING khác danh tính index → 409

### Phase 1: Java — Mở rộng Model Registry
- [ ] Task 1: `modelPurpose`, `status`, `verifiedAt`, bảng verification + version,
      constraint (V18)
- [ ] Task 2: `POST /internal/model-registry/credentials/{id}/health`
- [ ] Task 3: API SA: activate/deactivate/re-verify/priority theo state machine

### Checkpoint: Phase 1
- [ ] `./mvnw test` pass (gồm test matrix chuyển trạng thái + race embedding)
- [ ] `GET /chat-models?modelPurpose=CHAT&status=ACTIVE` (SA) trả đúng thứ tự priority
- [ ] Review với human trước khi đụng Python

### Phase 2: Python — Đọc registry thay vì .env
- [ ] Task 4: Client `/internal/model-registry/snapshot` + cache snapshot
- [ ] Task 5: `get_graph_models()` build model native theo `llmProvider` từ
      snapshot (ADR 0005, chưa failover)
- [ ] Task 6: Verify-before-active theo job lifecycle pull-based

### Checkpoint: Phase 2
- [ ] SA tạo 1 ChatModel mới → job verify tự chạy → ACTIVE → Python dùng nó cho 1
      lần chat (chấp nhận restart Python 1 lần)
- [ ] Agent tắt khi SA tạo credential → job ở QUEUED, bật agent lên → tự verify
- [ ] Tests pass ở cả 2 repo + kịch bản cross-repo tương ứng
- [ ] `OPENAI_API_KEY`/`OPENAI_MODEL`/`OPENAI_EMBEDDING_MODEL`/`MULTI_REP_LLM_MODEL`
      đã xoá khỏi `.env.example`, `docker-compose`, deployment; test kiến trúc xác
      nhận không route/Celery task nào đọc các biến này qua `Settings`, và không có
      fallback về OpenAI khi registry rỗng/lỗi (mục "Cutover khỏi cấu hình `.env`
      tĩnh")

### Phase 3: Hot reload không cần restart
- [ ] Task 7: Java tăng version + publish sau commit cho mọi thay đổi registry
- [ ] Task 8: Python (gunicorn + Celery) subscribe + poll, atomic swap snapshot

### Checkpoint: Phase 3
- [ ] SA đổi model active → trong vài giây request mới dùng model mới; request
      đang chạy dùng config cũ
- [ ] Test cross-repo với `gunicorn -w 2` + 1 Celery worker: cả 3 process đồng bộ;
      tắt pub/sub → poll vẫn đồng bộ trong ≤ 30s

### Phase 4: Python — Failover cho Chat & Extraction
- [ ] Task 9: Phân loại lỗi provider permanent vs transient
- [ ] Task 10: Circuit breaker + cooldown theo credential, fallback theo priority
- [ ] Task 11: Streaming — fallback chỉ trước chunk đầu; SSE `event: error`
- [ ] Task 12: Circuit breaker + failover cho Extraction

### Checkpoint: Phase 4
- [ ] Credential Chat chính hết credit → request mới tự chuyển credential dự phòng
- [ ] 429 có `Retry-After` → cooldown đúng thời gian, không bị loại vĩnh viễn
- [ ] Lỗi sau chunk đầu → client nhận `event: error` rồi `done`, không trộn 2 model

### Phase 5: Python — Embedding (không failover)
- [ ] Task 13: Embedding dùng đúng 1 credential active; lỗi provider → Celery task
      FAILED (bỏ hành vi nuốt lỗi từng chunk cho lỗi provider)

### Checkpoint: Phase 5
- [ ] Vô hiệu hoá credential embedding đang active → ingest job mới FAILED với lý
      do rõ ràng, không tự đổi model, không có chunk nào được ghi nửa vời

### Phase 6: Slack alert
- [ ] Task 14: Slack Incoming Webhook client trong Python
- [ ] Task 15: Bắn alert khi hết credit, key thu hồi, hết fallback — không bắn cho
      lỗi transient tự phục hồi

### Checkpoint: Phase 6
- [ ] Lỗi permanent → đúng 1 message Slack
- [ ] Lỗi transient tự phục hồi → không có message nào

### Phase 7: Bảo mật Custom URL
- Task 16: đã chuyển lên Task 0.6 (giữ số để không lệch tham chiếu)

### Phase 8: unisage-web — Trang quản lý
- [ ] Task 17: Trang admin theo purpose, credential, status + lý do verify,
      activate/deactivate/re-verify, priority

### Phase 9: Routing Policy nâng cao (làm sau Cost Tracking)
- [ ] Task 18: `routingPolicy` cấp modelPurpose + điều kiện bắt buộc trong Java
- [ ] Task 19: Python áp policy khi chọn credential trong `model_router`
- [ ] Task 20: Ngân sách toàn hệ thống hết → router từ chối request

### Checkpoint: Phase 9
- [ ] `LOWEST_COST` → ưu tiên credential cost/1K token thấp nhất thoả điều kiện
- [ ] `QUALITY_FIRST` → ưu tiên latency thấp nhất, bỏ qua cost
- [ ] Ngân sách hệ thống = 0 → từ chối, không có lệnh gọi provider nào

### Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ acceptance criteria ở mục Success trong intent đã xác nhận đều pass
- [ ] SA tự thêm key mới, thấy PENDING → ACTIVE (hoặc lý do lỗi), Chat dùng key
      mới — toàn bộ qua UI
- [ ] Bộ test cross-repo chạy xanh toàn bộ kịch bản
- [ ] Cả 8 nhóm trong "Gate: implementation approved" xanh, có link CI/report
- [ ] Ready for review

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| ~~Adapter LiteLLM → PydanticAI không giữ được streaming delta / structured output / usage~~ | — | **Đã gỡ**: spike Task 0.2 chạy thật, chốt dùng model native theo provider thay vì adapter LiteLLM (ADR 0005) |
| API key plaintext lộ qua log/response SA | High (bảo mật) | DTO nội bộ tách package, `toString` che key, `no-store`, test controller SA không bao giờ có field `apiKey` |
| `/internal/**` lộ qua gateway | High (bảo mật) | `InternalPathBlockFilter` chạy trước auth + Java yêu cầu secret; test tự động cả 2 lớp |
| Gọi thẳng port Java bỏ qua gateway để lấy snapshot (plaintext key) | High (bảo mật) | Không publish port ở production, `INTERNAL_ALLOWED_CIDRS`, secret mạnh bắt buộc |
| Result verify cũ ghi đè kết quả mới sau khi lease hết hạn | Medium | `leaseToken` + `lease_until > now()` (đồng hồ DB) trong CAS |
| Rotate key gây gián đoạn Chat | High | Staged rotation: giá trị cũ chạy tới khi giá trị mới verify OK |
| Verify OK của ứng viên cũ promote đè ứng viên mới | High | CAS `candidate_generation` + `base_revision` |
| Key plaintext bị nghe lén trên đường Python ↔ Java | High (bảo mật) | TLS/mTLS hoặc mạng private mã hoá; Python fail startup `prod` nếu `http://` không khai báo |
| Proxy môi trường hoặc provider SDK tự tạo client bỏ qua SSRF guard | High (bảo mật) | `trust_env=False`, factory duy nhất + test kiến trúc |
| Health report từ snapshot cũ DISABLE nhầm credential vừa rotate | Medium | `credentialRevision` trong report; key circuit breaker gắn revision |
| Key đã lưu bị gửi tới host mới do SA (hoặc tài khoản SA bị chiếm) đổi base URL | High (bảo mật) | Đổi host bắt buộc nhập lại key |
| Exception taxonomy của từng provider SDK (OpenAI, Anthropic...) không khớp permanent/transient | High — failover sai, alert spam | Task 9 kiểm `status_code`/loại exception thực tế từng provider SDK, test từng case |
| Worker lệch snapshot khi mất pub/sub hoặc Redis down | Medium | Version trong DB + poll 30s; publish best-effort |
| Race khi 2 SA activate embedding cùng lúc | Medium | Unique partial index + `FOR UPDATE` + map lỗi 409 |
| SSRF qua Custom Base URL / DNS rebinding / redirect | High (bảo mật) | Task 0.6: guard 2 lớp, pin IP lúc kết nối, tắt redirect, allowlist qua env |
| Đổi cách gọi LLM ảnh hưởng luồng Chat production | High | Checkpoint tăng dần; feature flag `MODEL_REGISTRY_ENABLED` cho phép quay về `.env` trong lúc rollout |
| Slack webhook URL bị commit nhầm | Medium | Env var, `.env.example` chỉ placeholder |

## Open Questions

Không còn câu hỏi mở. Các câu đã chốt:

- **Rule validate theo `modelPurpose`** (chốt 25-09-2026): **không ràng buộc
  thêm**. Cả CHAT/EMBEDDING/EXTRACTION đều được dùng `CLOUD_API` hoặc
  `SELF_HOSTED`; `validateBySourceType` giữ rule theo `sourceType` như hiện tại
  (chỉ tách create/update cho tri-state API key — Task 3). Lý do: endpoint sai đã
  bị chặn bởi SSRF allowlist + verify-before-active; embedding tự host vẫn tuân
  "chỉ 1 ACTIVE + không auto-failover".
- **`INTERNAL_ALLOWED_CIDRS` và `MODEL_REGISTRY_URL_ALLOWLIST` cho staging/
  production** (chốt 25-09-2026): plan **không** ghi giá trị cụ thể; team hạ tầng
  điền vào env lúc deploy. Plan chỉ ràng buộc quy tắc:
  - `INTERNAL_ALLOWED_CIDRS` fail-closed: rỗng/parse lỗi → chặn toàn bộ
    `/internal/**`; profile `prod` bắt buộc có giá trị, thiếu → fail startup.
  - `MODEL_REGISTRY_URL_ALLOWLIST` mặc định rỗng (không self-hosted nội bộ nào
    được phép) cho tới khi hạ tầng điền.
  - Checklist deploy trong `dev-onboard.md` mục production liệt kê 2 biến này là
    bắt buộc điền, kèm ví dụ định dạng (`10.0.1.0/24,10.0.2.15/32`;
    `llm.internal.example,10.0.5.0/28`) nhưng không phải giá trị thật.
