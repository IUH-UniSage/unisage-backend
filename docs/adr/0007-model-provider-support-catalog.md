# ADR-0007: Danh sách provider gọi được model nằm trong YAML có trong git; ngày ngừng hỗ trợ đồng bộ từ LiteLLM vào DB

- **Date**: 2026-10-05
- **Status**: Accepted
- **Context story**: Trang "Mô hình và Bảng giá" cần cho SA biết lúc thêm model nên chọn provider nào (openai/google/zai) và model nào đã ngừng hỗ trợ, để khỏi phải tra web.
- **Decision owners**: huy

## Context

Sau ADR-0006, `model_prices` chứa toàn bộ giá theo token của LiteLLM (khoảng 3.700 model, khoảng 100 nhà cung cấp). Mỗi dòng cần thêm hai thông tin:

1. **Provider của UniSage gọi được model đó.** LiteLLM không có thông tin này. Nó phụ thuộc vào transport mà `unisage-agent` hỗ trợ (`app/core/llm/provider_models.py`), và phải kiểm chứng bằng cách gọi thật (đã chạy ngày 2026-10-05 qua `build_model()`).
2. **Model đã ngừng hỗ trợ hay chưa.** LiteLLM có trường `deprecation_date` (khoảng 700 model).

Có ý kiến để nút "Đồng bộ" ghi luôn một file YAML chứa cả hai thông tin trên.

## Decision

### 1. Danh sách provider: `src/main/resources/model-provider-support.yml`, sửa tay và commit

`ModelProviderSupportCatalog` đọc file này lúc khởi động; file sai định dạng thì app không lên. File có hai phần:

- `providers`: giá trị mặc định theo nhà cung cấp, ví dụ `deepseek: [openai]` cho các API tương thích OpenAI.
- `models`: kết quả gọi thử từng model, gồm `providers`, `status` (`tested`, `paid`, `restricted`, `unsupported`), `note`, `is_deprecated`.

Đồng bộ **không** ghi file này. Khi có model mới thì chạy lại script gọi thử rồi commit.

### 2. Ngừng hỗ trợ: cột `model_prices.deprecation_date`, đồng bộ từ LiteLLM

`LiteLlmPriceParser` đọc `deprecation_date`. `ModelPricingSyncServiceImpl` ghi cột này cho mọi dòng, kể cả dòng giá chỉnh tay, và không tạo dòng lịch sử giá, vì đây không phải thay đổi giá.

### 3. API `GET /model-pricing` gộp hai nguồn

Mỗi dòng trả về có thêm `supportedProviders`, `supportStatus`, `supportNote`, `deprecationDate`, `deprecated`. Trong đó `deprecated` = (`deprecationDate` ≤ hôm nay) hoặc (`is_deprecated` trong YAML). Giá trị được tính lúc đọc, nên model tự chuyển sang ngừng hỗ trợ khi tới ngày mà không cần đồng bộ lại.

## Consequences

**Tích cực**:

- Đồng bộ chỉ ghi vào DB như trước. Không phụ thuộc file system có ghi được hay không, và các instance không lệch nhau.
- Danh sách provider có lịch sử trong git và được review như code.
- Web chỉ hiển thị dữ liệu API trả về, không giữ một bản sao danh sách riêng.

**Tiêu cực / rủi ro**:

- Danh sách provider sẽ cũ dần nếu không ai chạy lại script gọi thử. Model chưa có trong file sẽ rơi về giá trị mặc định theo nhà cung cấp (`inferred`), và giao diện ghi rõ là "chưa test".
- Trạng thái `paid` là giả định: lúc test bị chặn vì key free hoặc tài khoản hết số dư, chưa kiểm chứng với gói trả phí.

## Alternatives considered

- **Nút "Đồng bộ" ghi file YAML**: bị loại. File trong jar chỉ đọc được; đặt trên volume thì mỗi instance hoặc mỗi lần deploy lại có một bản khác nhau và không vào git. Hơn nữa đồng bộ không có nguồn nào để biết provider nào gọi được model, mà chỉ biết giá và ngày ngừng hỗ trợ.
- **Giữ danh sách ở web (file TS)**: bị loại. Trạng thái ngừng hỗ trợ đã nằm ở backend, gộp tại API thì chỉ có một nguồn sự thật.
