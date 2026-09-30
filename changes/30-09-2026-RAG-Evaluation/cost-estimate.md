# Dự trù chi phí: Đánh giá chất lượng trả lời UniSage

> Task 3 trong `todo.md`. Số liệu tính ngày **30-09-2026** trên dữ liệu crawl
> thực tế. Tái hiện bằng:
>
> ```bash
> cd unisage-agent
> MODEL_REGISTRY_ENABLED=false .venv/bin/python -m evals.cost_estimate \
>   --dataset ../unisage-gateway/dataset --prices litellm_prices.json --sample 60 \
>   --scenario gemini-2.5-flash-lite gemini-embedding-001 \
>   --scenario gemini-2.5-flash gemini-embedding-001 \
>   --scenario gpt-4o-mini text-embedding-3-small \
>   --scenario gpt-4.1-nano text-embedding-3-small
> ```
>
> **Nguồn giá:** bảng giá JSON của LiteLLM
> (`BerriAI/litellm/main/model_prices_and_context_window.json`, tải
> 30-09-2026). Đây là cùng nguồn mà `model_prices` của backend đang đồng bộ.
> Đơn vị: USD / 1M token.

## Tóm tắt

**Model rẻ nhất** (chỉ xét `openai` và `google`, hai provider hệ thống hỗ trợ):
**`gpt-5-nano`** cho LLM ($0,05 vào / $0,40 ra) và
**`text-embedding-3-small`** cho embedding ($0,02, ra đúng 1.536 chiều như
collection đang cấu hình).

**Chi phí nạp tài liệu vào Qdrant theo từng bước** (chi tiết ở §4):

| Bước | Tất cả (1.111 file) | ≤ 100 trang (1.085 file) | ≤ 50 trang (1.034 file) |
|---|---|---|---|
| Số chunk (= số point Qdrant) | ~64.600 | ~34.800 | ~25.400 |
| 1–2. Parse + chunk (chạy local) | $0 | $0 | $0 |
| **3. Enrich** (1 lời gọi LLM mỗi chunk) | $7,02 | $3,79 | $2,76 |
| **4. Embed** (3 vector mỗi chunk) | $0,57 | $0,31 | $0,22 |
| 5. Upsert Qdrant (local) | $0 · ~1,3 GB | $0 · ~0,7 GB | $0 · ~0,5 GB |
| **Tổng** | **$7,59** | **$4,09** | **$2,98** |
| Tổng tối đa nếu thêm ~300 token suy luận mỗi lời gọi | $8,32 | $4,49 | $3,26 |

**Điểm chính:**
- **Khoảng 93% chi phí nằm ở bước enrich**, và trong đó khoảng 80% là token
  output. Đổi sang model embedding khác gần như không tiết kiệm được gì. Muốn
  giảm tiếp thì phải bớt số chunk, hoặc giảm `INGEST_MULTI_REP_QUESTION_COUNT`
  (hiện là 3).
- **`gpt-5-nano` là model reasoning:** token suy luận ẩn được tính tiền như
  output. Pipeline không đặt `reasoning_effort` hay `max_tokens`, nên nên chạy
  thử khoảng 50 chunk rồi đọc số token thật trong `request_usage_lines`. Nếu
  cần chi phí ổn định, `gpt-4.1-nano` chỉ đắt hơn một chút ($4,82 cho mức
  ≤ 100 trang).
- **Model embedding phải trùng** với model đang đăng ký cho chat. Nếu hệ thống
  đang dùng `gemini-embedding-001`, bước 4 đắt gấp 7,5 lần (≈ $2,30 thay vì
  $0,31 ở mức ≤ 100 trang).
- **Mỗi lần đánh giá:** giám khảo khoảng $0,11–0,17 (dưới ngưỡng 1 USD); chạy
  graph UniSage tốn thêm khoảng $0,6–0,9 (§3).

**Cần chốt ở Checkpoint 1 (§8):** tập tài liệu (mức trang), model, có OCR
`scanned_pdf/` không, nơi lưu `files/`.

## 1. Kết quả crawl (Phase 0)

| Chỉ số | Giá trị |
|---|---|
| Site đã duyệt | 42 (39 truy cập được) |
| Link PDF tìm thấy | 2.300 |
| PDF tải về (sau khi bỏ trùng) | **1.945** file · 27.491 trang · **3,6 GB** |
| PDF có text | 1.298 file · 20.762 trang · 33,7 triệu ký tự |
| PDF scan (< 100 ký tự/trang) | 631 file · 6.729 trang, đã chuyển sang `scanned_pdf/` |
| PDF có lớp text hỏng (xem §5.6) | 187 file (`garbled_ocr` 167, `no_diacritics` 14, `broken_encoding` 6), đã chuyển sang `scanned_pdf/` |
| **PDF sạch còn lại trong `files/`** | **1.111 file · 32,1 triệu ký tự · 1,0 GB** (`quality=ok`) |
| PDF không đọc được | 16 (có mật khẩu, đã xóa, ghi `unreadable` vào `download_errors.csv`) |
| Lỗi tải | 200 trùng nội dung · 57 lỗi kết nối · 49 file > 30 MB · 32 lỗi 404 · 12 không phải PDF · 5 khác |
| File tải qua host có chuỗi TLS thiếu | 965 (đã ghi ở cột `tls_verified=false`) |

Đơn vị có nhiều file nhất: Phòng Khảo thí và ĐBCL (248), Viện ĐTQT và SĐH
(158), Khoa Công nghệ Hóa học (112), Viện Tài chính - Kế toán (106), Khoa Cơ
khí (104). Hai cơ sở tỉnh có dữ liệu: Phân hiệu Quảng Ngãi (71) và Cơ sở Thanh
Hóa (57).

## 2. Đo trên pipeline thật

Lấy 59 PDF có text, chia đều theo kích thước, rồi chạy qua
`app.rag.chunking.strategy.dispatch` với chiến lược `markdown_aware`. Bước này
không gọi LLM. Token được đếm bằng `o200k_base`; với Gemini, sai số có thể
khoảng ±30%.

| Chỉ số | Giá trị |
|---|---|
| Chunk / 1.000 ký tự | **2,01** |
| Token trung bình / chunk | **279** |
| Chunk ước tính nếu nạp toàn bộ PDF có text | **~67.700** |
| Prompt sinh câu trả lời (`advisory_prompt_snapshot.txt`) | 10.182 token |
| Prompt phân loại / HyDE | 2.706 / 728 token |

Mỗi chunk tốn **1 lời gọi LLM** (`MultiRepresentationEnricher` sinh tóm tắt và
3 câu hỏi) cộng **3 lần embed** (nội dung, tóm tắt, câu hỏi).

## 3. Chi phí nếu nạp toàn bộ 1.298 PDF có text

| Model chat · embedding | Nạp (một lần) | trong đó LLM enrich | trong đó embed | Sinh câu hỏi (một lần) | Mỗi lần đánh giá: graph | Mỗi lần đánh giá: **giám khảo** |
|---|---|---|---|---|---|---|
| `gemini-2.5-flash-lite` · `gemini-embedding-001` (0,10/0,40 · 0,15) | **$13,22** | $8,76 | $4,46 | $0,07 | $0,60 | **$0,11** |
| `gemini-2.5-flash` · `gemini-embedding-001` (0,30/2,50 · 0,15) | $50,09 | $45,64 | $4,46 | $0,31 | $2,11 | $0,40 |
| `gpt-4o-mini` · `text-embedding-3-small` (0,15/0,60 · 0,02) | $13,74 | $13,14 | $0,59 | $0,10 | $0,88 | $0,17 |
| `gpt-4.1-nano` · `text-embedding-3-small` (0,10/0,40 · 0,02) | **$9,36** | $8,76 | $0,59 | $0,07 | $0,59 | **$0,11** |

- Với mọi model rẻ, chi phí **giám khảo mỗi lần đánh giá dưới 0,20 USD**, thấp
  hơn nhiều so với ngưỡng 1 USD.
- **Cộng cả chi phí chạy graph** (model chat của UniSage), mỗi lần đánh giá tốn
  khoảng 0,7–1,05 USD với model rẻ. Theo spec, phần graph không tính vào ngưỡng
  1 USD, nhưng vẫn được ghi trong report.
- **Khoản tốn nhất là nạp tài liệu, và phần lớn nằm ở LLM enrich.** Nạp toàn bộ
  cần khoảng 67.700 lời gọi LLM, vừa tốn tiền vừa mất nhiều giờ vì giới hạn
  rate.
- Mỗi lần ablation tốn thêm khoảng một lần chạy graph (0,6–0,9 USD). Không cần
  gọi giám khảo nếu chỉ so Recall@k.

Giả định cho những phần không đo được: output enrich 220 token, câu trả lời 450
token, HyDE 300 token, giám khảo 3.200 token vào và 150 token ra, sinh câu hỏi
110 lời gọi × (3.500 vào + 700 ra), 300 câu hỏi, 8 chunk truy xuất mỗi câu.

## 4. Chi phí từng bước khi nạp vào Qdrant, dùng model rẻ nhất

**Model rẻ nhất** trong bảng giá, chỉ xét 2 provider hệ thống hỗ trợ là
`openai` và `google`:

| Vai trò | Model | Giá / 1M token | Ghi chú |
|---|---|---|---|
| LLM enrich | **`gpt-5-nano`** | $0,05 vào · $0,40 ra | Là model reasoning: token suy luận ẩn được tính như output, xem dòng "biên trên" bên dưới |
| LLM enrich (dự phòng) | `gpt-4.1-nano` | $0,10 vào · $0,40 ra | Không reasoning, chi phí ổn định hơn. `gemini-2.5-flash-lite` cùng giá |
| Embedding | **`text-embedding-3-small`** | $0,02 | Ra đúng 1.536 chiều như collection đang cấu hình (`_EMBEDDING_DIMENSIONS`). `gemini-embedding-001` đắt gấp 7,5 lần |

Không chọn các model giá $0 trong bảng (họ Gemma): đó là free tier, rate limit
thấp và dữ liệu có thể bị dùng để huấn luyện.

**Các bước của pipeline cho mỗi file** (`/ingestion/chunking` rồi `embed_chunks`):

| # | Bước | Chạy ở đâu | Chi phí | Khối lượng mỗi chunk |
|---|---|---|---|---|
| 1 | Parse PDF (`pymupdf4llm`, tự OCR Tesseract khi trang không có text) | CPU local | $0 | — |
| 2 | Chunk `markdown_aware` (Recursive 800 ký tự, chồng lấp 120) | CPU local | $0 | ~279 token |
| 3 | **Enrich** (`MultiRepresentationEnricher`): 1 lời gọi LLM, sinh tóm tắt và 3 câu hỏi | API LLM | **có** | vào ~415 token (prompt 136 + chunk 279), ra ~220 token |
| 4 | **Embed** 3 vector (nội dung, tóm tắt, câu hỏi) | API embedding | **có** | ~439 token |
| 5 | Upsert vào Qdrant (3 vector 1.536 chiều + payload) | Qdrant local | $0 | ~20 KB lưu trữ |

**Ba mức giới hạn trang** (chỉ tính file `quality=ok` trong `files/`):

| | **Tất cả file sạch** | **≤ 100 trang/file** | **≤ 50 trang/file** |
|---|---|---|---|
| Số file | 1.111 | 1.085 | 1.034 |
| Số chunk (= số point Qdrant) | ~64.600 | ~34.800 | ~25.400 |
| Bước 1–2: parse + chunk | $0 | $0 | $0 |
| Bước 3: enrich, token vào / ra | 26,8M / 14,2M | 14,5M / 7,7M | 10,5M / 5,6M |
| Bước 3: enrich với `gpt-5-nano` | $7,02 | $3,79 | $2,76 |
| Bước 3: biên trên nếu thêm ~300 token suy luận mỗi lời gọi | $7,75 | $4,18 | $3,04 |
| Bước 4: embed, số token | 28,3M | 15,3M | 11,1M |
| Bước 4: embed với `text-embedding-3-small` | $0,57 | $0,31 | $0,22 |
| Bước 5: upsert Qdrant | $0 (~1,3 GB) | $0 (~0,7 GB) | $0 (~0,5 GB) |
| **Tổng với `gpt-5-nano`** | **$7,59** (tối đa $8,32) | **$4,09** (tối đa $4,49) | **$2,98** (tối đa $3,26) |
| Tổng nếu dùng `gpt-4.1-nano` thay cho bước 3 | $8,93 | $4,82 | $3,51 |

**Nhận xét:**
- **Khoảng 93% chi phí nằm ở bước 3 (enrich).** Embedding chỉ chiếm khoảng
  7%. Muốn giảm tiếp thì phải bớt số chunk, hoặc giảm
  `INGEST_MULTI_REP_QUESTION_COUNT` (hiện là 3), không phải đổi model
  embedding.
- **Output đắt hơn input nhiều:** gấp 8 lần với `gpt-5-nano`, gấp 4 lần với
  `gpt-4.1-nano`. Vì vậy khoảng 220 token output mỗi lần enrich là phần chi
  phối chi phí (khoảng 80% chi phí bước 3 với `gpt-5-nano`). Với `gpt-5-nano`, giá thực tế
  phụ thuộc vào lượng token suy luận ẩn. Pipeline hiện không đặt
  `reasoning_effort` hay `max_tokens` (`multi_representation.py`), nên nên
  chạy thử khoảng 50 chunk rồi đọc số token thật trong `request_usage_lines`
  trước khi nạp hàng loạt.
- **Model embedding phải trùng** với model embedding đang đăng ký cho chat. Nếu
  hiện dùng `gemini-embedding-001` thì bước 4 đắt gấp 7,5 lần (ví dụ $2,30 thay
  vì $0,31 với mức ≤ 100 trang), và phải cấu hình ra 1.536 chiều.
- Bước 1 chạy OCR trên CPU: miễn phí nhưng chậm, chỉ đáng kể nếu sau này nạp
  thêm `scanned_pdf/`.

## 5. Phát hiện ảnh hưởng tới plan

1. **Tài liệu lớn chiếm phần lớn khối lượng.** Chỉ 26 file dài trên 100 trang
   nhưng chiếm **44% số ký tự**. Đó là giáo trình và sách, ví dụ Turban
   *Electronic Commerce* (820 trang) và *Top-down Network Design*, cùng luận án
   tiến sĩ, sách tiếng Anh phổ thông, phụ lục 1.186 trang. Các file này không
   phải tài liệu học vụ; sách còn có bản quyền của nhà xuất bản. Chúng làm tăng
   chi phí mà không giúp đánh giá.
2. **Parser production có OCR.** `pymupdf4llm` tự chạy Tesseract (đã cài gói
   tiếng Việt `vie`) trên trang không có text. Vì vậy 631 PDF scan vẫn nạp
   được, không cần loại bỏ, nhưng OCR chạy bằng CPU rất chậm. Nếu nạp thêm cả
   6.729 trang scan, số chunk tăng khoảng 30%.
3. **"Rerank" hiện chưa phải cross-encoder thật.** `cross_encoder.py` chỉ sắp
   xếp theo điểm tương đồng và lọc theo ngưỡng. Vì vậy ablation `no-rerank`
   (Task 13) thực chất là "tắt ngưỡng lọc". Report phải ghi rõ điều này.
4. **Crawl còn thiếu một phần.** 23 site chạm giới hạn 500 trang, nên còn PDF
   chưa được tìm tới. Ngoài ra còn 825 file Word/Excel và 609
   link Google Drive nằm ngoài phạm vi (chỉ tải PDF trên `*.iuh.edu.vn`). Với
   các con số ở trên thì dữ liệu hiện có đã quá đủ, nên **không đề xuất crawl
   thêm**.
5. **Tài liệu có bản quyền.** Nếu upload thư mục `files/` lên Hugging Face thì
   repo phải để private, và nên loại sách giáo trình ra khỏi tập tải lên.
6. **Lớp text hỏng trong PDF "có text".** Nhiều PDF scan đã được phần mềm
   máy scan OCR sẵn một lớp text rất kém, ví dụ "T6ng C6ng ty" thay cho "Tổng
   Công ty". Parser production thấy có text nên không OCR lại, và sẽ embed
   nguyên phần rác đó. `evals.crawl.triage` phát hiện 3 kiểu lỗi (cột
   `quality`) và chuyển các file này sang `scanned_pdf/`. Đây là bộ lọc
   heuristic: kiểm tra mẫu ngẫu nhiên cho thấy 10/10 file `garbled_ocr` đúng
   sau khi siết luật; lần đầu có 2/5 bắt nhầm và đã được chuyển trả về
   `files/`. Nhóm `no_diacritics` có file song ngữ (nửa tiếng Anh chuẩn, nửa
   tiếng Việt mất dấu), vẫn được xếp vào nhóm này.

## 6. Lựa chọn tập tài liệu để nạp (`selected=true`)

Chi phí nạp được tính theo tỷ lệ số ký tự so với toàn bộ PDF có text.

| Phương án | Quy tắc chọn | File | Chunk ~ | Nạp với flash-lite / 4.1-nano / 4o-mini |
|---|---|---|---|---|
| A. Toàn bộ file sạch | `quality=ok` | 1.111 | 64.600 | $12,6 / $8,9 / $13,1 |
| **B. Bỏ tài liệu lớn** (đề xuất) | `quality=ok`, ≤ 100 trang | 1.085 | 34.800 | **$6,8 / $4,8 / $7,1** |
| C. Chỉ tài liệu ngắn | `quality=ok`, ≤ 50 trang | 1.034 | 25.400 | $5,0 / $3,5 / $5,2 |
| D. Tập cân bằng | ≤ 50 trang, tối đa ~15 file/đơn vị, ưu tiên quy chế, quy định, thông báo, CTĐT | ~400 | ~9.000 | ~$1,8 / $1,3 / $1,9 |

(Tính lại sau khi lọc chất lượng ở §5.6. Các file bị loại chủ yếu là thông báo ngắn, nên chi phí chỉ giảm khoảng 5%.)

**Đề xuất: phương án B.** Phương án này giữ gần như toàn bộ tài liệu học vụ và
hành chính, đủ nhiều để có tài liệu gây nhiễu thực tế khi truy xuất. Nó chỉ bỏ
26 file sách và luận án, và giảm chi phí khoảng 44%. Nếu ngân sách nạp phải
dưới 2 USD thì chọn D. Có thể nạp thêm PDF scan qua OCR sau, như một lựa chọn
riêng.

## 7. Tổng chi phí theo phương án B, model `gemini-2.5-flash-lite` hoặc `gpt-4.1-nano`

| Khoản | Tần suất | USD |
|---|---|---|
| Nạp tài liệu | một lần | 4,8 – 6,8 |
| Sinh ~300 câu hỏi | một lần | ~0,07 |
| Giám khảo | mỗi lần đánh giá | ~0,11 (≤ 1 ✅) |
| Graph UniSage | mỗi lần đánh giá | ~0,6 |
| Ablation (3 chế độ, chỉ graph) | khi cần | ~1,8 |
| **Tổng cho đồ án** (nạp + sinh câu hỏi + khoảng 5 lần đánh giá + 1 lượt ablation) | | **~11 – 13** |

## 8. Cần người dùng quyết định (Checkpoint 1)

1. Chọn phương án tập tài liệu: A / **B** / C / D.
2. Chọn model chat cho nạp tài liệu và giám khảo (Gemini hay OpenAI), và model
   embedding. Rẻ nhất là `gpt-5-nano` + `text-embedding-3-small` (§4). Model embedding **phải trùng** với model đang đăng ký cho chat,
   vì câu hỏi và chunk phải nằm cùng không gian vector.
3. Có nạp thêm 631 PDF scan qua OCR không (chậm, tốn thêm khoảng 30% chi phí
   nạp)?
4. Chỗ lưu thư mục `files/` (3,6 GB): đề xuất Hugging Face private, còn CSV
   giữ trong git.
