package com.localllm.locie.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps failures onto RFC 9457 problem details, so a client never has to parse a stack trace. */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(OllamaUnavailableException.class)
    public ProblemDetail ollamaUnavailable(OllamaUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Ollama unavailable");
        return problem;
    }

    @ExceptionHandler(ChatNotFoundException.class)
    public ProblemDetail chatNotFound(ChatNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Chat not found");
        return problem;
    }

    @ExceptionHandler(OllamaModelNotFoundException.class)
    public ProblemDetail modelNotFound(OllamaModelNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Model not found");
        return problem;
    }

    @ExceptionHandler(ToolCallNotFoundException.class)
    public ProblemDetail toolCallNotFound(ToolCallNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Tool call not found");
        return problem;
    }

    @ExceptionHandler(MessageNotFoundException.class)
    public ProblemDetail messageNotFound(MessageNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Message not found");
        return problem;
    }

    /** A malformed path or query value (for example a UUID that isn't one) is a client mistake. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail unreadableParameter(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid value for '" + ex.getName() + "'");
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body could not be parsed as JSON");
        problem.setTitle("Invalid request");
        return problem;
    }

    /**
     * Anything unplanned. Framework exceptions that already carry a 4xx status keep it; everything
     * else becomes a 500 whose detail is deliberately generic - the cause goes to the log only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            ProblemDetail body = errorResponse.getBody();
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(body == null ? ProblemDetail.forStatus(errorResponse.getStatusCode()) : body);
        }
        log.error("unhandled request failure", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "The server could not complete the request. Check the backend log for details.");
        problem.setTitle("Unexpected error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
