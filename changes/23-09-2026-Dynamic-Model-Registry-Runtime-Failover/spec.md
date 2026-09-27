# Spec: Dynamic Model Registry + Runtime Active Switch + Failover

> Tài liệu tổng hợp tính năng theo góc nhìn sản phẩm/chức năng. Chi tiết quyết
> định kiến trúc, lý do kỹ thuật và checklist triển khai xem `plan.md`/`todo.md`
> trong cùng thư mục. Kịch bản test tay xem `manual-test-scenarios.md`.

## 1. Bài toán

Trước tính năng này, `unisage-agent` (Python) đọc API key/model LLM (OpenAI) từ
`.env`, nạp một lần lúc khởi động. Hết credit hay key bị revoke thì cách duy
nhất để khắc phục là sửa `.env` và restart. Super Admin (SA) không có cách nào
quản lý provider/model qua giao diện, không biết key nào đang lỗi, và hệ thống
không có cơ chế tự phục hồi khi một credential hỏng.

## 2. Tổng quan giải pháp

- **`unisage-backend` (Java)** là mặt phẳng điều khiển (control plane): lưu
  credential đã mã hoá, cung cấp API cho SA quản lý, chạy state machine và
  vòng đời xác minh, không bao giờ tự gọi provider LLM.
- **`unisage-agent` (Python)** là mặt phẳng dữ liệu (data plane): đọc snapshot
  credential từ Java qua API nội bộ, tự làm mới khi có thay đổi (không cần
  restart), tự chuyển sang credential dự phòng khi credential đang dùng lỗi.
- **`api-gateway`** chặn toàn bộ đường gọi nội bộ (`/internal/**`) từ bên
  ngoài.
- **`unisage-web`** có trang quản trị Provider/Model cho SA.

## 3. Đối tượng dùng

| Vai trò | Tương tác |
|---|---|
| Super Admin | Tạo/sửa/xoá/kích hoạt/ưu tiên credential qua trang admin |
| `unisage-agent` (mọi worker process: gunicorn, Celery worker, Celery Beat) | Đọc snapshot, tự chuyển credential khi lỗi, báo lỗi về Java |
| Người dùng cuối (chat) | Không thấy gì thay đổi — trải nghiệm liền mạch dù credential phía sau đang bị chuyển đổi |

## 4. Tính năng theo nhóm

### 4.1. Quản lý credential (CRUD)

- Mỗi `ChatModel` gắn đúng 1 trong 3 **purpose**: `CHAT`, `EMBEDDING`,
  `EXTRACTION` — cố định từ lúc tạo, không đổi được sau.
- Nguồn (`sourceType`): `CLOUD_API` (bắt buộc `llmProvider` + `apiKey`) hoặc
  `SELF_HOSTED` (server tương thích OpenAI, provider tuỳ chọn).
- **API key chỉ nhập, không bao giờ đọc lại** — API cho SA chỉ trả `hasApiKey`
  (boolean), giá trị thật chỉ tồn tại mã hoá trong DB và trong bộ nhớ của
  Python lúc chạy.
- **Cập nhật key kiểu tri-state** — phân biệt rõ 3 trạng thái của field
  `apiKey` trong request: vắng mặt/`null` → giữ key cũ; chuỗi rỗng/toàn khoảng
  trắng → lỗi 400; giá trị mới → tạo ứng viên rotation. `clearApiKey: true`
  chỉ hợp lệ với `SELF_HOSTED`.
- Đổi **host** của `apiBaseUrl` mà không nhập lại `apiKey` → bị chặn (không
  bao giờ gửi key cũ tới 1 địa chỉ SA chưa xác nhận).
- Soft-delete (`isActive`) độc lập với vòng đời vận hành (`status`): xoá →
  `INACTIVE` + ẩn khỏi routing; khôi phục → `isActive=true`, `status` giữ
  `INACTIVE` (không tự động chạy lại, SA phải activate tay).
- `priority` áp dụng ngay, không cần verify lại — quyết định thứ tự fallback
  khi có nhiều credential cùng purpose.

### 4.2. Danh sách provider được hỗ trợ

Chỉ những provider mà cả Java (validate lúc tạo) và Python (build client thật)
đều xác nhận dùng được, không phải mọi provider mà thư viện LLM hỗ trợ:

| Provider | Trạng thái |
|---|---|
| `openai` | Hỗ trợ đầy đủ |
| `google` (Gemini) | Hỗ trợ đầy đủ |
| `groq` | Hỗ trợ đầy đủ |
| `mistral` | Hỗ trợ đầy đủ |
| `SELF_HOSTED` (server tương thích OpenAI) | Hỗ trợ đầy đủ |
| `anthropic` | **Chưa hỗ trợ** — SDK yêu cầu tầng HTTP client khác (`httpx2`) mà lớp chống SSRF hiện tại chưa có |
| `xai` | **Không hỗ trợ** — SDK dùng gRPC, không có client HTTP nào để áp cơ chế chống SSRF |
| `deepseek` | **Không hỗ trợ** — SDK cố định sẵn địa chỉ máy chủ, không khớp cách hệ thống truyền `apiBaseUrl` theo từng credential |

### 4.3. Vòng đời vận hành (state machine)

`status`: `PENDING` (mới tạo, chưa verify) → `ACTIVE`/`INACTIVE` (đã verify) →
`DISABLED` (bị tự động khoá do lỗi liên tục). Chỉ row `ACTIVE` mới được dùng để
gọi provider thật.

- **Verify-before-active**: mọi credential mới hoặc mọi thay đổi credential
  đều phải qua xác minh (Python thử gọi 1 request nhỏ) trước khi được dùng
  thật — không có đường "tạo xong dùng ngay chưa kiểm tra".
- **Rotation không downtime**: sửa credential đang `ACTIVE` không ghi trực
  tiếp vào row — giá trị mới nằm ở "ứng viên" chờ verify; hệ thống vẫn chạy
  bằng giá trị cũ suốt thời gian chờ. Verify OK mới áp dụng; verify FAIL thì
  giữ nguyên giá trị cũ và báo lỗi cho SA.
- **Chống race khi rotate liên tiếp**: nếu SA sửa credential lần nữa khi ứng
  viên trước chưa verify xong, ứng viên cũ bị đánh dấu "đã bị thay" và không
  bao giờ được áp dụng nhầm, kể cả khi worker verify cũ trả kết quả muộn.
- **Xác minh có "vé thuê" (lease + fencing token)**: đảm bảo 2 worker Python
  không bao giờ cùng xử lý 1 job xác minh; kết quả gửi trễ hoặc gửi lại nhiều
  lần đều an toàn (không áp dụng 2 lần, không áp dụng nhầm job cũ).
- Hạ tầng xác minh chạy nền tự động (Celery Beat mỗi 15 giây + đánh thức ngay
  khi có credential mới/thay đổi) — SA không cần tự bấm gì để job được xử lý.

### 4.4. Đọc registry & tự làm mới (hot-reload)

- Python không còn đọc `.env` để lấy key/model — đọc "snapshot" (ảnh chụp toàn
  bộ credential đang `ACTIVE`) từ Java qua API nội bộ, nạp lúc khởi động.
- **Không có đường lùi về `.env`**: nếu registry rỗng (không có credential
  `ACTIVE` cho purpose CHAT), Python từ chối khởi động rõ ràng thay vì âm thầm
  chạy bằng key cũ.
- Mỗi khi registry đổi (SA sửa, verify xong, key bị khoá...), Java tăng số
  phiên bản (`version`) và báo qua Redis. Mọi worker Python (gunicorn, Celery)
  tự nhận bản mới trong vài giây, không cần restart. Nếu lỡ mất tín hiệu Redis,
  mỗi worker vẫn tự kiểm tra lại theo chu kỳ (tối đa 30 giây) nên không bao giờ
  bị lệch lâu.
- Request đang chạy giữ nguyên cấu hình cũ trong suốt vòng đời của nó — swap
  không làm gián đoạn request đang xử lý.

### 4.5. Failover tự động cho Chat & Extraction

- Khi 1 credential gọi lỗi, hệ thống **phân loại lỗi**: tạm thời (rate limit,
  lỗi mạng, provider quá tải — tự phục hồi) hay vĩnh viễn (key sai/bị revoke,
  hết hạn mức — không tự phục hồi được).
- Lỗi tạm thời → credential nghỉ (cooldown) một khoảng thời gian (theo
  `Retry-After` của provider nếu có), request tiếp theo tự chuyển sang
  credential ưu tiên kế tiếp.
- Lỗi vĩnh viễn → credential bị loại ngay khỏi routing **và** tự động chuyển
  `DISABLED` bên Java (không cần SA tự tay khoá).
- Hết toàn bộ credential khả dụng cho 1 purpose → trả lỗi rõ ràng, không lặp
  vô hạn.
- **Không bao giờ trộn nội dung từ 2 model trong 1 câu trả lời**: nếu đã bắt
  đầu trả lời (stream ra ít nhất 1 đoạn) mà credential lỗi giữa dòng, hệ thống
  **không** tự chuyển model khác để "vá" — báo lỗi rõ cho client, giữ lại phần
  đã nhận. Chỉ chuyển đổi credential khi lỗi xảy ra **trước** khi có bất kỳ nội
  dung nào được gửi ra.
- Extraction (trích xuất tóm tắt/câu hỏi khi ingest tài liệu) dùng lại đúng cơ
  chế trên, không có cơ chế circuit breaker riêng.

### 4.6. Embedding — cố ý KHÔNG tự động failover

Đổi model embedding (dù cùng số chiều vector) đưa dữ liệu vào không gian ngữ
nghĩa khác — nếu tự động chuyển sang credential khác, hệ thống sẽ âm thầm ghi
vector sai vào cùng nơi với vector cũ, làm hỏng kết quả tìm kiếm mà không có
dấu hiệu lỗi nào. Vì vậy:

- Tại một thời điểm chỉ đúng **1** credential EMBEDDING được `ACTIVE` (ép cứng
  ở tầng DB, không chỉ ở code).
- Lỗi provider ở embedding → **dừng hẳn job ingest đang chạy**, không tự
  chuyển sang credential khác, không tự động phục hồi. Dữ liệu đã ghi trước đó
  vẫn giữ nguyên, job được đánh dấu cần chạy lại.
- **Danh tính của vector đang nằm trong cơ sở dữ liệu vector** (provider/model/
  số chiều + "vân tay" đo từ 3 câu mẫu cố định) được ghi nhận riêng, tách khỏi
  credential đang chạy, và **không thể sửa/xoá** sau khi đã ghi nhận. Mọi hành
  động có thể đổi embedding đang dùng (rotate, kích hoạt, kích hoạt lại sau khi
  bị khoá) đều phải khớp danh tính này — lệch thì bị chặn ngay, kèm thông báo
  "cần re-index toàn bộ dữ liệu" (việc re-index là một dự án riêng, ngoài phạm
  vi tính năng này).
- Có lệnh dòng lệnh một lần để đăng ký danh tính cho dữ liệu **đã có sẵn** từ
  trước khi bật tính năng này.

### 4.7. Cảnh báo Slack

- Bắn cảnh báo khi: 1 credential bị khoá vĩnh viễn, hết toàn bộ credential khả
  dụng cho 1 purpose, job ingest embedding bị dừng do lỗi provider, hoặc verify
  thất bại vĩnh viễn.
- **Không** bắn cảnh báo cho lỗi tạm thời tự phục hồi được — tránh làm loãng
  kênh Slack bằng những sự cố không cần người can thiệp.
- Chống dội thông báo: cùng 1 credential + cùng 1 loại sự cố chỉ nhận tối đa 1
  cảnh báo mỗi 15 phút, dù có bao nhiêu request lỗi cùng lúc.
- Nội dung tin nhắn không bao giờ chứa API key — được lọc qua bộ redact ngay
  trước khi gửi, dù nguồn lỗi đến từ đâu.

### 4.8. Bảo mật

- Mọi API nội bộ giữa Java ↔ Python (nơi có thể trả về key thật) nằm trong
  namespace riêng (`/internal/**`), xác thực bằng secret dùng chung, **không**
  đi qua JWT/RBAC người dùng, và bị `api-gateway` chặn tuyệt đối từ bên ngoài
  — kể cả người có JWT hợp lệ cũng không gọi được.
- **Chống SSRF 2 lớp**: kiểm tra URL lúc SA nhập (Java) và kiểm tra lại ở tầng
  socket ngay lúc gọi thật (Python) — chặn địa chỉ nội bộ, địa chỉ metadata
  cloud, DNS rebinding, redirect sang địa chỉ khác, proxy hệ thống.
- **Redact secret**: key không bao giờ xuất hiện thô trong log, thông báo lỗi,
  Slack, hay bất kỳ nơi lưu trữ dài hạn nào — áp dụng ở cả 2 phía Java/Python,
  và Java redact lại lần 2 dù Python đã redact trước khi gửi sang.
- Response dành cho SA không bao giờ chứa giá trị key thật ở bất kỳ hình thức
  nào.

### 4.9. Trang quản trị (SA-facing UI)

Trang **Cấu hình AI** trong khu vực quản trị hệ thống:

- Danh sách credential theo purpose, lọc theo trạng thái, sắp xếp theo độ ưu
  tiên.
- Form thêm/sửa với đầy đủ validate phía trình duyệt + hiển thị lỗi từ server
  ngay tại field liên quan (URL không hợp lệ, thiếu key khi đổi host...).
- Trạng thái xác minh hiển thị đủ 7 giá trị (đang chờ, đang xác minh, thành
  công, thất bại kèm lý do, đã bị thay, đã huỷ, cần re-index), có xử lý an
  toàn khi gặp giá trị lạ (không rõ) từ server.
- Nút Kích hoạt/Tạm ngưng/Xác minh lại, disable đúng theo ma trận trạng thái
  kèm tooltip giải thích lý do.

## 5. Giới hạn hiện tại (đã biết, có chủ đích)

- **Anthropic/xAI/DeepSeek** chưa hỗ trợ (mục 4.2) — cần thêm hạ tầng riêng
  hoặc xác nhận provider có lối đi HTTP tương thích.
- **Không hiển thị danh tính embedding index trên UI** — chưa có API dành cho
  SA để đọc thông tin này (hiện chỉ có ở API nội bộ Java↔Python).
- **Không phân biệt được** verify lỗi tạm thời "còn lượt thử" với "lượt thử
  cuối" ở phía Python — do response của Java chưa mang thông tin số lượt thử
  tối đa, nên chỉ cảnh báo Slack cho lỗi verify vĩnh viễn, chưa cảnh báo được
  khi lỗi tạm thời dùng hết toàn bộ số lượt thử.
- **Đồng bộ hợp đồng dữ liệu giữa 3 repo** (kiểu dữ liệu trạng thái verify ở
  `unisage-web`, bộ test SSRF/redact ở `unisage-agent`) hiện làm bằng tay,
  chưa có công cụ tự sinh/tự đồng bộ từ backend — cần các repo được đưa lên
  GitHub thật để công cụ đó hoạt động.
- **Chính sách định tuyến nâng cao** (ưu tiên theo chi phí/độ trễ thay vì chỉ
  theo thứ tự ưu tiên cố định) chưa triển khai — phụ thuộc một tính năng đo
  chi phí/ngân sách riêng, chưa được xây dựng.
- Chưa kiểm chứng trên môi trường Kubernetes thật (mọi test hiện tại chạy trên
  Docker Compose).

## 6. Tài liệu liên quan

- `plan.md` — quyết định kiến trúc đầy đủ, lý do kỹ thuật cho từng lựa chọn.
- `todo.md` — checklist triển khai chi tiết theo từng phần việc.
- `manual-test-scenarios.md` — kịch bản test tay trên giao diện, bao gồm toàn
  bộ CRUD credential và các kịch bản failover.
