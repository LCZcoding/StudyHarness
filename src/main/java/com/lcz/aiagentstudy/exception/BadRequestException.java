package com.lcz.aiagentstudy.exception;

/** 请求本身不合法（不是未登录、也不是越权）。文案由抛出的地方决定。 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}