# Implementation Plan: Đánh giá chất lượng trả lời UniSage

## Overview

Xây bộ đánh giá RAG cho `unisage-agent` theo `spec.md`: crawl PDF công khai của
IUH vào `unisage-gateway/dataset/`, gán nhãn phân quyền giả lập, nạp vào hệ
thống, sinh ~300 câu hỏi, rồi chạy graph in-process để đo 8 tiêu chí mỗi lần
(cộng ablation và kiểm chứng giám khảo) với chi phí giám khảo ≤ 1 USD/lần.

**Cập nhật 10-10-2026:** bộ câu hỏi gộp với bộ demo thành 497 câu và sẽ bổ sung `calculation` /
`unanswerable`; tài liệu nạp qua luồng upload thật (lưu `documents`, admin sửa được, chip citation
mở được file, tiêu đề đọc được); số liệu báo cáo kèm n và khoảng tin cậy.

## Architecture Decisions

- **Nạp qua luồng upload thật, chạy đánh giá in-process.** Ingest giống wizard của web
  (`POST /documents` → MinIO → `/ingestion/*` của agent → Celery → Qdrant) để tài liệu có bản ghi
  `documents`, admin sửa được và chip citation mở được file. Khi *đánh giá* vẫn chạy graph
  in-process vì SSE chỉ trả text; intent, chunk truy xuất và citations chỉ có trong process.
  Đánh đổi: latency không gồm gateway/Java.
- **Tiêu đề đọc được có trước khi upload.** Chip lấy tên từ `object_key` (tên file upload), khung
  xem và admin lấy `documents.title`; cột `title` trong manifest cấp cho cả hai.
- **Môi trường đánh giá sạch.** DB + bucket + collection trống, chỉ chứa bộ `official`, chọn qua env.
- **Code ở `unisage-agent/evals/`, dữ liệu ở `unisage-gateway/dataset/`.** Code
  cần import `app.graph`; dữ liệu đặt ở gateway theo yêu cầu. Đường dẫn dataset
  truyền qua `--dataset`, không hard-code.
- **Recall và citation so ở mức tài liệu.** Chunk id đổi mỗi lần ingest lại,
  `file_id`/`document_id` thì không. Câu hỏi giữ `file_id`; runner đổi sang `document_id`
  (UUID backend) qua manifest.
- **Báo cáo có khoảng tin cậy.** Mỗi tỷ lệ kèm n và Wilson 95%; so sánh cấu hình theo cặp
  (McNemar / bootstrap theo cặp).
- **Leakage tính deterministic.** Áp lại đúng quy tắc hiển thị của
  `qdrant_store.py` lên metadata chunk trả về, không nhờ LLM.
- **Chấm bằng RAGAS (đổi 10-10-2026).** `Faithfulness`, `FactualCorrectness`, `LLMContextRecall`,
  `ResponseRelevancy`, `AspectCritic` (từ chối) cho câu trả lời; `IDBasedContextPrecision/Recall`
  cho truy xuất mức tài liệu. Prompt RAGAS chuyển sang tiếng Việt một lần, lưu cache. Ước tính chi phí
  chặn trước khi chạy (giám khảo `gemini-3.1-flash-lite-preview`, fallback `gemini-3.5-flash-lite`; ~2,4–2,9 USD và ~10–20 phút mỗi lần, trần đề xuất 4 USD).
- **Môi trường eval tách bằng env.** DB, bucket, collection, Redis db có hậu tố `eval`, chọn bằng file
  env riêng; script đánh giá từ chối chạy khi tên tài nguyên không chứa `eval` (`spec.md` §12.7).
- **CSV/JSONL làm nguồn sự thật**, không dùng DB hay Google Sheet.

## Dependency Graph

```
Crawl discover → duyệt sources → download toàn bộ + manifest
                                            │
                                            ▼
                         dự trù chi phí + chỉnh plan  ── người dùng duyệt
                                            │
                                            ▼
            spike upload + ingest 1 PDF (luồng thật) → spike run in-process
                                            │
                   ┌────────────────────────┼─────────────────────┐
                   ▼                        ▼                     ▼
          label + tiêu đề (7a)    citation dùng title (7b)   chặn xem file private (7c)
                   └────────────────────────┼─────────────────────┘
                                            ▼
                                 ingest hàng loạt (selected)
                                            │
                           ┌────────────────┤
                           ▼                ▼
          gộp + bổ sung câu hỏi (8, 8b)   runner + metrics deterministic
                           │                │
                           └──────┬─────────┘
                                  ▼
                      judge + budget guard → report (kèm CI)
                                  │
                      ┌───────────┴───────────┐
                      ▼                       ▼
                  ablation            kiểm chứng giám khảo
```

Crawl làm đầu tiên vì con số thực (số file, số trang, tỷ lệ PDF scan) quyết
định chi phí ingest và có thể làm đổi phạm vi các task sau. Không viết code
ngoài crawl trước khi người dùng duyệt `cost-estimate.md`.

## Task List

Chi tiết từng task: `todo.md`.

### Phase 0: Crawl toàn bộ PDF
- [ ] Task 1: Crawl discover → `sources.csv`
- [ ] Task 2: Download toàn bộ + `manifest.csv` + gitignore

### Checkpoint 0
- [ ] Toàn bộ PDF đã tải, manifest đủ cột, PDF không lọt vào git

### Phase 1: Dự trù chi phí
- [ ] Task 3: Dự trù chi phí + chỉnh spec/plan/todo → `cost-estimate.md`

### Checkpoint 1
- [ ] Người dùng duyệt chi phí, plan đã chỉnh và tập tài liệu `selected`

### Phase 2: Spike
- [ ] Task 4: Upload + ingest 1 PDF qua luồng thật (backend → MinIO → agent → Qdrant)
- [ ] Task 5: Chạy `run_graph()` in-process với wrapper ghi lại

### Checkpoint 2
- [ ] Đã biết chắc cách ingest và cách lấy intent/chunk/citation

### Phase 2b: Môi trường
- [ ] Task 4a: Tách dev / eval / prod bằng `env=<env>` (agent) và `APP_PROFILE` (backend); `task db:up` dựng hạ tầng (gần xong 10-10-2026)

### Phase 2c: Chạy trước trên bộ demo
- [ ] Task 4b: `evals/ingest.py` (xong code + test, chờ chạy thật) + bộ câu hỏi demo mở rộng

### Phase 3: Nhãn và ingest
- [ ] Task 6: Gán nhãn phân quyền
- [x] Task 7a: Tiêu đề đọc được (`title`, `title_source`) cho manifest; ingest upload dưới tên `<title>.pdf` để chip và khung xem hiện tên file, không hiện `file_id` / `document_id`
- [ ] Task 7b: Chip citation hiện tiêu đề tài liệu
- [ ] Task 7c: Chặn xem file private qua `GET /documents/{id}/citation`
- [ ] Task 7: Ingest hàng loạt các tài liệu `selected` qua luồng upload thật

### Checkpoint 3
- [ ] Ingest xong, tài liệu hiện ở trang admin, chip mở được file với tiêu đề đọc được, nhãn hợp lệ

### Phase 4: Bộ câu hỏi
- [ ] Task 8: Sinh câu hỏi theo 5 nhóm (xong) + gộp bộ demo → 497 câu (xong 10-10-2026)
- [ ] Task 8b: Bổ sung `calculation` và `unanswerable` lên 80–100 câu mỗi nhóm
- [ ] Task 9: Duyệt tay ≥ 20% và sinh persona cho nhóm `access`

### Phase 5: Runner
- [ ] Task 10: Runner + metrics deterministic (#1, #5, #6, #7, #8)
- [ ] Task 10b: Chế độ `web=on` + chỉ số nhánh web (#13–#15), cache web để chạy lại được
- [ ] Task 11: Chấm bằng RAGAS + chặn ngân sách (#2–#4)
- [ ] Task 12: `report.md` (mỗi tỷ lệ kèm n + khoảng tin cậy 95%)

### Checkpoint 4
- [ ] Một lệnh chạy trọn bộ, report đủ #1–#8, #11, #12, chi phí RAGAS ≤ trần đã duyệt

### Phase 6: Phục vụ báo cáo
- [ ] Task 13: Ablation qua env `CHAT_ALLOW_*` (#9)
- [ ] Task 14: Kiểm chứng giám khảo bằng người (#10)

### Checkpoint 5
- [ ] Toàn bộ tiêu chí hoàn thành trong `spec.md` §11 đạt

## Parallelization

- Sau Checkpoint 2: phần metrics thuần của Task 10 (test với dữ liệu giả) làm
  song song với Task 6–7.
- Task 7a, 7b, 7c độc lập nhau, làm song song sau Checkpoint 2.
- Task 8b và Task 10 song song sau Task 7.
- Task 13 và Task 14 độc lập nhau.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Luồng upload thật cần backend + MinIO + Celery worker cùng chạy, ingest ~1.085 file chậm | Med | Task 4 chạy thử 1 file; `evals.ingest` tiếp tục được (bỏ dòng `done`), giới hạn concurrency |
| Tài liệu private tải được qua `/documents/{id}/citation` | ~~High~~ | **Đã sửa ở Task 7c** (10-10-2026); tiêu chí #12 tiếp tục đo |
| Tiêu đề lấy từ trang 1 sai hoặc vô nghĩa | Med | `title_source=heading` để người duyệt; sửa tay → `manual`, không bị ghi đè |
| Admin sửa `documents.title` nhưng chip vẫn hiện tên cũ trong Qdrant payload | Low | Task 7b ghi rõ giới hạn; tên chip đồng bộ lại khi ingest lại |
| Gộp bộ demo dùng nhãn quyền mới cho 46 tài liệu | Med | Bắt buộc ingest lại theo manifest hiện tại; script gộp kiểm tra nhãn câu hỏi khớp manifest |
| Chi phí ingest toàn bộ PDF quá cao (embedding + multi-rep LLM mỗi chunk) | High | Đã đo ở Task 3: toàn bộ ≈ 9–13 USD, ~67.700 chunk; đề xuất chỉ nạp PDF có text ≤ 100 trang (5–7 USD) |
| Ingest ~38.000 lời gọi LLM mất nhiều giờ, dễ chạm rate limit | Med | Chạy nền, có thể tiếp tục (bỏ qua dòng `done`); giới hạn concurrency |
| Tài liệu có bản quyền (giáo trình, sách) lọt vào dataset chia sẻ | Med | Loại file > 100 trang; nơi lưu `files/` phải private |
| Site IUH chặn crawl hoặc ít PDF | Med | Discover trước, người duyệt nguồn; rate limit; bổ sung tay nếu thiếu |
| PDF scan (ảnh) parse ra rỗng | Med | Đánh dấu `ingest_status=empty_text`, loại khỏi sinh câu hỏi |
| Câu hỏi LLM sinh quá dễ / trích nguyên văn | Med | Prompt yêu cầu diễn đạt lại; duyệt tay ≥ 20%; nhóm nhiều ý riêng |
| Tài liệu dev khác lẫn vào môi trường đánh giá làm nhiễu recall | Med | Chạy trên DB + bucket + collection trống; `evals.run` kiểm tra số document trong collection khớp số dòng `done` |
| Chi phí RAGAS (~7 lời gọi/câu) vượt trần | Med | Ước tính trước, dừng nếu vượt `--max-cost`; chỉ số truy xuất dùng bản ID-based không gọi LLM; ablation không chạy giám khảo |
| Prompt RAGAS tiếng Anh chấm câu tiếng Việt kém | Med | `adapt_prompts` sang tiếng Việt một lần, lưu cache; Task 14 đo đồng thuận với người |
| RAGAS đổi API giữa các phiên bản | Low | Ghim phiên bản trong nhóm dependency `eval`; bọc trong `evals/judge.py` |
| Chạy eval ghi nhầm vào DB/collection dev | Med | Tên tài nguyên eval có hậu tố `eval`; script kiểm tra trước khi chạy |
| Web search tìm lại đúng PDF "private" (nhãn giả lập, bản gốc công khai trên `iuh.edu.vn`) | Med | Số liệu chính chạy `web=off`; `web=on` báo riêng, lộ qua web (#15) tách khỏi leakage (#5) |
| Kết quả web đổi theo thời gian, Tavily tính tiền theo lượt | Low | Cache ghi/phát lại `web_cache.jsonl` |
| Giám khảo LLM thiên lệch | Med | Task 14 đo đồng thuận với người; công bố số này trong báo cáo |
| Cờ `CHAT_ALLOW_*` làm đổi hành vi production | Low | Mặc định `true`; test hiện có phải pass không đổi |
| Đơn vị crawl không khớp department nào trong DB | Med | Bảng map `unit → department_id` do người duyệt; đơn vị không map được thì gom vào department chung |

## Open Questions

Chờ Checkpoint 1 (`cost-estimate.md` §8): phương án tập tài liệu, model nạp/giám khảo/embedding, có OCR PDF scan không, nơi lưu `files/` (đề xuất Hugging Face private).


Đã chốt: department theo DB (#2), cờ `CHAT_ALLOW_*` cho ablation (#3). Đổi ngày 10-10-2026: nạp
qua luồng upload thật thay cho đường B (#1), bỏ collection riêng `unisage_eval`, dùng môi trường
dev trống (#4), sửa quyền xem file nguồn (#5) — xem `spec.md` §12.
