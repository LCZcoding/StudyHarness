<template>
  <ChatRoom
    theme="love"
    title="AI 恋爱大师"
    ai-name="恋爱大师"
    user-name="我"
    ai-avatar="💗"
    user-avatar="🧑"
    sse-path="/ai/love_app/chat/sse"
    stream-mode="token"
    :chat-id="chatId"
    placeholder="说说你的感情烦恼吧…"
    idle-text="在线，随时等你倾诉"
    streaming-text="正在认真回复…"
    greeting="嗨～我是你的恋爱大师"
    greeting-sub="无论是暗恋、约会还是吵架，都可以跟我说说"
    :suggestions="suggestions"
  />
</template>

<script setup>
import ChatRoom from '../components/ChatRoom.vue'
import { createChatId } from '../utils/chatId.js'

// 进入页面自动生成聊天室 id，区分不同会话
const chatId = createChatId()

const suggestions = [
  '怎么判断对方是不是喜欢我？',
  '第一次约会聊什么不尴尬？',
  '帮我写一段温柔的表白文案',
  '吵架了怎么主动破冰？'
]
</script>
