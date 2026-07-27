package com.unisage.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // System errors (9xxx)
    SYS_UNCATEGORIZED(HttpStatus.INTERNAL_SERVER_ERROR, 9999, "Hệ thống có lỗi chưa xác định. Vui lòng thử lại sau."),
    AI_AGENT_UNAVAILABLE(HttpStatus.BAD_GATEWAY, 9001, "Dịch vụ AI Agent hiện không khả dụng."),

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
    ACCOUNT_NOT_EXISTED(HttpStatus.NOT_FOUND, 2003, "Tài khoản không tồn tại."),
    
    // Validation
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, 2300, "Dữ liệu đầu vào không hợp lệ."),
    AI_AGENT_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, 2301, "AI Agent từ chối dữ liệu đầu vào."),
    DOCUMENT_PERMISSION_FORBIDDEN(HttpStatus.FORBIDDEN, 2310, "Ban không đủ quyền để tạo documemnt."),
    
    // Not found errors
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, 2101, "Category không tồn tại."),
    EMBEDDED_MODEL_NOT_FOUND(HttpStatus.NOT_FOUND, 2104, "Embedded Model không tồn tại."),
    DOC_PACKAGE_NOT_FOUND(HttpStatus.NOT_FOUND, 2105, "DocPackage không tồn tại."),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, 2106, "Document không tồn tại."),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, 2107, "Conversation không tồn tại."),
    CHATBOT_CONFIG_NOT_FOUND(HttpStatus.NOT_FOUND, 2108, "ChatbotConfig không tồn tại."),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, 2109, "Role không tồn tại."),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, 2110, "Profile không tồn tại."),
    CHUNK_NOT_FOUND(HttpStatus.NOT_FOUND, 2111, "Chunk không tồn tại."),
    PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, 2112, "Permission không tồn tại."),
    
    // Business rule errors
    MODEL_TYPE_EXISTED(HttpStatus.BAD_REQUEST, 2113, "Model type này đã tồn tại!"),
    PATH_EXISTED(HttpStatus.BAD_REQUEST, 2114, "Đường dẫn đã tồn tại trong hệ thống!"),
    EMAIL_EXISTED(HttpStatus.BAD_REQUEST, 2115, "Email này đã được sử dụng!"),
    PHONE_EXISTED(HttpStatus.BAD_REQUEST, 2116, "Số điện thoại này đã được sử dụng!"),
    USER_CODE_EXISTED(HttpStatus.BAD_REQUEST, 2117, "Mã người dùng này đã tồn tại!"),
    ROLE_EXISTED(HttpStatus.BAD_REQUEST, 2119, "Vai trò này đã tồn tại!"),
    CATEGORY_NAME_EXISTED(HttpStatus.BAD_REQUEST, 2120, "Tên danh mục này đã tồn tại!"),
    CHATBOT_POOL_NOT_FOUND(HttpStatus.NOT_FOUND, 2121, "Chatbot Pool không tồn tại!"),
    MODEL_NAME_EXISTED(HttpStatus.BAD_REQUEST, 2122, "Tên mô hình này đã tồn tại!")
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
