package com.lcz.aiagentstudy.agent.model;


/**
 * Agent 执行状态
 * eg.使用 AgentState.IDLE 表示空闲
 */
public enum AgentState {

    IDLE, // 空闲
    RUNNING, // 运行中
    FINISHED, // 完成
    ERROR // 错误
}
