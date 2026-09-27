# ADR-0004: Guest Session TTL + Batched Cleanup Job

- **Date**: 2026-09-07
- **Status**: Accepted
- **Context story**: UNISAGE-7 — guest chat history bị mất khi reload trang (F5)
- **Decision owners**: huydh

## Context

Trước ADR này, `Conversation.ipAddress` (đã bị xoá hoàn toàn khỏi entity trong cùng đợt thay đổi
này) chỉ là dữ liệu ghi-một-lần: dùng để so khớp IP khi gọi lại `POST /messages` vào một
conversation guest đã có, và làm khoá cho `UsageLimit` — không có cách nào để liệt kê lại lịch sử
của một guest. Comment cũ trên field này ghi thẳng: *"no TTL/cleanup job by design"* — một quyết
định có chủ đích nhưng chưa từng được viết thành ADR.

Để guest giữ được lịch sử chat sau khi F5, danh tính guest được thay bằng `GuestSession` (bảng
mới, token ngẫu nhiên 256-bit hash SHA-256, cookie httpOnly) — xem code trong
`entity/GuestSession.java`, `service/guestsession/GuestSessionServiceImpl.java`,
`controller/ConversationController.java`. Khác với field `ipAddress` cũ, `GuestSession` là một
bản ghi DB thực sự, tồn tại độc lập với browser — nếu không có cơ chế dọn dẹp, số dòng
`guest_sessions`/`conversations`/`messages`/`usage_limits` của khách vãng lai không bao giờ đăng
nhập sẽ tăng vô hạn. Đây là lý do quyết định "không cleanup" trước đây (vốn áp dụng cho một cột dữ
liệu passive, không tốn thêm bảng) không còn phù hợp một khi danh tính guest có bảng riêng, và cần
một quyết định mới về vòng đời dữ liệu — đủ điều kiện cho một ADR theo quy ước của repo này.

## Decision

### 1. TTL trượt (sliding), 30 ngày, chỉ gia hạn ở endpoint có hoạt động ghi thật

`GuestSession.expiresAt` được gia hạn thành `now + 30 ngày` trong `GuestSessionServiceImpl
.resolveOrCreate` (gọi từ `POST /conversations`) và `resolveAndTouch` (gọi từ `POST /messages`
qua `MessageServiceImpl.validateOwnership`) — cả hai đại diện cho hoạt động chat thật. Ngược lại,
`resolveReadOnly` (dùng bởi `GET /conversations/guest`, tức F5 chỉ để xem lại lịch sử) **không**
gia hạn `expiresAt` và cũng không tạo session mới nếu cookie thiếu/hết hạn. Lựa chọn này đánh đổi
lấy việc guest chỉ đọc lại lịch sử mà không chat gì thêm trong suốt 30 ngày sẽ vẫn bị hết hạn —
chấp nhận được, vì mục tiêu là TTL cho *lịch sử chat*, không phải "nhớ tôi mãi mãi", và tránh ghi
DB không cần thiết trên một request `GET` vốn có thể được gọi thường xuyên mỗi lần tải trang.

30 ngày là con số chọn theo thảo luận với người dùng repo này lúc thiết kế, không có tiền lệ ràng
buộc từ 2 dự án tham chiếu (`IUH_Project_Zalo_BE` dùng Redis TTL riêng cho refresh token, không
áp dụng trực tiếp cho use case "lịch sử chat" này).

### 2. Job dọn dẹp chạy theo batch có giới hạn, không tải hết session hết hạn vào bộ nhớ

`GuestSessionCleanupJob` (`@Scheduled`, cron mặc định `0 0 3 * * *` — 3h sáng hằng ngày, cấu hình
qua `app.guest-session.cleanup.cron`) gọi lặp `GuestSessionService.purgeExpiredBatch(batchSize)`
(500 dòng/lần, hằng số `GuestSessionCleanupJob.BATCH_SIZE` — UNISAGE-94 bỏ cấu hình này khỏi
`system_configs` vì đây là thông số tinh chỉnh nội bộ, admin không cần sửa) cho tới khi một lượt trả về ít
hơn `batchSize`, tức đã hết session hết hạn để xử lý. Chọn batch thay vì một câu lệnh xoá hàng
loạt duy nhất vì số lượng guest session hết hạn tại một thời điểm không có giới hạn trên (một
project sinh viên có thể tích luỹ hàng chục nghìn session sau vài tháng không dọn), và một
transaction ôm quá nhiều dòng có rủi ro khoá bảng lâu, ảnh hưởng tới traffic đang chạy.

### 3. Logic xoá đặt trong `GuestSessionServiceImpl`, không đặt trong `GuestSessionCleanupJob`

Lần cài đặt đầu tiên đặt `@Transactional` + toàn bộ logic xoá 4 bảng ngay trong
`GuestSessionCleanupJob.purgeOneBatch()`, gọi từ `purgeExpiredSessions()` (`@Scheduled`) bằng
self-invocation (`this.purgeOneBatch()`) — lỗi kinh điển của Spring AOP: `@Transactional` chỉ
được áp dụng khi method được gọi **từ bên ngoài class**, qua proxy Spring tạo ra; gọi `this.xxx()`
trong cùng instance bỏ qua hoàn toàn proxy đó. Xác minh trực tiếp khi chạy sống (xem mục Test
lock): mọi lần cron kích hoạt đều ném `jakarta.persistence.TransactionRequiredException:
Executing an update/delete query` vì các `@Modifying @Query` bulk-delete chạy ngoài transaction.

Quyết định: chuyển toàn bộ logic một-batch (`purgeExpiredBatch`, có `@Transactional`) sang
`GuestSessionServiceImpl` — một bean khác với `GuestSessionCleanupJob`. `GuestSessionCleanupJob`
giờ chỉ còn vòng lặp gọi `guestSessionService.purgeExpiredBatch(batchSize)` — một lời gọi
cross-bean, đi qua proxy Spring đúng cách, `@Transactional` được áp dụng thật.

### 4. Thứ tự xoá tường minh trong Java, không dùng `ON DELETE CASCADE`

Không FK nào trong `V1__baseline_schema.sql` có `ON DELETE CASCADE` — kiểm tra lại toàn bộ file
xác nhận điều này trước khi quyết định. Giữ nhất quán với convention đó thay vì thêm cascade chỉ
cho riêng `guest_sessions`: `purgeExpiredBatch` xoá tuần tự theo đúng chiều phụ thuộc khoá ngoại —
`messages` (qua `conversation.guestSession.id`) → `usage_limits` → `conversations` → cuối cùng mới
tới `guest_sessions` — trong cùng một transaction mỗi batch, để không bao giờ vi phạm FK constraint
giữa các bước.

### 5. Conversation đã `claim()` không bao giờ nằm trong phạm vi dọn dẹp

`ConversationServiceImpl.claim()` (đã sửa trong cùng đợt thay đổi) set
`conversation.setGuestSession(null)` cùng lúc với `setUser(user)`. Kết hợp với CHECK constraint
`conversations_owner_xor` (không cho phép cả `user_id` và `guest_session_id` cùng khác null), một
conversation đã claim **không thể** còn trỏ tới `guest_session_id` — nên câu lệnh
`DELETE FROM Conversation c WHERE c.guestSession.id IN :guestSessionIds` trong
`ConversationRepository.deleteByGuestSessionIdIn` không cần thêm điều kiện `AND user_id IS NULL`:
đúng ngữ nghĩa "chỉ xoá dữ liệu guest chưa từng được claim" chỉ nhờ vào ràng buộc dữ liệu, không
cần logic phòng thủ thêm ở tầng query.

## Consequences

**Tích cực**:

- Dữ liệu guest không đăng nhập, không quay lại không còn tích luỹ vô hạn — hình thành đúng policy
  mà comment cũ trên `Conversation.ipAddress` từng định làm nhưng chưa hiện thực hoá.
- Batch giới hạn (500 dòng/transaction) giữ mỗi lượt xoá nhanh, không khoá bảng lâu — an toàn cho
  một service đang phục vụ traffic thật cùng lúc job chạy lúc 3h sáng.
- Tách logic xoá sang `GuestSessionServiceImpl` (thay vì để trong scheduler) fix luôn bug
  self-invocation, đồng thời khiến `purgeExpiredBatch` có thể tái sử dụng/test độc lập với lịch
  chạy cron.

**Tiêu cực / rủi ro**:

- Guest chỉ đọc lại lịch sử (không chat gì thêm) trong 30 ngày liên tục vẫn sẽ mất lịch sử khi
  session hết hạn — chấp nhận có chủ đích (mục Decision #1), nhưng là hành vi có thể gây bất ngờ
  nếu không đọc ADR này.
- Cron mặc định `0 0 3 * * *` chạy theo giờ hệ thống của máy chủ (JVM timezone), chưa được cấu hình
  tường minh theo UTC/Asia-Ho_Chi_Minh — nếu triển khai đa múi giờ sau này cần rà lại.
- Batch xoá 4 bảng tuần tự (không cascade) chậm hơn về lý thuyết so với `ON DELETE CASCADE`, nhưng
  chưa đo hiệu năng thật với số lượng lớn (hàng trăm nghìn dòng) — chỉ mới xác minh với batch nhỏ
  (xem Test lock).

## Alternatives considered

1. **`ON DELETE CASCADE` trên FK `guest_session_id`**: bị loại vì không nhất quán với convention
   hiện có của schema (không FK nào trong `V1__baseline_schema.sql` dùng cascade), và làm mất khả
   năng kiểm soát tường minh thứ tự xoá — một cascade sai phạm vi (vô tình xoá luôn dữ liệu không
   nên xoá) sẽ khó phát hiện hơn một câu lệnh Java tường minh.
2. **Xoá toàn bộ session hết hạn trong một transaction/một câu lệnh, không chia batch**: bị loại vì
   không có giới hạn trên cho số lượng session hết hạn tích luỹ được — một transaction quá lớn có
   thể khoá bảng lâu hoặc timeout, đặc biệt nếu job từng bị tắt/lỗi một thời gian dài trước khi
   chạy lại.
3. **`@Transactional` trực tiếp trên `GuestSessionCleanupJob.purgeOneBatch()`, gọi qua
   self-invocation từ `purgeExpiredSessions()`**: đây là cách cài đặt ban đầu — bị loại sau khi xác
   minh sống phát hiện `TransactionRequiredException` (xem Decision #3 và Test lock). Giữ lại như
   một bài học cụ thể cho repo này: bất kỳ `@Scheduled` method nào gọi một method `@Transactional`
   khác *trong cùng class* đều có nguy cơ này.

## Test lock

Xác minh sống trên DB dev local (Postgres thật qua Docker, không phải mock), vì bộ test hiện tại
chưa có hạ tầng Testcontainers/embedded DB để assert việc này ở dạng automated integration test:

- **Bug phát hiện lúc xác minh sống (trước khi có Decision #3)**: chạy `GuestSessionCleanupJob`
  với logic xoá đặt trực tiếp trong job (self-invocation `@Transactional`) → mọi lần cron kích
  hoạt đều ném `jakarta.persistence.TransactionRequiredException: Executing an update/delete
  query` trong log ứng dụng — bulk-delete `@Modifying @Query` chạy ngoài transaction vì proxy
  Spring không được áp dụng qua self-invocation. Sau khi chuyển logic sang
  `GuestSessionServiceImpl.purgeExpiredBatch` (bean khác, gọi cross-bean từ job), lỗi biến mất.
- **Trường hợp cơ bản**: tạo 1 guest session (qua `POST /conversations` thật, cookie thật), thêm 1
  message, "claim" một conversation thứ hai cùng session bằng cách set trực tiếp
  `user_id`/`guest_session_id` qua SQL (mô phỏng `PATCH /conversations/{id}/claim`), backdate
  `expires_at` của session về quá khứ, khởi động app với cron rút ngắn còn 10 giây
  (`GUEST_SESSION_CLEANUP_CRON=*/10 * * * * *`) để không phải chờ tới 3h sáng. Sau khi cron chạy:
  `guest_sessions` row biến mất, conversation chưa claim + message của nó biến mất, conversation
  đã claim **còn nguyên** (`user_id` set, `guest_session_id` null) — đúng như kỳ vọng của cả 3
  invariant liên quan.
- **Trường hợp nhiều batch**: tạo 5 guest session hết hạn riêng biệt, chạy job với
  `batch-size=2` (nhỏ hơn 5) — log ứng dụng ghi `"Purged 5 expired guest session(s)"` sau đúng một
  lần cron kích hoạt, xác nhận vòng lặp `do { ... } while (purgedThisBatch == batchSize)` thực sự
  lặp qua nhiều batch (2 + 2 + 1) trong cùng một lần chạy, không chỉ xử lý trang đầu tiên rồi dừng.
- Unit test (mock, không cần DB): `GuestSessionServiceImplTest
  .purgeExpiredBatch_noExpiredSessions_deletesNothing`/`_expiredSessions_deletesInFkSafeOrder`
  (xác nhận thứ tự gọi 4 repository đúng như Decision #4), `GuestSessionCleanupJobTest
  .purgeExpiredSessions_singleEmptyBatch_stopsAfterOneCall`/`_multipleFullBatches_loopsUntilAPartialBatch`
  (xác nhận logic vòng lặp độc lập với DB thật).
- `./mvnw test` — toàn bộ test hiện có (bao gồm các test mới ở trên) pass.

## References

- `src/main/java/com/unisage/backend/entity/GuestSession.java`
- `src/main/java/com/unisage/backend/entity/Conversation.java` (comment trỏ về ADR này)
- `src/main/java/com/unisage/backend/service/guestsession/GuestSessionServiceImpl.java`
  (`purgeExpiredBatch`, cùng nơi chứa `resolveOrCreate`/`resolveAndTouch`/`resolveReadOnly`)
- `src/main/java/com/unisage/backend/scheduler/GuestSessionCleanupJob.java`
- `src/main/java/com/unisage/backend/repository/{GuestSession,Conversation,Message,UsageLimit}Repository.java`
  (các query bulk-delete/`findExpiredIds`)
- `src/main/resources/db/migration/V1__baseline_schema.sql` (tiền lệ không dùng `ON DELETE
  CASCADE`, đối chiếu ở Decision #4)
- `src/main/resources/application.properties` (`app.guest-session.ttl-days`,
  `app.guest-session.cleanup.cron`)
