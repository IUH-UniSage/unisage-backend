# Implementation Plan: Graph Flow Alignment

> **Repo đích:** `unisage-agent` (`D:\KLTN\Main\unisage-agent`). Mọi đường dẫn file trong plan này tính từ gốc repo đó. Plan được lưu ở `backend-java/changes` theo yêu cầu.
>
> **Baseline:** commit `f5663b5` (sau `1fef03f`: đã bỏ DirectLLM, đã có `agents/hyde_generator.yaml` + `agents/message_classification.yaml`, taxonomy intent đã về 7 nhãn + `greeting` fallback).

## Overview

Đưa graph của `unisage-agent` về đúng flow design trong `RAG_Graph/KLTN` (ảnh flow_design, 11 node), đúng cơ chế load và lắp prompt của design (loader, builder, thư mục `prompt_templates/agents/`), và thêm pre-filter phân quyền ở node 08.

Hiện trạng còn lệch so với design:

- `academic_calculation` đang bị đẩy sang luồng advisory; chưa có node 07.
- Node 03 trả 1 nhãn text, không có `routing_mode`, nên không có cách nào đi vào mode MULTI.
- Mỗi lượt chat chỉ đi vào **một** nhánh: tin nhắn ghép 2 câu hỏi khác loại (VD "tính GPA giúp mình và cho mình biết thủ tục đăng ký tốt nghiệp") chỉ được trả lời phần thắng khi phân loại, phần còn lại bị bỏ im lặng.
- Node 06 chỉ có HyDE, thiếu Multi-query; Generation chỉ có khung `chat_academic_advisory`.
- Taxonomy còn 3 nhãn `academic_procedure`/`academic_calendar`/`academic_document` đi chung đường với `academic_advisory` mà không tạo ra hành vi khác (di tích của `CalendarLookupNode` và các template riêng đã xoá khi gộp flow).
- `agents/` thiếu 3 file của design: `multi_query_decomposer`, `calculation_extractor`, `reranker_compressor`.
- Node 08 không lọc theo quyền: mọi người dùng, kể cả khách, đọc được mọi chunk. `security_access_control` vì vậy đang tắt.
- Node 11 trả câu tĩnh kèm `ui_buttons` (bị mất vì không có field chứa), trong khi node chỉ chạy **sau** retrieve + rerank, nên theo design phải gọi LLM với `chat_ticket_fallback.yaml`.
- Tên trace vẫn theo số node cũ (`05B_`, `10_`, `11_`, `12_`, `13_`).

## Scope đã chốt với người dùng

- **Trong phạm vi:** tất cả mục ở trên, gồm pre-filter phân quyền, và **chạy song song nhiều nhánh trong một lượt** cho tin nhắn ghép nhiều câu hỏi (AD2, AD14 — chốt 2026-09-24).
- **Ngoài phạm vi (ghi known-gaps):** BM25/sparse search, RRF, cross-encoder rerank, nén ngữ cảnh, logic tính toán thật của CalculationNode, resume MULTI chỉ chạy lại đúng sub-query còn thiếu.
- **Xoá hẳn `ui_buttons`:** frontend đã có nút để người dùng chủ động tạo ticket.
- **Qdrant:** không migrate collection cũ; người dùng sẽ xoá và tạo lại collection trước khi chạy code mới.

## Ground truth

- **Design là chuẩn cho:** cấu trúc graph (node, cạnh, số thứ tự), cấu trúc load prompt (loader → `PromptTemplates` → builder), danh sách file `agents/*.yaml`, khung `main/*.yaml` nào dùng cho nhánh nào.
- **Code là chuẩn cho nội dung** mọi prompt YAML đã có trong code: `common/*.yaml`, `main/*.yaml`, và `agents/hyde_generator.yaml`, `agents/message_classification.yaml` (đều mới hơn design). **Không sửa, không chép đè từ design**; muốn sửa phải hỏi trước. Ngoại lệ đã được duyệt: thuật ngữ phân quyền trong `security_access_control.yaml` (AD13) và phần `## Output` (+ ví dụ) của `agents/message_classification.yaml` (AD2).
- **File chỉ có trong design** được copy sang code: `agents/multi_query_decomposer.yaml`, `agents/calculation_extractor.yaml`, `agents/reranker_compressor.yaml`, `main/chat_multi_intent_synthesis.yaml`, `main/chat_ticket_fallback.yaml`, `common/ticket_fallback.yaml`. Vì là file mới trong code, **được phép chỉnh nội dung** khi copy cho khớp hành vi thật (AD9, AD10).

## Architecture Decisions

- **AD1. `agents/*.yaml` load nguyên văn, KHÔNG `.format()`** (JSON mẫu có `{}`, không có placeholder). Theo quy ước hiện có: field `agent_<tên>` trong `PromptTemplates`, node dùng làm `system_prompt`.
- **AD2. Node 03 trả danh sách `tasks` (chốt 2026-09-24, thay contract 4 field của design).** Output của `agents/message_classification.yaml`:
  ```json
  {"tasks": [{"intent": "<nhãn>", "query": "<câu hỏi của task>", "routing_mode": "SINGLE" | "MULTI" | null}],
   "confidence": 0.9}
  ```
  - Có **hai kiểu "multi", mỗi kiểu một node lo** (sửa lại theo AD15, 2026-09-25): node 03 tách tin nhắn thành nhiều task **chỉ khi các câu hỏi khác NHÃN** (đi khác đích: 06 hay 07); 2+ câu hỏi cùng nhãn `academic_advisory` — dù là câu **so sánh** nhiều thực thể hay 2+ câu hỏi độc lập không liên quan — luôn là **1 task** gắn `routing_mode = MULTI`, việc bẻ nhỏ thành sub-query là của decomposer ở node 06, không phải node 03.
  - Tin nhắn chỉ có 1 câu hỏi → 1 task, `query` chép **nguyên văn** tin nhắn (để HyDE Bước 1 và `resolved_query` hoạt động y như cũ).
  - Bỏ `primary_intent`/`secondary_intents` của design: `secondary_intents` là mảng nhãn không ai đọc, và không định tuyến được — không có cơ chế nào chạy "nhãn phụ". `tasks` thay cho cả hai, còn mang câu hỏi con cụ thể để định tuyến thật. `confidence` giữ (ghi trace/log, chưa dùng để rẽ nhánh).
  - Chuẩn hoá khi parse: JSON hỏng/không có task nào → 1 task `academic_advisory` + `SINGLE`, `query` = tin nhắn gốc (hành vi an toàn hiện tại). Nhãn lạ trong một task → `academic_advisory`. `query` rỗng → tin nhắn gốc. `routing_mode` ép về `null` cho `social_chat`/`off_topic`/`academic_calculation`/`greeting`, về `SINGLE` nếu thiếu ở nhãn còn lại; `MULTI` chỉ giữ cho `academic_advisory`. Tối đa **3 task**, task thứ 4 trở đi bị bỏ (ghi log).
  - Không thêm LLM call nào ở node 03.
- **AD3. Taxonomy còn 4 nhãn + `greeting` (chốt 2026-09-24):** `social_chat`, `academic_advisory`, `academic_calculation`, `off_topic` (và `greeting` làm fallback an toàn). `academic_procedure`/`academic_calendar`/`academic_document` gộp vào `academic_advisory`:
  - Cả bốn vốn đi chung đường 06 → 08 → 09 → 10 và chung khung `chat_academic_advisory.yaml`; node 10 không nhận nhãn intent — định dạng theo loại câu hỏi (các bước cho thủ tục, ngày cho lịch) do `academic_domain_rules.yaml` và LLM tự nhận từ nội dung câu hỏi.
  - `academic_calendar` không tạo ra khác biệt nào; `academic_procedure`/`academic_document` chỉ để chọn một dòng chỉ dẫn cho HyDE (mode `PROCEDURE`/`DOCUMENT`, chưa làm). Giữ lại chỉ làm classifier phải phân biệt thêm ba ranh giới (Decision Rule "procedure vs document") mà nhầm cũng vô hại.
  - Lệch ảnh flow_design có chủ đích: ảnh ghi node 06 là "HyDE / Multi-query / Procedure / Document"; code chỉ còn HyDE (`SINGLE`) và Multi-query (`MULTI`).
  - Nhãn cũ nếu model vẫn trả về (`academic_procedure`...) được parse như nhãn lạ → `academic_advisory`.
- **AD4. Node 04 dựng `RoutePlan` từ `tasks` (deterministic, không LLM):**
  1. Có ít nhất một task `academic_*` → bỏ các task `social_chat`/`off_topic`/`greeting`, chỉ trả lời phần học vụ.
  2. Không có task `academic_*`: có task `off_topic` → `OffTopicRejectNode` (05); còn lại toàn `social_chat` → END template xã giao; chỉ có `greeting` → coi như `academic_advisory` + `SINGLE` (fallback an toàn như baseline).
  3. Task `academic_calculation` → nhánh `CalculationNode` (07).
  4. Task `academic_advisory` → nhánh `QueryTransformationNode` (06) kèm `mode = routing_mode` của task (`SINGLE` hoặc `MULTI`).
  Một `RoutePlan` có thể có **cả hai** nhánh 06 và 07 (xem AD14).
- **AD5. ~~PROCEDURE/DOCUMENT dùng lại `hyde_generator.yaml`~~ — bỏ (2026-09-24).** Hai mode này mất lý do tồn tại khi gộp nhãn (AD3). Node 06 chỉ còn `SINGLE` → HyDE (giữ nguyên cơ chế Bước 1 câu hỏi độc lập + `extract_standalone_question`) và `MULTI` → decomposer.
- **AD6. Node 06 xử lý từng task advisory, gộp thành một bộ sub-query (đã chốt với người dùng):**
  - Mỗi task của nhánh 06 chạy **song song** (`asyncio.gather`) theo `mode` của nó: `SINGLE` → HyDE (1 query); `MULTI` → decomposer.
  - Decomposer (`agents/multi_query_decomposer.yaml`) nhận **câu hỏi thật của task** (`task.query`, kèm lịch sử gần đây qua `append_recent_history` để câu nối tiếp vẫn đủ ngữ cảnh). **Task MULTI không chạy HyDE**: embed thẳng từng sub-query. Tối đa 3 sub-query mỗi task; output rỗng, sai JSON hoặc chỉ 1 sub-query → task đó rơi về `SINGLE` (HyDE).
  - Query của mọi task được nối theo thứ tự task thành **một** danh sách, đánh số `SQ1..SQn` (n ≤ 9 = 3 task × 3). `{sub_queries_list}` render các dòng `SQk. <câu hỏi>` — với task HyDE ghi `task.query` (câu hỏi người đọc được), không ghi văn bản giả định. Mã `SQk` trùng với `sub_query_id` trong `ask_user_form`.
  - Node 08: mỗi sub-query lấy tối đa `ceil(RETRIEVAL_MAX_CHUNKS / n)` chunk (đã áp pre-filter), để câu so sánh/câu ghép luôn có chunk của **mọi** phần thay vì bị một phần chiếm hết top-k. Gộp theo `chunk_id`: trùng thì giữ điểm cao hơn; bằng điểm thì giữ bản của sub-query đứng trước. Kết quả sắp giảm dần theo điểm (sort ổn định), cắt `RETRIEVAL_MAX_CHUNKS`, rồi mới qua ngưỡng ở node 09.
  - `PendingClarification`: thêm field tuỳ chọn `origin_tasks: list[ClassifiedTask] | None` (mặc định `None`, tương thích JSONB cũ) — các task nhánh 06 của lượt gốc, để lượt resume chạy lại **đúng các task đó với đúng mode** (task tính toán không chạy lại). `None` (dữ liệu cũ) → resume như hiện tại: 1 task `SINGLE` trên `original_query`. `pending_sub_query_id` lấy từ `sub_query_id` của `ask_user_form` nếu thuộc `SQ1..SQn`, ngược lại `None`. Resume vẫn chạy lại **toàn bộ** các task đó; chỉ chạy lại đúng sub-query còn thiếu là known-gap.
- **AD7. Pre-filter phân quyền** áp cho **mọi** truy vấn Qdrant ở node 08, kể cả từng sub-query MULTI. Một chunk được xem khi:
  - `is_public == True` (**đã sửa 2026-09**, xem ghi chú bên dưới), **hoặc**
  - `department` nằm trong `department_access` của người hỏi **và** `access_level` của chunk ≤ `access_level` được cấp cho đúng department đó, **hoặc**
  - người hỏi có entry wildcard `department_id = "*"` và `access_level` của chunk ≤ mức của entry đó, với mọi department (cùng nghĩa với `TrustedContext.granted_access_level` bên ingestion).

  Khách vãng lai (`department_access = []`) chỉ rơi vào vế đầu. Vế `is_public == True` luôn có mặt nên `should` không bao giờ rỗng. Filter dựng **chỉ** từ `AcademicSecurityContext`, không bao giờ đọc `confirmed_metadata`. Chunk thiếu `department`/`access_level`/`is_public` không khớp điều kiện nào nên bị loại (mặc định từ chối).

  > **Sửa 2026-09 (điều tra lại khi implement Task 4):** thiết kế ban đầu dùng `access_level == 0` làm điều kiện công khai. Người dùng chỉ ra đây là sai — tiêu chí "công khai" thật sự nằm ở field `@Column(name = "is_public") private Boolean isPublic` trên entity `Document` (`unisage-backend`), độc lập với `min_access_level`/`department`, và khách vãng lai không có `access_level` nào để so sánh (chỉ có `department_access = []`). Đã đổi sang field `is_public` riêng, boolean, mặc định `False`. Việc này kéo theo phải thêm `is_public` xuyên suốt pipeline ingest (không chỉ ở retrieval): `EmbeddingRequest.is_public` (mặc định `False`) → tham số Celery task `embed_chunks` → `ChunkPoint.is_public` → payload Qdrant — nếu không, không có cách nào đánh dấu một chunk là công khai, và mọi câu hỏi của khách sẽ luôn rơi vào TicketFallback.
- **AD8. Qdrant payload index:** `ensure_collection` tạo payload index `department` (keyword), `access_level` (integer), `is_public` (bool) lúc tạo collection. Không migrate collection cũ (người dùng sẽ xoá và tạo lại).
- **AD9. Khung Generation chọn theo số sub-query:** đúng 1 task nhánh 06 ở `SINGLE` → `chat_academic_advisory` (giữ nguyên, kèm `resolved_query`); mọi trường hợp còn lại (task MULTI, hoặc 2+ task nhánh 06) → `chat_multi_intent_synthesis` (+ `{sub_queries_list}`, `resolved_query = None`). Builder gom phần chung vào `_base_params(...)` rồi `.format(**base_params)` theo từng khung, đúng Pha 2 của `detail_prompt_template_loader.md`. Khung MULTI khi copy được thêm `{history_message}` (khung advisory trong code đang có) để hai khung nhận cùng một bộ `base_params`; `{user_query}` vẫn render qua `render_resolved_user_query` như khung advisory (luồng MULTI không có HyDE nên `resolved_query = None`).
- **AD10. Node 11 stream câu trả lời bằng LLM** (`chat_ticket_fallback` + `common/ticket_fallback`), không truyền chunk nào vào prompt. **Xoá `ui_buttons`** và `TicketFallbackResponse`; giữ cờ `used_ticket_fallback`. Nội dung 2 file copy từ design được chỉnh khi copy:
  - bỏ nhắc `rerank_score < 0.70` (code lọc ngưỡng trên điểm cosine; model cũng không cần biết cơ chế) → "không tìm thấy văn bản đủ liên quan";
  - bỏ "hướng dẫn người dùng nhấn nút tạo Ticket" (không còn nút gắn theo câu trả lời) → gợi ý dùng tính năng tạo ticket hỗ trợ có sẵn trên giao diện, không mô tả vị trí nút.
- **AD11. Node 07 là placeholder:** trả message tĩnh "đang phát triển", không gọi LLM hay tool. `calculation_extractor.yaml` được copy + load (để `agents/` đủ như design) nhưng chưa dùng.
- **AD12. `reranker_compressor.yaml` được copy + load nhưng chưa dùng**, vì node 09 chưa có bước nén (xem known-gaps).
- **AD13. Phân quyền trong prompt dùng mảng `department_access` (đã chốt).** Sửa đúng các chỗ thuật ngữ cũ `max_access_level` / `organization_scopes` trong `common/security_access_control.yaml`, không đổi nội dung khác. Sau khi pre-filter chạy, bỏ `_SECURITY_ACCESS_CONTROL_DEFERRED` và truyền `templates.security_access_control` vào mọi khung prompt.
- **AD14. Chạy song song nhánh 06 và 07 trong một lượt, gộp ở node 10 (chốt 2026-09-24, lệch flow_design có chủ đích).** Design cho node 04 chọn đúng một nhánh; chỗ gộp ở node 10 thì design đã có (07→10 và 09→10). Quy tắc:
  - Chỉ có task tính toán → node 07 stream placeholder rồi kết thúc (không qua 10).
  - Chỉ có task nhánh 06 → như plan cũ: 06 → 08 → 09 → 10 hoặc 11.
  - Có cả hai → chạy nhánh 06 (06 → 08 → 09 → 10/11) cho phần học vụ; phần tính toán: khi node 07 còn là placeholder, câu placeholder được **nối sau** câu trả lời của 10/11 một cách deterministic (không đưa qua LLM). Khi node 07 làm thật, kết quả tính toán sẽ đi vào prompt của 10 — việc của đợt làm CalculationNode.
  - TicketFallback (11) thay cho 10 khi nhánh 06 không có chunk nào vượt ngưỡng — tính trên **toàn bộ** chunk đã gộp của mọi task; nếu chỉ một phần thiếu văn bản, node 10 vẫn chạy và `task_1` đã buộc nói rõ phần nào chưa có quy định.
  - Hỏi lại thuộc tính (`ask_user_form`) phase này chỉ phát sinh từ nhánh 06 (node 07 là placeholder, không hỏi lại). `PendingClarification` chỉ có một `origin_node` — khi 07 làm thật mà cả hai nhánh cùng cần hỏi lại thì chưa xử lý được: ghi known-gap.
  - Trace một lượt ghép có cả `07_CalculationNode` lẫn chuỗi `06 → 08 → 09 → 10`.
- **AD15. Node 03 chỉ tách task theo NHÃN, không theo nội dung câu hỏi (chốt 2026-09-25, theo yêu cầu người dùng "không muốn 2 node trùng nhiệm vụ").** Trước AD15, node 03 tự tách một tin nhắn có "2+ câu hỏi thật sự khác nhau" thành nhiều task **dù cùng nhãn** `academic_advisory` (VD "Học phí CNTT bao nhiêu, với lại điều kiện học bổng là gì?" → 2 task `SINGLE`), trong khi decomposer ở node 06 cũng làm một việc tương tự cho câu so sánh (`routing_mode = MULTI`) — hai node cùng đảm nhiệm việc "bẻ một tin nhắn thành nhiều câu hỏi con", chỉ khác input.
  - Quy tắc mới: node 03 chỉ tách task khi các câu hỏi có **nhãn khác nhau** (đi khác node: 06 vs 07, hoặc dừng sớm ở 05). 2+ câu hỏi cùng nhãn `academic_advisory` — không phân biệt so sánh hay độc lập — luôn gộp thành **1 task duy nhất** với `routing_mode = MULTI`, `query` giữ nguyên văn phần học vụ của tin nhắn.
  - Node 06 (decomposer) là nơi DUY NHẤT bẻ một task `MULTI` thành sub-query, bất kể task đó là câu so sánh hay nhiều câu hỏi độc lập gộp lại — `agents/multi_query_decomposer.yaml` được mở rộng phần Objective + thêm ví dụ cho dạng "nhiều câu hỏi độc lập" (trước đó chỉ có ví dụ so sánh).
  - Không đổi contract JSON (`{"tasks": [...], "confidence"}`), không đổi code parse ở `message_classification.py`/`query_transformation.py`/`streaming_graph.py` — cả ba đều đã tổng quát theo số task/mode, chỉ có PROMPT của node 03 và node 06 thay đổi hướng dẫn. Test hiện có ở mức parse/graph-wiring không phụ thuộc nội dung prompt nên không cần sửa; thêm 1 test graph-wiring mới mô phỏng đúng hình dạng output mới (1 task MULTI → decomposer → khung `chat_multi_intent_synthesis`), giữ test cũ (2 task SINGLE riêng biệt) làm bài test phòng thủ cho trường hợp model vẫn lỡ trả về hình dạng cũ.
  - Kiểm tay với model thật (gpt-4o-mini) cho ví dụ "Học phí CNTT bao nhiêu, với lại điều kiện học bổng là gì?" chưa được thực hiện lại sau thay đổi này — cần làm trước khi coi là xong hoàn toàn (xem Task 10 mục kiểm tay cũ, cùng dạng).

## Task List

### Phase 1: Foundation

- [x] Task 1: Copy các prompt còn thiếu từ design + load qua loader/schema
- [x] Task 2: Xác minh baseline DirectLLM đã được gỡ hết
- [x] Task 3: Đổi tên trace theo số node mới

### Checkpoint: Foundation

- [x] Toàn bộ test/ruff/mypy pass; luồng advisory chạy như cũ

### Phase 2: Phân quyền + Classification + Routing

- [x] Task 4: Pre-filter phân quyền ở node 08 + payload index
- [x] Task 5: `security_access_control` theo `department_access` và bật trong prompt
- [x] Task 6: Node 03 trả danh sách `tasks` (taxonomy 4 nhãn)
- [x] Task 7: Node 04 dựng `RoutePlan` từ `tasks` (4 đích, có thể 2 nhánh cùng lúc)
- [x] Task 8: Node 07 CalculationNode placeholder + gộp với nhánh 06

### Checkpoint: Routing + Security

- [x] Mỗi intent đi đúng nhánh trong ảnh flow_design; tin nhắn ghép tính toán + học vụ đi cả hai nhánh; khách chỉ đọc được chunk `is_public`; review với người dùng

### Phase 3: Query Transformation + Fallback

- [x] Task 9: Node 06 chạy từng task (HyDE), trả `transformed_queries`
- [x] Task 10: Decomposer cho task MULTI + node 08 fan-out + khung `chat_multi_intent_synthesis` + `origin_tasks`
- [x] Task 10c: Node 03 chỉ tách task theo nhãn, không theo nội dung câu hỏi (AD15)
- [x] Task 11: Node 11 TicketFallback gọi LLM, xoá `ui_buttons`

### Checkpoint: Complete

- [ ] E2E advisory / so sánh (MULTI) / 2 câu hỏi học vụ khác chủ đề / tính toán + học vụ / fallback / guest pass
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
- **Resume clarification trong luồng MULTI** chạy lại toàn bộ các task nhánh 06 của lượt gốc (đúng mode nhờ `origin_tasks`), chưa dùng `pending_sub_query_id` để chỉ chạy lại sub-query còn thiếu.
- **Hỏi lại từ hai nhánh cùng lúc:** `PendingClarification` chỉ có một `origin_node`. Khi CalculationNode làm thật và cả nhánh tính toán lẫn nhánh học vụ cùng cần hỏi lại trong một lượt ghép, chưa có cách giữ hai vòng hỏi lại song song.
- **Tối đa 3 task mỗi tin nhắn:** tin nhắn có hơn 3 câu hỏi khác nhau chỉ được trả lời 3 câu đầu (task thứ 4 trở đi bị bỏ, có ghi log).
- **Kết quả tính toán chưa đi vào prompt của node 10:** khi CalculationNode còn là placeholder, câu placeholder chỉ được nối sau câu trả lời học vụ, không được LLM diễn giải chung.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Pre-filter làm người dùng mất chunk mà trước đây vẫn thấy → nhiều câu rơi vào TicketFallback | High | Test theo từng vai trò (khách, 1 department, nhiều department, wildcard); dữ liệu mới ingest vào collection tạo lại luôn có `department`/`access_level` (int) |
| Filter dựng sai làm lọt quyền hoặc chặn hết | High | Unit test riêng cho hàm dựng filter; vế `is_public == True` luôn có mặt; test `confirmed_metadata` leo thang không đổi filter; test vế công khai độc lập với department/access_level |
| Đổi output node 03 làm phân loại kém đi | High | Chỉ đổi phần Output của YAML, giữ nguyên taxonomy và rule; parse lỗi → 1 task advisory + SINGLE; test các ví dụ biên |
| Classifier tách task sai (tách thừa câu so sánh thành 2 task, hoặc gộp 2 câu hỏi khác nhau thành 1) | High | Prompt nói rõ chỉ tách khi có 2+ câu hỏi khác nhau, câu so sánh là 1 task `MULTI`; 1 câu hỏi thì chép nguyên văn; ví dụ biên trong YAML; kiểm tay bằng bộ tin nhắn mẫu ở todo Task 6 |
| MULTI và tin nhắn nhiều task tăng số lần gọi LLM (1 HyDE/decomposer mỗi task) và embedding/Qdrant (mỗi sub-query) | Med | Tối đa 3 task × 3 sub-query; các lời gọi của từng task chạy song song nên độ trễ không cộng dồn; task MULTI bỏ HyDE bù lại 1 LLM call |
| Node 11 gọi LLM thêm chi phí và có thể "bịa" quy chế | Med | `ticket_fallback.yaml` cấm suy đoán; không truyền chunk nào vào prompt |

## Open Questions

Không còn. Đã chốt với người dùng:

- Không làm BM25/RRF/rerank/nén ngữ cảnh; ghi known-gaps vào `known-gaps.md`.
- Xoá `ui_buttons`; node 11 gọi LLM.
- Có pre-filter phân quyền; wildcard `*` áp dụng như ingestion (AD7).
- Gộp `academic_procedure`/`academic_calendar`/`academic_document` vào `academic_advisory`, bỏ mode `PROCEDURE`/`DOCUMENT` (AD3, AD5).
- Multi-query: decomposer nhận câu hỏi thật, không qua HyDE (AD6).
- `security_access_control.yaml` dùng mảng `department_access` (AD13).
- Node 03 trả danh sách `tasks` (mỗi task có `intent`, `query`, `routing_mode`), bỏ `primary_intent`/`secondary_intents` của design; `routing_mode` nằm trong từng task (AD2).
- Tin nhắn ghép nhiều loại câu hỏi được trả lời hết bằng cách chạy song song nhánh 06 và 07, gộp ở node 10 (AD14).
- Qdrant: không migrate collection cũ.
