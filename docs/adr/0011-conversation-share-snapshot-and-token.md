# ADR-0011: Link chia sẻ hội thoại: bản chụp tham chiếu và token lưu dạng rõ

- **Date**: 2026-11-03
- **Status**: Accepted
- **Context story**: UNISAGE-107 — chia sẻ hội thoại kiểu "Share link" của ChatGPT
  (`changes/02-11-2026-Conversation-Sharing/spec.md`)
- **Decision owners**: huydh

## Context

User đã đăng nhập gửi link một hội thoại cho người khác; người nhận xem **bản chụp tại lúc tạo
link** và có thể "Tiếp tục" thành một hội thoại riêng của họ. Link không hết hạn, mỗi lần chia sẻ
tạo link mới, chủ link xem lại và sao chép được link trong trang "Liên kết đã chia sẻ", và có thể
thu hồi. Hai chi tiết của thiết kế trông như "lỗi" với người đọc sau mà không biết lý do:

1. Bảng `conversation_shares` (V42) **không chứa tin nhắn**. Bản chụp chỉ là một mốc cắt
   `(cutoff_at, cutoff_message_id)` trên hội thoại gốc.
2. Cột `conversation_shares.token` lưu **dạng rõ**, trong khi `guest_sessions` (ADR-0004) chỉ lưu
   hash SHA-256 của token.

## Decision

### 1. Bản chụp là tham chiếu, không phải bản sao

Bản chụp của một link = mọi tin nhắn `COMPLETED` của `conversation_id` có
`(created_at, id) <= (cutoff_at, cutoff_message_id)`, sắp theo `(created_at, id)`
(`MessageRepository.findSnapshot`). `cutoff_*` là tin nhắn `COMPLETED` cuối cùng lúc tạo link,
đọc lại từ DB nên `cutoff_at` có đúng độ chính xác micro giây Postgres lưu.

Đủ dùng vì:

- Tin nhắn đã `COMPLETED` không bị sửa nội dung khi chủ hội thoại chat tiếp; tin mới luôn có
  `created_at` lớn hơn (xem `CreatedAtSpacing`), nên nằm ngoài mốc cắt.
- Tin nhắn của hội thoại user không bị xoá vật lý (chỉ `GuestSessionServiceImpl` xoá vật lý, và chỉ
  hội thoại guest — loại không tạo link được).
- Xoá hội thoại gốc (bởi chủ qua `ConversationServiceImpl.softDelete`, hoặc job
  `ConversationRetentionJob` qua `ConversationRepository.softDeleteBeyondNewestPerOwner`) thu hồi
  mọi link của nó trong **cùng transaction / cùng câu lệnh**; ngoài ra truy vấn đọc link
  (`ConversationShareRepository.findActiveByToken`) luôn kiểm `conversation.deleted_at IS NULL`.

Response bản chụp (`SharedMessageResponse`) là DTO riêng, hẹp hơn `MessageResponse`: bỏ
`metadata.calculation_feedback`, ticket, model, thời gian; gỡ `objectKey`/`object_key` khỏi
citation nếu có; không bao giờ đọc `message_traces`/`calculation_traces`.

### 2. Token ngẫu nhiên 256 bit, lưu dạng rõ

`SecureRandom` 32 byte, base64url không padding (43 ký tự `[A-Za-z0-9_-]`, không có ký tự gateway
chặn như `%`, `;`, `/`, `.`, `\`), `UNIQUE`. Lưu dạng rõ để trang quản lý trả lại được link cho chủ
(`GET /shares` có `token`). Token chỉ là quyền **xem một bản chụp** cho người **đã đăng nhập**, không
phải bí mật xác thực: ai đọc được bảng `conversation_shares` thì vốn đọc được bảng `messages`.

### 3. "Tiếp tục" mới sao chép, và chỉ một lần cho mỗi (link, user)

`POST /shares/view/{token}/continue` tạo `Conversation{user = người gọi, source_share_id}` rồi sao
chép từng tin nhắn của bản chụp trong một transaction. `created_at` vẫn do JPA auditing gán (ADR
0002, không set tay); thứ tự được giữ bằng cách chờ đồng hồ vượt `created_at` của bản ghi vừa lưu
thêm ≥ 2 µs trước khi lưu bản kế tiếp (`CreatedAtSpacing.waitPast`, cùng kỹ thuật `startTurn` đã
dùng). Panel hỏi lại đang `open` được đổi thành `cancelled` trong bản sao, vì trạng thái chờ phía
agent gắn với hội thoại gốc. Unique index một phần
`(source_share_id, user_id) WHERE deleted_at IS NULL` chặn bản sao thứ hai; khi hai request đua nhau
thì request thua nhận `DataIntegrityViolationException`, transaction của nó rollback, và service
(dùng `TransactionTemplate` thay vì `@Transactional` chính vì bước này) đọc lại bản sao của request
thắng trong transaction mới và trả về đó.

## Consequences

**Tích cực**:

- Tạo link là O(1): một dòng, không nhân bản tin nhắn; migration đơn giản.
- Thu hồi chỉ là `revoked_at`; mọi trường hợp link không dùng được trả cùng `SHARE_NOT_FOUND`.
- Bản sao của người nhận độc lập hoàn toàn với link (thu hồi không ảnh hưởng).

**Tiêu cực / rủi ro**:

- Bản chụp phụ thuộc giả định "tin `COMPLETED` không đổi nội dung". Metadata vẫn có thể đổi
  (chủ gửi phản hồi tính toán, agent huỷ panel hỏi lại) — phản hồi bị lọc ra; trạng thái panel thì
  người xem thấy trạng thái hiện tại chứ không phải lúc chụp.
- Một tin `STREAMING` cũ hơn mốc cắt mà sau đó mới `COMPLETED` sẽ xuất hiện trong bản chụp (hiếm:
  luồng chat chỉ có một lượt đang chạy mỗi lúc).
- Nếu sau này có tính năng xoá vật lý tin nhắn của user, FK `cutoff_message_id` sẽ chặn và phải
  quyết định lại (thu hồi link trước khi xoá).
- Token dạng rõ: lộ bản dump DB thì lộ link (nhưng cũng lộ chính nội dung hội thoại).

## Alternatives considered

1. **Sao chép tin nhắn sang bảng snapshot khi tạo link**: tốn chỗ theo số lần chia sẻ, phải dọn khi
   xoá hội thoại, mà không thêm bảo đảm nào khi tin `COMPLETED` vốn không đổi.
2. **Lưu hash token như guest session**: không hiện lại được link trong trang quản lý; người dùng
   phải tạo link mới mỗi lần muốn gửi lại.
3. **Đặt `created_at` tăng dần bằng tay khi sao chép**: vi phạm ADR 0002 (auditing không set tay) và
   bị `AuditingEntityListener` ghi đè khi persist.

## Test lock

- `ConversationShareServiceImplTest` (Mockito): mốc cắt, token URL-safe 43 ký tự, lọc trường bản
  chụp (JSON không có `conversationId` khi không phải chủ, không `calculation_feedback`, `objectKey`,
  trace), sao chép đúng trường, đóng panel, xử lý đua unique index.
- `ConversationShareIntegrationTest` (Testcontainers + HTTP thật): tin nhắn sau khi tạo link không
  xuất hiện, bản sao đúng thứ tự `created_at` tăng ngặt, 4 request "Tiếp tục" đồng thời chỉ tạo một
  bản sao, xoá hội thoại và job dọn đều thu hồi link.
- `V42MigrationTest`: token unique, unique một phần `(source_share_id, user_id)`, các FK.

## References

- `src/main/resources/db/migration/V42__conversation_shares.sql`
- `entity/ConversationShare.java`, `entity/Conversation.java` (`sourceShare`)
- `service/conversation/ConversationShareServiceImpl.java`, `service/conversation/CreatedAtSpacing.java`
- `repository/ConversationShareRepository.java`, `repository/MessageRepository.java`,
  `repository/ConversationRepository.java` (`softDeleteBeyondNewestPerOwner`)
- ADR-0002 (audit fields), ADR-0004 (guest session token hash)
