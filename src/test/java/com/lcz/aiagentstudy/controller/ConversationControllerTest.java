package com.lcz.aiagentstudy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcz.aiagentstudy.constant.ConversationKeys;
import com.lcz.aiagentstudy.constant.SessionKeys;
import com.lcz.aiagentstudy.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional // 跑完自动回滚，不脏库
class ConversationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired MessageService messageService;

    /** 直接往库里插一个用户，返回它的 id */
    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_user (username, password_hash) VALUES (?, ?) RETURNING id",
                UUID.class,
                username,
                "x");
    }

    /** 直接往库里插一个会话，返回它的 id */
    private UUID newConversation(UUID userId, String mode) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_conversation (user_id, mode) VALUES (?, ?) RETURNING id",
                UUID.class,
                userId,
                mode);
    }

    /** 从 conversations 数组里按 conversationId 找出那一行（避免写复杂的 jsonPath 过滤表达式） */
    private JsonNode findConversation(JsonNode conversations, UUID conversationId) {
        for (JsonNode node : conversations) {
            if (node.get(ConversationKeys.CONVERSATION_ID).asText().equals(conversationId.toString())) {
                return node;
            }
        }
        return null;
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

    // ============================ 第⑤格：会话历史（§8 用例 1~8） ============================

    // ⑤用例 1：GET /conversations 未登录 → 401，body 要有那句话
    @Test
    void list_unauthorized() throws Exception {
        mockMvc.perform(get("/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    // ⑤用例 2：只返回本用户的会话；另一用户的会话不出现；updatedAt 非 null
    @Test
    void list_returnsOnlyOwnConversations() throws Exception {
        UUID userId = newUser("t-list-" + UUID.randomUUID());
        UUID otherUserId = newUser("t-list-other-" + UUID.randomUUID());
        UUID mineA = newConversation(userId, "FREE_CHAT");
        UUID mineB = newConversation(userId, "PRACTICE");
        UUID others = newConversation(otherUserId, "FREE_CHAT");

        MvcResult result = mockMvc.perform(get("/conversations")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + ConversationKeys.CONVERSATIONS + ".length()").value(2))
                .andExpect(jsonPath("$." + ConversationKeys.CONVERSATIONS + "[0].updatedAt").exists())
                .andReturn();

        JsonNode conversations = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(ConversationKeys.CONVERSATIONS.toString());
        Set<String> ids = new HashSet<>();
        conversations.forEach(node -> ids.add(node.get(ConversationKeys.CONVERSATION_ID).asText()));

        assertTrue(ids.contains(mineA.toString()), "本用户的会话 A 应出现");
        assertTrue(ids.contains(mineB.toString()), "本用户的会话 B 应出现");
        assertFalse(ids.contains(others.toString()), "另一用户的会话不应出现");
        conversations.forEach(node -> assertFalse(node.get("updatedAt").isNull(), "updatedAt 不应为 null"));
    }

    // ⑤用例 3：按 updated_at DESC 排序——显式 UPDATE 造两个可区分的值，别指望两次 now() 天然不同
    @Test
    void list_sortedByUpdatedAtDesc() throws Exception {
        UUID userId = newUser("t-sort-" + UUID.randomUUID());
        UUID older = newConversation(userId, "FREE_CHAT");
        UUID newer = newConversation(userId, "FREE_CHAT");

        OffsetDateTime olderTime = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime newerTime = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        jdbcTemplate.update("UPDATE t_conversation SET updated_at = ? WHERE id = ?", olderTime, older);
        jdbcTemplate.update("UPDATE t_conversation SET updated_at = ? WHERE id = ?", newerTime, newer);

        MvcResult result = mockMvc.perform(get("/conversations")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + ConversationKeys.CONVERSATIONS + "[0]." + ConversationKeys.CONVERSATION_ID)
                        .value(newer.toString()))
                .andReturn();

        JsonNode first = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(ConversationKeys.CONVERSATIONS.toString()).get(0);
        // 返回的字符串带会话时区偏移，比较瞬时点而不是逐字比较字符串
        assertEquals(newerTime.toInstant(), OffsetDateTime.parse(first.get("updatedAt").asText()).toInstant(),
                "排序首行应是 updated_at 较晚的那条，且返回的 updatedAt 与写入值一致");
    }

    // ⑤用例 4：title 派生自首条 USER 消息前 50 字；无 USER 消息 → null
    @Test
    void list_titleDerivedFromFirstUserMessage() throws Exception {
        UUID userId = newUser("t-title-" + UUID.randomUUID());
        UUID withMessage = newConversation(userId, "FREE_CHAT");
        UUID withoutMessage = newConversation(userId, "FREE_CHAT");

        String longQuestion = "这是一条明显超过五十个字符的用户提问，用来验证派生标题会被截断到前五十个字符为止，后面的内容都不应出现在标题里。";
        assertTrue(longQuestion.length() > 50, "测试数据本身必须超过 50 字符");
        jdbcTemplate.update(
                "INSERT INTO t_message (conversation_id, role, type, content) VALUES (?, 'USER', 'QUESTION', ?)",
                withMessage,
                longQuestion);

        MvcResult result = mockMvc.perform(get("/conversations")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + ConversationKeys.CONVERSATIONS + ".length()").value(2))
                .andReturn();

        JsonNode conversations = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(ConversationKeys.CONVERSATIONS.toString());
        JsonNode withMsg = findConversation(conversations, withMessage);
        JsonNode withoutMsg = findConversation(conversations, withoutMessage);
        assertNotNull(withMsg, "会话 A 必须出现在列表里");
        assertNotNull(withoutMsg, "会话 B 必须出现在列表里");

        assertFalse(withMsg.get("title").isNull(), "有 USER 消息的会话应派生 title");
        assertEquals(longQuestion.substring(0, 50), withMsg.get("title").asText(), "title 应是首条 USER 消息前 50 字");
        assertEquals(50, withMsg.get("title").asText().length(), "派生标题长度应恰为 50");
        assertTrue(withoutMsg.get("title").isNull(), "没有 USER 消息的会话 title 应为 null");
    }

    // ⑤用例 5：GET /conversations/{id}/messages 未登录 → 401
    @Test
    void messages_unauthorized() throws Exception {
        UUID conversationId = newConversation(newUser("t-msg-unauth-" + UUID.randomUUID()), "FREE_CHAT");

        mockMvc.perform(get("/conversations/" + conversationId + "/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    // ⑤用例 6：别人的会话 与 不存在的随机 UUID → 均 403，且两条 message 逐字相同（归属校验看门狗）
    @Test
    void messages_forbiddenSameMessageForOthersAndMissing() throws Exception {
        UUID userId = newUser("t-msg-owner-" + UUID.randomUUID());
        UUID otherUserId = newUser("t-msg-other-" + UUID.randomUUID());
        UUID othersConversation = newConversation(otherUserId, "FREE_CHAT");
        UUID randomId = UUID.randomUUID();

        MvcResult otherResult = mockMvc.perform(get("/conversations/" + othersConversation + "/messages")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("会话不存在或无权访问"))
                .andReturn();

        MvcResult missingResult = mockMvc.perform(get("/conversations/" + randomId + "/messages")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("会话不存在或无权访问"))
                .andReturn();

        String otherMessage = objectMapper.readTree(otherResult.getResponse().getContentAsString())
                .get("message").asText();
        String missingMessage = objectMapper.readTree(missingResult.getResponse().getContentAsString())
                .get("message").asText();
        assertEquals(missingMessage, otherMessage, "「别人的会话」与「不存在的 UUID」两种 403 文案必须逐字相同");
    }

    // ⑤用例 7：自己的会话，按④格方式落一轮消息 → 200，USER → ASSISTANT，content 一致
    @Test
    void messages_returnsRoundInOrder() throws Exception {
        UUID userId = newUser("t-msg-own-" + UUID.randomUUID());
        UUID conversationId = newConversation(userId, "FREE_CHAT");
        String question = "什么是指针的别名？";
        String answer = "指针的别名是引用。";
        messageService.appendRound(conversationId, question, answer);

        mockMvc.perform(get("/conversations/" + conversationId + "/messages")
                        .sessionAttr(SessionKeys.USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + ".length()").value(2))
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + "[0].messageId").exists())
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + "[0].createdAt").exists())
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + "[0].role").value("USER"))
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + "[0].content").value(question))
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + "[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$." + ConversationKeys.MESSAGES + "[1].content").value(answer));
    }

    // ⑤用例 8：路径变量不是 UUID → 400 + {"message":"conversationId 不合法"}（§2.1 新处理器的看门狗）
    @Test
    void messages_invalidConversationId() throws Exception {
        mockMvc.perform(get("/conversations/abc/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("conversationId 不合法"));
    }
}
