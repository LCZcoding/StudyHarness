package com.lcz.aiagentstudy.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出题提示词的离线评测。不连数据库、不建会话：只验"这段提示词能不能稳定产出合格题面"。
 *
 * 分两层：
 * 1. 规则层（硬断言）：题数、字段、kind、至少 1 个 EXPLAIN、target 长度与重复 —— 用代码判，不交给模型。
 * 2. 语义层（AI 裁判）：题面是否泄露答案、是否超纲、EXPLAIN 是否给了听众 —— 换 qwen-plus 判 deepseek 出的题。
 *
 * 语义层不硬断言，只写进 target/quiz-eval-report.md 由人工复审。
 * 人工把报告看几轮后，等 AI 判定与人工结论稳定一致，再把语义层升级成断言。
 */
@SpringBootTest
class QuizPromptEvalTest {

    /** 出题用 deepseek（与线上一致）。 */
    @Autowired
    private ChatModel deepSeekChatModel;

    /** 裁判用 qwen-plus（与出题模型不同，避免模型偏袒自己的输出）。bean 名固定是 dashScopeChatModel。 */
    @Autowired
    private ChatModel dashScopeChatModel;

    record Case(String name, String material, List<String> keyPoints) {
    }

    /** AI 裁判对单个任务的判定。index 从 0 开始，对应 3 个任务的位置。 */
    record TaskJudge(int index, boolean leaked, boolean outOfScope,
                     boolean explainHasAudience, String reason) {
    }

    record JudgeVerdict(List<TaskJudge> tasks) {
    }

    private static final String JUDGE_PROMPT = """
            你是出题质量审核员。用户会给你一份学习材料、材料要点、以及 3 个任务。
            判定主线只有一条：题面是否替用户说出了「用户本来要输出的那个答案」。

            1. leaked：题面是否已经把用户需要输出的答案主体说出来了。
               - 不算泄露：只点名概念，例如"布隆过滤器在缓存穿透中起什么作用"；
               - 不算泄露：给出前提或现象、让用户解释原因，例如"范围查询之后的列还能用于索引查找吗"；
               - 不算泄露：反事实提问，例如"如果只用两次握手会引发什么问题"，
                 用户仍要自己说出问题出在哪，这类问题有效；
               - 算泄露：直接给出结论、数值或选择，例如"应选 REQUIRES_NEW 而不是 NESTED"、
                 "隐式类型转换会导致索引失效"、"m 的默认值是 16"。
            2. outOfScope：题目考的知识点是否不在材料里。
            3. explainHasAudience：kind 为 EXPLAIN 时，题面是否指定了听众（例如"只会 Python 的人"）；
               kind 为 FACT 时一律填 true。
            4. reason：先写理由，不超过 40 字，再给结论。index 必须与输入顺序一致。
            """;

    private static final List<Case> CASES = List.of(
            new Case("HNSW 参数",
                    "HNSW 是分层可导航小世界图。m 表示每个节点在每层保留的最大邻居数，默认 16；"
                            + "ef_construction 控制建图时每层候选队列的大小，越大图质量越好、构建越慢；"
                            + "ef_search 控制查询时候选队列大小，越大召回越高、延迟越大。",
                    List.of("m 默认 16", "ef_construction 越大构建越慢", "ef_search 越大召回越高延迟越大")),
            new Case("TCP 三次握手",
                    "TCP 建立连接需要三次握手。客户端发送 SYN 并进入 SYN_SENT，服务端回复 SYN+ACK 进入 SYN_RCVD，"
                            + "客户端再发 ACK 后双方进入 ESTABLISHED。需要三次而不是两次，是因为要防止已失效的连接请求"
                            + "突然到达服务端，导致服务端白白建立连接。",
                    List.of("三次握手的报文顺序", "防止已失效的连接请求")),
            new Case("MySQL 最左前缀",
                    "联合索引 (a, b, c) 遵循最左前缀原则：where a=?、a=? and b=?、a=? and b=? and c=? 都能用上索引；"
                            + "跳过 a 直接查 b 或 c 用不上；范围查询之后的列无法继续用于索引查找。",
                    List.of("跳过 a 用不上索引", "范围查询之后的列无法继续用于索引查找")),
            new Case("JVM 可达性分析",
                    "JVM 判断对象是否存活使用可达性分析：从 GC Roots（栈帧局部变量、静态属性、常量、JNI 引用等）出发，"
                            + "沿引用链遍历，不可达的对象才可回收。引用计数无法处理循环引用，所以不用它作为主算法。",
                    List.of("GC Roots 包括栈帧局部变量等", "引用计数无法处理循环引用")),
            new Case("Spring 事务传播",
                    "REQUIRED：有事务就加入，没有就新建，默认值。REQUIRES_NEW：挂起外层事务，内部新建独立事务，"
                            + "内外互不影响，内层回滚不影响外层已提交部分。NESTED：基于保存点，外层回滚会带走内层。",
                    List.of("REQUIRED 是默认值", "REQUIRES_NEW 内外互不影响", "NESTED 外层回滚带走内层")),
            new Case("HashMap 扩容",
                    "HashMap 默认容量 16、负载因子 0.75，元素数超过容量×负载因子时扩容为两倍。"
                            + "JDK8 中扩容时若链表长度不超过 6 则保持链表，超过 8 且容量不小于 64 时转红黑树。",
                    List.of("默认容量 16、负载因子 0.75", "阈值为容量乘负载因子", "链表超过 8 且容量不小于 64 转红黑树")),
            new Case("缓存穿透",
                    "缓存穿透指查询不存在的数据，请求每次都打到数据库。常见解法：缓存空值并设短过期；"
                            + "布隆过滤器拦截不存在的 key；接口层做参数校验。与缓存击穿（热点 key 失效）不是一回事。",
                    List.of("缓存空值", "布隆过滤器拦截不存在的 key", "与缓存击穿的区别")),
            new Case("Vue 响应式",
                    "Vue 3 用 Proxy 拦截对象的 get/set 实现响应式；get 时收集依赖（track），set 时触发更新（trigger）。"
                            + "数组和对象都能被完整拦截，不再需要 Vue 2 的 defineProperty 递归改写。",
                    List.of("get 时 track、set 时 trigger", "Proxy 可拦截数组和对象", "不需要 defineProperty 递归")),
            new Case("HTTPS 握手",
                    "TLS 握手用于协商密钥：客户端发 ClientHello（支持的协议版本与密码套件、随机数），"
                            + "服务端回 ServerHello 并出示证书，客户端验证证书链后生成预主密钥并用服务端公钥加密发送，"
                            + "双方各自用随机数导出会话密钥，之后用对称加密通信。",
                    List.of("客户端验证证书链", "预主密钥用服务端公钥加密", "双方用随机数导出会话密钥")),
            new Case("索引失效场景",
                    "常见索引失效：对索引列做函数运算（where year(created_at)=2024）；隐式类型转换"
                            + "（字符串列与数字比较）；以 % 开头的模糊查询；or 条件中有一侧无索引；"
                            + "优化器判断全表扫描更便宜时也会放弃索引。",
                    List.of("函数运算导致失效", "隐式类型转换导致失效", "以 % 开头的模糊查询", "优化器估算代价后放弃索引"))
    );

    @Test
    void 出题评测_规则硬断言_语义交AI裁判并生成报告() throws IOException {
        ChatClient quizClient = ChatClient.builder(deepSeekChatModel)
                .defaultSystem(QuizService.SYSTEM_PROMPT)
                .build();
        ChatClient judgeClient = ChatClient.builder(dashScopeChatModel)
                .defaultSystem(JUDGE_PROMPT)
                .build();

        int rulePass = 0;
        int leaked = 0;
        int outOfScope = 0;
        int missingAudience = 0;
        int judgeFailed = 0;
        List<String> reviewItems = new ArrayList<>();

        StringBuilder report = new StringBuilder();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        report.append("# 出题评测报告 ").append(now).append("\n\n");

        StringBuilder detail = new StringBuilder();
        int ruleFailed = 0;

        for (Case c : CASES) {
            String userMessage = "材料如下：\n\n" + c.material()
                    + "\n\n请出 3 个任务。其中至少 1 个是 EXPLAIN。";

            List<QuizService.TaskDraft> tasks = null;
            List<String> ruleProblems = new ArrayList<>();
            try {
                QuizService.QuizDraft draft = quizClient.prompt().user(userMessage)
                        .call().entity(QuizService.QuizDraft.class);
                if (draft == null || draft.tasks() == null) {
                    ruleProblems.add("返回体没有 tasks");
                } else {
                    tasks = draft.tasks();
                    ruleProblems.addAll(ruleCheck(tasks));
                }
            } catch (RuntimeException e) {
                ruleProblems.add("解析失败: " + e.getMessage());
            }

            boolean ruleOk = ruleProblems.isEmpty();
            if (ruleOk) {
                rulePass++;
            } else {
                ruleFailed++;
            }
            System.out.printf("[规则%s] %s %s%n", ruleOk ? "PASS" : "FAIL", c.name(), String.join("; ", ruleProblems));

            List<TaskJudge> judges = List.of();
            if (ruleOk) {
                try {
                    judges = judge(judgeClient, c, tasks);
                } catch (RuntimeException e) {
                    judgeFailed++;
                    System.out.println("[裁判FAIL] " + c.name() + " " + e.getMessage());
                }
            }

            for (TaskJudge j : judges) {
                if (j.leaked()) {
                    leaked++;
                    reviewItems.add(c.name() + " 第" + (j.index() + 1) + "题 疑似泄露答案：" + j.reason());
                }
                if (j.outOfScope()) {
                    outOfScope++;
                    reviewItems.add(c.name() + " 第" + (j.index() + 1) + "题 疑似超纲：" + j.reason());
                }
                if (!j.explainHasAudience()) {
                    missingAudience++;
                    reviewItems.add(c.name() + " 第" + (j.index() + 1) + "题 EXPLAIN 缺听众设定：" + j.reason());
                }
            }

            appendDetail(detail, c, tasks, ruleProblems, judges);
        }

        report.append("## 汇总\n\n")
                .append("- 材料数：").append(CASES.size()).append('\n')
                .append("- 规则层通过：").append(rulePass).append('/').append(CASES.size()).append('\n')
                .append("- AI 裁判调用失败：").append(judgeFailed).append(" 份材料\n")
                .append("- 判定泄露答案：").append(leaked).append(" 条\n")
                .append("- 判定超纲：").append(outOfScope).append(" 条\n")
                .append("- EXPLAIN 缺听众：").append(missingAudience).append(" 条\n")
                .append("\n> 语义层不参与断言，以上结论由人工复审确认。\n\n");
        if (!reviewItems.isEmpty()) {
            report.append("## 待人工复审\n\n");
            reviewItems.forEach(item -> report.append("- ").append(item).append('\n'));
            report.append('\n');
        }
        report.append("## 明细\n\n").append(detail);

        Path out = Path.of("target", "quiz-eval-report.md");
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println("评测报告: " + out.toAbsolutePath());

        // 规则层是硬门槛：模型连条数/类型都返回不对，说明提示词或解析有问题，必须修。
        assertThat(ruleFailed)
                .as("规则层失败 %d 份材料，详见 target/quiz-eval-report.md", ruleFailed)
                .isLessThanOrEqualTo(1);
    }

    /** 规则层：只做确定性检查，不涉及任何语义判断。 */
    private List<String> ruleCheck(List<QuizService.TaskDraft> tasks) {
        List<String> problems = new ArrayList<>();
        if (tasks.size() != 3) {
            problems.add("题数=" + tasks.size());
        }
        boolean hasExplain = false;
        List<String> targets = new ArrayList<>();
        for (QuizService.TaskDraft t : tasks) {
            if (t == null || t.kind() == null || t.target() == null || t.text() == null) {
                problems.add("任务缺字段");
                continue;
            }
            String kind = t.kind().toUpperCase(Locale.ROOT);
            if (!"FACT".equals(kind) && !"EXPLAIN".equals(kind)) {
                problems.add("kind 非法: " + t.kind());
            }
            if ("EXPLAIN".equals(kind)) {
                hasExplain = true;
            }
            if (t.target().isBlank() || t.text().isBlank()) {
                problems.add("target 或 text 为空");
            }
            if (t.target().length() > 40) {
                problems.add("target 超过 40 字: " + t.target());
            }
            if (targets.contains(t.target())) {
                problems.add("target 重复: " + t.target());
            }
            targets.add(t.target());
        }
        if (!hasExplain) {
            problems.add("没有 EXPLAIN");
        }
        return problems;
    }

    private List<TaskJudge> judge(ChatClient judgeClient, Case c, List<QuizService.TaskDraft> tasks) {
        StringBuilder input = new StringBuilder();
        input.append("材料：\n").append(c.material()).append("\n\n");
        input.append("材料要点（参考答案，仅用于判断题目考什么）：\n");
        c.keyPoints().forEach(p -> input.append("- ").append(p).append('\n'));
        input.append("\n任务：\n");
        for (int i = 0; i < tasks.size(); i++) {
            QuizService.TaskDraft t = tasks.get(i);
            input.append(i).append(". [").append(t.kind()).append("] ")
                    .append(t.target()).append(" | ").append(t.text()).append('\n');
        }
        JudgeVerdict verdict = judgeClient.prompt().user(input.toString())
                .call().entity(JudgeVerdict.class);
        if (verdict == null || verdict.tasks() == null) {
            throw new IllegalStateException("裁判没有返回 tasks");
        }
        return verdict.tasks();
    }

    private void appendDetail(StringBuilder detail, Case c, List<QuizService.TaskDraft> tasks,
                              List<String> ruleProblems, List<TaskJudge> judges) {
        detail.append("### ").append(c.name()).append('\n');
        if (!ruleProblems.isEmpty()) {
            detail.append("规则层失败：").append(String.join("; ", ruleProblems)).append("\n\n");
        }
        if (tasks == null) {
            detail.append("（无题目）\n\n");
            return;
        }
        detail.append("| # | kind | target | 题面 | leaked | outOfScope | audience | AI 理由 |\n");
        detail.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (int i = 0; i < tasks.size(); i++) {
            QuizService.TaskDraft t = tasks.get(i);
            int idx = i;
            TaskJudge j = judges.stream().filter(x -> x.index() == idx).findFirst().orElse(null);
            detail.append("| ").append(i + 1)
                    .append(" | ").append(t.kind())
                    .append(" | ").append(t.target())
                    .append(" | ").append(t.text())
                    .append(" | ").append(j == null ? "未判" : j.leaked())
                    .append(" | ").append(j == null ? "未判" : j.outOfScope())
                    .append(" | ").append(j == null ? "未判" : j.explainHasAudience())
                    .append(" | ").append(j == null ? "-" : j.reason())
                    .append(" |\n");
        }
        detail.append("\n人工复审结论：__________\n\n");
    }
}
