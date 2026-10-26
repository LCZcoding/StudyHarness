package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.app.LoveApp;
import com.lcz.aiagentstudy.model.ChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 用例 7：接线测试。
 * 用 mock 顶掉 LoveApp，只验证「流式累积 + 完成时落库」这条线真的接上了——
 * 与模型好不好、答得对不对无关。
 *
 * <p>为什么这里没有 {@code @Transactional} 自动回滚：
 * 落库挂在 {@code publishOn(boundedElastic)} 之后的 {@code Flux.defer} 上，跑在**另一个线程**，
 * 用的是另一条连接。测试事务里的未提交数据它看不见（外键当场报 23503），它插入的行也不会被
 * 测试回滚。所以改成显式建数据 + {@code @AfterEach} 手动清理。
 */
@SpringBootTest
class ChatServiceTest {

    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean LoveApp loveApp;

    private UUID userId;
    private UUID conversationId;

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

    /** 只清这一条用例造的数据；顺序按外键依赖倒着删。 */
    @AfterEach
    void cleanUp() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM t_message WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM t_conversation WHERE id = ?", conversationId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM t_ai_call WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        }
    }

    @Test
    void stream_accumulatesChunksAndSavesOnComplete() {
        // 模型分两片吐出来；中间夹一条工具事件和一份来源——它们都不是正文
        when(loveApp.doChatByStream(anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(Flux.just(
                        new ChatEvent.Delta("答"),
                        new ChatEvent.Tool("t-1", "start", "getCurrentTime", "{}", null, null, null),
                        new ChatEvent.Tool("t-1", "end", "getCurrentTime", null, true, 3L, "2026-10-26 10:00:00"),
                        new ChatEvent.Sources(List.of(
                                new ChatEvent.Source("k-1", "线程池核心数", 0, "片段…", 0.83))),
                        new ChatEvent.Delta("案")));

        userId = newUser("t-stream-" + UUID.randomUUID());
        conversationId = newConversation(userId);

        // Flux 是懒的：不消费（不 subscribe）就一行回调都不会跑，落库也不会发生
        List<ChatEvent> events = chatService.stream(conversationId, "问题", userId, false, false)
                .collectList().block();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT role, content FROM t_message WHERE conversation_id = ? ORDER BY id",
                conversationId);

        assertEquals(2, rows.size(), "流结束时应落两行");
        assertEquals("问题", rows.get(0).get("content"));
        assertEquals("答案", rows.get(1).get("content"), "只拼 delta，事件不能混进正文");
        assertTrue(events.get(events.size() - 1) instanceof ChatEvent.Done, "最后一帧必须是 done");
    }
}
