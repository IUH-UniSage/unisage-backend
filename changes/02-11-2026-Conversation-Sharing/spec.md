# Spec: Chia sẻ hội thoại (Conversation Sharing)

> Tài liệu mô tả **cái gì** và **vì sao**. Thứ tự triển khai và rủi ro: `plan.md`.
> Checklist task: `todo.md` (cùng thư mục). Yêu cầu đã chốt với người dùng qua
> phỏng vấn ngày 02-11-2026.

## 1. Mục tiêu

User đã đăng nhập có thể **gửi link một hội thoại** cho người khác (cũng đã đăng
nhập). Người nhận mở link thì **xem bản chụp** của hội thoại. Bấm **"Tiếp tục"**
thì có một **bản sao riêng thuộc về họ**, và hỏi tiếp trong bản sao đó. Cách làm
giống "Share link" của ChatGPT: **không đồng bộ thời gian thực** giữa hai bên.

- **Người dùng:** mọi user đã đăng nhập, mọi chiều (SV ↔ SV, SV ↔ cán bộ, cán bộ ↔ cán bộ).
- **Tình huống:** chuyển câu trả lời hữu ích (học phí, thủ tục, lịch thi…) để
  người nhận khỏi hỏi lại từ đầu, rồi họ hỏi tiếp theo trường hợp của mình.
- **Thành công:** người nhận mở link, bấm "Tiếp tục" và hỏi tiếp ngay được.
  Không có đường nào để xem tài liệu ngoài quyền của họ.

## 2. Hành vi đã chốt

| # | Quy tắc |
|---|---|
| R1 | Chỉ **chủ hội thoại** (user đã đăng nhập) được tạo link. Hội thoại guest không chia sẻ được. |
| R2 | Link là **bản chụp tại lúc bấm chia sẻ**: gồm các tin nhắn `COMPLETED` tới thời điểm đó. A chat tiếp thì link không đổi. |
| R3 | **Mỗi lần chia sẻ tạo link mới.** Link cũ giữ bản chụp cũ. |
| R4 | Link **không hết hạn**. Token ngẫu nhiên ≥ 128 bit, URL-safe (không chứa `%`, `;`, `/`, `.`, `\`; gateway chặn các ký tự này). |
| R5 | **Ai đã đăng nhập mà có link** đều xem được. Chưa đăng nhập: chuyển sang trang đăng nhập, xong thì **quay lại đúng link**. |
| R6 | Người xem thấy **nguyên văn** các câu trả lời cũ, kể cả câu trích tài liệu nội bộ. Nhưng bấm mở file trích dẫn thì vẫn kiểm tra **quyền của chính người xem**. Không có quyền thì drawer hiện thông báo nhẹ (pattern có sẵn `DOCUMENT_VIEW_FORBIDDEN` → "Bạn không có quyền xem tài liệu này."), **không** hiện lỗi đỏ thô. |
| R7 | Bấm **"Tiếp tục"** thì tạo **hội thoại mới thuộc về người xem** (`user_id = B`), chứa bản sao các tin nhắn của bản chụp. Từ đó bản sao **không còn phụ thuộc link**. |
| R8 | Hỏi tiếp trong bản sao dùng **quyền tài liệu và hồ sơ của người hỏi** (B). Có sẵn nhờ kiến trúc hiện tại: agent đọc quyền từ header ở mỗi lượt và hồ sơ theo `user_id`. |
| R9 | Trang **"Liên kết đã chia sẻ"**: liệt kê link của tôi (tên hội thoại, ngày tạo, nút sao chép link), **thu hồi từng link** và **thu hồi tất cả**. |
| R10 | **Thu hồi** chỉ chặn việc **xem qua link** (mở link đã thu hồi giống như link không tồn tại). Bản sao người khác đã tạo **không bị ảnh hưởng**. |
| R11 | **Xoá hội thoại gốc** thì mọi link của nó **tự thu hồi**, kể cả khi xoá do job dọn hội thoại cũ (`ConversationRetentionJob`). |
| R12 | Mọi lỗi trong luồng chia sẻ hiện bằng **toast (`sonner`) hoặc drawer** theo pattern hiện tại, câu chữ tiếng Việt, không hiện thông báo thô từ backend. |

## 3. Ngoài phạm vi

- Đồng bộ thời gian thực giữa hai bên.
- Khách chưa đăng nhập xem link.
- Chia sẻ cho từng người cụ thể, phân quyền theo người nhận.
- Thông báo trong app hoặc email khi có người mở link.
- Thống kê lượt xem hoặc lượt "Tiếp tục".
- Chia sẻ lên mạng xã hội (Open Graph preview).

## 4. Điều kiện tiên quyết: vá 3 lỗ hổng phân quyền có sẵn

Khảo sát code (02-11-2026) thấy 3 lỗ hổng. Nếu không vá trước thì **thu hồi link
vô nghĩa**: người nhận vẫn đọc được hội thoại gốc qua UUID.

| Endpoint | Hiện trạng | Sửa |
|---|---|---|
| `GET /messages/conversation/{id}` | Public, không kiểm tra chủ (MessageServiceImpl:252) | Áp `validateOwnership` (user hoặc guest session như `send`). Hội thoại đã xoá → `CONVERSATION_NOT_FOUND`. |
| `GET /messages/{id}` | Không kiểm tra chủ | Như trên, qua hội thoại của tin nhắn. |
| `DELETE /conversations/{id}` | Không kiểm tra chủ (ConversationServiceImpl:72) | Chỉ chủ hội thoại được xoá. |
| `GET /conversations?userId=` | `userId` lấy từ query, không so với người gọi | Bỏ tham số, lấy từ JWT. Giữ tham số tạm nếu FE còn gửi, nhưng bỏ qua nó. |
| `PATCH /conversations/{id}/claim` | Không kiểm tra guest session | Yêu cầu cookie guest session khớp với hội thoại. |

Agent không đọc hai endpoint `GET` này (lịch sử đi qua `POST /messages/turn`),
nên vá không ảnh hưởng luồng chat. Cần chạy lại test e2e chat của guest.

## 5. Thiết kế

### 5.1. Dữ liệu (backend, Flyway `V42__conversation_shares.sql`)

```sql
CREATE TABLE conversation_shares (
    id               UUID PRIMARY KEY,
    token            VARCHAR(64)  NOT NULL UNIQUE,  -- URL-safe base64, 32 byte ngẫu nhiên
    conversation_id  UUID         NOT NULL REFERENCES conversations(id),
    owner_id         UUID         NOT NULL REFERENCES users(id),
    title            VARCHAR(255),                  -- tên hội thoại tại lúc chia sẻ
    cutoff_message_id UUID        NOT NULL REFERENCES messages(id), -- tin nhắn cuối của bản chụp
    cutoff_at        TIMESTAMP    NOT NULL,         -- created_at của tin nhắn cuối
    message_count    INT          NOT NULL,
    revoked_at       TIMESTAMP,
    -- + các cột BaseEntity: created_at, created_by, updated_at, updated_by, is_active
);
CREATE INDEX ON conversation_shares (owner_id, created_at DESC) WHERE revoked_at IS NULL;
CREATE INDEX ON conversation_shares (conversation_id) WHERE revoked_at IS NULL;

ALTER TABLE conversations ADD COLUMN source_share_id UUID REFERENCES conversation_shares(id);
CREATE UNIQUE INDEX ON conversations (source_share_id, user_id) WHERE deleted_at IS NULL;
```

- **Bản chụp không sao chép tin nhắn.** Bản chụp = các tin nhắn `COMPLETED` của
  hội thoại gốc có `(created_at, id) <= (cutoff_at, cutoff_message_id)`. Tin nhắn
  cũ không bị sửa nội dung khi A chat tiếp. Hội thoại gốc bị xoá thì link cũng
  đã bị thu hồi (R11). Vì vậy tham chiếu là đủ, đỡ tốn chỗ.
- **Token lưu dạng rõ** (khác guest session lưu hash) để trang "Liên kết đã chia
  sẻ" sao chép lại được link. Token là quyền xem một bản chụp, không phải mật
  khẩu. Ai đọc được DB thì vốn đã đọc được hội thoại.
- **`conversations.source_share_id`** đánh dấu bản sao đến từ link nào. Unique
  `(source_share_id, user_id)` khi chưa xoá, để bấm "Tiếp tục" hai lần (hoặc
  double-click) thì **mở lại bản sao đã có** thay vì tạo bản thứ hai.

### 5.2. API backend (qua gateway `/api/v1/master/**`)

Tất cả đặt trong `AUTHENTICATED_ONLY_PATHS`: mọi role đã đăng nhập dùng được,
không cần thêm Permission row. Danh tính lấy từ JWT, giống `/tickets/my`.

| Method | Path | Ai | Kết quả |
|---|---|---|---|
| `POST` | `/shares` body `{conversationId}` | chủ hội thoại | `201 {id, token, title, messageCount, createdAt}` |
| `GET` | `/shares?page&limit` | chính mình | trang link **chưa thu hồi** của tôi, mới nhất trước: `{id, token, title, conversationId, messageCount, createdAt}` |
| `DELETE` | `/shares/{id}` | chủ link | thu hồi 1 link (idempotent) |
| `DELETE` | `/shares` | chính mình | thu hồi tất cả link của tôi, trả số link đã thu hồi |
| `GET` | `/shares/view/{token}` | mọi user đăng nhập | bản chụp: `{title, createdAt, isOwner, messages: [{id, role, content, citations, metadata, createdAt}]}` |
| `POST` | `/shares/view/{token}/continue` | mọi user đăng nhập | `{conversationId}` của bản sao (tạo mới hoặc bản đã có) |

Mã lỗi mới (nhóm `28xx` trong `ErrorCode`, câu chữ tiếng Việt):

| Mã | HTTP | Khi nào |
|---|---|---|
| `SHARE_NOT_FOUND` 2801 | 404 | token sai, link đã thu hồi, hội thoại gốc đã xoá. Ba trường hợp **trả cùng một mã** để không lộ link nào từng tồn tại. |
| `SHARE_EMPTY_CONVERSATION` 2802 | 400 | hội thoại chưa có tin nhắn `COMPLETED` nào |
| `SHARE_GUEST_CONVERSATION` 2803 | 400 | hội thoại của guest (chưa claim) |

Không phải chủ hội thoại mà gọi `POST /shares` hoặc `DELETE /shares/{id}` thì trả
`CONVERSATION_NOT_FOUND` / `SHARE_NOT_FOUND`, không dùng 403, để không lộ id.

### 5.3. "Tiếp tục" sao chép gì

Một transaction:

1. Tạo `Conversation{user = B, title = share.title, source_share_id = share.id}`.
2. Sao chép từng tin nhắn của bản chụp, **giữ thứ tự** (`created_at` tăng dần,
   cách nhau ≥ 1 µs để `ORDER BY created_at` không đảo):

| Trường | Xử lý |
|---|---|
| `role`, `content`, `status=COMPLETED`, `citations`, `retrievalScore`, `responseTimeMs` | giữ nguyên |
| `chatModel` | giữ (chỉ để hiển thị/thống kê) |
| `metadata.calculation` | giữ (hiển thị bảng tính) |
| `metadata.clarification` | giữ để hiển thị, nhưng panel đang chờ trả lời thì **đóng lại** (không bấm được), vì trạng thái hỏi lại phía agent gắn với hội thoại gốc |
| `metadata.calculation_feedback` | **bỏ** (đánh giá của A) |
| `MessageTrace`, `CalculationTrace`, ticket | **không sao chép** (dữ liệu nội bộ của A) |

3. Phía agent **không cần sao chép gì**. Bản sao có `conversation_id` mới nên
   bảng `conversation_clarification_states` (panel chờ, `recent_calculations`
   chứa số liệu cá nhân của A) tự bắt đầu trống. Hồ sơ dùng theo `user_id = B`.

Nếu B đã có bản sao chưa xoá từ link này thì trả luôn `conversationId` đó.
`isOwner = true` (A mở link của chính mình) thì FE hiện nút "Mở hội thoại gốc"
thay cho "Tiếp tục".

### 5.4. Thu hồi khi xoá hội thoại

- `ConversationServiceImpl.delete` đặt `revoked_at = now()` cho mọi share còn
  hiệu lực của hội thoại, cùng transaction.
- `ConversationRepository.softDeleteBeyondNewestPerOwner` (job dọn hội thoại cũ):
  thêm một câu `UPDATE conversation_shares … WHERE conversation_id IN (đã xoá)`
  cùng transaction.
- Đọc bản chụp luôn kiểm tra thêm `conversation.deleted_at IS NULL` để phòng sót.

### 5.5. Web (`unisage-web`)

| Phần | Nội dung |
|---|---|
| Đăng nhập quay lại | `sign-in-screen.tsx` dùng `location.state.from` (do `PrivateRoute` gửi) nếu là đường dẫn nội bộ bắt đầu bằng `/`. Không có thì về trang mặc định của role. |
| Nút chia sẻ | Mục "Chia sẻ" trong dropdown "More" của từng hội thoại (`conversation-history.tsx`, cạnh "Xóa đoạn chat") và nút trên header chat (`chat-workspace-header.tsx`). |
| Dialog chia sẻ | Gọi `POST /shares`, hiện link `${origin}/share/{token}`, nút "Sao chép" (toast "Đã sao chép liên kết"). Ghi chú: "Người nhận cần đăng nhập. Tin nhắn bạn gửi sau khi tạo link sẽ không được chia sẻ." |
| Route `/share/:token` | Bọc `PrivateRoute`. Trang `SharedConversationPage`: banner "Hội thoại được chia sẻ · {ngày}", render tin nhắn bằng các component chat ở **chế độ chỉ đọc** (không feedback, không panel hỏi lại, không ô nhập). Chip trích dẫn vẫn mở `CitationDrawer` (R6). Nút "Tiếp tục cuộc trò chuyện" gọi `continue` rồi điều hướng `/chat/{id}`. `isOwner` thì nút "Mở hội thoại gốc". |
| Link không tồn tại | `SHARE_NOT_FOUND` → trạng thái trống "Liên kết không tồn tại hoặc đã bị thu hồi" kèm nút về trang chat. Không hiện lỗi đỏ. |
| Trang "Liên kết đã chia sẻ" | Route mới trong `UserLayout`, mục mới trong dropdown tài khoản. Bảng (`components/shared/list/*`): tên hội thoại, ngày tạo, số tin nhắn, nút sao chép link, nút thu hồi. Nút "Thu hồi tất cả". Xác nhận bằng `confirm-delete-dialog`. |
| Lỗi | Map mã lỗi `28xx` sang câu tiếng Việt và hiện bằng `toast` (sonner). Lỗi không rõ: "Không thực hiện được, vui lòng thử lại sau." |
| Code | Đặt trong `src/features/conversations/` (đang là placeholder): `api/`, `queries/`, `schemas/` (zod), `components/`. Đường dẫn trong `constants/paths.ts`, endpoint trong `constants/api-endpoints.ts`. |

### 5.6. Agent (`unisage-agent`) và gateway

- **Agent:** không đổi code. Chỉ thêm test e2e: hỏi tiếp trong bản sao chạy được,
  và kết quả truy xuất bị giới hạn theo quyền của B.
- **Gateway:** không đổi. `/api/v1/master/shares/**` tự chuyển về backend và yêu
  cầu đăng nhập (mặc định).

## 6. Lệnh

```
# backend
cd unisage-backend && ./mvnw test                         # unit + integration (Testcontainers)
cd unisage-backend && ./mvnw test -Dtest=ConversationShareServiceImplTest
# web
cd unisage-web && pnpm typecheck && pnpm lint && pnpm test
cd unisage-web && pnpm test:e2e -- e2e/chat-share.spec.ts
# agent (chỉ test)
cd unisage-agent && task test
```

## 7. Cấu trúc code

```
unisage-backend/src/main/java/com/unisage/backend/
  entity/ConversationShare.java
  repository/ConversationShareRepository.java
  service/conversation/ConversationShareService(.java|Impl.java)
  controller/ConversationShareController.java        # /shares
  dto/request/CreateShareRequest.java, dto/response/Share*Response.java
  exception/ErrorCode.java                           # + 2801..2803
  predefined/PredefinedPublicPaths.java              # + /shares/** vào AUTHENTICATED_ONLY_PATHS
src/main/resources/db/migration/V42__conversation_shares.sql
src/test/java/.../service/conversation/ConversationShareServiceImplTest.java
src/test/java/.../migration/V42MigrationTest.java

unisage-web/src/
  features/conversations/{api,queries,schemas,components}/…
  pages/user/shared-conversation-page.tsx, shared-links-page.tsx
  routes/user.routes.tsx, constants/{paths,api-endpoints}.ts
e2e/chat-share.spec.ts
```

## 8. Code style

Theo quy ước sẵn có của từng repo. Backend: `AppException(ErrorCode.X)`, trả
`ResponseEntity<ApiResponse<T>>`, không set tay các cột BaseEntity (ADR 0002).
Ví dụ:

```java
public ShareResponse create(UUID conversationId) {
    UUID me = securityUtil.getCurrentUserId();
    Conversation conversation = conversationRepository.findById(conversationId)
            .filter(c -> c.getDeletedAt() == null && c.getUser() != null
                    && c.getUser().getId().equals(me))
            .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
    Message cutoff = messageRepository.findLastCompleted(conversationId)
            .orElseThrow(() -> new AppException(ErrorCode.SHARE_EMPTY_CONVERSATION));
    ...
}
```

Web: theo `.claude/rules/*.md` của repo (feature folder, TanStack Query, zod,
`readSuccessData`), chuỗi tiếng Việt viết thẳng trong JSX (repo chưa có i18n).

## 9. Chiến lược test

| Mức | Nội dung |
|---|---|
| Unit backend (Mockito) | tạo link (chủ/không phải chủ/rỗng/guest), xem bản chụp chỉ đến cutoff, thu hồi, thu hồi tất cả, continue (tạo mới, trả bản cũ, sao chép đúng trường, đóng panel hỏi lại), xoá hội thoại thì thu hồi link |
| Integration backend (Testcontainers) | migration V42; unique `(source_share_id, user_id)`; retention job thu hồi link; vá phân quyền §4 (user khác bị chặn, guest đúng cookie vẫn đọc được) |
| Unit web (vitest + msw) | dialog chia sẻ, trang bản chụp (chỉ đọc, nút theo `isOwner`, trạng thái link không tồn tại), trang quản lý link, map mã lỗi sang toast, đăng nhập quay lại `from` |
| E2E (Playwright) | A tạo link → B đăng nhập qua link → xem → Tiếp tục → hỏi tiếp; A thu hồi → B mở link bị báo không tồn tại, bản sao của B vẫn còn; B bấm trích dẫn tài liệu không có quyền → drawer báo nhẹ |
| Agent | e2e: một lượt hỏi trong bản sao trả lời được, truy xuất theo quyền của B |

## 10. Ranh giới

- **Luôn:** chạy test của repo bị sửa trước khi commit; kiểm tra chủ sở hữu ở
  service, không chỉ dựa vào RBAC; dùng cùng `SHARE_NOT_FOUND` cho mọi trường
  hợp link không dùng được.
- **Hỏi trước:** thay đổi schema ngoài §5.1; đổi hành vi endpoint dùng chung
  (§4) theo cách khác bảng trên; thêm dependency; đổi gateway.
- **Không bao giờ:** sao chép `MessageTrace`, `CalculationTrace`, ticket,
  feedback hay trạng thái hỏi lại sang bản sao; trả nội dung hội thoại qua link
  đã thu hồi; lộ `objectKey` hoặc file tài liệu qua bản chụp.

## 11. Tiêu chí hoàn thành

1. A tạo link, B (đăng nhập) mở được bản chụp, gồm đúng các tin nhắn tới lúc tạo link.
2. A chat thêm: link cũ vẫn như cũ. A chia sẻ lại: link mới có thêm tin nhắn mới.
3. B chưa đăng nhập mở link: đăng nhập xong quay lại đúng link.
4. B bấm "Tiếp tục": có hội thoại mới của B, hỏi tiếp được. Bấm lần nữa: mở lại đúng bản đó.
5. Câu hỏi tiếp của B chỉ truy xuất tài liệu B được xem (kiểm bằng log truy xuất hoặc test agent).
6. B bấm trích dẫn tài liệu B không có quyền: drawer hiện "Bạn không có quyền xem tài liệu này.".
7. A thu hồi 1 link / tất cả: mở link → "Liên kết không tồn tại hoặc đã bị thu hồi". Bản sao của B vẫn còn.
8. A xoá hội thoại gốc (hoặc job dọn xoá): mọi link của nó bị thu hồi.
9. User khác không đọc hoặc xoá được hội thoại của A qua UUID (§4).
10. Không có lỗi thô từ backend trên màn hình trong mọi luồng ở trên.

## 12. Câu hỏi còn mở

1. **Hiện tên người chia sẻ** trên trang bản chụp ("Được chia sẻ bởi Nguyễn Văn A")? Mặc định đề xuất: **không hiện**, vì sinh viên có thể dán link vào nhóm lớn.
2. **Giới hạn số link** mỗi người (chống spam)? Mặc định đề xuất: không giới hạn ở bản đầu.
3. Bản sao hiện dòng nhỏ **"Tiếp tục từ hội thoại được chia sẻ"** ở đầu? Mặc định đề xuất: có (dựa vào `source_share_id`).
