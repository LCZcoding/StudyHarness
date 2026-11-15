package com.lcz.aiagentstudy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcz.aiagentstudy.constant.SessionKeys;
import com.lcz.aiagentstudy.repository.AnnotationRepository;
import com.lcz.aiagentstudy.model.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T006 测试：批注四接口的 MockMvc 端到端。真实 HTTP → 真实库（类上 @Transactional 回滚），
 * 模型用 @MockitoBean 顶掉，不发真实请求。覆盖 TC-03 / TC-04 / TC-05 / TC-06 的 HTTP 面。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnnotationControllerTest {

    private static final int MAX_DEPTH = 8;
    private static final String FULL_TEXT = "开头讲线程池核心参数。\n\n"
            + "第二段解释「召回 topK 片段」的做法，topK 表示取前 K 个最相似的片段。\n\n"
            + "第三段讲余弦相似度与归一化。";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AnnotationRepository repo;

    @MockitoBean(name = "deepSeekChatModel")
    ChatModel chatModel;

    // ---------- 造数据 ----------

    private UUID newUser() {
        return jdbc.queryForObject(
                "insert into t_user(username, password_hash) values (?, ?) returning id",
                UUID.class, UUID.randomUUID().toString(), "test-hash");
    }

    private UUID newSection(UUID userId) {
        return jdbc.queryForObject("""
                        insert into t_knowledge_item (user_id, title, content, doc_title, section_no)
                        values (?, '批注接口测试', ?, '批注接口测试', 1) returning id
                        """,
                UUID.class, userId, FULL_TEXT);
    }

    private long insertTop(UUID userId, String targetId, String quote, String answer) {
        return repo.insert(userId, new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, targetId, null, "p-1", quote, "原问题", answer));
    }

    /** JSON 用 ObjectMapper 生成 UTF-8 字节，避免 MockMvc 的中文编码坑。 */
    private byte[] body(Object... kv) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return objectMapper.writeValueAsBytes(m);
    }

    private byte[] askBody(String targetId, Long parentId) throws Exception {
        return body("targetType", "SECTION", "targetId", targetId, "blockKey", "p-1",
                "quote", "召回 topK 片段", "question", "topK 是什么？", "parentId", parentId);
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private MockHttpServletRequestBuilder as(UUID userId, MockHttpServletRequestBuilder builder) {
        return builder.sessionAttr(SessionKeys.USER_ID, userId);
    }

    // ---------- 四个接口 ----------

    @Test
    void 四个接口未登录都_401() throws Exception {
        mockMvc.perform(get("/annotations").param("targetType", "SECTION")
                        .param("targetId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());

        mockMvc.perform(post("/annotations/ask").contentType(MediaType.APPLICATION_JSON)
                        .content(askBody(UUID.randomUUID().toString(), null)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());

        mockMvc.perform(patch("/annotations/1").contentType(MediaType.APPLICATION_JSON)
                        .content(body("answer", "改一下")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());

        mockMvc.perform(delete("/annotations/1"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());
    }

    @Test
    void get_参数形状不合法_400且带message() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();

        mockMvc.perform(as(user, get("/annotations")
                        .param("targetType", "BOGUS").param("targetId", targetId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        mockMvc.perform(as(user, get("/annotations").param("targetId", targetId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        mockMvc.perform(as(user, get("/annotations").param("targetType", "SECTION")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        // targetId 超 64 字：在进 Service 之前就由 Controller 拒掉
        mockMvc.perform(as(user, get("/annotations").param("targetType", "SECTION")
                        .param("targetId", "x".repeat(65))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    @Test
    void get_返回扁平列表且不下发内部字段() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        long parent = insertTop(user, targetId, "召回 topK 片段", "取前 K 个。");
        long child = repo.insert(user, new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, targetId, parent, "p-1", "余弦相似度", "怎么算？", "夹角余弦。"));

        mockMvc.perform(as(user, get("/annotations")
                        .param("targetType", "SECTION").param("targetId", targetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(parent))
                .andExpect(jsonPath("$[0].parentId").value(nullValue()))
                .andExpect(jsonPath("$[0].blockKey").value("p-1"))
                .andExpect(jsonPath("$[0].quote").value("召回 topK 片段"))
                .andExpect(jsonPath("$[0].question").value("原问题"))
                .andExpect(jsonPath("$[0].edited").value(false))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].id").value(child))
                .andExpect(jsonPath("$[1].parentId").value(parent))
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].targetType").doesNotExist())
                .andExpect(jsonPath("$[0].targetId").doesNotExist());
    }

    @Test
    void get_他人正文_403() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner).toString();

        mockMvc.perform(as(other, get("/annotations")
                        .param("targetType", "SECTION").param("targetId", targetId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").exists());
    }

    // ---------- TC-03 ----------

    @Test
    void ask_顶层批注_落库埋点与响应形状() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("取前 K 个相似的片段。"));

        mockMvc.perform(as(user, post("/annotations/ask")
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(targetId, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.blockKey").value("p-1"))
                .andExpect(jsonPath("$.quote").value("召回 topK 片段"))
                .andExpect(jsonPath("$.answer").value("取前 K 个相似的片段。"))
                .andExpect(jsonPath("$.edited").value(false));

        assertThat(repo.countByTarget(user, TargetType.SECTION, targetId)).isEqualTo(1);

        // TC-03「落库字段正确」：响应形状之外的库内字段也要对得上
        Map<String, Object> stored = jdbc.queryForMap("""
                select user_id, target_type, target_id, parent_id, block_key,
                       quote, question, answer, edited
                  from t_annotation where target_id = ?
                """, targetId);
        assertThat(stored.get("user_id")).isEqualTo(user);
        assertThat(stored.get("target_type")).isEqualTo("SECTION");
        assertThat(stored.get("target_id")).isEqualTo(targetId);
        assertThat(stored.get("parent_id")).isNull();
        assertThat(stored.get("block_key")).isEqualTo("p-1");
        assertThat(stored.get("quote")).isEqualTo("召回 topK 片段");
        assertThat(stored.get("question")).isEqualTo("topK 是什么？");
        assertThat(stored.get("answer")).isEqualTo("取前 K 个相似的片段。");
        assertThat(stored.get("edited")).isEqualTo(false);

        String scene = jdbc.queryForObject(
                "select scene from t_ai_call where user_id = ? order by id desc limit 1",
                String.class, user);
        assertThat(scene).isEqualTo("ANNOTATION");
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void ask_子批注_继承父blockKey且scene为CHILD() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        long parent = insertTop(user, targetId, "召回 topK 片段", "取前 K 个。");
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf("夹角的余弦值。"));

        // 故意传错的 blockKey p-99，Service 必须改成父的 p-1
        byte[] req = body("targetType", "SECTION", "targetId", targetId, "blockKey", "p-99",
                "quote", "余弦相似度", "question", "怎么算？", "parentId", parent);
        mockMvc.perform(as(user, post("/annotations/ask")
                        .contentType(MediaType.APPLICATION_JSON).content(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(parent))
                .andExpect(jsonPath("$.blockKey").value("p-1"))
                .andExpect(jsonPath("$.answer").value("夹角的余弦值。"));

        String blockKey = jdbc.queryForObject("select block_key from t_annotation where id = "
                + "(select max(id) from t_annotation)", String.class);
        assertThat(blockKey).isEqualTo("p-1");
        assertThat(jdbc.queryForObject(
                "select scene from t_ai_call where user_id = ? order by id desc limit 1",
                String.class, user)).isEqualTo("ANNOTATION_CHILD");
    }

    // ---------- TC-04 ----------

    @Test
    void ask_越权targetId_403且模型未被调用() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner).toString();

        mockMvc.perform(as(other, post("/annotations/ask")
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(targetId, null))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").exists());

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void ask_他人parentId_403且模型未被调用() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner).toString();
        String otherTarget = newSection(other).toString();
        long othersAnnotation = insertTop(other, otherTarget, "q", "a");

        mockMvc.perform(as(owner, post("/annotations/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(askBody(targetId, othersAnnotation))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").exists());

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void ask_链深达8_400且模型未被调用() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        Long cursor = insertTop(user, targetId, "q", "a1");
        for (int i = 2; i <= MAX_DEPTH; i++) {
            cursor = repo.insert(user, new AnnotationRepository.NewAnnotation(
                    TargetType.SECTION, targetId, cursor, "p-1", "q", "q", "a" + i));
        }

        mockMvc.perform(as(user, post("/annotations/ask")
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(targetId, cursor))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        verify(chatModel, never()).call(any(Prompt.class));
    }

    /** G1 闸门点名的「限额在模型调用前被拒」：此前只有 Service 单测证据，这里补 HTTP 面。 */
    @Test
    void ask_限额I3满200_400且模型与埋点均未发生() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        jdbc.update("""
                        insert into t_annotation (user_id, target_type, target_id, block_key, quote, question, answer)
                        select ?, 'SECTION', ?, 'p-1', 'q', 'q', 'a' from generate_series(1, 200)
                        """, user, targetId);

        mockMvc.perform(as(user, post("/annotations/ask")
                        .contentType(MediaType.APPLICATION_JSON).content(askBody(targetId, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        // 拦截必须发生在 start() 之前：模型没被调、AI 调用没被记账、批注数也没变
        verify(chatModel, never()).call(any(Prompt.class));
        assertThat(repo.countByTarget(user, TargetType.SECTION, targetId)).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "select count(*) from t_ai_call where user_id = ? and scene = 'ANNOTATION'",
                Integer.class, user)).isZero();
    }

    @Test
    void ask_Valid校验_缺字段超长与非法枚举_400() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();

        // @NotNull targetType
        mockMvc.perform(as(user, post("/annotations/ask").contentType(MediaType.APPLICATION_JSON)
                        .content(body("targetId", targetId, "blockKey", "p-1",
                                "quote", "q", "question", "q"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        // @Size(max=300) quote
        mockMvc.perform(as(user, post("/annotations/ask").contentType(MediaType.APPLICATION_JSON)
                        .content(body("targetType", "SECTION", "targetId", targetId,
                                "blockKey", "p-1", "quote", "字".repeat(301), "question", "q"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        // 非法枚举由 Jackson 拒绝
        mockMvc.perform(as(user, post("/annotations/ask").contentType(MediaType.APPLICATION_JSON)
                        .content(body("targetType", "BOGUS", "targetId", targetId,
                                "blockKey", "p-1", "quote", "q", "question", "q"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ---------- TC-05 ----------

    @Test
    void patch_只改answer与edited_quote和question不动() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        long id = insertTop(user, targetId, "原句", "原答案");

        mockMvc.perform(as(user, patch("/annotations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body("answer", "改过的答案"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.answer").value("改过的答案"))
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.quote").value("原句"))
                .andExpect(jsonPath("$.question").value("原问题"));

        assertThat(jdbc.queryForObject("select edited from t_annotation where id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void patch_多传question或空体_400且库未变() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        long id = insertTop(user, targetId, "原句", "原答案");

        mockMvc.perform(as(user, patch("/annotations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("answer", "篡改", "question", "也想改"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        mockMvc.perform(as(user, patch("/annotations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        assertThat(jdbc.queryForObject("select answer from t_annotation where id = ?",
                String.class, id)).isEqualTo("原答案");
    }

    @Test
    void patch_越权_403() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner).toString();
        long id = insertTop(owner, targetId, "原句", "原答案");

        mockMvc.perform(as(other, patch("/annotations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body("answer", "篡改"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").exists());

        assertThat(jdbc.queryForObject("select answer from t_annotation where id = ?",
                String.class, id)).isEqualTo("原答案");
    }

    /**
     * 2026-11-15 浏览器实测补的回归：MockMvc 默认不带 Origin，Spring 的 CORS 校验根本不触发，
     * 所以 CorsConfig 的 allowedMethods 漏掉 PATCH 时，上面所有 patch 用例仍然全绿，
     * 真实浏览器却收到 403 "Invalid CORS request"（编辑保存失败）。
     * 这里同时验 OPTIONS 预检与带 Origin 的真实 PATCH，两条都必须放行。
     */
    @Test
    void patch_带浏览器Origin_预检和实际请求都不被CORS拦截() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        long id = insertTop(user, targetId, "原句", "原答案");

        mockMvc.perform(options("/annotations/{id}", id)
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));

        mockMvc.perform(as(user, patch("/annotations/{id}", id)
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON).content(body("answer", "带 Origin 也能保存"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("带 Origin 也能保存"));

        assertThat(jdbc.queryForObject("select answer from t_annotation where id = ?",
                String.class, id)).isEqualTo("带 Origin 也能保存");
    }

    // ---------- TC-06 ----------

    @Test
    void delete_级联删后代且deletedCount含自身() throws Exception {
        UUID user = newUser();
        String targetId = newSection(user).toString();
        long root = insertTop(user, targetId, "原句", "原答案");
        long child = repo.insert(user, new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, targetId, root, "p-1", "词", "q", "子答案"));
        long grandchild = repo.insert(user, new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, targetId, child, "p-1", "词2", "q", "孙答案"));

        mockMvc.perform(as(user, delete("/annotations/{id}", root)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(root))
                .andExpect(jsonPath("$.deletedCount").value(3));

        assertThat(jdbc.queryForObject(
                "select count(*) from t_annotation where target_id = ?", Integer.class, targetId))
                .isZero();
        assertThat(repo.findById(grandchild)).isEmpty();
    }

    @Test
    void delete_越权_403() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        String targetId = newSection(owner).toString();
        long id = insertTop(owner, targetId, "原句", "原答案");

        mockMvc.perform(as(other, delete("/annotations/{id}", id)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").exists());

        assertThat(repo.findById(id)).isPresent();
    }
}
