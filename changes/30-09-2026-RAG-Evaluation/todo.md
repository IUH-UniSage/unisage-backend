# Todo: Đánh giá chất lượng trả lời UniSage

Xem `spec.md` (yêu cầu, định nghĩa tiêu chí, schema dữ liệu), `scoring-rules.md` (quy tắc chấm theo nhóm câu) và `plan.md` (quyết
định kiến trúc, rủi ro). Thứ tự bắt buộc: **crawl toàn bộ PDF (Phase 0) → dự
trù chi phí + chỉnh plan (Phase 1) → người dùng duyệt** trước khi viết phần
code còn lại.

Lệnh chung (chạy trong `unisage-agent/`):
- Test: `.venv/bin/python -m pytest tests/evals`
- Lint: `.venv/bin/python -m ruff check evals tests/evals`

---

## Phase 0: Crawl toàn bộ PDF

### Task 1: Crawl discover → `sources.csv`

**Description:** Duyệt các domain `*.iuh.edu.vn` (trang chủ, phòng ban, khoa,
cơ sở tỉnh), tuân thủ `robots.txt`, ≤ 1 req/s/host, liệt kê các trang có link
PDF kèm số PDF ước tính. Chưa tải PDF.

**Acceptance criteria:**
- [x] `sources.csv` có cột: `url`, `unit`, `campus`, `pdf_count`, `approved` (mặc định `true`; người dùng đổi `false` để loại nguồn)
- [x] Không request ngoài `*.iuh.edu.vn`; URL bị `robots.txt` cấm thì bỏ qua
- [x] Test parse link bằng HTML fixture, không gọi mạng

**Verification:**
- [x] Tests pass: `pytest tests/evals/test_crawl.py`
- [ ] Manual check: **người dùng duyệt `sources.csv`** (điền cột `approved`)

**Dependencies:** None

**Files touched:** `evals/crawl/http.py` (allowlist, robots, rate limit, TLS fallback), `evals/crawl/discover.py`, `tests/evals/test_crawl.py`

**Kết quả (30-09-2026):** 42 site, 2.300 link PDF → `sources.csv`, `discovered.csv`. `approved` mặc định `true`, **chưa được người dùng duyệt**.

**Estimated scope:** M

### Task 2: Download + `manifest.csv` + gitignore

**Description:** Tải **toàn bộ** PDF từ các nguồn `approved=true` vào
`dataset/files/<unit>/<file_id>.pdf`, dedupe theo sha256, bỏ file > 30 MB, ghi
`manifest.csv` theo schema `spec.md` §3.3. Chạy lại không tải trùng.
Thêm `dataset/files/` vào `.gitignore` của `unisage-gateway`.

**Acceptance criteria:**
- [x] `manifest.csv` đủ cột; `pages` đọc được bằng PyMuPDF; cột `text_chars` (số ký tự text trích được, để phát hiện PDF scan và ước tính token)
- [x] In thống kê: số file, tổng trang, tổng dung lượng, theo đơn vị
- [x] Chạy lại lần 2 không tải lại file đã có
- [x] `git status` trong `unisage-gateway` không hiện PDF

**Verification:**
- [x] Tests pass: `pytest tests/evals/test_download.py`
- [x] Manual check: `git -C ../unisage-gateway status --short`

**Dependencies:** Task 1

**Files touched:** `evals/crawl/download.py`, `tests/evals/test_download.py`, `unisage-gateway/.gitignore`

**Kết quả (30-09-2026):** 1.945 PDF, 27.491 trang, 3,6 GB; 631 PDF scan; lỗi ghi ở `download_errors.csv`. Chi tiết: `cost-estimate.md` §1.

**Triage (`evals/crawl/triage.py`):** 16 PDF hỏng (có mật khẩu + object stream lỗi, đều của Phòng Khảo thí) đã xóa và ghi `unreadable` vào `download_errors.csv`; 631 PDF scan chuyển sang `scanned_pdf/<đơn vị>/`; còn 1.298 PDF có text trong `files/`. Manifest còn 1.929 dòng.

**Triage chất lượng text:** thêm cột `quality` (`ok` / `scanned` / `garbled_ocr` / `no_diacritics` / `broken_encoding`). 187 PDF có lớp text hỏng cũng chuyển sang `scanned_pdf/` → `files/` còn **1.111 PDF sạch (1,0 GB)**, `scanned_pdf/` 818 (2,4 GB). Mỗi lần chạy lại, triage kiểm tra lại cả file đã chuyển và trả file bắt nhầm về `files/`.

**Estimated scope:** S

### Checkpoint 0
- [x] `dataset/files/` có toàn bộ PDF từ các nguồn đã duyệt; `manifest.csv` đủ cột
- [x] PDF không lọt vào git; `pytest tests/evals` + `ruff check evals` pass

---

## Phase 1: Dự trù chi phí

### Task 3: Dự trù chi phí và chỉnh plan

**Description:** Từ `manifest.csv` thực tế, ước tính chi phí từng khoản rồi
chỉnh `spec.md`/`plan.md`/`todo.md` trước khi viết tiếp code. Viết kết quả vào
`cost-estimate.md` trong thư mục change này.

Các khoản cần ước tính (giá lấy từ trang giá chính thức tại thời điểm tính, ghi
nguồn + ngày):
- **Ingest (một lần):** token embedding của toàn bộ chunk; lời gọi LLM sinh
  câu hỏi multi-representation mỗi chunk (`INGEST_MULTI_REP_QUESTION_COUNT`);
  tỷ lệ chunk/trang đo trên vài file mẫu.
- **Sinh bộ câu hỏi (một lần).**
- **Mỗi lần đánh giá:** model chat của UniSage chạy ~300 câu (phân loại, HyDE,
  sinh câu trả lời) + giám khảo.
- **Ablation:** mỗi chế độ ≈ một lần đánh giá, không có giám khảo nếu chỉ so #1.

**Acceptance criteria:**
- [x] `cost-estimate.md` có bảng chi phí theo khoản, theo 2 lựa chọn model (Gemini / OpenAI), kèm giả định token
- [x] Nếu ingest toàn bộ vượt ngân sách hợp lý, đề xuất tập con (theo đơn vị / bỏ PDF scan / giới hạn trang) và số tài liệu cuối
- [ ] Thêm cột `selected` vào `manifest.csv` đánh dấu tài liệu sẽ ingest
- [ ] `spec.md` §5 và §11 cập nhật con số thực (số tài liệu, ngân sách một lần)
- [ ] Task list từ Task 4 trở đi được chỉnh theo kết quả (nếu cần)

**Verification:**
- [ ] Manual check: **người dùng duyệt chi phí và plan đã chỉnh**

**Dependencies:** Task 2

**Files touched:** `cost-estimate.md`, `unisage-agent/evals/cost_estimate.py`, `spec.md`, `plan.md`, `todo.md`

**Trạng thái:** đã có `cost-estimate.md` (đề xuất phương án B: PDF `quality=ok` ≤ 100 trang, 1.085 file, nạp 4,8–6,8 USD). **Chờ người dùng chọn** ở `cost-estimate.md` §8; 3 mục còn lại làm sau khi chọn.

**Estimated scope:** S

### Checkpoint 1
- [ ] Người dùng duyệt `cost-estimate.md` và plan đã chỉnh
- [ ] Chốt danh sách tài liệu sẽ ingest

---

## Phase 2: Spike

### Task 4: Upload + ingest 1 PDF qua luồng thật

**Description:** Dựng môi trường đánh giá trống theo quy ước `spec.md` §12.7 (`assistant_eval`,
`unisage_agent_eval`, bucket `unisage-eval`, collection `unisage_eval_chunks`, Redis `/1`), chọn bằng
file env riêng `.ENV.eval` / `.env.eval` cho backend, agent và Celery worker. Viết bản nháp `evals/ingest.py` nạp 1 PDF private giống wizard của web: đăng nhập admin
backend → `POST /documents` (multipart, `title` từ manifest, tên file `<title>.pdf`, department UUID
tra từ mã, `is_public`, `access_level`) → agent `/ingestion/preview` → `/ingestion/chunking` →
`/ingestion/embedding` với `object_key = document.sourceUrl` → chờ Celery `embed_chunks` xong. Ghi
`document_id` (UUID backend) vào manifest. *Thay cho đường B (đổi 10-10-2026, `spec.md` §12.1).*

**Acceptance criteria:**
- [ ] Tài liệu hiện ở trang admin, sửa được tiêu đề / metadata
- [ ] `evals/ingest.py` dừng ngay nếu `DB_NAME`, `MINIO_BUCKET` hoặc `QDRANT_COLLECTION` không chứa `eval`
- [ ] Payload chunk trong Qdrant có đúng `department_id`, `access_level`, `is_public`, `document_id`, `object_key`
- [ ] Chat thường hỏi được về tài liệu; chip hiện tiêu đề, bấm mở được file đúng trang
- [ ] Các bước tối thiểu + lệnh tái hiện ghi vào `plan.md`

**Verification:**
- [ ] Manual check: mở trang admin; hỏi 1 câu trên web, bấm chip; xem payload Qdrant

**Dependencies:** Checkpoint 1

**Files likely touched:** `plan.md`, `unisage-agent/evals/ingest.py` (bản nháp)

**Estimated scope:** S

### Task 4a: Tách môi trường dev / eval / prod

**Description:** Chuyển môi trường bằng một tham số, chạy được trên Windows/macOS/Linux (`spec.md`
§12.7).
- Agent: biến Task `env` (mặc định `dev`); mọi task `be:*`, `db:*`, `env:*` nạp `.env.<env>` trước
  `.env`. `task db:up env=<env>` bật Postgres + MinIO (compose của backend-java), Redis + Qdrant
  (compose `.devcontainer`), chờ Postgres sẵn sàng, tạo DB của env nếu thiếu. Alembic chuyển sang
  `task db:upgrade`. `task env:show env=<env>` in DB/bucket/collection/Redis đang trỏ tới. `UV_RUN`
  chọn `.venv/Scripts` (Windows) hay `.venv/bin`.
- Backend: profile `eval` (`application-eval.properties`), bật bằng `APP_PROFILE=eval` trong `.ENV`.
- Gateway: không đổi (không có DB).
- Redis của eval dùng db 3/4/5 và `CELERY_QUEUE_PREFIX=unisage_eval`, để worker dev không nhận task eval.

**Acceptance criteria:**
- [x] `task env:show env=eval` in `unisage_agent_eval`, `unisage-eval`, `unisage_eval_chunks`, Redis `/3`; không có `env` thì in giá trị dev
- [x] `task db:up` / `task db:up env=eval` tạo `assistant_DB` + `unisage_agent_db` / `assistant_eval` + `unisage_agent_eval`; chạy lại không lỗi
- [x] Backend: `APP_PROFILE=eval` → log "profile is active: eval"; để trống → profile mặc định
- [ ] `task db:up` chạy trọn trên máy có image MinIO (máy thử 10-10-2026 không kéo được `minio/minio:latest` từ Docker Hub)
- [ ] Chạy thử trên Windows (`task be:dev env=eval`, `task db:up env=eval`)
- [ ] Backend profile `eval` kết nối đúng `assistant_eval` (xem log khi chạy với Postgres thật)

**Files touched:** agent `Taskfile.yml`, `taskfiles/{backend,db,env,help}.yml`, `.env.eval.example`,
`.gitignore`, `README.md`; backend `application.properties`, `application-eval.properties`,
`.env.example`; `dev-onboard.txt`

**Estimated scope:** S

### Task 4b: Chạy trước trên bộ demo (quyết định 10-10-2026)

**Description:** Dựng và kiểm tra toàn bộ pipeline đánh giá trên bộ demo trước (106 tài liệu, rẻ và
chạy lại được nhiều lần/ngày), rồi mới chạy bộ official.
- `evals/ingest.py` (xong code): luồng upload thật qua gateway với token admin: `POST /documents`
  (multipart, `title`, file `<title>.pdf`, `docPackageId` = UUID tra từ mã department qua
  `/departments/roots` + `/children`, `minAccessLevelId` cho tài liệu private) → `/ingestion/chunking`
  (`markdown_aware` 800/120 như wizard) → `/ingestion/embedding` → poll `/ingestion/jobs/{id}`. Ghi
  `document_id` + `ingest_status` (uploaded / embedding / done / empty_text / error) sau mỗi bước; chạy
  lại bỏ dòng `done`, không upload lại dòng đã có `document_id`; tối đa 4 tài liệu embedding cùng lúc;
  từ chối chạy khi `APP_ENV != eval`. Lệnh: `task eval:ingest:demo env=eval -- --limit 1` (thử),
  rồi bỏ `--limit`. Tài khoản admin trong `.env.eval` (`UNISAGE_ADMIN_*`).
- Bộ câu hỏi demo: 276 câu `d*` + 70 câu `q*` không cần tài liệu (40 `unanswerable`, 18 `off_topic`,
  12 `social`); không câu `q*` nào khác nằm trọn trong 106 tài liệu demo. Viết thêm câu `normal` cho
  18 tài liệu demo chưa có câu nào và ~30 câu `calculation` (có `expected_numbers`).
- **Xong 10-10-2026:** thêm `d0277`–`d0336` (30 `normal` cho 18 tài liệu chưa có câu, 30 `calculation`
  trên 25 tài liệu, 11 câu tính ngược) do 2 subagent viết từ trích đoạn; kiểm tra lại độc lập: 60/60
  bằng chứng có nguyên văn trong trích đoạn, phép tính đúng. Mỗi câu có `question_user` (cách người dùng
  gõ trong chat). 3 câu có `review_note`. Bộ câu hỏi: 557 câu. **Bộ đánh giá demo = `source_set=demo`
  + câu không cần tài liệu = 406 dòng** (access 152, normal 114, unanswerable 50, calculation 40,
  off_topic 26, social 18, web_search 6). Runner chọn bộ này bằng `--subset demo`.
- Đề xuất chờ duyệt: thêm `question_user` cho ~122 câu có đáp án cũ của bộ demo, báo cáo hai cột
  (câu chính xác / câu người dùng); câu người dùng mơ hồ thì hệ thống hỏi lại cũng tính đạt.

**Acceptance criteria:**
- [x] Tests pass: `pytest tests/evals/test_ingest.py` (7 test: luồng đầy đủ, `empty_text`, chạy tiếp không upload lại, poll tới khi task kết thúc, tài liệu public, department lạ, lọc `--only`)
- [x] `--dry-run` trên bộ demo: 106 dòng, nhãn quyền khớp manifest, tên upload là tiêu đề tiếng Việt
- [ ] `--limit 1` chạy thật trên môi trường eval: document hiện ở trang admin, file trong bucket `unisage-eval`, chunk trong `unisage_eval_chunks` có `document_id`/`object_key`/`department`/`access_level`/`is_public` đúng, chip hiện tiêu đề
- [ ] 106/106 dòng demo có `ingest_status` ∈ {done, empty_text, error}

**Files touched:** `evals/ingest.py`, `tests/evals/test_ingest.py`, `taskfiles/eval.yml`, `Taskfile.yml`, `.env.eval.example`

### Task 5: Chạy graph in-process với wrapper ghi lại

**Description:** Viết `evals/recording.py`: `RecordingRetrieval` (bọc
`RetrievalService`, lưu chunk + metadata), `RecordingTrace` (lưu danh sách node
để suy ra intent), token sink đo TTFT. Chạy `run_graph()` cho 1 câu hỏi trên
tài liệu của Task 4 với 2 persona (đủ quyền / khác department).

**Acceptance criteria:**
- [ ] Thu được: response text, citations, danh sách node, chunk truy xuất kèm metadata, TTFT, tổng thời gian
- [ ] Persona khác department không nhận được chunk của tài liệu private
- [ ] Không sửa file nào trong `app/`

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_recording.py` (dùng `FakeRetrievalService`)
- [ ] Manual check: chạy thật 1 câu, in kết quả

**Dependencies:** Task 4

**Files likely touched:** `evals/__init__.py`, `evals/recording.py`, `tests/evals/test_recording.py`

**Estimated scope:** S

### Checkpoint 2
- [ ] Đã biết chắc cách ingest và cách lấy intent/chunk/citation

---

## Phase 3: Nhãn và ingest

### Task 6: Gán nhãn phân quyền

**Description:** `evals/label.py` điền `department_id`, `is_public`,
`access_level` theo quy tắc: `department_id` lấy từ bảng `departments` của
backend qua bảng map `evals/label_map.yaml` (`unit → department_id`, người
duyệt; `access_level` phải là mức có trong DB); ~30% tài liệu mỗi
department thành private (chọn ngẫu nhiên có seed cố định) với `access_level`
giả lập. Không ghi đè nhãn người đã sửa tay (cột `label_source=manual`).

**Acceptance criteria:**
- [x] 100% dòng có nhãn; `is_public=true` ⇒ `access_level` rỗng
- [x] Mọi `department_id` là mã department có trong `DataInitializer.seedDepartments()` (kiểm tra bằng `known_departments` trong `label_map.yaml`)
- [x] Mỗi department có ít nhất 1 tài liệu private (nếu có ≥ 2 tài liệu), trừ đơn vị `force_public`
- [x] Chạy lại với cùng seed ra cùng kết quả

**Verification:**
- [x] Tests pass: `pytest tests/evals/test_label.py`

**Dependencies:** Checkpoint 2

**Files touched:** `evals/label.py`, `evals/label_map.yaml`, `evals/crawl/download.py` (cột `label_source`), `tests/evals/test_label.py`

**Kết quả (30-09-2026, làm trước Checkpoint 1 theo yêu cầu người dùng):** 1.929 dòng có nhãn; trong 1.111 file `quality=ok` có 329 private (~30%, mức 1/2/3/4 = 83/79/79/88), 28 department. `department_id` lưu **mã department** (vd `PHONG_DAO_TAO`), không phải UUID: UUID sinh ngẫu nhiên khi seed DB nên phải tra ở bước ingest (Task 7). Giả định map (người dùng cần duyệt `label_map.yaml`): trang chính trường → `PHONG_TO_CHUC_HANH_CHINH`, tuyển sinh → `PHONG_DAO_TAO`, cẩm nang người học và TT Kết nối doanh nghiệp → `PHONG_CTSV`, TT Ngoại ngữ - Tin học → `TT_NGOAI_NGU`; ba đơn vị đầu luôn public.

**Estimated scope:** S

### Task 7a: Tiêu đề đọc được cho manifest

**Mục tiêu (10-10-2026):** khi ingest và khi hiện citation, tài liệu mang **tên file đọc được**, không
phải `file_id` / `document_id`. Chip lấy tên từ `object_key` = tên file lúc upload (bỏ tiền tố uuid
và đuôi file, `citations.source_title`), và chỉ rơi về `document_id` khi chunk không có `object_key`
(chunk của đường B cũ). PDF crawl đang lưu dưới tên `<file_id>.pdf`, nên `evals/ingest.py` phải upload
dưới tên `<title>.pdf`. Qdrant cũ sẽ bị xóa và nạp lại toàn bộ theo luồng upload thật, nên không cần
sửa code agent. Trong 1.085 file `selected`, ~110 tên gốc không dùng được (39 tên có tiền tố uuid, 72
tên ≤ 8 ký tự như `CTDT.pdf`, `ilovepdf_merged(4).pdf`).

**Description:** `evals/titles.py` thêm cột `title`, `title_source` vào manifest theo `spec.md` §3.5:
làm sạch `file_name` (bỏ tiền tố uuid, đuôi file, `-`/`_`); tên còn khó đọc (mã ngắn như `CTDT`,
`DHCT20`, tên công cụ như `ilovepdf_merged`) thì lấy dòng tiêu đề lớn nhất ở trang 1 bằng PyMuPDF.
Không ghi đè dòng `manual`.

**Acceptance criteria:**
- [x] 100% dòng `selected=true` có `title` không rỗng và khác `file_id`
- [x] `title` không còn tiền tố uuid, không chứa ký tự cấm trong tên file (`\ / : * ? " < > |`), ≤ 150 ký tự; trùng tên trong cùng department thì thêm chi tiết phân biệt (năm, khóa, số hiệu, "bản 2")
- [ ] `evals/ingest.py` upload dưới tên `<title>.pdf`; sau ingest, không chip nào trong 20 câu thử hiện chuỗi hex 12 ký tự hay uuid
- [ ] Dòng `title_source=manual` giữ nguyên khi chạy lại
- [ ] In danh sách dòng `heading` để người duyệt

**Verification:**
- [x] Tests pass: `pytest tests/evals/test_titles.py` (9 test: uuid, mã ngắn, NFC, ký tự cấm, dòng manual, trùng tên, dòng chữ lớn nhất)
- [x] Manual check: người dùng duyệt 26 dòng trong `dataset/official/titles_review.csv`

**Kết quả (10-10-2026):** đổi cách làm: không chỉ đặt tên lại cho file có tên khó đọc, mà **đọc
trang 1 của cả 1.085 tài liệu** để đặt tên tiếng Việt có dấu, đúng tên tài liệu tự ghi.
`evals.titles extract` rút text trang 1 + các dòng chữ lớn nhất (~1 phút); 6 subagent Claude đặt tên
theo cùng một bộ quy tắc ([loại văn bản] + [nội dung] + [ngành/khóa/năm/số hiệu], ≤ 120 ký tự);
`evals.titles apply` ghi `title`, `title_source` vào manifest. Kết quả: 1.085/1.085 có tên;
`title_source` heading 953 / content 100 / filename 32; độ tin cậy high 856 / medium 203 / low 26
(trang 1 là ảnh hoặc lỗi font, tạp chí không ghi năm...). Không có tên trùng trong cùng department.
Kiểm tra chéo tên với text trang 1 của chính tài liệu (phòng khi subagent đọc nhầm lô): 17 dòng bị
đánh dấu, xem tay đều đúng (chữ viết tắt được mở rộng, hoặc trang 1 không có text). Manifest cũ sao
lưu ở scratchpad; các cột khác không đổi.

**Duyệt + đổi tên file (10-10-2026):** người dùng duyệt cả 26 dòng (→ `title_source=manual`).
`evals.titles rename` đổi tên 1.085 PDF trên đĩa thành `<title>.pdf` (cùng thư mục), cập nhật
`local_path` trong `manifest.csv`, `demo/demo_manifest.csv`, `demo/selection.csv`; bảng đổi tên ở
`official/renamed_files.csv`. Sau đó đặt tên + đổi tên nốt 26 file có text nhưng > 100 trang
(cũng nằm trong `official/files/`) → `official/files/` không còn file tên mã (1.111 file). 818 file
trong `scanned_pdf/` (scan, lỗi font) giữ tên `<file_id>.pdf`. Đã xóa bản sao cũ `dataset/files/`
(1.111 file, cùng sha256 với `official/`, gitignore). Đường dẫn Windows dài nhất ~213 ký tự (< 260).

**Phát hiện thêm:** vài cặp tài liệu khác `sha256` nhưng nội dung trang 1 giống hệt (lịch trình hội
nghị Khoa CN Hóa học, phụ lục QTKD 2025, lịch bảo vệ Điện tử 01/11/2025); đang được phân biệt bằng
"bản 2". Cân nhắc bỏ bớt trước khi ingest để không nhân đôi chunk.

**Dependencies:** Task 6

**Files touched:** `evals/titles.py`, `evals/crawl/download.py` (thêm cột `title`, `title_source`), `tests/evals/test_titles.py`, `dataset/official/manifest.csv`, `dataset/official/titles_review.csv`

**Estimated scope:** S

### Task 7b: Chip citation hiện tiêu đề tài liệu

**Description:** Chip hiện `source_title(object_key)`; khi chunk không có `object_key` thì rơi về
`document_id` (12 ký tự hex như ảnh demo). Với luồng upload thật và tên file `<title>.pdf`, chip đã
hiện tiêu đề mà không cần sửa code. Task này xác nhận điều đó, và nếu cần thì thêm `title` vào
payload Qdrant lúc ingest (agent nhận `title` từ request ingestion) để chip ưu tiên `title`.

**Acceptance criteria:**
- [ ] Chip của tài liệu nạp ở Task 4/7 hiện tiêu đề, không hiện `file_id` hay uuid
- [ ] Khung xem file (`CitationDrawer`) hiện `documents.title`, mở đúng trang được trích
- [ ] Ghi rõ giới hạn: admin sửa tiêu đề thì khung xem cập nhật ngay, chip cập nhật khi ingest lại

**Verification:**
- [ ] Tests pass: `.venv/bin/python -m pytest tests/rag` (nếu sửa `citations.py`)
- [ ] Manual check: bấm chip trên web

**Dependencies:** Task 4

**Files likely touched:** `unisage-agent/app/rag/prompting/citations.py`, `unisage-agent/app/worker/tasks/ingestion.py` (chỉ khi cần)

**Estimated scope:** S

### Task 7c: Chặn xem file private qua citation

**Description:** `GET /documents/{id}/citation` đang là public path (`PredefinedPublicPaths`) và
`resolveFileUrl` trả presigned URL cho cả tài liệu private (đoạn kiểm tra quyền bị comment). Áp quy
tắc hiển thị giống Qdrant: tài liệu private chỉ trả `fileUrl` khi người gọi có `department_access`
cùng department (hoặc `*`) với `access_level ≥` của tài liệu; khách chỉ xem được tài liệu public.

**Acceptance criteria:**
- [x] Khách / persona thiếu quyền nhận lỗi (không có `fileUrl`) với tài liệu private
- [x] Persona đủ quyền và tài liệu public vẫn xem được như cũ (khách giờ cũng xem được tài liệu public)
- [x] Web hiện thông báo không đủ quyền thay vì lỗi chung

**Verification:**
- [x] Tests pass: `DocumentServiceImplTest`, `DynamicAuthorizationManagerTest` (các test Testcontainers chưa chạy được trên máy này)
- [ ] Manual check: mở chip của tài liệu private bằng tài khoản thiếu quyền

**Dependencies:** Checkpoint 2

**Files touched:** `DocumentServiceImpl.java`, `PredefinedPublicPaths.java`, `ErrorCode.java` (2312
`DOCUMENT_VIEW_FORBIDDEN`), `UserDepartmentAccessRepository.java` (`findAccessLevel`),
`DocumentServiceImplTest.java`, `DynamicAuthorizationManagerTest.java`; web `citation-drawer.tsx`,
`constants/error-codes.ts`

**Kết quả (10-10-2026):** endpoint chuyển sang `PUBLIC_PATHS` để khách mở được tài liệu public (trước
đây là `AUTHENTICATED_ONLY`, khách bị chặn hoàn toàn). Kiểm tra quyền chỉ áp cho citation, trang admin
giữ nguyên. Quy tắc giống bộ lọc Qdrant: SUPER_ADMIN (`*`) hoặc `user_department_access` cùng
department có mức ≥ `minAccessLevel`. Bỏ đoạn kiểm tra bị comment (so với `user.accessLevel` toàn
cục, khác quy tắc Qdrant). Test: 6 persona ở service + khách gọi được endpoint ở
`DynamicAuthorizationManagerTest`; test unit pass, 35 test Testcontainers chưa chạy được (container
Maven không thấy Docker). Web: `tsc`, eslint, prettier pass. **Còn:** chạy `./mvnw test` trên máy có
Docker, kiểm tra tay trên web, chụp màn hình drawer.

**Estimated scope:** M

### Task 7: Ingest hàng loạt theo manifest

**Description:** `evals/ingest.py` dùng luồng của Task 4 cho từng dòng `selected=true` chưa `done`:
upload với `title` của Task 7a, ingest qua agent, cập nhật `ingest_status` và `document_id` (UUID
backend). PDF parse ra rỗng thì đánh dấu `empty_text`. Phải ingest lại toàn bộ theo manifest hiện
tại vì 46 tài liệu đã đổi nhãn quyền khi gộp bộ demo.

**Acceptance criteria:**
- [ ] 100% dòng có `ingest_status` ∈ {`done`, `empty_text`, `error:<lý do>`}
- [ ] Chạy lại chỉ xử lý dòng chưa `done`
- [ ] Số document trong bảng `documents` và trong Qdrant khớp số dòng `done`

**Verification:**
- [ ] Manual check: đếm document ở trang admin và trong Qdrant

**Dependencies:** Task 4, Task 6, Task 7a, Task 7c

**Files likely touched:** `evals/ingest.py`

**Estimated scope:** M

### Checkpoint 3
- [ ] Tài liệu đã chốt ở Checkpoint 1 ingest xong, hiện ở trang admin, chip mở được file với tiêu đề đọc được, nhãn hợp lệ
- [ ] `pytest tests/evals` + `ruff check evals` pass

---

## Phase 4: Bộ câu hỏi

### Task 8: Sinh câu hỏi theo 5 nhóm

**Description:** `evals/questions/generate.py` lấy mẫu tài liệu `done` theo
department, gọi LLM rẻ sinh câu hỏi + đáp án chuẩn cho các nhóm `normal`,
`calculation`, `unanswerable`, `off_topic`/`social`, và câu gốc cho nhóm
`access`. Prompt yêu cầu diễn đạt lại, không chép nguyên văn. Ghi
`questions.jsonl` theo schema `spec.md` §3.4.

**Acceptance criteria:**
- [ ] ~300 câu, phân bố đúng bảng §3.4 (±10%)
- [ ] Mọi câu có đáp án đều có `expected_doc_ids` hợp lệ trong manifest
- [ ] Chi phí sinh ≤ 2 USD, in ra cuối lệnh

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_questions_schema.py`

**Dependencies:** Task 7

**Files touched:** `evals/questions/plan.py`, `evals/questions/build.py`, `tests/evals/test_questions.py`

**Kết quả (30-09-2026, làm trước ingest theo yêu cầu người dùng):** không có API key LLM trên máy nên câu hỏi do **subagent Claude** viết từ trích đoạn PDF (thay cho `generate.py` gọi API). `plan.py` đặt `selected=true` theo phương án B (`quality=ok`, ≤ 100 trang: 1.085 file) — **chưa được người dùng chốt ở Checkpoint 1**. 263 ô → 297 dòng (`dataset/questions.jsonl`); 191/191 trích dẫn khớp nguyên văn; 2 ô bị bỏ (danh sách giảng viên kèm ngày sinh; biểu mẫu font VNI). Câu `unanswerable` chưa được kiểm tra bằng truy xuất thật — cần kiểm lại sau ingest.

**Estimated scope:** M

**Cập nhật 10-10-2026:** gộp bộ demo (`dataset/demo/questions_demo.jsonl`) vào
`dataset/official/questions.jsonl` → **497 câu** (221 `q*` + 276 `d*`; bỏ 76 câu gốc bộ demo đã dùng
lại). 46 tài liệu nhận nhãn quyền của bộ demo (`label_source=demo`). Thêm cột `source_set`; tạo lại
`questions_review.csv`, giữ 21 ghi chú cũ.

### Task 8b: Bổ sung `calculation` và `unanswerable`

**Description:** Thêm câu để mỗi nhóm đạt 80–100 câu (`calculation` 31 → ~90, `unanswerable` 50 →
~90), theo cùng quy trình `plan.py` → viết câu hỏi → `build.py`. `calculation` phủ GPA, điểm học phần
LT/TH, quy đổi thang điểm, học phí, tín chỉ, số ngày; có cả câu hỏi ngược ("cần bao nhiêu điểm
cuối kỳ"). `unanswerable` phải được kiểm tra bằng truy xuất thật sau ingest là không có đáp án.

**Acceptance criteria:**
- [ ] `calculation` và `unanswerable` mỗi nhóm 80–100 câu; id mới không trùng
- [ ] Câu có đáp án: `evidence_found=true`, `expected_doc_ids` có trong manifest và đã `done`
- [ ] Câu `unanswerable`: top-10 truy xuất không chứa đáp án (kiểm tra sau ingest)

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_questions.py`

**Dependencies:** Task 7

**Files likely touched:** `evals/questions/plan.py`, `evals/questions/build.py`, `dataset/official/questions.jsonl`

**Estimated scope:** M

### Task 9: Duyệt tay và sinh persona cho nhóm `access`

**Description:** Với mỗi câu gốc nhóm `access` (trên tài liệu private), sinh 4
persona: đủ quyền (`access_level` bằng đúng mức), thiếu 1 bậc, khác department,
wildcard `*`, kèm `expect_visible`. Xuất file CSV phụ để người duyệt ≥ 20% câu
và ghi lại `reviewed=true`.

**Acceptance criteria:**
- [ ] Mỗi câu `access` có đủ 4 persona với `expect_visible` đúng quy tắc
- [ ] ≥ 20% tổng số câu `reviewed=true` (ưu tiên nhóm `access`, các dòng có `review_note`, câu mới của Task 8b); câu bị loại đã xóa

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_personas.py`
- [ ] Manual check: người dùng duyệt xong

**Dependencies:** Task 8

**Files touched:** `evals/questions/build.py` (persona + bảng duyệt), `tests/evals/test_questions.py`

**Trạng thái:** 4 persona đã sinh (24 thấy / 24 không thấy). Bảng duyệt `dataset/questions_review.csv` (21 dòng có `review_note` xếp đầu: phép tính quá dễ, câu dựa trên biểu mẫu/mục lục, nguồn font VNI...). **Chờ người dùng duyệt ≥ 20%.**

**Việc phát sinh:** triage chưa phát hiện PDF dùng **font VNI/TCVN3 cũ** (text dạng "Hß trÿ 70% hßc phí"); cần thêm luật vào `evals/crawl/triage.py` trước khi ingest.

**Estimated scope:** S

---

## Phase 5: Runner

### Task 10: Runner + metrics deterministic

**Description:** `evals/run.py` đọc `questions.jsonl`, chạy từng câu qua
`run_graph()` với wrapper Task 5, ghi `results.jsonl`. Đổi `expected_doc_ids` (`file_id`) sang
`document_id` (UUID backend) qua manifest trước khi so. `evals/metrics.py`
tính #1 Recall@5, #5 leakage/over-restriction, #6 latency p50/p95, #7 citation
accuracy, #8 confusion matrix (node → intent). Hỗ trợ `--limit`.

**Acceptance criteria:**
- [ ] Mỗi dòng `results.jsonl` có: câu hỏi, response, citations, doc id truy xuất kèm metadata phân quyền, nodes, TTFT, tổng thời gian
- [ ] Metrics là hàm thuần, có test cho case biên phân quyền (bằng mức, thấp hơn 1, `*`, public)
- [ ] Một câu lỗi không làm dừng cả lần chạy (ghi `error`)

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_metrics.py tests/evals/test_run_smoke.py`
- [ ] Manual check: `evals.run --limit 20` chạy thật

**Dependencies:** Task 5, Task 9

**Files likely touched:** `evals/run.py`, `evals/metrics.py`, `tests/evals/test_metrics.py`, `tests/evals/test_run_smoke.py`

**Estimated scope:** M

### Task 10b: Chế độ `web=on` và chỉ số nhánh web

**Description:** `evals/run.py --web {off,on}` (mặc định `off`) đặt `CHAT_WEB_SEARCH_ENABLED`.
`evals/recording.py` thêm `RecordingWebSearch` thay `app.graph.streaming_graph.search_web`: ghi
truy vấn, URL, điểm, nội dung vào `results.jsonl`, đồng thời ghi/phát lại `web_cache.jsonl`.
`evals/metrics.py` tính #13 (kết cục so với bảng `spec.md` §12.8), phần deterministic của #14
(domain, có citation) và #15 (URL web trùng `source_url` của tài liệu private).

**Acceptance criteria:**
- [ ] Chạy lại với cùng cache không gọi Tavily lần nào
- [ ] Mỗi dòng có `outcome` ∈ {`rag`, `web`, `ticket`, `none`} và `expected_outcomes` theo bảng §12.8
- [ ] #15 báo riêng, không cộng vào leakage #5
- [ ] Report so `web=off` và `web=on` theo cặp trên cùng câu

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_metrics.py tests/evals/test_recording.py` (bảng kết cục, cache phát lại)
- [ ] Manual check: `--web on --limit 20` chạy thật, đọc các dòng `outcome=web`

**Dependencies:** Task 10

**Files likely touched:** `evals/run.py`, `evals/recording.py`, `evals/metrics.py`, tests tương ứng

**Estimated scope:** S

### Task 11: Chấm bằng RAGAS + chặn ngân sách

**Description (đổi 10-10-2026, `spec.md` §12.6):** `evals/judge.py` đổi mỗi dòng `results.jsonl`
thành `SingleTurnSample` của RAGAS (ánh xạ ở `spec.md` §4) rồi chạy `ragas.evaluate`:
- Câu có đáp án (295 dòng): `Faithfulness`, `FactualCorrectness`, `LLMContextRecall`,
  `ResponseRelevancy`, `IDBasedContextPrecision`, `IDBasedContextRecall`.
- Mọi câu: `AspectCritic` "câu trả lời có từ chối hoặc chuyển ticket không" (#4).
- Dòng `outcome=web` (chế độ `web=on`): `Faithfulness` với `retrieved_contexts` = nội dung trang web
  (#14); chỉ chấm `FactualCorrectness` khi câu có `expected_answer`.
- Giám khảo cấu hình được (`--judge-model`, mặc định `gemini-3.1-flash-lite-preview`, fallback
  `--judge-fallback gemini-3.5-flash-lite` chỉ khi lỗi 429/5xx sau retry), thinking mức thấp nhất.
  Mỗi điểm ghi model đã chấm; > 5% dòng dùng fallback thì chấm lại bằng model chính (`spec.md` §5). Prompt RAGAS chuyển sang tiếng Việt bằng `adapt_prompts` một lần, lưu
  vào `evals/ragas_prompts/`.
- **Bể key Gemini** (`spec.md` §5): `GEMINI_API_KEYS` (danh sách, chỉ trong `.env.eval`, không commit);
  bộ giới hạn tốc độ riêng cho từng key (≤ 12 RPM), chia vòng tròn; 429 hết RPM → đợi `retryDelay` rồi
  đổi key; 429 hết RPD → loại key tới 14h/15h giờ VN; 503 → backoff 2–32 s, tối đa 5 lần, không đổi
  key, rồi mới fallback; 504 → timeout 90 s, retry 2 lần. Ghi điểm từng dòng ngay khi xong; chạy lại
  chỉ chấm dòng thiếu hoặc `NaN`. Cuối lần chạy in số lời gọi, số 429/503/504, số dòng dùng fallback
  theo từng key (key che bớt).
- Trước khi chạy ước tính chi phí; vượt `--max-cost` (mặc định 4 USD) thì dừng. Ghi token và chi
  phí thực tế (callback đếm token của RAGAS).
- Ghim phiên bản `ragas` trong nhóm dependency `eval` của `pyproject.toml` (không vào image
  production).

**Acceptance criteria:**
- [ ] Điểm của từng metric ghi lại theo `id` câu hỏi vào `results.jsonl`; metric lỗi → `NaN` + lý do, không crash
- [ ] Dừng trước khi gọi API khi ước tính > `--max-cost`
- [ ] Test bể key: giới hạn RPM mỗi key, loại key hết RPD, 503 không đổi key, dừng có checkpoint khi mọi key hết RPD (client giả)
- [ ] Trước lần chạy đầu: ghi RPM/RPD thật của từng project (AI Studio) vào report; xác nhận 22 key nằm ở 22 project khác nhau
- [ ] `--metrics` chọn được tập con (vd chỉ ID-based cho ablation, không tốn tiền)
- [ ] Chi phí thực tế cả bộ ≤ trần người dùng đã duyệt

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_judge.py` (ánh xạ sample, ước tính chi phí, chặn ngân sách; LLM giả)
- [ ] Manual check: `--limit 20` chạy thật, đọc điểm + chi phí; rồi chạy trọn bộ

**Dependencies:** Task 10; người dùng duyệt trần chi phí

**Files likely touched:** `evals/judge.py`, `evals/run.py`, `evals/ragas_prompts/`, `pyproject.toml`, `tests/evals/test_judge.py`

**Estimated scope:** M

### Task 12: `report.md`

**Description:** `evals/report.py` sinh `runs/<ts>/report.md`: bảng #1–#8 (gồm điểm RAGAS #2–#3d), #11, #12 (tổng
và theo nhóm câu / department / `source_set`), mỗi tỷ lệ kèm **n và khoảng tin cậy Wilson 95%**,
leakage bằng 0 ghi cận trên 3/n, confusion matrix, top câu lỗi (điểm thấp,
leakage, từ chối nhầm), chi phí, cấu hình (model, commit hash), ghi chú "nhãn
phân quyền là giả lập". Thêm `taskfiles/eval.yml`.

**Acceptance criteria:**
- [ ] Report đủ mục trên, đọc được không cần mở `results.jsonl`
- [ ] Không có tỷ lệ nào thiếu n hoặc khoảng tin cậy
- [ ] Leakage > 0 thì liệt kê từng câu vi phạm
- [ ] `task eval:run` chạy được

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_report.py`

**Dependencies:** Task 11

**Files likely touched:** `evals/report.py`, `tests/evals/test_report.py`, `taskfiles/eval.yml`, `Taskfile.yml`

**Estimated scope:** S

### Checkpoint 4
- [ ] Một lệnh chạy trọn bộ ra `report.md` đủ #1–#8
- [ ] Chi phí RAGAS ≤ trần đã duyệt
- [ ] `pytest tests/evals` + `ruff check evals` pass
- [ ] Người duyệt xem report trước khi sang Phase 6

---

## Phase 6: Phục vụ báo cáo

### Task 13: Ablation qua env `CHAT_ALLOW_*`

**Lưu ý (từ Task 3):** "rerank" hiện chỉ là sắp xếp theo điểm tương đồng + lọc ngưỡng, chưa có cross-encoder; `no-rerank` = tắt ngưỡng lọc. Report phải ghi rõ.

**Description:** Thêm 3 setting vào `app/core/config.py` theo convention
`CHAT_ALLOW_REPAIR_JSON`: `CHAT_ALLOW_HYDE`, `CHAT_ALLOW_RERANK`,
`CHAT_ALLOW_QUERY_DECOMPOSITION` (`bool`, mặc định `true`). Khi `false`:
HyDE → truy xuất bằng câu hỏi gốc (vẫn giữ bước viết lại câu hỏi follow-up
nếu tách được); rerank → giữ nguyên thứ tự truy xuất, cắt top-k; decomposition
→ luôn đi nhánh SINGLE. Runner nhận `--ablation {no-hyde,no-rerank,no-decomposition}`
và đặt env tương ứng; report so sánh #1–#3 với baseline.

**Acceptance criteria:**
- [ ] 3 env có trong `config.py`, `.env.example` (có comment), mặc định `true`
- [ ] Mỗi cờ `false` có unit test chứng minh bước đó bị bỏ qua
- [ ] Report có bảng so sánh ablation với baseline, so theo cặp trên cùng câu (McNemar / bootstrap theo cặp)
- [ ] Toàn bộ test hiện có pass không sửa

**Verification:**
- [ ] Tests pass: `.venv/bin/python -m pytest`
- [ ] Lint: `.venv/bin/python -m ruff check app evals`

**Dependencies:** Checkpoint 4

**Files likely touched:** `app/core/config.py`, `.env.example`, `app/graph/nodes/query_transformation.py`, `app/graph/nodes/post_retrieval_rerank.py`, `tests/graph/test_chat_allow_flags.py`, `evals/run.py`

**Estimated scope:** M

### Task 14: Kiểm chứng giám khảo bằng người

**Description:** Chọn ngẫu nhiên **50 câu** (có seed, phân tầng theo `category`) từ một lần chạy,
xuất `human_review.csv` để 2–3 người chấm faithfulness/correctness 1–5.
`evals/human_agreement.py` đổi điểm RAGAS 0–1 sang 1–5 (chia 5 khoảng đều), tính % lệch ≤ 1 điểm và
Spearman giữa người và RAGAS, ghi vào report. 50 thay cho 30 vì với 30 cặp, khoảng tin cậy của
Spearman quá rộng (±0,3) để kết luận giám khảo đáng tin.

**Acceptance criteria:**
- [ ] `human_review.csv` có 50 câu, đủ điểm của người chấm
- [ ] Report có số đồng thuận

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_human_agreement.py`

**Dependencies:** Checkpoint 4

**Files likely touched:** `evals/human_agreement.py`, `tests/evals/test_human_agreement.py`

**Estimated scope:** S

### Checkpoint 5
- [ ] Tất cả tiêu chí hoàn thành trong `spec.md` §11 đạt
- [ ] Report cuối sẵn sàng đưa vào báo cáo đồ án
