package com.lcz.yuaiagent.repository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;


import java.util.UUID;

@SpringBootTest
@Transactional   // 关键：测试类上时跑完自动回滚
class ConversationRepositoryTest {

    @Autowired
    private  ConversationRepository conversationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID newUser(String username) {
        return jdbcTemplate.queryForObject(
                "insert into t_user(username, password_hash) values (?, ?) returning id",
                UUID.class,
                 username,
                123456);
    }

    @Test
    void create() {
        UUID conversationId = conversationRepository.create(newUser("test1"), "FREE_CHAT");
        Assertions.assertNotNull (conversationId, "conversationId is null！");
    }

    @Test
    void belongsTo() {
        UUID userId1 = newUser("test2");
        UUID conversationId = conversationRepository.create(userId1, "FREE_CHAT");
        boolean b1 = conversationRepository.belongsTo(conversationId, userId1);
        Assertions.assertTrue(b1, "正常创建后 belongsTo Method return is not true!");

        UUID userId2 = newUser("test3");
        UUID conversationId2 = conversationRepository.create(userId2, "FREE_CHAT");
        boolean b2 = conversationRepository.belongsTo(conversationId2, userId1);
        Assertions.assertFalse(b2, "用户1查到用户2创建的会话！");

        UUID randomUUID = UUID.randomUUID();
        boolean b3 = conversationRepository.belongsTo(randomUUID, userId1);
        Assertions.assertFalse(b3, "通过随机conversationId查找归属，返回了true！");
    }
}