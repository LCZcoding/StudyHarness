<template>
  <ChatRoom
    theme="manus"
    title="AI 超级智能体"
    ai-name="YuManus"
    user-name="我"
    ai-avatar="🛰️"
    user-avatar="🧑‍💻"
    sse-path="/ai/manus/chat"
    stream-mode="step"
    :chat-id="chatId"
    placeholder="给 Agent 下达一个任务…"
    idle-text="Agent 空闲"
    streaming-text="Agent 执行中，步骤实时输出…"
    greeting="我是 YuManus，全能 AI 智能体"
    greeting-sub="我会拆解任务、调用工具，一步步完成你的目标"
    :suggestions="suggestions"
  />
</template>

<script setup>
import ChatRoom from '../components/ChatRoom.vue'
import { createChatId } from '../utils/chatId.js'

// 进入页面自动生成聊天室 id，用于多轮对话记忆
const chatId = createChatId()

const suggestions = [
  '帮我搜索今天的科技热点并总结',
  '用 Java 写一个快速排序并讲解思路',
  '帮我规划一份 3 天的北京旅行计划'
]
</script>
