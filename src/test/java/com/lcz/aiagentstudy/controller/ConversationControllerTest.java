package com.lcz.aiagentstudy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcz.aiagentstudy.constant.ConversationKeys;
import com.lcz.aiagentstudy.constant.SessionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 跑完自动回滚，不脏库
class ConversationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    /** 直接往库里插一个用户，返回它的 id */
    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class,
                username,
                "x");
    }

    // 用例 1：未登录 → 401，body 里要有那句话（前端能直接弹出来）
    @Test
    void create_unauthorized() throws Exception {
        mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FREE_CHAT\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    // 用例 2 + 3：已登录 → 201 + Location + conversationId；
    // 并且落库的 user_id 必须等于 session 里的 userId——这才是本格的核心，见文末断言消息
    @Test
    void create_ok_and_identity_comes_from_session() throws Exception {
        UUID userId = newUser("test-conv-" + UUID.randomUUID());

        MvcResult result = mockMvc.perform(post("/conversations")
                        // 直接伪造登录态：少一次 BCrypt 计算，也不依赖 AuthController 的实现
                        .sessionAttr(SessionKeys.USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FREE_CHAT\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$." + ConversationKeys.CONVERSATION_ID).exists())
                .andReturn();

        String conversationId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(ConversationKeys.CONVERSATION_ID).asText();

        UUID storedUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM t_conversation WHERE id = ?",
                UUID.class,
                UUID.fromString(conversationId));

        assertEquals(userId, storedUserId, "落库的 user_id 应等于 session 里的 userId");
    }

    // 用例 4：缺 mode
    // 下面 4~6 三条故意不伪造登录态：如果 @Valid / @NotNull 静默失效，
    // 请求会一路走到"取身份"那一步变成 401 —— 不是 400，一样会红。这是条更锋利的看门狗。
    @Test
    void create_missingMode() throws Exception {
        mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // 用例 5：mode 是没定义过的值
    @Test
    void create_unknownMode() throws Exception {
        mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    // 用例 6：mode 大小写写错（Jackson 按常量名精确匹配，这是特性不是 bug）
    @Test
    void create_lowercaseMode() throws Exception {
        mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"free_chat\"}"))
                .andExpect(status().isBadRequest());
    }
}
