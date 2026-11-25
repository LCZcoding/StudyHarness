package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.constant.SessionKeys;
import com.lcz.aiagentstudy.model.ChatEvent;
import com.lcz.aiagentstudy.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T011 的接线校验：`asNote` 字段能不能原样传到 `ChatService.stream` 的第 6 参。
 *
 * <p>为什么不并进 {@code AiChatControllerTest}：那边用真实 {@code ChatService}，验的是
 * 401/403/400 这些"开流之前"的分支；这里要把 {@code ChatService} 换成 mock 才能看见参数。
 * 两个类各自只有一种替身，比在一个类里反复换配置清楚。
 *
 * <p>重点守的是**老前端不发这个字段**的情形：DTO 用 {@code Boolean} 就是为它准备的，
 * 若哪天有人图省事改成 {@code boolean} 或在校验里直接拆箱，这条用例会立刻红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiChatControllerAsNoteTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean ChatService chatService;

    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class, username, "x");
    }

    private UUID newConversation(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_conversation (user_id, mode) VALUES (?, 'FREE_CHAT') RETURNING id",
                UUID.class, userId);
    }

    @Test
    void stream_passesAsNoteToChatService() throws Exception {
        // 返回一个能立刻结束的流：handler 里 stream(...) 是同步调用的，
        // 订阅之后发生什么不影响下面的 verify。
        when(chatService.stream(any(), anyString(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(Flux.just(new ChatEvent.Done()));

        UUID userId = newUser("t-asnote-" + UUID.randomUUID());
        UUID conversationId = newConversation(userId);

        // ① 老前端：请求体里没有 asNote → 必须当作 false，而不是 400、更不能 NPE
        mockMvc.perform(post("/ai/chat/stream")
                .sessionAttr(SessionKeys.USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + conversationId + "\",\"message\":\"讲一下 G1\"}"));

        verify(chatService).stream(conversationId, "讲一下 G1", userId, false, false, false);

        // ② 新前端：asNote=true 必须原样透传
        mockMvc.perform(post("/ai/chat/stream")
                .sessionAttr(SessionKeys.USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + conversationId + "\",\"message\":\"讲一下 G1\","
                        + "\"useRag\":true,\"useTools\":true,\"asNote\":true}"));

        verify(chatService).stream(conversationId, "讲一下 G1", userId, true, true, true);
    }
}
