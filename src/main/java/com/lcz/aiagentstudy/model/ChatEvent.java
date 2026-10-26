package com.lcz.aiagentstudy.model;

import java.util.List;

/**
 * 一轮对话对外吐出的事件。
 * 为什么不再用 Flux<String>：
 * 工具调用（名字/参数/结果）和 RAG 来源（章节/片段/序号）都是多字段结构，
 * 硬塞进纯文本只有两种下场——要么丢字段，要么用魔法字符串污染正文，
 * 而正文会被 MessageService 原样落库、还会被"把这轮做成题"当成学习材料。
 * 为什么用 sealed + record：
 * 1) record 自带不可变与序列化语义，直接交给 Jackson 就是一个单行 JSON；
 * 2) sealed 让"有哪些事件"成为编译期事实，加一种就必须回来处理。
 */
public sealed interface ChatEvent {
    /*
     * SSE 的 event: 名。这是前后端唯一的契约字符串，
     * 改这里等于改接口，必须同步改 chatStream.js。
     */
    String type();

    /** 正文增量。只有它会进答案、进聊天记忆、进数据库。 */
    record Delta(String text) implements ChatEvent {
        @Override public String type() { return "delta"; }
    }

    /** 本轮 RAG 召回结果。开流前一次性下发，之后不再变化。 */
    record Sources(List<Source> sources) implements ChatEvent {
        @Override public String type() { return "sources"; }
    }

    /**
     * 工具调用。start / end 两帧共享同一个 id，前端按 id 合并。
     * start 帧：args 有值，ok/ms/result 为 null
     * end 帧：args 为 null，ok/ms/result 有值
     */
    record Tool(String id, String phase, String name, String args,
                Boolean ok, Long ms, String result) implements ChatEvent {
        @Override public String type() { return "tool"; }
    }

    /** 正常结束。它到达时，本轮消息已经落库成功。 */
    record Done() implements ChatEvent {
        @Override public String type() { return "done"; }
    }

    /** 流内错误。SSE 已经发不出 HTTP 状态码，只能靠事件告诉前端。 */
    record Error(String message) implements ChatEvent {
        @Override public String type() { return "error"; }
    }

    /**
     * 一条召回片段（展示用，不参与任何业务逻辑）。
     * snippet 已在服务端截断：整块向量是 1000 字，全发过去会让单帧过大、前端也没人看完。
     */
    record Source(String knowledgeItemId, String title, Integer chunkSeq,
                  String snippet, Double score) {
    }

    // ---- 便捷工厂：调用点少写几个 new / 泛型  (直接用方法引用，ChatEvent::delta) ----

    static ChatEvent delta(String text) {
        return new Delta(text);
    }

    static ChatEvent sources(List<Source> sources) {
        return new Sources(sources);
    }

}
