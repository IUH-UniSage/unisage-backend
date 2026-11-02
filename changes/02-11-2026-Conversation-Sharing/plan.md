# Implementation Plan: Chia sẻ hội thoại

Yêu cầu: `spec.md`. Checklist chi tiết: `todo.md`. Cả hai cùng thư mục này.

## Tổng quan

Thêm link chia sẻ hội thoại kiểu ChatGPT, trải trên 2 repo:

- `unisage-backend`: bảng `conversation_shares` và các API `/shares`.
- `unisage-web`: dialog chia sẻ, trang bản chụp `/share/:token`, trang "Liên kết đã chia sẻ".

`unisage-agent` và `unisage-gateway` **không đổi code**: agent chỉ thêm test, gateway tự chuyển `/api/v1/master/shares/**` về backend. Trước khi làm tính năng, phải vá 3 lỗ hổng phân quyền có sẵn (spec §4); không vá thì thu hồi link vô nghĩa.

## Quyết định kiến trúc

| Quyết định | Lý do | Phương án đã bỏ |
|---|---|---|
| Bản chụp **tham chiếu** tin nhắn gốc qua `cutoff_at` và `cutoff_message_id`, không sao chép | Tin nhắn cũ không đổi nội dung khi A chat tiếp. Xoá hội thoại gốc thì link cũng bị thu hồi. Đỡ tốn chỗ, migration đơn giản | Sao chép tin nhắn sang bảng snapshot: tốn chỗ, phải đồng bộ khi xoá |
| Token lưu **dạng rõ** (32 byte ngẫu nhiên, base64url) | Trang quản lý cần sao chép lại được link. Token chỉ là quyền xem một bản chụp | Lưu hash như guest session: không hiện lại được link |
| `conversations.source_share_id` + unique `(source_share_id, user_id)` khi chưa xoá | Bấm "Tiếp tục" lặp lại thì mở lại đúng bản sao. Có sẵn dữ liệu cho dòng "Tiếp tục từ hội thoại được chia sẻ" | Mỗi lần bấm tạo bản mới: dễ sinh trùng do double-click |
| Endpoint `/shares/**` đặt trong `AUTHENTICATED_ONLY_PATHS`, kiểm tra chủ trong service | Mọi role đã đăng nhập (SV, cán bộ) đều dùng được, không phải seed Permission cho từng role | Thêm Permission row: phải sửa DataInitializer, migration seed, mọi role |
| Mọi link không dùng được → **cùng một** `SHARE_NOT_FOUND` (404) | Không lộ link nào từng tồn tại hay đã bị thu hồi | Phân biệt "đã thu hồi" / "không tồn tại" |
| Agent không sao chép state | `conversation_id` mới thì state hỏi lại và `recent_calculations` (số liệu cá nhân của A) tự trống. Quyền và hồ sơ lấy theo người hỏi ở mỗi lượt | Sao chép `recent_calculations`: lộ điểm số của A |

Viết **ADR backend 0011** cho hai quyết định đầu (bản chụp tham chiếu, token lưu dạng rõ): đây là chỗ dễ bị người sau "sửa ngược lại".

## Thứ tự và phụ thuộc

```
Phase 0  T1 vá đọc tin nhắn ─┐
         T2 vá xoá/list/claim ┴─ Checkpoint 0 (chat user + guest vẫn chạy)
Phase 1  T3 migration+entity ── T4 API tạo/xem ──┬── T6 dialog chia sẻ ──┐
         T5 đăng nhập quay lại ─────────────────┴── T7 trang bản chụp ──┴─ Checkpoint 1
Phase 2  T8 API continue ── T9 web "Tiếp tục" ── T10 test agent ── Checkpoint 2
Phase 3  T11 API quản lý + thu hồi khi xoá ── T12 trang quản lý ── T13 e2e + ADR + docs ── Checkpoint 3
```

- **Làm song song được:** T5 với T3/T4 (khác repo, không đụng nhau). T6 và T7 làm song song sau khi chốt hợp đồng API ở T4. T10 làm song song với T9.
- **Phải tuần tự:** migration V42 (T3) trước mọi task backend. Phase 0 trước Phase 1.

## Rủi ro

| Rủi ro | Mức | Cách giảm |
|---|---|---|
| Vá Phase 0 làm hỏng luồng guest (đọc lịch sử bằng cookie guest) hoặc màn hình admin đang gọi `GET /conversations?userId=` | Cao | Kiểm tra mọi chỗ FE gọi trước khi sửa. Giữ tham số `userId` nhưng bỏ qua. Chạy `e2e/chat-*.spec.ts` cho guest và user |
| Số migration V42 trùng với nhánh khác (đã xảy ra với V38→V41) | Trung bình | Kiểm tra `main` ngay trước khi merge, đổi số nếu trùng |
| Bản sao bị đảo thứ tự tin nhắn (sắp theo `created_at`, các bản sao chèn cùng một lúc) | Trung bình | Gán `created_at` tăng dần từng tin nhắn khi sao chép, có test |
| Panel hỏi lại còn mở trong bản sao, bấm vào thì agent lỗi (không có state) | Trung bình | Đóng panel khi sao chép (spec §5.3), có test. FE chỉ đọc ở trang bản chụp |
| Lộ dữ liệu nội bộ qua bản chụp (trace, `objectKey`, feedback) | Cao | Response bản chụp chỉ có các trường liệt kê trong spec §5.2, có test khẳng định các trường khác vắng mặt |
| Trang đăng nhập redirect tới `from` bị lợi dụng thành open redirect | Thấp | Chỉ chấp nhận đường dẫn nội bộ bắt đầu bằng `/` và không bắt đầu bằng `//` |

## Câu hỏi còn mở

Xem spec §12. Mặc định nếu không có phản hồi: không hiện tên người chia sẻ, không giới hạn số link, có dòng "Tiếp tục từ hội thoại được chia sẻ" trong bản sao.
