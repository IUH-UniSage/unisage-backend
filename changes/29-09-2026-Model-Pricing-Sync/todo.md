# Todo: Model Pricing Sync + gỡ provider Mistral/Groq

Xem `plan.md` cùng thư mục cho quyết định kiến trúc, map key LiteLLM, công thức cost và danh
sách cập nhật `DECISIONS.md`/`PRODUCT.md`. Phạm vi: `unisage-backend`, `unisage-agent`,
`unisage-web` trong `/home/huy/Main`.

Quy ước commit: từng bước, message ≤ 2 dòng, không chứa tên task/phase/plan/tên file, không
thêm co-author.

---

## Phase 1: Gỡ provider Mistral/Groq

### Task 1: Backend: allowlist + vô hiệu hoá model groq/mistral

**Acceptance criteria:**
- [x] `SUPPORTED_LLM_PROVIDERS` chỉ còn `openai`, `google`; tạo/sửa model CLOUD_API với
      provider `groq`/`mistral` → `CHAT_MODEL_PROVIDER_UNSUPPORTED`
- [x] `V28__deactivate_unsupported_providers.sql`: model CLOUD_API có provider groq/mistral
      (không phân biệt hoa thường) → INACTIVE, không xoá dòng (chưa có prod/staging, chỉ ảnh
      hưởng DB dev)
- [x] Bump `config_version` sau migration không cần; agent tự bỏ credential INACTIVE ở lần
      snapshot kế tiếp (xác nhận bằng đọc code snapshot Java)

**Ghi chú:** V28 còn huỷ verification job đang mở của model groq/mistral (kể cả job xoay
credential sang groq/mistral) và bump `model_registry_version` để agent tải lại snapshot.

**Verification:**
- [x] `./mvnw test` full pass; test tham số hoá 3 provider bị từ chối
- [x] Test migration: seed 1 model groq ACTIVE trước V28 → sau V28 là INACTIVE

**Dependencies:** None

**Files likely touched:**
- `src/main/java/com/unisage/backend/service/chatmodel/ChatModelServiceImpl.java`
- `src/test/java/com/unisage/backend/service/chatmodel/ChatModelServiceImplTest.java`
- `src/main/resources/db/migration/V28__deactivate_unsupported_providers.sql`

**Estimated scope:** S

---

### Task 2: Agent: gỡ factory, error classifier, dependency extra

**Acceptance criteria:**
- [x] `provider_models.py` bỏ `groq`, `mistral`; provider lạ → lỗi như hiện tại với provider
      không hỗ trợ
- [x] `llm_error_classifier.py` bỏ nhánh `groq`/`MistralError` và import tương ứng
- [x] Test tương ứng trong `test_provider_models.py`, `test_llm_error_classifier.py` được gỡ;
      không còn `import groq`/`mistralai` trong `app/` và `tests/`
- [x] `pydantic-ai-slim` bỏ extra `groq,mistral` bằng `uv` (không sửa tay `pyproject.toml`);
      `uv.lock` chưa lock lại, xem ghi chú
- [x] `PRODUCT.md` và `DECISIONS.md` cập nhật theo mục "Cập nhật tài liệu sản phẩm" của plan

**Ghi chú:** `uv.lock` trên nhánh đã lệch từ trước (`litellm` có trong `pyproject.toml` nhưng
không có trong lock, nên `uv sync --frozen` của Dockerfile không cài `litellm`). Lock lại lúc này
kéo `litellm` vào và hạ `pydantic-ai-slim` 2.49 → 2.31. Vì vậy chỉ đổi `pyproject.toml`
(`uv ... --frozen`); lock lại một lần ở Task 9 sau khi gỡ `litellm`.

**Verification:**
- [x] `pytest` full pass (trừ 3 test đã fail sẵn trên nhánh và 1 test SSRF chập chờn khi chạy
      cả suite, chạy riêng pass)
- [x] `rg -i "groq|mistral" app tests` chỉ còn tên model self-hosted (nếu có), không còn provider

**Dependencies:** Task 1

**Files likely touched:**
- `app/core/llm/provider_models.py`
- `app/core/errors/llm_error_classifier.py`
- `tests/core/test_provider_models.py`, `tests/core/test_llm_error_classifier.py`
- `pyproject.toml`, `uv.lock` (qua `uv`)
- `docs/product/PRODUCT.md`, `docs/product/DECISIONS.md`

**Estimated scope:** M

---

### Task 3: Web: gỡ khỏi danh sách provider

**Acceptance criteria:**
- [x] `chat-model-providers.json` chỉ còn OpenAI, Google (Gemini)
- [x] `chat-model-list.tsx` bỏ icon groq/mistral; xoá `public/providers/{groq,mistral}.png`
- [x] Bảng giá tĩnh `model-pricing.ts` bỏ các model mistral (file này sẽ bị xoá hẳn ở Task 10)

**Verification:**
- [x] `pnpm typecheck`, `eslint`, `pnpm build`, `vitest run` pass
- [x] Manual: form tạo model chỉ còn 2 provider cloud

**Dependencies:** Task 1

**Files likely touched:**
- `src/features/chat-models/constants/chat-model-providers.json`
- `src/features/chat-models/components/chat-model-list.tsx`
- `public/providers/groq.png`, `public/providers/mistral.png`
- `src/features/cost-management/constants/model-pricing.ts`

**Estimated scope:** S

---

## Checkpoint: Phase 1
- [x] Full test 3 repo pass
- [x] UI/API không tạo được model groq/mistral; model cũ (nếu có) đã INACTIVE
- [x] Review với human trước khi làm Phase 2 — user yêu cầu implement liền cả plan

---

## Phase 2: Backend: giá trong DB

### Task 4: Entity `ModelPrice` + V29 + API đọc

**Acceptance criteria:**
- [ ] `V29__add_model_prices.sql`: bảng `model_prices` theo plan, UNIQUE
      `(provider, model_name)`, CHECK `source IN ('LITELLM','MANUAL')`, giá ≥ 0; bảng
      `model_price_changes` theo plan, index `(provider, model_name, changed_at)` và
      `(changed_at)`
- [ ] Seed permission `MODEL_PRICING_READ`, `MODEL_PRICING_UPDATE` (resource_type `BUDGET`)
      và gán SUPER_ADMIN, theo khuôn V27; `DataInitializer` cũng seed cho DB mới
- [ ] `GET /model-pricing?provider=&q=` trả danh sách giá (per 1M token), có `source`,
      `syncedAt`, `updatedAt`, `updatedBy`
- [ ] `ddl-auto=validate` khởi động được

**Verification:**
- [ ] `./mvnw test` (Testcontainers): list + filter; user thiếu `MODEL_PRICING_READ` → 403

**Dependencies:** Checkpoint Phase 1

**Files likely touched:**
- `src/main/resources/db/migration/V29__add_model_prices.sql`
- `entity/ModelPrice.java`, `repository/ModelPriceRepository.java`
- `controller/ModelPricingController.java`, `service/pricing/ModelPricingService(Impl).java`

**Estimated scope:** M

---

### Task 5: Đồng bộ từ LiteLLM

**Acceptance criteria:**
- [ ] `ModelPricingSyncService` tải JSON từ `MODEL_PRICING_SOURCE_URL` (timeout, cap 10 MB),
      map key theo plan (`openai` giữ tên; `gemini/` → `google` bỏ tiền tố; chỉ `chat`/`embedding`)
- [ ] Upsert bằng `JdbcTemplate` `ON CONFLICT (provider, model_name)`, chỉ cập nhật dòng
      `LITELLM` và chỉ khi giá đổi; không bao giờ ghi đè dòng `MANUAL`
- [ ] Mỗi dòng thêm/đổi giá ghi 1 dòng `model_price_changes` (`SYNC_CREATE`/`SYNC_UPDATE`,
      giá cũ/mới) trong cùng transaction
- [ ] Dòng giá âm / > $1000 per 1M / thiếu input price → bỏ qua + log; tải lỗi hoặc JSON sai
      schema → không đổi gì, trả lỗi rõ
- [ ] `@Scheduled` theo `MODEL_PRICING_SYNC_CRON` (mặc định 03:00 giờ VN) + `POST
      /model-pricing/sync` (quyền `MODEL_PRICING_UPDATE`) trả số dòng thêm/cập nhật/bỏ qua
- [ ] Có thay đổi → bump `config_version`
- [ ] Env mới thêm vào `.env.example`
- [ ] ADR mới trong `docs/adr/` (copy `0000-template.md`): nguồn giá model = DB đồng bộ
      LiteLLM + SA ghi đè, thay bảng `litellm` offline của agent; không cào trang giá provider

**Verification:**
- [ ] `./mvnw test`: server HTTP nội bộ trong test (JDK `com.sun.net.httpserver`, không thêm
      dependency) trả fixture JSON; case: map đúng 2 provider, bỏ provider khác, giữ `MANUAL`,
      giá bất thường bị bỏ, URL lỗi → DB không đổi, chạy 2 lần không đổi `updated_at` và không
      sinh thêm dòng lịch sử
- [ ] Manual: sync thật từ GitHub vào DB dev

**Dependencies:** Task 4

**Files likely touched:**
- `service/pricing/ModelPricingSyncService(Impl).java`
- `scheduler/ModelPricingSyncJob.java`
- `controller/ModelPricingController.java`
- `src/main/resources/application.properties`, `.env.example`
- `docs/adr/00NN-model-pricing-source.md`

**Estimated scope:** M

---

### Task 6: SA ghi đè / khôi phục giá

**Acceptance criteria:**
- [ ] `PUT /model-pricing/{id}` sửa giá → `source = MANUAL`, `updatedBy` = SA; đi qua JPA nên
      có audit log
- [ ] `POST /model-pricing` thêm giá tay cho model chưa có trong LiteLLM (provider phải thuộc
      allowlist)
- [ ] `DELETE /model-pricing/{id}` chỉ cho dòng `MANUAL` (= khôi phục LiteLLM ở lần sync sau)
- [ ] Mọi thay đổi bump `config_version` và ghi 1 dòng `model_price_changes`
      (`MANUAL_CREATE`/`MANUAL_UPDATE`/`MANUAL_RESET`, `changed_by` = SA)
- [ ] `ErrorCode` mới cho validate (giá âm, provider không hỗ trợ, trùng model, xoá dòng LITELLM)

**Verification:**
- [ ] `./mvnw test`: override rồi sync lại → giữ giá tay; xoá dòng MANUAL rồi sync → trở lại giá
      LiteLLM; mỗi thao tác tay có đúng 1 dòng lịch sử với giá cũ/mới đúng

**Dependencies:** Task 5

**Files likely touched:**
- `controller/ModelPricingController.java`
- `service/pricing/ModelPricingServiceImpl.java`
- `dto/request/ModelPriceRequest.java`
- `exception/ErrorCode.java`

**Estimated scope:** M

---

### Task 6b: API lịch sử giá

**Acceptance criteria:**
- [ ] `GET /model-pricing/history?provider=&model=&changeType=&from=&to=&page=&limit=`
      (quyền `MODEL_PRICING_READ`), mới nhất trước, có email người sửa
- [ ] Thời gian trả kèm offset (UTC `Z`) để FE không phải đoán múi giờ

**Verification:**
- [ ] `./mvnw test`: lọc theo từng tham số, phân trang, sync + sửa tay đều hiện đúng thứ tự

**Dependencies:** Task 6

**Files likely touched:**
- `controller/ModelPricingController.java`
- `service/pricing/ModelPricingServiceImpl.java`
- `repository/ModelPriceChangeRepository.java`, `dto/response/ModelPriceChangeResponse.java`

**Estimated scope:** S

---

### Task 7: Snapshot nội bộ cho agent

**Acceptance criteria:**
- [ ] `GET /internal/model-pricing/snapshot` → `{version, entries[{provider, modelName,
      inputPerMillion, outputPerMillion, cachedInputPerMillion}]}`
- [ ] Cùng stack bảo mật `/internal/**` như `/internal/budgets/snapshot`

**Verification:**
- [ ] `./mvnw test`: mở rộng `InternalEndpointCoverageTest` + no-store test; không secret → 403,
      sai secret → 403, đúng secret → 200

**Dependencies:** Task 4

**Files likely touched:**
- `controller/internal/InternalModelPricingController.java`
- `dto/response/internal/InternalModelPricingSnapshotResponse.java`
- `src/test/.../controller/internal/InternalEndpointCoverageTest.java`

**Estimated scope:** S

---

## Checkpoint: Phase 2
- [ ] `./mvnw test` full pass
- [ ] Sync thật vào DB dev; override/khôi phục đúng; snapshot trả đúng version
- [ ] Review với human trước khi đụng agent

---

## Phase 3: Agent: tính cost từ snapshot

### Task 8: `PricingSnapshot`

**Acceptance criteria:**
- [ ] `app/core/pricing/snapshot.py` theo khuôn `app/core/budget/snapshot.py`: load/refresh từ
      `GET /internal/model-pricing/snapshot`, tra `(provider lowercase, model_name)`, fail-open
- [ ] Refresh bằng poller trong app + task beat cho worker (khuôn `refresh_budget_snapshot`),
      chu kỳ theo biến `PRICING_SNAPSHOT_REFRESH_SECONDS` (mặc định 60)

**Verification:**
- [ ] pytest: load OK, lỗi mạng giữ snapshot cũ, chưa từng load → `None`

**Dependencies:** Task 7

**Files likely touched:**
- `app/core/pricing/snapshot.py`, `app/core/pricing/poller.py`
- `app/worker/celery_app.py`, `app/core/config.py`
- `tests/core/pricing/test_snapshot.py`

**Estimated scope:** M

---

### Task 9: `cost_calculator` dùng snapshot, gỡ `litellm`

**Acceptance criteria:**
- [ ] `calculate_actual`/`estimate` nhận thêm `provider`, tính theo công thức trong plan bằng
      `Decimal`; không có giá hoặc snapshot `None` → UNPRICED + fallback như hiện tại;
      SELF_HOSTED → FREE
- [ ] Cập nhật caller: `usage_recorder.py`, `app/api/v1/chat.py`, luồng Extraction/Embedding
- [ ] Gỡ `litellm` bằng `uv remove`, rồi `uv lock` cho lock khớp `pyproject.toml` (sửa luôn lỗi
      lock lệch từ trước, xem ghi chú Task 2); bỏ ngoại lệ `litellm` trong
      `tests/core/test_no_raw_provider_clients.py` (giữ lệnh cấm import)
- [ ] `DECISIONS.md` và `PRODUCT.md` cập nhật theo mục "Cập nhật tài liệu sản phẩm" của plan

**Verification:**
- [ ] pytest full pass; case so khớp: `gpt-4o-mini` 1000 in/500 out/200 cached cho đúng số
      LiteLLM cũ với cùng đơn giá
- [ ] Manual: seed giá DB, 1 request usage đi qua → line PRICED đúng cost

**Dependencies:** Task 8

**Files likely touched:**
- `app/core/usage/cost_calculator.py`, `app/core/usage/usage_recorder.py`
- `app/api/v1/chat.py` (+ caller Extraction/Embedding)
- `tests/core/test_cost_calculator.py`, `tests/core/test_no_raw_provider_clients.py`
- `docs/product/DECISIONS.md`, `docs/product/PRODUCT.md`

**Estimated scope:** M (tách caller Extraction/Embedding ra task riêng nếu vượt 5 file code)

---

## Checkpoint: Phase 3
- [ ] pytest full pass
- [ ] SA sửa giá → request sau dùng giá mới trong ≤ 60s
- [ ] Review với human trước khi làm web

---

## Phase 4: Web: tab Bảng giá động

### Task 10: Tab Bảng giá đọc API

**Acceptance criteria:**
- [ ] Bảng model đang đăng ký ghép với giá từ `GET /model-pricing` theo `(provider, model)`:
      badge nguồn (LiteLLM/Chỉnh tay), thời điểm đồng bộ, link trang giá chính thức của provider
- [ ] SA có `MODEL_PRICING_UPDATE`: sửa giá (dialog), thêm giá tay, khôi phục giá LiteLLM,
      nút "Đồng bộ ngay" hiện kết quả; thiếu quyền → ẩn các nút
- [ ] Ghi chú: chỉ tier Standard; giá mới áp dụng cho request sau, không sửa chi phí đã ghi
- [ ] Xoá `constants/model-pricing.ts`

**Verification:**
- [ ] `pnpm typecheck`, `eslint`, `pnpm build`, `vitest run` pass
- [ ] Manual với server chạy thật (Playwright): sửa → badge Chỉnh tay; khôi phục; đồng bộ

**Dependencies:** Task 6

**Files likely touched:**
- `src/features/cost-management/components/pricing/pricing-tab.tsx` (+ `price-dialog.tsx`)
- `src/features/cost-management/{api,queries,schemas}/…`
- `src/utils/permissions.ts`
- `src/features/cost-management/constants/model-pricing.ts` (xoá)

**Estimated scope:** M

---

### Task 11: Màn hình lịch sử giá

**Acceptance criteria:**
- [ ] Trong tab Bảng giá có khu "Lịch sử thay đổi giá": bảng thời điểm, provider/model, loại
      thay đổi (đồng bộ/chỉnh tay/khôi phục), giá cũ → mới cho từng loại giá, người sửa
- [ ] Lọc theo provider, model, loại thay đổi, khoảng thời gian; phân trang
- [ ] Từ một dòng model ở bảng giá mở được lịch sử của riêng model đó (lọc sẵn)

**Verification:**
- [ ] `pnpm typecheck`, `eslint`, `pnpm build`, `vitest run` pass
- [ ] Manual (Playwright, server thật): sync + sửa tay → lịch sử hiện đúng 2 loại, lọc đúng

**Dependencies:** Task 6b, Task 10

**Files likely touched:**
- `src/features/cost-management/components/pricing/price-history-table.tsx`
- `src/features/cost-management/components/pricing/pricing-tab.tsx`
- `src/features/cost-management/{api,queries,schemas}/…`

**Estimated scope:** M

---

## Checkpoint: Hoàn chỉnh
- [ ] Full test 3 repo pass
- [ ] Test tay trên trình duyệt với server chạy thật: giá đồng bộ, sửa, khôi phục, lịch sử giá,
      cost request mới theo giá DB
- [ ] `DECISIONS.md`/`PRODUCT.md` khớp hành vi cuối
- [ ] Ready for review
