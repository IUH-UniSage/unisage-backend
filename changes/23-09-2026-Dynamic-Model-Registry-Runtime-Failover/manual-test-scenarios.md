# Kịch bản test thủ công trên UI — Dynamic Model Registry

Tài liệu này mô tả các kịch bản test tay qua trang admin (`unisage-web`), dùng
key/provider **thật** (không qua fake provider của harness tích hợp). Mục tiêu:
xác nhận hành vi failover/rotation đúng như thiết kế khi nhìn từ góc độ người dùng
cuối và Super Admin, không phải chỉ đúng ở mức code/test tự động.

## Chuẩn bị

- Trang admin `unisage-web` chạy được, đăng nhập bằng tài khoản Super Admin.
- Ít nhất 2 tài khoản/API key OpenAI thật, còn hạn mức (có thể dùng 2 project key
  khác nhau trên cùng 1 tài khoản OpenAI, hoặc 2 tài khoản khác nhau).
- `MODEL_REGISTRY_ENABLED=true` ở `unisage-agent` (đã là default sau cutover).
- (Khuyến nghị) Đã cấu hình `SLACK_APIKEY_ALERT_WEBHOOK_URL` để quan sát alert thật.

---

## Kịch bản 1: Revoke key đang ACTIVE trên OpenAI Platform → hệ thống tự chuyển sang key dự phòng

**Mục tiêu:** xác nhận khi key đang dùng bị vô hiệu hoá ở phía provider (không phải
do SA thao tác trong hệ thống), request chat mới vẫn trả lời được bình thường bằng
key dự phòng, và hệ thống tự đánh dấu key hỏng — không cần SA can thiệp tay để
request tiếp theo hoạt động.

### Bước chuẩn bị dữ liệu

1. Vào trang admin → **Provider/Model** → tạo 2 `ChatModel` cho purpose **CHAT**:
   - Credential A: `priority = 1`, `apiKey` = key OpenAI thật #1.
   - Credential B: `priority = 2`, `apiKey` = key OpenAI thật #2.
2. Chờ vài giây (job verify tự chạy qua Celery Beat) → refresh trang, xác nhận cả
   2 đều chuyển sang **status = Đang hoạt động (ACTIVE)**, `latestVerification` =
   *Đã xác minh (SUCCEEDED)*.
3. Vào ứng dụng chat (client thật, hoặc gọi trực tiếp
   `POST /api/v1/ai/chat/stream`), gửi 1 câu hỏi → xác nhận trả lời bình thường.
   Đây là baseline: request đang thật sự đi qua Credential A (priority 1).

### Bước thực hiện — revoke key ở phía OpenAI

4. Đăng nhập **platform.openai.com** (tài khoản ứng với Credential A) → vào
   **API keys** → **Revoke** đúng key đang gắn với Credential A.
   > Lưu ý: đây là hành động **không thể hoàn tác** ở phía OpenAI — dùng key test/
   > phụ, không dùng key production đang chạy dịch vụ khác.

### Bước quan sát

5. Ngay sau khi revoke (không sửa gì trong hệ thống của mình), gửi tiếp 1 câu hỏi
   chat mới.
   - **Kỳ vọng:** người dùng vẫn nhận được câu trả lời bình thường, **không thấy
     lỗi nào** ở phía client (không có `event: error`, không mất nội dung streaming
     giữa dòng).
   - Nếu gửi liên tiếp nhiều request cùng lúc: chỉ request đầu tiên chạm tới
     Credential A mới thấy độ trễ do 1 lần gọi lỗi + fallback; các request sau đó
     (trong thời gian cooldown) đi thẳng vào Credential B, không có độ trễ thêm.
6. Vào lại trang admin → Credential A:
   - **Kỳ vọng:** `status` chuyển thành **Đã tự động khoá (DISABLED)** (vì
     `AuthenticationError` từ OpenAI được phân loại PERMANENT), có `lastErrorCode`/
     `lastErrorAt` hiển thị (không lộ nội dung key).
   - Nút **Kích hoạt lại** bị disable, có tooltip giải thích cần xác minh lại.
7. (Nếu đã cấu hình Slack) Kiểm channel Slack:
   - **Kỳ vọng:** đúng **1** message alert xuất hiện, có purpose/provider/model/lý
     do/thời điểm, **không chứa API key**. Gửi thêm nhiều request lỗi liên tiếp
     trong 15 phút tiếp theo → không có alert thứ 2 (debounce).
8. Kiểm `GET /chat-models`: Credential B vẫn `status = ACTIVE`, `revision` không
   đổi (Credential B không liên quan tới lỗi của A).

### Kết quả PASS/FAIL

| # | Kiểm tra | Kỳ vọng |
|---|----------|---------|
| 1 | Request chat sau khi revoke key A | Trả lời bình thường, không lỗi tới client |
| 2 | Credential A trên admin UI | `status = DISABLED`, có `lastErrorCode` |
| 3 | Nút "Kích hoạt lại" của A | Disabled, có tooltip lý do |
| 4 | Credential B | Vẫn `ACTIVE`, không bị ảnh hưởng |
| 5 | Slack | Đúng 1 alert, không lộ key, không lặp trong 15 phút |
| 6 | Log backend-java / unisage-agent | Không có chuỗi key thật nào xuất hiện |

**Dọn dẹp sau test:** xoá hoặc revoke luôn Credential B (key test), không để key
test còn treo trên tài khoản OpenAI thật.

---

## Kịch bản 2: SA tự sửa key (rotate tay) — không có downtime

Khác với kịch bản 1 (provider tự revoke), đây là SA **chủ động đổi key** qua UI.

1. Với Credential A đang `ACTIVE` (dùng key còn sống), vào form sửa → nhập key mới
   (key khác, còn hạn mức) → Lưu.
2. **Ngay sau khi lưu**, gửi 1 câu hỏi chat → **kỳ vọng: vẫn trả lời được**, vì hệ
   thống đang chạy bằng key **cũ** trong lúc key mới chờ verify (staged rotation).
   Admin UI hiển thị badge "Thay đổi đang chờ xác minh — đang chạy bằng cấu hình cũ".
3. Chờ verify xong (vài giây) → refresh → badge biến mất, `revision` tăng lên 1.
4. Gửi lại 1 câu hỏi chat → xác nhận vẫn trả lời được (giờ dùng key mới).
5. **Test key mới sai (nhập sai tay)**: sửa lại key thành 1 chuỗi bậy (`sk-invalid`)
   → Lưu → verify FAIL → admin UI hiển thị "Thay đổi chưa được áp dụng" kèm lỗi →
   **kỳ vọng: hệ thống vẫn dùng key cũ** cho tới khi SA sửa lại đúng.

---

## Kịch bản 3: Hết credential khả dụng (rút toàn bộ key)

1. Từ trạng thái Kịch bản 1 (Credential A đã DISABLED), tiếp tục revoke luôn key
   của Credential B trên OpenAI Platform.
2. Gửi 1 câu hỏi chat mới → **kỳ vọng:** client nhận `event: error` với
   `code = "LLM_UNAVAILABLE"`, message tiếng Việt thân thiện, **không** có nội dung
   trộn giữa 2 model, kết thúc bằng `event: done`.
3. Cả A và B đều `DISABLED` trên admin UI; có thêm 1 Slack alert riêng cho trường
   hợp "hết credential khả dụng" (khác alert của từng credential ở bước trước).
