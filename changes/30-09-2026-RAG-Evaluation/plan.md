# Implementation Plan: Đánh giá chất lượng trả lời UniSage

## Overview

Xây bộ đánh giá RAG cho `unisage-agent` theo `spec.md`: crawl PDF công khai của
IUH vào `unisage-gateway/dataset/`, gán nhãn phân quyền giả lập, nạp vào hệ
thống, sinh ~300 câu hỏi, rồi chạy graph in-process để đo 8 tiêu chí mỗi lần
(cộng ablation và kiểm chứng giám khảo) với chi phí giám khảo ≤ 1 USD/lần.

## Architecture Decisions

- **Graph in-process, không qua HTTP.** SSE chỉ trả text; intent, chunk truy
  xuất và citations chỉ có trong process. Wrapper ghi lại
  (`RetrievalServiceProtocol`, `GraphTrace`, `token_sink`) lấy đủ dữ liệu mà
  không sửa `app/`. Đánh đổi: latency không gồm gateway/Java.
- **Code ở `unisage-agent/evals/`, dữ liệu ở `unisage-gateway/dataset/`.** Code
  cần import `app.graph`; dữ liệu đặt ở gateway theo yêu cầu. Đường dẫn dataset
  truyền qua `--dataset`, không hard-code.
- **Recall và citation so ở mức tài liệu.** Chunk id đổi mỗi lần ingest lại,
  `file_id`/`document_id` thì không.
- **Leakage tính deterministic.** Áp lại đúng quy tắc hiển thị của
  `qdrant_store.py` lên metadata chunk trả về, không nhờ LLM.
- **Một lời gọi giám khảo mỗi câu.** Chấm gộp faithfulness, correctness và
  refusal trong một JSON; có bước ước tính chi phí chặn trước khi chạy.
- **CSV/JSONL làm nguồn sự thật**, không dùng DB hay Google Sheet.

## Dependency Graph

```
Crawl discover → duyệt sources → download toàn bộ + manifest
                                            │
                                            ▼
                         dự trù chi phí + chỉnh plan  ── người dùng duyệt
                                            │
                                            ▼
                     spike ingest (đường B) → spike run in-process
                                            │
                                            ▼
                                 label → ingest (selected)
                                            │
                           ┌────────────────┤
                           ▼                ▼
                  generate questions   runner + metrics deterministic
                           │                │
                           └──────┬─────────┘
                                  ▼
                      judge + budget guard → report
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
- [ ] Task 4: Nạp 1 PDF vào `unisage_eval` bằng pipeline agent (đường B)
- [ ] Task 5: Chạy `run_graph()` in-process với wrapper ghi lại

### Checkpoint 2
- [ ] Đã biết chắc cách ingest và cách lấy intent/chunk/citation

### Phase 3: Nhãn và ingest
- [ ] Task 6: Gán nhãn phân quyền
- [ ] Task 7: Ingest hàng loạt các tài liệu `selected`

### Checkpoint 3
- [ ] Ingest xong vào `unisage_eval`, nhãn hợp lệ

### Phase 4: Bộ câu hỏi
- [ ] Task 8: Sinh câu hỏi theo 5 nhóm
- [ ] Task 9: Duyệt tay ≥ 20% và sinh persona cho nhóm `access`

### Phase 5: Runner
- [ ] Task 10: Runner + metrics deterministic (#1, #5, #6, #7, #8)
- [ ] Task 11: Giám khảo + chặn ngân sách (#2, #3, #4)
- [ ] Task 12: `report.md`

### Checkpoint 4
- [ ] Một lệnh chạy trọn bộ, report đủ #1–#8, chi phí giám khảo ≤ 1 USD

### Phase 6: Phục vụ báo cáo
- [ ] Task 13: Ablation qua env `CHAT_ALLOW_*` (#9)
- [ ] Task 14: Kiểm chứng giám khảo bằng người (#10)

### Checkpoint 5
- [ ] Toàn bộ tiêu chí hoàn thành trong `spec.md` §11 đạt

## Parallelization

- Sau Checkpoint 2: phần metrics thuần của Task 10 (test với dữ liệu giả) làm
  song song với Task 6–7.
- Task 8 và Task 10 song song sau Task 7.
- Task 13 và Task 14 độc lập nhau.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Pipeline agent cần object trên MinIO / draft chunking trước khi embed | Med | Task 4 xác định bước tối thiểu; nếu cần thì script tự upload MinIO + tạo draft |
| Chi phí ingest toàn bộ PDF quá cao (embedding + multi-rep LLM mỗi chunk) | High | Đã đo ở Task 3: toàn bộ ≈ 9–13 USD, ~67.700 chunk; đề xuất chỉ nạp PDF có text ≤ 100 trang (5–7 USD) |
| Ingest ~38.000 lời gọi LLM mất nhiều giờ, dễ chạm rate limit | Med | Chạy nền, có thể tiếp tục (bỏ qua dòng `done`); giới hạn concurrency |
| Tài liệu có bản quyền (giáo trình, sách) lọt vào dataset chia sẻ | Med | Loại file > 100 trang; nơi lưu `files/` phải private |
| Site IUH chặn crawl hoặc ít PDF | Med | Discover trước, người duyệt nguồn; rate limit; bổ sung tay nếu thiếu |
| PDF scan (ảnh) parse ra rỗng | Med | Đánh dấu `ingest_status=empty_text`, loại khỏi sinh câu hỏi |
| Câu hỏi LLM sinh quá dễ / trích nguyên văn | Med | Prompt yêu cầu diễn đạt lại; duyệt tay ≥ 20%; nhóm nhiều ý riêng |
| Tài liệu đánh giá lọt vào `unisage_chunks` (process nào đó quên đặt `QDRANT_COLLECTION`) | High | `evals` từ chối chạy nếu `settings.QDRANT_COLLECTION != "unisage_eval"`; Task 4 kiểm tra đếm point trước/sau |
| Chi phí giám khảo vượt 1 USD | Med | Ước tính trước, dừng nếu vượt; cắt context đưa vào giám khảo |
| Giám khảo LLM thiên lệch | Med | Task 14 đo đồng thuận với người; công bố số này trong báo cáo |
| Cờ `CHAT_ALLOW_*` làm đổi hành vi production | Low | Mặc định `true`; test hiện có phải pass không đổi |
| Đơn vị crawl không khớp department nào trong DB | Med | Bảng map `unit → department_id` do người duyệt; đơn vị không map được thì gom vào department chung |

## Open Questions

Chờ Checkpoint 1 (`cost-estimate.md` §8): phương án tập tài liệu, model nạp/giám khảo/embedding, có OCR PDF scan không, nơi lưu `files/` (đề xuất Hugging Face private).


Tất cả đã chốt: đường ingest B — gọi thẳng pipeline agent, in-process (#1), department theo DB (#2), cờ `CHAT_ALLOW_*` cho ablation (#3), collection riêng `unisage_eval` qua `QDRANT_COLLECTION` (#4).
