package com.lcz.aiagentstudy.web;


import com.lcz.aiagentstudy.constant.SessionKeys;
import com.lcz.aiagentstudy.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.UUID;


// session to userId
@Component
public class CurrentUserResolver {
    // 收 HttpServletRequest 而不是 HttpSession：Spring MVC 解析 HttpSession 参数时会执行 getSession(true)，
    // 未登录请求也被强制建一个空会话、下发 JSESSIONID（"有 Cookie ≠ 已登录"）。
    // getSession(false) 拿不到会话就返回 null → 直接 401，不再下发空会话。
    public UUID requireUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new UnauthorizedException("未登录");
        }
        Object objectUUID = session.getAttribute(SessionKeys.USER_ID);
        if (! (objectUUID instanceof UUID)) {
            throw new UnauthorizedException("未登录");
        }
        return (UUID) objectUUID;
    }
}
