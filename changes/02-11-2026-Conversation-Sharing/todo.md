# Todo: Chia sẻ hội thoại

Xem `spec.md` (yêu cầu, API, dữ liệu) và `plan.md` (quyết định, rủi ro).

Lệnh chung:
- Backend: `cd unisage-backend && ./mvnw test` (một lớp: `./mvnw test -Dtest=ClassName`)
- Web: `cd unisage-web && pnpm typecheck && pnpm lint && pnpm test`; e2e: `pnpm test:e2e`
- Agent: `cd unisage-agent && task test`

---

## Phase 0: Vá phân quyền có sẵn

### Task 1: Kiểm tra chủ khi đọc tin nhắn

**Description:** `GET /messages/conversation/{id}` và `GET /messages/{id}` phải qua `validateOwnership`. Chủ là user thì user đó đọc được; hội thoại guest thì guest có cookie khớp đọc được. Hội thoại đã xoá trả `CONVERSATION_NOT_FOUND`.

**Acceptance criteria:**
- [ ] User B đọc hội thoại của A → `CONVERSATION_NOT_FOUND`. A đọc của mình vẫn được.
- [ ] Guest đúng cookie đọc được; guest khác cookie hoặc không cookie bị chặn.
- [ ] Hội thoại đã soft delete → `CONVERSATION_NOT_FOUND`.

**Verification:**
- [ ] `./mvnw test -Dtest=MessageServiceImplTest`
- [ ] Manual: mở chat bằng user và bằng guest trên web, lịch sử vẫn tải.

**Dependencies:** None
**Files:** `service/conversation/MessageServiceImpl.java`, `controller/MessageController.java`, `MessageServiceImplTest.java`
**Scope:** S

### Task 2: Kiểm tra chủ khi xoá, liệt kê, claim hội thoại

**Description:**
- `DELETE /conversations/{id}`: chỉ chủ được xoá.
- `GET /conversations`: lấy user từ JWT. Vẫn nhận tham số `userId` nhưng bỏ qua.
- `PATCH /conversations/{id}/claim`: yêu cầu cookie guest session khớp với hội thoại.

**Acceptance criteria:**
- [ ] Xoá hội thoại của người khác → `CONVERSATION_NOT_FOUND`, dữ liệu không đổi.
- [ ] `GET /conversations?userId=<người khác>` trả hội thoại của chính người gọi.
- [ ] Claim không có hoặc sai cookie guest → bị chặn. Đúng cookie → claim thành công như cũ.

**Verification:**
- [ ] `./mvnw test -Dtest='ConversationServiceImplTest,ConversationControllerTest'` (tạo test mới nếu chưa có)
- [ ] `grep -rn "conversations?userId\|conversationClaim" unisage-web/src`: mọi chỗ gọi vẫn đúng.

**Dependencies:** None
**Files:** `service/conversation/ConversationServiceImpl.java`, `controller/ConversationController.java`, test mới `ConversationServiceImplTest.java`
**Scope:** S

### Checkpoint 0
- [ ] `./mvnw test` xanh.
- [ ] `pnpm test:e2e -- e2e/chat-*.spec.ts` xanh, cả luồng guest và user.
- [ ] Review với người dùng trước Phase 1.

---

## Phase 1: Tạo link và xem bản chụp

### Task 3: Migration V42, entity, repository

**Description:** Tạo bảng `conversation_shares` và cột `conversations.source_share_id` theo spec §5.1. Thêm entity `ConversationShare`, `ConversationShareRepository` với các truy vấn: theo token (chưa thu hồi, hội thoại chưa xoá), theo owner (phân trang), thu hồi theo hội thoại, thu hồi theo owner.

**Acceptance criteria:**
- [ ] Flyway chạy V42 sạch trên DB mới và trên DB có dữ liệu (`ddl-auto=validate` qua).
- [ ] Unique `(source_share_id, user_id) WHERE deleted_at IS NULL` có hiệu lực.

**Verification:**
- [ ] `./mvnw test -Dtest=V42MigrationTest` (Testcontainers, theo mẫu `V34MigrationTest`).
- [ ] Trước khi merge: kiểm tra `main` chưa có V42.

**Dependencies:** Checkpoint 0
**Files:** `db/migration/V42__conversation_shares.sql`, `entity/ConversationShare.java`, `entity/Conversation.java`, `repository/ConversationShareRepository.java`, `migration/V42MigrationTest.java`
**Scope:** M

### Task 4: API tạo link và xem bản chụp

**Description:** `POST /shares` và `GET /shares/view/{token}` theo spec §5.2. Thêm mã lỗi 2801–2803. Thêm `/shares/**` vào `AUTHENTICATED_ONLY_PATHS`. Token: `SecureRandom` 32 byte, base64url không padding.

**Acceptance criteria:**
- [ ] Chủ tạo được link. Người khác tạo → `CONVERSATION_NOT_FOUND`. Hội thoại rỗng → 2802; hội thoại guest → 2803.
- [ ] Bản chụp chỉ gồm tin nhắn `COMPLETED` tới cutoff. Tin nhắn tạo sau không xuất hiện. `isOwner` đúng.
- [ ] Response bản chụp không có `MessageTrace`, `objectKey`, `metadata.calculation_feedback`.
- [ ] Token sai → `SHARE_NOT_FOUND`. Chưa đăng nhập → 401.

**Verification:**
- [ ] `./mvnw test -Dtest='ConversationShareServiceImplTest,ConversationShareControllerTest'`
- [ ] Manual: gọi qua gateway `:8400/api/v1/master/shares` bằng 2 tài khoản.

**Dependencies:** Task 3
**Files:** `controller/ConversationShareController.java`, `service/conversation/ConversationShareService(.java|Impl.java)`, `dto/request/CreateShareRequest.java`, `dto/response/Share*Response.java`, `exception/ErrorCode.java`, `predefined/PredefinedPublicPaths.java` (+ test)
**Scope:** M

### Task 5: Web: đăng nhập xong quay lại trang cũ

**Description:** `sign-in-screen.tsx` dùng `location.state.from` nếu là đường dẫn nội bộ (bắt đầu `/`, không bắt đầu `//`); không có thì về trang mặc định của role. Áp dụng cho mọi trang private, không riêng `/share`.

**Acceptance criteria:**
- [ ] Mở `/share/abc` khi chưa đăng nhập → login → quay lại `/share/abc`.
- [ ] `from = "//evil.com"` hoặc URL tuyệt đối → bỏ qua, về trang mặc định.

**Verification:**
- [ ] `pnpm test -- sign-in-screen`
- [ ] Manual trên trình duyệt.

**Dependencies:** None (làm song song với Task 3–4)
**Files:** `src/features/auth/components/sign-in-screen.tsx`, `src/features/auth/utils/role-routing.ts`, test tương ứng
**Scope:** S

### Task 6: Web: nút và dialog chia sẻ

**Description:** Thêm mục "Chia sẻ" vào dropdown "More" của từng hội thoại và nút trên header chat. Dialog gọi `POST /shares`, hiện link, có nút "Sao chép" và ghi chú theo spec §5.5. Code nằm trong `src/features/conversations/` (api, queries, schemas zod). Lỗi hiện bằng toast.

**Acceptance criteria:**
- [ ] Tạo được link, sao chép vào clipboard, toast "Đã sao chép liên kết".
- [ ] Hội thoại rỗng: nút bị vô hiệu, hoặc lỗi 2802 hiện toast tiếng Việt.
- [ ] Không có chuỗi lỗi thô từ backend trên màn hình.

**Verification:**
- [ ] `pnpm test -- share-dialog` (vitest + msw)
- [ ] `pnpm typecheck && pnpm lint`

**Dependencies:** Task 4
**Files:** `features/conversations/{api/share-api.ts,schemas/share-schemas.ts,queries/*,components/share-dialog.tsx}`, `features/chat/components/conversation-history.tsx`, `chat-workspace-header.tsx`, `constants/api-endpoints.ts`
**Scope:** M

### Task 7: Web: trang bản chụp `/share/:token`

**Description:** Route mới bọc `PrivateRoute`. Trang `SharedConversationPage` render tin nhắn chỉ đọc bằng các component chat có sẵn (thêm prop `readOnly`: ẩn feedback, panel hỏi lại, ô nhập). Chip trích dẫn mở `CitationDrawer`. Link không tồn tại → trạng thái trống "Liên kết không tồn tại hoặc đã bị thu hồi". Nút "Tiếp tục" làm ở Task 9.

**Acceptance criteria:**
- [ ] B thấy đúng nội dung bản chụp, không có ô nhập hay nút đánh giá.
- [ ] Bấm trích dẫn tài liệu B không có quyền → drawer "Bạn không có quyền xem tài liệu này.".
- [ ] Token sai → trạng thái trống thân thiện, không có lỗi đỏ.

**Verification:**
- [ ] `pnpm test -- shared-conversation-page`
- [ ] Manual với 2 tài khoản khác quyền.

**Dependencies:** Task 4, Task 5
**Files:** `pages/user/shared-conversation-page.tsx`, `routes/user.routes.tsx`, `constants/paths.ts`, `features/chat/components/chat-conversation-content.tsx` (prop `readOnly`), `features/conversations/components/shared-conversation-view.tsx`
**Scope:** M

### Checkpoint 1
- [ ] Backend và web đều xanh (test, typecheck, lint).
- [ ] Manual: A tạo link → B (chưa đăng nhập) mở → đăng nhập → thấy bản chụp.
- [ ] Review với người dùng.

---

## Phase 2: "Tiếp tục" thành bản sao của mình

### Task 8: API continue

**Description:** `POST /shares/view/{token}/continue` theo spec §5.3. Một transaction tạo hội thoại mới cho người gọi, sao chép tin nhắn với `created_at` tăng dần, giữ hoặc bỏ từng trường theo bảng trong spec, đóng panel hỏi lại đang mở. Đã có bản sao chưa xoá thì trả bản đó. Thêm `sourceShareId` vào response hội thoại.

**Acceptance criteria:**
- [ ] Bản sao thuộc B, đúng thứ tự, đủ `content`/`citations`/`metadata.calculation`. Không có `calculation_feedback`, trace, ticket.
- [ ] Gọi 2 lần → cùng `conversationId`. B xoá bản sao rồi gọi lại → tạo bản mới.
- [ ] A thu hồi link sau đó → bản sao của B không đổi.

**Verification:**
- [ ] `./mvnw test -Dtest=ConversationShareServiceImplTest`
- [ ] Manual: B gửi tiếp một câu trong bản sao (`POST /messages/turn` chạy, lịch sử đúng).

**Dependencies:** Task 4
**Files:** `service/conversation/ConversationShareServiceImpl.java`, `repository/MessageRepository.java`, `ConversationServiceImpl.toResponse`, `dto/response/ConversationResponse.java`, test
**Scope:** M

### Task 9: Web: nút "Tiếp tục" và dòng nguồn gốc

**Description:** Trang bản chụp có nút "Tiếp tục cuộc trò chuyện" (gọi continue, rồi điều hướng `/chat/{id}` và làm mới danh sách hội thoại). `isOwner` thì nút "Mở hội thoại gốc". Bản sao có dòng nhỏ "Tiếp tục từ hội thoại được chia sẻ" ở đầu (dựa vào `sourceShareId`).

**Acceptance criteria:**
- [ ] B bấm → vào `/chat/{id}`, hội thoại xuất hiện trong sidebar, gõ câu mới được.
- [ ] A mở link của mình → thấy nút "Mở hội thoại gốc".
- [ ] Lỗi → toast tiếng Việt.

**Verification:**
- [ ] `pnpm test -- shared-conversation-page`
- [ ] Manual end-to-end.

**Dependencies:** Task 7, Task 8
**Files:** `features/conversations/components/shared-conversation-view.tsx`, `features/conversations/queries/use-mutations.ts`, `features/chat/components/chat-conversation-content.tsx`, `features/chat/schemas/chat-schemas.ts`
**Scope:** S

### Task 10: Agent: test hỏi tiếp trong bản sao dùng quyền của B

**Description:** Thêm test e2e (`-m integration`): tạo bản sao thuộc B có quyền thấp, gửi một câu hỏi; kiểm tra truy xuất chỉ trả chunk B được xem và không có state hỏi lại của hội thoại gốc. Không sửa code agent. Nếu test cho thấy cần sửa thì dừng lại hỏi.

**Acceptance criteria:**
- [ ] Lượt hỏi trong bản sao trả lời được.
- [ ] Không có chunk ngoài quyền B trong trace.

**Verification:**
- [ ] `cd unisage-agent && .venv/bin/python -m pytest -m integration tests/e2e/test_shared_conversation.py`

**Dependencies:** Task 8
**Files:** `tests/e2e/test_shared_conversation.py`
**Scope:** S

### Checkpoint 2
- [ ] Mọi test xanh. Luồng A chia sẻ → B tiếp tục → B hỏi tiếp chạy end-to-end.
- [ ] Review với người dùng.

---

## Phase 3: Quản lý và thu hồi

### Task 11: API quản lý link, thu hồi khi xoá hội thoại

**Description:**
- Thêm `GET /shares` (phân trang), `DELETE /shares/{id}` và `DELETE /shares`.
- `ConversationServiceImpl.delete` thu hồi mọi link của hội thoại, cùng transaction.
- `softDeleteBeyondNewestPerOwner` (job dọn) cũng thu hồi link của các hội thoại bị xoá.

**Acceptance criteria:**
- [ ] Danh sách chỉ có link chưa thu hồi của chính mình, mới nhất trước.
- [ ] Thu hồi link của người khác → `SHARE_NOT_FOUND`. Thu hồi 2 lần không lỗi.
- [ ] Xoá hội thoại hoặc chạy job → mở link → `SHARE_NOT_FOUND`.

**Verification:**
- [ ] `./mvnw test -Dtest='ConversationShareServiceImplTest,ConversationRetentionJobTest'`

**Dependencies:** Task 4
**Files:** `controller/ConversationShareController.java`, `service/conversation/ConversationShareServiceImpl.java`, `ConversationServiceImpl.java`, `repository/ConversationRepository.java`, tests
**Scope:** M

### Task 12: Web: trang "Liên kết đã chia sẻ"

**Description:** Route mới trong `UserLayout`, thêm mục vào dropdown tài khoản. Bảng dùng `components/shared/list/*`: tên hội thoại (bấm thì mở hội thoại gốc), ngày tạo, số tin nhắn, nút sao chép link, nút thu hồi. Nút "Thu hồi tất cả". Xác nhận bằng `confirm-delete-dialog`, kết quả báo bằng toast.

**Acceptance criteria:**
- [ ] Thu hồi 1 link hoặc tất cả cập nhật bảng ngay, toast thành công.
- [ ] Danh sách rỗng có trạng thái trống.
- [ ] Lỗi hiện toast tiếng Việt.

**Verification:**
- [ ] `pnpm test -- shared-links-page`
- [ ] `pnpm typecheck && pnpm lint`

**Dependencies:** Task 11
**Files:** `pages/user/shared-links-page.tsx`, `features/conversations/components/shared-links-table.tsx`, `routes/user.routes.tsx`, `constants/paths.ts`, `features/chat/components/conversation-history.tsx`
**Scope:** M

### Task 13: E2E, ADR, tài liệu

**Description:**
- Playwright `e2e/chat-share.spec.ts` chạy đủ tiêu chí hoàn thành ở spec §11.
- ADR backend `0011-conversation-share-snapshot-and-token.md`: bản chụp tham chiếu, token lưu dạng rõ.
- Cập nhật `docs/postman/`, `unisage-web/docs/route-screen-matrix.md`, ERD trong `docs/design/`.

**Acceptance criteria:**
- [ ] E2E xanh trên stack dev.
- [ ] ADR và tài liệu đã cập nhật.

**Verification:**
- [ ] `pnpm test:e2e -- e2e/chat-share.spec.ts`

**Dependencies:** Task 9, Task 12
**Files:** `e2e/chat-share.spec.ts`, `docs/adr/0011-…md`, `docs/postman/*`, `docs/route-screen-matrix.md`, `docs/design/*`
**Scope:** M

### Checkpoint 3: Hoàn thành
- [ ] Đạt 10 tiêu chí ở spec §11.
- [ ] Mọi test xanh ở backend, web và agent.
- [ ] Sẵn sàng review và tạo PR (chỉ khi người dùng yêu cầu).
