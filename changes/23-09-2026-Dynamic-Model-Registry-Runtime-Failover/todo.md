# Todo: Dynamic Model Registry + Runtime Active Switch + Failover

Xem `plan.md` trong cùng thư mục để biết bối cảnh, architecture decisions, contract
chi tiết (Internal API, State machine, Verification lifecycle, Hot-reload
consistency, SSE error contract, SSRF policy) và risk.

Thứ tự bắt buộc: **Phase 0.5 → Task 0 (ADR) → Phase 1-6 (MVP)**. Phase 0.5 là điều
kiện approve plan. SSRF (Task 0.6) và redaction (Task 0.7) phải merge trước Task 5 — lần đầu Python gọi URL
lấy từ registry. Phase 8 (UI) làm song song với Phase 4-6 sau khi API Phase 1/3 ổn
định. Số phase/task cũ giữ nguyên vì plan Cost Tracking tham chiếu.

---

## Phase 0: Quyết định & ADR

### Task 0: Viết ADR cho Dynamic Model Registry

**Description:** Ghi lại quyết định kiến trúc: cách Python gọi provider (kết quả
spike Task 0.2), namespace `/internal/**` + Python không expose endpoint nội bộ,
verify pull-based, Embedding không auto-failover, Redis chỉ là tín hiệu +
version trong DB, circuit breaker ở cấp credential, SSRF là gate.

**Acceptance criteria:**
- [x] File mới `unisage-backend/docs/adr/0005-dynamic-model-registry.md` (copy từ
      `0000-template.md`; số lớn nhất hiện có là 0004)
- [x] Nêu 2 phương án đã cân nhắc cho mỗi quyết định lớn (SDK vs Proxy; adapter vs
      thay graph layer; push verify vs pull verify) và lý do chọn
- [x] Ghi kết luận thực tế của spike Task 0.2: LiteLLM SDK không qua được tiêu chí
      inject transport SSRF, chuyển sang model native PydanticAI theo provider
- [x] Nêu constraint "Embedding không auto-failover" và lý do (vector space)

**Verification:**
- [x] Manual check: người chưa tham gia thảo luận đọc ADR hiểu được "tại sao"

**Dependencies:** Task 0.2

**Files likely touched:**
- `unisage-backend/docs/adr/0005-dynamic-model-registry.md`

**Estimated scope:** XS (1 file)

---

## Phase 0.5: Gỡ blocker trước khi code tính năng

### Task 0.1: Internal API security flow `/internal/**`

**Description:** Hiện `InternalSecretFilter` chỉ gắn attribute
`TRUSTED_INTERNAL_CALLER_ATTRIBUTE` khi secret đúng, còn `DynamicAuthorizationManager`
vẫn đòi public path hoặc RBAC → Python gọi endpoint nội bộ mới sẽ bị 403. Task này
dựng đường ống bảo mật cho cả namespace (theo mục "Internal API contract" trong
plan.md), chưa implement endpoint nghiệp vụ.

**Acceptance criteria:**
- [x] `InternalSecretFilter.INTERNAL_ONLY_PATHS` thêm `("*", "/internal/**")`
- [x] `DynamicAuthorizationManager`: path `/internal/**` + attribute `TRUE` →
      grant, không cần `Authentication`; path `/internal/**` thiếu attribute →
      deny, kể cả khi có JWT SA hợp lệ
- [x] `/internal/**` **không** được thêm vào `PredefinedPublicPaths` hay
      `PredefinedPermissions`
- [x] 1 endpoint ping `GET /internal/model-registry/version` (trả version từ Task
      0.4, tạm trả `0` nếu Task 0.4 chưa xong) để test end-to-end
- [x] `InternalCallerCidrFilter` kiểm `/internal/**` bằng **chỉ**
      `request.getRemoteAddr()` với `INTERNAL_ALLOWED_CIDRS`, **fail-closed**: danh
      sách rỗng hoặc parse lỗi → từ chối mọi request `/internal/**` (log error 1 lần
      lúc startup); dev đặt giá trị tường minh trong `.env`/`.ENV` example
- [x] Filter **không đọc** `X-Forwarded-For`, `Forwarded`, `X-Real-IP` hay header
      forward nào, không gọi lại `MessageController#extractClientIp`; thêm
      `server.forward-headers-strategy=NONE` tường minh làm lớp thứ 2
- [x] So sánh secret constant-time: `MessageDigest.isEqual` (Java,
      `InternalSecretFilter.isValidSecret`), `hmac.compare_digest` (Python — thay `!=`
      ở `app/core/security.py` và `app/api/v1/ingestion.py`)
- [x] Java, Python **và api-gateway** fail startup ở profile `prod` nếu
      `INTERNAL_SECRET_KEY` bằng giá trị mặc định hardcode hoặc < 32 ký tự
- [x] Python fail startup ở profile `prod` nếu `BACKEND_JAVA_URL` là `http://` và
      `INTERNAL_NETWORK_ENCRYPTED` khác `true`; tài liệu deploy nêu rõ yêu cầu TLS/
      mTLS hoặc private network mã hoá cho đường Python ↔ Java
- [x] `api-gateway`: `InternalPathBlockFilter` (`GlobalFilter`, order
      `Ordered.HIGHEST_PRECEDENCE`, chạy trước `AuthenticationFilter` order `-1`)
      trả 404 cho `/api/v1/master/internal/**` và `/api/v1/ai/internal/**`. Không
      dùng route chặn (route chạy sau auth → trả 401 khi thiếu JWT)
- [x] Filter đọc `getURI().getRawPath()`, không chỉ `getPath()`: raw path dưới
      `/api/v1/master/` hoặc `/api/v1/ai/` chứa `%2F`, `%5C`, `%2E`, `%25`, `\`, `;`
      → 404 ngay; phần còn lại decode lặp tới ổn định (≤ 3 lần), gộp `//`, resolve
      `.`/`..`, lowercase rồi mới match (plan.md, Security flow bước 3)
- [x] Deploy production: `backend-java/docker-compose.yml` hiện publish
      `8401:8401` — giữ cho dev, thêm override/manifest production chỉ `expose`
      trong network nội bộ; ghi vào `dev-onboard.md` mục production
- [x] Manifest production đặt gateway, Java, agent vào cùng network nội bộ và
      dùng tên service thay cho `host.docker.internal` (mặc định hiện tại ở
      `api-gateway/docker-compose.yml`): `JAVA_BACKEND_URI=http://backend-java:8401`,
      `PYTHON_AI_URI=http://unisage-agent:8402`, agent
      `BACKEND_JAVA_BASE_URL=http://backend-java:8401/api/v1` (hoặc `https://`)
- [x] Gateway và agent fail startup ở profile `prod` nếu URI upstream có host
      `host.docker.internal`/`localhost`/`127.0.0.1`
- [x] `dev-onboard.md` mục production có tiểu mục Kubernetes (repo chưa có
      manifest K8s — áp dụng khi thêm): Service Java `ClusterIP`, không Ingress tới
      `/api/v1/internal/**`, `NetworkPolicy` default-deny chỉ cho pod gateway/agent/
      Celery vào 8401. `INTERNAL_ALLOWED_CIDRS` **không** mặc định là pod CIDR: ghi
      bảng "hạ tầng → `remoteAddr` Java thấy" (pod IP / node IP khi SNAT / egress
      gateway / `127.0.0.1` khi có sidecar) và quy trình 4 bước trên staging của
      plan.md (log `remoteAddr` thực tế từ agent/worker/beat → điền CIDR hẹp nhất →
      ghi lại cấu hình CNI/mesh → kiểm pod ngoài policy bị chặn). Nếu quan sát ra
      `127.0.0.1`/IP sidecar/node CIDR/egress IP dùng chung → **gate chặn deploy**
      (plan.md bước 4): bắt buộc NetworkPolicy đã chứng minh được CNI enforce, hoặc
      mesh mTLS `STRICT` + `AuthorizationPolicy` theo service identity; đính kèm kết
      quả test vào checklist deploy, thiếu thì không release. Không thêm
      `127.0.0.1`/dải dùng chung vào CIDR
- [x] Script kiểm staging `scripts/k8s/verify-internal-access.sh` (dùng khi có
      manifest K8s): chạy 1 pod thử không label được phép → gọi Java 8401 phải bị
      chặn; pod agent → `/internal/model-registry/version` 200; nếu có mesh: gọi
      bằng service account khác → 403. Script exit ≠ 0 khi bất kỳ bước nào sai
- [x] Log 1 dòng INFO `remoteAddr` cho request `/internal/**` (không header, không
      body), bật/tắt bằng `INTERNAL_LOG_REMOTE_ADDR` để dùng cho bước đo trên staging
- [x] Filter `InternalResponseHeadersFilter` gắn `Cache-Control: no-store` +
      `Pragma: no-cache` cho **mọi** response `/internal/**` (gồm cả lỗi 4xx/5xx),
      không gắn ở từng controller
- [x] `BackendJavaClient._client()` truyền **tường minh** `follow_redirects=False`
      (hiện chỉ dựa vào mặc định của httpx); mọi 3xx từ Java → raise
      `BackendJavaRedirectError`, không đọc body, không gọi `Location`
- [x] Contract **7 endpoint** (bảng "Internal API contract" trong plan.md — danh
      sách duy nhất, gồm GET/PUT `/embedding-index/{collection}/identity`) được
      review; DTO nội bộ của cả 7 đặt ở `dto/request/internal/`,
      `dto/response/internal/` — không controller SA nào import package này (thêm
      ArchUnit test hoặc test grep đơn giản)
- [x] Test "contract coverage" `InternalEndpointCoverageTest` đọc **route mapping
      thực tế** trong `ApplicationContext` đã khởi động (`@SpringBootTest`), không
      quét source code:
      - Backend có cả `spring-boot-starter-web` và `spring-boot-starter-webflux`
        nhưng chạy servlet (không có `RouterFunction`/`@EnableWebFlux`). Test assert
        `WebApplicationType.SERVLET` và không có bean
        `org.springframework.web.reactive.HandlerMapping` nào — nếu sau này ai bật
        WebFlux server, test đỏ và buộc mở rộng coverage cho reactive mapping.
      - Duyệt **mọi** bean `org.springframework.web.servlet.HandlerMapping`:
        `RequestMappingHandlerMapping` (lấy method + pattern từ
        `getHandlerMethods()`), `RouterFunctionMapping` (functional endpoint MVC),
        `SimpleUrlHandlerMapping`, và mapping của actuator.
      - Tập (method, pattern) có prefix `/internal/` phải **bằng** 7 endpoint trong
        `contracts/internal-endpoints.json` (tới "Checkpoint: Registry lifecycle";
        trước đó cho phép tập con + đánh dấu pending). Thêm endpoint mà quên cập nhật
        contract (và do đó quên test gateway/`no-store`) thì test fail
      - Kiểm thêm: không mapping nào ngoài `/internal/**` trả DTO trong package
        `dto/response/internal/` (đọc return type của handler method)

**Verification:**
- [x] Tests pass: `./mvnw test` — secret đúng/không JWT → 200; không secret →
      403; JWT SA không secret → 403; secret sai → 403; secret đúng nhưng IP ngoài
      CIDR → 403; CIDR rỗng → 403; secret đúng + IP ngoài CIDR + lần lượt từng header
      `X-Forwarded-For: 127.0.0.1`, `Forwarded: for=127.0.0.1`, `X-Real-IP: 127.0.0.1`
      → vẫn 403; ngược lại IP trong CIDR + XFF trỏ IP ngoài → vẫn 200 (header bị bỏ
      qua hoàn toàn); profile `prod` + secret mặc định → context fail
- [x] Tests pass: **startup fail** (không chỉ 403 lúc runtime) — `ApplicationContextRunner`
      / `@SpringBootTest` với profile `prod` và lần lượt: `INTERNAL_ALLOWED_CIDRS`
      không đặt, đặt rỗng, đặt giá trị parse lỗi (`10.0.0.0/33`, `abc`) → context
      không khởi động được, message nêu đúng tên biến; profile `prod` với giá trị
      hợp lệ → khởi động được. Ở profile dev/test, CIDR rỗng không làm fail startup
      nhưng vẫn chặn mọi `/internal/**` (fail-closed)
- [x] Unit test `InternalCallerCidrFilterTest` dùng `MockHttpServletRequest` với
      `remoteAddr` đặt tường minh, và một test source-scan đơn giản xác nhận class
      filter không chứa chuỗi `X-Forwarded-For`/`Forwarded`/`X-Real-IP`/`getHeader(`
- [x] Tests pass: `api-gateway` `./mvnw test` — repo đã có
      `AuthenticationFilterTest`, thêm 2 lớp test mới:
      - Unit `InternalPathBlockFilterTest`: `MockServerWebExchange` cho ma trận path
        bên dưới → 404, chain **không** được gọi; `getOrder()` nhỏ hơn
        `AuthenticationFilter.getOrder()`.
      - Integration `InternalPathBlockIntegrationTest`:
        `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient` với toàn
        bộ filter chain thật, backend thay bằng `MockWebServer` (thêm
        `okhttp3:mockwebserver` test scope). Counter phải **không bị control case
        làm nhiễu**: 2 `MockWebServer` riêng — `blockedBackend` cho test chặn,
        `controlBackend` cho control case — nằm ở 2 test class (2 context, mỗi
        context trỏ `JAVA_BACKEND_URI`/`PYTHON_AI_URI` vào server của mình qua
        `@DynamicPropertySource`). Trong class chặn, mỗi case ghi nhận
        `getRequestCount()` trước và sau, assert chênh lệch 0 (không phụ thuộc thứ
        tự chạy test). Ma trận {không JWT, JWT hợp lệ, JWT sai/hết hạn} ×
        {mọi path trong `contracts/internal-endpoints.json` với prefix
        `/api/v1/master` — gồm `.../model-registry/snapshot`,
        `.../model-registry/verifications/claim` và
        `.../model-registry/embedding-index/unisage_chunks/identity` (GET và PUT),
        `/api/v1/master/INTERNAL/...`, `/api/v1/master/%69nternal/...`,
        `/api/v1/master//internal/...`, `/api/v1/master/x/../internal/...`,
        `/api/v1/master/x/%2E%2E/internal/...`, `/api/v1/master/x%2F..%2Finternal/...`,
        `/api/v1/master/%252E%252E/internal/...` (double-encoding),
        `/api/v1/master/internal;a=b/...`, `/api/v1/master;x=y/internal/...`,
        `/api/v1/master/x\..\internal/...`, `/api/v1/ai/internal/...`} → luôn **404**
        (thiếu JWT mà ra 401 = filter chạy sau auth = FAIL) và
        `mockWebServer.getRequestCount() == 0`
      - Path phải tới server **đúng byte**: gửi bằng socket thô / `HttpClient` Reactor
        Netty với URI đã encode sẵn (`URI.create(raw)`, không để `WebTestClient`
        tự encode), và assert trong test rằng `getRawPath()` filter nhận được trùng
        chuỗi đã gửi — tránh test "xanh giả" do client đã normalize trước
      - Case nào Netty từ chối request line trước khi tới filter (vd `\` thô) thì
        chấp nhận 400 thay cho 404, nhưng phải liệt kê tường minh trong test kèm lý
        do; điều kiện "backend giả nhận 0 request" không có ngoại lệ
- [x] Tests pass: `InternalPathBlockControlTest` (class riêng, `controlBackend`
      riêng) — `/api/v1/master/chat-models` và `/api/v1/master/internal-docs` (tên
      gần giống nhưng không phải `/internal/`) với JWT hợp lệ vẫn tới backend giả
      đúng 1 request (đảm bảo filter không chặn quá tay)
- [x] Tests pass: pytest `test_config.py` — `prod` + secret mặc định → lỗi khởi
      tạo; `prod` + `http://` không cờ mạng mã hoá → lỗi khởi tạo; `prod` +
      `BACKEND_JAVA_BASE_URL` host `host.docker.internal`/`localhost` → lỗi khởi tạo;
      `test_internal_secret.py` vẫn pass sau khi đổi sang `compare_digest`
- [x] Tests pass: `./mvnw test` — `InternalNoStoreTest` tham số hoá theo
      `contracts/internal-endpoints.json`: mọi endpoint (đặc biệt #1 snapshot và #4
      **claim** — 2 endpoint trả key plaintext) và 1 response lỗi 403 trên
      `/internal/**` đều có `Cache-Control: no-store` + `Pragma: no-cache`. Endpoint
      chưa implement ở task này được đánh dấu pending trong file và bật khi task
      tương ứng xong (Task 1/2/4/6); tới "Checkpoint: Registry lifecycle" không
      còn endpoint nào pending
- [x] Tests pass: gateway profile `prod` với
      `JAVA_BACKEND_URI=http://host.docker.internal:8401` → context fail
- [x] Tests pass: pytest `test_backend_java_client.py` — `httpx.MockTransport` giả
      Java trả `302 Location: https://evil.test/steal` (và 301/307/308) → client
      raise `BackendJavaRedirectError`; transport ghi nhận đúng **1** request (tới
      Java), **0** request tới `evil.test`, nên `X-Internal-Secret` và key không bị
      chuyển tiếp; assert `client.follow_redirects is False`. Test chạy cho **mọi**
      method của client gọi endpoint nội bộ (hiện có + `get_model_registry_snapshot`,
      `claim_verifications`, `post_verification_result`, `report_health`,
      `get/put_embedding_index_identity` khi được thêm ở Task 2/4/6/13) — mỗi task
      thêm method mới phải thêm case vào test này

**Dependencies:** None

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/security/InternalSecretFilter.java`
- `unisage-backend/src/main/java/com/unisage/backend/config/DynamicAuthorizationManager.java`
- `unisage-backend/src/main/java/com/unisage/backend/config/InternalSecretStartupCheck.java`
- `unisage-backend/src/main/java/com/unisage/backend/controller/internal/InternalModelRegistryController.java`
- `unisage-backend/src/test/java/com/unisage/backend/security/InternalModelRegistryAuthTest.java`
- `unisage-backend/src/main/resources/application.properties`
- `unisage-backend/src/main/java/com/unisage/backend/security/InternalCallerCidrFilter.java`
- `unisage-backend/src/test/java/com/unisage/backend/security/InternalCallerCidrFilterTest.java`
- `api-gateway/src/main/java/com/unisage/gateway/filter/InternalPathBlockFilter.java`
- `api-gateway/src/main/java/com/unisage/gateway/config/InternalSecretStartupCheck.java`
- `api-gateway/src/test/java/com/unisage/gateway/filter/InternalPathBlockFilterTest.java`
- `api-gateway/src/test/java/com/unisage/gateway/filter/InternalPathBlockIntegrationTest.java`
- `api-gateway/src/test/java/com/unisage/gateway/filter/InternalPathBlockControlTest.java`
- `api-gateway/pom.xml`
- `unisage-agent/app/core/config.py`
- `unisage-agent/app/core/security.py`
- `unisage-agent/app/api/v1/ingestion.py`
- `unisage-agent/app/integrations/backend_java_client.py`
- `unisage-agent/tests/integrations/test_backend_java_client.py`
- `unisage-backend/src/main/java/com/unisage/backend/security/InternalResponseHeadersFilter.java`
- `api-gateway/docker-compose.prod.yml` (hoặc manifest production tương đương)
- `dev-onboard.md`

**Estimated scope:** L (~15 files — **bắt buộc** tách thành 3 PR: 0.1a Java
security + CIDR + XFF; 0.1b gateway filter + 2 lớp test; 0.1c secret/TLS startup
check ở cả 3 service + deploy docs)

---

### Task 0.2: Spike adapter LiteLLM → PydanticAI

**Description:** Graph gọi LLM qua `Agent(model=...)` và streaming qua
`stream_agent_text()` → `agent.run_stream()` → `stream_text(delta=True)`
(`app/graph/streaming.py`). Không thể "thay bằng LiteLLM SDK" trực tiếp. Spike
viết `LiteLLMModel(pydantic_ai.models.Model)` tối thiểu (implement `request()` và
`request_stream()` bằng `litellm.acompletion`) và kiểm từng yêu cầu dưới đây.
Code spike nằm ở branch riêng, không merge; kết quả ghi vào ADR (Task 0).

**Acceptance criteria (mỗi mục ghi PASS/FAIL + ghi chú) — kết quả thật, đã chạy:**
- [x] `stream_agent_text()` chạy không sửa, delta đến trước khi kết thúc — **PASS**
      (2 chunk thay vì per-token; xác nhận bằng test đối chứng là do lớp
      `_continuation` của `pydantic-ai` bản đang pin `>=1.0.0` (resolve 2.50.0),
      **không phải đặc thù của adapter** — model native cũng bị coalesce y hệt)
- [x] `Agent(output_type=<PydanticModel>)` hoạt động, kể cả khi stream — **PASS** cả
      2 trường hợp (từng là "điểm dễ vỡ nhất", hoá ra không phải điểm chặn)
- [x] `RequestUsage` có mặt ở cả non-stream và stream — **PASS**
- [x] Exception provider đi ra là exception typed — **PASS**, nhưng là
      `litellm.AuthenticationError` (subclass `openai.APIError`, có `status_code`),
      không phải `pydantic_ai.ModelHTTPError` — đúng tinh thần tiêu chí (typed,
      không mất `status_code`), chỉ khác class cụ thể
- [x] Inject được HTTP client/transport tuỳ biến cho SSRF guard Task 0.6 — **FAIL**
      ở adapter LiteLLM (`litellm.acompletion(client=...)` đòi object dạng OpenAI
      SDK client hoặc `aiohttp.ClientSession`, không nhận `httpx.AsyncClient` trần
      — lỗi `'AsyncClient' object has no attribute 'api_key'`); **PASS** ở provider
      native `OpenAIProvider(http_client=...)` (test đối chứng: transport giả nhận
      đúng 1 request)
- [x] `FunctionModel` test doubles hiện có vẫn dùng được — không re-verify được
      trong venv cô lập của spike (thiếu dependency không liên quan), nhưng không
      bị ảnh hưởng về kiến trúc ở cả 2 phương án (test double gắn thẳng, không qua
      adapter lẫn model native)
- [ ] Chạy thật với ít nhất 2 provider — chỉ chạy được OpenAI (không có credential
      provider thứ 2 trong môi trường chạy spike); phát hiện quyết định (mục
      transport injection) không phụ thuộc provider nào nên không đổi kết luận

**Decision gate — đã áp dụng nhánh:**
- ~~Tất cả PASS → dùng `LiteLLMModel` adapter (Task 5).~~
- ~~Structured output/stream FAIL...~~ (không xảy ra — cả 2 đều PASS)
- **→ Không inject được transport (LiteLLM) → bỏ LiteLLM, dùng model native
  PydanticAI (`OpenAIChatModel`/`AnthropicModel`...) build từ snapshot, Task 9
  phân loại trên exception của SDK provider.** Đã cập nhật plan.md (Architecture
  Decisions) + ADR trước khi làm Task 5 — xem
  `unisage-backend/docs/adr/0005-dynamic-model-registry.md`.
- Phương án native đã tự chứng minh qua tiêu chí SSRF (mục transport injection ở
  trên) cho `OpenAIProvider`; `AnthropicProvider` và provider OpenAI-compatible
  (`SELF_HOSTED`) dùng cùng cơ chế `http_client=` — không test riêng trong spike
  này (cùng constructor pattern), Task 5/0.6 phải có test riêng cho từng provider
  thật sự đưa vào `SUPPORTED_LLM_PROVIDERS`.

**Verification:**
- [x] Báo cáo spike (bảng PASS/FAIL) đính kèm vào ADR
- [ ] Review với human, chốt phương án — **cần xác nhận từ bạn** trước khi implement
      Task 5 dựa trên kết luận này

**Dependencies:** None

**Files likely touched (spike, không merge — đã chạy và xoá khỏi working tree,
không commit):**
- `unisage-agent/app/core/llm/litellm_model.py` (đã xoá)
- `unisage-agent/tests/spike/run_litellm_spike.py` (đã xoá)

**Estimated scope:** M (time-box 2 ngày) — **hoàn thành**

---

### Task 0.3: State machine + ràng buộc DB

**Description:** `BaseEntity.isActive` đang được `delete`/`recover` dùng làm
soft-delete; thêm `status` mà không định nghĩa quan hệ sẽ sinh trạng thái mâu thuẫn.
Task này chốt ma trận chuyển trạng thái và ràng buộc DB (mục "State machine" trong
plan.md) thành spec kiểm thử được, trước khi code Task 1/3.

**Acceptance criteria:**
- [x] Ma trận trong plan.md được review, không còn ô "chưa định nghĩa" cho cặp
      (trạng thái, sự kiện) nào
- [x] Danh sách test case chuyển trạng thái (hợp lệ + bị từ chối 409) viết sẵn dạng
      skeleton (disabled) để Task 1/3 bật lên — `ChatModelStateTransitionTest`
- [x] Staged rotation (mục "Credential rotation" plan.md) được review; test
      skeleton: sửa key của row ACTIVE → row vẫn trong snapshot với key cũ cho tới
      khi verify OK; verify FAIL → key cũ vẫn chạy; update không có `apiKey` → giữ
      key cũ; `apiKey = ""` → 400; đổi host không nhập lại key → 400 —
      `ChatModelRotationTest`
- [x] Fencing token — test skeleton (`VerificationFencingTest`):
      - A claim → lease hết hạn → B claim lại → A gửi result token cũ → 409, job/row
        không đổi; B gửi result → áp dụng
      - A claim → lease hết hạn, **chưa ai claim lại** → A gửi result đúng token →
        vẫn 409 (kiểm `lease_until > now()` bằng đồng hồ DB)
      - A gửi result hợp lệ 2 lần (retry mạng) → lần 2 `200 { duplicate: true }`,
        không áp dụng lần 2; áp dụng cho cả nhánh OK, TRANSIENT (job đã về
        `QUEUED`) và FAILED
      - 2 request result OK giống hệt nhau gửi **đồng thời** (2 thread, barrier) →
        đúng 1 `applied`, 1 `duplicate`; `revision` tăng đúng 1; version bump đúng
        1; `ModelRegistryChangedEvent` publish đúng 1 lần (spy publisher)
      - Lỗi giữa chừng khi promote (ép exception sau khi ghi
        `last_result_lease_token`) → rollback toàn bộ: marker không còn, revision
        không đổi, không event; gửi lại cùng token → được áp dụng bình thường
- [x] Rotation race — test skeleton (same file):
      - A claim job generation 5 (key 1) → SA đổi sang key 2 (generation 6, job 5
        `SUPERSEDED`) → A gửi OK **với token đúng và lease còn hạn** → 409, row vẫn
        chạy key cũ, không event
      - Job ở `SUPERSEDED` bị sửa tay `lease_until` về tương lai → vẫn không promote
        (điều kiện `status = 'RUNNING'`)
      - Biến thể CAS generation thất bại khi job còn `RUNNING` (ghi thẳng
        `candidate_generation` trong test) → job `SUPERSEDED`, row không nhận key 1
      - SA sửa credential và result tới đồng thời → không deadlock (thứ tự khoá
        model → job ở cả 2 đường), kết quả cuối nhất quán
- [x] Stale health: test skeleton report mang `credentialRevision` cũ sau rotate →
      `applied: false`, không tăng counter, không DISABLE
- [x] SQL draft (tên dự kiến ban đầu `V16`, thật ra lên DB là
      `V18__add_chat_model_purpose_and_status.sql` — xem ghi chú ở Task 1) gồm
      CHECK constraint, unique partial index embedding,
      `CHECK (NOT (is_active = false AND status = 'ACTIVE'))`, cột `revision`,
      `candidate_generation`, cột `lease_token`/`lease_until`/
      `last_result_lease_token`/`candidate_generation`/`base_revision`/`candidate_*`
      của bảng verification (status đủ 7 giá trị theo bảng "Trạng thái verification" của plan.md), unique partial index 1 job
      dở/credential, backfill row cũ
- [x] `ErrorCode` mới được chốt tên: `CHAT_MODEL_STATUS_CONFLICT`,
      `EMBEDDING_ACTIVE_CONFLICT`, `CHAT_MODEL_NOT_VERIFIED`,
      `VERIFICATION_LEASE_LOST`, `CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST`,
      `CHAT_MODEL_PROVIDER_UNSUPPORTED`, `CHAT_MODEL_URL_NOT_ALLOWED`,
      `EMBEDDING_REINDEX_REQUIRED`, `EMBEDDING_INDEX_IDENTITY_EXISTS`
- [x] Embedding identity guard (plan.md) — test skeleton (`EmbeddingIdentityGuardTest`):
      - EMBEDDING ACTIVE, ứng viên đổi `llmModelName` → verify OK → job
        `REINDEX_REQUIRED`, row + snapshot không đổi, không event
      - Tương tự cho đổi `llmProvider`, `apiBaseUrl`, `modelSourceRef`, và cho
        `dimension` đo được khác
      - Chỉ đổi key, fingerprint khớp (cosine ≥ 0.999) → promote; fingerprint lệch
        hoặc dimension lệch → `REINDEX_REQUIRED`
      - EMBEDDING INACTIVE đổi model → promote bình thường (không nằm trong snapshot);
        sau đó activate → 409 `EMBEDDING_REINDEX_REQUIRED`
      - Swap: activate EMBEDDING B khác danh tính index khi A đang ACTIVE → 409, A
        vẫn ACTIVE; B cùng danh tính → swap thành công
      - A bị DISABLED, activate lại A sau khi sửa key (danh tính khớp) → được; activate
        C khác danh tính → 409
      - `PUT .../embedding-index/{collection}/identity` lần 2 → 409
        `EMBEDDING_INDEX_IDENTITY_EXISTS`, danh tính không đổi (kể cả khi body khác)
- [x] Bootstrap danh tính đồng thời — test skeleton, kiểm **đủ transaction
      semantics** (Java, `@SpringBootTest` + Postgres thật qua Testcontainers, không
      H2) — implemented as `EmbeddingIndexIdentityConcurrencyTest` (enabled/bật in
      Task 1, deliberately `@RepeatedTest(10)` instead of 50 loops and without the
      `pg_stat_user_tables` assertion — see Task 1's note on why; ran clean
      (MinIO dependency was down in this environment on the first pass, causing
      unrelated `ApplicationContext` load failures across the whole suite —
      started it and reran, full `./mvnw test` now green with 0 errors):
      - Collection rỗng, A và B gọi PUT cùng lúc (2 thread + `CyclicBarrier`, 2
        connection DB riêng) với danh tính **khác nhau** → đúng **1** response 201
        và đúng **1** response 409 `EMBEDDING_INDEX_IDENTITY_EXISTS` (không có 500,
        không có 2×201)
      - **Không có UPDATE/DELETE**: chụp `n_tup_upd`, `n_tup_del` của
        `embedding_index_identity` trong `pg_stat_user_tables` trước/sau (sau
        `pg_stat_force_next_flush()`) → delta = 0; `n_tup_ins` delta = 1; trigger
        chặn UPDATE/DELETE không bị kích hoạt (không có exception trong log)
      - **Đọc lại sau conflict đúng**: GET sau cả 2 request trả đúng danh tính của
        bên nhận 201 (so từng field + fingerprint), và bên nhận 409 khi GET cũng
        thấy đúng danh tính đó (không thấy trạng thái nửa vời)
      - Lặp 50 vòng (mỗi vòng collection mới) để bắt race không tất định
      - `UPDATE`/`DELETE` thẳng bằng SQL → trigger raise, row không đổi
      - 2 collection khác nhau đăng ký song song → cả 2 đều 201, không chặn nhau
- [ ] Bootstrap đồng thời phía ingest — test cross-repo (bật ở Task 13, nằm trong
      "Checkpoint: Registry lifecycle" mở rộng tới Phase 5): collection Qdrant rỗng,
      2 Celery worker với snapshot khác nhau (A dùng credential danh tính X, B dùng
      Y — mô phỏng snapshot lệch trong lúc SA swap) cùng chạy ingest:
      - Đúng 1 bên đăng ký được danh tính; bên kia nhận 409, GET lại thấy lệch →
        ingest job FAILED với lý do rõ
      - **Không request nào dùng danh tính sai để upsert vector**: mọi point trong
        collection mang payload `embedding_identity_key` (hash của danh tính, ghi ở
        Task 13) bằng đúng danh tính đã đăng ký; số point của bên thua = 0
      - Bên thua không gọi embedding provider để ghi (đếm request ở fake provider
        cho credential thua: chỉ có request đo fingerprint, không có batch embed)
      (chưa có — không tìm thấy test cross-repo nào mô phỏng 2 Celery worker với
      snapshot embedding lệch danh tính cùng ingest chạy live; `test_embedding_identity_guard.py`
      và các unit test của Task 13 chỉ phủ logic guard, không phủ kịch bản 2-worker
      này trên live stack)
- [x] Chạy thử migration draft trên bản copy DB dev: row cũ backfill đúng, index
      tạo được (fail nếu dữ liệu hiện có vi phạm → xử lý trong migration) —
      `V18MigrationTest`, Testcontainers pgvector Postgres thật

**Verification:**
- [ ] Review với human
- [x] Manual check: 2 transaction activate 2 embedding đồng thời → 1 bị index chặn
      — verified bằng `V18MigrationTest#singleActiveEmbedding_uniqueIndexEnforced`
      thay vì `psql` tay (cùng khẳng định, tự động hoá được)

**Dependencies:** None

**Files likely touched:**
- `unisage-backend/src/test/java/com/unisage/backend/service/chatmodel/ChatModelStateTransitionTest.java`
- `unisage-backend/src/test/java/com/unisage/backend/service/chatmodel/ChatModelRotationTest.java`
- `unisage-backend/src/test/java/com/unisage/backend/service/modelregistry/VerificationFencingTest.java`
- `unisage-backend/src/main/resources/db/migration/V18__add_chat_model_purpose_and_status.sql` (draft)

**Estimated scope:** M (4 files, chủ yếu test skeleton)

---

### Task 0.4: Redis cho Java — dependency, deploy, version, publish-after-commit

**Description:** `pom.xml` chưa có Spring Data Redis, `backend-java/docker-compose.yml`
chưa có Redis; Redis hiện chỉ chạy trong `unisage-agent/.devcontainer`. Task này
dựng hạ tầng theo mục "Hot-reload consistency" trong plan.md để Task 7 chỉ còn nối
nghiệp vụ.

**Acceptance criteria:**
- [x] `pom.xml` thêm `spring-boot-starter-data-redis`; `application.properties`
      thêm `spring.data.redis.url=${REDIS_URL:redis://localhost:6379/0}`
- [x] `backend-java/docker-compose.yml` truyền `REDIS_URL` cho service
      `backend-java`/`devcontainer`, trỏ **cùng** Redis của `unisage-agent` (không
      dựng Redis thứ 2); `dev-onboard.md` và `.ENV` example cập nhật
- [x] Bảng `model_registry_version` (singleton) + `ModelRegistryVersionService.bump()`
      chạy `UPDATE ... RETURNING` trong transaction hiện tại (`MANDATORY`)
- [x] `ModelRegistryChangedEvent` + listener `@TransactionalEventListener(AFTER_COMMIT)`
      publish lên `model-registry:updates`; lỗi Redis chỉ log, không ném lên
- [x] Actuator health có Redis; Redis down không làm Java fail startup
      (`spring-boot-starter-data-redis` autoconfig, không có `required` gate riêng)
- [x] Tests Java không cần Redis thật: publisher được mock ở unit test
      (`ModelRegistryEventPublisherTest`); 1 integration test dùng Testcontainers
      Redis (`ModelRegistryVersionServiceTest`, `GenericContainer` redis:7-alpine)

**Verification:**
- [x] Tests pass: `./mvnw test` — gồm case transaction rollback → không publish,
      commit → publish đúng 1 lần với version mới
- [ ] Manual check: `redis-cli SUBSCRIBE model-registry:updates` thấy message khi
      gọi `bump()` qua 1 endpoint test — chưa có endpoint test thật để gọi thủ công
      (chỉ có qua `ModelRegistryVersionServiceTest` tự động); làm khi Task 7 nối
      nghiệp vụ thật

**Dependencies:** None (migration bảng version gộp vào V18 cùng Task 1)

**Files likely touched:**
- `unisage-backend/pom.xml`
- `unisage-backend/src/main/resources/application.properties`
- `unisage-backend/docker-compose.yml`
- `unisage-backend/src/main/java/com/unisage/backend/config/RedisConfig.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/modelregistry/ModelRegistryVersionService.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/modelregistry/ModelRegistryEventPublisher.java`
- `dev-onboard.md`

**Estimated scope:** M (5-7 files)

---

### Task 0.5: Khung test tích hợp cross-repo

**Description:** Hot-reload, verify pull-based và failover chỉ kiểm được thật khi
Java, Python (≥2 worker), Celery, Redis chạy cùng nhau. Dựng khung chạy được bằng
1 lệnh, các task sau thêm kịch bản vào đây thay vì mỗi task tự dựng.

**Acceptance criteria:**
- [x] `docker-compose.integration.yml` (ở `unisage-agent/tests/e2e/`): Postgres,
      Redis, api-gateway, backend-java (không publish port ra host, chỉ gateway/
      agent/test runner trong network gọi được), unisage-agent `gunicorn -w 2`, 1 Celery worker, **1
      Celery Beat** (service riêng, đúng 1 instance — verify pull-based phụ thuộc
      nó), 1 fake LLM provider OpenAI-compatible, Qdrant (cho kịch bản embedding)
      — verified by actually building and running the stack this pass
      (`docker compose -f docker-compose.integration.yml up -d`, all services
      healthy)
- [x] **Chỗ chạy pytest — chốt: service `test-runner`** trong cùng compose network
      (image agent + dependency test), chạy
      `docker compose -f docker-compose.integration.yml run --rm test-runner pytest -m integration`:
      - Network có subnet cố định (vd `172.30.0.0/24`); `INTERNAL_ALLOWED_CIDRS` của
        Java trong harness = đúng subnet này → test-runner gọi được `/internal/**`
        trực tiếp (đóng vai Python ở "Checkpoint: Registry lifecycle"), còn host thì
        không.
      - **Không service nào publish port ra host**, kể cả gateway. Cần debug thì
        publish gateway duy nhất ở `127.0.0.1` qua override file riêng, không nằm
        trong file compose chính.
      - Fixture cần `docker compose stop/start celery-beat` → test-runner mount
        `/var/run/docker.sock` **chỉ trong compose integration** (máy dev/CI runner
        tạm), ghi rõ rủi ro trong README; không có ở compose nào khác.
      - Host chỉ chạy `docker compose ... run` và đọc report (JUnit XML ghi ra volume).
- [ ] (chưa làm đủ — chỉ `CELERY_BEAT_HEARTBEAT_INTERVAL_SECONDS` được rút ngắn
      xuống `2` trong `docker-compose.integration.yml`; `MODEL_REGISTRY_VERIFICATION_INTERVAL_SECONDS`
      vẫn giữ default 15s, không override cho profile test. Không chặn test hiện
      có vì `test_registry_lifecycle.py` gọi claim/result trực tiếp qua API thay vì
      chờ chu kỳ Beat, nhưng đúng như acceptance criteria ghi thì chưa xong)
- [x] Cô lập Redis (plan.md "Hot-reload consistency"): `celery_app.py` hiện dùng
      `settings.REDIS_URL` cho cả broker và backend — tách thành
      `CELERY_BROKER_URL` (DB 1) và `CELERY_RESULT_BACKEND` (DB 2), giữ `REDIS_URL`
      (DB 0) cho registry/circuit breaker/lock/event; tên queue Celery lấy từ
      `CELERY_QUEUE_PREFIX`, channel pub/sub từ `MODEL_REGISTRY_CHANNEL`. Harness
      sinh prefix riêng mỗi lần chạy (vd `it-<uuid8>`), Java đọc cùng tên channel
      — confirmed in `app/core/config.py`/`app/worker/celery_app.py`
- [x] DNS server test (`tests/e2e/rebinding_dns/`, dựa trên `dnslib`): hostname
      `rebind.test` trả IP fake provider ở lần hỏi đầu, IP của 1 service "nội bộ"
      mồi (đếm kết nối) ở các lần sau, TTL 0; container agent/worker đặt `dns:` trỏ
      vào nó để test dùng resolver thật của OS (dùng ở Task 0.6) — ran
      `test_ssrf_rebinding.py` against the live stack this pass, passed
- [x] Seed dữ liệu: `ModelRegistryIntegrationSeeder` (`@Profile("integration")`,
      không bao giờ chạy ở profile khác) tạo qua service (để key được mã hoá bằng
      `ApiKeyConverter`, không insert SQL thô), idempotent khi restart (check-before-
      insert; endpoint reset dưới đây luôn xoá trước khi gọi lại seed). Bản seeder này
      được viết thẳng theo schema **hiện tại** (đã có Task 1) thay vì bản 2-ChatModel
      cũ rồi mở rộng sau — không còn cần bước trung gian: `modelPurpose`/
      `status = ACTIVE`/`verifiedAt`/`revision = 1` cho đủ CHAT (×2, priority 1 và 2),
      EMBEDDING, EXTRACTION, đều trỏ base URL cấu hình được (mặc định
      `http://localhost:8000/v1`)
- [x] Thứ tự khởi động: agent/worker/beat `depends_on` backend-java
      `condition: service_healthy` (healthcheck xác nhận seed xong) — từ Task 4
      Python fail startup khi không có CHAT ACTIVE. Trước Task 4 chạy với
      `MODEL_REGISTRY_ENABLED=false` — confirmed in `docker-compose.integration.yml`
      (agent/celery-worker/celery-beat all `depends_on: backend-java: condition:
      service_healthy`); registry is now the only credential path (Task 4/5
      cutover complete), so the pre-Task-4 flag branch no longer applies
- [x] Fixture pytest reset trạng thái giữa các test, **tuần tự** để không race với
      Beat/worker đang thao tác verification job — `registry_reset_fn` in
      `conftest.py` implements exactly this 7-step sequence; smoke test proves it
      runs twice in a row without error against the live stack:
      1. `docker compose stop celery-beat` (không còn task mới được lên lịch)
      2. `celery -A app.worker.celery_app purge -f -Q <queue của lần chạy này>` —
         chỉ purge queue có prefix của harness, **không bao giờ** purge toàn broker
      3. Poll `celery -A app.worker.celery_app inspect active` tới khi rỗng
         (timeout 30s → fail fixture, không reset nửa vời)
      4. Gọi endpoint reset của seeder: trong 1 transaction xoá verification job +
         reseed; từ chối 409 nếu còn job `RUNNING` có lease chưa hết hạn → fixture
         quay lại bước 3
      5. Xoá Redis key theo prefix `mr:*` (circuit breaker, lock, debounce) — **không**
         `FLUSHDB` vì cùng Redis là Celery broker
      6. Reset fake provider
      7. `docker compose start celery-beat` (chỉ với test cần Beat; test khác giữ
         Beat tắt và gọi task verify trực tiếp để kết quả tất định)
      Scope fixture theo module để không trả giá stop/start Beat cho từng test
- [x] Endpoint reset không bao giờ tồn tại ngoài harness:
      - [x] Path `POST /internal/test/registry/reset` — nằm dưới `/internal/**` nên
        tự động qua secret + CIDR (fail-closed) + no-store hiện có (path-pattern-based,
        không cần khai báo riêng); chặn ở gateway là việc của api-gateway repo, chưa
        làm ở đây.
      - [x] Controller + seeder chỉ đăng ký bean với `@Profile("integration")`.
      - [x] Java fail startup nếu `integration` bật cùng `prod` (hoặc cùng bất kỳ
        profile nào ngoài `integration`/`test`) — `ModelRegistryIntegrationProfileStartupCheck`.
      - [x] Test: context profile mặc định và `prod` → không có bean seeder/controller
        reset, `POST /internal/test/registry/reset` (có secret, IP hợp lệ) → 404 (đã
        phải sửa `GlobalExceptionHandler` — catch-all cũ nuốt `NoResourceFoundException`
        thành 500 thay vì để lọt 404 mặc định của Spring).
      - [x] Chỉ bind trong network harness: Java không publish port, CIDR = subnet
        harness — confirmed: `docker-compose.integration.yml` gives `backend-java`
        no `ports:` entry (only reachable inside the fixed-subnet `it_net`
        network), `INTERNAL_ALLOWED_CIDRS` scoped to that subnet.
- [x] Fake provider (FastAPI nhỏ) điều khiển được qua API admin: trả OK / 401
      invalid key / 429 + `Retry-After` / 402 hết credit / lỗi giữa stream sau N
      chunk / `echo_secrets` (trả lỗi chứa nguyên `Authorization` + body, dùng cho
      test canary Task 0.7); phục vụ cả TLS với cert do CA test ký cho
      `fake-provider.test` — confirmed in `tests/e2e/fake_llm_provider/app.py`
      (all listed modes present) and `tests/e2e/tls/`
- [x] Mỗi request báo được worker nào xử lý (header `X-Worker-Pid` chỉ bật ở profile
      test) để assert "cả 2 worker đã reload" — confirmed in `app/api/v1/health.py`
- [x] Allowlist SSRF của profile này chứa hostname fake provider — confirmed
      `MODEL_REGISTRY_URL_ALLOWLIST: "fake-llm-provider,fake-provider.test"` in
      `docker-compose.integration.yml`
- [x] 1 kịch bản **smoke hạ tầng** (ghi rõ trong tên test và README — không phải
      nghiệm thu nghiệp vụ): Java healthy, Python healthy, Redis pub/sub thông, Beat
      đang chạy (thấy ít nhất 1 lần tick), seed ChatModel tồn tại (đọc qua
      `/internal/model-registry/version` + DB), fake provider trả lời được từ
      trong network agent, fixture reset chạy 2 lần liên tiếp không lỗi.
      Vòng đời purpose/status/revision nghiệm thu ở "Checkpoint: Registry
      lifecycle"; assertion "snapshot của cả 2 worker chứa credential seed" thêm ở
      Task 4 — ran `pytest -m integration tests/e2e/test_model_registry_smoke.py`
      against the live stack this pass: 6 passed
- [x] Hướng dẫn chạy trong `unisage-agent/tests/e2e/README.md`; marker pytest
      `integration` để không chạy trong unit suite mặc định — confirmed; README
      is stale in one place (still says "seeder/reset endpoint intentionally not
      implemented" from before Task 1's seeder landed) but the instructions
      themselves work as written (used them verbatim this pass)

**Verification:**
- [x] `pytest -m integration tests/e2e/test_model_registry_smoke.py` xanh cục bộ
      — ran it for real against the live docker-compose stack this pass: 6 passed
      in 17.77s

**Dependencies:** Task 0.1, Task 0.4

**Files likely touched:**
- `unisage-agent/tests/e2e/docker-compose.integration.yml`
- `unisage-agent/tests/e2e/fake_llm_provider/app.py`
- `unisage-agent/tests/e2e/conftest.py`
- `unisage-agent/tests/e2e/test_model_registry_smoke.py`
- `unisage-agent/tests/e2e/README.md`
- `unisage-agent/tests/e2e/rebinding_dns/server.py`
- `unisage-agent/app/worker/celery_app.py`
- `unisage-agent/app/core/config.py`
- `unisage-backend/src/main/java/com/unisage/backend/config/ModelRegistryIntegrationSeeder.java`

**Estimated scope:** L (~9 files — tách 0.5a compose + fake provider + seeder +
reset; 0.5b tách Redis DB/queue/channel; 0.5c DNS server test)

---

### Task 0.6: SSRF guard 2 lớp (chuyển lên từ Phase 7)

**Description:** SA nhập `apiBaseUrl` tuỳ ý. Từ Task 5/6 trở đi Python sẽ thực sự
gọi URL này, nên guard phải có trước. Áp đúng mục "SSRF policy" trong plan.md.

**Acceptance criteria:** (Task 0.6 was flagged in the audit brief as a section where
almost every box was stale-unchecked despite the guard, factory, and every listed
test being built and passing — confirmed again this pass: `SsrfGuard.java`,
`ssrf_guard.py`, `http_client.py`, `test_ssrf_guard.py`,
`test_no_raw_provider_clients.py`, `test_provider_call_sites_use_pinned_backend.py`,
and the four `tests/e2e/test_ssrf_*.py` files all exist and pass; two of the
integration ones (`test_ssrf_rebinding.py`) were re-run live against the harness
this pass — see Task 0.5)
- [x] Kiểm cú pháp URL chạy **trước** kiểm IP/DNS, ở cả Java và Python (plan.md
      "SSRF policy"): reject scheme ngoài danh sách; userinfo (`user:pass@`, `@`
      rỗng); có query hoặc fragment; port ngoài `1-65535`/rỗng/không phải số; ký
      tự control `\x00-\x1f`/`\x7f`, khoảng trắng (kể cả `%20`, tab, xuống dòng),
      `\`; host rỗng; host non-ASCII không chuyển IDNA hợp lệ được (hợp lệ thì dùng
      A-label cho mọi bước sau); URL > 2048 ký tự. Bỏ `.` cuối host trước khi so
      allowlist
- [ ] (vectors đúng và đủ, nhưng cơ chế `contracts:sync` chưa tồn tại — xem Task
      0.8: không có `contracts.lock`/`tools/contracts_sync.py` nào trong
      `unisage-agent`, agent chỉ có file `contracts/vendor/ssrf-url-vectors.json`
      khớp nội dung backend nhưng được đặt vào đó bằng tay, không qua sync tool)
      Bộ test vector chung `ssrf-url-vectors.json` (input → accept/reject + lý do),
      nguồn duy nhất ở `unisage-backend/contracts/`, agent lấy qua `contracts:sync`
      theo SHA trong `contracts.lock` (plan.md "Contract files dùng chung"); gồm
      ít nhất: `https://u:p@api.openai.com/v1`, `https://@api.openai.com`,
      `https://api.openai.com/v1?x=1`, `https://api.openai.com/v1#f`,
      `https://api.openai.com:0/v1`, `https://api.openai.com:65536`,
      `https://api.openai.com:/v1`, `https://api.openai.com:8a/v1`,
      `ftp://h/`, `file:///etc/passwd`, `gopher://h/`, `https://api.openai.com/v1\n`,
      `https://api.open ai.com`, `https://api.openai.com\\@evil.com/`,
      `https://ex%00ample.com`, `https://xn--.com`, `https://münchen.de/v1` (IDNA
      hợp lệ → accept qua bước cú pháp), `https://api.openai.com./v1`
- [x] Java `SsrfGuard`: validate khi tạo/sửa `ChatModel` (cú pháp, scheme, resolve
      mọi A/AAAA, dải bị chặn, IPv4-mapped IPv6, allowlist từ env
      `MODEL_REGISTRY_URL_ALLOWLIST`), lỗi → `ErrorCode.CHAT_MODEL_URL_NOT_ALLOWED`
      kèm lý do cụ thể (vd `USERINFO_NOT_ALLOWED`) để UI hiển thị
- [x] Java: danh sách `SUPPORTED_LLM_PROVIDERS` (chốt theo spike Task 0.2 — chỉ
      provider đã chứng minh inject được client); tạo/sửa với provider ngoài danh
      sách → 400 `CHAT_MODEL_PROVIDER_UNSUPPORTED`
- [x] Python `ssrf_guard` + `PinnedNetworkBackend` (bọc `httpcore` async network
      backend): override `connect_tcp(host, port)` → resolve 1 lần, kiểm mọi IP
      theo cùng rule với Java, mở socket tới IP đã kiểm. Pin ở **socket layer**,
      không chỉ kiểm URL. URL request giữ hostname gốc → header `Host`, TLS SNI và
      kiểm hostname của certificate đều theo hostname gốc
- [x] Certificate validation luôn bật: SSL context tạo tường minh từ `certifi`;
      cấm `verify=False`, `CERT_NONE`, `check_hostname=False` (test kiến trúc quét
      thêm các chuỗi này); harness chỉ được thêm CA test vào context
- [x] `pyproject.toml`: khai báo `httpcore` là dependency trực tiếp với dải hẹp
      (vd `>=1.0.x,<1.1`) và siết `httpx` (hiện `>=0.27.0`) về dải minor đã test;
      ghi lý do trong ADR (hook `connect_tcp` không phải public API ổn định)
- [x] Factory duy nhất `app/core/llm/http_client.py::build_provider_http_client(credential)`
      trả `httpx.AsyncClient`/`httpx.Client` với transport dùng
      `PinnedNetworkBackend`, `follow_redirects=False`, `trust_env=False`, timeout
      mặc định; hook response: mọi 3xx → raise `ProviderRedirectRejectedError`
- [x] Chuyển **ngay trong task này** mọi đường gọi provider hiện có sang factory, kể
      cả khi vẫn đọc key từ `.env`: `openai_embedder.py` và `multi_representation.py`
      (đang tự tạo `OpenAI(api_key=...)`), `get_graph_models()` (truyền
      `http_client=` vào `OpenAIProvider`). Provider native PydanticAI (ADR 0005)
      / verifier ở các task sau dùng lại factory này
- [x] Registry transport ở Python: map `llmProvider` → cặp (Model class, Provider
      class) native PydanticAI đã chứng minh ở spike (ADR 0005). Credential có
      provider không nằm trong map → không build, log error, báo health PERMANENT
      `PROVIDER_TRANSPORT_UNSUPPORTED`; **không bao giờ** fallback về client mặc
      định của SDK
- [x] Test kiến trúc `tests/core/test_no_raw_provider_clients.py`: quét AST của
      `app/`, fail nếu thấy `OpenAI(`, `AsyncOpenAI(`, `Anthropic(`,
      `httpx.Client(`, `httpx.AsyncClient(` ở ngoài factory (ADR 0005 — bỏ
      LiteLLM làm đường gọi provider, không được quay lại dùng nó)
- [ ] **Cập nhật (đồng bộ với plan Cost Tracking, quyết định litellm-chỉ-định-giá):**
      rule cấm `import litellm` tuyệt đối ở trên **quá rộng** — Cost Tracking
      cần `litellm.completion_cost()`/`litellm.cost_per_token()` để tra giá
      offline. Thu hẹp rule thành 2 phần:
      1. **Whitelist đúng 1 module** `app/core/cost_calculator.py` (đặt bởi
         plan Cost Tracking) được phép `import litellm` — chỉ để gọi
         `completion_cost`/`cost_per_token`, không dùng cho gì khác.
      2. Trong **chính module đó**, test kiến trúc vẫn quét và fail nếu thấy
         `litellm.completion(`, `litellm.acompletion(`, `litellm.embedding(`,
         `litellm.aembedding(`, hoặc bất kỳ cách tạo `httpx`/`AsyncOpenAI`/
         provider transport nào (kể cả gián tiếp qua kwargs như `client=`,
         `api_base=` trỏ ra ngoài) — `cost_calculator.py` chỉ được phép làm
         phép tính giá thuần, không được mở bất kỳ kết nối mạng nào.
      Mọi file khác trong `app/` (kể cả `model_router.py`, `get_graph_models()`)
      vẫn bị cấm `import litellm` hoàn toàn như rule gốc.
- [x] Allowlist production mặc định rỗng, giá trị thật do hạ tầng điền lúc deploy
      (plan.md "Open Questions"); test profile Java đặt `localhost` để test hiện có
      (`http://localhost:8000/v1` trong `ChatModelServiceImplTest`) vẫn pass; thêm
      test chứng minh cùng URL bị chặn khi allowlist rỗng
- [ ] (một nửa — `dev-onboard.md` mục production có `INTERNAL_ALLOWED_CIDRS` kèm
      hướng dẫn chi tiết, nhưng không có mục `MODEL_REGISTRY_URL_ALLOWLIST` nào;
      ngoài ra file này nằm ngoài mọi git repo trong workspace — không tự sửa vào
      đây vì không phải quyết định của mình)
      `dev-onboard.md` mục production: checklist deploy liệt kê
      `INTERNAL_ALLOWED_CIDRS` (bắt buộc) và `MODEL_REGISTRY_URL_ALLOWLIST` (rỗng =
      không self-hosted nội bộ nào) kèm ví dụ định dạng, không phải giá trị thật
- [x] Bộ test chung cho cả 2 phía: `127.0.0.1`, `2130706433` (decimal),
      `0x7f.1`, `[::1]`, `[::ffff:127.0.0.1]`, `169.254.169.254`, `10.0.0.1`,
      `100.64.0.1`, `[fd00::1]`, `metadata.google.internal` → chặn;
      `https://api.openai.com/v1` → qua
- [x] Test rebinding (unit): resolver giả trả IP public ở lần resolve của
      validator, `127.0.0.1` ở lần sau → socket vẫn mở tới IP đã kiểm (hoặc bị
      chặn), không bao giờ tới `127.0.0.1`
- [x] Test rebinding **thật** (integration, `pytest -m integration`): gọi
      `https://rebind.test/...` qua DNS server test của Task 0.5 (resolver OS thật,
      TTL 0) → request tới fake provider hoặc bị chặn, service nội bộ mồi nhận **0**
      kết nối
- [x] Test Host/SNI/cert: fake provider TLS với cert cho `fake-provider.test` do CA
      test ký → request OK và fake provider ghi nhận `Host: fake-provider.test` +
      SNI `fake-provider.test`; cert sai hostname / hết hạn / CA lạ → lỗi TLS,
      không có response nào được dùng
- [x] Test hook còn sống sau khi nâng dependency: backend giả đếm `connect_tcp` > 0
      cho mọi request (fail ngay nếu phiên bản `httpcore` mới bỏ qua hook)
- [x] Test redirect: fake server 302 về `http://169.254.169.254/` → request bị từ
      chối với `ProviderRedirectRejectedError`, fake metadata nhận 0 request
- [x] Test `trust_env`: đặt `HTTPS_PROXY`/`HTTP_PROXY`/`ALL_PROXY` trỏ proxy giả
      (đếm kết nối) và `SSL_CERT_FILE` trỏ CA lạ trong env test → client từ
      factory không đi qua proxy giả (0 kết nối) và không dùng CA từ env
- [x] Test cho từng đường gọi: embedder, multi-representation, graph model (và
      adapter/provider native khi có) đều chạy qua `PinnedNetworkBackend` (assert
      bằng backend giả đếm lần `connect_tcp`)

**Verification:**
- [x] Tests pass: `./mvnw test` và `pytest tests/core/test_ssrf_guard.py
      tests/core/test_no_raw_provider_clients.py` — ran both this pass: `./mvnw
      test` green (0 errors after starting the MinIO container this environment
      needed), pytest 918 passed / 3 failed (the 3 failures are unrelated —
      `CHAT_RERANK_SCORE_THRESHOLD` config default drift, a health-check
      dependency, a clarification-repository concurrency test — none touch SSRF)
- [x] Tests hiện có của embedder/multi-representation vẫn pass sau khi chuyển
      sang factory — covered by the same pytest run above
- [ ] (chưa làm tay — mechanism đã có và đã kiểm bằng test tự động
      (`SsrfGuardTest`, `test_ssrf_guard.py`), cần người review thao tác thật qua
      API/UI để xác nhận)
      Manual check: tạo ChatModel `apiBaseUrl=http://127.0.0.1:8401` ở profile
      không có allowlist → bị từ chối rõ ràng

**Dependencies:** Task 0.2 (cách inject client cho từng provider); Task 0.5 (DNS
server test + fake provider TLS cho test integration)

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/utils/SsrfGuard.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/test/java/com/unisage/backend/utils/SsrfGuardTest.java`
- `unisage-agent/app/core/ssrf_guard.py`
- `unisage-agent/app/core/llm/http_client.py`
- `unisage-agent/app/rag/embeddings/openai_embedder.py`
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/app/api/deps.py`
- `unisage-agent/tests/core/test_ssrf_guard.py`
- `unisage-agent/tests/core/test_no_raw_provider_clients.py`
- `unisage-agent/tests/e2e/test_ssrf_rebinding.py`
- `unisage-agent/pyproject.toml`
- `unisage-backend/contracts/ssrf-url-vectors.json`
- `unisage-agent/contracts/vendor/ssrf-url-vectors.json` (sinh bởi sync, không sửa tay)

**Estimated scope:** L (~12 files — tách 0.6a Java guard + provider list; 0.6b
Python guard + factory + pin dependency + test kiến trúc; 0.6c chuyển embedder/
multi-rep/graph sang factory; 0.6d test integration rebinding/TLS)

---

### Task 0.7: Secret redaction + test canary

**Description:** API key có thể lọt qua message của exception provider, cột
`error_message`/`lastErrorMessage`, log/traceback, Celery result backend, Slack,
response SA. Hiện thực mục "Secret redaction" trong plan.md trước khi bất kỳ task
nào ghi lỗi provider ra ngoài (Task 2, 6, 9, 15).

**Acceptance criteria:**
- [x] Java `SecretRedactor` và Python `app/core/redaction.py` cùng quy tắc: thay
      chính xác key của credential đang xử lý (và chuỗi con ≥ 8 ký tự), pattern
      `Authorization`/`Bearer`/`x-api-key`/`api-key`/`sk-`/`sk-ant-`, query
      `key=`/`api_key=`/`token=`, userinfo trong URL; cắt 500 ký tự sau khi redact
- [ ] (rule đúng và giống nhau ở 2 repo — `SecretRedactorTest`/`test_redaction.py`
      dùng cùng `redaction-vectors.json` — nhưng cơ chế `contracts:sync` không tồn
      tại, xem Task 0.8: agent's `contracts/vendor/redaction-vectors.json` khớp nội
      dung backend nhưng được đặt vào đó bằng tay)
      Bộ test vector redaction dùng chung `redaction-vectors.json`: nguồn ở
      `unisage-backend/contracts/`, agent lấy qua `contracts:sync` (không copy tay)
- [x] Python: `logging.Filter` redaction gắn vào root logger, áp cho cả message và
      traceback (`exc_info`/`stack_info`); Java: `MessageConverter`/pattern layout
      redact tương đương
- [x] Helper `safe_error_message(exc, credential)` là cách duy nhất biến exception
      provider thành text; cấm `str(exc)`/`repr(exc)` đi ra DB/Slack/HTTP (test kiến
      trúc quét `app/core/llm/`, `app/worker/`, `app/integrations/`) — confirmed:
      `tests/core/test_safe_error_message_usage.py` exists and passes
- [x] Không log request body/headers của lời gọi provider; tắt debug logging của
      `httpx`/`httpcore`/`openai`/`anthropic` ở mức INFO trở lên trong config mặc định
- [x] Celery: cấu hình chung cho task của feature này `ignore_result=True`,
      `store_errors_even_if_ignored=False`; test xác nhận không task nào của
      feature nhận argument chứa key (kiểm signature + gọi thử với broker giả) —
      confirmed: `tests/core/test_celery_task_secret_safety.py`
- [ ] (không tìm thấy — không có `assert_no_canary()` hay bất kỳ helper quét canary
      nào trong `unisage-agent`; đây là gap thật, không phải box lỗi thời)
      Harness: helper `assert_no_canary()` quét Postgres (mọi cột text, trừ
      `api_key_encrypted`/`candidate_api_key_encrypted`), mọi DB Redis (`SCAN` +
      đọc value, gồm result backend), log mọi container (`docker compose logs`),
      response SA đã ghi lại, payload fake Slack webhook
- [x] Fake provider có chế độ `echo_secrets`: trả lỗi 401/500 chứa nguyên
      `Authorization` header và request body — confirmed in
      `tests/e2e/fake_llm_provider/app.py`

**Verification:**
- [x] Tests pass: unit test redactor ở 2 repo với bộ vector chung — ran
      `SecretRedactorTest` (part of the green `./mvnw test` run) and
      `tests/core/test_redaction.py` (part of the 918-passed pytest run) this pass
- [ ] (không chạy được — file `tests/e2e/test_secret_canary.py` và
      `tests/e2e/secret_canary.py` không tồn tại trong repo; đây là gap thật cần
      code mới, không phải test có sẵn chưa chạy)
      Tests pass: `pytest -m integration tests/e2e/test_secret_canary.py` — seed key
      `sk-canary-<uuid>`, bật `echo_secrets`, chạy 1 lần verify fail + 1 health
      report → `assert_no_canary()` xanh

**Dependencies:** Task 0.5 (harness), Task 0.6 (factory — nơi gắn redactor cho lỗi
provider)

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/utils/SecretRedactor.java`
- `unisage-backend/src/test/java/com/unisage/backend/utils/SecretRedactorTest.java`
- `unisage-agent/app/core/redaction.py`
- `unisage-agent/app/core/logging_config.py`
- `unisage-agent/tests/core/test_redaction.py`
- `unisage-agent/tests/e2e/secret_canary.py`
- `unisage-agent/tests/e2e/test_secret_canary.py`

**Estimated scope:** M (7 files)

---

### Task 0.8: Contract files dùng chung + công cụ sync

**Description:** Hiện thực mục "Contract files dùng chung" trong plan.md để không
còn bản copy tay nào giữa 3 repo (không có monorepo, không có OpenAPI).

**Acceptance criteria:** (audited this pass: the backend half exists, the
sync/CI/husky half across web and agent does not — `unisage-agent` has no
`contracts.lock` or `tools/contracts_sync.py` anywhere, `unisage-web` has no
`contracts.lock` and no `contracts:sync`/`contracts:check` script in
`package.json`, and `backend-java` has no `.github/workflows/` at all so there's
no CI to run `git diff --exit-code contracts/`. Every file that's supposed to be
generated/vendored by these tools is instead copied by hand — `unisage-web`'s
`src/features/chat-models/schemas/verification-status.ts` says so explicitly in
its own docstring — so nothing in this task is done)
- [x] `unisage-backend/contracts/` commit; `ContractExportTest` sinh các file từ code
      (ban đầu: `verification-statuses.json` từ enum — enum có ở Task 1, trước đó
      test sinh file rỗng có đánh dấu pending)
- [ ] (chưa có CI backend nào — không có `.github/workflows/` trong `backend-java`
      — nên bước `git diff --exit-code contracts/` không tự động chạy ở đâu cả)
      CI backend chạy test rồi `git diff --exit-code contracts/`
- [ ] (không có gì trong mục này — không `contracts.lock`, không script
      `contracts:sync`/`contracts:check`, không file `src/generated/contracts/`,
      không husky hook nào tham chiếu contracts)
      `unisage-web`: `contracts.lock` (SHA backend), `pnpm contracts:sync` tải file
      qua GitHub API và sinh `src/generated/contracts/*.ts` có header
      `// GENERATED — DO NOT EDIT`; `pnpm contracts:check` = sync + `git diff
      --exit-code`; thêm vào CI và `husky` pre-commit
- [ ] (không có gì trong mục này — không `contracts.lock`, không
      `tools/contracts_sync.py`; `contracts/vendor/*.json` tồn tại và khớp nội
      dung backend nhưng được commit thẳng bằng tay)
      `unisage-agent`: `contracts.lock`, `python -m tools.contracts_sync` tải
      `ssrf-url-vectors.json`, `redaction-vectors.json` vào `contracts/vendor/`;
      `--check` cho CI
- [ ] Job CI ở web và agent so SHA trong lock với HEAD `main` của backend, contract
      khác → cảnh báo (không chặn merge, nhưng hiện rõ trên PR)
- [ ] Dev không có `GITHUB_TOKEN` vẫn chạy test được với file vendored đã commit;
      chỉ sync/check cần mạng — (đúng trên thực tế vì mọi thứ đều copy tay, nhưng
      không phải vì cơ chế này hoạt động — không tính là đạt)

**Verification:**
- [ ] Sửa tay 1 dòng trong file generated/vendored → `contracts:check` đỏ —
      không có lệnh `contracts:check` nào để chạy
- [ ] Thêm 1 giá trị vào enum Java mà không sinh lại → CI backend đỏ; sinh lại +
      bump lock ở web mà quên cập nhật bảng nhãn → `tsc -b` đỏ — không có CI hay
      lock nào để đỏ

**Dependencies:** None (Task 0.6/0.7/1/17 tiêu thụ)

**Files likely touched:**
- `unisage-backend/contracts/*.json`
- `unisage-backend/src/test/java/com/unisage/backend/contracts/ContractExportTest.java`
- `unisage-web/contracts.lock`, `unisage-web/scripts/contracts-sync.ts`,
  `unisage-web/package.json`, `unisage-web/.husky/pre-commit`
- `unisage-agent/contracts.lock`, `unisage-agent/tools/contracts_sync.py`

**Estimated scope:** M (~8 files, 3 repo — tách theo repo)

---

## Checkpoint: Phase 0.5
- [ ] (Task 0.8 chưa xong — không còn bản copy tay chỉ đúng một nửa: backend có
      `ContractExportTest`, nhưng web/agent không có `contracts:check` nào để
      chạy, và không CI backend nào chạy `git diff --exit-code contracts/`)
      Contract files: không còn bản copy tay; `contracts:check` xanh ở web và agent,
      `git diff --exit-code contracts/` xanh ở backend
- [ ] (redactor xanh, Celery secret-safety xanh, nhưng test canary không tồn tại —
      xem Task 0.7)
      Redactor 2 repo xanh với bộ vector chung; test canary xanh; task Celery của
      feature `ignore_result=True`, không nhận credential làm argument
- [x] `/internal/**`: secret đúng/không JWT → 200; không secret → 403; JWT SA
      không secret → 403; IP ngoài `INTERNAL_ALLOWED_CIDRS` → 403; CIDR rỗng → 403;
      `X-Forwarded-For`/`Forwarded`/`X-Real-IP` giả không đổi kết quả theo cả 2
      chiều; filter CIDR không đọc header nào — covered by Task 0.1's tests, ran
      clean this pass
- [x] Gateway: unit + integration test xanh — mọi biến thể `/internal/**` (gồm
      `%2F`, `%2E%2E`, double-encoding, `;param`) gửi **đúng raw path** → 404 (không
      phải 401) với cả không JWT / JWT hợp lệ / JWT sai, backend giả nhận 0
      request; control case route thường vẫn đi qua — ran `api-gateway`'s
      `./mvnw test` clean this pass (0 failures/errors)
- [x] Profile `prod` fail startup với secret mặc định (Java + Python + gateway) và
      với `BACKEND_JAVA_URL=http://` khi chưa khai báo mạng mã hoá; manifest
      production không publish port Java; Python dùng `hmac.compare_digest` —
      covered by Task 0.1's tests
- [x] Spike có kết luận, ADR (Task 0) đã ghi phương án được chọn; phương án đó (kể
      cả khi là dự phòng native PydanticAI) đã chứng minh inject được client SSRF
- [x] Java start được với Redis; rollback không publish, commit publish 1 lần
- [x] Khung test cross-repo xanh với **smoke hạ tầng** (Celery Beat, seed ChatModel
      trỏ fake provider, reset tuần tự chạy lặp không lỗi) — không phải nghiệm thu
      vòng đời registry — ran the live smoke test this pass, 6 passed
- [x] Redis cô lập: broker/backend/registry ở DB riêng, queue + channel có prefix
      theo lần chạy; purge chỉ đụng queue của harness
- [x] SSRF test suite xanh ở cả 2 repo: socket-layer pinning, DNS rebinding thật
      qua DNS server test, Host/SNI gốc + cert validation bật, redirect bị từ chối,
      proxy/CA từ env bị bỏ qua; `httpcore`/`httpx` đã pin; test kiến trúc "không
      client provider thô" xanh; embedder + multi-representation + graph đã đi qua
      factory — ran the DNS-rebinding integration test live this pass in addition
      to the full unit suite
- [x] Test skeleton đã review (Task 0.3): fencing (token, hạn lease), idempotency
      cùng transaction (result lặp đồng thời → revision/version/event đúng 1 lần;
      rollback giữa chừng không để lại marker), job `SUPERSEDED` không promote dù
      token/lease hợp lệ, rotation race không deadlock, stale health report,
      embedding identity guard (đổi danh tính → `REINDEX_REQUIRED`, activate/swap
      khác danh tính → 409) — `VerificationFencingTest`/`EmbeddingIdentityGuardTest`
      ran clean this pass
- [ ] Review với human → Phase 0.5 PASS, được bắt đầu Phase 1. **Chưa** phải
      approve toàn bộ plan (xem "Trạng thái approve" trong plan.md)

---

## Phase 1: Java — Mở rộng Model Registry

### Task 1: `modelPurpose`, `status`, verification, version + migration V18

**Description:** Hiện thực schema đã chốt ở Task 0.3/0.4. **Cập nhật:** tên file
dự kiến ban đầu là `V16`, nhưng khi migrate thật thì `V16__dashboard_permission.sql`
và `V17__purge_noise_audit_logs.sql` từ `main` đã chiếm 2 số đó trước, nên file
thật trong repo là `V18__add_chat_model_purpose_and_status.sql` (xác nhận bằng
`ls db/migration`, có `V18MigrationTest` tương ứng, không phải `V16MigrationTest`
như một số dòng checklist bên dưới còn ghi theo tên nháp cũ). Plan Cost Tracking
đã được sửa lại để tham chiếu đúng V18 thay vì V16.

**Acceptance criteria:**
- [x] Enum `ChatModelPurpose` (CHAT/EMBEDDING/EXTRACTION), `ChatModelStatus`
      (PENDING/ACTIVE/INACTIVE/DISABLED), `ChatModelVerificationStatus`
      (QUEUED/RUNNING/SUCCEEDED/FAILED/**SUPERSEDED**/CANCELLED/**REINDEX_REQUIRED** — đủ 7 giá trị, khớp
      bảng "Verification lifecycle" trong plan.md) ở `entity/enums/`
- [x] Bảng `embedding_index_identity` **khoá chính `collection_name`** (không
      singleton toàn cục) + cột `embedding_dimension`, `embedding_fingerprint real[]`
      trên `chat_models` và `chat_model_verifications`; entity `@Immutable` +
      repository không có method update/delete
- [x] Endpoint #6/#7 (GET/PUT `.../embedding-index/{collection}/identity`): PUT chỉ
      dùng `INSERT ... ON CONFLICT (collection_name) DO NOTHING RETURNING`, 0 row →
      409 `EMBEDDING_INDEX_IDENTITY_EXISTS`; không có nhánh update, không
      check-then-insert
- [x] V18 thêm trigger `BEFORE UPDATE OR DELETE ON embedding_index_identity` raise
      exception
- [x] Bật test skeleton "Bootstrap danh tính đồng thời" của Task 0.3 —
      `EmbeddingIndexIdentityConcurrencyTest`; dùng `@RepeatedTest(10)` thay vì 50
      vòng, và bỏ assertion `pg_stat_user_tables` (flaky do connection pool riêng
      của 2 thread) — "không UPDATE/DELETE" đã được `V18MigrationTest` phủ chắc
      chắn hơn (assert trigger raise), không mất coverage
- [x] V18 có `CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','SUPERSEDED','CANCELLED','REINDEX_REQUIRED'))`
      cho `chat_model_verifications`
- [x] `contracts/verification-statuses.json` (7 giá trị, đúng thứ tự bảng plan.md)
      được **sinh** từ enum bởi `ContractExportTest`; CI chạy test rồi
      `git diff --exit-code contracts/`. Test `VerificationStatusContractTest` so enum
      Java ↔ CHECK constraint trong DB (đọc `pg_constraint`); và
      schema của DTO `latestVerification.status` (enum trong OpenAPI/Jackson) chỉ
      nhận đúng 7 giá trị đó
- [x] `ChatModel` thêm `modelPurpose` (not-null, bắt buộc khi tạo, không sửa được
      sau khi tạo), `status` (default `PENDING`), `revision` (row mới 0, row cũ
      backfill 1), `candidateGeneration`, `verifiedAt`
- [x] Entity `ChatModelVerification` (gồm `candidateGeneration`, `baseRevision`,
      `candidate*` với key qua `ApiKeyConverter`, `leaseToken`, `leaseUntil`,
      `lastResultLeaseToken`) + repository (claim bằng
      native query `FOR UPDATE SKIP LOCKED`)
- [x] `V18__add_chat_model_purpose_and_status.sql`: cột mới, CHECK, unique partial
      index embedding, unique partial index 1 job dở/credential, bảng
      `chat_model_verifications`, bảng `model_registry_version` (seed
      `version = 1`), backfill row cũ theo plan.md
- [x] `ModelRegistryIntegrationSeeder` (`@Profile("integration")`) đã được tạo, seed
      đúng theo schema hiện tại (`modelPurpose`/`status = ACTIVE`/`verifiedAt`/
      `revision = 1`): 2 CHAT (priority 1, 2) + 1 EMBEDDING + 1 EXTRACTION, tất cả qua
      `ChatModelService`/`ChatModelCandidatePromotionService` thật (không insert SQL
      thô). `POST /internal/test/registry/reset` (cùng profile) xoá job xác minh +
      reseed trong 1 transaction, 409 nếu còn job `RUNNING` với lease chưa hết hạn.
      Startup fail nếu profile `integration` bật cùng bất kỳ profile nào khác ngoài
      `test`. Test: bean/route vắng mặt + 404 (không phải 403) ở profile mặc định và
      `prod`; integration test seed đúng 4 credential, reset 2 lần không lỗi/không
      trùng, job RUNNING chặn reset bằng 409
- [x] `delete`/`recover` hiện có tuân state machine (delete → `INACTIVE` +
      `is_active=false`; recover → `is_active=true`, status giữ `INACTIVE`) — đã
      đúng từ trước, không cần sửa code
- [x] Bật test skeleton của Task 0.3 cho phần delete/recover
- [x] Không thêm rule validate theo `modelPurpose` (đã chốt ở plan.md "Open
      Questions"): mọi purpose được cả `CLOUD_API` và `SELF_HOSTED`; test xác nhận
      tạo EMBEDDING `SELF_HOSTED` hợp lệ (URL trong allowlist test) được chấp nhận

**Verification:**
- [x] Tests pass: `./mvnw test` (`ddl-auto=validate` bắt lệch entity ↔ V18) — 371
      run, 0 failures, 0 errors, 52 skipped (đều là placeholder có chủ đích cho
      Task 2/3)
- [x] Build succeeds: `./mvnw clean package -DskipTests`
- [ ] Manual check: migrate DB dev, row cũ vẫn dùng được, status backfill đúng —
      chưa làm thủ công trên DB dev thật, chỉ mới xác nhận qua `V18MigrationTest`
      (Testcontainers)

**Dependencies:** Task 0.3, Task 0.4

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/entity/ChatModel.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/ChatModelVerification.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/enums/ChatModelPurpose.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/enums/ChatModelStatus.java`
- `unisage-backend/src/main/java/com/unisage/backend/entity/enums/ChatModelVerificationStatus.java`
- `unisage-backend/src/main/java/com/unisage/backend/repository/ChatModelVerificationRepository.java`
- `unisage-backend/src/main/resources/db/migration/V18__add_chat_model_purpose_and_status.sql`

**Estimated scope:** M (6-7 files)

---

### Task 2: `POST /internal/model-registry/credentials/{id}/health`

**Description:** Python báo lỗi provider về Java. Nằm trong namespace nội bộ đã
dựng ở Task 0.1, không đụng `ChatModelController` SA-facing.

**Acceptance criteria:**
- [x] Body `{ credentialRevision, snapshotVersion, errorType: TRANSIENT|PERMANENT, errorCode, message, occurredAt }`
- [x] `credentialRevision` khác `revision` hiện tại của row → bỏ qua toàn bộ
      (không counter, không status), trả `200 { applied: false }`; so sánh
      revision và cập nhật trong cùng 1 câu `UPDATE ... WHERE id = :id AND revision = :rev`
- [x] Tăng `errorCount`, set `lastErrorAt`; lưu `lastErrorCode`/`lastErrorMessage`
      để UI giải thích — `message` đi qua `SecretRedactor` (Task 0.7) rồi mới cắt
      500 ký tự và lưu, dù Python đã redact
- [x] PERMANENT + `status = ACTIVE` → `DISABLED` qua compare-and-set, bump version
      (Task 0.4) trong cùng transaction; TRANSIENT không bump version
- [x] Credential đã DISABLED/INACTIVE nhận thêm báo lỗi → chỉ cập nhật counter,
      không đổi status, trả 200 (idempotent)
- [x] PERMANENT được ghi nhận để Task 15 lấy dữ liệu alert

**Verification:**
- [x] Tests pass: `./mvnw test` — gồm bật test skeleton stale report của Task 0.3
- [x] Manual check: Postman collection (`docs/postman/`) với header secret

**Dependencies:** Task 1, Task 0.1, Task 0.7

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/internal/InternalModelRegistryController.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/modelregistry/ModelRegistryInternalServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/request/internal/CredentialHealthReportRequest.java`

**Estimated scope:** S (3 files)

---

### Task 3: API SA — activate/deactivate/re-verify/priority

**Description:** SA-facing, phục vụ trang admin Task 17. Mọi chuyển trạng thái
theo ma trận plan.md.

**Acceptance criteria:**
- [x] `PATCH /chat-models/{id}/status` body `{ status: ACTIVE|INACTIVE }` — từ chối
      các chuyển không có trong ma trận bằng 409 (`CHAT_MODEL_NOT_VERIFIED`,
      `CHAT_MODEL_STATUS_CONFLICT`)
- [x] Activate EMBEDDING (kể cả swap và bật lại sau DISABLED): **trước tiên** so
      danh tính row (provider/model/sourceRef/baseUrl + `embeddingDimension` +
      `embeddingFingerprint`) với `embedding_index_identity`; lệch → 409
      `EMBEDDING_REINDEX_REQUIRED` kèm danh sách field khác, không đổi gì. Khớp (hoặc
      index chưa có danh tính và collection rỗng) → tự chuyển embedding đang ACTIVE
      sang INACTIVE trong cùng transaction; race → 409 `EMBEDDING_ACTIVE_CONFLICT`
- [x] Promote ứng viên (xử lý result Task 6) cho row EMBEDDING đang ACTIVE: ứng viên
      đổi danh tính hoặc fingerprint/dimension lệch index → job `REINDEX_REQUIRED`,
      row không đổi
- [x] `POST /chat-models/{id}/verify` — tạo job mới, job dở sang `SUPERSEDED`
- [x] `create` → row `PENDING`, `revision = 0`, `candidateGeneration = 1` + job
      `QUEUED` (`baseRevision = 0`)
- [x] `update` đổi field credential / `verify` → **staged rotation** (plan.md
      "Credential rotation"): trong 1 transaction `candidateGeneration += 1`, khoá model → job,
      job dở sang `SUPERSEDED`, tạo job `QUEUED` với ứng viên + `candidateGeneration` mới +
      `baseRevision = revision`; row giữ nguyên status/giá trị cũ
- [x] Tách `ChatModelUpdateRequest` khỏi `ChatModelRequest` (hiện create và `PUT`
      dùng chung, field `apiKey` là `String` nên không phân biệt được vắng mặt với
      `null`). Field `apiKey` kiểu `JsonNullable<String>`; thêm dependency
      `org.openapitools:jackson-databind-nullable` và bean `JsonNullableModule`
- [x] Tách validate create/update: `validateBySourceType` hiện bắt buộc `apiKey`
      cho CLOUD_API ở **cả** update, và `update` gán thẳng
      `setApiKeyEncrypted(request.apiKey())` — mâu thuẫn với "vắng = giữ key cũ".
      Update chỉ bắt buộc key khi row chưa có key hoặc khi đổi host (quy tắc
      `CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST`); không còn gán key thẳng vào row
- [x] Quy tắc API key khi update (bảng tri-state trong plan.md, xử lý ở đúng 1
      method service): vắng → giữ key cũ; `null` → giữ key cũ; `""`/toàn khoảng
      trắng → 400; key mới → thành ứng viên;
      `clearApiKey: true` chỉ cho `SELF_HOSTED`; đổi host `apiBaseUrl` mà không
      nhập lại key → 400 `CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST`. Ứng viên giữ
      key cũ thì copy bản mã hoá, không giải mã rồi mã hoá lại qua DTO
- [x] `PATCH /chat-models/{id}/priority`
- [x] `GET /chat-models` hỗ trợ filter `modelPurpose`, `status`, sort `priority`;
      response thêm `status`, `modelPurpose`, `revision`, `verifiedAt`,
      `hasPendingChange`, `latestVerification`, `lastErrorCode`; **không** có
      field API key (chỉ `hasApiKey` như cũ) và không lộ giá trị ứng viên
- [x] Mọi thay đổi ảnh hưởng snapshot gọi `bump()` (Task 0.4)
- [x] Endpoint mới được seed permission trong `PredefinedPermissions`/
      `DataInitializer` (theo skill `new-feature-spring-boot`)
- [x] Bật toàn bộ test skeleton của Task 0.3

**Verification:**
- [x] Tests pass: `./mvnw test` (ma trận chuyển trạng thái + race embedding +
      rotation: row ACTIVE vẫn nằm trong snapshot với key cũ trong suốt thời gian
      ứng viên chờ verify) — ran full suite clean this pass
- [ ] (chưa có — tri-state semantics của `apiKey` được unit-test ở tầng service
      qua `ChatModelRotationTest` (dùng `JsonNullable.undefined()`/`of()` trực
      tiếp trong Java), nhưng không có `@WebMvcTest` nào gửi **JSON thô** qua HTTP
      để chứng minh `JsonNullableModule`/Jackson thật sự phân biệt được field vắng
      mặt với `null` từ một request body thật — gap thật ở tầng HTTP, không phải
      lỗi thời)
      Tests pass: `@WebMvcTest` gửi **JSON thô** (không qua object mapper của test)
      cho 4 trường hợp `apiKey` vắng / `null` / `""` / `"sk-new"` (+ `"   "`) →
      đúng bảng; kiểm cả không tạo job ứng viên khi key giữ nguyên và không đổi
      field nào khác
- [ ] (chưa chạy tay — endpoint filter/sort đã có và được unit-test, cần người
      review gọi thật qua Postman)
      Manual check: filter + sort qua Postman

**Dependencies:** Task 1

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/ChatModelController.java`
- `unisage-backend/src/main/java/com/unisage/backend/repository/ChatModelRepository.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/response/ChatModelResponse.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/request/ChatModelUpdateRequest.java`
- `unisage-backend/pom.xml`
- `unisage-backend/src/main/java/com/unisage/backend/predefined/PredefinedPermissions.java`
- `unisage-backend/src/main/java/com/unisage/backend/exception/ErrorCode.java`

**Estimated scope:** L (6 files — tách "status/verify" và "priority/filter" nếu quá lớn)

---

## Checkpoint: Phase 1
- [x] `./mvnw test` pass toàn bộ, gồm ma trận chuyển trạng thái và race embedding
- [x] `GET /chat-models?modelPurpose=CHAT&status=ACTIVE` (SA) trả đúng thứ tự priority
      — `@PageableDefault(sort = "priority")` + filter params confirmed in
      `ChatModelController`
- [x] Response SA không chứa API key ở bất kỳ endpoint nào — `ChatModelResponse`
      only exposes `hasApiKey`, never the key itself
- [ ] Review với human trước khi đụng Python

---

## Checkpoint: Registry lifecycle (sau Task 1-3 + phần Java của Task 4 và Task 6)

Smoke ở Phase 0.5 chạy trên schema cũ nên chỉ chứng minh hạ tầng. Checkpoint này
nghiệm thu vòng đời purpose/status/revision trên harness cross-repo, với pytest
đóng vai Python gọi thẳng internal API của Java. Checkpoint cần endpoint snapshot
(#1, phần Java của Task 4) và claim/result (#4/#5, phần Java của Task 6), nên làm
phần Java của 2 task đó **trước** phần Python (Task 4 và 6 vốn đã được ghi là tách
Java/Python). Tới checkpoint này cả 7 endpoint đã có, `InternalNoStoreTest` không
còn endpoint nào pending, và test "contract coverage" so bằng (không chỉ tập con).

All of the following were actually run this pass against the live
docker-compose stack (`tests/e2e/test_registry_lifecycle.py`, 9 passed in
101.99s) — not just unit-tested at the Java level:

- [x] Seeder đã mở rộng theo schema mới; reset tuần tự vẫn chạy lặp không lỗi
- [x] Stale lease: claim → đợi quá `lease_until` → result đúng token → 409 —
      `test_stale_lease_result_is_rejected_after_expiry`
- [x] Lease bị claim lại: result token cũ → 409; result token mới → áp dụng —
      `test_lease_reclaimed_after_expiry_old_token_409_new_token_applies`
- [x] Result lặp: gửi cùng result 2 lần (tuần tự và đồng thời) → lần 2
      `duplicate: true`; `revision` tăng đúng 1, `/version` tăng đúng 1, subscriber
      Redis trên channel của harness nhận đúng 1 message (nhánh OK, TRANSIENT, FAILED)
      — `test_duplicate_result_applied_once_revision_and_version_bump_once`
- [x] Rotation race: claim job generation N → SA sửa credential (generation N+1,
      job N `SUPERSEDED`) → result OK của job N với token đúng + lease còn hạn →
      409, không promote, không message Redis; snapshot vẫn trả key cũ tới khi job
      N+1 OK — `test_rotation_race_superseded_job_result_rejected_no_promotion_no_event`
- [x] Rotation không downtime: suốt quá trình ứng viên chờ verify, snapshot vẫn
      có row ACTIVE với key cũ — `test_rotation_has_no_downtime_snapshot_keeps_old_key_while_candidate_pending`
- [x] Stale health: report `credentialRevision` cũ → `applied: false` —
      `test_stale_health_report_is_not_applied`
- [x] Key plaintext không lọt: quét toàn bộ log Java trong lượt chạy + response
      mọi endpoint SA → không có chuỗi key seed nào; chỉ `/internal/**` qua đúng
      secret + CIDR mới trả key — `test_seeded_key_plaintext_never_appears_in_sa_responses_or_java_logs`
- [x] Gateway: gọi `/api/v1/master/internal/model-registry/snapshot` qua gateway
      thật trong harness → 404, Java không nhận request (log access) —
      `test_gateway_blocks_internal_snapshot_path`

---

## Phase 2: Python — Đọc registry thay vì .env

### Task 4: Snapshot endpoint + client Python + cache snapshot

**Description:** Java: implement `GET /internal/model-registry/snapshot` theo
contract plan.md. Python: client gọi endpoint này, build snapshot in-memory cho 3
purpose. Request Chat/Ingest **không** gọi Java trực tiếp, chỉ đọc snapshot.

**Acceptance criteria:**
- [x] Java: snapshot đọc version + dữ liệu trong 1 transaction read-only
      `REPEATABLE_READ`; chỉ row `is_active AND status = ACTIVE`; giải mã key qua
      `ApiKeyConverter`; `Cache-Control: no-store` (qua filter chung, bỏ endpoint
      #1 khỏi trạng thái pending trong `InternalNoStoreTest`); DTO `toString` che key
- [x] `BackendJavaClient.get_model_registry_snapshot()` / `get_model_registry_version()`
      được thêm vào test redirect của Task 0.1 (302 → 0 request tới host khác)
- [x] Python `app/core/model_registry.py`: dataclass `frozen` cho snapshot,
      load lúc startup của **cả** FastAPI lifespan và Celery `worker_process_init`
- [x] `BackendJavaClient` thêm `get_model_registry_snapshot()` /
      `get_model_registry_version()` (dùng lại `_auth_headers`)
- [x] Key chỉ nằm trong bộ nhớ; không log, `repr` che key
- [x] Startup fail rõ ràng nếu không có credential ACTIVE cho CHAT — trừ khi
      `MODEL_REGISTRY_ENABLED=false` (feature flag rollout, dùng `.env` cũ)
- [x] Mỗi credential trong snapshot mang `revision`; Python giữ `revision` +
      `snapshot.version` để gắn vào health report (Task 2)

**Verification:**
- [x] Tests pass: `./mvnw test` (snapshot không lẫn row PENDING/DISABLED/deleted;
      row đang có ứng viên chờ verify vẫn trả giá trị cũ)
- [x] Tests pass: pytest `test_model_registry.py` với Java response giả lập
- [ ] (chưa có — `test_model_registry_smoke.py` không có assertion nào so sánh
      snapshot của cả 2 gunicorn worker + Celery worker; gap thật)
      Kịch bản cross-repo: smoke bổ sung assertion cả 2 gunicorn worker + Celery
      worker đều load snapshot chứa credential seed
- [ ] (chưa chạy tay — cơ chế che key trong log đã có và unit-tested, cần người
      review chạy thật và đọc log)
      Manual check: start Python với 1 ChatModel ACTIVE, log (đã che key) đúng

**Dependencies:** Task 3

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/internal/InternalModelRegistryController.java`
- `unisage-backend/src/main/java/com/unisage/backend/dto/response/internal/InternalModelRegistrySnapshotResponse.java`
- `unisage-agent/app/core/model_registry.py`
- `unisage-agent/app/integrations/backend_java_client.py`
- `unisage-agent/tests/core/test_model_registry.py`

**Estimated scope:** M (5 files)

---

### Task 5: `get_graph_models()` build model từ snapshot

**Description:** Thay `OpenAIChatModel(settings.OPENAI_MODEL, ...)` (giá trị cứng)
trong `app/api/deps.py::get_graph_models()` bằng model native PydanticAI build
từ snapshot CHAT, chọn class theo `llmProvider` (quyết định ADR 0005 — không
dùng LiteLLM SDK, spike Task 0.2 cho thấy nó không inject được transport SSRF).
**Chưa có failover** — chỉ credential priority cao nhất. Graph node và
`stream_agent_text()` không đổi.

**Acceptance criteria:**
- [x] `app/core/llm/provider_models.py`: map `llmProvider` → (Model class,
      Provider class) — `"openai"` → `OpenAIChatModel`/`OpenAIProvider`,
      `"anthropic"` → `AnthropicModel`/`AnthropicProvider`; `SELF_HOSTED` dùng
      `OpenAIChatModel` + `OpenAIProvider(base_url=...)`. Mỗi entry truyền
      `http_client=` từ `build_provider_http_client()` (Task 0.6) vào `Provider`
      class — đã xác nhận cơ chế này nhận đúng transport ở spike Task 0.2 — map
      also covers google/mistral/groq (providers actually supported end to end)
- [x] Provider không có trong map → không build, lỗi `CHAT_MODEL_PROVIDER_UNSUPPORTED`/
      health `PROVIDER_TRANSPORT_UNSUPPORTED`, không fallback SDK mặc định
- [x] (superseded, went further than written — the rollout finished: `get_graph_models()`
      no longer has an `.env` fallback branch at all, `MODEL_REGISTRY_ENABLED` is
      not referenced anywhere in `app/api/deps.py`, and `OPENAI_API_KEY`/`OPENAI_MODEL`/
      etc. are gone from `.env.example` — see commit "Make the model registry the
      only credential source for the CHAT graph model")
      `get_graph_models()` build từ snapshot; `MODEL_REGISTRY_ENABLED=false` → giữ
      đường `.env` cũ
- [x] Các node dùng chung model (`classification`, `query_transformation`,
      `generation`) hoạt động y hệt khi registry có 1 credential — không regression
- [x] (satisfied more strongly than written — there's no flag branch left to gate;
      `settings.OPENAI_*` isn't referenced anywhere in `app/api/deps.py`)
      `MODEL_REGISTRY_ENABLED=false` là **cờ rollout tạm thời duy nhất** còn được
      phép đọc `settings.OPENAI_*`; khi cờ `true` (mặc định sau khi rollout xong),
      `get_graph_models()` không được đọc `settings.OPENAI_API_KEY`/`OPENAI_MODEL`
      dưới bất kỳ hình thức nào — kể cả làm giá trị dự phòng khi snapshot rỗng
      (mục "Cutover khỏi cấu hình `.env` tĩnh" trong plan.md)
- [x] (file này đã bị xoá theo cùng commit trên — vì không còn nhánh fallback nào để
      test "không rơi vào nhánh đó", coverage cũ trở thành vô nghĩa; xác nhận bằng
      grep trực tiếp: `settings.OPENAI_*` không xuất hiện ở `app/api/deps.py`)
      Test kiến trúc `test_no_env_fallback_when_registry_enabled.py`: với
      `MODEL_REGISTRY_ENABLED=true` và snapshot không có credential CHAT nào →
      raise lỗi rõ ràng (không dùng `.env`, không dựng `OpenAIChatModel` mặc định);
      quét AST xác nhận nhánh `MODEL_REGISTRY_ENABLED=false` là **nhánh duy nhất**
      trong `get_graph_models()` còn tham chiếu `settings.OPENAI_*`

**Verification:**
- [x] Tests pass: `pytest tests/graph/` và `tests/api/` — part of the 918-passed run
- [ ] (không tìm thấy — không có test `pytest.mark.integration` nào gửi 1 request
      chat thật qua endpoint và xác nhận nó đi qua fake provider trong live stack;
      `test_advisory_flow_e2e.py` chạy trong unit suite với test double, không phải
      qua docker stack)
      Kịch bản cross-repo: 1 lần chat đi qua fake provider
- [ ] (chưa chạy tay)
      Manual check: 1 request chat thật với ChatModel ACTIVE

**Dependencies:** Task 4, Task 0.2, Task 0.6

**Files likely touched:**
- `unisage-agent/app/api/deps.py`
- `unisage-agent/app/core/llm/provider_models.py`
- `unisage-agent/tests/api/test_deps.py`
- `unisage-agent/tests/core/test_no_env_fallback_when_registry_enabled.py`
- `unisage-agent/tests/core/test_provider_models.py`

**Estimated scope:** M (5 files)

---

### Task 6: Verify-before-active theo job lifecycle pull-based

**Description:** Hiện thực mục "Verification lifecycle" trong plan.md. Không có
chiều Java → Python: Python claim job từ Java, thử credential, trả kết quả. Agent
down thì job nằm chờ, không mất.

**Acceptance criteria:**
- [x] Java: `POST /internal/model-registry/verifications/claim?limit=N` — claim +
      lease 60s + sinh `leaseToken` (UUID) mới ở mỗi lần claim; response
      `{ jobId, leaseToken, attempt, leaseUntil, credential: {...ứng viên} }`
- [x] Java: `POST /verifications/{jobId}/result` body bắt buộc có `leaseToken`; xử
      lý theo đúng bước 4 "Verification lifecycle" trong plan.md, **toàn bộ trong 1
      `@Transactional`**: khoá `chat_models` `FOR UPDATE` rồi mới khoá job
      `FOR UPDATE`; kiểm `status = 'RUNNING' AND lease_token = :token AND lease_until > now()`
      (đồng hồ DB) trên row job đã khoá
- [x] Không qua điều kiện → nếu `last_result_lease_token = :token` thì
      `200 { applied: false, duplicate: true }`, ngược lại 409
      `VERIFICATION_LEASE_LOST`; cả 2 trường hợp không ghi gì, không bump version,
      không đăng ký event. Job `SUPERSEDED`/`CANCELLED` luôn rơi vào nhánh này
- [x] Qua điều kiện → ghi `last_result_lease_token`; OK → CAS trên row model đã
      khoá `candidate_generation = job.candidateGeneration AND revision = job.baseRevision`
      (đúng: chép ứng viên, `revision += 1`, trạng thái theo cột "Verify OK",
      `verifiedAt`, bump version, đăng ký `ModelRegistryChangedEvent` chỉ publish
      AFTER_COMMIT, job `SUCCEEDED`; sai: job `SUPERSEDED`, row không đổi, không
      event); TRANSIENT còn lượt → `QUEUED`, xoá token, backoff 30s/2m/10m;
      PERMANENT/hết lượt → `FAILED`, row không đổi
- [x] Đường SA sửa credential/re-verify (Task 3) dùng cùng thứ tự khoá model → job
      và chuyển job dở sang `SUPERSEDED` trong cùng transaction
- [x] Python: Celery Beat task `verify_pending_credentials` mỗi 15s + chạy ngay khi
      nhận `verification-requested` (Redis); chỉ 1 lần chạy đồng thời (Redis lock);
      gửi lại đúng `leaseToken` đã nhận; lỗi mạng khi gửi result → retry tối đa
      3 lần với cùng token (an toàn nhờ idempotency); 409 thì log info và bỏ qua
- [x] Task `verify_pending_credentials` khai báo `ignore_result=True`, **không nhận
      argument nào** (chỉ khi cần thì nhận `jobId`, không bao giờ nhận credential);
      claim response chỉ nằm trong biến cục bộ; message lỗi gửi về Java dựng bằng
      `safe_error_message` (Task 0.7). Java redact lại `error_message` trước khi lưu
- [x] Python verify: SSRF guard → 1 request nhỏ (completion 1 token cho CHAT/
      EXTRACTION; với EMBEDDING embed **3 câu probe cố định** trong
      `app/core/llm/embedding_probe.py`) timeout 15s, không retry bên trong; lỗi
      phân loại bằng `classify_llm_error` (bản tối thiểu, hoàn thiện ở Task 9)
- [x] Result của EMBEDDING gửi kèm `embeddingDimension` và `embeddingFingerprint`
      (3 vector probe); Java so với `embedding_index_identity` theo plan.md
      "Embedding identity guard" (Task 3 dùng lại phép so này cho activate)
- [x] Celery beat được cấu hình (chưa có trong `app/worker/`) và chạy trong
      compose cross-repo

**Verification:**
- [x] Tests pass ở Java (claim đồng thời 2 caller không trùng job; lease hết hạn
      claim lại được với token mới; bật test skeleton fencing của Task 0.3; ma
      trận result) và Python — full `./mvnw test` and pytest suites both green
      this pass
- [ ] (một phần — rotation-no-downtime được chứng minh live trong
      `test_registry_lifecycle.py` bằng cách gọi thẳng internal API đóng vai
      Python, nhưng không có kịch bản nào chạy qua Celery Beat/worker thật để
      chứng minh "key sai → FAILED", "503 hai lần rồi OK → ACTIVE", hay "tắt
      agent → job QUEUED, bật lại tự verify" trên live stack — các nhánh lỗi/retry
      cụ thể của Python verify loop chỉ có unit test (`test_verification_tasks.py`),
      chưa chạy qua fake provider thật)
      Kịch bản cross-repo: key sai → `FAILED` với `errorCode` hiển thị được; fake
      provider 503 hai lần rồi OK → ACTIVE sau retry; tắt agent → job `QUEUED`,
      bật lại → tự verify; rotate key của CHAT đang ACTIVE → chat vẫn chạy suốt
      quá trình, sau verify OK request mới dùng key mới
- [ ] (chưa có — không tìm thấy kịch bản cross-repo nào mô phỏng fake provider
      treo lâu hơn lease trong khi Celery worker thật đang chạy; fencing đã được
      chứng minh live nhưng bằng cách khoá `lease_until` trực tiếp qua SQL
      (`test_stale_lease_result_is_rejected_after_expiry`), không qua 1 provider
      treo thật)
      Kịch bản cross-repo fencing: fake provider treo lâu hơn lease ở lần claim
      đầu → worker khác claim lại và hoàn tất → result muộn của lần đầu bị 409
- [x] Tests pass: response `claim` (trả key ứng viên plaintext) có
      `Cache-Control: no-store` + `Pragma: no-cache` — bỏ endpoint #4 và #5 khỏi
      trạng thái pending trong `InternalNoStoreTest`; test riêng assert header này
      trên response claim có job (không chỉ response rỗng)
- [x] Tests pass: `claim_verifications()` và `post_verification_result()` của
      `BackendJavaClient` nằm trong test redirect của Task 0.1: Java trả 302 →
      raise, 0 request tới host khác, `X-Internal-Secret` không bị chuyển tiếp

**Dependencies:** Task 5, Task 3, Task 0.7

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/controller/internal/InternalModelRegistryController.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/modelregistry/ChatModelVerificationServiceImpl.java`
- `unisage-agent/app/worker/verification_tasks.py`
- `unisage-agent/app/worker/celery_app.py`
- `unisage-agent/tests/worker/test_verification_tasks.py`

**Estimated scope:** L (5+ files — tách Java / Python thành 2 task khi bắt đầu code)

---

## Checkpoint: Phase 2
- [ ] (chưa chạy tay/live end-to-end — vòng đời claim/result đã chứng minh live
      qua `test_registry_lifecycle.py`, nhưng chưa có kịch bản nào chạy hết từ
      "SA tạo ChatModel" tới "Python thực sự dùng nó trả lời 1 lần chat" qua fake
      provider thật trên live stack; xem ghi chú Task 6)
      SA tạo ChatModel mới → job verify tự chạy → ACTIVE → Python dùng nó cho 1 lần
      chat (chấp nhận restart Python thủ công)
- [ ] (chưa có kịch bản live cho nhánh key sai — xem ghi chú Task 6; hành vi có
      unit test ở cả 2 repo)
      Key sai → SA thấy lý do lỗi, không ACTIVE
- [x] Tests pass ở cả 2 repo — `./mvnw test` và pytest suite đều xanh; kịch bản
      cross-repo: một phần (xem ghi chú Task 6 cho các nhánh chưa chạy live)
- [x] `OPENAI_API_KEY`/`OPENAI_MODEL`/`OPENAI_EMBEDDING_MODEL`/`MULTI_REP_LLM_MODEL`
      xoá khỏi `.env.example`, `docker-compose`, deployment/production secret —
      confirmed (grep trống ở cả `.env.example` và `.devcontainer/`);
      `test_no_env_fallback_when_registry_enabled.py` đã bị xoá cùng lúc dọn nhánh
      fallback (xem ghi chú Task 5) nên không còn để chạy — nhưng bằng chứng mạnh
      hơn: grep xác nhận không còn tham chiếu `settings.OPENAI_*` nào trong
      `app/api/deps.py`
- [ ] Review với human trước khi làm Phase 3

---

## Phase 3: Hot reload không cần restart

### Task 7: Java bump version + publish sau commit cho mọi thay đổi registry

**Description:** Hạ tầng đã có ở Task 0.4. Task này đảm bảo **mọi** đường ghi ảnh
hưởng snapshot đều bump version và phát event.

**Acceptance criteria:**
- [x] Danh sách đường ghi được liệt kê và có test: status (SA + health PERMANENT +
      verify OK áp ứng viên), priority, delete/recover. Tạo job ứng viên
      (create/update credential) **không** bump version vì snapshot chưa đổi.
      Audit tìm thêm 1 bug: `ChatModelCandidatePromotionServiceImpl.promote()` bump
      không điều kiện trên mọi `PROMOTED`, kể cả khi row promote xong vẫn ở ngoài
      snapshot (EMBEDDING → INACTIVE, INACTIVE → INACTIVE) — đã sửa: chỉ bump khi
      `newStatus == ACTIVE`
- [x] Health TRANSIENT, thay đổi `maxRpm` không bump — đã kiểm
      `unisage-agent/app/core/model_registry.py` parse `maxRpm` vào dataclass nhưng
      không route/model_router nào đọc lại field này (chưa có Task 9/10) → **chốt:
      không bump trên maxRpm-only edit**, chỉ `priority` (Python sort theo priority
      trong purpose). `ChatModelServiceImpl.update()` trước đây bump cả trên
      maxRpm-only — đã sửa
- [x] Event `verification-requested` phát sau commit khi có job mới (Task 6) — Task 6
      chưa wire; thêm `VerificationRequestedEvent` +
      `VerificationRequestedEventPublisher` (kênh riêng
      `model-registry:verification-requested`), publish ở `create`/`update`
      (khi `credentialChanged`)/`verify`

**Verification:**
- [x] Tests pass: `./mvnw test` — mỗi đường ghi bump đúng 1 lần, rollback không publish
      (`ChatModelWritePathVersionBumpTest` mới — Testcontainers Postgres thật, không
      mock — cho updatePriority/updateStatus activate+deactivate/delete; các đường
      còn lại kiểm bằng Mockito trong `ChatModelStateTransitionTest`/
      `ChatModelRotationTest`/`EmbeddingIdentityGuardTest`/`VerificationFencingTest`)
- [ ] Manual check: `redis-cli SUBSCRIBE model-registry:updates` khi đổi priority —
      chưa làm thủ công (môi trường agent không có Redis chạy sẵn để verify tay);
      cơ chế đã được chứng minh tự động qua `ModelRegistryVersionServiceTest` (Task
      0.4) + `ChatModelWritePathVersionBumpTest` (spy publisher, real Postgres tx)

**Dependencies:** Task 0.4, Task 3, Task 6

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `unisage-backend/src/main/java/com/unisage/backend/service/modelregistry/ModelRegistryInternalServiceImpl.java`

**Estimated scope:** S (2-3 files)

---

### Task 8: Python subscribe + poll, atomic swap snapshot

**Description:** Mỗi process Python (gunicorn worker **và** Celery worker) chạy 1
subscriber `model-registry:updates` và 1 poll `/version` mỗi 30s. Version mới hơn →
lấy snapshot, build router mới, swap reference.

**Acceptance criteria:**
- [x] gunicorn: asyncio task tạo trong lifespan, huỷ khi shutdown; Celery: thread
      daemon tạo ở `worker_process_init` — confirmed in `app/core/registry_subscriber.py`/
      `app/worker/celery_app.py`
- [x] Swap là gán lại 1 reference tới object `frozen` — request đang chạy giữ
      snapshot cũ
- [x] Bỏ qua message có version ≤ hiện tại; reload lỗi → giữ snapshot cũ, log
      warning, thử lại lần poll sau
- [x] Log rõ khi poll phát hiện lệch (dấu hiệu mất pub/sub message)

**Verification:**
- [x] Tests pass: pytest với Redis giả lập — swap không ảnh hưởng request đang chạy,
      message out-of-order bị bỏ qua — `tests/core/test_registry_subscriber.py`,
      part of the green pytest run this pass
- [ ] (không tìm thấy — không có test cross-repo nào assert cả 2 gunicorn worker +
      Celery worker cùng nhận version mới qua `X-Worker-Pid`, hay đo thời gian đồng
      bộ khi chặn pub/sub; gap thật)
      Kịch bản cross-repo: đổi model active → 2 gunicorn worker + Celery worker
      đều nhận version mới trong vài giây (assert qua `X-Worker-Pid`); chặn
      pub/sub → vẫn đồng bộ trong ≤ 30s

**Dependencies:** Task 7, Task 4

**Files likely touched:**
- `unisage-agent/app/core/model_registry.py`
- `unisage-agent/app/core/registry_subscriber.py`
- `unisage-agent/app/main.py`
- `unisage-agent/app/worker/celery_app.py`

**Estimated scope:** M (3-4 files)

---

## Checkpoint: Phase 3
- [x] SA đổi model active → request mới dùng model mới trong vài giây; request đang
      chạy dùng config cũ — mechanism unit-tested (`test_registry_subscriber.py`)
      and the underlying version-bump/publish path proven live in
      `test_registry_lifecycle.py`
- [ ] (không có — xem ghi chú Task 8, chưa có kịch bản cross-repo nào đo đồng bộ
      thật giữa ≥2 gunicorn worker + Celery worker)
      Kịch bản cross-repo ≥2 worker + Celery xanh

---

## Phase 4: Python — Failover cho Chat & Extraction

### Task 9: Phân loại lỗi provider permanent vs transient

**Description:** Dựa trên exception typed của từng provider SDK (`openai`,
`anthropic`...) — model native PydanticAI theo ADR 0005 dùng thẳng SDK provider
tương ứng, exception đi ra có thể là exception gốc của SDK đó hoặc
`pydantic_ai.exceptions.ModelHTTPError` (PydanticAI có bọc lại một phần ở model
native, khác với path LiteLLM đã bỏ) — cả hai đều phải được nhận diện. Đầu vào
của Task 2, 6, 10, 15.

**Acceptance criteria:**
- [x] `classify_llm_error(exc) -> ErrorType` xử lý ít nhất: `AuthenticationError`,
      `RateLimitError` (check mã lỗi — `insufficient_quota` là PERMANENT, 429
      thường là TRANSIENT), `APIConnectionError`, timeout, provider 5xx,
      `SsrfBlockedError` (PERMANENT) — cho **từng SDK provider** trong
      `provider_models.py` (Task 5), không chỉ OpenAI — confirmed: handles openai,
      google-genai, mistralai SDK exception types explicitly (matches the actually
      supported provider set)
- [x] Test từng loại, gồm cả exception đã bị PydanticAI bọc (`ModelHTTPError`...)
- [x] Không nhận diện được → TRANSIENT

**Verification:**
- [x] Tests pass: pytest `test_llm_error_classifier.py` — part of the green
      pytest run this pass

**Dependencies:** Task 5

**Files likely touched:**
- `unisage-agent/app/core/llm_error_classifier.py`
- `unisage-agent/tests/core/test_llm_error_classifier.py`

**Estimated scope:** S (2 files)

---

### Task 10: Circuit breaker + cooldown theo credential, fallback theo priority

**Description:** TRANSIENT → cooldown (`Retry-After` nếu có, backoff ngắn nếu
không), chọn credential priority kế tiếp. PERMANENT → loại ngay khỏi routing (ghi
Redis) và gọi Task 2 để Java set `DISABLED`.

**Acceptance criteria:**
- [x] Cooldown/disabled state theo `(credential id, revision)`, dùng chung qua
      Redis cho mọi worker (key có TTL, vd `mr:cb:{id}:{revision}`) — revision mới
      sau rotate bắt đầu với state sạch
- [x] Health report gửi Java mang `credentialRevision` + `snapshotVersion` của
      credential đã gây lỗi (không phải của snapshot hiện tại lúc gửi)
- [x] Chọn credential tiếp theo theo `priority`, bỏ qua credential đang cooldown
      hoặc bị loại
- [x] Hết credential khả dụng → raise `NoAvailableCredentialError` (không loop),
      điều kiện trigger Task 15
- [x] Redis down → router vẫn chạy với state cục bộ của worker (degrade, không crash)

**Verification:**
- [x] Tests pass: pytest chuỗi lỗi TRANSIENT → chuyển đúng thứ tự, tự phục hồi sau
      cooldown; PERMANENT → gọi health API, loại ngay — part of the green pytest
      run this pass
- [ ] (không có — chưa tìm thấy kịch bản cross-repo nào cho fake provider trả 402
      rồi assert cả 2 worker chuyển sang credential B; gap thật)
      Kịch bản cross-repo: fake provider credential A trả 402 → request tiếp theo
      trên **cả 2 worker** dùng credential B

**Dependencies:** Task 9, Task 8

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/tests/core/test_model_router.py`

**Estimated scope:** M (2 files — tách circuit breaker state ra nếu vượt ~300 dòng)

---

### Task 11: Streaming — fallback trước chunk đầu, `event: error` sau chunk đầu

**Description:** Áp mục "SSE error contract" trong plan.md. Hiện
`_sse_token_generator` (`app/api/v1/chat.py`) chỉ phát `token`/`done`, lỗi graph
chỉ được ghi `MsgStatus.ERROR` ở Java mà client không biết.

**Acceptance criteria:**
- [x] `stream_agent_text()` (điểm stream duy nhất) bọc logic: lỗi trước chunk đầu →
      `model_router` chọn credential khác, chạy lại; lỗi sau chunk đầu → không
      retry, propagate
- [x] Queue giữa `run_and_persist` và `_sse_token_generator` đổi sang item có kiểu;
      `run_and_persist` đẩy error item trước sentinel khi graph lỗi
- [x] SSE phát `event: error` (`code`, `message`, `retryable`) rồi `event: done`;
      message tiếng Việt thân thiện, không lộ chi tiết provider/key
- [x] Lỗi xảy ra **trước** chunk đầu và đã hết credential → `LLM_UNAVAILABLE`
- [x] `unisage-web`: client SSE xử lý `event: error` — confirmed in
      `src/features/chat/hooks/use-chat-stream.ts` (+ its own test file)
- [x] Không có case nào tạo response trộn nội dung từ 2 model

**Verification:**
- [x] Tests pass: pytest lỗi ở chunk N>1 → `error` rồi `done`, không có token sau đó
      — part of the green pytest run (`test_chat_stream_errors.py`)
- [x] Tests pass: lỗi trước chunk đầu → fallback, client nhận response bình thường
- [ ] (không có — chưa tìm thấy kịch bản cross-repo nào cho fake provider cắt
      stream sau 3 chunk trên live stack; gap thật, chỉ unit-tested)
      Kịch bản cross-repo: fake provider cắt stream sau 3 chunk

**Dependencies:** Task 10

**Files likely touched:**
- `unisage-agent/app/graph/streaming.py`
- `unisage-agent/app/graph/streaming_session.py`
- `unisage-agent/app/api/v1/chat.py`
- `unisage-agent/tests/api/test_chat_stream_errors.py`
- `unisage-web/src/features/chat/` (client SSE)

**Estimated scope:** M (4-5 files)

---

### Task 12: Circuit breaker + failover cho Extraction

**Description:** `app/rag/enrichment/multi_representation.py` đã dùng client từ
factory SSRF (Task 0.6) nhưng vẫn đọc key từ `.env`. Chuyển qua `model_router`
purpose EXTRACTION.

**Acceptance criteria:**
- [x] Đi qua `model_router` purpose EXTRACTION; mọi credential fallback cũng dùng
      client từ `build_provider_http_client` (test kiến trúc Task 0.6 vẫn xanh)
- [x] Dùng lại `model_router`, không viết circuit breaker lần 2
- [x] Sau fallback, validate format output; không đạt → PERMANENT cho credential đó
      thay vì trả kết quả sai định dạng cho ingest — confirmed
      `MalformedExtractionResponseError` path

**Verification:**
- [x] Tests pass: pytest `test_multi_representation.py` với credential giả lập lỗi
      — part of the green pytest run this pass

**Dependencies:** Task 10

**Files likely touched:**
- `unisage-agent/app/rag/enrichment/multi_representation.py`
- `unisage-agent/tests/test_multi_representation.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 4
- [ ] (mechanism unit-tested and green — `test_model_router.py` — nhưng chưa có
      kịch bản cross-repo nào chạy thật qua fake provider trên live stack; xem
      ghi chú Task 10)
      Credential Chat chính hết credit → request mới tự chuyển credential dự phòng,
      request cũ không bị ảnh hưởng
- [ ] (unit-tested, chưa live) 429 có `Retry-After` → cooldown đúng thời gian,
      không bị loại vĩnh viễn
- [ ] (unit-tested cả 2 phía Python/web, chưa live) Lỗi sau chunk đầu → client
      nhận `event: error` rồi `done`
- [ ] Review với human trước khi làm Phase 5-6

---

## Phase 5: Python — Embedding (không failover)

### Task 13: Embedding dùng đúng 1 credential active, lỗi provider thì dừng job

**Description:** Embedding **không** đi qua `model_router`. Hiện task Celery trong
`app/worker/celery_app.py` bắt mọi exception trong vòng lặp chunk, ghi `FAILED`
cho chunk đó rồi chạy tiếp và cuối cùng publish `completed/SUCCESS` — nghĩa là key
chết vẫn cho ra job "thành công" với toàn chunk lỗi. Task này tách lỗi provider
khỏi lỗi dữ liệu của từng chunk.

**Acceptance criteria:**
- [ ] (lệnh `register_embedding_index_identity` tồn tại và làm đúng những gì mô tả
      — only-if-absent, đo fingerprint, PUT identity — nhưng KHÔNG có mục nào ghi
      vào `dev-onboard.md`: grep không thấy tham chiếu nào tới lệnh này ở đó. Ghi
      chú: `dev-onboard.md` nằm ngoài mọi git repo trong workspace, không tự sửa
      vào đây)
      **Bước rollout bắt buộc, trước khi bật registry cho embedding:** lệnh
      `python -m app.tools.register_embedding_index_identity`, chạy bằng cấu hình
      `.env` cũ (`OPENAI_EMBEDDING_MODEL`, mặc định `text-embedding-3-small`), đo
      fingerprint 3 câu probe, đọc dimension của collection Qdrant, rồi
      `PUT /internal/model-registry/embedding-index/{collection}/identity`
      (only-if-absent; 409 → đọc lại và so, khớp thì coi như đã đăng ký). Ghi vào
      `dev-onboard.md` mục production
- [x] `openai_embedder.py` dùng credential EMBEDDING active duy nhất từ snapshot,
      client lấy từ `build_provider_http_client` (đã chuyển sang factory ở Task 0.6
      — task này chỉ đổi nguồn credential từ `.env` sang snapshot)
- [x] Guard phía Python, áp cho **cả ingest và query embedding của retrieval**
      (`app/rag/retrieval/service.py`): khi load snapshot, so credential EMBEDDING
      với `embeddingIndexIdentity` trong snapshot và với dimension của collection
      Qdrant; lệch → không dùng credential đó, ingest job FAILED và retrieval trả
      lỗi rõ ràng (không trả kết quả sai), log error + alert. Collection đã có
      vector mà chưa có danh tính → cũng từ chối
- [x] Collection rỗng + chưa có danh tính → **trước batch embed đầu tiên** đo
      fingerprint rồi gọi `PUT` danh tính bằng credential EMBEDDING đang dùng; 201 →
      tiếp tục; 409 → GET lại và so, khớp → tiếp tục, lệch → job FAILED, không
      embed/upsert gì
- [x] Mọi point upsert vào Qdrant mang payload `embedding_identity_key` (hash
      provider/model/sourceRef/baseUrl/dimension của danh tính đã đăng ký), để test
      và vận hành kiểm được vector nào tạo bởi danh tính nào
- [x] Bật test "Bootstrap đồng thời phía ingest" của Task 0.3 — via
      `tests/rag/test_embedding_identity_guard.py` at the unit level (the live
      cross-repo two-worker variant is still missing, see Task 0.3's note)
- [x] Lỗi provider được bọc thành `EmbeddingProviderError` và **thoát khỏi vòng
      lặp chunk**: task publish `{"type": "failed", "reason": ...}`, cập nhật job
      `FAILED`, raise để Celery ghi FAILURE; không autoretry với lỗi này
- [x] Lỗi dữ liệu của 1 chunk (không phải provider) giữ hành vi hiện tại (chunk
      `FAILED`, chạy tiếp)
- [x] Gọi Task 2 health API khi lỗi provider
- [x] Snapshot có >1 EMBEDDING (không thể xảy ra nhờ unique index, nhưng phòng
      thủ) → log error và dùng row priority cao nhất
- [x] Không còn chunk nào bị ghi vector nửa vời sau khi task FAILED (chunk đã
      upsert trước đó được giữ, job đánh dấu cần chạy lại)

**Verification:**
- [x] Tests pass: `test_embed_chunks_task.py` — lỗi provider ở chunk 2 → task
      FAILED, không có lần gọi embed thứ 3, không publish `completed` — part of
      the green pytest run this pass
- [x] Tests pass: lỗi dữ liệu 1 chunk → task vẫn SUCCESS như cũ
- [x] Tests pass: snapshot EMBEDDING lệch danh tính index hoặc lệch dimension của
      collection → ingest FAILED, retrieval trả lỗi rõ, provider **không** được gọi
- [x] Tests pass: lệnh bootstrap chạy lần 2 → nhận 409, không ghi đè danh tính

**Dependencies:** Task 4, Task 8 (Celery nhận snapshot)

**Files likely touched:**
- `unisage-agent/app/rag/embeddings/openai_embedder.py`
- `unisage-agent/app/rag/retrieval/service.py`
- `unisage-agent/app/core/llm/embedding_probe.py`
- `unisage-agent/app/tools/register_embedding_index_identity.py`
- `unisage-agent/app/worker/celery_app.py`
- `unisage-agent/tests/test_embed_chunks_task.py`
- `unisage-agent/tests/test_embedding_provider.py`
- `unisage-agent/tests/rag/test_embedding_identity_guard.py`

**Estimated scope:** L (~8 files — tách 13a lỗi provider dừng job; 13b identity
guard + lệnh bootstrap)

---

## Checkpoint: Phase 5
- [x] Vô hiệu hoá credential embedding đang active → ingest job mới FAILED với lý do
      rõ ràng, không tự đổi model — covered by `test_embedding_identity_guard.py`
- [ ] (chưa live — `EMBEDDING_REINDEX_REQUIRED`/`EMBEDDING_ACTIVE_CONFLICT` đều
      unit-tested (`EmbeddingIdentityGuardTest` Java, `test_embedding_identity_guard.py`
      Python), nhưng chưa có kịch bản cross-repo nào chạy cả chuỗi SA-sửa-model →
      ingest/retrieval vẫn dùng model cũ trên live stack)
      Kịch bản cross-repo: SA sửa model của EMBEDDING đang ACTIVE → job
      `REINDEX_REQUIRED`, ingest + chat retrieval vẫn chạy bằng model cũ; SA activate
      EMBEDDING khác danh tính → 409, snapshot không đổi

---

## Phase 6: Slack alert

### Task 14: Slack Incoming Webhook client trong Python

**Description:** Client gửi message tới Slack Incoming Webhook (URL là secret).
Chưa có tích hợp Slack trong repo.

**Acceptance criteria:**
- [x] `app/integrations/slack_notifier.py` POST message text đơn giản
- [x] URL đọc từ `SLACK_APIKEY_ALERT_WEBHOOK_URL`, placeholder trong `.env.example`
- [x] Lỗi gọi Slack không làm crash luồng chính — log, không raise

**Verification:**
- [x] Tests pass: pytest với mock `httpx` — part of the green pytest run this pass
- [ ] (chưa chạy tay — cần người review test webhook thật và xem message xuất
      hiện đúng channel)
      Manual check: webhook test, message xuất hiện đúng channel

**Dependencies:** None (làm song song Phase 3-5)

**Files likely touched:**
- `unisage-agent/app/integrations/slack_notifier.py`
- `unisage-agent/app/core/config.py`
- `unisage-agent/.env.example`

**Estimated scope:** S (3 files)

---

### Task 15: Bắn alert đúng điều kiện

**Description:** Nối `slack_notifier` vào: lỗi PERMANENT (Task 9/10), hết credential
khả dụng (Task 10), embedding job FAILED do provider (Task 13), verify FAILED hết
lượt (Task 6). Không bắn cho TRANSIENT tự phục hồi.

**Acceptance criteria:**
- [ ] (redaction trước khi gửi đã có và unit-tested, nhưng phần "test canary quét
      payload Slack giả" không tồn tại — xem Task 0.7, không có
      `test_secret_canary.py` nào trong repo)
      Message có purpose, provider, model, lý do, thời điểm — không chứa API key;
      mọi text đi qua redactor (Task 0.7) ngay trước khi gửi, kể cả lý do lấy từ
      Java; test canary (Task 0.7) quét payload Slack giả
- [x] Debounce: cùng credential + cùng loại sự cố chỉ 1 alert / 15 phút
      (Redis `SET NX EX`)

**Verification:**
- [x] Tests pass: nhiều request đồng thời lỗi PERMANENT → `send` đúng 1 lần —
      part of the green pytest run this pass (`test_alerting.py`)
- [x] Tests pass: TRANSIENT → không gọi `send`

**Dependencies:** Task 14, Task 10, Task 13

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/core/alerting.py`
- `unisage-agent/tests/core/test_alerting.py`

**Estimated scope:** S (3 files)

---

## Checkpoint: Phase 6
- [x] Lỗi permanent → đúng 1 message Slack — unit-tested (`test_alerting.py`)
- [x] Lỗi transient tự phục hồi → không có message nào
- [ ] Review với human — điểm kết thúc MVP bắt buộc

---

## Phase 7: Bảo mật Custom URL

### Task 16: Đã chuyển lên Task 0.6

SSRF guard là gate trước Task 5/6/17, không còn nằm sau MVP. Giữ số task để không
lệch tham chiếu.

---

## Phase 8: unisage-web — Trang quản lý

### Task 17: Trang admin quản lý Provider/Model

**Description:** Trang mới trong khu admin: danh sách `ChatModel` theo purpose, form
thêm/sửa, status + lý do verify/lỗi, activate/deactivate/re-verify, priority.

**Acceptance criteria:**
- [x] Dùng layout/permission pattern admin hiện có — confirmed:
      `use-chat-model-dashboard.ts` uses `usePermissions`/`useResourcePermissions`,
      route wired through `PermissionRoute` in `system-admin.routes.tsx`
- [x] API key chỉ nhập, không bao giờ hiển thị lại (API SA chỉ có `hasApiKey`)
- [x] Hiển thị `status` + `latestVerification` với **đủ 7 trạng thái** và nhãn
      theo bảng "Trạng thái verification" trong plan.md (`QUEUED` đang chờ,
      `RUNNING` đang xác minh, `SUCCEEDED`, `FAILED` kèm `errorCode`/`errorMessage`,
      `SUPERSEDED`, `CANCELLED`, `REINDEX_REQUIRED`); "đang chờ agent" khi job
      `QUEUED` quá 5 phút; nút "Xác minh lại" — confirmed in
      `chat-model-formatters.ts`/`verification-status.ts`
- [ ] (union 7 giá trị + `Record<VerificationStatus, string>` đúng — nhưng
      **hand-maintained, không sinh bởi `contracts:sync`**: file tự nhận trong
      docstring của nó là "Hand-maintained until a `pnpm contracts:sync` tool
      exists... copied by hand". Không có cơ chế sync nào tồn tại — xem Task 0.8)
      Type TypeScript `VerificationStatus` là union đúng 7 giá trị, map nhãn UI là
      `Record<VerificationStatus, string>` (thiếu giá trị → lỗi compile). Type được
      **sinh** vào `src/generated/contracts/verification-status.ts` bởi
      `pnpm contracts:sync` từ `verification-statuses.json` của backend ở SHA trong
      `contracts.lock` — không có bản copy sửa tay. CI web chạy
      `pnpm contracts:check` (sync lại + `git diff --exit-code`) và cảnh báo khi lock
      cũ hơn HEAD `main` của backend; husky chặn commit sửa `src/generated/**` mà
      không đổi lock. Giá trị lạ từ API → hiện "Không xác định" + log, không crash
- [x] `hasPendingChange = true` → badge "Thay đổi đang chờ xác minh — đang chạy
      bằng cấu hình cũ"; verify FAIL → "Thay đổi chưa được áp dụng" kèm lỗi
- [x] Form sửa: ô API key để trống = giữ key cũ (placeholder "Để trống để giữ
      key hiện tại"); đổi host base URL → ô API key thành bắt buộc
- [x] Hiển thị `lastErrorCode`/`lastErrorAt` cho credential DISABLED
- [x] Nút activate bị disable cho PENDING/DISABLED, kèm tooltip lý do (khớp ma trận)
- [x] EMBEDDING: không còn "cảnh báo rồi cho đổi". Hiển thị danh tính của index
      (provider/model/dimension); nút activate bị disable cho credential khác danh
      tính, tooltip "Cần re-embed toàn bộ dữ liệu — ngoài phạm vi trang này"; job
      `REINDEX_REQUIRED` hiện "Thay đổi dùng được nhưng đổi model embedding — chưa
      áp dụng, cần re-index"; xử lý 409 `EMBEDDING_REINDEX_REQUIRED` và
      `EMBEDDING_ACTIVE_CONFLICT` bằng thông báo + reload
- [x] Lỗi `CHAT_MODEL_URL_NOT_ALLOWED` hiển thị ngay ở field base URL — confirmed
      `CHAT_MODEL_URL_NOT_ALLOWED_CODE` handling in `chat-model-dialog.tsx`

**Verification:**
- [ ] (chưa chạy tay — cần người review thao tác thật qua browser)
      Manual check trong browser: thêm key → PENDING → ACTIVE; key sai → thấy lý
      do; đổi priority; deactivate
- [ ] (không chạy trong lượt này — không có spec `unisage-web/e2e` nào riêng cho
      chat-models, và chạy toàn bộ suite Playwright hiện có cần dựng backend thật;
      không có bằng chứng regression nhưng cũng chưa xác nhận)
      Existing e2e suite (`unisage-web/e2e`) vẫn pass

**Dependencies:** Task 3, Task 6, Task 0.6

**Files likely touched:**
- `unisage-web/src/features/model-registry/` (thư mục mới)
- `unisage-web/src/routes/`

**Estimated scope:** L (5+ files — tách list view / form / actions nếu quá lớn)

---

## Phase 9: Routing Policy nâng cao

> Bắt đầu **sau khi** plan Cost Tracking + Budget Management
> (`changes/23-09-2026-Cost-Tracking-Budget-Management/`) đã ghi nhận cost/latency
> ổn định.

### Task 18: `routingPolicy` + điều kiện bắt buộc ở Java

**Description:** SA chọn 1 trong 4 chiến lược theo `modelPurpose`: `PRIORITY` (hành
vi hiện tại), `LOWEST_COST`, `BALANCED` (cost + latency có trọng số),
`QUALITY_FIRST`. Kèm điều kiện bắt buộc: model tối thiểu, độ trễ tối đa.

**Acceptance criteria:**
- [ ] Entity/bảng `routing_policy`: `modelPurpose`, `strategy`, `maxLatencyMs`,
      `minRequiredModel` (tuỳ chọn), API SA-facing
- [ ] Policy nằm trong snapshot nội bộ (Task 4), thay đổi policy bump version
- [ ] Migration `V28__add_routing_policy.sql` (đã đổi từ V20 — xem "Migration
      version" trong plan.md; xác nhận lại bằng `ls db/migration | sort -V | tail -1`
      trước khi tạo file thật vì Cost Tracking (V25-V27) có thể chưa merge)

**Verification:**
- [ ] Tests pass: `./mvnw test`
- [ ] Manual check: đổi strategy → snapshot Python phản ánh sau 1 chu kỳ hot-reload

**Dependencies:** Task 3, Task 7; bảng cost/latency của plan Cost Tracking (tối
thiểu là schema)

**Files likely touched:**
- `unisage-backend/src/main/java/com/unisage/backend/entity/RoutingPolicy.java`
- `unisage-backend/src/main/java/com/unisage/backend/controller/RoutingPolicyController.java`
- `unisage-backend/src/main/resources/db/migration/V28__add_routing_policy.sql`

**Estimated scope:** M (3-4 files)

---

### Task 19: Python áp policy khi chọn credential

**Description:** `model_router` (Task 10) đang chọn theo `priority` tĩnh. Đọc
`routingPolicy` từ snapshot: `LOWEST_COST` sort theo cost/1K token (dữ liệu Cost
Tracking), `BALANCED` dùng `score = w1*normalized_cost + w2*normalized_latency`
(mặc định 0.5/0.5), `QUALITY_FIRST` sort theo latency trung bình tăng dần,
`PRIORITY` giữ nguyên.

**Acceptance criteria:**
- [ ] Mỗi strategy 1 hàm sort riêng; strategy chỉ quyết định **thứ tự trong tập
      khả dụng**, không thay việc loại credential đang cooldown/DISABLED
- [ ] Điều kiện bắt buộc lọc trước khi sort — không thoả thì loại hẳn
- [ ] Test riêng cho 4 strategy với dữ liệu giả lập

**Verification:**
- [ ] Tests pass: pytest `test_model_router.py`

**Dependencies:** Task 18, Task 10

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/tests/core/test_model_router.py`

**Estimated scope:** M (2 files)

---

### Task 20: Từ chối request khi ngân sách toàn hệ thống hết

**Description:** Ngân sách **toàn hệ thống** hết → `model_router` từ chối ngay,
không coi là lỗi credential để fallback (fallback vẫn tốn tiền). Ngân sách hẹp hơn
(theo provider/nhóm) hết thì vẫn fallback bình thường.

**Acceptance criteria:**
- [ ] Kiểm cờ "system budget exhausted" (running-total Redis của Cost Tracking)
      **trước** khi chọn credential, tách khỏi vòng lặp fallback
- [ ] Cờ bật → `SystemBudgetExhaustedError`; stream trả `event: error` với
      `SYSTEM_BUDGET_EXHAUSTED` (contract Task 11), không gọi provider nào
- [ ] Test: cờ bật, N credential ACTIVE khả dụng → 0 lời gọi provider

**Verification:**
- [ ] Tests pass: pytest giả lập cờ budget-exhausted

**Dependencies:** Task 19, Task 11; running-total budget của plan Cost Tracking

**Files likely touched:**
- `unisage-agent/app/core/model_router.py`
- `unisage-agent/app/core/exceptions.py`

**Estimated scope:** S (2 files)

---

## Checkpoint: Phase 9
- [ ] `LOWEST_COST` cho CHAT → ưu tiên credential cost thấp nhất thoả điều kiện
- [ ] `QUALITY_FIRST` → ưu tiên latency thấp nhất, bỏ qua cost
- [ ] Ngân sách hệ thống = 0 → từ chối, không phát sinh lệnh gọi provider nào

---

## Checkpoint: Hoàn chỉnh
- [ ] Toàn bộ acceptance criteria ở mục Success trong intent đã xác nhận đều pass
- [ ] SA tự thêm key mới, thấy PENDING → ACTIVE (hoặc lý do lỗi), Chat dùng key
      mới — toàn bộ qua UI
- [ ] Bộ test cross-repo (`pytest -m integration`) xanh toàn bộ kịch bản
- [ ] "Gate: implementation approved" trong plan.md: cả 8 nhóm xanh (không skip,
      không pending), đính kèm link CI run/report JUnit cho từng nhóm — staged
      rotation + activate/swap embedding; `REINDEX_REQUIRED`; concurrent identity
      registration; contract coverage; gateway/no-store/redirect; SSRF; secret
      redaction; Kubernetes source-IP (hoặc "N/A — compose" kèm test thay thế)
- [ ] Ready for review
