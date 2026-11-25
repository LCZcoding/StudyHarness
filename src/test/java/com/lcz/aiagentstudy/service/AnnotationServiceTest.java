package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.model.AnnotationRow;
import com.lcz.aiagentstudy.model.TargetType;
import com.lcz.aiagentstudy.repository.AnnotationRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T005 测试（v1.1 新编号）。模型用 @MockitoBean 顶掉（沿用 FailedCallRecordingTest 的写法），零成本、不发真实请求。
 * 打真实库但类上 @Transactional 回滚。
 * 相比旧版：NOTE 归属与全文改由 T004 的 MessageRepository 提供，Service 内不再有 SQL。
 */
@SpringBootTest
@Transactional
class AnnotationServiceTest {

    private static final int MAX_DEPTH = 8;

    private static final String FULL_TEXT = "开头讲线程池核心参数，这里放一句占位文字。\n\n"
            + "第二段解释「召回 topK 片段」的做法，topK 表示取前 K 个最相似的片段，"
            + "再由重排模型精排。\n\n第三段讲余弦相似度与归一化。";

    @Autowired private AnnotationService service;
    @Autowired private AnnotationRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean(name = "deepSeekChatModel")
    ChatModel chatModel;

    private UUID newUser() {
        return jdbc.queryForObject(
                "insert into t_user(username, password_hash) values (?, ?) returning id",
                UUID.class, UUID.randomUUID().toString(), "test-hash");
    }

    private UUID newSection(UUID userId, String content) {
        return jdbc.queryForObject("""
                        insert into t_knowledge_item (user_id, title, content, doc_title, section_no)
                        values (?, '批注测试', ?, '批注测试', 1) returning id
                        """,
                UUID.class, userId, content);
    }

    private long newNote(UUID userId, String content) {
        UUID conv = jdbc.queryForObject(
                "insert into t_conversation(user_id, mode) values (?, 'FREE_CHAT') returning id",
                UUID.class, userId);
        return jdbc.queryForObject("""
                        insert into t_message(conversation_id, role, type, content)
                        values (?, 'ASSISTANT', 'NOTE', ?) returning id
                        """,
                Long.class, conv, content);
    }

    private AnnotationService.AskRequest top(String targetId) {
        return new AnnotationService.AskRequest(
                TargetType.SECTION, targetId, "p-1", "召回 topK 片段", "topK 是什么？", null);
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private long insertTop(UUID userId, String targetId, String quote, String answer) {
        return repo.insert(userId, new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, targetId, null, "p-1", quote, "问题", answer));
    }

    @Test
    void ask_顶层成功_落库埋点且答案截断200字() {
        UUID user = newUser();
        String targetId = newSection(user, FULL_TEXT).toString();
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("答".repeat(300)));

        AnnotationRow row = service.ask(user, top(targetId));

        assertThat(row.id()).isPositive();
        assertThat(row.parentId()).isNull();
        assertThat(row.quote()).isEqualTo("召回 topK 片段");
        assertThat(row.answer()).hasSize(200);
        assertThat(row.edited()).isFalse();
        assertThat(row.createdAt()).isNotNull();
        assertThat(repo.countByTarget(user, TargetType.SECTION, targetId)).isEqualTo(1);

        String scene = jdbc.queryForObject(
                "select scene from t_ai_call where user_id = ? order by id desc limit 1",
                String.class, user);
        assertThat(scene).isEqualTo("ANNOTATION");

        // SECTION 路径：全文确实来自资料章，模型收到的是讲义正文而不是空串
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getContents())
                .contains("【全文（前 2000 字）】", "第二段解释", "topK 表示取前 K 个");
    }

    @Test
    void ask_子批注_继承父blockKey且scene可辨() {
        UUID user = newUser();
        String targetId = newSection(user, FULL_TEXT).toString();
        long parent = insertTop(user, targetId, "召回 topK 片段", "topK 是取前 K 个。");
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("夹角的余弦值。"));

        AnnotationRow row = service.ask(user, new AnnotationService.AskRequest(
                TargetType.SECTION, targetId, "p-99", "余弦相似度", "怎么算？", parent));

        assertThat(row.parentId()).isEqualTo(parent);
        assertThat(row.blockKey()).isEqualTo("p-1");   // 覆盖请求里故意传错的 p-99
        assertThat(row.answer()).isEqualTo("夹角的余弦值。");
        assertThat(repo.countByTarget(user, TargetType.SECTION, targetId)).isEqualTo(2);

        String scene = jdbc.queryForObject(
                "select scene from t_ai_call where user_id = ? order by id desc limit 1",
                String.class, user);
        assertThat(scene).isEqualTo("ANNOTATION_CHILD");
    }

    @Test
    void ask_越权与限额_全部在模型调用之前被拦截() {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner, FULL_TEXT).toString();
        String otherTarget = newSection(other, FULL_TEXT).toString();

        // 非法 targetId
        assertThatThrownBy(() -> service.ask(owner,
                new AnnotationService.AskRequest(TargetType.SECTION, "not-a-uuid", "p-1", "q", "q", null)))
                .isInstanceOf(BadRequestException.class);
        // 目标不属于本人
        assertThatThrownBy(() -> service.ask(owner, top(otherTarget)))
                .isInstanceOf(ForbiddenException.class);
        // 父批注属于他人
        long othersAnnotation = insertTop(other, otherTarget, "q", "a");
        assertThatThrownBy(() -> service.ask(owner, new AnnotationService.AskRequest(
                TargetType.SECTION, targetId, "p-1", "q", "q", othersAnnotation)))
                .isInstanceOf(ForbiddenException.class);
        // I1 父与本次 targetId 不一致
        long crossTarget = insertTop(owner, otherTarget, "q", "a");
        assertThatThrownBy(() -> service.ask(owner, new AnnotationService.AskRequest(
                TargetType.SECTION, targetId, "p-1", "q", "q", crossTarget)))
                .isInstanceOf(BadRequestException.class);
        // I2 链深达 8
        Long cursor = insertTop(owner, targetId, "q", "a1");
        for (int i = 2; i <= MAX_DEPTH; i++) {
            cursor = repo.insert(owner, new AnnotationRepository.NewAnnotation(
                    TargetType.SECTION, targetId, cursor, "p-1", "q", "q", "a" + i));
        }
        Long deepParent = cursor;
        assertThatThrownBy(() -> service.ask(owner, new AnnotationService.AskRequest(
                TargetType.SECTION, targetId, "p-1", "q", "q", deepParent)))
                .isInstanceOf(BadRequestException.class);
        // I3 目标下已满 200 条（上面已有 8 条，补到 200）
        int existing = repo.countByTarget(owner, TargetType.SECTION, targetId);
        jdbc.update("""
                        insert into t_annotation (user_id, target_type, target_id, block_key, quote, question, answer)
                        select ?, 'SECTION', ?, 'p-1', 'q', 'q', 'a' from generate_series(1, ?)
                        """,
                owner, targetId, 200 - existing);
        assertThatThrownBy(() -> service.ask(owner, top(targetId)))
                .isInstanceOf(BadRequestException.class);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void note归属与全文_走T004的MessageRepository() {
        UUID user = newUser();
        long noteId = newNote(user, "笔记正文里提到 topK 片段的取舍，以及余弦相似度。");
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("取前 K 个片段。"));

        AnnotationRow row = service.ask(user, new AnnotationService.AskRequest(
                TargetType.NOTE, String.valueOf(noteId), "p-1", "topK 片段", "它是什么？", null));

        assertThat(row.answer()).isEqualTo("取前 K 个片段。");
        assertThat(repo.countByTarget(user, TargetType.NOTE, String.valueOf(noteId))).isEqualTo(1);
        // 全文确实来自那条 NOTE 消息：模型收到的 user prompt 里必须有笔记正文，而不是空串
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getContents())
                .contains("【全文（前 2000 字）】", "笔记正文里提到", "余弦相似度", "topK 片段");

        UUID other = newUser();
        assertThatThrownBy(() -> service.ask(other, new AnnotationService.AskRequest(
                TargetType.NOTE, String.valueOf(noteId), "p-1", "q", "q", null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.list(other, TargetType.NOTE, String.valueOf(noteId)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void note归属_非NOTE消息与非法id在模型调用前被拒() {
        UUID user = newUser();
        UUID conv = jdbc.queryForObject(
                "insert into t_conversation(user_id, mode) values (?, 'FREE_CHAT') returning id",
                UUID.class, user);
        long plainId = jdbc.queryForObject("""
                        insert into t_message(conversation_id, role, type, content)
                        values (?, 'ASSISTANT', 'ANSWER', '普通回答') returning id
                        """, Long.class, conv);

        // 本人会话里的普通 ANSWER 不能当笔记（noteBelongsToUser 内含 type='NOTE'）
        assertThatThrownBy(() -> service.ask(user, new AnnotationService.AskRequest(
                TargetType.NOTE, String.valueOf(plainId), "p-1", "q", "q", null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.list(user, TargetType.NOTE, String.valueOf(plainId)))
                .isInstanceOf(ForbiddenException.class);
        // 非法 targetId 在解析阶段就 400
        assertThatThrownBy(() -> service.ask(user, new AnnotationService.AskRequest(
                TargetType.NOTE, "not-a-number", "p-1", "q", "q", null)))
                .isInstanceOf(BadRequestException.class);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void service_不再注入JdbcTemplate_NOTE能力来自MessageRepository() {
        List<Class<?>> fieldTypes = Arrays.stream(AnnotationService.class.getDeclaredFields())
                .map(Field::getType).toList();
        assertThat(fieldTypes).contains(MessageRepository.class);
        assertThat(fieldTypes).doesNotContain(JdbcTemplate.class);
    }

    @Test
    void list_update_delete_归属隔离与可编辑范围() {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner, FULL_TEXT).toString();
        long id = insertTop(owner, targetId, "原句", "原答案");

        assertThat(service.list(owner, TargetType.SECTION, targetId)).hasSize(1);
        assertThatThrownBy(() -> service.list(other, TargetType.SECTION, targetId))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.updateAnswer(other, id, "篡改"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(other, id))
                .isInstanceOf(ForbiddenException.class);

        AnnotationRow updated = service.updateAnswer(owner, id, "改过的答案");
        assertThat(updated.answer()).isEqualTo("改过的答案");
        assertThat(updated.edited()).isTrue();
        assertThat(updated.quote()).isEqualTo("原句");

        long child = repo.insert(owner, new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, targetId, id, "p-1", "词", "q", "子答案"));
        assertThat(service.delete(owner, id)).isEqualTo(2);
        assertThat(repo.findById(child)).isEmpty();
    }

    @Test
    void 上下文组装_纯函数() {
        String full = "START_MARK" + "前缀".repeat(700) + "【召回 topK 片段】"
                + "后缀".repeat(700) + "END_MARK";
        String top = AnnotationService.buildTopUserMessage(full, "召回 topK 片段", "topK 是什么？");
        assertThat(top).contains("【全文（前 2000 字）】", "【所在段落】", "【选中片段】", "【问题】", "topK 是什么？");
        String block = top.split("【所在段落】\n")[1].split("\n\n【选中片段】")[0];
        assertThat(block).contains("【召回 topK 片段】")   // quote 定位生效，没退化成文章开头
                .doesNotContain("START_MARK", "END_MARK");

        assertThat(AnnotationService.truncate("字".repeat(500), 200)).hasSize(200);

        // 造 5 层链：父 + 4 个更早的祖先，前文解释只应保留最近 3 条
        List<AnnotationRepository.AnnotationEntity> ancestors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ancestors.add(new AnnotationRepository.AnnotationEntity(
                    100L - i, UUID.randomUUID(), TargetType.SECTION, "t",
                    i == 4 ? null : 101L - i,
                    "p-1",
                    i == 4 ? "正文原句" : "词",
                    "q",
                    "解释" + i,
                    false, null));
        }
        String child = AnnotationService.buildChildUserMessage(
                ancestors.get(0), ancestors, "余弦相似度", "怎么算？");
        assertThat(child).contains("【正文原句】", "正文原句", "【父批注答案】", "解释0",
                "【本次选中的词】", "余弦相似度", "【问题】", "怎么算？");
        assertThat(child).contains("解释1", "解释2", "解释3").doesNotContain("解释4");
    }
}
