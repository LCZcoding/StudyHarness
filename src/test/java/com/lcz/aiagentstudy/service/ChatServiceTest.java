package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.app.LoveApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 用例 7：接线测试。
 * 用 mock 顶掉 LoveApp，只验证「流式累积 + 完成时落库」这条线真的接上了——
 * 与模型好不好、答得对不对无关。
 */
@SpringBootTest
@Transactional // 跑完自动回滚，不脏库
class ChatServiceTest {

    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean LoveApp loveApp;

    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class,
                username,
                "x");
    }

    private UUID newConversation(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_conversation (user_id, mode) VALUES (?, 'FREE_CHAT') RETURNING id",
                UUID.class,
                userId);
    }

    @Test
    void stream_accumulatesChunksAndSavesOnComplete() {
        // 模型分两片吐出来；ChatService 必须把两片拼成一整句才落库
        when(loveApp.doChatByStream(anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(Flux.just("答", "案"));

        UUID userId = newUser("t-stream-" + UUID.randomUUID());
        UUID conversationId = newConversation(userId);

        // Flux 是懒的：不消费（不 subscribe）就一行回调都不会跑，落库也不会发生
        chatService.stream(conversationId, "问题", userId, false, false).collectList().block();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, content FROM t_message WHERE conversation_id = ? ORDER BY id",
                conversationId);

        assertEquals(2, rows.size(), "流结束时应落两行");
        assertEquals("问题", rows.get(0).get("content"));
        assertEquals("答案", rows.get(1).get("content"), "ASSISTANT 那行应存的是拼接后的全文");
    }
}
