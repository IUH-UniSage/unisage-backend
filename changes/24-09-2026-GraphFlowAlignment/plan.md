# Implementation Plan: Graph Flow Alignment

> **Repo đích:** `unisage-agent` (`D:\KLTN\Main\unisage-agent`). Mọi đường dẫn file trong plan này tính từ gốc repo đó. Plan được lưu ở `backend-java/changes` theo yêu cầu.
>
> **Baseline:** commit `f5663b5` (sau `1fef03f`: đã bỏ DirectLLM, đã có `agents/hyde_generator.yaml` + `agents/message_classification.yaml`, taxonomy intent đã về 7 nhãn + `greeting` fallback).

## Overview

Đưa graph của `unisage-agent` về đúng flow design trong `RAG_Graph/KLTN` (ảnh flow_design, 11 node), đúng cơ chế load và lắp prompt của design (loader, builder, thư mục `prompt_templates/agents/`), và thêm pre-filter phân quyền ở node 08.

Hiện trạng còn lệch so với design:

- `academic_calculation` đang bị đẩy sang luồng advisory; chưa có node 07.
- Node 03 trả 1 nhãn text, không có `routing_mode`, nên không có cách nào đi vào mode MULTI.
- Node 06 chỉ có HyDE, thiếu Multi-query / Procedure / Document; Generation chỉ có khung `chat_academic_advisory`.
- `agents/` thiếu 3 file của design: `multi_query_decomposer`, `calculation_extractor`, `reranker_compressor`.
- Node 08 không lọc theo quyền: mọi người dùng, kể cả khách, đọc được mọi chunk. `security_access_control` vì vậy đang tắt.
- Node 11 trả câu tĩnh kèm `ui_buttons` (bị mất vì không có field chứa), trong khi node chỉ chạy **sau** retrieve + rerank, nên theo design phải gọi LLM với `chat_ticket_fallback.yaml`.
- Tên trace vẫn theo số node cũ (`05B_`, `10_`, `11_`, `12_`, `13_`).

## Scope đã chốt với người dùng

- **Trong phạm vi:** tất cả mục ở trên, gồm pre-filter phân quyền.
- **Ngoài phạm vi (ghi known-gaps):** BM25/sparse search, RRF, cross-encoder rerank, nén ngữ cảnh, logic tính toán thật của CalculationNode, resume MULTI chỉ chạy lại đúng sub-query còn thiếu.
- **Xoá hẳn `ui_buttons`:** frontend đã có nút để người dùng chủ động tạo ticket.
- **Qdrant:** không migrate collection cũ; người dùng sẽ xoá và tạo lại collection trước khi chạy code mới.

## Ground truth

- **Design là chuẩn cho:** cấu trúc graph (node, cạnh, số thứ tự), cấu trúc load prompt (loader → `PromptTemplates` → builder), danh sách file `agents/*.yaml`, khung `main/*.yaml` nào dùng cho nhánh nào.
- **Code là chuẩn cho nội dung** mọi prompt YAML đã có trong code: `common/*.yaml`, `main/*.yaml`, và `agents/hyde_generator.yaml`, `agents/message_classification.yaml` (đều mới hơn design). **Không sửa, không chép đè từ design**; muốn sửa phải hỏi trước. Ngoại lệ đã được duyệt: thuật ngữ phân quyền trong `security_access_control.yaml` (AD13) và phần `## Output` (+ ví dụ) của `agents/message_classification.yaml` (AD2).
- **File chỉ có trong design** được copy sang code: `agents/multi_query_decomposer.yaml`, `agents/calculation_extractor.yaml`, `agents/reranker_compressor.yaml`, `main/chat_multi_intent_synthesis.yaml`, `main/chat_ticket_fallback.yaml`, `common/ticket_fallback.yaml`. Vì là file mới trong code, **được phép chỉnh nội dung** khi copy cho khớp hành vi thật (AD9, AD10).

## Architecture Decisions

- **AD1. `agents/*.yaml` load nguyên văn, KHÔNG `.format()`** (JSON mẫu có `{}`, không có placeholder). Theo quy ước hiện có: field `agent_<tên>` trong `PromptTemplates`, node dùng làm `system_prompt`.
- **AD2. Node 03 trả thêm `routing_mode` (đã chốt).** Không có nó thì không bao giờ vào được MULTI. Đổi phần `## Output` của `agents/message_classification.yaml` sang **đúng Output Contract của design** (`RAG_Graph/KLTN/prompt_template/agents/message_classification.yaml`): `{"primary_intent", "secondary_intents", "confidence", "routing_mode": "SINGLE" | "MULTI" | null}` + 1-2 ví dụ; taxonomy và rule của bản trong code giữ nguyên. Node parse bằng Pydantic (`IntentClassification`, 4 field như design). Routing chỉ dùng `primary_intent` + `routing_mode`; `secondary_intents` và `confidence` được parse và giữ trên kết quả (ghi vào trace/log), chưa dùng để rẽ nhánh. Thiếu `secondary_intents`/`confidence` → mặc định `[]`/`None`; thiếu hoặc sai `primary_intent`/JSON hỏng → `academic_advisory` + `SINGLE`, giữ hành vi an toàn hiện tại. Không thêm LLM call nào.
- **AD3. Taxonomy giữ như baseline** (7 nhãn + `greeting` làm fallback an toàn). Câu so sánh 2+ thực thể là `academic_advisory` + `routing_mode = MULTI`.
- **AD4. Node 04 trả `(next_node, mode)`:** `social_chat` → END template; `off_topic` → `OffTopicRejectNode`; `academic_calculation` → `CalculationNode`; `academic_procedure` → `PROCEDURE`; `academic_document` → `DOCUMENT`; `academic_advisory`/`academic_calendar` (và `greeting` fallback) → `MULTI` nếu `routing_mode == "MULTI"`, ngược lại `SINGLE`.
- **AD5. PROCEDURE/DOCUMENT dùng lại `agents/hyde_generator.yaml`** (đã chốt). Node 06 nối thêm 1 dòng chỉ dẫn theo mode vào input; giữ nguyên cơ chế hiện có (Bước 1 câu hỏi độc lập + `extract_standalone_question`).
- **AD6. Multi-query (đã chốt với người dùng):**
  - Decomposer (`agents/multi_query_decomposer.yaml`) nhận **câu hỏi thật của người dùng** (kèm lịch sử gần đây qua `append_recent_history`, như node 03/06 đang làm, để câu nối tiếp vẫn đủ ngữ cảnh). **Luồng MULTI không chạy HyDE**: embed thẳng từng sub-query.
  - Tối đa 3 sub-query. Output rỗng, sai JSON hoặc chỉ 1 sub-query → fallback về `SINGLE` (HyDE).
  - Đánh số `SQ1`, `SQ2`, `SQ3` theo thứ tự decomposer trả về. `{sub_queries_list}` render thành các dòng `SQ1. <câu hỏi>`. Mã này trùng với `sub_query_id` trong `ask_user_form`.
  - Node 08: mỗi sub-query lấy tối đa `ceil(RETRIEVAL_MAX_CHUNKS / n)` chunk (đã áp pre-filter), để câu so sánh luôn có chunk của **mọi** thực thể thay vì bị một thực thể chiếm hết top-k. Gộp theo `chunk_id`: trùng thì giữ điểm cao hơn; bằng điểm thì giữ bản của sub-query đứng trước. Kết quả sắp giảm dần theo điểm (sort ổn định), cắt `RETRIEVAL_MAX_CHUNKS`, rồi mới qua ngưỡng ở node 09.
  - `PendingClarification`: thêm field tuỳ chọn `origin_mode` (mặc định `None`, tương thích dữ liệu JSONB cũ) để lượt resume chạy lại đúng mode (MULTI thì phân rã lại). `pending_sub_query_id` lấy từ `sub_query_id` của `ask_user_form` nếu thuộc `SQ1..SQn`, ngược lại `None`. Resume vẫn chạy lại **toàn bộ** câu hỏi gốc; chỉ chạy lại đúng sub-query còn thiếu là known-gap.
- **AD7. Pre-filter phân quyền** áp cho **mọi** truy vấn Qdrant ở node 08, kể cả từng sub-query MULTI. Một chunk được xem khi:
  - `access_level == 0` (thuộc department nào cũng được), **hoặc**
  - `department` nằm trong `department_access` của người hỏi **và** `access_level` của chunk ≤ `access_level` được cấp cho đúng department đó, **hoặc**
  - người hỏi có entry wildcard `department_id = "*"` và `access_level` của chunk ≤ mức của entry đó, với mọi department (đã chốt: cùng nghĩa với `TrustedContext.granted_access_level` bên ingestion).

  Khách vãng lai (`department_access = []`) chỉ rơi vào vế đầu. Vế `access_level == 0` luôn có mặt nên `should` không bao giờ rỗng. Filter dựng **chỉ** từ `AcademicSecurityContext`, không bao giờ đọc `confirmed_metadata`. Chunk thiếu `department` hoặc `access_level` không khớp điều kiện nào nên bị loại (mặc định từ chối).
- **AD8. Qdrant payload index:** `ensure_collection` tạo payload index `department` (keyword) và `access_level` (integer) lúc tạo collection. Không migrate collection cũ (người dùng sẽ xoá và tạo lại).
- **AD9. Khung Generation chọn theo mode:** `SINGLE`/`PROCEDURE`/`DOCUMENT` → `chat_academic_advisory` (giữ nguyên); `MULTI` → `chat_multi_intent_synthesis` (+ `{sub_queries_list}`). Builder gom phần chung vào `_base_params(...)` rồi `.format(**base_params)` theo từng khung, đúng Pha 2 của `detail_prompt_template_loader.md`. Khung MULTI khi copy được thêm `{history_message}` (khung advisory trong code đang có) để hai khung nhận cùng một bộ `base_params`; `{user_query}` vẫn render qua `render_resolved_user_query` như khung advisory (luồng MULTI không có HyDE nên `resolved_query = None`).
- **AD10. Node 11 stream câu trả lời bằng LLM** (`chat_ticket_fallback` + `common/ticket_fallback`), không truyền chunk nào vào prompt. **Xoá `ui_buttons`** và `TicketFallbackResponse`; giữ cờ `used_ticket_fallback`. Nội dung 2 file copy từ design được chỉnh khi copy:
  - bỏ nhắc `rerank_score < 0.70` (code lọc ngưỡng trên điểm cosine; model cũng không cần biết cơ chế) → "không tìm thấy văn bản đủ liên quan";
  - bỏ "hướng dẫn người dùng nhấn nút tạo Ticket" (không còn nút gắn theo câu trả lời) → gợi ý dùng tính năng tạo ticket hỗ trợ có sẵn trên giao diện, không mô tả vị trí nút.
- **AD11. Node 07 là placeholder:** trả message tĩnh "đang phát triển", không gọi LLM hay tool. `calculation_extractor.yaml` được copy + load (để `agents/` đủ như design) nhưng chưa dùng.
- **AD12. `reranker_compressor.yaml` được copy + load nhưng chưa dùng**, vì node 09 chưa có bước nén (xem known-gaps).
- **AD13. Phân quyền trong prompt dùng mảng `department_access` (đã chốt).** Sửa đúng các chỗ thuật ngữ cũ `max_access_level` / `organization_scopes` trong `common/security_access_control.yaml`, không đổi nội dung khác. Sau khi pre-filter chạy, bỏ `_SECURITY_ACCESS_CONTROL_DEFERRED` và truyền `templates.security_access_control` vào mọi khung prompt.

## Task List

### Phase 1: Foundation

- [ ] Task 1: Copy các prompt còn thiếu từ design + load qua loader/schema
- [ ] Task 2: Xác minh baseline DirectLLM đã được gỡ hết
- [ ] Task 3: Đổi tên trace theo số node mới

### Checkpoint: Foundation

- [ ] Toàn bộ test/ruff/mypy pass; luồng advisory chạy như cũ

### Phase 2: Phân quyền + Classification + Routing

- [ ] Task 4: Pre-filter phân quyền ở node 08 + payload index
- [ ] Task 5: `security_access_control` theo `department_access` và bật trong prompt
- [ ] Task 6: Node 03 trả thêm `routing_mode`
- [ ] Task 7: Node 04 routing 4 đích + suy ra `mode`
- [ ] Task 8: Node 07 CalculationNode placeholder

### Checkpoint: Routing + Security

- [ ] Mỗi intent đi đúng nhánh trong ảnh flow_design; khách không đọc được chunk `access_level > 0`; review với người dùng

### Phase 3: Query Transformation + Fallback

- [ ] Task 9: Node 06 mode PROCEDURE/DOCUMENT, trả `transformed_queries`
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
> - **RRF:** điểm RRF tính theo thứ hạng (`1/(60 + rank)`, cỡ 0.01-0.03), không cùng thang với ngưỡng 0.70. Nếu đổi node 08 sang RRF khi chưa có cross-encoder chấm lại ở node 09, mọi chunk đều dưới ngưỡng và mọi câu hỏi rơi vào TicketFallback. RRF chỉ có nghĩa khi đi cùng rerank. Luồng MULTI vì vậy gộp sub-query bằng quota + điểm cao nhất, không dùng RRF.
> - **Cross-encoder rerank:** phần tốn nhất (phí API mỗi câu hỏi, hoặc RAM/CPU/GPU để self-host). Lợi ích bị giảm vì (1) vector `questions` sinh sẵn đã khớp sát câu hỏi sinh viên, (2) mỗi lượt chỉ lấy 8 chunk. Ngoài ra `bge-reranker-base` trong design yếu với tiếng Việt; nếu làm nên dùng `bge-reranker-v2-m3` (self-host qua HuggingFace TEI) hoặc API đa ngôn ngữ (Cohere `rerank-v3.5`, Jina, Voyage).
> - **Nén ngữ cảnh (`agents/reranker_compressor.yaml`):** file đã được load nhưng không node nào dùng, vì chỉ có ý nghĩa sau khi có rerank.
>
> **Rủi ro còn lại:** `RERANK_SCORE_THRESHOLD = 0.70` đang áp lên điểm cosine của `text-embedding-3-small`, trong khi design đặt ngưỡng này cho điểm cross-encoder. Cặp câu liên quan có thể có cosine dưới 0.70, dẫn tới TicketFallback nhầm (hoặc ngược lại). Cần đo trên bộ 30-50 câu hỏi thật có nhãn chunk đúng để chỉnh ngưỡng.
>
> **Khi nào làm lại:** bộ đánh giá cho thấy trượt câu hỏi có mã/số hiệu → thêm BM25; chunk đúng lấy được nhưng xếp sai, hoặc ngưỡng cosine không tách được đúng/sai → thêm rerank (khi đó mới thêm RRF).

Các mục ngắn khác cùng section:

- **CalculationNode là placeholder:** chưa có extractor, Calculator Tool, nguồn dữ liệu điểm/học phí.
- **Resume clarification trong luồng MULTI** chạy lại toàn bộ câu hỏi gốc (đúng mode nhờ `origin_mode`), chưa dùng `pending_sub_query_id` để chỉ chạy lại sub-query còn thiếu.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Pre-filter làm người dùng mất chunk mà trước đây vẫn thấy → nhiều câu rơi vào TicketFallback | High | Test theo từng vai trò (khách, 1 department, nhiều department, wildcard); dữ liệu mới ingest vào collection tạo lại luôn có `department`/`access_level` (int) |
| Filter dựng sai làm lọt quyền hoặc chặn hết | High | Unit test riêng cho hàm dựng filter; vế `access_level == 0` luôn có mặt; test `confirmed_metadata` leo thang không đổi filter |
| Đổi output node 03 làm phân loại kém đi | High | Chỉ đổi phần Output của YAML, giữ nguyên taxonomy và rule; parse lỗi → advisory + SINGLE; test các ví dụ biên |
| MULTI tăng số lần gọi embedding/Qdrant (2-3x) và thêm 1 LLM call | Med | Tối đa 3 sub-query; chỉ chạy khi `routing_mode == MULTI`; bỏ HyDE trong luồng MULTI bù lại 1 LLM call |
| Node 11 gọi LLM thêm chi phí và có thể "bịa" quy chế | Med | `ticket_fallback.yaml` cấm suy đoán; không truyền chunk nào vào prompt |

## Open Questions

Không còn. Đã chốt với người dùng:

- Không làm BM25/RRF/rerank/nén ngữ cảnh; ghi known-gaps vào `known-gaps.md`.
- Xoá `ui_buttons`; node 11 gọi LLM.
- Có pre-filter phân quyền; wildcard `*` áp dụng như ingestion (AD7).
- PROCEDURE/DOCUMENT dùng lại `hyde_generator.yaml` (AD5).
- Multi-query: decomposer nhận câu hỏi thật, không qua HyDE (AD6).
- `security_access_control.yaml` dùng mảng `department_access` (AD13).
- Node 03 lấy `routing_mode` bằng cách đổi phần Output của `message_classification.yaml` sang JSON (AD2).
- Qdrant: không migrate collection cũ.
