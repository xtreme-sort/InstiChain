package edu.iitm.instichain.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> apiError(ApiException error) {
        return ResponseEntity.status(error.status())
                .header("Cache-Control", "no-store")
                .body(Map.of("detail", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalidRequest(Exception error) {
        String detail = "The request body is invalid.";
        if (error instanceof MethodArgumentNotValidException validation
                && !validation.getBindingResult().getAllErrors().isEmpty()) {
            detail = validation.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .header("Cache-Control", "no-store")
                .body(Map.of("detail", detail));
    }
}
