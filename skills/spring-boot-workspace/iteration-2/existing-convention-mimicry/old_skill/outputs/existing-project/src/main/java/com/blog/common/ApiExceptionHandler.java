package com.blog.common;

import com.blog.article.ArticleNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// 이 프로젝트의 에러 응답 포맷: { "code": ..., "message": ... }
// RFC 9457 ProblemDetail 을 쓰지 않는다.
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ArticleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ArticleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "ARTICLE_NOT_FOUND",
                "message", ex.getMessage()
        ));
    }
}
