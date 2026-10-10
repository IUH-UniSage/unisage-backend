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
2. Chạy graph **in-process** khi *đánh giá*, không qua `POST /chat/stream`: SSE hiện chỉ trả
   `token`/`error`/`done`, không lộ intent, chunk truy xuất hay citations. Lấy
   các thông tin này bằng wrapper ghi lại (`RetrievalServiceProtocol`,
   `GraphTrace`, `token_sink`) — **không sửa code production** cho mục đích
   đánh giá. Hệ quả: latency đo được là latency của graph, không gồm
   gateway/Java.
3. Security context của từng câu hỏi được dựng trực tiếp
   (`AcademicSecurityContext.department_access`), không cần user thật trong DB.
4. Tài liệu crawl là **công khai**; nhãn phân quyền là **giả lập** và phải ghi
   rõ điều này trong báo cáo.
5. Qdrant + Postgres + MinIO + Celery worker dev đang chạy được. Tài liệu đánh giá được
   **nạp qua luồng upload thật** (§12.1, đổi ngày 10-10-2026): có bản ghi trong bảng
   `documents` của backend, admin xem và sửa được, chat thường truy xuất được, bấm chip
   citation thì mở được file.

## 3. Dữ liệu

### 3.1. Nguồn tài liệu

- PDF công khai trên các domain `*.iuh.edu.vn`: phòng ban, khoa/viện, cơ sở
  tỉnh. Chỉ IUH, không dùng bộ QA công khai (ViQuAD, Zalo…).
- Crawl lịch sự: tuân thủ `robots.txt`, ≤ 1 request/giây/host, User-Agent ghi
  rõ mục đích, bỏ file > 30 MB, dedupe theo sha256.
- Bước **discover** chạy trước, xuất danh sách nguồn (`sources.csv`) để người
  duyệt; chỉ tải hàng loạt sau khi duyệt.

### 3.2. Lưu trữ

PDF lưu trên Hugging Face org (`hgjyhm/unisage-iuh-dataset`), không commit vào git. Repo
gateway chỉ giữ CSV/JSONL (`.gitignore`: `dataset/**/*.pdf`, `dataset/**/files/`,
`dataset/**/scanned_pdf/`, `dataset/**/excerpts/`, `dataset/.cache/`).

```
unisage-gateway/dataset/
├── official/                 ← bộ chính thức, dùng cho đánh giá
│   ├── files/<unit_slug>/<file_id>.pdf        ← PDF có text (tải từ HF, gitignore)
│   ├── scanned_pdf/<unit_slug>/<file_id>.pdf  ← PDF scan / text hỏng (gitignore)
│   ├── discovered.csv, download_errors.csv, sources.csv
│   ├── manifest.csv          ← danh sách file + nhãn + tiêu đề + trạng thái ingest
│   ├── questions.jsonl       ← 497 câu (gộp bộ gốc + demo, §3.4)
│   ├── questions_review.csv  ← bảng duyệt tay
│   └── question_work/
├── demo/                     ← bộ demo 04-10-2026 (nguồn của các câu `d*`), giữ để tra cứu
├── human_review.csv          ← chấm tay 30 câu (commit)
└── runs/<yyyyMMdd-HHmm>/
    ├── results.jsonl         ← kết quả từng câu (commit)
    └── report.md             ← bảng chỉ số (commit)
```

### 3.3. `manifest.csv`

| Cột | Ý nghĩa |
|---|---|
| `file_id` | 12 ký tự đầu của sha256 |
| `file_name` | Tên file gốc lúc crawl (nhiều file khó đọc: `CTDT.pdf`, `ilovepdf_merged(4).pdf`, có tiền tố uuid) |
| `title` | **Tiêu đề đọc được**, dùng làm `documents.title` và tên file khi upload (§3.5) |
| `title_source` | `filename` (làm sạch từ tên file), `heading` (lấy từ tiêu đề trang 1) hoặc `manual` (người sửa, không bị ghi đè) |
| `source_url` | URL tải PDF |
| `source_page` | Trang HTML chứa link |
| `unit` | Đơn vị (phòng/khoa/cơ sở) suy ra từ domain/đường dẫn |
| `campus` | Cơ sở (`HCM`, hoặc tên tỉnh) |
| `department_id` | **Mã** department do backend seed (`departments.name`, vd `PHONG_DAO_TAO`); UUID tra ở bước ingest |
| `is_public` | `true`/`false` |
| `access_level` | Số nguyên; **để trống khi `is_public=true`** |
| `pages`, `size_bytes`, `sha256`, `local_path`, `crawled_at` | Metadata file |
| `text_chars` | Số ký tự text trích được (phát hiện PDF scan, ước tính token) |
| `quality` | `ok` (ở `files/`), còn lại ở `scanned_pdf/`: `scanned`, `garbled_ocr`, `no_diacritics`, `broken_encoding`. Gán bởi `evals.crawl.triage` |
| `selected` | `true` nếu được chọn ingest (chốt ở Task 3 sau khi dự trù chi phí) |
| `label_source` | `auto` (do `evals.label` gán), `demo` (46 tài liệu được bộ demo gán lại để có case phân quyền) hoặc `manual` (người sửa tay, không bị ghi đè) |
| `ingest_status`, `document_id` | Trạng thái nạp vào hệ thống; `document_id` là **UUID của bảng `documents` bên backend** (khác `file_id`) |

Quy tắc nhãn (`evals.label`, cấu hình `evals/label_map.yaml`): map đơn vị phát hành → mã
department của backend; trong mỗi nhóm (department, `quality=ok` hay không) khoảng 30%
tài liệu thành `is_public=false` với `access_level` ngẫu nhiên 1–4 (seed cố định);
mức 0 bị loại để mọi tài liệu private đều có persona "thiếu 1 bậc", mức 5 để dành cho admin.
Một số đơn vị hướng ra công chúng (trang chính, tuyển sinh, cẩm nang) luôn public.
Quy tắc hiển thị khớp `app/rag/vectorstore/qdrant_store.py`: chunk thấy được
khi `is_public`, hoặc user có entry cùng department (hoặc `*`) với
`access_level ≥` của chunk.

### 3.4. `questions.jsonl`

**497 dòng**, gộp ngày 10-10-2026 từ hai bộ:

- Bộ gốc (`q*`, 30-09-2026): sinh bởi `evals.questions.plan` → subagent Claude viết câu hỏi
  từ trích đoạn PDF → `evals.questions.build`. Còn 221/297 câu sau khi gộp.
- Bộ demo (`d*`, 04-10-2026, `dataset/demo/`): 276 câu phủ các luồng (phân quyền, SINGLE/MULTI,
  web search). 76 câu gốc mà bộ demo đã dùng lại (`reused_from`) bị bỏ khỏi bộ gốc để không chấm
  trùng; bản trong bộ demo được giữ vì có nhãn quyền mới.
- Script gộp kiểm tra: id không trùng, mọi `expected_doc_ids` có trong manifest, nhãn quyền trong
  câu khớp manifest.

```json
{"id": "q0002", "source_set": "official", "slot_id": "acc-001", "category": "access",
 "case": "", "question": "...", "expected_answer": "...", "evidence": "<trích nguyên văn>",
 "evidence_found": true, "expected_doc_ids": ["a1b2c3d4e5f6"], "expected_intent": "academic_advisory",
 "routing_mode": "", "sub_question_count": null, "expect_web_search": false,
 "department_id": "KHOA_LUAT_KHCT", "doc_is_public": false, "doc_access_level": 3,
 "persona": "same_department_one_level_below",
 "asker": {"department_access": [{"department_id": "KHOA_LUAT_KHCT", "access_level": 2}]},
 "expect_visible": false, "reused_from": "", "reviewed": false, "generator": "..."}
```

- `expected_doc_ids` là `file_id` trong manifest; runner đổi sang `document_id` (UUID backend)
  qua manifest trước khi so với chunk truy xuất.
- `expected_intent` dùng đúng nhãn của graph: `academic_advisory`,
  `academic_calculation`, `off_topic`, `social_chat`.
- `evidence_found`: `build.py` kiểm tra trích dẫn có nằm nguyên văn trong trích
  đoạn không (chuẩn hóa khoảng trắng + Unicode NFC).
- Persona: tài liệu public → `guest` (không quyền); tài liệu private → cùng
  department đúng mức. Nhóm `access` có 4 persona: đúng mức (thấy), thiếu 1 bậc
  (không thấy), department khác mức 5 (không thấy), `*` mức 5 (thấy).

| `category` | Hiện có | Mục tiêu | Kiểm tra |
|---|---|---|---|
| `access` | 188 | giữ | Cùng câu hỏi với nhiều persona; đo leakage / over-restriction |
| `normal` | 178 | giữ | Truy xuất + trả lời thường, gồm câu một ý, nhiều ý (MULTI), so sánh |
| `unanswerable` | 50 | **80–100** | Không có trong tài liệu → phải từ chối / ticket |
| `calculation` | 31 | **80–100** | Tính tín chỉ, học phí, GPA, điểm học phần, số ngày, tỷ lệ… |
| `off_topic` / `social` | 26 / 18 | giữ | Phân loại intent đúng |
| `web_search` | 6 | giữ | Cần tìm thêm trên web |

Vì sao bổ sung `calculation` và `unanswerable`: với n ≈ 30–50, khoảng tin cậy 95% của một tỷ lệ
khoảng 80% rộng ±11–14 điểm %, không đủ để báo cáo riêng nhóm. Với n ≈ 90 còn khoảng ±8 điểm %.
Chất lượng trước số lượng: câu bổ sung phải qua cùng kiểm tra `evidence_found` và được duyệt tay.

**Đánh giá độ đủ (10-10-2026, trên 497 câu):** gộp bộ demo không thêm tài liệu nào (106 tài liệu demo
đều nằm trong 1.085 tài liệu `selected`), bộ câu hỏi dùng 212 tài liệu của 28 department; 873 tài
liệu còn lại đóng vai nhiễu khi truy xuất.

| Phép đo | n hiệu dụng | Độ chính xác (Wilson 95% ở p≈0,8) | Đủ? |
|---|---|---|---|
| RAGAS + Recall@5 tổng | 295 dòng / 246 câu khác nhau | ±5 điểm % | Đủ |
| `normal` | 178 | ±6 | Đủ |
| `calculation` | 31 | ±14 | **Chưa**, cần Task 8b |
| `unanswerable` | 50 | ±11 | **Chưa**, cần Task 8b |
| Leakage (persona không được thấy) | 102 dòng / 49 câu gốc | 0 lỗi → cận trên ~3% (3/102) | Đủ cho mục tiêu 0% |
| Theo persona (37 dòng mỗi loại) | 37 | 0 lỗi → cận trên ~8% | Chỉ báo cáo tham khảo |
| SINGLE / MULTI / so sánh / web search | 16 / 14 / 4 / 6 | > ±20 | Chỉ nêu ví dụ, không báo tỷ lệ |
| Ablation theo cặp (McNemar) | ~246 | phát hiện được chênh ≥ 8–10 điểm % | Đủ cho chênh lớn |

Kết luận: đủ để báo cáo chất lượng tổng của pipeline, nhóm `normal` và leakage. Chưa đủ cho
`calculation`, `unanswerable` (Task 8b) và các nhánh định tuyến. Điều kiện chung: `reviewed=0`, phải
duyệt tay ≥ 20% (Task 9) trước khi công bố số liệu. Lưu ý các dòng `access` của cùng một câu gốc
không độc lập, nên tính khoảng tin cậy theo câu gốc (bootstrap theo cụm).

### 3.5. Tiêu đề tài liệu

Tên hiện trên chip citation (`citations.py: source_title`) lấy từ `object_key` = `UUID_<tên file
upload>`; khung xem file và trang admin dùng `documents.title`. Vì vậy tiêu đề phải đọc được **trước
khi upload**:

1. `evals.titles` làm sạch `file_name`: bỏ tiền tố uuid, đuôi file, thay `-`/`_` bằng khoảng trắng.
2. Tên còn khó đọc (quá ngắn, chỉ là mã như `CTDT`, `DHCT20`, `K14-CTK-CTM`, hoặc tên công cụ như
   `ilovepdf_merged`, `scan`) thì lấy dòng tiêu đề lớn nhất ở trang 1 (PyMuPDF) → `title_source=heading`.
3. Người duyệt xem các dòng `heading` và sửa tay nếu sai (`title_source=manual`).
4. Upload với `title = manifest.title` và tên file `<title>.pdf`, để cả chip, khung xem và admin đều
   hiện cùng một tên.

## 4. Tiêu chí đánh giá

Quy tắc một câu trả lời thế nào là đạt, theo từng nhóm câu hỏi: xem `scoring-rules.md`.

| # | Tiêu chí | Định nghĩa | Cách đo | Tần suất |
|---|---|---|---|---|
| 1 | **Recall@k** (k=5) | % câu có đáp án mà ít nhất một `expected_doc_ids` nằm trong top-k sau rerank | Deterministic, so ở mức **tài liệu** (chunk id đổi khi ingest lại) | Mỗi lần |
| 2 | **Faithfulness** | Tỷ lệ khẳng định trong câu trả lời được context truy xuất ủng hộ (0–1) | RAGAS `Faithfulness` | Mỗi lần |
| 3 | **Answer correctness** | Độ khớp khẳng định giữa câu trả lời và `expected_answer` (0–1, F1) | RAGAS `FactualCorrectness` | Mỗi lần |
| 3b | **Context recall** | Tỷ lệ ý của `expected_answer` có trong context truy xuất (0–1) | RAGAS `LLMContextRecall` | Mỗi lần |
| 3c | **Response relevancy** | Câu trả lời có đúng trọng tâm câu hỏi (0–1) | RAGAS `ResponseRelevancy` (LLM + embedding) | Mỗi lần |
| 3d | **Context precision / recall mức tài liệu** | So `document_id` truy xuất với `expected_doc_ids` theo thứ hạng | RAGAS `IDBasedContextPrecision` / `IDBasedContextRecall` (không gọi LLM) | Mỗi lần |
| 4 | **Từ chối đúng** | `unanswerable` và persona không được thấy: % từ chối hoặc ticket; câu có đáp án: % từ chối nhầm | RAGAS `AspectCritic` ("câu trả lời có từ chối không") + cờ `used_ticket_fallback` | Mỗi lần |
| 5 | **Leakage / over-restriction** | Leakage: % câu có chunk vượt quyền trong kết quả truy xuất (**mục tiêu 0%**). Over-restriction: % persona đủ quyền nhưng không truy xuất được tài liệu đúng | Deterministic, áp lại quy tắc §3.3 lên metadata chunk | Mỗi lần |
| 6 | **Latency** | p50/p95 của TTFT (lần gọi `token_sink` đầu tiên) và tổng thời gian | Đo thời gian | Mỗi lần |
| 7 | **Citation accuracy** | % citation có `documentId` thuộc `expected_doc_ids` | Deterministic | Mỗi lần |
| 8 | **Confusion matrix intent** | Nhãn `expected_intent` so với nhánh thực tế suy ra từ node trong `GraphTrace` (`greeting`, `social`, `off_topic`, `calculation`, `rag`, `ticket`) | Deterministic | Mỗi lần |
| 9 | **Ablation** | Chỉ số #1–#3 khi tắt rerank / HyDE / tách câu hỏi con (pipeline chưa có sparse nên không ablate) | Chạy lại với env `CHAT_ALLOW_*` | Khi cần |
| 10 | **Kiểm chứng giám khảo** | Độ đồng thuận giữa người chấm và LLM trên 30 câu (% lệch ≤ 1 điểm, Spearman) | `human_review.csv` | Một lần |

Điểm RAGAS (0–1) được báo cáo dưới dạng trung bình và tỷ lệ ≥ 0,7. Mẫu đưa vào RAGAS theo
`SingleTurnSample`: `user_input` = câu hỏi, `response` = câu trả lời, `retrieved_contexts` = text
chunk sau rerank, `retrieved_context_ids` = `document_id` của chunk, `reference` = `expected_answer`,
`reference_contexts` = `evidence`, `reference_context_ids` = `expected_doc_ids` đã đổi sang
`document_id`. Chỉ câu có đáp án (`normal`, `calculation`, `access` với `expect_visible=true`; 295
dòng) chạy #2–#3d; mọi câu chạy #4.

**Cách báo cáo số liệu:** mọi tỷ lệ đều ghi kèm **n** và **khoảng tin cậy 95%** (Wilson), cả số
tổng lẫn số theo `category` / `source_set`; trung bình điểm 1–5 ghi kèm khoảng tin cậy bootstrap. Với
leakage bằng 0, ghi cận trên theo quy tắc 3/n (vd 0/150 lượt "không được thấy" → < 2%). So sánh hai
cấu hình (ablation, trước/sau khi sửa) dùng **so sánh theo cặp** trên cùng câu (McNemar cho tỷ lệ,
bootstrap theo cặp cho điểm), không so hai con số rời. Mỗi chỉ số do giám khảo LLM chấm phải ghi kèm
độ đồng thuận với người chấm (#10).

| # | Tiêu chí bổ sung | Định nghĩa | Cách đo |
|---|---|---|---|
| 11 | **Mở được nguồn** | % citation có `documentId` mở được qua `GET /documents/{id}/citation` và tiêu đề khác `file_id` | Deterministic, gọi API backend sau khi chạy |
| 13 | **Kết cục đúng nhánh** | So kết cục thực tế (`rag` / `web` / `ticket`) với kết cục kỳ vọng theo bảng §12.8 | Deterministic, từ `GraphTrace` + `used_web_search` + `used_ticket_fallback` |
| 14 | **Chất lượng câu trả lời từ web** | Câu trả lời dùng web: faithfulness với trang web lấy về, mọi citation `WEB` thuộc `iuh.edu.vn`, có ít nhất một citation | RAGAS `Faithfulness` (context = nội dung trang web) + kiểm tra domain deterministic |
| 15 | **Lộ qua web** | Persona không được thấy, câu trả lời dùng web và trang web chính là `source_url` của tài liệu private (manifest) | Deterministic; báo riêng, **không** cộng vào leakage #5 (§12.8) |
| 12 | **Leakage khi xem file** | Persona không đủ quyền gọi `GET /documents/{id}/citation` cho tài liệu private phải bị từ chối (**mục tiêu 0%**) | Deterministic, dùng câu `access` có `expect_visible=false` |

## 5. Ngân sách

- **Đổi ngày 10-10-2026: dùng RAGAS thay giám khảo một lời gọi** (§12.6).
- **Giám khảo đã chọn (10-10-2026):** `gemini-3.1-flash-lite-preview`, fallback `gemini-3.5-flash-lite`.
  Giá LiteLLM tải 10-10-2026, token suy luận (thinking) tính như output:

  | Model | Vào / 1M | Ra / 1M |
  |---|---|---|
  | `gemini-3.1-flash-lite-preview` (chính) | $0,25 | $1,50 |
  | `gemini-3.5-flash-lite` (fallback) | $0,30 | $2,50 |

  **Khối lượng một lần chạy đầy đủ:** 295 dòng có đáp án × ~10 lời gọi (Faithfulness 2,
  FactualCorrectness 4, LLMContextRecall 1, ResponseRelevancy 1, AspectCritic 1–2) + 202 dòng chỉ chấm
  từ chối × 1 lời gọi ≈ **3.150 lời gọi**, ~5,2M token vào, ~0,75M token ra (giả định 8 chunk/câu,
  ~500 token/chunk).

  | Kịch bản | Chỉ dùng model chính | Nếu toàn bộ chạy bằng fallback |
  |---|---|---|
  | Thinking tắt / `minimal` | **~2,4 USD** | ~3,4 USD |
  | Thinking `low` (~500 token suy luận mỗi lời gọi, +1,6M token ra) | ~4,8 USD | ~7,4 USD |
  | Kèm 20% dự phòng retry, thinking `minimal` | **~2,9 USD** | ~4,1 USD |

  Fallback chỉ dùng khi model chính lỗi (429/5xx sau retry), thường < 5% số lời gọi, nên chi phí thực
  tế gần cột trái. **Trần đề xuất 4 USD / lần**; đặt thinking ở mức thấp nhất model cho phép.
  Embedding cho `ResponseRelevancy` (~1.200 đoạn ngắn) < 0,01 USD.

  **Chạy bằng 22 key Gemini free (cập nhật 10-10-2026): chi phí 0 USD, giới hạn nằm ở hạn mức.**
  - Hạn mức áp **theo project, không theo key**, và theo từng model; số lời gọi/ngày (RPD) reset lúc
    0h giờ Thái Bình Dương, tức 14h (giờ mùa hè) hoặc 15h giờ Việt Nam
    ([Google](https://ai.google.dev/gemini-api/docs/rate-limits)). 22 key chỉ cộng dồn được khi nằm ở
    22 project khác nhau; nhiều key trong cùng project vẫn chỉ có hạn mức của 1 project.
  - Google không còn công bố con số free tier; nguồn bên thứ ba cho flash-lite: 15 RPM, 250k TPM,
    RPD 500–1.500, có bài đo thực tế chỉ còn 20 RPD. **Phải xem con số thật của từng project trong AI
    Studio** trước khi chạy.
  - Khối lượng: ~3.150 lời gọi/lần chạy, +15% retry ≈ **3.600 lời gọi**, ~1,9k token/lời gọi. TPM không
    phải nút thắt (12 lời gọi × 1,9k ≈ 23k token/phút/project, xa mức 250k).

  | Kịch bản | Tốc độ an toàn (12 RPM/project) | Một lần chạy | Số lần chạy/ngày |
  |---|---|---|---|
  | 22 project, RPD ≥ 500 | 264 lời gọi/phút | **~15 phút** lý thuyết, **30–60 phút** thực tế có retry 503 | ~3 (RPD 500) – 6 (RPD 1.000) |
  | 22 project, RPD 250 | 264 lời gọi/phút | như trên | ~1 |
  | 22 project, RPD 20 | — | ~8 ngày (440 lời gọi/ngày) | không khả thi: chuyển sang trả phí (~2,4 USD/lần) hoặc chỉ chấm một phần |
  | 22 key nhưng cùng 1 project | 12 lời gọi/phút | ~5 giờ, và hết RPD sau 500–1.000 lời gọi | 1 lần chạy kéo dài nhiều ngày |

  Model fallback `gemini-3.5-flash-lite` có hạn mức riêng nên có thêm sức chứa, nhưng phải tuân quy tắc
  không trộn giám khảo bên dưới. Nếu graph UniSage cũng gọi Gemini free (~5 lời gọi/câu × 497 ≈ 2.500
  lời gọi/lần), **không dùng chung project** với giám khảo, nếu không hai bên ăn chung RPD.

  **Rủi ro và cách xử lý (Task 11):**

  | Lỗi | Nguyên nhân | Xử lý |
  |---|---|---|
  | 429 `RESOURCE_EXHAUSTED`, vượt RPM | Dồn lời gọi vào một project | Bộ giới hạn tốc độ phía client cho từng key (≤ 12 RPM, chia vòng tròn); đợi đúng `retryDelay` trong lỗi rồi chuyển sang key khác |
  | 429, hết RPD | Hết lời gọi trong ngày của project | Đánh dấu key "hết đến 14h/15h" và bỏ khỏi vòng; hết mọi key thì dừng có checkpoint, hôm sau chạy tiếp |
  | 503 `UNAVAILABLE` (model quá tải) | Model preview + free tier bị xếp ưu tiên thấp, hay gặp giờ cao điểm Mỹ (đêm, sáng sớm VN) | Lỗi theo model, **không đổi key**: backoff lũy thừa 2–32 s có jitter, tối đa 5 lần, rồi mới dùng fallback; dự trù 5–20% lời gọi phải retry. Nên chạy vào ban ngày giờ VN |
  | 504 `DEADLINE_EXCEEDED` | Prompt dài (NLI của Faithfulness với 8 chunk) | Timeout client 90 s, retry 2 lần; giới hạn context đưa vào giám khảo ≤ 8 chunk × 500 token |
  | Kết quả lẫn hai giám khảo | Fallback dùng quá nhiều | Ghi model + key (đã che) cho từng điểm; > 5% dòng dùng fallback thì chấm lại bằng model chính |
  | Đứt giữa chừng | Hết RPD, mất mạng | Ghi điểm từng dòng ngay khi xong; chạy lại chỉ chấm dòng còn thiếu hoặc `NaN` |

  **Rủi ro không phải kỹ thuật:**
  - Tạo nhiều project để cộng dồn hạn mức free có thể bị Google coi là lách hạn mức (cần đọc điều
    khoản Gemini API), rủi ro bị khóa project hoặc tài khoản.
  - Dữ liệu gửi qua free tier có thể được Google dùng để cải thiện sản phẩm. Bộ dữ liệu là tài liệu
    công khai, nhãn private chỉ là giả lập, nên chấp nhận được, nhưng phải ghi trong báo cáo.
  - Model preview có thể bị đổi hoặc ngừng giữa các lần chạy.

  **Đã chốt (10-10-2026, nguồn sự thật do người dùng xác nhận trong AI Studio): 22 key ở 22 project
  khác nhau, mỗi project 15 RPM, 250k TPM, 500 RPD** cho `gemini-3.1-flash-lite-preview` → **11.000 lời gọi/ngày**, 264 lời gọi/phút (12 RPM × 22).

  | Việc | Lời gọi (gồm retry 503 10–40%) | Thời gian | Còn lại trong ngày |
  |---|---|---|---|
  | 1 lần chạy RAGAS đầy đủ | 3.500–5.250 (~160–240/project) | 20–40 phút | 2–3 lần chạy đầy đủ/ngày |
  | + graph UniSage nếu dùng chung key (~2.500/lần) | 6.000–7.750 | — | chỉ 1 lần đầy đủ/ngày → tách project riêng cho UniSage |
  | `--limit 20` (thử nhanh) | ~250 | 1–2 phút | không đáng kể |
  | Ablation chỉ #1, #3d (không LLM) | 0 | — | không tốn hạn mức |
  | Chấm lại dòng `NaN` / dòng dùng fallback | thường < 500 | vài phút | — |

  Lịch đề xuất: lần chạy đầy đủ bắt đầu sau khi reset (14h/15h giờ VN), chạy trong ngày giờ VN để
  tránh cao điểm 503; chừa ≥ 1.500 lời gọi/ngày cho chấm lại. Fallback `gemini-3.5-flash-lite` có
  500 RPD riêng/project (cần xác nhận trong AI Studio), chỉ dùng khi model chính 503 kéo dài.

  **Khuyến nghị:** dùng 22 key cho các lần chạy thử và chạy lặp (`--limit`, ablation). Lần chạy dùng
  cho báo cáo cuối, nếu RPD thực tế thấp hoặc 503 nhiều, chuyển sang key trả phí (~2,4–2,9 USD).

  **Quy tắc dùng fallback:** mỗi điểm ghi lại model đã chấm. Nếu > 5% số dòng phải dùng fallback thì
  chấm lại các dòng đó bằng model chính trước khi báo cáo, để không trộn hai giám khảo trong một
  bảng số. Model preview có thể bị đổi hoặc ngừng: report ghi đúng tên model và ngày chạy. Nếu model
  chat của UniSage cũng là Gemini thì ghi chú nguy cơ giám khảo thiên vị cùng họ model, và Task 14
  (người chấm) càng quan trọng.
- Chạy rút gọn (ablation, sửa lỗi lặp lại): chỉ #1, #3d (không LLM) và #2 trên `--limit`; chi phí
  giám khảo ≈ 0.
- Runner ước tính chi phí trước khi chạy (số mẫu × token ước lượng × bảng giá)
  và **dừng nếu dự kiến > `--max-cost`** (mặc định 4 USD); ghi chi phí thực tế vào `report.md`.
- Sinh câu hỏi là chi phí một lần, tính riêng (dự kiến ≤ 2 USD).
- **Ingest** (embedding + LLM sinh câu hỏi multi-representation mỗi chunk) là
  chi phí một lần. Đo ở Task 3 (`cost-estimate.md`): toàn bộ 1.298 PDF có text
  ≈ 9–13 USD; phương án đề xuất (`quality=ok`, ≤ 100 trang, 1.085 file) ≈ 5–7 USD. Chờ
  người dùng chốt tập `selected=true` (106 tài liệu của bộ demo đều đã `selected=true`).
- Chi phí chạy graph (model chat của chính UniSage) không tính vào 1 USD, nhưng
  được ghi lại trong report.

## 6. Commands

Chạy từ `unisage-agent/` (Linux; Windows thay `.venv/bin/python` bằng
`.venv\Scripts\python.exe`). Tất cả cũng có alias trong `taskfiles/eval.yml`.

```bash
DATASET=../unisage-gateway/dataset/official
export EVAL_BACKEND_URL=http://localhost:8081   # backend-java, tài khoản admin qua EVAL_ADMIN_*

.venv/bin/python -m evals.crawl.discover --dataset $DATASET
.venv/bin/python -m evals.crawl.download --dataset $DATASET
.venv/bin/python -m evals.crawl.triage --dataset $DATASET     # bỏ PDF hỏng, tách PDF scan
.venv/bin/python -m evals.label --dataset $DATASET            # gán nhãn theo quy tắc
.venv/bin/python -m evals.titles --dataset $DATASET           # sinh cột title / title_source
.venv/bin/python -m evals.ingest --dataset $DATASET           # upload qua backend + ingest qua agent
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
│   ├── titles.py     tiêu đề đọc được cho manifest
│   ├── ingest.py     upload qua backend (POST /documents) + ingest qua agent theo manifest
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
- **Hỏi trước:** sửa file trong `app/` ngoài 3 cờ `CHAT_ALLOW_*` đã duyệt và phần tiêu đề /
  quyền xem file ở Task 7b, 7c; thêm dependency mới; ingest vào môi trường khác môi trường dev đã
  chốt; tăng ngân sách.
- **Không bao giờ:** commit PDF; crawl ngoài `*.iuh.edu.vn`; commit API key;
  thay đổi hành vi production chỉ để đẹp số liệu; vượt 1 USD/lần mà không hỏi.

## 11. Tiêu chí hoàn thành

- [ ] `sources.csv` được người duyệt; `manifest.csv` có toàn bộ PDF crawl được;
      tập `selected=true` có ≥ 150 PDF từ ≥ 8 đơn vị (con số chỉnh ở Task 3), 100% dòng có nhãn hợp lệ (`is_public=true` ⇒ `access_level` rỗng).
- [ ] `dataset/files/` bị gitignore; `git status` không hiện PDF.
- [ ] 100% tài liệu `selected=true` có `ingest_status=done` (hoặc lý do lỗi), có bản ghi trong
      `documents`, hiện ở trang admin và sửa được.
- [ ] 100% tài liệu `selected=true` có `title` đọc được; chip citation hiện tiêu đề, không hiện `file_id`.
- [ ] Bấm chip citation mở được file đúng trang; persona không đủ quyền không mở được file private.
- [ ] `cost-estimate.md` được người dùng duyệt trước khi ingest.
- [ ] `questions.jsonl` có ≥ 497 câu, `calculation` và `unanswerable` mỗi nhóm 80–100 câu,
      ≥ 20% `reviewed=true` (ưu tiên nhóm `access` và các dòng có `review_note`).
- [ ] `evals.run` chạy trọn bộ bằng một lệnh, sinh `results.jsonl` và
      `report.md` có đủ tiêu chí #1–#8, #11, #12; mọi tỷ lệ có n và khoảng tin cậy 95%.
- [ ] Chi phí RAGAS thực tế ≤ trần đã duyệt (đề xuất 4 USD / lần), ghi trong report.
- [ ] Leakage được đo; nếu > 0% thì report liệt kê từng câu vi phạm.
- [ ] Có ít nhất một lần ablation (#9) và một lần kiểm chứng giám khảo (#10).
- [ ] `pytest tests/evals` và `ruff check evals` pass.

## 12. Open Questions

1. ~~Đường nạp tài liệu~~ → **Đổi ngày 10-10-2026: nạp qua luồng upload thật.** Người dùng cần
   tài liệu lưu trong DB, hiện ở trang admin để sửa, và bấm chip citation xem được file. Đường B
   không tạo bản ghi `documents` và không có `object_key`, nên chip hiện `file_id` và khung xem file
   báo "Nguồn này chưa liên kết với tài liệu". Luồng mới (giống wizard của web):
   `POST /documents` (backend, multipart, `title` từ manifest) → MinIO → `/ingestion/preview` →
   `/ingestion/chunking` → `/ingestion/embedding` (agent, `object_key = document.sourceUrl`) → Celery
   `embed_chunks` → Qdrant. Hệ quả: cần Celery worker chạy; luồng upload của backend giờ được kiểm
   tra luôn. Quyết định cũ giữ lại bên dưới để tra cứu.

   *Cũ (30-09-2026):* **Đã chốt: đường B**, gọi thẳng pipeline của
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
4. ~~Môi trường ingest~~ → **Đổi ngày 10-10-2026:** bỏ collection riêng `unisage_eval`. Tài liệu ở
   trang admin phải truy xuất được trong chat thường, nên nạp vào collection mặc định của môi
   trường dev. Để số liệu không bị tài liệu dev khác làm nhiễu, chạy trên **DB + bucket MinIO +
   collection mới, trống** (chỉ chứa bộ `official`), cấu hình qua env, không sửa code. Quyết định cũ:

   *Cũ (30-09-2026):* **Đã chốt:** Qdrant collection riêng
   `unisage_eval`, chọn qua env `QDRANT_COLLECTION` có sẵn (không sửa code).
   Mọi process tham gia (script `evals`, Celery worker nếu ingest qua worker)
   phải chạy với `QDRANT_COLLECTION=unisage_eval`. Collection mới cần đăng ký
   embedding identity một lần bằng `app/tools/register_embedding_index_identity.py`.
5. ~~Quyền xem file nguồn~~ → **Đã sửa 10-10-2026 (Task 7c).** `GET /documents/{id}/citation` chuyển
   sang `PUBLIC_PATHS` để khách mở được tài liệu public; tài liệu private chỉ trả về khi người gọi là
   SUPER_ADMIN hoặc có `user_department_access` cùng department với mức ≥ `minAccessLevel` (cùng quy
   tắc với bộ lọc Qdrant), ngược lại trả 403 mã 2312. Mô tả cũ:
   *(phát hiện 10-10-2026):* `GET /documents/{id}/citation` nằm trong
   `PredefinedPublicPaths` (không cần đăng nhập) và `resolveFileUrl` trả presigned URL cho cả tài liệu
   private (đoạn kiểm tra quyền đang bị comment). Khi tài liệu private được nạp thật, bất kỳ ai có
   `documentId` đều tải được file. Task 7c sửa lỗi này trước khi ingest bộ có nhãn private; #12 đo nó.
6. **Giám khảo → RAGAS (đổi 10-10-2026).** Người dùng yêu cầu đánh giá bằng RAGAS để chỉ số có định
   nghĩa chuẩn, so được với tài liệu tham khảo. Thay giám khảo tự viết một lời gọi (§5 cũ) bằng
   `Faithfulness`, `FactualCorrectness`, `LLMContextRecall`, `ResponseRelevancy`, `AspectCritic` (từ
   chối) và `IDBasedContextPrecision/Recall`. Đánh đổi: nhiều lời gọi mỗi câu (~7) nên chi phí tăng
   ~4 lần; prompt RAGAS viết bằng tiếng Anh nên phải chạy `adapt_prompts` sang tiếng Việt một lần và
   lưu lại; điểm RAGAS là 0–1 thay cho thang 1–5. Các chỉ số deterministic (#1, #5–#8, #11, #12)
   giữ nguyên, không đi qua RAGAS.
7. **Tách môi trường dev / eval / prod (10-10-2026).** Không cần sửa code: mọi tài nguyên đã đọc từ
   env, và env của tiến trình đè lên file dotenv (`.ENV` của backend, `.env` của agent). Quy ước tên:

   | Tài nguyên | dev | eval | prod |
   |---|---|---|---|
   | Backend `DB_NAME` | `assistant_DB` | `assistant_eval` | server riêng |
   | Agent `DB_URL` (db) | `unisage_agent_db` | `unisage_agent_eval` | server riêng |
   | `MINIO_BUCKET` (backend + agent) | `unisage-documents` | `unisage-eval` | server riêng |
   | `QDRANT_COLLECTION` (agent + Celery worker) | `unisage_chunks` | `unisage_eval_chunks` | server riêng |
   | `REDIS_URL` (backend + agent) | `/0` | `/3` | server riêng |
   | Celery broker / result (agent) | `/1` / `/2` | `/4` / `/5`, `CELERY_QUEUE_PREFIX=unisage_eval` | server riêng |

   Cách chuyển (cập nhật 10-10-2026):
   - **Agent:** `task <task> env=eval` (vd `task be:dev env=eval`, `task be:worker env=eval`,
     `task db:upgrade env=eval`). Task nạp `.env.eval` trước rồi `.env`; `task env:show env=eval` in ra
     DB/bucket/collection/Redis đang dùng. Mẫu: `.env.eval.example`. `task db:up env=eval` bật Postgres +
     MinIO (compose của backend-java), Redis + Qdrant (compose `.devcontainer`) và tạo `assistant_eval`,
     `unisage_agent_eval` nếu chưa có. Chạy được trên Windows/macOS/Linux (interpreter shell của Task,
     các vòng lặp chạy trong container Postgres; venv tự chọn `Scripts/` hay `bin/`).
   - **Backend:** Spring profile `eval` (`application-eval.properties`), bật bằng một dòng
     `APP_PROFILE=eval` trong `.ENV` (`application.properties` có `spring.profiles.active=${APP_PROFILE:}`).
     Đặt `SPRING_PROFILES_ACTIVE` trong `.ENV` **không** bật được profile (đã thử 10-10-2026), nên phải
     đi qua `APP_PROFILE`.
   - **Gateway:** không có DB, không cần đổi. Test tự
   động (`mvn test` dùng Testcontainers, `pytest` dùng fake) không chạm DB nào trong bảng. Prod không
   bao giờ dùng chung instance với dev/eval. `evals.ingest` và `evals.run` từ chối chạy nếu
   `DB_NAME`, `MINIO_BUCKET` hoặc `QDRANT_COLLECTION` không chứa `eval`.
8. **Web search tự kích hoạt khi không thấy tài liệu (10-10-2026).** Khi rerank không để lại chunk
   nào cho một câu hỏi con, `WebSearchNode` tìm trên `iuh.edu.vn` (Tavily); không có kết quả thì mới
   chuyển ticket. Hai hệ quả cho việc đánh giá:
   - Câu `unanswerable` (không có trong kho) có thể được trả lời đúng từ web. Đó không phải lỗi.
   - Tài liệu private là **nhãn giả lập** trên PDF vốn công khai ở `iuh.edu.vn`. Persona không đủ
     quyền vẫn có thể nhận được nội dung từ web. Trong hệ thống thật, đó là thông tin công khai, nên
     không tính là leakage.

   **Cách đánh giá: chạy hai chế độ trên cùng bộ câu hỏi.**
   - **Chế độ chính `web=off`** (`CHAT_WEB_SEARCH_ENABLED=false`): đo RAG và phân quyền thuần. Mọi
     chỉ số #1–#8 tính ở chế độ này; kỳ vọng cho câu không có đáp án / không được thấy là từ chối
     hoặc ticket. Đây là số liệu đưa vào báo cáo chính.
   - **Chế độ phụ `web=on`**: chỉ đo thêm #13–#15 và độ chênh của #2–#4 so với `web=off`, theo cặp
     trên cùng câu.

   Kết cục kỳ vọng khi `web=on`:

   | Nhóm câu | Kết cục đúng | Kết cục sai |
   |---|---|---|
   | Có đáp án, persona được thấy (`normal`, `calculation`, `access` thấy) | `rag` | `web` (truy xuất trượt, bị web che), `ticket` |
   | `access` không được thấy | `ticket`, hoặc `web` mà không có chunk / citation tài liệu private | Có chunk hoặc `documentId` private trong context / citation (= leakage #5) |
   | `unanswerable` | `web` có citation `iuh.edu.vn` và faithful, hoặc `ticket` | Trả lời không có nguồn, hoặc bịa từ chunk không liên quan |
   | `web_search` (`expect_web_search=true`) | `web` | `ticket`, hoặc `rag` với chunk không liên quan |
   | `off_topic` / `social` | Không vào RAG, không gọi web | Gọi web |

   - Leakage #5 vẫn đo trên chunk Qdrant và citation `documentId`, **không** đo trên nội dung câu
     trả lời. Như vậy lỗi phân quyền thật không bị lẫn với nội dung web công khai.
   - Câu trả lời từ web không có `reference` đáng tin (trang web có thể khác PDF), nên chỉ chấm
     faithfulness với nội dung web (#14), không chấm `FactualCorrectness`. Riêng câu có đáp án mà
     rơi vào web thì vẫn chấm correctness với `expected_answer` để thấy web có cứu được không.
   - **Tái lập được:** kết quả web thay đổi theo thời gian và Tavily tính tiền theo lượt. Runner thay
     `app.graph.streaming_graph.search_web` bằng wrapper ghi/phát lại (không sửa `app/`): lần đầu
     ghi `web_cache.jsonl` (key = câu hỏi con), các lần sau (ablation, chạy lại) phát lại từ cache.
     Ước tính ≤ 2 lượt tìm/câu × ~200 câu kích hoạt ≈ 400 lượt cho lần chạy đầu.
