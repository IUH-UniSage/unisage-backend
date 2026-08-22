package com.unisage.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // System errors (9xxx)
    SYS_UNCATEGORIZED(HttpStatus.INTERNAL_SERVER_ERROR, 9999, "Hệ thống có lỗi chưa xác định. Vui lòng thử lại sau."),

    // Authentication errors (1xxx)
    AUTH_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, 1001, "Bạn cần đăng nhập để thực hiện thao tác này."),
    AUTH_UNAUTHORIZED(HttpStatus.FORBIDDEN, 1002, "Bạn không có quyền truy cập chức năng này."),
    JWT_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, 1003, "Token không hợp lệ."),
    JWT_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, 1004, "Token đã hết hạn."),
    JWT_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, 1005, "Chữ ký token không hợp lệ."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, 1006, "Email hoặc mật khẩu không chính xác."),

    // User account errors (2xxx)
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, 2004, "Người dùng không tồn tại."),
    USER_BANNED(HttpStatus.FORBIDDEN, 2002, "Người dùng đã bị khoá."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, 2005, "Tài khoản của bạn đã bị khóa hoặc chưa kích hoạt."),

    // Validation
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, 2300, "Dữ liệu đầu vào không hợp lệ."),
    DOCUMENT_PERMISSION_FORBIDDEN(HttpStatus.FORBIDDEN, 2310, "Ban không đủ quyền để tạo documemnt."),

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
            "Bạn đã dùng hết số tin nhắn miễn phí hôm nay. Quay lại sau 00:00 hoặc đăng nhập để tiếp tục."),
    ACCESS_LEVEL_NOT_FOUND(HttpStatus.NOT_FOUND, 2131, "Access Level không tồn tại."),

    // Business rule errors
    EMAIL_EXISTED(HttpStatus.BAD_REQUEST, 2115, "Email này đã được sử dụng!"),
    PHONE_EXISTED(HttpStatus.BAD_REQUEST, 2116, "Số điện thoại này đã được sử dụng!"),
    USER_CODE_EXISTED(HttpStatus.BAD_REQUEST, 2117, "Mã người dùng này đã tồn tại!"),
    ROLE_EXISTED(HttpStatus.BAD_REQUEST, 2119, "Vai trò này đã tồn tại!"),
    CATEGORY_NAME_EXISTED(HttpStatus.BAD_REQUEST, 2120, "Tên danh mục này đã tồn tại!"),
    USER_DEPARTMENT_ACCESS_EXISTED(HttpStatus.BAD_REQUEST, 2126, "Phân quyền phòng ban này đã tồn tại!"),
    ACCESS_LEVEL_EXISTED(HttpStatus.BAD_REQUEST, 2132, "Access Level này đã tồn tại!"),

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
