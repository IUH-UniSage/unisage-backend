package com.unisage.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // System errors (9xxx)
    SYS_UNCATEGORIZED(HttpStatus.INTERNAL_SERVER_ERROR, 9999, "Hệ thống có lỗi chưa xác định. Vui lòng thử lại sau."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, 9998, "Không tìm thấy endpoint này."),

    // Authentication errors (1xxx)
    AUTH_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, 1001, "Bạn cần đăng nhập để thực hiện thao tác này."),
    AUTH_UNAUTHORIZED(HttpStatus.FORBIDDEN, 1002, "Bạn không có quyền truy cập chức năng này."),
    JWT_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, 1003, "Token không hợp lệ."),
    JWT_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, 1004, "Token đã hết hạn."),
    JWT_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, 1005, "Chữ ký token không hợp lệ."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, 1006, "Email hoặc mật khẩu không chính xác."),
    INTERNAL_SECRET_INVALID(HttpStatus.FORBIDDEN, 1007, "Thiếu hoặc sai X-Internal-Secret."),

    // User account errors (2xxx)
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, 2004, "Người dùng không tồn tại."),
    USER_BANNED(HttpStatus.FORBIDDEN, 2002, "Người dùng đã bị khoá."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, 2005, "Tài khoản của bạn đã bị khóa hoặc chưa kích hoạt."),

    // Validation
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, 2300, "Dữ liệu đầu vào không hợp lệ."),
    DOCUMENT_PERMISSION_FORBIDDEN(HttpStatus.FORBIDDEN, 2310, "Ban không đủ quyền để tạo documemnt."),
    DOCUMENT_PUBLIC_ACCESS_LEVEL_CONFLICT(HttpStatus.BAD_REQUEST, 2311,
            "Tài liệu công khai không được đặt cấp độ truy cập tối thiểu."),

    // Not found errors
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, 2101, "Category không tồn tại."),
    DEPARTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, 2105, "Department không tồn tại."),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, 2106, "Document không tồn tại."),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, 2109, "Role không tồn tại."),
    PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, 2112, "Permission không tồn tại."),
    CHAT_MODEL_NOT_FOUND(HttpStatus.NOT_FOUND, 2121, "Chat Model không tồn tại!"),
    USER_DEPARTMENT_ACCESS_NOT_FOUND(HttpStatus.NOT_FOUND, 2125, "Phân quyền phòng ban không tồn tại."),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, 2127, "Conversation không tồn tại."),
    MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, 2128, "Message không tồn tại."),
    CONVERSATION_ALREADY_CLAIMED(HttpStatus.BAD_REQUEST, 2129, "Hội thoại này đã thuộc về một người dùng khác."),
    USAGE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, 2130,
            "Bạn đã dùng hết hạn mức sử dụng. Vui lòng quay lại sau thời điểm được thông báo."),
    ACCESS_LEVEL_NOT_FOUND(HttpStatus.NOT_FOUND, 2131, "Access Level không tồn tại."),
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, 2139, "Yêu cầu hỗ trợ không tồn tại."),
    AUDIT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, 2144, "Nhật ký hệ thống không tồn tại."),
    SYSTEM_CONFIG_NOT_FOUND(HttpStatus.NOT_FOUND, 2145, "Cấu hình hệ thống không tồn tại."),
    USAGE_LIMIT_PLAN_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, 2150, "Hệ thống chưa cấu hình gói hạn mức mặc định."),
    USAGE_LIMIT_PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, 2151, "Gói hạn mức không tồn tại."),

    // Business rule errors
    EMAIL_EXISTED(HttpStatus.BAD_REQUEST, 2115, "Email này đã được sử dụng!"),
    PHONE_EXISTED(HttpStatus.BAD_REQUEST, 2116, "Số điện thoại này đã được sử dụng!"),
    USER_CODE_EXISTED(HttpStatus.BAD_REQUEST, 2117, "Mã người dùng này đã tồn tại!"),
    ROLE_EXISTED(HttpStatus.BAD_REQUEST, 2119, "Vai trò này đã tồn tại!"),
    CATEGORY_NAME_EXISTED(HttpStatus.BAD_REQUEST, 2120, "Tên danh mục này đã tồn tại!"),
    USER_DEPARTMENT_ACCESS_EXISTED(HttpStatus.BAD_REQUEST, 2126, "Phân quyền phòng ban này đã tồn tại!"),
    ACCESS_LEVEL_EXISTED(HttpStatus.BAD_REQUEST, 2132, "Access Level này đã tồn tại!"),
    USAGE_LIMIT_PLAN_NAME_EXISTED(HttpStatus.BAD_REQUEST, 2152, "Tên gói hạn mức này đã tồn tại!"),
    USAGE_LIMIT_PLAN_DEFAULT_PROTECTED(HttpStatus.BAD_REQUEST, 2153,
            "Không thể xóa hoặc bỏ đánh dấu gói mặc định. Hãy chọn gói mặc định khác trước."),
    USAGE_LIMIT_PLAN_IN_USE(HttpStatus.BAD_REQUEST, 2154, "Gói hạn mức đang được gán cho vai trò, không thể xóa."),
    CHAT_MODEL_PROVIDER_REQUIRED(HttpStatus.BAD_REQUEST, 2133, "llmProvider không được để trống khi sourceType là CLOUD_API."),
    CHAT_MODEL_API_KEY_REQUIRED(HttpStatus.BAD_REQUEST, 2134, "apiKey không được để trống khi sourceType là CLOUD_API."),
    MESSAGE_ROLE_NOT_ASSISTANT(HttpStatus.FORBIDDEN, 2135, "Chỉ có thể cập nhật tin nhắn của trợ lý (ASSISTANT)."),
    MESSAGE_INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, 2136, "Chuyển trạng thái tin nhắn không hợp lệ."),
    MESSAGE_CONTENT_CONFLICT(HttpStatus.CONFLICT, 2137, "Tin nhắn đã được hoàn tất trước đó với nội dung khác."),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, 2138, "Mật khẩu hiện tại không chính xác."),
    TICKET_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, 2140, "Tin nhắn này đã có yêu cầu hỗ trợ."),
    TICKET_MESSAGE_INVALID(HttpStatus.BAD_REQUEST, 2141, "Chỉ có thể báo cáo câu trả lời của trợ lý trong hội thoại của bạn."),
    TICKET_INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, 2142, "Yêu cầu hỗ trợ đã đóng, không thể thay đổi nữa."),
    TICKET_RESOLUTION_REQUIRED(HttpStatus.BAD_REQUEST, 2143, "Cần nhập nội dung phản hồi khi đánh dấu đã giải quyết."),
    SYSTEM_CONFIG_INVALID_VALUE(HttpStatus.BAD_REQUEST, 2146, "Giá trị cấu hình không hợp lệ với kiểu dữ liệu của cấu hình này."),
    SYSTEM_CONFIG_NOT_EDITABLE(HttpStatus.FORBIDDEN, 2147, "Cấu hình này không thể chỉnh sửa."),
    SYSTEM_CONFIG_FILE_SIZE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, 2155,
            "Kích thước file tối đa phải lớn hơn 0 và không vượt quá giới hạn tải lên của máy chủ (100 MB)."),
    MESSAGE_METADATA_INVALID(HttpStatus.BAD_REQUEST, 2156,
            "metadata chỉ chấp nhận khóa clarification_answers và không vượt quá 32 KB."),
    CLARIFICATION_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, 2157,
            "Bảng hỏi không ở trạng thái cho phép chuyển sang trạng thái này."),

    // Internal API (/internal/**) — X-Internal-Secret is checked separately by
    // InternalSecretFilter (code 1007); this is the CIDR layer.
    INTERNAL_CALLER_NOT_ALLOWED(HttpStatus.FORBIDDEN, 2500, "Địa chỉ gọi không nằm trong danh sách cho phép."),

    // Dynamic Model Registry (25xx)
    CHAT_MODEL_STATUS_CONFLICT(HttpStatus.CONFLICT, 2510, "Trạng thái model đã thay đổi, vui lòng tải lại."),
    EMBEDDING_ACTIVE_CONFLICT(HttpStatus.CONFLICT, 2511, "Một model embedding khác vừa được kích hoạt."),
    CHAT_MODEL_NOT_VERIFIED(HttpStatus.CONFLICT, 2512, "Model chưa được xác minh, không thể kích hoạt."),
    VERIFICATION_LEASE_LOST(HttpStatus.CONFLICT, 2513, "Lượt xác minh đã hết hạn hoặc bị lấy lại bởi tiến trình khác."),
    CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST(HttpStatus.BAD_REQUEST, 2514,
            "Phải nhập lại apiKey khi đổi apiBaseUrl sang host mới."),
    CHAT_MODEL_PROVIDER_UNSUPPORTED(HttpStatus.BAD_REQUEST, 2515, "Provider này chưa được hỗ trợ."),
    CHAT_MODEL_URL_NOT_ALLOWED(HttpStatus.BAD_REQUEST, 2516, "apiBaseUrl không hợp lệ hoặc không được phép."),
    EMBEDDING_REINDEX_REQUIRED(HttpStatus.CONFLICT, 2517,
            "Model embedding này khác danh tính với dữ liệu đã index, cần re-index trước khi dùng."),
    EMBEDDING_INDEX_IDENTITY_EXISTS(HttpStatus.CONFLICT, 2518, "Danh tính index của collection này đã được xác lập."),
    VERIFICATION_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, 2519, "Job xác minh không tồn tại."),
    REGISTRY_RESET_JOB_RUNNING(HttpStatus.CONFLICT, 2520,
            "Còn job xác minh đang chạy với lease chưa hết hạn, thử lại sau."),

    // Cost Tracking (26xx)
    USAGE_LOG_INVALID_PAYLOAD(HttpStatus.BAD_REQUEST, 2600, "Payload usage log không hợp lệ."),
    USAGE_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, 2607, "Nhật ký chi phí không tồn tại."),
    BUDGET_NOT_FOUND(HttpStatus.NOT_FOUND, 2601, "Ngân sách không tồn tại."),
    BUDGET_INVALID_SCOPE(HttpStatus.BAD_REQUEST, 2602,
            "scopeProvider/scopePurpose không khớp với scope đã chọn."),
    BUDGET_INVALID_THROTTLE(HttpStatus.BAD_REQUEST, 2603,
            "throttleMaxConcurrency bắt buộc và phải > 0 khi action = THROTTLE, và phải để trống ở action khác."),
    BUDGET_ALREADY_ENABLED_FOR_PERIOD(HttpStatus.CONFLICT, 2604,
            "Đã có ngân sách khác đang bật cho cùng phạm vi và kỳ này."),
    BUDGET_ALERT_SETTING_INVALID(HttpStatus.BAD_REQUEST, 2605,
            "Cấu hình cảnh báo không hợp lệ (ngưỡng phải 1-200, email không đúng định dạng)."),
    BUDGET_ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, 2606, "Cảnh báo không tồn tại."),
    MODEL_PRICING_SYNC_FAILED(HttpStatus.BAD_GATEWAY, 2608,
            "Không đồng bộ được bảng giá từ nguồn LiteLLM, giá hiện tại được giữ nguyên."),
    MODEL_PRICE_NOT_FOUND(HttpStatus.NOT_FOUND, 2609, "Giá model không tồn tại."),
    MODEL_PRICE_INVALID(HttpStatus.BAD_REQUEST, 2610,
            "Giá model không hợp lệ (giá phải từ 0 đến 1000 USD mỗi 1 triệu token)."),
    MODEL_PRICE_ALREADY_EXISTS(HttpStatus.CONFLICT, 2611, "Model này đã có giá, hãy sửa giá hiện có."),
    MODEL_PRICE_NOT_MANUAL(HttpStatus.BAD_REQUEST, 2612,
            "Chỉ khôi phục được giá đã chỉnh tay."),

    // File storage errors (24xx)
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 2401, "Không thể tải file lên hệ thống lưu trữ."),
    FILE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 2402, "Không thể xoá file khỏi hệ thống lưu trữ."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, 2403, "File không tồn tại trong hệ thống lưu trữ."),
    FILE_ACCESS_DENIED(HttpStatus.FORBIDDEN, 2404, "Bạn không đủ quyền truy cập file này."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, 2405, "Kích thước file vượt quá giới hạn cho phép."),
    FILE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, 2406, "Định dạng file không được hỗ trợ."),
    FILE_TOO_MANY_FILES(HttpStatus.BAD_REQUEST, 2407, "Chỉ được phép tải lên một file trong mỗi lần gửi."),
    ;

    private final HttpStatus httpStatus;
    private final int code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, int code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
