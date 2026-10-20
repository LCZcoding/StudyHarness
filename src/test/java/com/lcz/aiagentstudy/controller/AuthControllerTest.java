package com.lcz.aiagentstudy.controller;


import com.lcz.aiagentstudy.constant.SessionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void login_ok() throws Exception {
        jdbcTemplate.update("INSERT INTO t_user (username, password_hash) VALUES (?, ?)",
                "test-login", passwordEncoder.encode("123456"));

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-login\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertNotNull(result.getRequest().getSession().getAttribute(SessionKeys.USER_ID));
    }

    @Test
    void login_wrongPassword() throws Exception {
        jdbcTemplate.update("INSERT INTO t_user (username, password_hash) VALUES (?, ?)",
                "test-wrong-pwd", passwordEncoder.encode("123456"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-wrong-pwd\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    // 看门狗：@Valid + jakarta.validation 只要被改坏（改回 javax、删掉 @NotBlank、缺 hibernate-validator），
    // 空用户名会一路走到 Service 变成 401，这条立刻红
    @Test
    void login_blankUsername() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"123456\"}"))
                .andExpect(status().isBadRequest());
    }

    // ③格遗留：登出成功 → 204，且会话必须真的被销毁（下面第 3 条断言才是"非假验收"的那一条）
    @Test
    void logout_ok() throws Exception {
        UUID userId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/auth/logout")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isNoContent())
                .andReturn();

        assertEquals("", result.getResponse().getContentAsString(), "204 的响应体必须为空");
        // MockMvc 测不到"容器不下发 Set-Cookie"，但能测到 session 对象已被 invalidate：
        // getSession(false) 拿到 null，说明 session.invalidate() 真的执行了，而不是只回了个 204。
        assertNull(result.getRequest().getSession(false), "登出后会话应已被销毁");
    }

    // ③格遗留：未登录调登出 → 仍然 204（幂等），而且不能因为这次调用凭空建出一个新会话来
    @Test
    void logout_withoutLogin_isStillNoContentAndCreatesNoSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent())
                .andReturn();

        // 看门狗：若把 logout 的参数改回参数式 HttpSession，Spring MVC 会执行 getSession(true)，
        // 这里立刻变非 null —— 未登录的登出反而"登出"出一个新 JSESSIONID。
        assertNull(result.getRequest().getSession(false),
                "未登录的登出请求不应创建会话");
    }
}