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

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}