# Spec: Đánh giá chất lượng trả lời UniSage (RAG Evaluation Harness)

> Tài liệu mô tả **cái gì** và **vì sao**. Thứ tự triển khai, rủi ro xem
> `plan.md`; checklist task xem `todo.md` trong cùng thư mục.

## 1. Mục tiêu

Luồng chat của `unisage-agent` đã chạy tương đối hoàn chỉnh nhưng chưa có cách
đo chất lượng. Cần một bộ đánh giá **chạy lại được bằng một lệnh**, cho ra:

1. **Bảng chỉ số** để đưa vào báo cáo đồ án / bảo vệ trước hội đồng.
2. **Danh sách câu trả lời lỗi** (kèm chunk truy xuất, trích dẫn, điểm giám
   khảo) để tìm chỗ sửa trong pipeline.

Người dùng của bộ đánh giá: nhóm phát triển UniSage (chạy trên máy dev).

## 2. Giả định (sửa ngay nếu sai)

1. Code đánh giá viết bằng Python, đặt trong `unisage-agent/evals/` (ngoài
   `app/`, không đóng gói vào image) để gọi thẳng `run_graph()` in-process.
2. Chạy graph **in-process**, không qua `POST /chat/stream`: SSE hiện chỉ trả
   `token`/`error`/`done`, không lộ intent, chunk truy xuất hay citations. Lấy
   các thông tin này bằng wrapper ghi lại (`RetrievalServiceProtocol`,
   `GraphTrace`, `token_sink`) — **không sửa code production** cho mục đích
   đánh giá. Hệ quả: latency đo được là latency của graph, không gồm
   gateway/Java.
3. Security context của từng câu hỏi được dựng trực tiếp
   (`AcademicSecurityContext.department_access`), không cần user thật trong DB.
4. Tài liệu crawl là **công khai**; nhãn phân quyền là **giả lập** và phải ghi
   rõ điều này trong báo cáo.
5. Qdrant + Postgres + MinIO dev đang chạy được; dữ liệu đánh giá nằm trong
   collection riêng `unisage_eval` (§12.4), không lẫn với `unisage_chunks`.

## 3. Dữ liệu

### 3.1. Nguồn tài liệu

- PDF công khai trên các domain `*.iuh.edu.vn`: phòng ban, khoa/viện, cơ sở
  tỉnh. Chỉ IUH, không dùng bộ QA công khai (ViQuAD, Zalo…).
- Crawl lịch sự: tuân thủ `robots.txt`, ≤ 1 request/giây/host, User-Agent ghi
  rõ mục đích, bỏ file > 30 MB, dedupe theo sha256.
- Bước **discover** chạy trước, xuất danh sách nguồn (`sources.csv`) để người
  duyệt; chỉ tải hàng loạt sau khi duyệt.

### 3.2. Lưu trữ

```
unisage-gateway/dataset/
├── files/               ← PDF có text (gitignore)
│   └── <unit_slug>/<file_id>.pdf
├── scanned_pdf/         ← PDF scan / chỉ ảnh / lớp text hỏng, cùng cấu trúc (gitignore)
│   └── <unit_slug>/<file_id>.pdf
├── discovered.csv       ← mọi link PDF tìm thấy (commit)
├── download_errors.csv  ← lỗi tải, trùng nội dung, PDF hỏng (commit)
├── sources.csv          ← danh sách trang nguồn đã duyệt (commit)
├── manifest.csv         ← danh sách file crawl + nhãn (commit)
├── questions.jsonl      ← bộ câu hỏi (commit)
├── human_review.csv     ← chấm tay 30 câu (commit)
└── runs/<yyyyMMdd-HHmm>/
    ├── results.jsonl    ← kết quả từng câu (commit)
    └── report.md        ← bảng chỉ số (commit)
```

### 3.3. `manifest.csv`

| Cột | Ý nghĩa |
|---|---|
| `file_id` | 12 ký tự đầu của sha256 |
| `file_name` | Tên file gốc |
| `source_url` | URL tải PDF |
| `source_page` | Trang HTML chứa link |
| `unit` | Đơn vị (phòng/khoa/cơ sở) suy ra từ domain/đường dẫn |
| `campus` | Cơ sở (`HCM`, hoặc tên tỉnh) |
| `department_id` | Department gán cho tài liệu |
| `is_public` | `true`/`false` |
| `access_level` | Số nguyên; **để trống khi `is_public=true`** |
| `pages`, `size_bytes`, `sha256`, `local_path`, `crawled_at` | Metadata file |
| `text_chars` | Số ký tự text trích được (phát hiện PDF scan, ước tính token) |
| `quality` | `ok` (ở `files/`), còn lại ở `scanned_pdf/`: `scanned`, `garbled_ocr`, `no_diacritics`, `broken_encoding`. Gán bởi `evals.crawl.triage` |
| `selected` | `true` nếu được chọn ingest (chốt ở Task 3 sau khi dự trù chi phí) |
| `ingest_status`, `document_id` | Trạng thái nạp vào hệ thống |

Quy tắc nhãn: `department_id` lấy từ bảng `departments` của backend, map từ đơn vị phát hành (bảng map `unit → department_id` trong `evals/label_map.yaml`, người duyệt); khoảng 30% tài liệu
mỗi department được chuyển thành `is_public=false` với `access_level` giả lập.
Quy tắc hiển thị khớp `app/rag/vectorstore/qdrant_store.py`: chunk thấy được
khi `is_public`, hoặc user có entry cùng department (hoặc `*`) với
`access_level ≥` của chunk.

### 3.4. `questions.jsonl`

Khoảng **300 câu**, LLM sinh từ tài liệu, người duyệt tay ≥ 20%.

```json
{"id": "q0042", "category": "access", "question": "...",
 "expected_answer": "...", "expected_doc_ids": ["a1b2c3d4e5f6"],
 "expected_intent": "rag",
 "asker": {"department_access": [{"department_id": "phong-tai-chinh", "access_level": 1}]},
 "expect_visible": false, "reviewed": true}
```

| `category` | Số lượng dự kiến | Kiểm tra |
|---|---|---|
| `normal` | ~150 | Truy xuất + trả lời thường, gồm câu nhiều ý |
| `calculation` | ~30 | Tính học phí, tín chỉ, GPA |
| `unanswerable` | ~40 | Không có trong tài liệu → phải từ chối / ticket |
| `access` | ~50 | Cùng một câu hỏi với nhiều persona (đủ quyền, thiếu 1 bậc, khác department, `*`) |
| `off_topic` / `social` | ~30 | Phân loại intent đúng |

## 4. Tiêu chí đánh giá

| # | Tiêu chí | Định nghĩa | Cách đo | Tần suất |
|---|---|---|---|---|
| 1 | **Recall@k** (k=5) | % câu có đáp án mà ít nhất một `expected_doc_ids` nằm trong top-k sau rerank | Deterministic, so ở mức **tài liệu** (chunk id đổi khi ingest lại) | Mỗi lần |
| 2 | **Faithfulness** | Mọi khẳng định trong câu trả lời có căn cứ trong context được truy xuất (1–5) | LLM giám khảo | Mỗi lần |
| 3 | **Answer correctness** | Khớp ý với `expected_answer` (1–5) | LLM giám khảo (chung lời gọi với #2, #4) | Mỗi lần |
| 4 | **Từ chối đúng** | `unanswerable`: % từ chối hoặc ticket; câu có đáp án: % từ chối nhầm | Giám khảo + cờ `used_ticket_fallback` | Mỗi lần |
| 5 | **Leakage / over-restriction** | Leakage: % câu có chunk vượt quyền trong kết quả truy xuất (**mục tiêu 0%**). Over-restriction: % persona đủ quyền nhưng không truy xuất được tài liệu đúng | Deterministic, áp lại quy tắc §3.3 lên metadata chunk | Mỗi lần |
| 6 | **Latency** | p50/p95 của TTFT (lần gọi `token_sink` đầu tiên) và tổng thời gian | Đo thời gian | Mỗi lần |
| 7 | **Citation accuracy** | % citation có `documentId` thuộc `expected_doc_ids` | Deterministic | Mỗi lần |
| 8 | **Confusion matrix intent** | Nhãn `expected_intent` so với nhánh thực tế suy ra từ node trong `GraphTrace` (`greeting`, `social`, `off_topic`, `calculation`, `rag`, `ticket`) | Deterministic | Mỗi lần |
| 9 | **Ablation** | Chỉ số #1–#3 khi tắt rerank / HyDE / tách câu hỏi con (pipeline chưa có sparse nên không ablate) | Chạy lại với env `CHAT_ALLOW_*` | Khi cần |
| 10 | **Kiểm chứng giám khảo** | Độ đồng thuận giữa người chấm và LLM trên 30 câu (% lệch ≤ 1 điểm, Spearman) | `human_review.csv` | Một lần |

Điểm 1–5 được báo cáo dưới dạng trung bình và tỷ lệ ≥ 4.

## 5. Ngân sách

- Giám khảo: Gemini Flash / Flash-Lite hoặc OpenAI mini. **≤ 1 USD cho một lần
  chạy** (không tính ablation).
- Mỗi câu gọi giám khảo **đúng 1 lần**, trả về JSON chấm cả #2, #3, #4. Không
  dùng pipeline nhiều lời gọi của RAGAS.
- Runner ước tính chi phí trước khi chạy (số câu × token ước lượng × bảng giá)
  và **dừng nếu dự kiến > 1 USD**; ghi chi phí thực tế vào `report.md`.
- Sinh câu hỏi là chi phí một lần, tính riêng (dự kiến ≤ 2 USD).
- **Ingest** (embedding + LLM sinh câu hỏi multi-representation mỗi chunk) là
  chi phí một lần. Đo ở Task 3 (`cost-estimate.md`): toàn bộ 1.298 PDF có text
  ≈ 9–13 USD; phương án đề xuất (`quality=ok`, ≤ 100 trang, 1.085 file) ≈ 5–7 USD. Chờ
  người dùng chốt tập `selected=true`.
- Chi phí chạy graph (model chat của chính UniSage) không tính vào 1 USD, nhưng
  được ghi lại trong report.

## 6. Commands

Chạy từ `unisage-agent/` (Linux; Windows thay `.venv/bin/python` bằng
`.venv\Scripts\python.exe`). Tất cả cũng có alias trong `taskfiles/eval.yml`.

```bash
DATASET=../unisage-gateway/dataset
export QDRANT_COLLECTION=unisage_eval   # bắt buộc cho ingest + run

.venv/bin/python -m app.tools.register_embedding_index_identity   # một lần cho collection mới

.venv/bin/python -m evals.crawl.discover --dataset $DATASET
.venv/bin/python -m evals.crawl.download --dataset $DATASET
.venv/bin/python -m evals.crawl.triage --dataset $DATASET     # bỏ PDF hỏng, tách PDF scan
.venv/bin/python -m evals.label --dataset $DATASET            # gán nhãn theo quy tắc
.venv/bin/python -m evals.ingest --dataset $DATASET           # nạp vào hệ thống
.venv/bin/python -m evals.questions.generate --dataset $DATASET --n 300
.venv/bin/python -m evals.run --dataset $DATASET              # một lần đánh giá
.venv/bin/python -m evals.run --dataset $DATASET --limit 20   # chạy thử
.venv/bin/python -m evals.run --dataset $DATASET --ablation no-rerank   # đặt CHAT_ALLOW_RERANK=false
.venv/bin/python -m evals.human_agreement --dataset $DATASET

# Kiểm tra chất lượng code
.venv/bin/python -m pytest tests/evals
.venv/bin/python -m ruff check evals tests/evals
```

## 7. Cấu trúc code

```
unisage-agent/
├── evals/
│   ├── crawl/        discover.py, download.py, robots.py
│   ├── label.py      gán department / is_public / access_level
│   ├── ingest.py     nạp tài liệu theo manifest
│   ├── questions/    generate.py, prompts.py, schema.py
│   ├── recording.py  RecordingRetrieval, RecordingTrace, token timer
│   ├── judge.py      một lời gọi / câu, JSON output, ước tính chi phí
│   ├── metrics.py    hàm thuần tính 10 tiêu chí
│   ├── report.py     results.jsonl → report.md
│   └── run.py        CLI
├── tests/evals/      unit test cho metrics, label, recording, judge parsing
└── taskfiles/eval.yml
```

## 8. Code style

Theo convention hiện có của `unisage-agent`: Python 3.12, ruff line-length 100,
type hint đầy đủ, docstring giải thích "vì sao". Metrics là hàm thuần để test
không cần LLM:

```python
def recall_at_k(results: Sequence[QuestionResult], k: int = 5) -> float:
    """Doc-level: chunk ids change on re-ingest, document ids don't."""

    answerable = [r for r in results if r.question.expected_doc_ids]
    if not answerable:
        return 0.0
    hits = sum(
        1 for r in answerable
        if set(r.retrieved_doc_ids[:k]) & set(r.question.expected_doc_ids)
    )
    return hits / len(answerable)
```

## 9. Chiến lược test

- `pytest` trong `tests/evals/`, hermetic (không mạng, không LLM thật).
- Unit test cho: từng hàm trong `metrics.py` (có case biên phân quyền: bằng
  mức, thấp hơn 1, wildcard, public), quy tắc nhãn (`is_public=true` →
  `access_level` rỗng), parse output giám khảo (JSON lỗi → đánh dấu
  `judge_error`, không crash), ước tính chi phí chặn khi > 1 USD.
- Test crawler với HTML fixture (không gọi IUH thật).
- Smoke test: `evals.run --limit 5` với `FakeRetrievalService` và model mock có
  sẵn trong `tests/llm_mocks.py`.

## 10. Ranh giới

- **Luôn:** tuân thủ `robots.txt` và rate limit; ghi chi phí mỗi lần chạy; ghi
  rõ "nhãn phân quyền là giả lập" trong report; chạy `pytest tests/evals` trước
  commit.
- **Hỏi trước:** sửa file trong `app/` ngoài 3 cờ `CHAT_ALLOW_*` đã duyệt; thêm dependency mới; ingest vào collection khác `unisage_eval`; tăng
  ngân sách.
- **Không bao giờ:** commit PDF; crawl ngoài `*.iuh.edu.vn`; commit API key;
  thay đổi hành vi production chỉ để đẹp số liệu; vượt 1 USD/lần mà không hỏi.

## 11. Tiêu chí hoàn thành

- [ ] `sources.csv` được người duyệt; `manifest.csv` có toàn bộ PDF crawl được;
      tập `selected=true` có ≥ 150 PDF từ ≥ 8 đơn vị (con số chỉnh ở Task 3), 100% dòng có nhãn hợp lệ (`is_public=true` ⇒ `access_level` rỗng).
- [ ] `dataset/files/` bị gitignore; `git status` không hiện PDF.
- [ ] 100% tài liệu `selected=true` có `ingest_status=done` (hoặc lý do lỗi).
- [ ] `cost-estimate.md` được người dùng duyệt trước khi ingest.
- [ ] `questions.jsonl` có ~300 câu, đủ 5 nhóm, ≥ 20% `reviewed=true`.
- [ ] `evals.run` chạy trọn bộ bằng một lệnh, sinh `results.jsonl` và
      `report.md` có đủ tiêu chí #1–#8.
- [ ] Chi phí giám khảo thực tế ≤ 1 USD, ghi trong report.
- [ ] Leakage được đo; nếu > 0% thì report liệt kê từng câu vi phạm.
- [ ] Có ít nhất một lần ablation (#9) và một lần kiểm chứng giám khảo (#10).
- [ ] `pytest tests/evals` và `ruff check evals` pass.

## 12. Open Questions

1. ~~Đường nạp tài liệu~~ → **Đã chốt: đường B**, gọi thẳng pipeline của
   agent (cùng logic `/ingestion/chunking` + `embed_chunks`, chạy `embed_chunks`
   ngay trong process của script, không qua hàng đợi Celery). Không đi qua API
   upload của backend vì: collection riêng khiến UI admin hiện tài liệu mà chat
   thường không truy xuất được, DB dev bị lẫn dữ liệu đánh giá, và cần thêm
   worker riêng dễ ghi nhầm collection. Bước bị bỏ qua (Java nhận upload → MinIO)
   không ảnh hưởng nội dung chunk. `document_id` sinh từ `file_id`. Hệ quả:
   luồng upload của backend không được kiểm tra ở đây (thuộc kiểm thử tích hợp).
2. ~~Department ID~~ → **Đã chốt:** dùng department có sẵn trong bảng
   `departments` của backend. Đơn vị crawl được map vào department gần nhất
   (cột `unit` giữ tên gốc, `department_id` lấy từ DB).
3. ~~Ablation~~ → **Đã chốt:** sửa code, thêm 3 env theo convention
   `CHAT_ALLOW_REPAIR_JSON`: `CHAT_ALLOW_HYDE`, `CHAT_ALLOW_RERANK`,
   `CHAT_ALLOW_QUERY_DECOMPOSITION`, kiểu `bool`, **mặc định `true`** (hành vi
   production không đổi). Pipeline hiện chưa có sparse/BM25/RRF nên không có
   cờ sparse.
4. ~~Môi trường ingest~~ → **Đã chốt:** Qdrant collection riêng
   `unisage_eval`, chọn qua env `QDRANT_COLLECTION` có sẵn (không sửa code).
   Mọi process tham gia (script `evals`, Celery worker nếu ingest qua worker)
   phải chạy với `QDRANT_COLLECTION=unisage_eval`. Collection mới cần đăng ký
   embedding identity một lần bằng `app/tools/register_embedding_index_identity.py`.
