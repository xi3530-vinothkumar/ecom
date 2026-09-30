package com.ecom.platform.core;

/**
 * Base for all expected business errors. Mapped to problem+json by the web layer and to a gRPC
 * status by the gRPC server interceptor; anything else surfaces as 500 / INTERNAL.
 */
public class EcomException extends RuntimeException {

    private final ErrorCode code;

    public EcomException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public EcomException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static EcomException notFound(String message) {
        return new EcomException(ErrorCode.NOT_FOUND, message);
    }

    public static EcomException conflict(String message) {
        return new EcomException(ErrorCode.CONFLICT, message);
    }

    public static EcomException validation(String message) {
        return new EcomException(ErrorCode.VALIDATION, message);
    }

    public static EcomException unprocessable(String message) {
        return new EcomException(ErrorCode.UNPROCESSABLE, message);
    }

    public static EcomException forbidden(String message) {
        return new EcomException(ErrorCode.FORBIDDEN, message);
    }

    public static EcomException unauthenticated(String message) {
        return new EcomException(ErrorCode.UNAUTHENTICATED, message);
    }

    public static EcomException unavailable(String message, Throwable cause) {
        return new EcomException(ErrorCode.UNAVAILABLE, message, cause);
    }
}
