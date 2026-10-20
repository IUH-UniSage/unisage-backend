# Spec: Re-index chỉ mục embedding (UNISAGE-103)

Repo liên quan: `unisage-backend`, `unisage-agent`, `unisage-web`. Xem `plan.md` (quyết định
kiến trúc, contract, rủi ro) và `todo.md` (task theo phase).

## 1. Bối cảnh

Hiện tại chỉ mục vector (Qdrant) chỉ được ghi một chiều, lúc chạy wizard nạp liệu:

- **Đổi embedding model bị chặn**: `PATCH /chat-models/{id}/status` trả 409
  `EMBEDDING_REINDEX_REQUIRED` (2517) khi model mới khác identity của collection; job verify
  kết thúc `REINDEX_REQUIRED`. Identity của một collection là bất biến (V18), nên đổi model chỉ
  có thể bằng **collection mới** (đã ghi trong `changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/plan.md`
  mục "Embedding identity guard" — re-index là plan riêng). Tên collection là config tĩnh
  (`QDRANT_COLLECTION`) ở cả backend lẫn agent; số chiều vector hard-code 1536 ở agent.
- **Sửa tài liệu không chạm Qdrant**: đổi phòng ban / `minAccessLevel` / `isPublic` không đổi
  payload `department` / `access_level` / `is_public` của các point → retrieval vẫn lọc quyền
  theo giá trị cũ (lỗ hổng phân quyền). Thay file giữ `COMPLETED` trong khi Qdrant vẫn là chunk
  của file cũ. Xoá mềm tài liệu để lại vector.
- **Không chunk + embed lại được tài liệu đã `COMPLETED`**: wizard mở thẳng màn "Hoàn tất";
  embed lại với ít chunk hơn để lại point cũ (chỉ script eval có purge).

## 2. Yêu cầu

### R1 — Re-index khi đổi embedding model

- R1.1 Admin (trang **Cấu hình AI**) bắt đầu re-index sang một EMBEDDING model đã verify
  (`verifiedAt`, `embeddingDimension`, `embeddingFingerprint` có giá trị), đang `INACTIVE`,
  có identity khác chỉ mục hiện tại. Hai lối vào:
  - nút **Re-index** trên panel "Chỉ mục embedding";
  - bấm **Kích hoạt** một EMBEDDING model khác identity → thay vì chỉ báo lỗi 2517, web hỏi
    xác nhận và bắt đầu re-index sang model đó.
- R1.2 Re-index ghi vào **collection mới** (`<base>__<yyyyMMddHHmmss>`), đăng ký identity mới
  cho collection đó, embed lại **mọi point** của collection đang active từ payload sẵn có
  (`content`, `summary`, `questions` — không parse lại PDF, không gọi LLM enrichment). Payload
  giữ nguyên, chỉ thay `embedding_identity_key`.
- R1.3 Trong lúc chạy, chat vẫn truy xuất collection cũ. Khi xong (thành công): **cutover**
  trong một transaction — trỏ collection active sang collection mới, kích hoạt model mới, tắt
  model cũ, bump registry version → agent hot-swap.
- R1.4 Tối đa một lần re-index mở (`QUEUED`/`RUNNING`) cho một base collection.
- R1.5 Admin xem tiến độ (số point đã xử lý / tổng, lỗi) và **huỷ** được. Huỷ / lỗi → collection
  active không đổi, collection đích bị xoá (agent), model đích giữ nguyên trạng thái.
- R1.6 Trong lúc re-index: embed tài liệu mới bị từ chối 409 `EMBEDDING_REINDEX_IN_PROGRESS`
  (web báo rõ). Cập nhật payload / xoá point (R2) áp dụng cho **cả** collection active lẫn
  collection đích.
- R1.7 Collection cũ không bị xoá (rollback thủ công / dọn dẹp ngoài scope).

### R2 — Re-index khi sửa tài liệu

- R2.1 Đổi phòng ban / `minAccessLevel` / `isPublic` → backend gọi agent cập nhật payload của
  mọi point có `document_id` đó (không embed lại). Gọi agent lỗi → cập nhật tài liệu thất bại
  (fail-closed, transaction rollback), vì payload sai là sai phân quyền.
- R2.2 Thay file → backend gọi agent xoá point + bản nháp chunk của tài liệu, đặt status
  `OUTDATED` (nếu trước đó không phải `PENDING`). Tài liệu hiện nút "Xử lý nạp liệu" để chạy lại
  wizard từ đầu.
- R2.3 Xoá mềm tài liệu → backend gọi agent xoá point + bản nháp (fail-closed).

### R3 — Chunk + embed lại tài liệu đã nạp

- R3.1 Tài liệu `COMPLETED` / `FAILED` / `OUTDATED` có action **"Chunk & embed lại"** (danh sách,
  chi tiết, màn Hoàn tất của wizard) → wizard chạy lại từ bước xem trước / chunking.
- R3.2 Khi bắt đầu embed, tài liệu chuyển `PROCESSING`; kết thúc → `COMPLETED` / `FAILED` như hiện
  tại. Đồng bộ status nền (`DocumentStatusSync`) xử lý cả `PROCESSING`.
- R3.3 Embed xong (job không fatal) → agent xoá các point của tài liệu **không** thuộc tập chunk
  vừa embed (hết point cũ sót lại), ở collection active và collection đích (nếu đang re-index).
- R3.4 Đổi phòng ban / quyền khi chạy lại wizard: wizard đã gửi `department_id`, `access_level`,
  `is_public` của tài liệu hiện tại → point mới mang giá trị mới (không đổi hành vi).

## 3. Ngoài scope

- Xoá collection cũ sau cutover, rollback về collection cũ qua UI.
- Embed song song vào 2 collection (dual-write) trong lúc re-index.
- Đổi chunking strategy hàng loạt (bulk re-chunk theo `chunking_version`).
- Cập nhật payload `category` (chưa được nạp từ tài liệu; vẫn là default của task).

## 4. Tiêu chí chấp nhận chính

1. Bắt đầu re-index sang model khác số chiều (vd 1536 → 768) → collection mới có đúng số
   chiều, cùng số point với collection cũ; sau cutover chat trả lời bằng collection mới, model
   mới `ACTIVE`, model cũ `INACTIVE`.
2. Huỷ giữa chừng → collection active không đổi, collection đích bị xoá, run `CANCELLED`.
3. Đổi phòng ban của tài liệu `COMPLETED` → `department` trong mọi point của tài liệu đổi theo;
   agent tắt → API sửa tài liệu trả lỗi, DB không đổi.
4. Thay file tài liệu `COMPLETED` → không còn point nào của tài liệu, status `OUTDATED`, wizard
   chạy lại từ đầu được.
5. Chunk & embed lại tài liệu 10 chunk thành 6 chunk → còn đúng 6 point.
