# Implementation Plan: Graph Flow Alignment

> **Repo đích:** `unisage-agent` (`D:\KLTN\Main\unisage-agent`). Mọi đường dẫn file trong plan này tính từ gốc repo đó. Plan được lưu ở `backend-java/changes` theo yêu cầu.

## Overview

Đưa graph của `unisage-agent` về đúng flow design trong `RAG_Graph/KLTN` (ảnh flow_design, 11 node), đúng cơ chế load và lắp prompt của design (loader, builder, thư mục `prompt_templates/agents/`), và thêm pre-filter phân quyền ở node 08.

Hiện trạng lệch so với design:

- Còn `DirectLLMNode` (design đã bỏ); `academic_calculation` đang bị đẩy sang luồng advisory; chưa có node 07.
- Node 03 và 06 viết cứng system prompt trong code thay vì load `agents/*.yaml`; node 03 chỉ trả 1 nhãn text, không có `routing_mode`.
- Node 06 chỉ có HyDE, thiếu Multi-query / Procedure / Document; Generation chỉ có khung `chat_academic_advisory`.
- Node 08 không lọc theo quyền: mọi người dùng, kể cả khách, đọc được mọi chunk.
- Node 11 trả câu tĩnh kèm `ui_buttons` (bị mất vì không có field chứa), trong khi node chỉ chạy **sau** retrieve + rerank, nên theo design phải gọi LLM với `chat_ticket_fallback.yaml`.
- Tên trace vẫn theo số node cũ (`05B_`, `10_`, `11_`, `12_`, `13_`).

## Scope đã chốt với người dùng

- **Trong phạm vi:** tất cả mục ở trên, gồm pre-filter phân quyền.
- **Ngoài phạm vi (ghi known-gaps):** BM25/sparse search, RRF, cross-encoder rerank, nén ngữ cảnh, logic tính toán thật của CalculationNode, resume MULTI chỉ chạy lại đúng sub-query còn thiếu.
- **Xoá hẳn `ui_buttons`:** frontend đã có nút để người dùng chủ động tạo ticket.

## Ground truth

- **Design là chuẩn cho:** cấu trúc graph (node, cạnh, số thứ tự), cấu trúc load prompt (loader → `PromptTemplates` → builder), `agents/*.yaml`, khung `main/*.yaml` nào dùng cho nhánh nào.
- **Code là chuẩn cho:** nội dung các file `common/*.yaml` đang có trong code (mới hơn design). **Không sửa các file này**; nếu cần sửa phải hỏi người dùng trước. Ngoại lệ duy nhất đã được duyệt: thuật ngữ phân quyền trong `security_access_control.yaml` (AD13).
- File chỉ có trong design (`agents/*`, `main/chat_multi_intent_synthesis.yaml`, `main/chat_ticket_fallback.yaml`, `common/ticket_fallback.yaml`) được copy sang code.

## Architecture Decisions

- **AD1. `agents/*.yaml` được load nguyên văn, KHÔNG `.format()`.** Các file này chứa JSON mẫu có dấu `{}` và không có placeholder, nên `.format()` sẽ ném `KeyError`. Loader dùng lại `_load_yaml_template`; node dùng thẳng làm `system_prompt` của `Agent`.
- **AD2. Node 03 trả JSON dạng text, node tự parse bằng Pydantic** (`IntentClassification`: `primary_intent`, `secondary_intents`, `confidence`, `routing_mode`), không dùng `output_type` của pydantic_ai. Lý do: đúng Output Contract trong YAML, và dùng lại được mock `make_sync_llm_model` (trả text). Parse lỗi hoặc nhãn lạ → fallback `academic_advisory` + `SINGLE`, giữ hành vi an toàn hiện tại.
- **AD3. Intent taxonomy còn 7 nhãn** theo `message_classification.yaml` đã cập nhật: `general_knowledge` → `off_topic`; `academic_comparison` → `academic_advisory` + `routing_mode = MULTI`.
- **AD4. Node 04 suy ra `mode` cho node 06:** `academic_procedure` → `PROCEDURE`, `academic_document` → `DOCUMENT`, `academic_advisory`/`academic_calendar` → `MULTI` nếu `routing_mode == "MULTI"`, ngược lại `SINGLE`.
- **AD5. PROCEDURE/DOCUMENT dùng lại `hyde_generator.yaml`** (đã chốt). Node 06 thêm 1 dòng chỉ dẫn theo mode vào input gửi LLM; thư mục `agents/` giữ đúng 5 file như design.
- **AD6. Node 06 trả `transformed_queries: list[str]`** cho mọi mode. Node 08 retrieve từng query, gộp theo `chunk_id`, giữ điểm cao nhất (cùng cách `search_chunks` đang gộp 3 named vector), cắt `RETRIEVAL_MAX_CHUNKS`. **Không dùng RRF** (xem known-gaps).
- **AD7. Pre-filter phân quyền (đã chốt)** áp cho **mọi** truy vấn Qdrant ở node 08, kể cả từng sub-query MULTI. Một chunk được xem khi:
  - `access_level == 0` (thuộc department nào cũng được), **hoặc**
  - `department` nằm trong `department_access` của người hỏi **và** `access_level` của chunk ≤ `access_level` được cấp cho đúng department đó.

  Khách vãng lai (`department_access = []`) chỉ rơi vào vế đầu. Filter được dựng **chỉ** từ `AcademicSecurityContext`, tuyệt đối không đọc `confirmed_metadata` (design node 08). Dạng Qdrant: `Filter(should=[access_level == 0, Filter(must=[department == d, access_level <= lvl]) cho mỗi entry])`.
- **AD8. Khung Generation chọn theo nhánh:** `SINGLE`/`PROCEDURE`/`DOCUMENT` → `chat_academic_advisory` (giữ nguyên); `MULTI` → `chat_multi_intent_synthesis` (+ `{sub_queries_list}`). Builder gom phần chung vào một `_base_params(...)` rồi `.format(**base_params)` theo từng khung, đúng Pha 2 của `detail_prompt_template_loader.md`.
- **AD9. Các khung `main/` copy từ design được thêm `{history_message}`** cho khớp `chat_academic_advisory.yaml` hiện có trong code. Không đụng `common/`.
- **AD10. Node 11 stream câu trả lời bằng LLM** (`chat_ticket_fallback` + `common/ticket_fallback`), không truyền chunk nào vào prompt. **Xoá `ui_buttons`** và `TicketFallbackResponse`; giữ cờ `used_ticket_fallback`.
- **AD11. Node 07 là placeholder:** route `academic_calculation` → `CalculationNode`, trả message tĩnh "đang phát triển", không gọi LLM hay tool. `calculation_extractor.yaml` vẫn được copy + load (để `agents/` đủ như design) nhưng chưa node nào dùng.
- **AD12. `reranker_compressor.yaml` được copy + load nhưng chưa dùng**, vì node 09 chưa có bước nén (xem known-gaps).
- **AD13. Phân quyền dùng mảng `department_access` (đã chốt).** `common/security_access_control.yaml` đang nói theo mô hình cũ `max_access_level` / `organization_scopes`, không khớp `<academic_user_context>` thật (chỉ có `department_access: [{department_id, access_level}]`). Người dùng đã duyệt sửa đúng các chỗ thuật ngữ này sang `department_access` (kèm quy tắc tài liệu `access_level = 0` là công khai), không đổi phần nội dung khác. Sau khi pre-filter chạy, bỏ `_SECURITY_ACCESS_CONTROL_DEFERRED` và truyền `templates.security_access_control` vào mọi khung prompt.

## Task List

### Phase 1: Foundation

- [ ] Task 1: Loader + schema load `agents/` và các khung còn thiếu
- [ ] Task 2: Bỏ DirectLLMNode
- [ ] Task 3: Đổi tên trace theo số node mới

### Checkpoint: Foundation

- [ ] Toàn bộ test/ruff/mypy pass; luồng advisory chạy như cũ

### Phase 2: Phân quyền + Classification + Routing

- [ ] Task 4: Pre-filter phân quyền ở node 08
- [ ] Task 5: `security_access_control` theo `department_access` và bật trong prompt
- [ ] Task 6: Node 03 dùng `agents/message_classification.yaml`, output JSON 7 intent
- [ ] Task 7: Node 04 routing 4 đích + suy ra `mode`
- [ ] Task 8: Node 07 CalculationNode placeholder

### Checkpoint: Routing + Security

- [ ] Mỗi intent đi đúng nhánh trong ảnh flow_design; khách không đọc được chunk `access_level > 0`; review với người dùng

### Phase 3: Query Transformation + Fallback

- [ ] Task 9: Node 06 HyDE/Procedure/Document load từ YAML, trả `transformed_queries`
- [ ] Task 10: Node 06 Multi-query + node 08 fan-out + khung `chat_multi_intent_synthesis`
- [ ] Task 11: Node 11 TicketFallback gọi LLM, xoá `ui_buttons`

### Checkpoint: Complete

- [ ] E2E advisory / multi / calculation / fallback / guest pass
- [ ] Task 12: Ghi known-gaps

## Draft nội dung known-gaps (Task 12)

Ghi vào `docs/specs/known-gaps.md`: đổi tiêu đề "Known Gaps: Ingestion Service" thành "Known Gaps", nhóm các mục hiện có dưới `## Ingestion` (các mục con hạ xuống `###`), thêm `## Graph / Retrieval`:

> ### Không làm hybrid search, RRF, cross-encoder rerank (2026-09)
>
> Design (`RAG_Graph/KLTN/nodes/08`, `09`) mô tả node 08 = pre-filter + dense + BM25 + RRF, node 09 = cross-encoder `bge-reranker-base` + ngưỡng 0.70 + nén ngữ cảnh. Code làm pre-filter + dense search trên 3 named vector (content/summary/questions, giữ điểm cao nhất mỗi chunk) + lọc ngưỡng trên điểm cosine. Đây là quyết định có chủ đích, không phải bỏ sót:
>
> - **BM25 / sparse search:** lợi ích chính là khớp chính xác mã/số hiệu (`QĐ-45/2023`, `INT1001`), trong khi câu hỏi sinh viên chủ yếu hỏi theo nghĩa. Chi phí chạy gần 0 nhưng cần thêm sparse vector vào collection Qdrant, tức sửa ingestion và **re-index toàn bộ tài liệu**. Chưa có số liệu cho thấy dense search trượt loại câu hỏi này, nên chưa đáng chi phí đó.
> - **RRF:** điểm RRF tính theo thứ hạng (`1/(60 + rank)`, cỡ 0.01-0.03), không cùng thang với ngưỡng 0.70. Nếu đổi node 08 sang RRF khi chưa có cross-encoder chấm lại ở node 09, mọi chunk đều dưới ngưỡng và mọi câu hỏi rơi vào TicketFallback. RRF chỉ có nghĩa khi đi cùng rerank.
> - **Cross-encoder rerank:** phần tốn nhất (phí API mỗi câu hỏi, hoặc RAM/CPU/GPU để self-host). Lợi ích bị giảm vì (1) vector `questions` sinh sẵn đã khớp sát câu hỏi sinh viên, (2) mỗi lượt chỉ lấy 8 chunk. Ngoài ra `bge-reranker-base` trong design yếu với tiếng Việt; nếu làm nên dùng `bge-reranker-v2-m3` (self-host qua HuggingFace TEI) hoặc API đa ngôn ngữ (Cohere `rerank-v3.5`, Jina, Voyage).
> - **Nén ngữ cảnh (`agents/reranker_compressor.yaml`):** file đã được load nhưng không node nào dùng, vì chỉ có ý nghĩa sau khi có rerank.
>
> **Rủi ro còn lại:** `RERANK_SCORE_THRESHOLD = 0.70` đang áp lên điểm cosine của `text-embedding-3-small`, trong khi design đặt ngưỡng này cho điểm cross-encoder. Cặp câu liên quan có thể có cosine dưới 0.70, dẫn tới TicketFallback nhầm (hoặc ngược lại). Cần đo trên bộ 30-50 câu hỏi thật có nhãn chunk đúng để chỉnh ngưỡng.
>
> **Khi nào làm lại:** bộ đánh giá cho thấy trượt câu hỏi có mã/số hiệu → thêm BM25; chunk đúng lấy được nhưng xếp sai, hoặc ngưỡng cosine không tách được đúng/sai → thêm rerank (khi đó mới thêm RRF).

Các mục ngắn khác cùng section:

- **CalculationNode là placeholder:** chưa có extractor, Calculator Tool, nguồn dữ liệu điểm/học phí.
- **Resume clarification trong luồng MULTI** chạy lại toàn bộ câu hỏi gốc, chưa dùng `pending_sub_query_id` để chỉ chạy lại sub-query còn thiếu.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Pre-filter làm người dùng mất chunk mà trước đây vẫn thấy → nhiều câu rơi vào TicketFallback | High | Test theo từng vai trò (khách, 1 department, nhiều department); kiểm tra dữ liệu Qdrant hiện có đã gắn `department`/`access_level` dạng int đúng chưa trước khi bật |
| Filter dựng sai (vd `should` rỗng khi `department_access = []`) làm lọt quyền hoặc chặn hết | High | Vế `access_level == 0` luôn có mặt nên `should` không bao giờ rỗng; unit test riêng cho hàm dựng filter |
| Node 03 phân loại sai sau khi đổi prompt (nhất là `off_topic` nay gồm cả kiến thức phổ thông → câu học vụ mơ hồ bị từ chối) | High | Rule 1/5 của YAML ưu tiên `academic_*`; parse lỗi → advisory; test các ví dụ biên trong YAML |
| MULTI fan-out tăng số lần gọi embedding/Qdrant (2-3x) và thêm 1 LLM call | Med | Tối đa 3 sub-query như YAML; chỉ chạy khi `routing_mode == MULTI` |
| Node 11 gọi LLM thêm chi phí và có thể "bịa" quy chế | Med | `ticket_fallback.yaml` cấm suy đoán; không truyền chunk nào vào prompt |
| Đổi `GraphModels` (bỏ `direct_llm`) làm vỡ nhiều test | Low | Gom vào Task 2, sửa test cùng lúc |

## Open Questions

Không còn. Các câu hỏi đã chốt với người dùng: không làm BM25/RRF/rerank; xoá `ui_buttons`; có pre-filter phân quyền; PROCEDURE/DOCUMENT dùng lại `hyde_generator.yaml`; ghi gap vào `known-gaps.md`; `security_access_control.yaml` dùng mảng `department_access` (AD13).
