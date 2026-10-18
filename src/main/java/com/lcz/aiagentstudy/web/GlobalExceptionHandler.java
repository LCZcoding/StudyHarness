package com.lcz.aiagentstudy.web;

import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.exception.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
