# Plan: Re-index chỉ mục embedding (UNISAGE-103)

Yêu cầu ở `spec.md`. Task ở `todo.md`.

## 1. Quyết định kiến trúc

### D1 — Blue/green theo collection, collection active lưu ở DB

- Identity của collection là bất biến (V18). Model mới → collection mới + identity mới, chạy
  nền, xong mới cutover. Chat không gián đoạn, collection cũ còn nguyên để rollback thủ công.
- `QDRANT_COLLECTION` / `model-registry.embedding-collection-name` trở thành **base collection**
  (tên gốc của một môi trường: `unisage_chunks`, `unisage_eval_chunks`). Collection **active**
  của base đó lưu ở bảng `embedding_index_pointer`; chưa có dòng nào → active = base (dữ liệu
  hiện có chạy tiếp, không cần migrate).
- Tên collection đích: `<base>__<yyyyMMddHHmmss>` (UTC) — giữ chữ `eval` cho môi trường eval
  (guard purge của script eval dựa vào nó).
- Snapshot model registry thêm `embeddingCollection` (active) và `embeddingReindex` (run đang
  mở, nếu có). Identity trong snapshot là identity của collection **active**. Agent đọc
  collection từ snapshot, không đọc `settings.QDRANT_COLLECTION` nữa (fallback khi chưa có
  snapshot / backend cũ).

Phương án loại: xoá rồi embed lại tại chỗ (mất truy xuất suốt lúc chạy, phá quy tắc identity
bất biến, không rollback được). Ghi ADR `docs/adr/0008-embedding-reindex-blue-green.md`.

### D2 — Run do backend sở hữu, agent claim theo lease (giống job verify)

- Bảng `embedding_reindex_runs` là nguồn sự thật. Agent claim qua
  `POST /internal/embedding-reindex/claim` (Beat poll `EMBEDDING_REINDEX_POLL_INTERVAL_SECONDS`,
  mặc định 15s), heartbeat qua `progress`, kết thúc qua `result`. Lease 10 phút, gia hạn mỗi
  lần `progress`; lease hết hạn (worker chết) → claim lại được, chạy lại từ đầu (upsert theo
  point id nên idempotent).
- Không thêm kênh Redis wake-up: độ trễ ≤ 15s là chấp nhận được cho một thao tác hiếm.
- Chỉ re-index sang một EMBEDDING model **khác**, đang `INACTIVE`. Xoay credential của model đang
  ACTIVE sang danh tính khác vẫn kết thúc `REINDEX_REQUIRED` (tạo model mới rồi re-index).
- Backend không có client gọi agent cho luồng này — giữ chiều agent → backend như verify.

### D3 — Embed lại từ payload, không parse lại

Point đã có `content`, `summary`, `questions`. Text embed tái tạo đúng như lúc ingest
(`app/worker/tasks/ingestion.py`): `[content, summary or content, " ".join(questions) or content]`.
Không gọi EXTRACTION → rẻ, nhanh, kết quả nhất quán với chunk đã duyệt.

### D4 — Chặn embed mới trong lúc re-index, áp dụng sửa/xoá cho cả 2 collection

Không dual-write embed (phải giữ 2 embedder + 2 budget). Thay vào đó:
- `POST /ingestion/embedding` trả 409 `EMBEDDING_REINDEX_IN_PROGRESS` khi snapshot có run mở.
- Cập nhật payload / xoá point / xoá point cũ sau embed áp dụng cho **mọi** collection đang
  dùng (active + đích).
- Cuối run, agent chạy **một lượt đối soát**: id có ở nguồn mà thiếu ở đích → embed bổ sung;
  id có ở đích mà không còn ở nguồn → xoá. Bịt kẽ hở job embed đã chạy trước khi run bắt đầu.

### D5 — Sửa tài liệu: backend gọi agent đồng bộ, fail-closed

Backend thêm client `AgentIndexClient` (RestTemplate, `X-Internal-Secret`), gọi sau khi DB đã
ghi (trong transaction); lỗi → `AppException(DOCUMENT_INDEX_SYNC_FAILED)` → rollback. File MinIO
mới đã upload có thể mồ côi khi rollback — chấp nhận (giống hành vi lỗi hiện tại).

## 2. Contract

### 2.1 Backend DB — `V37__add_embedding_reindex.sql`

```sql
CREATE TABLE public.embedding_index_pointer (
    base_collection   varchar(255) PRIMARY KEY,
    active_collection varchar(255) NOT NULL,
    updated_at        timestamp    NOT NULL DEFAULT now()
);

CREATE TABLE public.embedding_reindex_runs (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    base_collection       varchar(255) NOT NULL,
    source_collection     varchar(255) NOT NULL,
    target_collection     varchar(255) NOT NULL UNIQUE,
    target_chat_model_id  uuid NOT NULL REFERENCES public.chat_models(id),
    target_model_revision integer NOT NULL,
    status                varchar(20) NOT NULL,  -- QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED
    total_points          integer,
    processed_points      integer NOT NULL DEFAULT 0,
    failed_points         integer NOT NULL DEFAULT 0,
    lease_token           uuid,
    lease_until           timestamp,
    error_message         varchar(500),
    requested_by          uuid REFERENCES public.users(id),
    created_at            timestamp NOT NULL DEFAULT now(),
    started_at            timestamp,
    finished_at           timestamp
);
-- Một run mở cho mỗi base collection.
CREATE UNIQUE INDEX ux_embedding_reindex_runs_open
    ON public.embedding_reindex_runs (base_collection) WHERE status IN ('QUEUED', 'RUNNING');

-- Identity của collection đích do run đăng ký.
ALTER TABLE public.embedding_index_identity DROP CONSTRAINT embedding_index_identity_established_by_check;
ALTER TABLE public.embedding_index_identity ADD CONSTRAINT embedding_index_identity_established_by_check
    CHECK (established_by IN ('bootstrap-cli', 'first-upsert', 'reindex'));
```

### 2.2 Backend admin API (gateway `/api/v1/master/**`)

Dưới `/chat-models/embedding-index` để dùng chung nhóm quyền CHAT_MODEL (thêm
`CHAT_MODEL_REINDEX` = `POST /chat-models/embedding-index/**`, gán cho role có
`CHAT_MODEL_ACTIVATE`).

| Method | Path | Body | Trả về |
|---|---|---|---|
| GET | `/chat-models/embedding-index` | — | `EmbeddingIndexResponse` |
| POST | `/chat-models/embedding-index/reindex` | `{chatModelId}` | `EmbeddingReindexRunResponse` (201) |
| POST | `/chat-models/embedding-index/reindex/{runId}/cancel` | — | `EmbeddingReindexRunResponse` |

`EmbeddingIndexResponse`: `baseCollection`, `activeCollection`, `activeIdentity`
(`provider`, `modelName`, `dimension`, `establishedAt` | null), `activeModel`
(`id`, `displayName`, `llmModelName` | null), `currentRun` | null, `lastRun` | null.

`EmbeddingReindexRunResponse`: `id`, `status`, `sourceCollection`, `targetCollection`,
`targetModel` (`id`, `displayName`, `llmModelName`, `embeddingDimension`), `totalPoints`,
`processedPoints`, `failedPoints`, `errorMessage`, `createdAt`, `startedAt`, `finishedAt`.

Lỗi mới (2521–2526):
- `EMBEDDING_REINDEX_IN_PROGRESS` (409) — đã có run mở.
- `EMBEDDING_REINDEX_NOT_NEEDED` (409) — model trùng identity active → kích hoạt bình thường.
- `EMBEDDING_REINDEX_MODEL_INVALID` (400) — không phải EMBEDDING / chưa verify / không INACTIVE / đã xoá.
- `EMBEDDING_REINDEX_RUN_NOT_FOUND` (404), `EMBEDDING_REINDEX_RUN_NOT_OPEN` (409).

`PATCH /chat-models/{id}/status` giữ nguyên contract (vẫn 409 2517 khi khác identity) nhưng so
với identity của collection **active**. Web bắt 2517 để mời re-index.

### 2.3 Backend internal API (agent → backend, `/internal/embedding-reindex`)

| Method | Path | Body | Trả về |
|---|---|---|---|
| POST | `/claim` | — | `[]` hoặc `[claim]` |
| POST | `/{runId}/progress` | `{leaseToken, totalPoints, processedPoints, failedPoints}` | `{status}` (agent dừng nếu `CANCELLED`) |
| POST | `/{runId}/result` | `{leaseToken, succeeded, totalPoints, processedPoints, failedPoints, errorMessage}` | `{status}` |

`claim`: `runId`, `leaseToken`, `sourceCollection`, `targetCollection`, `credential` (cùng shape
với claim verify: provider, modelName, modelSourceRef, apiBaseUrl, apiKey, sourceType, maxRpm,
maxConcurrency), `chatModelId`, `credentialRevision`, `dimension`, `fingerprint`.
Lease mất / run không còn RUNNING → 409 `EMBEDDING_REINDEX_LEASE_LOST`.

`result SUCCEEDED` → cutover trong một transaction:
1. Run phải `RUNNING` + lease khớp; model đích còn `INACTIVE`, `isActive`, revision khớp
   `target_model_revision`; identity của collection đích tồn tại và khớp model — nếu không → run
   `FAILED` với lý do, không cutover.
2. Upsert `embedding_index_pointer(base → target)`.
3. Tắt EMBEDDING đang ACTIVE, bật model đích (`INACTIVE → ACTIVE`).
4. Run `SUCCEEDED`, `finished_at`; bump registry version (AFTER_COMMIT publish như cũ).

Snapshot (`GET /internal/model-registry/snapshot`) thêm:
`embeddingCollection` (string), `embeddingReindex` (`{runId, status, targetCollection}` | null).

### 2.4 Agent internal API (backend → agent, `X-Internal-Secret`)

| Method | Path | Body | Trả về |
|---|---|---|---|
| PATCH | `/api/v1/internal/documents/{id}/index-metadata` | `{department_id, access_level, is_public}` | `{updated_points}` |
| DELETE | `/api/v1/internal/documents/{id}/index` | — | `{deleted_points}` |

Dưới `/internal/` vì gateway gắn `X-Internal-Secret` cho mọi request tới agent nhưng chặn
`/api/v1/ai/internal/**` — chỉ backend gọi trực tiếp mới tới được. Lỗi gọi agent →
`DOCUMENT_INDEX_SYNC_FAILED` (2313, 502).

Áp dụng cho collection active + đích (nếu có run mở). `DELETE` còn xoá bản nháp
(`document_process_logs` + `document_chunks`) để wizard chạy lại từ đầu.

### 2.5 Agent ingestion

- `POST /ingestion/embedding`: snapshot có run mở → 409 `EMBEDDING_REINDEX_IN_PROGRESS` (agent 4016).
- `embed_chunks` kết thúc không fatal → xoá point của tài liệu có `chunk_id` không thuộc tập
  vừa gửi (mọi collection đang dùng).

## 3. Thiết kế agent

- `app/core/registry/model_registry.py`: parse `embeddingCollection`, `embeddingReindex`.
  Helper `active_collection()` và `index_collections()` (active + đích) ở
  `app/rag/vectorstore/collections.py`.
- `qdrant_store`: mọi hàm nhận `collection` (mặc định `active_collection()`);
  `ensure_collection(client, collection, dimension)`; thêm payload index `document_id`;
  `delete_document_points(client, collection, document_id, keep_chunk_ids=None)`,
  `set_document_payload(...)`, `scroll_points(...)`, `count_points(...)`, `delete_collection(...)`.
- `embedding_identity.ensure_embedding_identity` dùng collection active; dimension khi tạo
  collection lấy từ identity / số chiều đo được, bỏ hằng 1536.
- `app/api/deps.py`: cache key retrieval thêm collection active.
- Embedder dựng được từ một `CredentialConfig` cho trước (cho model đích) — không qua registry,
  không qua identity guard của collection active.
- Task `run_embedding_reindex` (queue ingestion) + Beat `claim_embedding_reindex` (queue default):
  1. tạo collection đích đúng `dimension`, PUT identity (`established_by="reindex"`; 409 thì đối
     chiếu, khớp thì đi tiếp);
  2. đếm point nguồn → `progress(total, 0)`;
  3. scroll theo lô `EMBEDDING_REINDEX_BATCH_SIZE` (mặc định 32 point → 96 text / lần gọi; thử lại
     `EMBEDDING_REINDEX_MAX_ATTEMPTS` lần với backoff), embed,
     upsert sang đích với cùng point id + payload, `embedding_identity_key` mới; `progress` sau
     mỗi lô → `CANCELLED` thì xoá collection đích và dừng; 409 lease thì dừng (không xoá);
  4. đối soát id (D4);
  5. `result(SUCCEEDED)`; lỗi fatal → xoá collection đích, `result(FAILED, lý do)`.
  Usage ghi với purpose `INGEST` (không thêm enum mới ở backend).

## 4. Thiết kế web

- `features/chat-models`: panel **Chỉ mục embedding** đầu tab "models" — model/collection/số
  chiều đang dùng, run hiện tại (thanh tiến độ, poll 3s khi run mở bằng `refetchInterval`),
  nút **Re-index** (dialog chọn EMBEDDING model đã verify, `INACTIVE`) và **Huỷ**.
- Kích hoạt EMBEDDING model bị 409 2517 → dialog xác nhận "Model khác chỉ mục hiện tại, cần
  re-index toàn bộ" → `POST .../reindex`.
- `features/documents`: action **Chunk & embed lại** (COMPLETED / FAILED / OUTDATED) → wizard
  với `?restart=1`; form sửa hiện cảnh báo khi thay file của tài liệu đã nạp.
- `features/ingestion`: wizard bỏ qua màn "Hoàn tất" khi `restart=1`; nút "Chunk & embed lại"
  ở màn Hoàn tất; đặt `PROCESSING` khi bắt đầu embed; `DocumentStatusSync` theo dõi cả
  `PROCESSING`; thông báo riêng cho 409 `EMBEDDING_REINDEX_IN_PROGRESS`.

## 5. Rủi ro

| Rủi ro | Giảm thiểu |
|---|---|
| Job embed đang chạy khi run bắt đầu ghi vào collection nguồn sau khi đã scroll | Lượt đối soát cuối run (D4) |
| Worker chết giữa chừng | Lease hết hạn → claim lại, chạy lại từ đầu (idempotent) |
| Model đích bị sửa / xoá khi đang re-index | Cutover kiểm tra revision + trạng thái, sai → `FAILED` |
| Collection cũ chiếm dung lượng | Ngoài scope; ghi chú dọn tay trong ADR |
| Backend gọi agent lỗi khi sửa tài liệu | Fail-closed, rollback; web hiện lỗi để thử lại |
| Chi phí embed lại | Chỉ embedding (không LLM); 3 text/point; admin xem số point trước khi bắt đầu |
