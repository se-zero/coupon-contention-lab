package com.experiment.coupon.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 예외 응답 통일
 *
 * 5xx 는 NFR-04 위반 신호다. 여기서 삼키지 말고 그대로 노출해 측정에 잡히게 한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 요청 본문 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleInvalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("result", "INVALID_REQUEST"));
    }

    // 매핑되지 않은 경로 — 아래 catch-all 에 걸리면 404 가 5xx 로 둔갑해 NFR-04 측정이 오염된다
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("result", "NOT_FOUND"));
    }

    // 예상하지 못한 예외 — 커넥션 풀 고갈, 락 타임아웃 등이 여기로 온다
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("unexpected error: {}", e.getClass().getSimpleName(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("result", "ERROR", "type", e.getClass().getSimpleName()));
    }
}
