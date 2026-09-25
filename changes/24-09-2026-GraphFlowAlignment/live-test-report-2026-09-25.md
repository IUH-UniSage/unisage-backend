# Kiểm tay thực tế: 3 câu hỏi phức hợp (model thật gpt-4o-mini, Qdrant thật)

> Chạy trực tiếp qua các hàm node thật (`classify_intent`, `plan_route`, `transform_tasks`,
> `retrieve_chunks`, `rerank_chunks`, `run_generation_synthesis`/`run_ticket_fallback`) với
> model `gpt-4o-mini` thật và collection Qdrant `unisage_chunks` thật (server đã được người
> dùng restart, 11 chunk đã ingest từ `Quyet Dinh 1035 - Hoc phi 2025-2026.pdf`). Gọi trực
> tiếp thay vì qua HTTP vì cần đọc output của từng node — log console của server đang chạy
> trong terminal riêng của người dùng, script này không truy cập được. Người dùng test với
> `department_access = [{"*", 3}]` (giả lập sinh viên đã đăng nhập, đủ quyền đọc mọi chunk
> công khai/nội bộ mức ≤ 3) để tránh việc pre-filter phân quyền che mất kết quả — 11 chunk
> hiện có đều thiếu field `is_public` (dữ liệu ingest trước khi field này tồn tại), nên khách
> vãng lai sẽ luôn rơi vào TicketFallback bất kể câu hỏi.
>
> Mục đích chính: xác nhận lại thay đổi AD15 (2026-09-25) — `MessageClassificationNode` chỉ
> tách task theo NHÃN, 2+ câu hỏi cùng nhãn `academic_advisory` luôn gộp 1 task
> `routing_mode = MULTI`, và decomposer ở `QueryTransformationNode` mới là nơi tách sub-query.

---

## Câu 1 — 3 câu hỏi cùng nhãn `academic_advisory` (không so sánh, không tính toán)

**Câu hỏi:**
> "Học phí đại học chính quy khối Công nghệ khóa tuyển sinh 2025-2026 là bao nhiêu, học phí
> hệ liên thông văn bằng 2 khóa 2025-2026 là bao nhiêu, và quyết định mức học phí này có
> hiệu lực từ khi nào?"

### Output `MessageClassificationNode`
```json
{
  "tasks": [
    {
      "intent": "academic_advisory",
      "query": "Học phí đại học chính quy khối Công nghệ khóa tuyển sinh 2025-2026 là bao nhiêu, học phí hệ liên thông văn bằng 2 khóa 2025-2026 là bao nhiêu, và quyết định mức học phí này có hiệu lực từ khi nào?",
      "routing_mode": "MULTI"
    }
  ],
  "confidence": 0.93
}
```
✅ Đúng theo AD15: 3 câu hỏi cùng nhãn `academic_advisory` gộp thành **1 task duy nhất**,
`routing_mode = MULTI`, `query` giữ nguyên văn cả 3 phần — không bị tách thành 3 task riêng
như trước AD15.

### Output `QueryTransformationNode` (decomposer, vì `routing_mode = MULTI`)
```json
[
  "Định mức học phí đại học chính quy khối Công nghệ khóa tuyển sinh 2025-2026",
  "Định mức học phí hệ liên thông văn bằng 2 khóa tuyển sinh 2025-2026",
  "Thời gian hiệu lực của quyết định mức học phí cho các khóa học này"
]
```
✅ Decomposer tách đúng 3 sub-query, mỗi sub-query một ý, đủ ngữ cảnh để tìm riêng — không
chạy HyDE (đúng thiết kế Task 10/AD6).

### Retrieval
6 chunk lấy về, đều từ `Quyet Dinh 1035 - Hoc phi 2025-2026.pdf`, điểm cosine 0.635–0.725 —
phủ đúng cả 3 chủ đề (mức thu khối Công nghệ, mức thu liên thông văn bằng 2, ngày ký quyết
định). `has_valid_context = True` → đi tiếp `GenerationSynthesisNode` (khung
`chat_multi_intent_synthesis`, vì 3 sub-query).

### Câu trả lời cuối (LLM, khung multi-intent)
> ## Học phí đại học chính quy khối Công nghệ khóa tuyển sinh 2025-2026
>
> - Mức thu học phí cho khối Công nghệ là **38.350.000 đồng/năm học** [1].
>
> ## Học phí hệ liên thông văn bằng 2 khóa tuyển sinh 2025-2026
>
> - Mức thu học phí cho hệ liên thông văn bằng 2 là **21.000.000 đồng/năm học** [2].
>
> ## Thời gian hiệu lực của quyết định mức học phí
>
> - Quyết định về mức thu học phí được ban hành theo **Quyết định số 1035/QĐ-ĐHCN** ngày 11
>   tháng 4 năm 2025 và có hiệu lực kể từ ngày ký [5].
>
> Bạn có thể tham khảo các thông tin trên để nắm rõ về mức học phí và quyết định liên quan
> đến việc thu học phí tại trường.

**Nhận xét:** trả lời đủ cả 3 ý, đúng số liệu so với chunk gốc, có trích dẫn `[n]` cho từng ý.

---

## Câu 2 — Trộn 2 nhãn: `academic_calculation` + `academic_advisory` (2 câu hỏi con)

**Câu hỏi:**
> "Tính giúp em học phí phải đóng nếu đăng ký 15 tín chỉ hệ đại học chính quy khối Kinh tế
> khóa 2023-2024, với lại cho em hỏi luôn học phí hệ nghiên cứu sinh khối Công nghệ khóa
> 2025-2026 là bao nhiêu, và điều kiện được giảm 50% học phí ở Phân hiệu Quảng Ngãi là gì?"

### Output `MessageClassificationNode`
```json
{
  "tasks": [
    {
      "intent": "academic_calculation",
      "query": "Tính giúp em học phí phải đóng nếu đăng ký 15 tín chỉ hệ đại học chính quy khối Kinh tế khóa 2023-2024",
      "routing_mode": null
    },
    {
      "intent": "academic_advisory",
      "query": "Học phí hệ nghiên cứu sinh khối Công nghệ khóa 2025-2026 là bao nhiêu, và điều kiện được giảm 50% học phí ở Phân hiệu Quảng Ngãi là gì?",
      "routing_mode": "MULTI"
    }
  ],
  "confidence": 0.92
}
```
✅ Đúng theo AD15: tách **2 task vì khác nhãn** (`academic_calculation` vs `academic_advisory`);
trong đó 2 câu hỏi con cùng nhãn `academic_advisory` (nghiên cứu sinh + Quảng Ngãi) được
**gộp lại thành 1 task `MULTI`** thay vì tách thành 2 task riêng như trước AD15.

### `IntentRoutingNode` (deterministic, không LLM)
Cả `calculation_tasks` (1 phần tử) và `advisory_tasks` (1 phần tử, `MULTI`) đều có → chạy
song song 2 nhánh theo AD14: nhánh 06→…→10 cho phần học vụ, nhánh 07 (placeholder) cho phần
tính toán, nối kết quả ở cuối.

### Output `QueryTransformationNode` (decomposer trên task `MULTI`)
```json
[
  "Định mức học phí cho chương trình nghiên cứu sinh khối Công nghệ khóa 2025-2026",
  "Điều kiện để được giảm 50% học phí tại Phân hiệu Quảng Ngãi"
]
```

### Retrieval
7 chunk, điểm 0.503–0.732, phủ đúng cả 2 chủ đề (nghiên cứu sinh Công nghệ, chính sách giảm
Quảng Ngãi). `has_valid_context = True`.

### Câu trả lời cuối (LLM, khung multi-intent + placeholder tính toán nối cuối)
> ### Thông tin học phí hệ nghiên cứu sinh khối Công nghệ khóa 2025-2026
>
> Mức thu học phí cho chương trình nghiên cứu sinh tại khối Công nghệ khóa tuyển sinh năm
> học 2025-2026 là **60.000.000 đồng/năm học** và **2.000.000 đồng/01 tín chỉ** [2].
>
> ### Điều kiện để được giảm 50% học phí tại Phân hiệu Quảng Ngãi
>
> Theo chính sách thu hút người học của Nhà trường, mức thu học phí đối với các chương trình
> đào tạo tại Phân hiệu Quảng Ngãi sẽ được hỗ trợ **50% trong thời gian đào tạo 02 năm đầu**
> [1].
>
> *(… tóm tắt bảng …)*
>
> Phần tính toán (GPA, tín chỉ, học phí) hiện đang được phát triển nên mình chưa tính giúp
> bạn được. Bạn có thể tự tính theo công thức trong quy chế đào tạo, hoặc liên hệ Phòng Đào
> tạo để được hỗ trợ nhé.

**Nhận xét quan trọng:** câu trả lời **chỉ** đề cập 2 ý học vụ (nghiên cứu sinh + Quảng Ngãi)
— hoàn toàn không tự tính học phí 15 tín chỉ, đúng thiết kế `_run_advisory_flow` (chỉ truyền
phần câu hỏi học vụ vào prompt generation khi lượt có cả task tính toán, xem
`streaming_graph.py`). Placeholder `CalculationNode` được nối đúng ở cuối, tách biệt rõ khỏi
phần trả lời của LLM (không đi qua model).

---

## Câu 3 — Trộn `academic_advisory` (MULTI) + `off_topic`, có xã giao mở đầu

**Câu hỏi:**
> "Cảm ơn bạn đã hỗ trợ hôm qua nha! Cho mình hỏi thêm là học phí hệ đại học chính quy khối
> Kinh tế khóa tuyển sinh 2021-2022 trở về trước là bao nhiêu, học phí này áp dụng theo quyết
> định số mấy, và tiện thể cho mình hỏi luôn giá vàng SJC hôm nay bao nhiêu một lượng?"

### Output `MessageClassificationNode`
```json
{
  "tasks": [
    {
      "intent": "academic_advisory",
      "query": "Học phí hệ đại học chính quy khối Kinh tế khóa tuyển sinh 2021-2022 trở về trước là bao nhiêu, học phí này áp dụng theo quyết định số mấy?",
      "routing_mode": "MULTI"
    },
    {
      "intent": "off_topic",
      "query": "Giá vàng SJC hôm nay bao nhiêu một lượng?",
      "routing_mode": null
    }
  ],
  "confidence": 0.9
}
```
✅ Câu cảm ơn mở đầu bị bỏ hoàn toàn (đúng rule "câu chào/cảm ơn đi kèm câu hỏi học vụ → chỉ
giữ task câu hỏi"); 2 câu hỏi học phí + quyết định số mấy **cùng nhãn `academic_advisory`**
gộp thành 1 task `MULTI` (đúng AD15); câu hỏi giá vàng tách riêng thành task `off_topic`.

### `IntentRoutingNode`
Có task `academic_*` → theo AD4 rule 1, bỏ task `off_topic`, chỉ chạy nhánh học vụ.

### Output `QueryTransformationNode` (decomposer)
```json
[
  "Định mức học phí hệ đại học chính quy khối Kinh tế khóa tuyển sinh 2021-2022 trở về trước",
  "Số quyết định áp dụng cho học phí hệ đại học chính quy khối Kinh tế"
]
```

### Retrieval
6 chunk, điểm 0.612–0.687. `has_valid_context = True`.

### Câu trả lời cuối (LLM)
> Hiện tại, tôi không có thông tin về giá vàng SJC. Tuy nhiên, tôi có thể cung cấp cho bạn
> thông tin về mức học phí của hệ đại học chính quy khối Kinh tế cho khóa tuyển sinh năm học
> 2021-2022 trở về trước.
>
> ### Mức thu học phí hệ đại học chính quy khối Kinh tế (khóa tuyển sinh năm học 2021-2022
> trở về trước):
>
> | Hệ Đào Tạo | Khóa Tuyển Sinh | Mức thu theo năm học |
> | --- | --- | --- |
> | Đại học chính quy | Khối Kinh tế | 23.294.000 [1] |
>
> Quy định về mức học phí này được nêu trong Quyết định số 1035/QĐ-ĐHCN ngày 11 tháng 4 năm
> 2025 của Hiệu trưởng ĐHCN TP.HCM [1].
>
> Nếu bạn cần thêm thông tin hoặc có câu hỏi khác, xin vui lòng cho tôi biết!

**Nhận xét:** khác với câu 2 (nơi phần tính toán bị cắt khỏi `user_query` truyền vào
generation vì `route_plan.calculation_tasks` có giá trị), ở câu này KHÔNG có task tính toán
nên `user_query` truyền vào generation vẫn là **toàn bộ tin nhắn gốc** (gồm cả câu cảm ơn và
câu hỏi giá vàng) — việc bỏ qua phần xã giao/ngoài phạm vi hoàn toàn dựa vào chỉ dẫn trong
prompt (`response_style`/`academic_domain_rules`), không phải do cắt bớt `user_query` ở tầng
graph. Model xử lý đúng: từ chối khéo phần giá vàng, trả lời đủ phần học phí, không nhắc gì
đến câu cảm ơn.

---

## Tổng kết

| # | Số câu hỏi con | Nhãn | `routing_mode` | Task classification | Sub-query decomposer | Kết quả |
| :-: | :-: | --- | :-: | :-: | :-: | --- |
| 1 | 3 | advisory × 3 | MULTI | 1 task (gộp) | 3 sub-query | Trả lời đủ 3 ý, đúng số liệu |
| 2 | 3 | calculation × 1, advisory × 2 | null / MULTI | 2 task (tách theo nhãn, advisory gộp) | 2 sub-query | Trả lời đủ 2 ý học vụ + placeholder tính toán nối cuối, không tự tính |
| 3 | 3 | advisory × 2, off_topic × 1 | MULTI / null | 2 task (tách theo nhãn, advisory gộp; cảm ơn bị bỏ) | 2 sub-query | Trả lời đủ 2 ý học vụ, từ chối khéo phần off_topic |

Cả 3 câu đều xác nhận đúng hành vi AD15: `MessageClassificationNode` không còn tự tách các
câu hỏi cùng nhãn `academic_advisory` thành nhiều task — chỉ tách khi khác nhãn — và việc bẻ
nhỏ thành sub-query luôn do decomposer ở `QueryTransformationNode` đảm nhiệm.

**Giới hạn của lần test này:** corpus Qdrant hiện chỉ có 11 chunk từ đúng 1 văn bản (Quyết
định 1035/QĐ-ĐHCN, mức học phí 2025-2026) nên cả 3 câu đều xoay quanh chủ đề học phí để có
dữ liệu thật trả lời — chưa test được học bổng/thủ tục/lịch học vụ (không có tài liệu nguồn)
hay trường hợp thật sự rơi vào TicketFallback vì thiếu tài liệu (câu đầu tiên trong phiên test
này, hỏi cả học bổng/thủ tục/lịch, đã rơi vào TicketFallback đúng như dự kiến — xem lịch sử
hội thoại). Test dùng `department_access` giả lập quyền cấp 3 toàn trường (không qua gateway
JWT thật) để tránh pre-filter phân quyền chặn mất kết quả do 11 chunk hiện có thiếu field
`is_public`.
