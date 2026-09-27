# Kịch bản test thủ công trên UI — Dynamic Model Registry

Test tay qua trang admin (`unisage-web` → **Cấu hình AI**), dùng key/provider
**thật** khi cần (không qua fake provider của harness). Tài liệu này bao quát đầy
đủ vòng đời CRUD của `ChatModel` (tạo/sửa/xoá/khôi phục/kích hoạt/xác minh) trước
khi đi vào các kịch bản failover cấp hệ thống.

## Quy ước

- **SA** = Super Admin, đăng nhập trang admin.
- Mã lỗi ghi trong bảng lấy đúng từ `ErrorCode.java` — nếu UI hiện thông báo khác
  chữ nhưng đúng field/đúng tình huống thì vẫn PASS, không bắt buộc khớp từng chữ.
- Cột **Kết quả** để trống, tick ✅/❌ khi test tay.
- Mọi test dùng key thật đều dùng **key phụ/test**, đủ hạn mức, sẵn sàng revoke —
  không dùng key production.

---

## Nhóm A — CREATE: tạo `ChatModel` mới

### A1. Tạo hợp lệ, đủ 3 purpose

| # | Bước | Input | Kỳ vọng | Kết quả |
|---|------|-------|---------|---------|
| A1.1 | Tạo mới, purpose CHAT, CLOUD_API | provider=`openai`, model=`gpt-4o-mini`, apiBaseUrl=`https://api.openai.com/v1`, apiKey hợp lệ, maxRpm=`500` | 201, `status=PENDING`, `revision=0`, `hasApiKey=true`, sau vài giây job verify chạy → `ACTIVE` |  |
| A1.2 | Tạo mới, purpose EMBEDDING | tương tự, model=`text-embedding-3-small` | 201 → PENDING → verify OK → **INACTIVE** (embedding không tự động ACTIVE dù verify OK — phải SA activate tay) |  |
| A1.3 | Tạo mới, purpose EXTRACTION | tương tự | 201 → PENDING → verify OK → ACTIVE (giống CHAT) |  |
| A1.4 | Tạo `SELF_HOSTED`, không có `llmProvider` | sourceType=`SELF_HOSTED`, apiBaseUrl nội bộ hợp lệ (nằm trong `MODEL_REGISTRY_URL_ALLOWLIST`), không nhập provider | 201 thành công — SELF_HOSTED không bắt buộc provider |  |

### A2. Validate field bắt buộc / sai định dạng

| # | Input | Kỳ vọng | Mã lỗi | Kết quả |
|---|-------|---------|--------|---------|
| A2.1 | CLOUD_API, để trống `llmProvider` | 400 | `CHAT_MODEL_PROVIDER_REQUIRED` (2133) |  |
| A2.2 | CLOUD_API, để trống `apiKey` | 400 | `CHAT_MODEL_API_KEY_REQUIRED` (2134) |  |
| A2.3 | `llmProvider = "anthropic"` | 400 — chưa hỗ trợ | `CHAT_MODEL_PROVIDER_UNSUPPORTED` (2515) |  |
| A2.4 | `llmProvider = "xai"` hoặc `"deepseek"` | 400 tương tự | `CHAT_MODEL_PROVIDER_UNSUPPORTED` (2515) |  |
| A2.5 | `llmProvider = "google"` / `"groq"` / `"mistral"` | 201 — đều được hỗ trợ thật | — |  |
| A2.6 | `maxRpm = 0` hoặc số âm | 400 validation | — |  |
| A2.7 | Để trống `llmModelName` | 400 validation | — |  |

### A3. SSRF guard khi tạo (kiểm `apiBaseUrl`)

| # | `apiBaseUrl` | Kỳ vọng | Mã lỗi | Kết quả |
|---|-------------|---------|--------|---------|
| A3.1 | `http://127.0.0.1:8401` | 400 — loopback bị chặn | `CHAT_MODEL_URL_NOT_ALLOWED` (2516) |  |
| A3.2 | `http://169.254.169.254/latest/meta-data` | 400 — cloud metadata bị chặn | `CHAT_MODEL_URL_NOT_ALLOWED` |  |
| A3.3 | `http://10.0.0.5/v1` | 400 — private range bị chặn | `CHAT_MODEL_URL_NOT_ALLOWED` |  |
| A3.4 | `https://user:pass@api.openai.com/v1` | 400 — có userinfo | `CHAT_MODEL_URL_NOT_ALLOWED` |  |
| A3.5 | `https://api.openai.com/v1?x=1` | 400 — có query string | `CHAT_MODEL_URL_NOT_ALLOWED` |  |
| A3.6 | `ftp://api.openai.com/v1` | 400 — scheme không cho phép | `CHAT_MODEL_URL_NOT_ALLOWED` |  |
| A3.7 | `https://api.openai.com/v1` (URL hợp lệ thật) | 201 | — |  |
| A3.8 | `http://internal-llm.local:9000/v1` (SELF_HOSTED, chưa thêm vào allowlist) | 400 | `CHAT_MODEL_URL_NOT_ALLOWED` |  |

### A4. Bảo mật response

| # | Kiểm tra | Kỳ vọng | Kết quả |
|---|----------|---------|---------|
| A4.1 | Response body của A1.1 | Không có field `apiKey` nào chứa giá trị thật — chỉ có `hasApiKey: true` |  |
| A4.2 | Mở DevTools Network tab khi tạo | Request body có key (bắt buộc, gửi lên server), nhưng **response** không echo lại key |  |

---

## Nhóm B — READ: danh sách, filter, chi tiết

| # | Bước | Kỳ vọng | Kết quả |
|---|------|---------|---------|
| B1 | Vào trang danh sách, không filter | Thấy tất cả credential, sort theo `priority` | |
| B2 | Filter theo `modelPurpose=EMBEDDING` | Chỉ thấy các row EMBEDDING | |
| B3 | Filter theo `status=ACTIVE` | Chỉ thấy row đang hoạt động | |
| B4 | Bấm vào 1 credential → xem chi tiết | Thấy đủ: `status`, `revision`, `verifiedAt`, `lastErrorCode`, `lastErrorAt`, `latestVerification`, **không thấy `apiKey` thật ở đâu cả** (view-source / DevTools cũng không có) | |
| B5 | Credential vừa tạo, job verify đang `QUEUED` > 5 phút (ví dụ tắt Celery Beat) | Nhãn hiện "Đang chờ agent" thay vì "Đang chờ xác minh" | |

---

## Nhóm C — UPDATE: sửa credential, đúng ngữ nghĩa tri-state `apiKey`

**Bảng tri-state bắt buộc đúng** (test bằng cách gọi API trực tiếp bằng Postman/DevTools nếu form không cho nhập đủ các trường hợp biên):

| # | Payload `apiKey` gửi lên | Kỳ vọng | Kết quả |
|---|---------------------------|---------|---------|
| C1 | Không có field `apiKey` trong body | Giữ key cũ, không tạo job rotate mới nếu không đổi field credential khác | |
| C2 | `"apiKey": null` | Giữ key cũ (tương đương C1) | |
| C3 | `"apiKey": ""` hoặc `"   "` | 400 validation | |
| C4 | `"apiKey": "sk-key-moi-hop-le"` | Ứng viên mới được tạo, **row vẫn dùng key cũ** cho tới khi verify xong; UI hiện badge "Thay đổi đang chờ xác minh" | |

### C5. Đổi field credential khác (rotation) — qua UI

1. Sửa `apiKey` của credential đang `ACTIVE` → Lưu.
2. **Ngay sau khi lưu**, gửi 1 request chat thật → phải trả lời được (dùng key **cũ**).
3. Chờ verify xong (≤ 15s) → refresh → `revision` tăng đúng 1, badge "đang chờ xác minh" biến mất, `hasApiKey=true`.
4. Gửi lại request chat → xác nhận dùng key **mới** (đổi key mới thành key sai có chủ đích để kiểm chắc, hoặc xem log Java credential nào được snapshot trả về).

### C6. Đổi host `apiBaseUrl` mà không nhập lại key

| # | Payload | Kỳ vọng | Mã lỗi | Kết quả |
|---|---------|---------|--------|---------|
| C6.1 | Đổi `apiBaseUrl` sang host khác, không gửi `apiKey` | 400 — bắt buộc nhập lại key khi đổi host | `CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST` (2514), UI hiện lỗi ngay ở field API key | |
| C6.2 | Đổi `apiBaseUrl` sang host khác, **có** gửi `apiKey` mới | 200 — ứng viên rotation tạo bình thường | | |
| C6.3 | Đổi `apiBaseUrl` nhưng path khác, host giữ nguyên (`/v1` → `/v2` cùng domain) | 200 — không bắt buộc nhập lại key (chỉ đổi *host* mới bắt) | | |

### C7. Verify FAIL khi rotate

1. Sửa `apiKey` thành 1 chuỗi sai rõ ràng (`sk-invalid-xxxx`) → Lưu.
2. Chờ verify chạy → **kỳ vọng: FAIL**, UI hiện "Thay đổi chưa được áp dụng" kèm lý do lỗi.
3. Gửi request chat → **vẫn dùng key cũ**, không downtime.
4. Nút "Xác minh lại" → sửa key đúng → Lưu lại → verify OK.

### C8. `clearApiKey` — chỉ hợp lệ với SELF_HOSTED

| # | Input | Kỳ vọng | Kết quả |
|---|-------|---------|---------|
| C8.1 | Credential `CLOUD_API`, gửi `clearApiKey: true` | 400 — không cho phép | |
| C8.2 | Credential `SELF_HOSTED`, gửi `clearApiKey: true` | 200 — key bị xoá, `hasApiKey=false` | |

### C9. `modelPurpose` bất biến

| # | Bước | Kỳ vọng | Kết quả |
|---|------|---------|---------|
| C9.1 | Form sửa | Trường "Mục đích sử dụng" bị disable, không cho đổi | |
| C9.2 | Gọi API `PUT` với `modelPurpose` khác giá trị hiện tại (bỏ qua UI) | Bị ignore hoặc 400 (không được đổi purpose sau khi tạo) | |

### C10. `priority`

| # | Bước | Kỳ vọng | Kết quả |
|---|------|---------|---------|
| C10.1 | `PATCH /{id}/priority` đổi priority | 200, áp dụng ngay, **không** cần verify lại | |
| C10.2 | Danh sách sort lại đúng theo priority mới | | |

---

## Nhóm D — DELETE / RECOVER (soft-delete)

| # | Bước | Kỳ vọng | Kết quả |
|---|------|---------|---------|
| D1 | Xoá 1 credential đang `ACTIVE` | `isActive=false`, `status` chuyển thành `INACTIVE`; credential biến mất khỏi snapshot Python (không còn được dùng để gọi provider) | |
| D2 | Xoá 1 credential đang `PENDING`/`DISABLED` | Tương tự — `isActive=false`, `status=INACTIVE` | |
| D3 | Khôi phục (`recover`) credential vừa xoá | `isActive=true`, **`status` giữ nguyên `INACTIVE`** (không tự động ACTIVE lại) | |
| D4 | Sau khôi phục, bấm "Kích hoạt" | Vì đã từng verify trước đó (`verifiedAt != null`) → cho activate lại bình thường | |
| D5 | Xoá rồi cố activate (không recover trước) | Bị chặn — không tương tác được với row đã soft-delete | |

---

## Nhóm E — State machine: activate / deactivate / re-verify

| # | Trạng thái ban đầu | Hành động | Kỳ vọng | Mã lỗi (nếu có) | Kết quả |
|---|---------------------|-----------|---------|-----------------|---------|
| E1 | `PENDING` (chưa verify xong) | Bấm "Kích hoạt" | Nút hiện dạng **disabled + tooltip** "Chưa được xác minh — không thể kích hoạt", API trả 409 nếu gọi trực tiếp | `CHAT_MODEL_NOT_VERIFIED` (2512) | |
| E2 | `DISABLED` (do lỗi PERMANENT trước đó) | Bấm "Kích hoạt" | Nút disabled + tooltip "Đã bị khoá do lỗi liên tục — xác minh lại trước khi kích hoạt" | `CHAT_MODEL_NOT_VERIFIED` (2512) | |
| E3 | `DISABLED`, đã verify lại OK | Bấm "Kích hoạt" | Cho phép, chuyển `ACTIVE` | — | |
| E4 | `INACTIVE`, đã từng verify | Bấm "Kích hoạt" | Cho phép, chuyển `ACTIVE` | — | |
| E5 | `ACTIVE` | Bấm "Tạm ngưng" (deactivate) | Chuyển `INACTIVE` | — | |
| E6 | Bất kỳ | Bấm "Xác minh lại" khi đang có job `QUEUED`/`RUNNING` | Job cũ chuyển `SUPERSEDED`, tạo job mới | — | |
| E7 | 2 tab admin cùng activate 1 lúc | 1 request thành công, request thua gặp lỗi trạng thái, không crash | `CHAT_MODEL_STATUS_CONFLICT` (2510) | |

---

## Nhóm F — EMBEDDING đặc thù (không auto-failover, có identity guard)

| # | Bước | Kỳ vọng | Mã lỗi | Kết quả |
|---|------|---------|--------|---------|
| F1 | Tạo credential EMBEDDING thứ 2 (khác model, ví dụ `text-embedding-3-large`), đang có 1 EMBEDDING khác ACTIVE | Verify OK nhưng **không tự activate** (vì cần SA activate tay + embedding luôn INACTIVE sau verify) | — | |
| F2 | SA bấm "Kích hoạt" cho credential F1 (đổi model embedding) | Bị chặn — danh tính vector trong Qdrant lệch với model mới | `EMBEDDING_REINDEX_REQUIRED` (2517) | |
| F3 | SA bấm "Kích hoạt" cho credential cùng danh tính (chỉ đổi key, cùng model) | Thành công, credential EMBEDDING cũ tự chuyển `INACTIVE` | — | |
| F4 | 2 SA cùng activate 2 credential EMBEDDING khác nhau đồng thời | Chỉ 1 thắng, người thua nhận lỗi rõ, không có 2 EMBEDDING cùng ACTIVE | `EMBEDDING_ACTIVE_CONFLICT` (2511) | |
| F5 | Xem chi tiết credential EMBEDDING | *(Gap đã biết)* Không có nơi hiển thị danh tính index (provider/model/dimension) hiện tại — chỉ thấy qua log Java hoặc gọi trực tiếp `/internal/**` | — | |

---

## Nhóm G — Failover cấp hệ thống (dùng key thật, provider thật)

### G1. Revoke key đang ACTIVE trên OpenAI Platform → tự chuyển key dự phòng

1. Chuẩn bị: Credential A (`priority=1`, key thật #1), Credential B (`priority=2`,
   key thật #2), cả 2 `ACTIVE`.
2. Gửi 1 câu hỏi chat → xác nhận trả lời được (dùng key A).
3. Vào **platform.openai.com** (tài khoản gắn key A) → **API keys** → **Revoke**
   đúng key A.
   > Hành động không thể hoàn tác — chỉ dùng key test/phụ.
4. Gửi tiếp câu hỏi chat mới → **kỳ vọng: vẫn trả lời bình thường**, không lỗi tới
   client (hệ thống tự chuyển sang key B).
5. Xem lại Credential A trên admin: `status=DISABLED`, có `lastErrorCode`.
6. (Nếu có Slack) đúng 1 alert, không lộ key, không lặp trong 15 phút.
7. Credential B: vẫn `ACTIVE`, không bị ảnh hưởng.

### G2. Hết toàn bộ credential khả dụng

1. Từ trạng thái G1, revoke luôn key B.
2. Gửi câu hỏi chat mới → client nhận `event: error` (`code=LLM_UNAVAILABLE`),
   message tiếng Việt thân thiện, kết thúc bằng `event: done`, không lộ nội dung
   provider/key.
3. Cả A và B `DISABLED`; có alert riêng cho "hết credential khả dụng".

### G3. Lỗi giữa dòng streaming (sau khi đã có chunk đầu)

1. Với 1 credential đang chạy tốt, dùng cách nào đó khiến provider ngắt kết nối
   **sau khi đã trả vài chunk** (ví dụ giới hạn mạng tạm thời, hoặc test qua
   harness fake-provider với chế độ "cắt sau N chunk" nếu không có cách giả lập
   trên UI thật).
2. **Kỳ vọng:** client thấy phần text đã nhận được **giữ lại**, sau đó
   `event: error` (`code=LLM_STREAM_INTERRUPTED`) rồi `event: done` — **không**
   tự động fallback sang model khác giữa dòng (tránh trộn nội dung 2 model).

**Dọn dẹp sau Nhóm G:** revoke/xoá toàn bộ key test đã dùng, không để treo trên
tài khoản OpenAI thật.

---

## Bảng tổng hợp

| Nhóm | Số case | Đã PASS | Ghi chú |
|------|---------|---------|---------|
| A — Create | 19 | | |
| B — Read | 5 | | |
| C — Update / rotation | 15 | | |
| D — Delete/Recover | 5 | | |
| E — State machine | 7 | | |
| F — Embedding | 5 | | |
| G — Failover hệ thống | 3 kịch bản lớn | | |
