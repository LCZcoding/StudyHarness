package com.lcz.aiagentstudy.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 用例 6-20：模型调用失败时必须落一行 success=false 的观测记录。
 *
 * <p>做法：mock 掉 ChatModel 让它抛异常，走一次真实的 {@link PracticeService#guide}；
 * 与「模型答得好不好」无关，只验证 try/catch → recordFailure → t_ai_call 这条线接上了。
 */
@SpringBootTest
@Transactional // 跑完回滚，不脏库
class FailedCallRecordingTest {

    @Autowired PracticeService practiceService;
    @Autowired JdbcTemplate jdbcTemplate;

    // 容器里有 dashScope / deepSeek / ollama 三个 ChatModel，必须按 bean 名顶掉 PracticeService 实际用的那个
    @MockitoBean(name = "deepSeekChatModel")
    ChatModel chatModel;

    @Test
    void failureIsRecorded() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("mock-ai-down"));

        UUID userId = jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class, "t-fail-" + UUID.randomUUID(), "x");
        UUID itemId = jdbcTemplate.queryForObject(
                "INSERT INTO t_knowledge_item (user_id, title, content, doc_title, section_no) "
                        + "VALUES (?, '失败埋点测试', '线程池的核心参数…', '失败埋点测试', 1) RETURNING id",
                UUID.class, userId);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> practiceService.guide(userId, itemId, null));
        assertEquals("mock-ai-down", thrown.getMessage());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT scene, success, error_code, prompt_tokens FROM t_ai_call "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                userId);
        assertEquals("PRACTICE", row.get("scene"));
        assertEquals(Boolean.FALSE, row.get("success"));
        assertEquals("RuntimeException", row.get("error_code"));
        assertEquals(null, row.get("prompt_tokens"));
    }
}
