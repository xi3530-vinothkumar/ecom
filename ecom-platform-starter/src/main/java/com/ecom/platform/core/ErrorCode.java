package com.ecom.platform.core;

import io.grpc.Status;

/** Single mapping of business error categories to HTTP status and gRPC status. */
public enum ErrorCode {
    VALIDATION(400, Status.Code.INVALID_ARGUMENT),
    UNAUTHENTICATED(401, Status.Code.UNAUTHENTICATED),
    FORBIDDEN(403, Status.Code.PERMISSION_DENIED),
    NOT_FOUND(404, Status.Code.NOT_FOUND),
    CONFLICT(409, Status.Code.ALREADY_EXISTS),
    UNPROCESSABLE(422, Status.Code.FAILED_PRECONDITION),
    UNAVAILABLE(503, Status.Code.UNAVAILABLE),
    INTERNAL(500, Status.Code.INTERNAL);

    private final int httpStatus;
    private final Status.Code grpcCode;

    ErrorCode(int httpStatus, Status.Code grpcCode) {
        this.httpStatus = httpStatus;
        this.grpcCode = grpcCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Status.Code grpcCode() {
        return grpcCode;
    }

    public static ErrorCode fromGrpc(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> VALIDATION;
            case UNAUTHENTICATED -> UNAUTHENTICATED;
            case PERMISSION_DENIED -> FORBIDDEN;
            case NOT_FOUND -> NOT_FOUND;
            case ALREADY_EXISTS, ABORTED -> CONFLICT;
            case FAILED_PRECONDITION -> UNPROCESSABLE;
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED -> UNAVAILABLE;
            default -> INTERNAL;
        };
    }
}
