package com.lcz.aiagentstudy.web;


import com.lcz.aiagentstudy.constant.SessionKeys;
import com.lcz.aiagentstudy.exception.UnauthorizedException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.util.UUID;


// session to userId
@Component
public class CurrentUserResolver {
    public UUID requireUserId(HttpSession session){
        Object objectUUID = session.getAttribute(SessionKeys.USER_ID);
        if (! (objectUUID instanceof UUID)) {
            throw new UnauthorizedException("未登录");
        }
        return (UUID) objectUUID;
    }
}
