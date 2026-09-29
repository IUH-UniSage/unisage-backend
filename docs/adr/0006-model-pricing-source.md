# ADR-0006: Giá model nằm trong DB backend-java, đồng bộ từ LiteLLM, SA ghi đè được

- **Date**: 2026-09-29
- **Status**: Accepted
- **Context story**: UNISAGE-90 (Cost Tracking + Budget Management), plan
  `changes/29-09-2026-Model-Pricing-Sync/`
- **Decision owners**: hoanghuy04

## Context

Cost Tracking cần đơn giá để tính cost từng lượt gọi provider và để giữ chỗ ngân sách. Bản đầu
dùng 2 nguồn không liên quan tới nhau:

- `unisage-agent/app/core/usage/cost_calculator.py` gọi `litellm.cost_per_token()` trên bảng giá
  đóng gói sẵn trong package `litellm`, tra bằng **tên model trần**. Bảng này đặt key Gemini API
  dưới dạng `gemini/<model>`, nên model Gemini có thể bị ghi UNPRICED dù có giá. Giá chỉ đổi khi
  nâng version `litellm`.
- Tab "Bảng giá" của `unisage-web` đọc một bảng tĩnh chép tay trong FE.

Hai nguồn có thể lệch nhau, và SA không có cách nào sửa một giá sai hay thêm giá cho model mới.
Budget chặn request dựa trên các con số này, nên giá sai nghĩa là chặn nhầm hoặc để lọt.

## Decision

### 1. Một nguồn giá duy nhất: bảng `model_prices` ở backend-java

Khoá `(provider, model_name)`, giá USD per 1M token (input, output, cached input). Agent lấy giá
qua `GET /internal/model-pricing/snapshot` (cùng cơ chế snapshot + `config_version` với budget),
web đọc `GET /model-pricing`. Agent gỡ dependency `litellm`.

### 2. Đồng bộ hằng ngày từ file JSON giá của LiteLLM

`ModelPricingSyncServiceImpl` tải `model_prices_and_context_window.json` (URL cấu hình bằng
`MODEL_PRICING_SOURCE_URL`), map `litellm_provider = openai` → `openai` giữ nguyên tên,
`litellm_provider = gemini` → `google` bỏ tiền tố `gemini/`; chỉ lấy mode `chat`/`embedding`
(`LiteLlmPriceParser`). Tải/parse lỗi thì không đổi gì. Giá âm hoặc trên $1000 per 1M bị bỏ qua.
Ghi bằng `JdbcTemplate` dưới advisory lock, không qua JPA, để audit log không nhận hàng trăm dòng
mỗi lần sync.

### 3. SA ghi đè, ghi đè thắng đồng bộ

Dòng SA sửa có `source = MANUAL`; sync không bao giờ ghi đè (có cả điều kiện `source = 'LITELLM'`
trong câu UPDATE để an toàn khi đua). "Khôi phục" = xoá dòng MANUAL, lần sync sau tạo lại giá
LiteLLM.

### 4. Lịch sử giá có bảng riêng

`model_price_changes` ghi mọi lần giá đổi (do sync hoặc SA) kèm giá cũ/mới. Audit log không đủ vì
không chứa thay đổi do sync. Cost của từng lượt gọi đã được lưu cố định lúc gọi, nên đổi giá không
sửa số liệu quá khứ.

## Consequences

**Tích cực**:

- UI, budget và cost ghi log dùng cùng một con số.
- Tra theo `(provider, model)` hết lỗi lệch key `gemini/...`.
- SA sửa được giá sai và thêm giá cho model mới mà không cần deploy.
- Agent không còn phụ thuộc `litellm` (package nặng, từng làm lệch `uv.lock`).

**Tiêu cực / rủi ro**:

- Backend-java phải gọi ra internet (GitHub) mỗi ngày. Không truy cập được thì giá đứng yên ở lần
  sync cuối; đổi `MODEL_PRICING_SOURCE_URL` sang mirror nếu cần.
- DB mới chưa có giá cho tới lần sync đầu (03:00 hoặc SA bấm "Đồng bộ ngay"); trong lúc đó mọi
  lượt gọi là UNPRICED + ước tính.
- Chỉ lấy giá tier Standard; giá batch/flex/priority và giá theo ngưỡng context (> 200k token)
  không được mô hình hoá.
- Agent dùng giá mới sau tối đa 1 chu kỳ poll snapshot (60 giây).

## Alternatives considered

1. **Giữ bảng `litellm` offline trong agent**: không cần làm gì, nhưng lệch key Gemini, giá chỉ đổi
   khi nâng version và SA không sửa được.
2. **Cào HTML trang giá của provider** (`developers.openai.com/api/docs/pricing`,
   `ai.google.dev/gemini-api/docs/pricing`): một phần bảng render bằng JS nên không có trong HTML
   tĩnh; trang AI Studio chỉ là vỏ JS; nhiều tier và giá theo loại dữ liệu khiến parser phải đoán;
   provider đổi giao diện là hỏng không báo lỗi. Chỉ giữ lại link tới trang chính thức để SA đối chiếu.
3. **Chỉ nhập tay**: đơn giản nhưng SA phải tự cập nhật mọi model, dễ quên.

## Test lock

- `ModelPricingSyncServiceImplTest`: map đúng 2 provider và bỏ phần còn lại, giữ giá MANUAL, ghi
  lịch sử giá cũ/mới khi upstream đổi, chạy lại không sinh thay đổi, nguồn lỗi (HTTP 500, JSON
  sai, không có dòng dùng được) giữ nguyên giá. Chạy trên server HTTP cục bộ, không gọi mạng.
- `ModelPricingServiceImplTest`: đọc, lọc, thời gian trả kèm offset UTC.

## References

- `src/main/resources/db/migration/V29__add_model_prices.sql`
- `service/pricing/`, `integration/LiteLlmPriceClient.java`, `scheduler/ModelPricingSyncJob.java`
- ADR-0005 (Dynamic Model Registry): cấm dùng LiteLLM SDK để gọi provider; quyết định này không
  dùng SDK, chỉ đọc file JSON giá.
- `unisage-agent/docs/product/DECISIONS.md` › giá model.
