# Todo: Graph Flow Alignment

> **Repo đích:** `unisage-agent`. Đường dẫn file tính từ gốc repo đó.

Lệnh kiểm tra chung (AGENTS.md của `unisage-agent`):

```powershell
.venv\Scripts\python.exe -m pytest
.venv\Scripts\python.exe -m ruff check app tests
.venv\Scripts\python.exe -m ruff format --check app tests
.venv\Scripts\python.exe -m mypy app tests
```

---

## Phase 1: Foundation

### Task 1: Loader + schema load `agents/` và các khung còn thiếu

**Description:** Copy từ `RAG_Graph/KLTN/prompt_template` sang `app/rag/prompting/prompt_templates`: 5 file `agents/*.yaml`, `main/chat_multi_intent_synthesis.yaml`, `main/chat_ticket_fallback.yaml`, `common/ticket_fallback.yaml`. Thêm `{history_message}` vào 2 khung `main/` mới (AD9). Mở rộng `PromptTemplates` và `_load_all_templates`. Agent template load nguyên văn, không `.format()` (AD1). Không sửa file `common/` đã có.

**Acceptance criteria:**
- [ ] `get_templates()` có đủ 5 field agent, 2 khung main mới và `ticket_fallback`
- [ ] Không file `common/*.yaml` có sẵn nào bị thay đổi
- [ ] Test loader khẳng định agent template giữ nguyên dấu `{`/`}` của JSON mẫu

**Verification:**
- [ ] `pytest tests/rag/test_prompt_loader.py`
- [ ] Bộ lệnh kiểm tra chung pass

**Dependencies:** None

**Files likely touched:** `app/rag/prompting/schema.py`, `app/rag/prompting/loader.py`, `app/rag/prompting/prompt_templates/{agents,main,common}/*.yaml` (file mới), `tests/rag/test_prompt_loader.py`

**Estimated scope:** Medium

---

### Task 2: Bỏ DirectLLMNode

**Description:** Xoá `app/graph/nodes/direct_llm.py`, `main/chat_direct_llm.yaml`, `build_direct_llm_prompt`, field `chat_direct_llm` trong schema/loader, `GraphModels.direct_llm` (+ `deps.get_graph_models`), nhánh `DirectLLMNode` trong `streaming_graph.py` và `intent_routing.py`. Tạm map `general_knowledge` → `OffTopicRejectNode` (Task 6 bỏ hẳn nhãn này).

**Acceptance criteria:**
- [ ] `grep -ri "direct_llm\|DirectLLM" app tests` không còn kết quả
- [ ] Tin nhắn kiến thức phổ thông trả template off-topic

**Verification:**
- [ ] Bộ lệnh kiểm tra chung pass (xoá `tests/graph/test_direct_llm_node.py`; sửa `conftest.py`, `llm_mocks.py`, `test_graph_wiring.py`, `test_prompt_loader.py`)

**Dependencies:** Task 1

**Files likely touched:** `app/graph/nodes/direct_llm.py` (xoá), `app/graph/streaming_graph.py`, `app/graph/streaming_state.py`, `app/api/deps.py`, `app/rag/prompting/{__init__,schema,loader}.py` + tests

**Estimated scope:** Medium

---

### Task 3: Đổi tên trace theo số node mới

**Description:** Trong `streaming_graph.py`: `05B_SocialChat` → `04_IntentRouting_SocialChat` (END template, không phải node riêng), `05B_OffTopicRejectNode` → `05_OffTopicRejectNode`, `10_` → `08_`, `11_` → `09_`, `12_` → `10_`, `13_` → `11_`. Sửa comment/docstring còn ghi số cũ (`nodes 10/11` trong config, `node 12` trong clarification schema...).

**Acceptance criteria:**
- [ ] Trace một lượt advisory: `01, 02, 03, 04, 06, 08, 09, 10`
- [ ] Không còn chuỗi trace số cũ trong `app/`

**Verification:**
- [ ] `pytest tests/test_graph_trace.py tests/graph/test_graph_wiring.py tests/e2e`

**Dependencies:** Task 2

**Files likely touched:** `app/graph/streaming_graph.py`, `app/core/config.py`, `app/schemas/clarification.py`, tests trace/wiring

**Estimated scope:** Small

### Checkpoint: Foundation
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] Luồng advisory (e2e) chạy như trước

---

## Phase 2: Phân quyền + Classification + Routing

### Task 4: Pre-filter phân quyền ở node 08

**Description:** Thêm hàm thuần `build_access_filter(security: AcademicSecurityContext) -> models.Filter` theo AD7. `search_chunks` nhận `query_filter` và truyền vào mọi `query_points` (cả 3 named vector). `RetrievalServiceProtocol.retrieve(query, *, security, limit=None)`; node 08 truyền `graph_input.security`. Tạo payload index cho `department` (keyword) và `access_level` (integer) trong `ensure_collection` nếu chưa có. Cập nhật docstring "Deliberately does NOT filter by permission yet" và các `FakeRetrievalService` trong test.

**Acceptance criteria:**
- [ ] Khách chỉ nhận chunk `access_level == 0`, mọi department
- [ ] Người có `[{KHOA_CNTT, 2}]` nhận: mọi chunk cấp 0 + chunk KHOA_CNTT cấp 1, 2; không nhận KHOA_CNTT cấp 3 hay chunk department khác cấp ≥ 1
- [ ] Filter không đọc `confirmed_metadata` (test: `confirmed_metadata` chứa giá trị leo thang → filter không đổi)

**Verification:**
- [ ] Unit test cho `build_access_filter` (khách, 1 department, nhiều department)
- [ ] `pytest tests/test_retrieval.py tests/test_qdrant_store.py tests/graph tests/api`

**Dependencies:** Task 3

**Files likely touched:** `app/rag/vectorstore/qdrant_store.py`, `app/rag/retrieval/service.py`, `app/graph/nodes/retrieval_filtering.py`, `app/graph/streaming_graph.py`, tests

**Estimated scope:** Medium

---

### Task 5: `security_access_control` theo `department_access` và bật trong prompt

**Description:** Sửa `common/security_access_control.yaml` (đã được người dùng duyệt, AD13) — chỉ đúng các chỗ thuật ngữ phân quyền, không đổi nội dung khác:
- Mục 1: "đã qua bộ lọc phân quyền theo `max_access_level` và `organization_scopes`" → theo `department_access` (mỗi phòng ban kèm `access_level` được cấp); tài liệu `access_level = 0` là công khai, ai cũng đọc được.
- Mục 2: "khoa khác với `organization_scopes`" → "phòng ban/khoa không có trong `department_access`"; "`role` và `max_access_level` hiện có" → "`role` và `department_access` hiện có".
- Mục 4: danh sách cấm nhắc `max_access_level`, `organization_scopes` → `access_level`, `department_access`.

Rồi bỏ `_SECURITY_ACCESS_CONTROL_DEFERRED` trong `app/rag/prompting/__init__.py`, truyền `templates.security_access_control` vào mọi khung có placeholder này.

**Acceptance criteria:**
- [ ] `grep -rn "max_access_level\|organization_scopes" app/rag/prompting` không còn kết quả
- [ ] Prompt advisory render ra có khối "Quy Tắc Bảo Mật & Phân Quyền Thông Tin"
- [ ] `git diff` của yaml chỉ chạm các dòng thuật ngữ ở trên

**Verification:**
- [ ] `pytest tests/rag tests/graph/test_generation_synthesis_node.py`

**Dependencies:** Task 4

**Files likely touched:** `app/rag/prompting/prompt_templates/common/security_access_control.yaml`, `app/rag/prompting/__init__.py`, `tests/rag/test_prompt_loader.py`

**Estimated scope:** Small

---

### Task 6: Node 03 dùng `agents/message_classification.yaml`, output JSON 7 intent

**Description:** Thêm model `IntentClassification` (`app/schemas/`), `system_prompt = get_templates().message_classification`. Parse JSON trong text output (chịu được code fence ```json). Nhãn ngoài 7 intent hoặc parse lỗi → `academic_advisory` + `SINGLE` (AD2, AD3).

**Acceptance criteria:**
- [ ] Output JSON hợp lệ → đúng `primary_intent`/`routing_mode`
- [ ] Output rác hoặc nhãn cũ (`general_knowledge`, `academic_comparison`) → fallback, không raise
- [ ] Không còn system prompt viết cứng trong node

**Verification:**
- [ ] `pytest tests/graph/test_message_classification_node.py`

**Dependencies:** Task 1

**Files likely touched:** `app/graph/nodes/message_classification.py`, `app/schemas/intent.py` (mới), `tests/graph/test_message_classification_node.py`

**Estimated scope:** Small

---

### Task 7: Node 04 routing 4 đích + suy ra `mode`

**Description:** `route_intent(classification)` trả `(next_node, mode)`: `social_chat` → END template; `off_topic` → `OffTopicRejectNode`; 4 intent advisory → `QueryTransformationNode` với `mode` theo AD4; `academic_calculation` → `CalculationNode`. Cập nhật `streaming_graph.py` truyền `mode` xuống `_run_advisory_flow`. Resume clarification giữ `SINGLE` như hiện tại.

**Acceptance criteria:**
- [ ] Bảng test đủ 7 intent × `routing_mode` ra đúng `(node, mode)`
- [ ] Bỏ docstring "not implemented yet - degrade to advisory"

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

### Task 9: Node 06 HyDE/Procedure/Document load từ YAML, trả `transformed_queries`

**Description:** `system_prompt = get_templates().hyde_generator`. `transform_query(agent, query, mode, confirmed_metadata) -> list[str]` (1 phần tử cho 3 mode này). PROCEDURE/DOCUMENT thêm 1 dòng chỉ dẫn vào input (AD5). Node 08 nhận `list[str]` (1 phần tử thì hành vi như cũ).

**Acceptance criteria:**
- [ ] 3 mode đều trả đúng 1 query; input LLM của PROCEDURE/DOCUMENT có dòng chỉ dẫn tương ứng
- [ ] Việc gộp `confirmed_metadata` vào query giữ nguyên
- [ ] Không còn system prompt viết cứng trong node

**Verification:**
- [ ] `pytest tests/graph/test_query_transformation_node.py tests/e2e`

**Dependencies:** Task 4, Task 7

**Files likely touched:** `app/graph/nodes/query_transformation.py`, `app/graph/nodes/retrieval_filtering.py`, `app/graph/streaming_graph.py`, tests

**Estimated scope:** Medium

---

### Task 10: Node 06 Multi-query + node 08 fan-out + khung `chat_multi_intent_synthesis`

**Description:** Mode MULTI: agent thứ hai dùng `multi_query_decomposer.yaml`, parse `{"sub_queries": [...]}` (tối đa 3; parse lỗi → fallback 1 query HyDE). Node 08 retrieve từng sub-query (mỗi lần đều áp pre-filter), gộp theo `chunk_id` giữ điểm cao nhất, cắt `RETRIEVAL_MAX_CHUNKS` (AD6). Builder: tách `_base_params(...)`, thêm `build_multi_intent_prompt(..., sub_queries)`; Generation chọn khung theo mode (AD8).

**Acceptance criteria:**
- [ ] Câu so sánh 2 ngành → 2-3 sub-query → context gộp không trùng chunk
- [ ] Prompt MULTI có `{sub_queries_list}` đã điền; prompt SINGLE giống hệt trước khi refactor (test so chuỗi)
- [ ] Decomposer trả rác → vẫn trả lời được qua 1 query

**Verification:**
- [ ] `pytest tests/graph tests/rag tests/e2e`

**Dependencies:** Task 9

**Files likely touched:** `app/graph/nodes/query_transformation.py`, `app/graph/nodes/retrieval_filtering.py`, `app/rag/prompting/{__init__,builder}.py`, `app/graph/nodes/generation_synthesis.py`, tests

**Estimated scope:** Medium (nếu vượt 5 file thì tách "builder + khung MULTI" thành task riêng)

---

### Task 11: Node 11 TicketFallback gọi LLM, xoá `ui_buttons`

**Description:** Builder `build_ticket_fallback_prompt(user_query, security, confirmed_metadata, history)` lắp `chat_ticket_fallback` + `ticket_fallback` (không có chunk, `task_1`, `task_2`). Node stream câu trả lời qua `stream_agent_text` với `models.generation`. Xoá `TicketFallbackResponse`, `ui_buttons` và payload dựng sẵn (AD10).

**Acceptance criteria:**
- [ ] `has_valid_context = False` → text trả về là output LLM (stream từng token), `used_ticket_fallback = True`
- [ ] Prompt không chứa `<academic_context>`
- [ ] `grep -ri "ui_buttons\|OPEN_TICKET_MODAL" app tests` không còn kết quả

**Verification:**
- [ ] `pytest tests/graph/test_ticket_fallback_node.py tests/e2e`

**Dependencies:** Task 1, Task 3

**Files likely touched:** `app/graph/nodes/ticket_fallback.py`, `app/rag/prompting/{__init__,builder}.py`, `app/graph/streaming_graph.py`, `tests/graph/test_ticket_fallback_node.py`

**Estimated scope:** Small

### Checkpoint: Complete
- [ ] Bộ lệnh kiểm tra chung pass
- [ ] E2E: advisory SINGLE, MULTI (so sánh), calculation placeholder, ticket fallback, khách vãng lai
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
