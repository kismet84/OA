package com.company.oa;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

public class ApiException extends RuntimeException {
  final int status;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new ApiException(400, message);
  }
}

@RestControllerAdvice
class ApiErrors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> domain(ApiException e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getDefaultMessage())
        .filter(value -> PunchService.UPGRADE_MESSAGE.equals(value)
            || "定位精度须大于0且不超过200米".equals(value))
        .findFirst().orElse("输入无效，请检查必填项、文本长度、格式和数值范围");
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict() {
    return ResponseEntity.status(409).body(Map.of("message", "数据冲突，请检查工号、手机号是否重复或关联数据是否有效"));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.multipart.MaxUploadSizeExceededException.class
  })
  ResponseEntity<?> malformed(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "参数格式错误或文件超过 10MB"));
  }
}
