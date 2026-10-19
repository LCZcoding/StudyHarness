package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.app.LoveApp;
import com.lcz.aiagentstudy.constant.SessionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 跑完自动回滚，不脏库
class AiChatControllerTest {

    /** 用例 2 与用例 3 共用同一句文案：「不存在」与「不归你」必须归并成同一种失败 */
    private static final String FORBIDDEN_MESSAGE = "会话不存在或无权访问";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * 用例 1~5 都在「开流之前」就结束，本不需要模型。
     * 仍然把 LoveApp 换成 mock：万一归属校验被改坏了、请求一路走到开流那步，
     * 也不会真的打到收费的大模型 API（mock 未打桩返回 null → 500，测试照样红）。
     */
    @MockitoBean LoveApp loveApp;

    /** 直接往库里插一个用户，返回它的 id */
    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class,
                username,
                "x");
    }

    /** 直接往库里插一个会话，返回它的 id */
    private UUID newConversation(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_conversation (user_id, mode) VALUES (?, 'FREE_CHAT') RETURNING id",
                UUID.class,
                userId);
    }

    private static String body(String conversationId, String message) {
        return "{\"conversationId\":\"" + conversationId + "\",\"message\":\"" + message + "\"}";
    }

    // 用例 1：未登录 → 401，body 里要有那句话（前端能直接弹出来）
    @Test
    void stream_unauthorized() throws Exception {
        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), "hello")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    // 用例 2：已登录，但会话是别人的 → 403
    @Test
    void stream_forbidden_conversationOfOthers() throws Exception {
        UUID conversationId = newConversation(newUser("t-owner-" + UUID.randomUUID()));
        UUID intruderId = newUser("t-intruder-" + UUID.randomUUID());

        mockMvc.perform(post("/ai/chat/stream")
                        .sessionAttr(SessionKeys.USER_ID, intruderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(conversationId.toString(), "hello")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
    }

    // 用例 3：已登录，会话根本不存在 → 403，且文案与用例 2 逐字相同
    @Test
    void stream_forbidden_conversationNotExists() throws Exception {
        UUID userId = newUser("t-someone-" + UUID.randomUUID());

        mockMvc.perform(post("/ai/chat/stream")
                        .sessionAttr(SessionKeys.USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), "hello")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
    }

    // 用例 4：缺 conversationId / conversationId 不是合法 UUID 字符串
    // 故意不伪造登录态：万一 @Valid 静默失效，请求会一路走到「取身份」变成 401，测试照样红
    @Test
    void stream_badConversationId() throws Exception {
        // 缺字段 → MethodArgumentNotValidException，文案由 DTO 注解的 message 带着
        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("conversationId 不能为空"));

        // 不是合法 UUID → Jackson 解不开，文案由处理器固定给出
        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-a-uuid", "hello")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体不合法"));
    }

    // 用例 5：message 为空 / 全空白 / 超过 4000 字符
    @Test
    void stream_badMessage() throws Exception {
        String conversationId = UUID.randomUUID().toString();

        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(conversationId, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("message 不能为空"));

        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(conversationId, "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("message 不能为空"));

        mockMvc.perform(post("/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(conversationId, "a".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("message 过长"));
    }
}
