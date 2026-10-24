package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.model.ConversationMode;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizService {

    /** 评测集（test 包）也要用同一段提示词，所以不给 private。 */
    static final String SYSTEM_PROMPT = """
            你是出题官，只出题、不答题。
            规则：
            1. 必须严格基于用户给出的材料出题，不得引入材料以外的知识点。
            2. 只输出任务，绝不包含答案、提示、解释。题面只能点明考察对象，不能把结论写进题面：
               例如「为什么优化器会判断全表扫描更便宜」把答案写进了题面，
               应改为「优化器在什么情况下会放弃使用索引」。
            3. 任务分两类：
               - FACT：一个明确的回忆问题（是什么 / 为什么 / 怎么做），不要选择题。
               - EXPLAIN：讲解任务。必须给听众设定（例如"只会 Python、没听过向量检索的人"），
                 要求用户用自己的话讲清楚因果，而不是背结论。
            4. 每个任务给三个字段：
               - kind：FACT 或 EXPLAIN（大写）
               - target：这道题考的知识点，一个名词短语（例如"HNSW 的 m 参数"），不超过 40 字
               - text：题面，一句话，20 到 80 字
            5. 每个任务的 target 不能重复。
            """;

    private final ChatModel deepSeekChatModel;
    private final KnowledgeService knowledgeService;
    private final KnowledgePointRepository knowledgePointRepository;
    private final QuestionRepository questionRepository;
    private final QuizStore quizStore;
    private final AiCallRecorder aiCallRecorder;



    // 出题只看材料前 12000 字：再长会顶到模型上下文上限，
    // 且「一次出 3 题」本来就覆盖不了整份讲义（写进技术债第 7 条）
    private static final int MATERIAL_LIMIT = 12_000;

    public record TaskDraft(String kind, String target, String text) {
    }

    public record QuizDraft(List<TaskDraft> tasks) {
    }

    public QuizStore.CreatedQuiz generate(UUID userId, UUID knowledgeItemId) {
        KnowledgeItemRow item = knowledgeService.requireOwned(knowledgeItemId, userId);

        // 同一份资料还有没答完的轮次就原样返回：用户中途退出再进来，看到的是上次那几道题，
        // 已作答的结论也还在。只有全部答完（或全部跳过）才允许开新一轮。
        Optional<UUID> unfinished =
                questionRepository.findLatestUnfinishedSelfTest(userId, knowledgeItemId);
        if (unfinished.isPresent()) {
            List<QuizStore.CreatedQuestion> questions = questionRepository
                    .findByConversation(unfinished.get()).stream()
                    .map(r -> new QuizStore.CreatedQuestion(r.id(), r.kind(), r.target(), r.text()))
                    .toList();
            return new QuizStore.CreatedQuiz(unfinished.get(), questions);
        }

        Optional<QuestionRepository.RetryQuestion> retry =
                questionRepository.findRetryQuestion(userId, knowledgeItemId);

        // 有旧题就重问 1 道 + 新出 2 道；没有就新出 3 道
        int newCount = retry.isPresent() ? 2 : 3;
        boolean requireExplain = retry.map(r -> !"EXPLAIN".equals(r.kind())).orElse(true);

        List<String> verified = knowledgePointRepository.findRecentVerified(userId, 5);
        List<TaskDraft> tasks = draft(userId, item.content(), newCount, requireExplain, verified);

        return quizStore.createSelfTest(userId, item, retry.orElse(null), tasks);
    }

    /** 复习的交错题：只出 1 个。出不来就返回 empty，不阻断这次复习。 */
    public Optional<TaskDraft> draftOne(UUID userId, KnowledgeItemRow item, List<String> verified) {
        try {
            return draft(userId, item.content(), 1, false, verified).stream().findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<TaskDraft> draft(UUID userId, String content, int count, boolean requireExplain,
                                  List<String> verified) {
        ChatClient client = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();

        long startedAt = aiCallRecorder.start();
        ResponseEntity<ChatResponse, QuizDraft> result;
        try {
            result = client.prompt()
                    .user(buildUserMessage(truncate(content), count, requireExplain, verified))
                    .call()
                    .responseEntity(QuizDraft.class);
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "QUIZ_GENERATE", startedAt, e);
            throw e;
        }
        aiCallRecorder.recordCall(userId, "QUIZ_GENERATE", startedAt, result.getResponse());

        QuizDraft draft = result.getEntity();
        // ↓ 原方法剩下的校验与归一化原样保留

        if (draft == null || draft.tasks() == null || draft.tasks().size() != count) {
            throw new IllegalStateException("模型未按要求返回 " + count + " 个任务，请重试");
        }

        List<TaskDraft> normalized = new ArrayList<>(count);
        for (TaskDraft t : draft.tasks()) {
            if (t == null || t.kind() == null || t.target() == null || t.text() == null
                    || t.target().isBlank() || t.text().isBlank()) {
                throw new IllegalStateException("模型返回的任务缺少 kind/target/text，请重试");
            }
            String kind = t.kind().trim().toUpperCase(Locale.ROOT);
            if (!"FACT".equals(kind) && !"EXPLAIN".equals(kind)) {
                throw new IllegalStateException("模型返回了未知任务类型: " + t.kind());
            }
            normalized.add(new TaskDraft(kind, t.target().trim(), t.text().trim()));
        }

        if (requireExplain && normalized.stream().noneMatch(t -> "EXPLAIN".equals(t.kind()))) {
            throw new IllegalStateException("模型没有给出 EXPLAIN 任务，请重试");
        }
        return normalized;
    }

    private String buildUserMessage(String content, int count, boolean requireExplain,
                                    List<String> verified) {
        StringBuilder sb = new StringBuilder();
        sb.append("材料如下：\n\n").append(content).append("\n\n");
        sb.append("请出 ").append(count).append(" 个任务。");
        sb.append(requireExplain ? "其中至少 1 个是 EXPLAIN。" : "类型 FACT / EXPLAIN 不限。");
        if (!verified.isEmpty()) {
            sb.append("\n\n用户已掌握这些概念：").append(String.join("、", verified))
              .append("。如果本次任务里有 EXPLAIN，请让它要求用户用已掌握的概念做类比。");
        }
        return sb.toString();
    }

    private static String truncate(String material) {
        if (material == null) {
            return "";
        }
        return material.length() <= MATERIAL_LIMIT
                ? material : material.substring(0, MATERIAL_LIMIT) + "\n……（材料已截断）";
    }

    private final GradingService gradingService;
    private final HelpService helpService;
    private final AttemptStore attemptStore;
    private final HelpTurnRepository helpTurnRepository;

    /** 求助：先回答用户的疑问，再把这题标记为"求助过"。不改判分、不产生成绩。 */
    public String help(UUID userId, long questionId, String userQuestion) {
        QuestionRepository.QuestionContext question = requireOwnedQuestion(userId, questionId);
        String answer = helpService.answer(userId, question.material(), question.text(), userQuestion);
        questionRepository.markHelpUsed(questionId);
        // 落库放在模型调用之后：先算出一问一答，再一次性写两行，回放时不出现「有问没答」
        helpTurnRepository.saveTurn(questionId, userQuestion, answer);
        return answer;
    }

    /** 回放某道题的全部求助记录：刷新页面或重开抽屉时，之前的问答都还在。 */
    public List<HelpTurnRepository.HelpTurn> helpHistory(UUID userId, long questionId) {
        requireOwnedQuestion(userId, questionId);
        return helpTurnRepository.findByQuestion(questionId);
    }

    public AttemptStore.AttemptResult attempt(UUID userId, long questionId, String answer) {
        QuestionRepository.QuestionContext question = requireOwnedQuestion(userId, questionId);
        GradingService.Grading grading = gradingService.grade(
                userId, question.material(), question.target(), question.kind(),
                question.text(), answer);
        return attemptStore.record(userId, question, answer, grading);
    }

    /** 跳过这道题：不判分、不产生成绩，只让当前这轮不再等它；排期往后推 1 天。 */
    public OffsetDateTime skip(UUID userId, long questionId) {
        requireOwnedQuestion(userId, questionId);
        if (questionRepository.hasRecord(questionId)) {
            throw new BadRequestException("这道题已经作答，不能跳过");
        }
        if (questionRepository.isSkipped(questionId)) {
            throw new BadRequestException("这道题已经跳过");
        }
        questionRepository.markSkipped(questionId);
        // 自测题没有复习排期，跳过就只是跳过，不必造一行排期
        return reviewScheduleRepository.findByQuestion(questionId).isPresent()
                ? reviewScheduleRepository.postpone(questionId, 1)
                : null;
    }

    private QuestionRepository.QuestionContext requireOwnedQuestion(UUID userId, long questionId) {
        QuestionRepository.QuestionContext question = questionRepository.findWithOwner(questionId)
                .stream().findFirst()
                // 与"不存在"同一句文案：不让攻击者用状态码探测题目是否存在
                .orElseThrow(() -> new ForbiddenException("题目不存在或无权访问"));
        if (!question.userId().equals(userId)) {
            throw new ForbiddenException("题目不存在或无权访问");
        }
        return question;
    }

    // 追加依赖
    private final ConversationService conversationService;
    private final KnowledgeItemRepository knowledgeItemRepository;
    private final ReviewScheduleRepository reviewScheduleRepository;
    private final ConversationRepository conversationRepository;

    public List<QuestionRepository.QuestionRow> listByConversation(UUID userId, UUID conversationId) {
        conversationService.assertOwned(conversationId, userId);
        return questionRepository.findByConversation(conversationId);
    }

    /**
     * 2 道到期 + 1 道交错。交错题来自「另一份资料」，这是产品里唯一一处跨资料混排，
     * 也是"知识网络"能落地的最小形态。拿不到交错题就全用到期题（最多 3 道）。
     */
    public QuizStore.CreatedQuiz startReview(UUID userId) {
        // 保证幂等
        Optional<UUID> latest = conversationRepository.findLatestIdByMode(
                userId, ConversationMode.REVIEW.name());
        if (latest.isPresent()) {
            List<QuestionRepository.QuestionRow> rest = questionRepository.findUnanswered(latest.get());
            if (!rest.isEmpty()) {
                List<QuizStore.CreatedQuestion> questions = rest.stream()
                        .map(r -> new QuizStore.CreatedQuestion(r.id(), r.kind(), r.target(), r.text()))
                        .toList();
                return new QuizStore.CreatedQuiz(latest.get(), questions);
            }
        }

        List<ReviewScheduleRepository.DueItem> due =
                reviewScheduleRepository.findDue(userId, 3);
        if (due.isEmpty()) {
            throw new BadRequestException("现在没有到期需要复习的题");
        }

        UUID interleavedItemId = null;
        TaskDraft interleaved = null;
        Optional<UUID> anotherItemId =
                knowledgeItemRepository.findAnotherItemId(userId, due.get(0).knowledgeItemId());
        if (anotherItemId.isPresent()) {
            KnowledgeItemRow item = knowledgeService.requireOwned(anotherItemId.get(), userId);
            List<String> verified = knowledgePointRepository.findRecentVerified(userId, 5);
            Optional<TaskDraft> draft = draftOne(userId, item, verified);
            if (draft.isPresent()) {
                interleaved = draft.get();
                interleavedItemId = item.id();
            }
        }

        // 交错题出不来（只有一份资料 / 模型失败）就把第 3 道到期题补上，不阻断这次复习
        List<ReviewScheduleRepository.DueItem> picked =
                interleaved == null ? due : due.subList(0, Math.min(2, due.size()));
        return quizStore.createReview(userId, picked, interleavedItemId, interleaved);
    }

}