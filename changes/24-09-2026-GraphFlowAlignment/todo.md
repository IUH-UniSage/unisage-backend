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
- [ ] `get_templates()` có đủ 6 field mới
- [ ] `git diff` không chạm file YAML nào đã có sẵn (`agents/hyde_generator`, `agents/message_classification`, mọi `common/*`, `main/*` cũ)
- [ ] `ticket_fallback.yaml` / `chat_ticket_fallback.yaml` không còn `rerank_score` và không còn câu "nhấn nút"
- [ ] Test loader khẳng định agent template mới giữ nguyên dấu `{`/`}` của JSON mẫu

**Verification:**
- [ ] `pytest tests/rag/test_prompt_loader.py`
- [ ] Bộ lệnh kiểm tra chung pass

**Dependencies:** None

**Files likely touched:** `app/rag/prompting/schema.py`, `app/rag/prompting/loader.py`, 6 file YAML mới, `tests/rag/test_prompt_loader.py`

**Estimated scope:** Medium

---

### Task 2: Xác minh baseline DirectLLM đã được gỡ hết

**Description:** Commit `1fef03f` đã gỡ DirectLLMNode. Task này chỉ xác minh, không xoá gì thêm. Nếu còn sót thì dọn: hiện chỉ còn 1 comment nhắc `general_knowledge/DirectLLMNode` ở `tests/e2e/test_advisory_flow_e2e.py:233` → viết lại comment cho khớp flow hiện tại.

**Acceptance criteria:**
- [ ] `grep -rniE "direct_llm|DirectLLM|chat_direct_llm|general_knowledge" app tests` không còn kết quả (trừ nơi cố ý nói nhãn cũ bị map sang fallback, nếu có, kèm lý do)
- [ ] `GraphModels` chỉ còn `classification`, `query_transformation`, `generation`, `retrieval`

**Verification:**
- [ ] Chạy grep trên; `pytest tests/e2e`

**Dependencies:** None

**Files likely touched:** `tests/e2e/test_advisory_flow_e2e.py` (chỉ comment)

**Estimated scope:** XS

---

### Task 3: Đổi tên trace theo số node mới

**Description:** Trong `app/graph/streaming_graph.py`: `05B_SocialChat` → `04_IntentRouting_SocialChat` (END template, không phải node riêng), `05B_OffTopicRejectNode` → `05_OffTopicRejectNode`, `10_RetrievalFilteringNode` → `08_`, `11_PostRetrievalRerankNode` → `09_`, `12_GenerationSynthesisNode` → `10_`, `13_TicketFallbackNode` → `11_`. Sửa comment "Node 10/11/12/13" trong cùng file, `# Retrieval / rerank (nodes 10/11)` trong `app/core/config.py` + `.env.example`, và "node 12" trong docstring `app/schemas/clarification.py`.

**Acceptance criteria:**
- [ ] Trace một lượt advisory: `01, 02, 03, 04, 06, 08, 09, 10`
- [ ] `grep -rnE "05B_|10_Retrieval|11_PostRetrieval|12_Generation|13_Ticket" app tests` không còn kết quả

**Verification:**
- [ ] `pytest tests/test_graph_trace.py tests/graph/test_graph_wiring.py tests/e2e`

**Dependencies:** None

**Files likely touched:** `app/graph/streaming_graph.py`, `app/core/config.py`, `.env.example`, `app/schemas/clarification.py`, `tests/test_graph_trace.py`

**Estimated scope:** Small

### Checkpoint: Foundation
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] Luồng advisory (e2e) chạy như trước

---

## Phase 2: Phân quyền + Classification + Routing

### Task 4: Pre-filter phân quyền ở node 08 + payload index

**Description:** Thêm hàm thuần `build_access_filter(security: AcademicSecurityContext) -> models.Filter` theo AD7 (gồm vế wildcard `*`). `search_chunks` nhận `query_filter` và truyền vào mọi `query_points` (cả 3 named vector). `RetrievalServiceProtocol.retrieve(query, *, security, limit=None)`; node 08 truyền `graph_input.security`. `ensure_collection` tạo payload index `department` (keyword) + `access_level` (integer) khi tạo collection mới (AD8; không xử lý collection cũ). Cập nhật docstring "Deliberately does NOT filter by permission yet" và các `FakeRetrievalService` trong test.

**Acceptance criteria:**
- [ ] Khách chỉ nhận chunk `access_level == 0`, mọi department
- [ ] `[{KHOA_CNTT, 2}]` nhận: mọi chunk cấp 0 + chunk KHOA_CNTT cấp 1, 2; không nhận KHOA_CNTT cấp 3 hay chunk department khác cấp ≥ 1
- [ ] Nhiều department với mức khác nhau: mỗi department dùng đúng mức của nó
- [ ] Wildcard: `[{*, 2}]` thấy chunk mọi department cấp ≤ 2, không thấy cấp 3; `[{*, 1}, {KHOA_CNTT, 3}]` thấy KHOA_CNTT cấp 3 nhưng department khác chỉ tới cấp 1
- [ ] `confirmed_metadata` chứa giá trị leo thang → filter sinh ra không đổi
- [ ] Collection mới tạo có 2 payload index

**Verification:**
- [ ] Unit test cho `build_access_filter`
- [ ] `pytest tests/test_retrieval.py tests/test_qdrant_store.py tests/graph tests/api`

**Dependencies:** Task 3

**Files likely touched:** `app/rag/vectorstore/qdrant_store.py`, `app/rag/retrieval/service.py`, `app/graph/nodes/retrieval_filtering.py`, `app/graph/streaming_graph.py`, tests

**Estimated scope:** Medium

---

### Task 5: `security_access_control` theo `department_access` và bật trong prompt

**Description:** Sửa `common/security_access_control.yaml` (đã được người dùng duyệt, AD13), chỉ đúng các chỗ thuật ngữ phân quyền, không đổi nội dung khác:
- Mục 1: "đã qua bộ lọc phân quyền theo `max_access_level` và `organization_scopes`" → theo `department_access` (mỗi phòng ban kèm `access_level` được cấp); tài liệu `access_level = 0` là công khai, ai cũng đọc được.
- Mục 2: "khoa khác với `organization_scopes`" → "phòng ban/khoa không có trong `department_access`"; "`role` và `max_access_level` hiện có" → "`role` và `department_access` hiện có".
- Mục 4: danh sách cấm nhắc `max_access_level`, `organization_scopes` → `access_level`, `department_access`.

Rồi bỏ `_SECURITY_ACCESS_CONTROL_DEFERRED` trong `app/rag/prompting/__init__.py`, truyền `templates.security_access_control` vào mọi khung có placeholder này.

**Acceptance criteria:**
- [ ] `grep -rnE "max_access_level|organization_scopes" app/rag/prompting` không còn kết quả
- [ ] Prompt advisory render ra có khối "Quy Tắc Bảo Mật & Phân Quyền Thông Tin"
- [ ] `git diff` của yaml chỉ chạm các dòng thuật ngữ ở trên

**Verification:**
- [ ] `pytest tests/rag tests/graph/test_generation_synthesis_node.py`

**Dependencies:** Task 4

**Files likely touched:** `app/rag/prompting/prompt_templates/common/security_access_control.yaml`, `app/rag/prompting/__init__.py`, `tests/rag/test_prompt_loader.py`

**Estimated scope:** Small

---

### Task 6: Node 03 trả thêm `routing_mode`

**Description:** Theo AD2 (đã chốt): sửa phần `## Output` của `agents/message_classification.yaml` sang đúng Output Contract của design `{"primary_intent", "secondary_intents", "confidence", "routing_mode"}` + 1-2 ví dụ (câu so sánh → `routing_mode: "MULTI"`); taxonomy và rule giữ nguyên. Thêm model `IntentClassification` (`app/schemas/intent.py`) với đúng 4 field đó; `classify_intent` parse JSON (chịu được code fence), trả `IntentClassification`. Thiếu `secondary_intents`/`confidence` → `[]`/`None`; JSON hỏng, thiếu hoặc nhãn lạ ở `primary_intent` → `academic_advisory` + `SINGLE`.

**Acceptance criteria:**
- [ ] JSON đủ 4 field theo design → parse đúng cả 4; JSON thiếu `secondary_intents`/`confidence` vẫn parse được với giá trị mặc định
- [ ] Output rác, nhãn cũ, hoặc nhãn text thuần kiểu cũ → fallback, không raise
- [ ] Taxonomy và rule trong YAML không đổi (`git diff` chỉ chạm phần Output/ví dụ)

**Verification:**
- [ ] `pytest tests/graph/test_message_classification_node.py`

**Dependencies:** Task 1

**Files likely touched:** `app/rag/prompting/prompt_templates/agents/message_classification.yaml`, `app/graph/nodes/message_classification.py`, `app/schemas/intent.py` (mới), `tests/graph/test_message_classification_node.py`

**Estimated scope:** Small

---

### Task 7: Node 04 routing 4 đích + suy ra `mode`

**Description:** `route_intent(classification)` trả `(next_node, mode)` theo AD4. Cập nhật `streaming_graph.py` truyền `mode` xuống `_run_advisory_flow`. Lượt resume clarification dùng `pending.origin_mode` nếu có, ngược lại `SINGLE` (field được thêm ở Task 10; trước đó luôn `SINGLE`).

**Acceptance criteria:**
- [ ] Bảng test đủ 7 intent + `greeting` × `routing_mode` ra đúng `(node, mode)`
- [ ] Bỏ docstring "not implemented yet - degrade to the advisory flow"

**Verification:**
- [ ] `pytest tests/graph/test_intent_routing_node.py tests/graph/test_graph_wiring.py`

**Dependencies:** Task 6

**Files likely touched:** `app/graph/nodes/intent_routing.py`, `app/graph/streaming_graph.py`, tests routing/wiring

**Estimated scope:** Small

---

### Task 8: Node 07 CalculationNode placeholder

**Description:** `app/graph/nodes/calculation.py` với `CALCULATION_PLACEHOLDER_TEMPLATE`; graph stream template rồi kết thúc lượt, trace `07_CalculationNode`. Không gọi LLM, không dùng `calculation_extractor.yaml` (AD11).

**Acceptance criteria:**
- [ ] Intent `academic_calculation` → trả placeholder, không chạy retrieval
- [ ] `confirmed_metadata`/`pending_clarification` được giữ nguyên qua lượt

**Verification:**
- [ ] Test mới trong `tests/graph/`; `pytest tests/graph`

**Dependencies:** Task 7

**Files likely touched:** `app/graph/nodes/calculation.py` (mới), `app/graph/streaming_graph.py`, `tests/graph/test_calculation_node.py` (mới)

**Estimated scope:** Small

### Checkpoint: Routing + Security
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] Mỗi intent đi đúng nhánh trong ảnh flow_design
- [ ] Khách không đọc được chunk `access_level > 0`
- [ ] Prompt advisory có khối `security_access_control` nói theo `department_access`
- [ ] Review với người dùng trước Phase 3

---

## Phase 3: Query Transformation + Fallback

### Task 9: Node 06 mode PROCEDURE/DOCUMENT, trả `transformed_queries`

**Description:** `transform_query(agent, query, *, mode, confirmed_metadata, history) -> list[str]` (1 phần tử cho SINGLE/PROCEDURE/DOCUMENT). PROCEDURE/DOCUMENT nối 1 dòng chỉ dẫn vào input (AD5); giữ nguyên `append_recent_history`, `extract_standalone_question`, việc gộp `confirmed_metadata`. Node 08 nhận `list[str]` (1 phần tử thì hành vi như cũ).

**Acceptance criteria:**
- [ ] 3 mode đều trả đúng 1 query; input LLM của PROCEDURE/DOCUMENT có dòng chỉ dẫn tương ứng, SINGLE thì không
- [ ] `resolved_query` truyền sang Generation vẫn như trước
- [ ] `agents/hyde_generator.yaml` không đổi

**Verification:**
- [ ] `pytest tests/graph/test_query_transformation_node.py tests/e2e`

**Dependencies:** Task 4, Task 7

**Files likely touched:** `app/graph/nodes/query_transformation.py`, `app/graph/nodes/retrieval_filtering.py`, `app/graph/streaming_graph.py`, tests

**Estimated scope:** Medium

---

### Task 10: Node 06 Multi-query + node 08 fan-out + khung `chat_multi_intent_synthesis`

**Description:** Theo AD6:
- Mode MULTI: agent decomposer (`agent_multi_query_decomposer`) nhận câu hỏi thật + lịch sử gần đây; **không chạy HyDE**. Parse `{"sub_queries": [...]}`, tối đa 3; rỗng / sai JSON / chỉ 1 → fallback `SINGLE` (HyDE).
- Node 08: quota `ceil(RETRIEVAL_MAX_CHUNKS / n)` mỗi sub-query, pre-filter mỗi lần; gộp theo `chunk_id` giữ điểm cao hơn, bằng điểm giữ sub-query đứng trước; sort ổn định, cắt `RETRIEVAL_MAX_CHUNKS`.
- Builder: tách `_base_params(...)`, thêm `build_multi_intent_prompt(..., sub_queries)` render `SQ1. ...`; Generation chọn khung theo mode (AD9).
- `PendingClarification.origin_mode` (tuỳ chọn, mặc định `None`); `pending_sub_query_id` lấy từ `sub_query_id` của `ask_user_form` nếu thuộc `SQ1..SQn` (n = số sub-query của lượt đó), ngược lại `None`. Luồng không phải MULTI luôn `None`.

**Acceptance criteria:**
- [ ] Câu so sánh 2 ngành → 2-3 sub-query → context có chunk của **mỗi** ngành, không trùng chunk
- [ ] Prompt MULTI có `SQ1. ...`, `SQ2. ...`
- [ ] Prompt SINGLE giống trước khi refactor builder, **ngoại trừ** khối `security_access_control` đã được bật ở Task 5 (test so chuỗi với snapshot lấy sau Task 5)
- [ ] Decomposer trả rác hoặc 1 sub-query → trả lời qua HyDE như SINGLE
- [ ] `pending_sub_query_id`:
  - form MULTI 2 sub-query có `sub_query_id: "SQ2"` → `pending_sub_query_id == "SQ2"`, lưu qua `clarification_state` repository rồi đọc lại vẫn là `"SQ2"`
  - `"SQ5"` (ngoài `SQ1..SQ2`), `"abc"`, hoặc không có khoá → `None`
  - luồng SINGLE có `sub_query_id` trong form → vẫn `None`
- [ ] `origin_mode` của lượt MULTI được lưu và đọc lại là `MULTI`; resume lượt đó chạy lại ở mode MULTI
- [ ] Hàng JSONB cũ (không có `origin_mode`, `pending_sub_query_id: null`) vẫn `model_validate` được, resume chạy mode `SINGLE` như trước

**Verification:**
- [ ] `pytest tests/graph tests/rag tests/e2e tests/database`

**Dependencies:** Task 5, Task 9

**Files likely touched:** `app/graph/nodes/query_transformation.py`, `app/graph/nodes/retrieval_filtering.py`, `app/rag/prompting/{__init__,builder}.py`, `app/graph/nodes/generation_synthesis.py`, `app/schemas/clarification.py`, tests

**Estimated scope:** Large → tách khi làm: **10a** decomposer + fan-out node 08 (3 file); **10b** builder + khung MULTI + `origin_mode` (4 file)

---

### Task 11: Node 11 TicketFallback gọi LLM, xoá `ui_buttons`

**Description:** Builder `build_ticket_fallback_prompt(user_query, security, confirmed_metadata, history)` lắp `chat_ticket_fallback` + `ticket_fallback` + `security_access_control` (không có chunk, `task_1`, `task_2`). Node stream câu trả lời qua `stream_agent_text` với `models.generation`. Xoá `TicketFallbackResponse`, `ui_buttons` và payload dựng sẵn (AD10).

**Acceptance criteria:**
- [ ] `has_valid_context = False` → text trả về là output LLM (stream từng token), `used_ticket_fallback = True`
- [ ] Prompt không chứa **block dữ liệu** truy xuất: không có `{prepared_context}` được render và không có nội dung chunk nào (test: dựng chunk có chuỗi đánh dấu duy nhất, assert chuỗi đó không có trong prompt). Không assert trên tag `<academic_context>`, vì khối `security_access_control` hợp lệ có nhắc tên tag này
- [ ] Prompt có khối "Quy Tắc Bảo Mật & Phân Quyền Thông Tin"
- [ ] `grep -rniE "ui_buttons|OPEN_TICKET_MODAL" app tests` không còn kết quả

**Verification:**
- [ ] `pytest tests/graph/test_ticket_fallback_node.py tests/e2e`

**Dependencies:** Task 1, Task 3, Task 5

**Files likely touched:** `app/graph/nodes/ticket_fallback.py`, `app/rag/prompting/{__init__,builder}.py`, `app/graph/streaming_graph.py`, `tests/graph/test_ticket_fallback_node.py`

**Estimated scope:** Small

### Checkpoint: Complete
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] E2E: advisory SINGLE, PROCEDURE, MULTI (so sánh), calculation placeholder, ticket fallback, khách vãng lai
- [ ] Review với người dùng

---

### Task 12: Ghi known-gaps

**Description:** Trong `docs/specs/known-gaps.md`: đổi tiêu đề thành "Known Gaps", nhóm mục cũ dưới `## Ingestion`, thêm `## Graph / Retrieval` theo draft trong `plan.md` (BM25, RRF, cross-encoder, nén ngữ cảnh, rủi ro ngưỡng cosine 0.70, CalculationNode placeholder, resume MULTI).

**Acceptance criteria:**
- [ ] Mỗi gap nêu: design nói gì, code làm gì, vì sao không làm, khi nào nên làm lại
- [ ] Mục Ingestion cũ giữ nguyên nội dung, chỉ đổi cấp heading

**Verification:**
- [ ] Người dùng review nội dung

**Dependencies:** Task 11

**Files likely touched:** `docs/specs/known-gaps.md`

**Estimated scope:** XS
