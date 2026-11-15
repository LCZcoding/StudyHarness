package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.model.AnnotationRow;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.model.TargetType;
import com.lcz.aiagentstudy.repository.AnnotationRepository;
import com.lcz.aiagentstudy.repository.KnowledgeItemRepository;
import com.lcz.aiagentstudy.repository.MessageRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 批注业务。对应 plan.md 5.2 #4~#7 与附录 A2/A3/A4。
 * ask() 不标 @Transactional：远程模型调用不能占着数据库连接。
 * v1.1 / T005：NOTE 的归属与全文统一走 T004 提供的 MessageRepository，Service 内不再有 SQL，也不注入 JdbcTemplate。
 */
@Service
@RequiredArgsConstructor
public class AnnotationService {

    private static final String EXPLAIN_SYSTEM = """
            你是嵌在笔记右侧的批注助手。用户选中了正文里的一句话并提了一个问题。

            输出要求（必须严格遵守）：
            - 一段话，60~120 字，中文。
            - 不分点、不列清单、不加标题、不使用 Markdown 列表、不复述用户的问题。
            - 只解释「选中片段」在「给定全文语境」里的意思，不要写成百科词条。
            - 用最少的术语；必须出现术语时，顺带用半句话解释它。
            - 不确定就说不确定，不编造事实。
            """;

    private static final String EXPLAIN_CHILD_SYSTEM = """
            你是嵌在笔记右侧的批注助手。用户正在读上一条批注的解释，又选中了其中一个陌生词继续追问。

            输出要求（必须严格遵守）：
            - 一段话，60~120 字，中文。
            - 不分点、不列清单、不加标题、不使用 Markdown 列表、不复述用户的问题。
            - 必须结合「正文原句」和「前文解释」的语境来解释这个词，不要泛泛给百科定义。
            - 不要重复前文解释已经说过的内容。
            - 不确定就说不确定。
            """;

    private static final int FULL_TEXT_LIMIT = 2000;
    private static final int BLOCK_WINDOW = 300;
    private static final int ANSWER_LIMIT = 200;
    private static final int MAX_QUOTE = 300;
    private static final int MAX_QUESTION = 500;
    private static final int MAX_BLOCK_KEY = 32;
    private static final int MAX_TARGET_ID = 64;
    private static final int MAX_EDIT_ANSWER = 2000;
    private static final int MAX_TARGET_COUNT = 200;   // I3
    private static final int MAX_CHAIN_DEPTH = 8;      // I2
    private static final int MAX_ANCESTORS = 3;

    private final AnnotationRepository annotationRepository;
    private final KnowledgeItemRepository knowledgeItemRepository;
    private final MessageRepository messageRepository;   // T004：NOTE 归属校验与全文取数
    private final AiCallRecorder aiCallRecorder;
    private final ChatModel deepSeekChatModel;

    /** #5 请求体。Bean Validation 由 Controller 的 @Valid 触发；Service 里另有一层硬校验。 */
    public record AskRequest(
            @NotNull TargetType targetType,
            @NotBlank @Size(max = 64) String targetId,
            @NotBlank @Size(max = 32) String blockKey,
            @NotBlank @Size(max = 300) String quote,
            @NotBlank @Size(max = 500) String question,
            Long parentId
    ) {
    }

    /** #4 扁平列表。先校验目标归属，再查（Repository 另有一层 user_id 过滤）。 */
    public List<AnnotationRow> list(UUID userId, TargetType targetType, String targetId) {
        assertTargetOwned(targetType, targetId, userId);
        return annotationRepository.findByTarget(userId, targetType, targetId);
    }

    /** #5 生成批注。7 步顺序：校验 → 解析 → 归属 → 父链/I1/I2 → I3 → 模型 → 落库。 */
    public AnnotationRow ask(UUID userId, AskRequest req) {
        validate(req);                                   // 1
        assertTargetOwned(req.targetType(), req.targetId(), userId); // 2 + 3

        Long parentId = req.parentId();
        AnnotationRepository.AnnotationEntity parent = null;
        List<AnnotationRepository.AnnotationEntity> ancestors = List.of();
        String blockKey = req.blockKey();

        if (parentId != null) {                          // 4
            parent = annotationRepository.findById(parentId)
                    .orElseThrow(() -> new BadRequestException("父批注不存在"));
            if (!parent.userId().equals(userId)) {
                throw new ForbiddenException("无权引用该批注");
            }
            if (parent.targetType() != req.targetType() || !parent.targetId().equals(req.targetId())) {
                throw new BadRequestException("父批注与本次目标不一致");
            }
            blockKey = parent.blockKey();                // 子批注继承父的 blockKey
            if (annotationRepository.depthOf(parentId) >= MAX_CHAIN_DEPTH) {
                throw new BadRequestException("追问层级已达上限");
            }
            ancestors = collectAncestors(parent);
        }

        if (annotationRepository.countByTarget(userId, req.targetType(), req.targetId())
                >= MAX_TARGET_COUNT) {                   // 5
            throw new BadRequestException("该正文的批注数已达上限");
        }

        String fullText = parent == null ? loadFullText(req.targetType(), req.targetId()) : "";
        String system = parent == null ? EXPLAIN_SYSTEM : EXPLAIN_CHILD_SYSTEM;
        String userMessage = parent == null
                ? buildTopUserMessage(fullText, req.quote(), req.question())
                : buildChildUserMessage(parent, ancestors, req.quote(), req.question());
        String scene = parent == null ? "ANNOTATION" : "ANNOTATION_CHILD";

        String answer = callModel(userId, scene, system, userMessage);   // 6
        long id = annotationRepository.insert(userId, new AnnotationRepository.NewAnnotation(
                req.targetType(), req.targetId(), parentId, blockKey,
                req.quote(), req.question(), answer));                    // 7
        return annotationRepository.findById(id).orElseThrow().toRow();
    }

    /** #6 只改答案。归属校验在前，quote/question 永不可改。 */
    public AnnotationRow updateAnswer(UUID userId, long id, String answer) {
        if (!annotationRepository.belongsTo(id, userId)) {
            throw new ForbiddenException("无权修改该批注");
        }
        if (answer == null || answer.isBlank() || answer.length() > MAX_EDIT_ANSWER) {
            throw new BadRequestException("答案不合法");
        }
        annotationRepository.updateAnswer(id, answer);
        return annotationRepository.findById(id).orElseThrow().toRow();
    }

    /** #7 删除。deletedCount 含自身，仅用于前端结果提示；实际删除靠 FK 级联。 */
    public int delete(UUID userId, long id) {
        if (!annotationRepository.belongsTo(id, userId)) {
            throw new ForbiddenException("无权删除该批注");
        }
        int deletedCount = annotationRepository.countDescendants(id);
        annotationRepository.deleteById(id);
        return deletedCount;
    }

    // ---------- 校验 ----------

    private static void validate(AskRequest req) {
        if (req == null || req.targetType() == null) {
            throw new BadRequestException("targetType 不能为空");
        }
        if (isBlank(req.targetId()) || req.targetId().length() > MAX_TARGET_ID) {
            throw new BadRequestException("targetId 不合法");
        }
        if (isBlank(req.blockKey()) || req.blockKey().length() > MAX_BLOCK_KEY) {
            throw new BadRequestException("blockKey 不合法");
        }
        if (isBlank(req.quote()) || req.quote().length() > MAX_QUOTE) {
            throw new BadRequestException("引用片段不合法");
        }
        if (isBlank(req.question()) || req.question().length() > MAX_QUESTION) {
            throw new BadRequestException("问题不合法");
        }
    }

    /** 同时完成 targetId 解析（失败 400）与归属校验（失败 403）。SECTION/NOTE 走不同表。 */
    private void assertTargetOwned(TargetType type, String targetId, UUID userId) {
        if (type == TargetType.SECTION) {
            UUID id;
            try {
                id = UUID.fromString(targetId);
            } catch (RuntimeException e) {
                throw new BadRequestException("targetId 不合法");
            }
            if (!knowledgeItemRepository.belongsTo(id, userId)) {
                throw new ForbiddenException("无权访问该正文");
            }
            return;
        }
        long id;
        try {
            id = Long.parseLong(targetId);
        } catch (RuntimeException e) {
            throw new BadRequestException("targetId 不合法");
        }
        if (!messageRepository.noteBelongsToUser(id, userId)) {   // T004：plan 4.4 的跨两层 JOIN
            throw new ForbiddenException("无权访问该正文");
        }
    }

    /** 从 parent 一路向上到根，返回 [parent, grandparent, ..., root]。 */
    private List<AnnotationRepository.AnnotationEntity> collectAncestors(
            AnnotationRepository.AnnotationEntity parent) {
        List<AnnotationRepository.AnnotationEntity> chain = new ArrayList<>();
        AnnotationRepository.AnnotationEntity cur = parent;
        while (cur != null && chain.size() <= MAX_CHAIN_DEPTH) {
            chain.add(cur);
            cur = cur.parentId() == null ? null
                    : annotationRepository.findById(cur.parentId()).orElse(null);
        }
        return chain;
    }

    // ---------- 上下文（纯函数，便于单测） ----------

    /** A4 顶层：全文 + 所在段落 + 选中片段 + 问题。 */
    static String buildTopUserMessage(String fullText, String quote, String question) {
        return "【全文（前 2000 字）】\n" + truncate(fullText, FULL_TEXT_LIMIT)
                + "\n\n【所在段落】\n" + locateBlock(fullText, quote)
                + "\n\n【选中片段】\n" + quote
                + "\n\n【问题】\n" + question;
    }

    /** A4 子批注：正文原句 + 前文解释（最多 3 层，不含父）+ 父答案 + 本次选中的词 + 问题。 */
    static String buildChildUserMessage(AnnotationRepository.AnnotationEntity parent,
                                        List<AnnotationRepository.AnnotationEntity> ancestors,
                                        String quote, String question) {
        List<AnnotationRepository.AnnotationEntity> rootToParent = new ArrayList<>(ancestors);
        Collections.reverse(rootToParent);                 // [root, ..., parent]
        String rootQuote = rootToParent.isEmpty() ? "" : rootToParent.get(0).quote();
        List<AnnotationRepository.AnnotationEntity> earlier =
                rootToParent.isEmpty() ? List.of() : rootToParent.subList(0, rootToParent.size() - 1);
        int from = Math.max(0, earlier.size() - MAX_ANCESTORS);
        List<String> earlierAnswers = new ArrayList<>();
        for (AnnotationRepository.AnnotationEntity a : earlier.subList(from, earlier.size())) {
            earlierAnswers.add(a.answer());
        }
        return "【正文原句】\n" + rootQuote
                + "\n\n【前文解释】\n" + String.join("\n---\n", earlierAnswers)
                + "\n\n【父批注答案】\n" + parent.answer()
                + "\n\n【本次选中的词】\n" + quote
                + "\n\n【问题】\n" + question;
    }

    /**
     * 后端没有前端的 splitBlocks，无法按 blockKey 取段落。
     * 用 quote 在全文中的位置取前后各 300 字；找不到就退化成全文前 2000 字（plan 6.1 的兜底）。
     */
    static String locateBlock(String fullText, String quote) {
        if (fullText == null || fullText.isBlank()) {
            return "";
        }
        int pos = quote == null ? -1 : fullText.indexOf(quote);
        if (pos < 0) {
            return truncate(fullText, FULL_TEXT_LIMIT);
        }
        int start = Math.max(0, pos - BLOCK_WINDOW);
        int end = Math.min(fullText.length(), pos + quote.length() + BLOCK_WINDOW);
        return (start > 0 ? "……" : "") + fullText.substring(start, end)
                + (end < fullText.length() ? "……" : "");
    }

    static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    // ---------- 模型与全文 ----------

    private String callModel(UUID userId, String scene, String system, String userMessage) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(system).build();
        long startedAt = aiCallRecorder.start();
        ChatResponse response;
        try {
            response = client.prompt().user(userMessage).call().chatResponse();
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, scene, startedAt, e);
            throw new IllegalStateException("批注生成失败，请重试", e);
        }
        aiCallRecorder.recordCall(userId, scene, startedAt, response);
        String content = response == null || response.getResult() == null
                ? null : response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("批注生成失败，请重试");
        }
        return truncate(content.trim(), ANSWER_LIMIT);   // 200 字硬截断，不重试
    }

    private String loadFullText(TargetType type, String targetId) {
        if (type == TargetType.SECTION) {
            return knowledgeItemRepository.findById(UUID.fromString(targetId))
                    .map(KnowledgeItemRow::content).orElse("");
        }
        return messageRepository.findNoteById(Long.parseLong(targetId))   // T004：不写 SQL
                .map(MessageRepository.NoteMessage::content)
                .orElse("");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
