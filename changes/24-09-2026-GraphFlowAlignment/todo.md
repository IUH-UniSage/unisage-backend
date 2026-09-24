# Todo: Graph Flow Alignment

> **Repo đích:** `unisage-agent`. Đường dẫn file tính từ gốc repo đó. Baseline: commit `f5663b5`.

Lệnh kiểm tra chung (AGENTS.md của `unisage-agent`):

```powershell
.venv\Scripts\python.exe -m pytest
.venv\Scripts\python.exe -m ruff check app tests
.venv\Scripts\python.exe -m ruff format --check app tests
.venv\Scripts\python.exe -m mypy app tests
```

---

## Phase 1: Foundation

### Task 1: Copy các prompt còn thiếu từ design + load qua loader/schema

**Description:** Copy từ `RAG_Graph/KLTN/prompt_template` sang `app/rag/prompting/prompt_templates` **chỉ các file code chưa có**: `agents/multi_query_decomposer.yaml`, `agents/calculation_extractor.yaml`, `agents/reranker_compressor.yaml`, `main/chat_multi_intent_synthesis.yaml`, `main/chat_ticket_fallback.yaml`, `common/ticket_fallback.yaml`. Khi copy: khung MULTI thêm `{history_message}` (AD9); 2 file ticket fallback chỉnh nội dung theo AD10. Thêm field vào `PromptTemplates` theo quy ước hiện có (`agent_multi_query_decomposer`, `agent_calculation_extractor`, `agent_reranker_compressor`, `chat_multi_intent_synthesis`, `chat_ticket_fallback`, `ticket_fallback`) và vào `_load_all_templates`. Agent template load nguyên văn (AD1).

**Acceptance criteria:**
- [x] `get_templates()` có đủ 6 field mới
- [x] `git diff` không chạm file YAML nào đã có sẵn (`agents/hyde_generator`, `agents/message_classification`, mọi `common/*`, `main/*` cũ)
- [x] `ticket_fallback.yaml` / `chat_ticket_fallback.yaml` không còn `rerank_score` và không còn câu "nhấn nút"
- [x] Test loader khẳng định agent template mới giữ nguyên dấu `{`/`}` của JSON mẫu

**Verification:**
- [x] `pytest tests/rag/test_prompt_loader.py`
- [x] Bộ lệnh kiểm tra chung pass

**Dependencies:** None

**Files likely touched:** `app/rag/prompting/schema.py`, `app/rag/prompting/loader.py`, 6 file YAML mới, `tests/rag/test_prompt_loader.py`

**Estimated scope:** Medium

---

### Task 2: Xác minh baseline DirectLLM đã được gỡ hết

**Description:** Commit `1fef03f` đã gỡ DirectLLMNode. Task này chỉ xác minh, không xoá gì thêm. Nếu còn sót thì dọn: hiện chỉ còn 1 comment nhắc `general_knowledge/DirectLLMNode` ở `tests/e2e/test_advisory_flow_e2e.py:233` → viết lại comment cho khớp flow hiện tại.

**Acceptance criteria:**
- [x] `grep -rniE "direct_llm|DirectLLM|chat_direct_llm|general_knowledge" app tests` không còn kết quả (trừ nơi cố ý nói nhãn cũ bị map sang fallback, nếu có, kèm lý do)
- [x] `GraphModels` chỉ còn `classification`, `query_transformation`, `generation`, `retrieval`

**Verification:**
- [x] Chạy grep trên; `pytest tests/e2e`

**Dependencies:** None

**Files likely touched:** `tests/e2e/test_advisory_flow_e2e.py` (chỉ comment)

**Estimated scope:** XS

---

### Task 3: Đổi tên trace theo số node mới

**Description:** Trong `app/graph/streaming_graph.py`: `05B_SocialChat` → `04_IntentRouting_SocialChat` (END template, không phải node riêng), `05B_OffTopicRejectNode` → `05_OffTopicRejectNode`, `10_RetrievalFilteringNode` → `08_`, `11_PostRetrievalRerankNode` → `09_`, `12_GenerationSynthesisNode` → `10_`, `13_TicketFallbackNode` → `11_`. Sửa comment "Node 10/11/12/13" trong cùng file, `# Retrieval / rerank (nodes 10/11)` trong `app/core/config.py` + `.env.example`, và "node 12" trong docstring `app/schemas/clarification.py`.

**Acceptance criteria:**
- [x] Trace một lượt advisory: `01, 02, 03, 04, 06, 08, 09, 10`
- [x] `grep -rnE "05B_|10_Retrieval|11_PostRetrieval|12_Generation|13_Ticket" app tests` không còn kết quả

**Verification:**
- [x] `pytest tests/test_graph_trace.py tests/graph/test_graph_wiring.py tests/e2e`

**Dependencies:** None

**Files likely touched:** `app/graph/streaming_graph.py`, `app/core/config.py`, `.env.example`, `app/schemas/clarification.py`, `tests/test_graph_trace.py`

**Estimated scope:** Small

### Checkpoint: Foundation
- [x] Bộ lệnh kiểm tra chung pass
- [x] Luồng advisory (e2e) chạy như trước

---

## Phase 2: Phân quyền + Classification + Routing

### Task 4: Pre-filter phân quyền ở node 08 + payload index

**Description:** Thêm hàm thuần `build_access_filter(security: AcademicSecurityContext) -> models.Filter` theo AD7 (gồm vế wildcard `*`). `search_chunks` nhận `query_filter` và truyền vào mọi `query_points` (cả 3 named vector). `RetrievalServiceProtocol.retrieve(query, *, security, limit=None)`; node 08 truyền `graph_input.security`. `ensure_collection` tạo payload index `department` (keyword) + `access_level` (integer) + `is_public` (bool) khi tạo collection mới (AD8; không xử lý collection cũ). Cập nhật docstring "Deliberately does NOT filter by permission yet" và các `FakeRetrievalService` trong test.

**Sửa trong lúc làm (xem ghi chú AD7 trong plan.md):** tiêu chí "công khai" không phải `access_level == 0` mà là field `is_public: bool` riêng (mirror `Document.isPublic` bên `unisage-backend`), vì khách vãng lai không có `access_level` nào để so — chỉ có `department_access = []`. Field này chưa tồn tại ở đâu trong `unisage-agent` nên phải thêm xuyên suốt: `EmbeddingRequest.is_public` (`app/schemas/ingestion.py`, mặc định `False`) → `embed_chunks.delay(...)` (`app/api/v1/ingestion.py`) → tham số `is_public` của Celery task `embed_chunks` (`app/worker/celery_app.py`) → `ChunkPoint.is_public` → payload Qdrant. Không có bước này thì không chunk nào có thể được đánh dấu công khai, và mọi câu hỏi của khách sẽ luôn rơi vào TicketFallback.

**Acceptance criteria:**
- [x] Khách chỉ nhận chunk `is_public == True`, mọi department
- [x] `[{KHOA_CNTT, 2}]` nhận: mọi chunk `is_public == True` + chunk KHOA_CNTT cấp 1, 2 (không public); không nhận KHOA_CNTT cấp 3 hay chunk department khác cấp ≥ 1
- [x] Nhiều department với mức khác nhau: mỗi department dùng đúng mức của nó
- [x] Wildcard: `[{*, 2}]` thấy chunk mọi department cấp ≤ 2, không thấy cấp 3; `[{*, 1}, {KHOA_CNTT, 3}]` thấy KHOA_CNTT cấp 3 nhưng department khác chỉ tới cấp 1
- [x] Vế `is_public` độc lập với department/access_level: chunk `is_public = True` với `department`/`access_level` bất kỳ vẫn được thấy, kể cả khi người hỏi không có quyền department đó
- [x] `confirmed_metadata` chứa giá trị leo thang → filter sinh ra không đổi
- [x] Collection mới tạo có 3 payload index (`department`, `access_level`, `is_public`)
- [x] `EmbeddingRequest` không truyền `is_public` → mặc định `False` xuyên suốt tới `ChunkPoint`/payload; truyền `is_public: true` → giữ nguyên tới `ChunkPoint`

**Verification:**
- [x] Unit test cho `build_access_filter`
- [x] `pytest tests/test_retrieval.py tests/test_qdrant_store.py tests/test_embed_chunks_task.py tests/test_ingestion_embedding.py tests/test_ingestion_schemas.py tests/graph tests/api`

**Dependencies:** Task 3

**Files likely touched:** `app/rag/vectorstore/qdrant_store.py`, `app/rag/retrieval/service.py`, `app/graph/nodes/retrieval_filtering.py`, `app/graph/streaming_graph.py`, `app/schemas/ingestion.py`, `app/api/v1/ingestion.py`, `app/worker/celery_app.py`, tests

**Estimated scope:** Medium → Large (ingestion pipeline cũng bị chạm vì `is_public`)

---

### Task 5: `security_access_control` theo `department_access` và bật trong prompt

**Description:** Sửa `common/security_access_control.yaml` (đã được người dùng duyệt, AD13), chỉ đúng các chỗ thuật ngữ phân quyền, không đổi nội dung khác:
- Mục 1: "đã qua bộ lọc phân quyền theo `max_access_level` và `organization_scopes`" → theo `department_access` (mỗi phòng ban kèm `access_level` được cấp); tài liệu đánh dấu công khai (`is_public`) thì ai cũng đọc được, không phân biệt phòng ban.
- Mục 2: "khoa khác với `organization_scopes`" → "phòng ban/khoa không có trong `department_access`"; "`role` và `max_access_level` hiện có" → "`role` và `department_access` hiện có".
- Mục 4: danh sách cấm nhắc `max_access_level`, `organization_scopes` → `access_level`, `department_access`.

Rồi bỏ `_SECURITY_ACCESS_CONTROL_DEFERRED` trong `app/rag/prompting/__init__.py`, truyền `templates.security_access_control` vào mọi khung có placeholder này.

**Acceptance criteria:**
- [x] `grep -rnE "max_access_level|organization_scopes" app/rag/prompting` không còn kết quả
- [x] Prompt advisory render ra có khối "Quy Tắc Bảo Mật & Phân Quyền Thông Tin"
- [x] `git diff` của yaml chỉ chạm các dòng thuật ngữ ở trên

**Verification:**
- [x] `pytest tests/rag tests/graph/test_generation_synthesis_node.py`

**Dependencies:** Task 4

**Files likely touched:** `app/rag/prompting/prompt_templates/common/security_access_control.yaml`, `app/rag/prompting/__init__.py`, `tests/rag/test_prompt_loader.py`

**Estimated scope:** Small

---

### Task 6: Node 03 trả danh sách `tasks`

**Description:** Theo AD2 (chốt 2026-09-24): sửa phần `## Output` của `agents/message_classification.yaml` sang contract `{"tasks": [{"intent", "query", "routing_mode"}], "confidence"}`, thêm hướng dẫn **khi nào tách task** (chỉ khi có 2+ câu hỏi khác nhau; câu so sánh là 1 task `MULTI`; 1 câu hỏi thì chép nguyên văn tin nhắn vào `query`) và ví dụ. Theo AD3 (chốt 2026-09-24): taxonomy còn 4 nhãn + `greeting` — gộp `academic_procedure`/`academic_calendar`/`academic_document` vào `academic_advisory`, bỏ Decision Rule "procedure vs document"; các rule còn lại giữ nguyên. Model trong `app/schemas/intent.py`: `ClassifiedTask {intent, query, routing_mode}` và `IntentClassification {tasks, confidence}`. `classify_intent(agent, message, history)` parse JSON (chịu được code fence) và chuẩn hoá theo AD2. Trong `streaming_graph.py` chỉ nối tạm: route theo intent của **task đầu tiên** cho tới Task 7 (hành vi như baseline với tin nhắn 1 câu hỏi).

> Bản làm trước theo contract 4 field (`primary_intent`/`secondary_intents`/`confidence`/`routing_mode`) chưa commit — làm lại theo contract này.

**Acceptance criteria:**
- [x] JSON 1 task → 1 `ClassifiedTask` đúng `intent`/`query`/`routing_mode`, `confidence` đúng
- [x] JSON 2 task khác loại (tính toán + thủ tục) → đủ 2 task, đúng thứ tự
- [x] Câu so sánh trả về 1 task `academic_advisory` + `MULTI` → giữ nguyên `MULTI`
- [x] Chuẩn hoá: JSON hỏng / `tasks` rỗng / nhãn text kiểu cũ → 1 task `academic_advisory` + `SINGLE`, `query` = tin nhắn gốc; nhãn lạ trong 1 task → `academic_advisory`; `query` rỗng → tin nhắn gốc; `routing_mode` sai chỗ bị ép về `null`/`SINGLE`; nhãn cũ `academic_procedure`/`academic_calendar`/`academic_document` → `academic_advisory` (giữ `MULTI` nếu có); hơn 3 task → giữ 3 task đầu
- [x] YAML: taxonomy còn 4 nhãn + `greeting`, các ví dụ của 3 nhãn cũ chuyển sang `academic_advisory`, bỏ rule "procedure vs document"; các Decision Rules còn lại không đổi chữ nào
- [x] Mock của model phân loại trong mọi test khác trả đúng JSON mới (helper `make_classification_llm_model` trong `tests/llm_mocks.py`)

**Verification:**
- [x] `pytest tests/graph/test_message_classification_node.py`
- [x] Bộ lệnh kiểm tra chung pass
- [x] Kiểm tay (gọi model thật `gpt-4o-mini`, 2026-09-24: 6/6 đúng): 6 tin nhắn mẫu ra đúng số task —
  "Điều kiện học bổng loại giỏi là gì?" (1 task SINGLE) ·
  "Ngành CNTT và Kế toán học phí chênh bao nhiêu?" (1 task MULTI) ·
  "Tính giúp điểm GPA cho mình và cho mình biết thủ tục đăng ký tốt nghiệp" (2 task: calculation + advisory) ·
  "Học phí ngành CNTT bao nhiêu, với lại điều kiện học bổng là gì?" (2 task advisory) ·
  "Cảm ơn nhé, cho mình hỏi hạn đóng học phí kỳ 2" (1 task advisory — phần cảm ơn bị bỏ theo quy tắc của prompt) ·
  "Giá vàng hôm nay?" (1 task off_topic)

**Dependencies:** Task 1

**Files likely touched:** `app/rag/prompting/prompt_templates/agents/message_classification.yaml`, `app/graph/nodes/message_classification.py`, `app/schemas/intent.py` (mới), `app/graph/streaming_graph.py`, `tests/graph/test_message_classification_node.py`, `tests/llm_mocks.py`, `tests/conftest.py` + các test dùng mock phân loại

**Estimated scope:** Medium

---

### Task 7: Node 04 dựng `RoutePlan` từ `tasks`

**Description:** `plan_route(classification) -> RoutePlan` theo AD4: `end` (`"SOCIAL_CHAT"` | `"OFF_TOPIC"` | `None`), `calculation_tasks: list[ClassifiedTask]`, `advisory_tasks: list[tuple[ClassifiedTask, QueryMode]]` với `QueryMode = SINGLE | MULTI`. `streaming_graph.py` rẽ theo `RoutePlan`: `end` → template/05 như cũ; mọi lượt có task học vụ (advisory và/hoặc tính toán) → vẫn chạy nhánh advisory trên **cả tin nhắn**, y như baseline (tin nhắn 1 câu hỏi có `task.query` chính là tin nhắn). `advisory_tasks`/`calculation_tasks` được dựng đủ nhưng chưa được dùng: truyền task + mode vào node 06 là Task 9/10, tách nhánh 07 là Task 8 — không thêm tham số chưa dùng vào `_run_advisory_flow`. Lượt resume: có `pending.origin_tasks` thì dùng, không có thì 1 task `SINGLE` trên `original_query` (field `origin_tasks` được thêm ở Task 10; trước đó luôn nhánh cũ).

**Acceptance criteria:**
- [x] Bảng test: mỗi intent đơn × `routing_mode` ra đúng nhánh + `mode`
- [x] Tin nhắn ghép: `social_chat` + học vụ → chỉ còn phần học vụ; `off_topic` + học vụ → chỉ còn phần học vụ; `social_chat` + `off_topic` → `OFF_TOPIC`; chỉ `greeting` → 1 task advisory `SINGLE`
- [x] Tính toán + thủ tục → `calculation_tasks` 1 phần tử và `advisory_tasks` 1 phần tử (`SINGLE`)
- [x] Bỏ docstring "not implemented yet - degrade to the advisory flow"

**Verification:**
- [x] `pytest tests/graph/test_intent_routing_node.py tests/graph/test_graph_wiring.py`

**Dependencies:** Task 6

**Files likely touched:** `app/graph/nodes/intent_routing.py`, `app/graph/streaming_graph.py`, tests routing/wiring

**Estimated scope:** Small

---

### Task 8: Node 07 CalculationNode placeholder + gộp với nhánh 06

**Description:** `app/graph/nodes/calculation.py` với `CALCULATION_PLACEHOLDER_TEMPLATE` (AD11). Theo AD14: chỉ có `calculation_tasks` → stream placeholder, kết thúc lượt, trace `07_CalculationNode`; có cả hai nhánh → chạy nhánh 06 trước, rồi **nối** placeholder sau câu trả lời của 10/11 (một token riêng cách bằng dòng trống, không đi qua LLM), trace có `07_CalculationNode`. Không gọi LLM, không dùng `calculation_extractor.yaml`.

**Acceptance criteria:**
- [ ] Chỉ tính toán → trả placeholder, không chạy retrieval
- [ ] Tính toán + thủ tục → câu trả lời có phần thủ tục (từ generation) **và** placeholder ở cuối; `response_text` lưu sang Java chứa cả hai
- [ ] `confirmed_metadata`/`pending_clarification` được giữ nguyên qua lượt chỉ tính toán; lượt ghép giữ `pending_clarification` do nhánh 06 sinh ra
- [ ] Citation của lượt ghép chỉ dựng từ phần trả lời học vụ (placeholder không có `[n]`)

**Verification:**
- [ ] Test mới trong `tests/graph/`; `pytest tests/graph`

**Dependencies:** Task 7

**Files likely touched:** `app/graph/nodes/calculation.py` (mới), `app/graph/streaming_graph.py`, `tests/graph/test_calculation_node.py` (mới), `tests/graph/test_graph_wiring.py`

**Estimated scope:** Small

### Checkpoint: Routing + Security
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] Mỗi intent đi đúng nhánh trong ảnh flow_design; tin nhắn ghép tính toán + học vụ đi cả hai nhánh
- [ ] Khách chỉ đọc được chunk `is_public`
- [ ] Prompt advisory có khối `security_access_control` nói theo `department_access`
- [ ] Review với người dùng trước Phase 3

---

## Phase 3: Query Transformation + Fallback

### Task 9: Node 06 chạy từng task (HyDE), trả `transformed_queries`

**Description:** `transform_query(agent, task, *, confirmed_metadata, history) -> list[str]` (1 phần tử — HyDE; không còn mode PROCEDURE/DOCUMENT, xem AD5); giữ nguyên `append_recent_history`, `extract_standalone_question`, việc gộp `confirmed_metadata`. `_run_advisory_flow` nhận **toàn bộ** `advisory_tasks`, chạy node 06 cho từng task song song (`asyncio.gather`), nối kết quả theo thứ tự task. Node 08 nhận `list[str]` (1 phần tử thì hành vi như cũ). Task MULTI ở bước này tạm chạy HyDE như SINGLE (decomposer là Task 10).

**Acceptance criteria:**
- [ ] 1 task SINGLE trả đúng 1 query, input LLM y như trước khi đổi (không có dòng chỉ dẫn theo mode)
- [ ] 2 task advisory → 2 lời gọi HyDE chạy song song, 2 query theo đúng thứ tự task
- [ ] 1 task: `resolved_query` truyền sang Generation vẫn như trước; 2+ task: `resolved_query = None`
- [ ] `agents/hyde_generator.yaml` không đổi

**Verification:**
- [ ] `pytest tests/graph/test_query_transformation_node.py tests/e2e`

**Dependencies:** Task 4, Task 7

**Files likely touched:** `app/graph/nodes/query_transformation.py`, `app/graph/nodes/retrieval_filtering.py`, `app/graph/streaming_graph.py`, tests

**Estimated scope:** Medium

---

### Task 10: Decomposer cho task MULTI + node 08 fan-out + khung `chat_multi_intent_synthesis` + `origin_tasks`

**Description:** Theo AD6, AD9:
- Task MULTI: agent decomposer (`agent_multi_query_decomposer`) nhận `task.query` + lịch sử gần đây; **không chạy HyDE**. Parse `{"sub_queries": [...]}`, tối đa 3; rỗng / sai JSON / chỉ 1 → task đó rơi về `SINGLE` (HyDE).
- Query của mọi task nối thành `SQ1..SQn` theo thứ tự task.
- Node 08: quota `ceil(RETRIEVAL_MAX_CHUNKS / n)` mỗi sub-query, pre-filter mỗi lần; gộp theo `chunk_id` giữ điểm cao hơn, bằng điểm giữ sub-query đứng trước; sort ổn định, cắt `RETRIEVAL_MAX_CHUNKS`.
- Builder: tách `_base_params(...)`, thêm `build_multi_intent_prompt(..., sub_queries)` render `SQk. ...` (task HyDE ghi `task.query`); Generation chọn khung theo AD9.
- `PendingClarification.origin_tasks: list[ClassifiedTask] | None` (mặc định `None`) = các task nhánh 06 của lượt gốc; resume chạy lại đúng các task đó. `pending_sub_query_id` lấy từ `sub_query_id` của `ask_user_form` nếu thuộc `SQ1..SQn` (n = tổng số sub-query của lượt đó), ngược lại `None`; lượt chỉ có 1 sub-query luôn `None`.

**Acceptance criteria:**
- [ ] Câu so sánh 2 ngành → 2-3 sub-query → context có chunk của **mỗi** ngành, không trùng chunk
- [ ] 2 task advisory khác chủ đề → context có chunk của **mỗi** task
- [ ] Prompt multi có `SQ1. ...`, `SQ2. ...`
- [ ] Prompt SINGLE giống trước khi refactor builder, **ngoại trừ** khối `security_access_control` đã được bật ở Task 5 (test so chuỗi với snapshot lấy sau Task 5)
- [ ] Decomposer trả rác hoặc 1 sub-query → task đó trả lời qua HyDE như SINGLE
- [ ] `pending_sub_query_id`:
  - form có 2 sub-query và `sub_query_id: "SQ2"` → `pending_sub_query_id == "SQ2"`, lưu qua `clarification_state` repository rồi đọc lại vẫn là `"SQ2"`
  - `"SQ5"` (ngoài `SQ1..SQ2`), `"abc"`, hoặc không có khoá → `None`
  - lượt chỉ có 1 sub-query có `sub_query_id` trong form → vẫn `None`
- [ ] `origin_tasks` của lượt nhiều task được lưu và đọc lại đúng; resume chạy lại đúng các task đó, đúng mode
- [ ] Hàng JSONB cũ (không có `origin_tasks`, `pending_sub_query_id: null`) vẫn `model_validate` được, resume chạy 1 task `SINGLE` như trước

**Verification:**
- [ ] `pytest tests/graph tests/rag tests/e2e tests/database`

**Dependencies:** Task 5, Task 9

**Files likely touched:** `app/graph/nodes/query_transformation.py`, `app/graph/nodes/retrieval_filtering.py`, `app/rag/prompting/{__init__,builder}.py`, `app/graph/nodes/generation_synthesis.py`, `app/schemas/clarification.py`, `app/graph/streaming_graph.py`, tests

**Estimated scope:** Large → tách khi làm: **10a** decomposer + fan-out node 08 (3 file); **10b** builder + khung multi + `origin_tasks` (4-5 file)

---

### Task 11: Node 11 TicketFallback gọi LLM, xoá `ui_buttons`

**Description:** Builder `build_ticket_fallback_prompt(user_query, security, confirmed_metadata, history)` lắp `chat_ticket_fallback` + `ticket_fallback` + `security_access_control` (không có chunk, `task_1`, `task_2`). Node stream câu trả lời qua `stream_agent_text` với `models.generation`. Xoá `TicketFallbackResponse`, `ui_buttons` và payload dựng sẵn (AD10). Theo AD14: fallback chỉ thay cho node 10 khi toàn bộ chunk đã gộp của nhánh 06 không vượt ngưỡng; lượt ghép vẫn nối placeholder tính toán sau câu fallback.

**Acceptance criteria:**
- [ ] `has_valid_context = False` → text trả về là output LLM (stream từng token), `used_ticket_fallback = True`
- [ ] Prompt không chứa **block dữ liệu** truy xuất: không có `{prepared_context}` được render và không có nội dung chunk nào (test: dựng chunk có chuỗi đánh dấu duy nhất, assert chuỗi đó không có trong prompt). Không assert trên tag `<academic_context>`, vì khối `security_access_control` hợp lệ có nhắc tên tag này
- [ ] Prompt có khối "Quy Tắc Bảo Mật & Phân Quyền Thông Tin"
- [ ] Lượt ghép tính toán + thủ tục mà nhánh 06 không có chunk → câu fallback + placeholder ở cuối
- [ ] `grep -rniE "ui_buttons|OPEN_TICKET_MODAL" app tests` không còn kết quả

**Verification:**
- [ ] `pytest tests/graph/test_ticket_fallback_node.py tests/e2e`

**Dependencies:** Task 1, Task 3, Task 5, Task 8

**Files likely touched:** `app/graph/nodes/ticket_fallback.py`, `app/rag/prompting/{__init__,builder}.py`, `app/graph/streaming_graph.py`, `tests/graph/test_ticket_fallback_node.py`

**Estimated scope:** Small

### Checkpoint: Complete
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] E2E: advisory SINGLE, so sánh (MULTI), 2 câu hỏi học vụ khác chủ đề, tính toán + thủ tục, chỉ tính toán, ticket fallback, khách vãng lai
- [ ] Review với người dùng

---

### Task 12: Ghi known-gaps

**Description:** Trong `docs/specs/known-gaps.md`: đổi tiêu đề thành "Known Gaps", nhóm mục cũ dưới `## Ingestion`, thêm `## Graph / Retrieval` theo draft trong `plan.md` (BM25, RRF, cross-encoder, nén ngữ cảnh, rủi ro ngưỡng cosine, CalculationNode placeholder, resume MULTI, hỏi lại từ hai nhánh cùng lúc, tối đa 3 task, kết quả tính toán chưa vào prompt). Cập nhật `docs/product/PRODUCT.md`/`DECISIONS.md` nếu còn câu nào mô tả "mỗi lượt một nhánh".

**Acceptance criteria:**
- [ ] Mỗi gap nêu: design nói gì, code làm gì, vì sao không làm, khi nào nên làm lại
- [ ] Mục Ingestion cũ giữ nguyên nội dung, chỉ đổi cấp heading

**Verification:**
- [ ] Người dùng review nội dung

**Dependencies:** Task 11

**Files likely touched:** `docs/specs/known-gaps.md`, `docs/product/PRODUCT.md`, `docs/product/DECISIONS.md`

**Estimated scope:** XS
