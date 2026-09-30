# Todo: Đánh giá chất lượng trả lời UniSage

Xem `spec.md` (yêu cầu, định nghĩa tiêu chí, schema dữ liệu) và `plan.md` (quyết
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

### Task 4: Nạp 1 PDF vào `unisage_eval` bằng pipeline agent (đường B)

**Description:** Tạo collection `unisage_eval` (đăng ký embedding identity bằng
`app/tools/register_embedding_index_identity.py`). Viết bản nháp
`evals/ingest.py` nạp 1 PDF theo đường B: dùng lại logic của
`/ingestion/chunking` và chạy `embed_chunks` in-process (không `.delay()`),
với `department_id` lấy từ DB, `is_public=false`, `access_level` hợp lệ,
`document_id` sinh từ `file_id`. Xác định các bước tối thiểu (có cần upload
MinIO / lưu draft không) và ghi lại vào `plan.md`.

**Acceptance criteria:**
- [ ] Payload chunk trong `unisage_eval` có đúng `department_id`, `access_level`, `is_public`, `document_id`
- [ ] `unisage_chunks` không có thêm point nào (đếm trước/sau)
- [ ] Không cần Celery worker đang chạy; không sửa file trong `app/`
- [ ] Các bước tối thiểu + lệnh tái hiện ghi vào `plan.md`

**Verification:**
- [ ] Manual check: đếm point hai collection trước/sau; xem payload

**Dependencies:** Checkpoint 1

**Files likely touched:** `plan.md`, `unisage-agent/evals/ingest.py` (bản nháp)

**Estimated scope:** S

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

### Task 7: Ingest hàng loạt theo manifest

**Description:** `evals/ingest.py` dùng đường nạp đã làm ở Task 4 (đường B), tra UUID department từ mã (`department_id` trong manifest) qua DB/API backend, nạp từng
dòng manifest có `selected=true` (chốt ở Task 3) và chưa `done`, cập nhật `ingest_status` và `document_id`. PDF parse
ra rỗng thì đánh dấu `empty_text`.

**Acceptance criteria:**
- [ ] 100% dòng có `ingest_status` ∈ {`done`, `empty_text`, `error:<lý do>`}
- [ ] Chạy lại chỉ xử lý dòng chưa `done`
- [ ] `evals.ingest` và `evals.run` thoát với lỗi nếu `settings.QDRANT_COLLECTION != "unisage_eval"`

**Verification:**
- [ ] Manual check: đếm document trong Qdrant khớp số dòng `done`

**Dependencies:** Task 6

**Files likely touched:** `evals/ingest.py`

**Estimated scope:** S

### Checkpoint 3
- [ ] Tài liệu đã chốt ở Checkpoint 1 ingest xong vào `unisage_eval`, nhãn hợp lệ
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

### Task 9: Duyệt tay và sinh persona cho nhóm `access`

**Description:** Với mỗi câu gốc nhóm `access` (trên tài liệu private), sinh 4
persona: đủ quyền (`access_level` bằng đúng mức), thiếu 1 bậc, khác department,
wildcard `*`, kèm `expect_visible`. Xuất file CSV phụ để người duyệt ≥ 20% câu
và ghi lại `reviewed=true`.

**Acceptance criteria:**
- [ ] Mỗi câu `access` có đủ 4 persona với `expect_visible` đúng quy tắc
- [ ] ≥ 20% tổng số câu `reviewed=true`; câu bị loại đã xóa

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
`run_graph()` với wrapper Task 5, ghi `results.jsonl`. `evals/metrics.py`
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

### Task 11: Giám khảo + chặn ngân sách

**Description:** `evals/judge.py`: một lời gọi mỗi câu, output JSON
`{faithfulness, correctness, refused, reason}`. Model cấu hình được (Gemini
hoặc OpenAI). Trước khi chạy, ước tính chi phí; nếu > 1 USD thì dừng (có cờ
`--max-cost`). Ghi token và chi phí thực tế.

**Acceptance criteria:**
- [ ] JSON lỗi → `judge_error`, không crash, có retry 1 lần
- [ ] Dừng trước khi gọi API khi ước tính > ngưỡng
- [ ] Chi phí thực tế cả bộ ~300 câu ≤ 1 USD

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_judge.py`
- [ ] Manual check: chạy trọn bộ, đọc chi phí in ra

**Dependencies:** Task 10

**Files likely touched:** `evals/judge.py`, `evals/run.py`, `tests/evals/test_judge.py`

**Estimated scope:** S

### Task 12: `report.md`

**Description:** `evals/report.py` sinh `runs/<ts>/report.md`: bảng #1–#8 (tổng
và theo nhóm câu / department), confusion matrix, top câu lỗi (điểm thấp,
leakage, từ chối nhầm), chi phí, cấu hình (model, commit hash), ghi chú "nhãn
phân quyền là giả lập". Thêm `taskfiles/eval.yml`.

**Acceptance criteria:**
- [ ] Report đủ mục trên, đọc được không cần mở `results.jsonl`
- [ ] Leakage > 0 thì liệt kê từng câu vi phạm
- [ ] `task eval:run` chạy được

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_report.py`

**Dependencies:** Task 11

**Files likely touched:** `evals/report.py`, `tests/evals/test_report.py`, `taskfiles/eval.yml`, `Taskfile.yml`

**Estimated scope:** S

### Checkpoint 4
- [ ] Một lệnh chạy trọn bộ ra `report.md` đủ #1–#8
- [ ] Chi phí giám khảo ≤ 1 USD
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
- [ ] Report có bảng so sánh ablation với baseline
- [ ] Toàn bộ test hiện có pass không sửa

**Verification:**
- [ ] Tests pass: `.venv/bin/python -m pytest`
- [ ] Lint: `.venv/bin/python -m ruff check app evals`

**Dependencies:** Checkpoint 4

**Files likely touched:** `app/core/config.py`, `.env.example`, `app/graph/nodes/query_transformation.py`, `app/graph/nodes/post_retrieval_rerank.py`, `tests/graph/test_chat_allow_flags.py`, `evals/run.py`

**Estimated scope:** M

### Task 14: Kiểm chứng giám khảo bằng người

**Description:** Chọn ngẫu nhiên 30 câu (có seed) từ một lần chạy, xuất
`human_review.csv` để 2–3 người chấm faithfulness/correctness 1–5.
`evals/human_agreement.py` tính % lệch ≤ 1 điểm và Spearman giữa người và giám
khảo, ghi vào report.

**Acceptance criteria:**
- [ ] `human_review.csv` có 30 câu, đủ điểm của người chấm
- [ ] Report có số đồng thuận

**Verification:**
- [ ] Tests pass: `pytest tests/evals/test_human_agreement.py`

**Dependencies:** Checkpoint 4

**Files likely touched:** `evals/human_agreement.py`, `tests/evals/test_human_agreement.py`

**Estimated scope:** S

### Checkpoint 5
- [ ] Tất cả tiêu chí hoàn thành trong `spec.md` §11 đạt
- [ ] Report cuối sẵn sàng đưa vào báo cáo đồ án
