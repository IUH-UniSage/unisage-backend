# ADR-0008: Re-index embedding bằng collection mới và cutover, collection đang dùng lưu ở DB

- **Date**: 2026-10-17
- **Status**: Accepted
- **Context story**: UNISAGE-103 — re-index khi đổi model embedding, khi sửa tài liệu, và chunk + embed lại tài liệu đã nạp
- **Decision owners**: huydh

## Context

ADR-0005 (dynamic model registry) chặn việc đổi model embedding: `ChatModelServiceImpl.activateEmbedding`
trả 409 `EMBEDDING_REINDEX_REQUIRED` khi model khác danh tính của collection, và
`embedding_index_identity` là bất biến (entity `@Immutable`, không có update/delete, trigger V18).
Tên collection là cấu hình tĩnh (`model-registry.embedding-collection-name` / `QDRANT_COLLECTION`)
ở cả backend lẫn unisage-agent. Muốn dùng model mới thì phải có một cách embed lại toàn bộ dữ liệu
mà vẫn giữ được các bất biến đó.

## Decision

### 1. Embed lại vào collection mới, xong thì cutover

Một lượt re-index (`embedding_reindex_runs`) embed lại mọi point của collection đang dùng sang
`<base>__<yyyyMMddHHmmss>` bằng model đích, đăng ký danh tính mới cho collection đó
(`established_by = 'reindex'`). Khi agent báo thành công,
`EmbeddingReindexInternalServiceImpl.result` cutover trong **một transaction**: trỏ collection
đang dùng sang collection đích, tắt model embedding cũ, bật model đích, rồi bump registry version.
Danh tính cũ không bị sửa hay xoá, đúng tinh thần ADR-0005.

### 2. Collection đang dùng lưu ở `embedding_index_pointer`

Cấu hình tĩnh trở thành **base collection** của môi trường. `EmbeddingIndexPointerService` trả
collection đang dùng: chưa có dòng nào thì là chính base, nên dữ liệu hiện có không cần migrate.
Snapshot model registry thêm `embeddingCollection` và `embeddingReindex`, và agent đọc collection
từ đó thay vì từ `.env`.

### 3. Agent claim theo lease, credential không đi qua broker

Agent nhận việc giống job verify: claim `FOR UPDATE SKIP LOCKED`, lease 10 phút gia hạn mỗi lô,
lease hết hạn thì có thể claim lại (upsert theo point id nên chạy lại được). Task Celery không nhận
tham số nào, nên API key của model đích không đi qua broker.

### 4. Chặn embed mới, áp dụng sửa/xoá cho cả hai collection

Trong lúc re-index, `POST /ingestion/embedding` trả 409 (agent 4016). Cập nhật payload và xoá point
được áp dụng cho cả collection đang dùng lẫn collection đích. Cuối lượt có một bước đối soát id
(bổ sung point thiếu, xoá point thừa), để bịt kẽ hở của các job đã chạy trước khi lượt bắt đầu.

## Consequences

**Tích cực**:

- Chat không gián đoạn khi đổi model; collection cũ còn nguyên nên rollback được bằng tay.
- Embed lại từ payload (`content`/`summary`/`questions`): không parse PDF, không gọi LLM.
- Danh tính vẫn bất biến; mỗi collection có đúng một danh tính.

**Tiêu cực / rủi ro**:

- Collection cũ chiếm dung lượng cho tới khi được xoá bằng tay. Chưa có UI dọn dẹp hay rollback.
- Nạp tài liệu mới bị dừng trong suốt lượt re-index.
- Chỉ re-index sang **một model INACTIVE khác**. Một EMBEDDING đang ACTIVE mà xoay credential
  sang danh tính khác thì vẫn kết thúc `REINDEX_REQUIRED`: phải tạo model mới rồi re-index.

## Alternatives considered

1. **Xoá collection và embed lại tại chỗ**: chat mất truy xuất suốt lúc chạy, phải phá quy tắc
   danh tính bất biến, và không rollback được.
2. **Ghi song song (dual-write) mọi lần embed mới vào cả hai collection**: phải giữ hai embedder và
   hai budget trong `embed_chunks`. Việc chặn embed trong một thao tác hiếm, cộng bước đối soát,
   đơn giản hơn nhiều.
3. **Đổi `QDRANT_COLLECTION` trong `.env` rồi restart**: cutover không còn atomic với việc kích
   hoạt model, và phải sửa cấu hình ở hai service.

## Test lock

- `EmbeddingReindexServiceTest`, `EmbeddingReindexInternalServiceTest` (backend): validate lúc bắt
  đầu, lease, huỷ, cutover và các trường hợp cutover bị từ chối.
- `tests/worker/test_embedding_reindex.py` (agent, Qdrant in-memory): embed lại đủ point, huỷ, lỗi,
  đối soát, chạy lại.
- Migration V37 đã được áp nối tiếp V1–V36 trên Postgres 17 (database tạm, sau đó xoá) vào ngày
  2026-10-17.

## References

- `changes/17-10-2026-Embedding-Reindex/{spec,plan,todo}.md`
- ADR-0005 (dynamic model registry)
