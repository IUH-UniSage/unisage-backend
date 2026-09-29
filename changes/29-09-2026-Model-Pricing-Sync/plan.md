# Implementation Plan: Model Pricing Sync + gỡ provider Mistral/Groq

## Overview

Hiện tại giá model nằm ở 2 nơi không đồng bộ:
- **Agent** tính cost bằng bảng giá LiteLLM đóng gói sẵn (`app/core/usage/cost_calculator.py`,
  `litellm.cost_per_token`), tra theo **tên model trần**. Nhiều model chỉ có trong bảng dưới
  key có tiền tố provider (vd `gemini/gemini-2.5-flash`), nên có thể bị ghi UNPRICED dù có giá.
- **Tab Bảng giá (web)** đọc một bảng tĩnh chép tay trong FE
  (`features/cost-management/constants/model-pricing.ts`).

Plan này đưa giá về **một nguồn duy nhất trong DB backend-java**:
- Job đồng bộ hằng ngày từ file JSON giá của LiteLLM.
- SA ghi đè được trên tab Bảng giá.
- Agent lấy giá qua snapshot nội bộ, giống cơ chế budget snapshot.

Đi kèm là việc **gỡ hẳn provider `mistral` và `groq`** khỏi cả 3 tầng (web, backend allowlist,
agent), vì chỉ còn hỗ trợ `openai` và `google`.

Quyết định đã chốt với user (29-09-2026):
- Không cào HTML trang giá của provider làm nguồn chính (mong manh, nhiều tier, một phần bảng
  render bằng JS). Chỉ hiển thị link trang giá chính thức để SA đối chiếu.
- Nguồn = LiteLLM JSON đồng bộ vào DB + SA ghi đè (`MANUAL` thắng `LITELLM`).
- Agent chuyển sang lấy giá từ backend, bỏ dependency `litellm`.
- Gỡ mistral/groq ở cả 3 tầng.

## Architecture Decisions

- **Bảng `model_prices`**, khoá duy nhất `(provider, model_name)`, cột giá USD **per 1M token**:
  `input_per_million`, `output_per_million` (nullable, embedding không có),
  `cached_input_per_million` (nullable), `source` (`LITELLM` | `MANUAL`), `synced_at`,
  `updated_at`, `updated_by`. Tiền `NUMERIC(18,8)` ↔ `BigDecimal`, cùng quy ước với
  `request_usage_lines`.
- **Khoá theo `(provider, model_name)`**, không theo tên model trần, để hết lệch key kiểu
  `gemini/...`. Map từ LiteLLM:
  - `openai`: các key có `litellm_provider == "openai"`, giữ nguyên tên.
  - `google`: các key có `litellm_provider == "gemini"`, bỏ tiền tố `gemini/`.
  - Chỉ lấy `mode` là `chat` hoặc `embedding`. Provider khác bỏ qua.
- **Đồng bộ ghi bằng `JdbcTemplate` (upsert `ON CONFLICT`), không qua JPA**:
  `AuditEventListener` tự ghi audit cho mọi insert/update entity qua Hibernate. Nếu sync đi qua
  JPA, mỗi lần chạy sẽ sinh hàng trăm dòng audit. Upsert chỉ cập nhật dòng `source = LITELLM`
  và chỉ khi giá thực sự đổi.
- **Lịch sử giá có bảng riêng `model_price_changes`** (append-only), vì user cần màn hình lịch
  sử riêng (29-09-2026) và audit log không chứa các thay đổi do sync:
  - Cột: `provider`, `model_name`, giá cũ/mới của 3 loại giá, `change_type`
    (`SYNC_CREATE` | `SYNC_UPDATE` | `MANUAL_CREATE` | `MANUAL_UPDATE` | `MANUAL_RESET`),
    `changed_by` (null khi sync), `changed_at`.
  - Sync ghi dòng lịch sử **trong cùng transaction** với upsert, chỉ khi giá thực sự đổi.
  - Chỉnh tay ghi dòng lịch sử trong service và vẫn có audit log tự động qua JPA.
  - Cost của từng line đã được snapshot lúc gọi, nên đổi giá không làm sai số liệu quá khứ.
- **"Khôi phục giá LiteLLM"** = xoá dòng `MANUAL`. Lần sync kế tiếp (hoặc bấm "Đồng bộ ngay")
  tạo lại dòng `LITELLM`.
- **Nguồn tải cấu hình qua env**:
  - `MODEL_PRICING_SOURCE_URL`, mặc định raw GitHub của
    `BerriAI/litellm/main/model_prices_and_context_window.json`.
  - `MODEL_PRICING_SYNC_CRON`, mặc định 03:00 giờ VN hằng ngày.
  - Timeout và giới hạn kích thước response (file hiện ~1-2 MB, cap 10 MB).
  - Tải lỗi / JSON sai schema → giữ nguyên giá cũ, log cảnh báo, không xoá gì.
- **Chặn giá bất thường** khi sync: giá âm hoặc > $1000 per 1M → bỏ qua dòng đó và log. Tránh
  một lỗi dữ liệu upstream làm budget chặn nhầm toàn hệ thống.
- **Mọi thay đổi giá bump `config_version`** qua `ModelRegistryVersionService`, giống budget
  CRUD, để agent refresh.
- **Snapshot nội bộ `GET /internal/model-pricing/snapshot`**: `{version, entries[]}`, cùng stack
  bảo mật `/internal/**` (secret + CIDR, `TRUSTED_INTERNAL_CALLER_ATTRIBUTE`) như
  `/internal/budgets/snapshot`.
- **Agent `PricingSnapshot`** theo khuôn `app/core/budget/snapshot.py`: cache module-level,
  refresh theo poller + beat, fail-open (giữ snapshot cũ khi lỗi).
  `cost_calculator` tra `(provider, model_name)`. Không có giá → UNPRICED + ước tính
  `BUDGET_RESERVATION_FALLBACK_USD`, như hiện tại. SELF_HOSTED vẫn FREE.
- **Công thức cost giữ đúng ngữ nghĩa cũ của LiteLLM**:
  `(input − cached) × input_price + cached × cached_price (hoặc input_price nếu null)
  + output × output_price`. Tính bằng `Decimal`. Có test so khớp với vài số LiteLLM cũ.
- **Chỉ lấy giá tier chuẩn (Standard)**: bỏ qua batch/flex/priority và giá > 200k context.
  Ghi rõ trên UI.
- **Permission mới**: `MODEL_PRICING_READ` (GET `/model-pricing/**`) và
  `MODEL_PRICING_UPDATE` (POST/PUT/DELETE, gồm sync). Dùng `resource_type = 'BUDGET'` để khỏi
  nới CHECK constraint lần nữa. Seed cho SUPER_ADMIN theo khuôn `V27__seed_cost_permissions.sql`.
- **Gỡ mistral/groq**:
  - Backend: bỏ khỏi `SUPPORTED_LLM_PROVIDERS`.
  - Migration: model CLOUD_API có provider groq/mistral đã tồn tại → chuyển `INACTIVE` để agent
    không nhận credential mà nó không còn dựng được.
  - Agent: bỏ nhánh trong `provider_models.py` và `llm_error_classifier.py` cùng test tương ứng;
    bỏ extra `groq,mistral` khỏi `pydantic-ai-slim` bằng `uv`.
  - Web: bỏ khỏi `chat-model-providers.json`, icon trong `chat-model-list.tsx`,
    `public/providers/{groq,mistral}.png`.

## Cập nhật tài liệu sản phẩm

Hai file trong `unisage-agent/docs/product/` phải cập nhật **trong cùng task đổi hành vi**,
không để dồn cuối (user yêu cầu 29-09-2026):
- **Task 2**:
  - `PRODUCT.md` › Business rules: danh sách `llmProvider` còn `openai`, `google` (+
    `SELF_HOSTED`).
  - `DECISIONS.md` › "Vì sao danh sách `llmProvider` không gồm mọi provider...": thêm lý do gỡ
    Groq/Mistral và việc V28 chuyển model cũ sang INACTIVE.
- **Task 9**:
  - `DECISIONS.md`: thay mục "Vì sao `litellm` chỉ dùng để định giá..." bằng mục mới "Vì sao giá
    model lấy từ backend (đồng bộ LiteLLM + SA ghi đè) thay vì bảng `litellm` offline hay cào
    trang giá provider". Mục cũ giữ lại 1 dòng trỏ sang mục mới.
  - `PRODUCT.md` › Business rules: thêm luật giá model gồm nguồn giá; `MANUAL` thắng `LITELLM`;
    chỉ tier Standard; không có giá → UNPRICED + ước tính; SELF_HOSTED miễn phí; giá mới áp dụng
    cho request sau ≤ 60s, không sửa cost quá khứ.

## Migration versions

Repo đang ở V27.
- `V28__deactivate_unsupported_providers.sql`
- `V29__add_model_prices.sql`: bảng `model_prices` + `model_price_changes` + index + seed
  permission.

## Task List

### Phase 1: Gỡ provider Mistral/Groq
- [ ] Task 1: Backend: allowlist + migration vô hiệu hoá model groq/mistral
- [ ] Task 2: Agent: gỡ factory, error classifier, dependency extra
- [ ] Task 3: Web: gỡ khỏi danh sách provider, icon, bảng giá tĩnh

### Checkpoint: Phase 1
- [ ] Full test 3 repo pass; không tạo được model groq/mistral qua UI/API

### Phase 2: Backend: giá trong DB
- [ ] Task 4: Entity `ModelPrice` + V29 + API đọc `GET /model-pricing`
- [ ] Task 5: Sync từ LiteLLM (service + job + `POST /model-pricing/sync`)
- [ ] Task 6: SA ghi đè / khôi phục giá
- [ ] Task 6b: API lịch sử giá `GET /model-pricing/history`
- [ ] Task 7: Snapshot nội bộ `GET /internal/model-pricing/snapshot`

### Checkpoint: Phase 2
- [ ] Sync thật từ GitHub vào DB dev; override và reset đúng; snapshot trả đúng version

### Phase 3: Agent: tính cost từ snapshot
- [ ] Task 8: `PricingSnapshot` (load/refresh/poller/beat)
- [ ] Task 9: `cost_calculator` dùng snapshot, gỡ `litellm`

### Checkpoint: Phase 3
- [ ] Line cost ghi đúng theo giá DB; SA sửa giá → request sau dùng giá mới trong ≤ 60s

### Phase 4: Web: tab Bảng giá động
- [ ] Task 10: Tab Bảng giá đọc API, sửa/khôi phục/đồng bộ, bỏ bảng tĩnh
- [ ] Task 11: Màn hình lịch sử giá

### Checkpoint: Hoàn chỉnh
- [ ] Full test 3 repo pass, test tay trên trình duyệt với server chạy thật
- [ ] Ready for review

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| LiteLLM đổi schema JSON hoặc sai dữ liệu | Cao: budget chặn nhầm | Validate từng dòng, chặn giá bất thường, lỗi thì giữ giá cũ; SA ghi đè được |
| GitHub không truy cập được từ môi trường deploy | Trung bình | Giá cũ vẫn dùng; nút "Đồng bộ ngay" báo lỗi rõ; `MODEL_PRICING_SOURCE_URL` đổi sang mirror được |
| DB dev đã có model groq/mistral | Thấp (chưa có prod/staging) | V28 chỉ chuyển INACTIVE, không xoá |
| Snapshot giá trễ ≤ 60s sau khi sửa | Thấp | Bump `config_version` để refresh sớm; chấp nhận độ trễ poll |
| Công thức cost mới lệch với LiteLLM cũ | Trung bình: lệch số liệu | Test so khớp số với các case LiteLLM đã có trong `test_cost_calculator.py` |
| Sync qua JPA sinh audit rác | Thấp | Sync dùng JdbcTemplate upsert; lịch sử sync nằm ở `model_price_changes` |
| Bảng lịch sử phình to | Thấp | Chỉ ghi khi giá đổi; index `(provider, model_name, changed_at)` |

## Open Questions

Đã chốt với user 29-09-2026:
- Chưa có môi trường prod/staging, nên V28 chỉ ảnh hưởng DB dev.
- Làm chung ticket UNISAGE-90 trên nhánh `feature/huydh-unisage-90-cost-tracking-management`.
- Cần màn hình lịch sử giá riêng → bảng `model_price_changes` + Task 6b, Task 11.

Chưa có câu hỏi mở.
