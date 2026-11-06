package com.openimage.common;

import com.openimage.ai.worker.AiWorkerDeferredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.openimage.modules.auth.email.EmailCodeSendException;
import com.openimage.risk.RiskControlException;
import com.openimage.storage.ImageUploadException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse("Invalid request");
        return ApiResponse.fail("BAD_REQUEST", message);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> denied() {
        return ApiResponse.fail("FORBIDDEN", "No permission");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> bad(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "Invalid request" : e.getMessage();
        return ApiResponse.fail(isAiDisabledMessage(message) ? "AI_DISABLED" : "BAD_REQUEST", message);
    }

    @ExceptionHandler(RiskControlException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> risk(RiskControlException e) {
        return ApiResponse.fail("RISK_CONTROL_BLOCKED", e.getMessage() == null ? "内容可能触发平台风控" : e.getMessage());
    }

    @ExceptionHandler(EmailCodeSendException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> emailCodeSendFailed() {
        return ApiResponse.fail("EMAIL_CODE_SEND_FAILED", "验证码发送失败，请稍后重试");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ApiResponse.fail("UPLOAD_TOO_LARGE", "图片文件过大，请压缩到 25MB 以内后再上传");
    }

    @ExceptionHandler(ImageUploadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> imageUpload(ImageUploadException e) {
        return ApiResponse.fail(e.code(), e.getMessage());
    }

    @ExceptionHandler(AiWorkerDeferredException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> aiWorkerProcessing(AiWorkerDeferredException e) {
        return ApiResponse.fail("AI_WORKER_PROCESSING", "本机 AI 已领取任务，仍在处理中，请稍后再试");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> responseStatus(ResponseStatusException e) {
        String code = e.getStatusCode().value() == 401 ? "UNAUTHORIZED"
            : e.getStatusCode().value() == 403 ? "FORBIDDEN"
            : "REQUEST_REJECTED";
        String message = e.getReason() == null || e.getReason().isBlank()
            ? "Request rejected"
            : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.fail(code, message));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> err(Exception e) {
        log.error("Unhandled API exception", e);
        return ApiResponse.fail("INTERNAL_ERROR", "Service is temporarily unavailable");
    }

    private boolean isAiDisabledMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        return message != null && (
            message.contains("AI 作图服务已被管理员禁用")
                || message.contains("AI 文案服务已被管理员禁用")
                || message.contains("未配置密钥")
                || normalized.contains("ai_disabled")
                || normalized.contains("admin-disabled")
        );
    }
}
