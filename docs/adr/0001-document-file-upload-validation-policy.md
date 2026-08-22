# ADR-0001: Document File Upload Validation Policy (MinIO)

- **Date**: 2026-08-22
- **Status**: Accepted
- **Context story**: `tasks/plan.md` + `tasks/todo.md` — File Upload Validation (backend-java)
- **Decision owners**: Backend team (unisage-backend)

## Context

`DocumentController.createDocument`/`updateDocument` nhận file qua `@ModelAttribute` (Spring bind `MultipartFile`) rồi ủy quyền cho `FileServiceImpl.upload()` để đẩy thẳng lên MinIO qua `minioClient.putObject(...)`. Trước khi có các thay đổi trong ADR này, luồng upload có 3 lỗ hổng:

1. **Không giới hạn loại file**: bất kỳ file nào (kể cả `.exe`, `.png`, binary tuỳ ý) đều được `putObject` thẳng vào bucket `unisage-documents` mà không qua whitelist nào — rủi ro lưu trữ nội dung không mong muốn/độc hại dưới danh nghĩa "tài liệu".
2. **Lỗi kích thước file bị nuốt sai chỗ**: `spring.servlet.multipart.max-file-size=10MB` đã cấu hình, nhưng khi vượt giới hạn, Spring ném `MaxUploadSizeExceededException` — trước đây không có handler riêng nên rơi vào `@ExceptionHandler(Exception.class)` chung, trả **500 "lỗi chưa xác định"** thay vì 400 với message rõ ràng. Sai cả HTTP status lẫn trải nghiệm client.
3. **Không giới hạn số file mỗi request**: `CreateDocumentRequest`/`UpdateDocumentRequest` bind field `file` là 1 `MultipartFile`, nhưng nếu client gửi nhiều `-F "file=@a.pdf" -F "file=@b.pdf"`, Spring **âm thầm chỉ lấy 1 phần** và bỏ qua phần dư — không có tín hiệu lỗi nào cho client biết request của họ có phần bị bỏ qua.

## Decision

### 1. Whitelist loại file theo đuôi file (extension-only), không đối chiếu Content-Type

`AllowedFileType` (`entity/enums/AllowedFileType.java`) liệt kê 4 đuôi được phép: `.txt`, `.pdf`, `.docx`, `.doc`. `FileServiceImpl.upload()` gọi `AllowedFileType.fromExtension(originalFilename)` **trước** khi chạm MinIO — không khớp whitelist → `throw new AppException(ErrorCode.FILE_TYPE_NOT_ALLOWED)` (code `2406`), không gọi `putObject`.

Bản đầu của thiết kế còn đối chiếu thêm `MultipartFile.getContentType()` (Content-Type do client tự khai trong request) với MIME type kỳ vọng của từng đuôi, và reject nếu lệch. Quyết định cuối: **bỏ bước đối chiếu Content-Type**, chỉ giữ check extension. Content-Type là header client tự set — không phải nguồn tin cậy để chặn cứng, dễ false-positive (client/thư viện HTTP đôi khi gửi Content-Type generic như `application/octet-stream` cho file hợp lệ) mà không tăng thêm an toàn thực chất (kẻ tấn công đổi Content-Type dễ như đổi đuôi file). Việc xác thực nội dung file thật sự (magic bytes/deep content inspection) nếu cần sẽ là một quyết định riêng, không nằm trong scope ADR này.

`fromExtension` so sánh không phân biệt hoa/thường và trả `Optional.empty()` an toàn (không NPE) khi filename `null`/rỗng/không có đuôi — `FileServiceImpl.upload()` đã fallback `originalFilename = "file"` khi Spring trả `null`, nên trường hợp này luôn rơi vào nhánh reject rõ ràng thay vì lọt qua.

### 2. Bắt `MaxUploadSizeExceededException` tường minh, trả 400 với giới hạn thực tế

`GlobalExceptionHandler` thêm `@ExceptionHandler(MaxUploadSizeExceededException.class)` đặt **trước** handler `Exception.class` chung (Spring chọn handler cụ thể nhất theo exception hierarchy, không phụ thuộc thứ tự khai báo, nhưng đặt gần nhau để dễ đọc). Handler trả `ErrorCode.FILE_SIZE_EXCEEDED` (400, code `2405`) với message nêu giới hạn thực tế đọc từ `@Value("${spring.servlet.multipart.max-file-size}")` thay vì hard-code "10MB" — nếu cấu hình đổi, message tự đồng bộ.

### 3. Chặn multi-file cùng field ở tầng Servlet, trước khi vào service

`MultipartRequestValidator.validateSingleFile(HttpServletRequest, String fieldName)` (`utils/MultipartRequestValidator.java`) soi `request.getParts()` (Servlet API thô — vì `MultipartFile` đã bind qua `@ModelAttribute` đã bị Spring âm thầm rút gọn về 1 phần, không còn phản ánh số phần thực tế client gửi) để đếm số part trùng tên field. Nếu > 1 → `throw new AppException(ErrorCode.FILE_TOO_MANY_FILES)` (400, code `2407`).

`DocumentController.createDocument`/`updateDocument` gọi `multipartRequestValidator.validateSingleFile(servletRequest, "file")` **trước** khi ủy quyền cho `documentService` — request bị từ chối trước khi chạm DB/MinIO. Khi `request.getParts()` ném exception (request không phải multipart, hoặc field `file` không có mặt — trường hợp `file` là optional trong request) validator **nuốt lỗi và không throw**, giữ hành vi hiện tại (field không bắt buộc vẫn hoạt động bình thường).

### 4. Dải mã lỗi tiếp nối `ErrorCode` (24xx — File storage errors)

| Code | HTTP | Ý nghĩa |
|---|---|---|
| 2401-2404 | (đã có) | Upload/delete/not-found/access-denied MinIO |
| 2405 | 400 | `FILE_SIZE_EXCEEDED` — vượt `max-file-size` |
| 2406 | 400 | `FILE_TYPE_NOT_ALLOWED` — đuôi file không nằm trong whitelist |
| 2407 | 400 | `FILE_TOO_MANY_FILES` — nhiều hơn 1 part cùng tên field trong 1 request |

## Consequences

**Tích cực**:

- MinIO chỉ chứa file thuộc whitelist đã chốt — giảm bề mặt tấn công lưu trữ nội dung tuỳ ý dưới danh nghĩa tài liệu.
- Client nhận đúng HTTP status (400) và message rõ ràng cho cả 3 lớp lỗi (size/type/multi-file) thay vì 500 chung chung hoặc bị bỏ qua âm thầm.
- Validate type/multi-file đều chạy **trước** khi chạm MinIO/DB — không tốn I/O cho request chắc chắn bị từ chối.

**Tiêu cực / rủi ro**:

- Whitelist extension-only không chặn được file có nội dung giả mạo đuôi (vd đổi tên `.exe` thành `.pdf`) — chấp nhận rủi ro này theo quyết định "không đối chiếu Content-Type" ở mục 1; nếu cần chặn chặt hơn, cần ADR riêng cho content sniffing/magic-byte check.
- `MultipartRequestValidator` dựa vào `HttpServletRequest.getParts()` — theo `plan.md`, nếu hành vi thực tế không như kỳ vọng (một số servlet container implementation khác nhau), cần đổi sang cast `MultipartHttpServletRequest` + `getMultiFileMap()`. Chưa có bằng chứng thực tế (chưa test qua container thật ngoài Tomcat mặc định của Spring Boot) nên giữ nguyên `getParts()` cho tới khi có regression.
- Thêm whitelist nghĩa là mọi loại file mới (vd `.xlsx`, `.pptx`) cần một PR cập nhật `AllowedFileType` — có chủ đích, không phải oversight: mọi loại file mới phải qua review thay vì tự động được chấp nhận.

## Alternatives considered

1. **Chặn cả extension và Content-Type** (thiết kế ban đầu trong `tasks/todo.md` Task 4): rejected ở bản cập nhật cuối — Content-Type client tự khai không phải nguồn tin cậy, dễ false-positive, không tăng an toàn thực chất so với chỉ check extension.
2. **Blacklist thay vì whitelist** (chặn `.exe`, `.sh`, ... thay vì chỉ cho phép danh sách cố định): rejected — blacklist luôn thiếu, danh sách extension nguy hiểm liên tục mở rộng; whitelist đóng (chỉ 4 đuôi tài liệu) khớp với use case thực tế của hệ thống (upload tài liệu tri thức, không phải file đính kèm tuỳ ý).
3. **Dùng `MultipartHttpServletRequest.getMultiFileMap()` ngay từ đầu** thay vì `HttpServletRequest.getParts()`: cân nhắc nhưng chưa chọn — `getParts()` là Servlet API chuẩn, không phụ thuộc Spring multipart resolver cụ thể; giữ làm phương án fallback nếu phát sinh vấn đề thực tế (xem rủi ro ở trên).

## Test lock

- `AllowedFileTypeTest`: khớp extension hợp lệ/không hợp lệ (`.exe`, `.png`), không phân biệt hoa/thường (`.PDF`), filename không có đuôi, filename `null`/rỗng.
- `FileServiceImplTest`: `upload_allowsWhitelistedType` (không regression cho file hợp lệ), `upload_rejectsExtensionNotInWhitelist` (`.exe` → `FILE_TYPE_NOT_ALLOWED`, không gọi `minioClient.putObject`).
- `GlobalExceptionHandlerTest`: `MaxUploadSizeExceededException` → response đúng `ErrorCode.FILE_SIZE_EXCEEDED` (400, không phải 500); exception khác vẫn rơi đúng handler cũ.
- `MultipartRequestValidatorTest`: > 1 part cùng tên `file` → `FILE_TOO_MANY_FILES`; đúng 1 part → không throw; `request.getParts()` ném exception → không throw (silent, giữ hành vi field optional).

## References

- `tasks/plan.md`, `tasks/todo.md` (backend-java) — bối cảnh, quyết định gốc, và ghi chú xác minh build/test.
- `src/main/java/com/unisage/backend/entity/enums/AllowedFileType.java`
- `src/main/java/com/unisage/backend/service/file/FileServiceImpl.java`
- `src/main/java/com/unisage/backend/exception/GlobalExceptionHandler.java`
- `src/main/java/com/unisage/backend/utils/MultipartRequestValidator.java`
- `src/main/java/com/unisage/backend/exception/ErrorCode.java` (dải 24xx)
