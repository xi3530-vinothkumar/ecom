package com.ecom.platform.web;

import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps exceptions to RFC 9457 problem+json. Unexpected errors never leak internals. */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    @ExceptionHandler(EcomException.class)
    public ResponseEntity<ProblemDetail> handleEcom(EcomException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(ex.code().httpStatus()), ex.getMessage());
        pd.setProperty("code", ex.code().name());
        return respond(pd);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        return respond(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            org.springframework.http.HttpHeaders headers,
            org.springframework.http.HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> String.valueOf(fe.getDefaultMessage()),
                        (a, b) -> a));
        pd.setProperty("errors", errors);
        CallContext.current().ifPresent(c -> pd.setProperty("correlationId", c.correlationId()));
        return ResponseEntity.badRequest().body(pd);
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        CallContext.current().ifPresent(c -> pd.setProperty("correlationId", c.correlationId()));
        return ResponseEntity.status(pd.getStatus()).body(pd);
    }
}
