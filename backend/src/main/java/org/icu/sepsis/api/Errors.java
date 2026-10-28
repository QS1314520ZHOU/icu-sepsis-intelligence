package org.icu.sepsis.api;

import javax.validation.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Envelope<Object>> business(ApiException e) {
    return ResponseEntity.status(e.status).body(new Envelope<>(e.code, e.getMessage(), null));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest().body(new Envelope<>("INVALID_ARGUMENT", "参数或时间格式不正确", null));
  }

  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    DuplicateKeyException.class,
    org.springframework.data.mongodb.MongoTransactionException.class
  })
  public ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(new Envelope<>("STATE_CONFLICT", "记录已变化或命令重复，请刷新后重试", null));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<?> db(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error(
            "DataAccessException type={} msg={} trace={}",
            e.getClass().getName(),
            e.getMessage(),
            org.slf4j.MDC.get("traceId"),
            e);
    return ResponseEntity.status(503)
        .body(new Envelope<>("DATA_SOURCE_UNAVAILABLE", "数据库暂不可用，操作未确认成功", null));
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ResponseEntity<?> forbidden(Exception e) {
    return ResponseEntity.status(403).body(new Envelope<>("FORBIDDEN", "无权执行此操作", null));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> internal(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error(
            "Unhandled failure type={}, trace={}",
            e.getClass().getSimpleName(),
            org.slf4j.MDC.get("traceId"));
    return ResponseEntity.status(500)
        .body(new Envelope<>("INTERNAL_ERROR", "系统内部错误，请使用traceId联系管理员", null));
  }
}
