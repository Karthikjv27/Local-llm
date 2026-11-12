package com.localllm.locie.common;

import com.localllm.locie.chat.ChatNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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
}
