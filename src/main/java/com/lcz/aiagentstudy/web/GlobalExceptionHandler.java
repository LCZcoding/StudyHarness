package com.lcz.aiagentstudy.web;

import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.exception.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

//from 异常 到 http
@RestControllerAdvice
public class GlobalExceptionHandler  {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<?> handleUnauthorized(UnauthorizedException ex){
        return ResponseEntity.status(401).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<?> handleForbidden(ForbiddenException ex){
        return ResponseEntity.status(403).body(Map.of("message", ex.getMessage()));
    }
    // 校验失败：文案由 DTO 注解的 message 携带，处理器只把第一条转发出去。400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    // body 解不开（非法 JSON / UUID 格式不对）：拒绝方是 Jackson，它带不了我们的文案 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求体不合法"));
    }
    // 路径参数 例如conversationId 格式不对 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // `ex.getName()` 取的就是 `@PathVariable` 里声明的变量名
        return ResponseEntity.badRequest().body(Map.of("message", ex.getName() + " 不合法"));
    }

}
