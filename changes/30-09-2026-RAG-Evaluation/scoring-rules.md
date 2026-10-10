# Quy tắc chấm điểm theo nhóm câu hỏi

Tài liệu này quy định **một câu trả lời thế nào là đạt** cho từng nhóm câu hỏi trong
`dataset/official/questions.jsonl`. Đây là căn cứ để viết `evals/metrics.py`, `evals/judge.py` và để
đọc `report.md`. Định nghĩa từng chỉ số (#1–#15) xem `spec.md` §4; cách xử lý web search xem
`spec.md` §12.8.

## 1. Quy tắc chung

### 1.1. Đơn vị chấm

Một dòng trong `questions.jsonl` (một câu hỏi + một persona) là một lượt chấm. Riêng nhóm `access`,
nhiều dòng dùng chung một câu gốc với persona khác nhau. Khi tính khoảng tin cậy, các dòng này được
gộp theo câu gốc (bootstrap theo cụm), vì chúng không độc lập.

### 1.2. Kết cục (`outcome`) của một lượt

Runner suy ra kết cục từ danh sách node trong `GraphTrace` và các cờ của state:

| `outcome` | Điều kiện |
|---|---|
| `social` | Có `01_GreetingDetectionNode` hoặc `04_IntentRouting_SocialChat`, không có `08_RetrievalFilteringNode` |
| `off_topic` | Có `05_OffTopicRejectNode`, không có `08_RetrievalFilteringNode` |
| `calculation` | Có `07_CalculationNode` |
| `rag` | Có `08_RetrievalFilteringNode`, câu trả lời dùng ít nhất một chunk, `used_web_search=false`, `used_ticket_fallback=false` |
| `web` | `used_web_search=true` và câu trả lời có ít nhất một citation `WEB` |
| `ticket` | `used_ticket_fallback=true` |
| `error` | Graph ném lỗi hoặc hết thời gian chờ |

Một câu nhiều ý có thể có nhiều kết cục (vd ý 1 `calculation`, ý 2 `rag`). Khi đó ghi danh sách và
chấm từng ý theo nhóm của ý đó.

### 1.3. Ngưỡng đạt của điểm RAGAS

Điểm RAGAS nằm trong khoảng 0–1. **Một metric được tính là đạt khi điểm ≥ 0,7.** Đây là quy ước ban đầu,
sẽ chỉnh lại sau khi đo độ đồng thuận với người chấm (Task 14). Nếu đổi ngưỡng thì ghi vào report.
Metric trả về `NaN` (giám khảo lỗi) thì dòng đó **không tính** vào metric ấy, report ghi số dòng
`NaN`; dòng có `NaN` ở metric bắt buộc thì cho vào hàng đợi duyệt tay (§4).

### 1.4. Hai chế độ chạy

- **`web=off`** (`CHAT_WEB_SEARCH_ENABLED=false`): **số liệu chính** của báo cáo. Mọi quy tắc trong
  §2 viết cho chế độ này, trừ chỗ ghi rõ `web=on`.
- **`web=on`**: số liệu phụ, chỉ báo #13–#15 và chênh lệch so với `web=off` theo cặp trên cùng câu.

### 1.5. Rò rỉ quyền (leakage) luôn tính trên dữ liệu truy xuất

Một dòng **có rò rỉ** khi persona không đủ quyền với một tài liệu private mà tài liệu đó vẫn xuất
hiện trong chunk sau rerank, hoặc trong citation `documentId` của câu trả lời. Không chấm rò rỉ trên
nội dung câu trả lời, vì nội dung có thể đến từ web công khai (§12.8). Rò rỉ áp dụng cho **mọi
nhóm**, không chỉ nhóm `access`.

## 2. Quy tắc theo nhóm

Bảng tóm tắt, chi tiết ở các mục bên dưới.

| Nhóm | Số dòng | `outcome` đạt (`web=off`) | Điều kiện nội dung |
|---|---|---|---|
| `normal` | 178 | `rag` | Truy xuất đúng tài liệu; faithful; đúng đáp án |
| `calculation` | 31 | `calculation` | Kết quả số khớp; không hỏi lại số đã có trong câu |
| `access`, được thấy | 86 | như nhóm của `expected_intent` | Như `normal` / `calculation` |
| `access`, không được thấy | 102 | `ticket` hoặc từ chối | Không rò rỉ |
| `unanswerable` | 50 | `ticket` hoặc từ chối | Không bịa chi tiết |
| `off_topic` | 26 | `off_topic` | Từ chối lịch sự, không truy xuất |
| `social` | 18 | `social` | Không truy xuất |
| `web_search` | 6 | chỉ chấm khi `web=on`: `web` | Có citation `iuh.edu.vn` |

### 2.1. `normal`: hỏi đáp thường

Gồm các `case`: `normal_guest`, `advisory_single`, `advisory_multi`, `advisory_compare` và câu không
ghi `case` (bộ gốc).

**Đạt khi thỏa tất cả các điều kiện sau:**
1. `outcome = rag`.
2. Intent đúng: đi qua nhánh RAG, không bị xếp vào `off_topic` / `social` / `calculation`.
3. **Recall@5 (#1):** ít nhất một `expected_doc_ids` nằm trong top 5 sau rerank.
4. **Faithfulness (#2) ≥ 0,7**.
5. **FactualCorrectness (#3) ≥ 0,7** so với `expected_answer`.
6. Không từ chối nhầm: `AspectCritic` "có từ chối không" = không.

**Đo thêm, không tính vào đạt/không đạt:** `LLMContextRecall`, `ResponseRelevancy`,
`IDBasedContextPrecision/Recall` (#3b–#3d), độ chính xác citation (#7), latency (#6).

**Quy tắc riêng theo `case`:**
- `advisory_multi`: câu có nhiều ý. FactualCorrectness tính trên toàn bộ đáp án, nên thiếu một ý sẽ bị
  trừ điểm. Ngoài ra báo tỷ lệ trả lời **đủ mọi ý**: đếm số ý trong `expected_answer` (đánh số
  `(1)`, `(2)`…) và kiểm tra bằng `AspectCritic` "câu trả lời có trả lời ý (k) không". Nếu
  `expected_doc_ids` có nhiều tài liệu thì báo thêm Recall@5 **toàn bộ** (mọi tài liệu đều được truy
  xuất).
- `advisory_compare`: đạt thêm điều kiện câu trả lời nêu **cả hai vế** so sánh và kết luận chênh lệch
  đúng (`AspectCritic`).
- `routing_mode` (`SINGLE` / `MULTI`) và `sub_question_count`: so với nhánh thực tế nếu trace ghi được.
  Chỉ báo tham khảo, vì mỗi nhánh có dưới 20 câu.

**Lỗi điển hình cần liệt kê trong report:** truy xuất trượt (Recall@5 = 0), trả lời không bám
context (Faithfulness thấp nhưng Recall đạt), trả lời sai dù truy xuất đúng, từ chối nhầm.

### 2.2. `calculation`: câu cần tính toán

Có hai đường tính:
- **Python:** 3 công thức cài sẵn (`grade_conversion`, `course_score`, `gpa`).
- **LLM tự tính:** dùng số liệu trong tài liệu (tổng tín chỉ, học phí, số ngày…), có nhãn "AI tự
  tính, có thể sai".

Câu trong bộ đánh giá đều tự chứa đủ số liệu hoặc lấy được số liệu từ tài liệu.

**Đạt khi thỏa tất cả các điều kiện sau:**
1. `outcome = calculation` (đi qua `07_CalculationNode`).
2. **Đúng số:** mọi giá trị trong `expected_numbers` đều xuất hiện trong câu trả lời, sau khi chuẩn hóa
   (bỏ dấu phân cách hàng nghìn, `,` ↔ `.` thập phân, bỏ đơn vị). Số so khớp **chính xác** tới số chữ
   số thập phân của đáp án, vì quy tắc làm tròn đã chốt (`SPEC-calc-engine.md`: 0,1 cho điểm học phần,
   2 chữ số cho GPA). Lệch do làm tròn khác quy chế tính là **sai**.
3. **Không hỏi lại thừa:** câu đã cho đủ số mà graph vẫn trả form `ask_user_form` thì **không đạt**.
   Báo riêng tỷ lệ này. Câu thật sự thiếu số liệu thì không thuộc nhóm này.
4. Nếu số liệu lấy từ tài liệu: Recall@5 đạt và có citation tới tài liệu đó.

**Đo thêm:** FactualCorrectness, Faithfulness, tỷ lệ đi đường Python và đường LLM. Report tách
độ chính xác theo hai đường, vì đường LLM được phép sai và có nút phản hồi Đúng/Sai.

**Cần bổ sung dữ liệu (Task 8b):** thêm trường `expected_numbers` (danh sách số kết quả cuối, vd
`[8, 7]` cho câu "tổng 8 tín chỉ, còn đăng ký thêm 7"). Hiện kết quả chỉ nằm trong câu chữ của
`expected_answer`. Khi chưa có trường này thì tạm lấy số cuối cùng của mỗi phép `= …` trong
`expected_answer` và đưa vào hàng đợi duyệt.

### 2.3. `access`: kiểm tra phân quyền

Mỗi câu gốc trên tài liệu private có 4 persona, cộng thêm `guest` và `access_public_cross`. Chấm theo
`expect_visible`.

**`expect_visible = true`** (đúng mức, wildcard `*`, tài liệu public xem từ department khác):
- Chấm như nhóm ứng với `expected_intent`: `academic_advisory` thì theo §2.1, `academic_calculation`
  thì theo §2.2.
- Thêm **over-restriction (#5):** Recall@5 = 0 với persona đủ quyền, trong khi persona `*` cùng câu
  lại truy xuất được, thì tính là chặn nhầm (lỗi bộ lọc, không phải lỗi truy xuất).

**`expect_visible = false`** (khách, thiếu 1 bậc, department khác):
1. **Không rò rỉ** (§1.5): đây là điều kiện bắt buộc, mục tiêu 0%.
2. `outcome ∈ {ticket}` hoặc câu trả lời từ chối (`AspectCritic` = có).
3. Không có citation `documentId` của tài liệu private.

Một dòng có rò rỉ thì report **liệt kê đầy đủ**: id, persona, `document_id`, mức tài liệu, mức người
hỏi, chunk bị lộ.

**`web=on`:** kết cục `web` cũng đạt, nếu không có chunk hay citation private. Trường hợp trang web
trùng `source_url` của tài liệu private được báo ở #15, không tính là rò rỉ.

**Báo cáo:** tỷ lệ rò rỉ tổng và theo persona, kèm cận trên 3/n khi bằng 0; over-restriction theo
persona. Khoảng tin cậy gộp theo câu gốc (49 câu).

### 2.4. `unanswerable`: không có đáp án trong kho

**Đạt (`web=off`) khi:**
1. `outcome = ticket`, hoặc câu trả lời nói rõ không có thông tin (`AspectCritic` "có từ chối / nói
   không có thông tin không" = có).
2. **Không bịa chi tiết:** câu trả lời không đưa ra con số, ngày, tên cụ thể làm đáp án
   (`AspectCritic` "có khẳng định thông tin cụ thể không" = không). Gợi ý liên hệ phòng ban hoặc tạo
   ticket thì được phép.

**Không đạt:** trả lời như thể có thông tin.

**Ngoại lệ, chuyển duyệt tay thay vì tính lỗi:** câu trả lời có nội dung **và** Faithfulness ≥ 0,7
với chunk truy xuất được. Nghĩa là kho thật ra có đáp án, nên câu hỏi gán nhãn sai. Người duyệt
quyết định đổi nhãn sang `normal` hay xóa câu. Task 8b yêu cầu kiểm tra top 10 truy xuất trước, nên
trường hợp này phải hiếm.

**`web=on`:** đạt nếu `outcome = web`, mọi citation thuộc `iuh.edu.vn`, Faithfulness với nội dung web
≥ 0,7; hoặc `outcome = ticket`. Không chấm độ đúng, vì không có đáp án chuẩn.

### 2.5. `off_topic`: ngoài phạm vi

**Đạt khi:**
1. `outcome = off_topic` (đi qua `05_OffTopicRejectNode`).
2. Không truy xuất (không có `08_RetrievalFilteringNode`) và không gọi web.
3. Câu trả lời từ chối lịch sự, không trả lời nội dung ngoài phạm vi (`AspectCritic`).

**Không đạt:** trả lời câu hỏi (vd dự báo thời tiết), hoặc chạy truy xuất. Chạy truy xuất nghĩa là
tốn chi phí và có thể trả lời lan man.

### 2.6. `social`: chào hỏi, xã giao

**Đạt khi:**
1. `outcome = social`.
2. Không truy xuất, không gọi web, không tạo ticket.

Không chấm giọng văn bằng LLM. Nội dung chào hỏi có mẫu sẵn, chỉ xem tay khi duyệt.

### 2.7. `web_search`: cần thông tin trên web

Câu hỏi về thông tin không có trong kho nhưng có trên website trường (vd điểm chuẩn năm mới).

- **`web=off`:** không tính vào kết quả chính. Ghi nhận `outcome`, kỳ vọng `ticket`.
- **`web=on`:** đạt khi có `09b_WebSearchNode` trong trace và có ít nhất một citation `WEB` thuộc
  `iuh.edu.vn`. **Không chấm nội dung**, vì kết quả web thay đổi theo thời gian (đúng như ghi chú của
  bộ dữ liệu). Chỉ có 6 câu, nên chỉ báo số đạt / tổng, không báo tỷ lệ kèm khoảng tin cậy.

## 3. Gộp thành số liệu trong report

| Số liệu | Tập dòng | Cách tính |
|---|---|---|
| Tỷ lệ đạt theo nhóm | từng nhóm ở §2 | đạt / tổng (không tính dòng `error`), Wilson 95% |
| Tỷ lệ đạt tổng | mọi dòng trừ `web_search` | trung bình có trọng số theo số dòng; ghi kèm bảng theo nhóm |
| Điểm RAGAS trung bình | dòng có đáp án (295) | trung bình + bootstrap 95%; tỷ lệ ≥ 0,7 |
| Recall@5 | dòng có `expected_doc_ids` và persona được thấy | Wilson 95% |
| Leakage | mọi dòng có persona không đủ quyền với ít nhất một tài liệu truy xuất | 0 → cận trên 3/n; gộp theo câu gốc |
| Từ chối đúng / nhầm | `unanswerable` + `access` không thấy / dòng có đáp án | Wilson 95% |
| Intent (#8) | mọi dòng | confusion matrix `expected_intent` × `outcome` |
| Lỗi hệ thống | mọi dòng | số dòng `outcome = error`, báo riêng, không cộng vào tỷ lệ đạt |

Mọi bảng tách thêm theo `source_set` (`official` / `demo`), để thấy chênh lệch giữa hai bộ.

## 4. Hàng đợi duyệt tay

Runner xuất `runs/<ts>/review_queue.csv` gồm các dòng:
- `unanswerable` có nội dung và Faithfulness ≥ 0,7 (nghi gán nhãn sai, §2.4).
- `calculation` chưa có `expected_numbers`, hoặc số gần đúng nhưng lệch ở chữ số cuối.
- Metric bắt buộc trả về `NaN`.
- Recall@5 đạt nhưng FactualCorrectness < 0,3 (nghi đáp án chuẩn sai).
- Mọi dòng có rò rỉ.

Kết quả duyệt (đổi nhãn, xóa câu, sửa đáp án) ghi ngược vào `questions.jsonl` với `reviewed=true`,
rồi chấm lại.

## 5. Việc cần làm để áp dụng quy tắc này

- [ ] Thêm trường `expected_numbers` cho nhóm `calculation` (Task 8b).
- [ ] Đánh số ý `(1)`, `(2)`… trong `expected_answer` của `advisory_multi` nếu còn thiếu.
- [ ] `evals/metrics.py`: hàm thuần `outcome_of(trace, state)` và `passes(row, result)` theo §1.2 và
      §2, có test cho từng nhóm.
- [ ] `evals/judge.py`: định nghĩa các `AspectCritic` dùng ở §2 (từ chối, bịa chi tiết, đủ ý, hai vế
      so sánh, từ chối lịch sự).
- [ ] Chỉnh ngưỡng 0,7 sau Task 14.
