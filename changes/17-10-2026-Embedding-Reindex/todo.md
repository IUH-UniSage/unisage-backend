# Todo: Re-index chỉ mục embedding (UNISAGE-103)

Xem `spec.md` (yêu cầu) và `plan.md` (contract, thiết kế). Thứ tự: backend → agent → web.

Lệnh kiểm tra:
- Backend: `./mvnw test`
- Agent: `.venv/bin/python -m pytest`, `ruff check app tests`, `ruff format --check app tests`, `mypy app tests`
- Web: `pnpm lint && pnpm format:check && pnpm typecheck && pnpm test`

---

## Phase 1: Backend

### Task 1: Migration + entity cho pointer và run

**Acceptance criteria:**
- [x] `V37__add_embedding_reindex.sql` đúng `plan.md` §2.1
- [x] Entity `EmbeddingIndexPointer`, `EmbeddingReindexRun`, enum `EmbeddingReindexStatus`, repository
- [x] `EmbeddingIndexIdentityWriter` chấp nhận `established_by = reindex`

**Files touched:** `db/migration/V37__add_embedding_reindex.sql`, `entity/`, `repository/`

### Task 2: Collection active thay cho config tĩnh

**Acceptance criteria:**
- [x] `EmbeddingIndexPointerService.activeCollection()` (không có dòng → base)
- [x] Snapshot trả `embeddingCollection`, `embeddingReindex`; identity là của collection active
- [x] `activateEmbedding` + promotion của job verify so với identity collection active

**Files touched:** `service/modelregistryinternal/`, `service/chatmodel/`, `service/chatmodelcandidatepromotion/`

### Task 3: Admin API re-index

**Acceptance criteria:**
- [x] `GET /chat-models/embedding-index`, `POST .../reindex`, `POST .../reindex/{id}/cancel`
- [x] Validate model (R1.1), một run mở (R1.4), lỗi 25xx mới
- [x] Permission `CHAT_MODEL_REINDEX` seed qua `DataInitializer`

### Task 4: Internal API claim / progress / result + cutover

**Acceptance criteria:**
- [x] Claim lấy run `QUEUED` hoặc `RUNNING` hết lease (`FOR UPDATE SKIP LOCKED`), cấp lease 10 phút
- [x] `progress` gia hạn lease, trả status; lease sai → 409
- [x] `result SUCCEEDED` cutover đúng `plan.md` §2.3; kiểm tra sai → `FAILED`

### Task 5: Sửa / xoá tài liệu đồng bộ chỉ mục

**Acceptance criteria:**
- [x] `AgentIndexClient` (`app.agent.base-url`, `X-Internal-Secret`)
- [x] Đổi phòng ban / quyền / isPublic → `PATCH index-metadata`; thay file → `DELETE index` + `OUTDATED`; xoá mềm → `DELETE index`
- [x] Agent lỗi → `DOCUMENT_INDEX_SYNC_FAILED`, rollback

### Task 6: Test + docs

- [x] Unit test service re-index, cutover, document sync
- [x] `docs/design` (2 bảng mới), ADR 0008

---

## Phase 2: Agent

### Task 7: Collection từ snapshot + qdrant_store theo collection — xong
### Task 8: Endpoint `index-metadata` / `index`, chặn embed khi re-index, xoá point cũ sau embed — xong
### Task 9: Embedder từ credential cho trước + task `run_embedding_reindex` + Beat claim — xong
### Task 10: Test (pytest), ruff, mypy; docs config — xong

---

## Phase 3: Web

### Task 11: Panel chỉ mục embedding + dialog re-index + bắt 2517 khi kích hoạt — xong
### Task 12: Chunk & embed lại tài liệu (danh sách, chi tiết, wizard restart, PROCESSING) — xong
### Task 13: Cảnh báo thay file; test + lint + typecheck + e2e (`e2e/embedding-reindex.spec.ts`) — xong

---

## Kết quả kiểm tra (17-10-2026)

- Backend: 472 test, 0 failure; 35 test Testcontainers không chạy được (máy không có Docker cho
  Testcontainers). Migration V37 áp nối tiếp V1–V36 trên Postgres 17 thành công.
- Agent: 2278 passed; `tests/evals/test_judge.py::test_503_and_504...` chập chờn sẵn trên `main`
  (random không seed); `tests/e2e` lỗi import có sẵn trên `main`.
- Web: lint, format, typecheck, build sạch; 203 unit test; 4 e2e (desktop + mobile) pass.
- Chưa chạy end-to-end thật với backend + agent + Qdrant + model embedding thật.
