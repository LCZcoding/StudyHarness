<template>
  <ChatRoom
    theme="guide"
    title="AI 问题引导解决助手"
    ai-name="引导助手"
    user-name="我"
    ai-avatar="❓"
    user-avatar="🧑"
    sse-path="/ai/love_app/chat/sse"
    stream-mode="token"
    :chat-id="chatId"
    placeholder="描述你遇到的问题，我们一起拆解…"
    idle-text="在线，随时为你答疑"
    streaming-text="正在梳理思路，引导你一步步解决…"
    greeting="你好，我是你的问题引导解决助手"
    greeting-sub="无论是报错排查、代码问题还是概念学习，我们一起找到答案"
    :suggestions="suggestions"
  />
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ChatRoom from '../components/ChatRoom.vue'
import { createConversation } from '../api/conversation.js'
const router = useRouter()
// 进入页面自动生成聊天室 id，区分不同会话
const chatId = ref('')
onMounted(async()=>{
  try{
    const data = await createConversation('PRACTICE')
  chatId.value = data.conversationId
  }catch(e){
    if(e.response?.status == 401){
      router.push('/login')
    }
  }
  
})

const suggestions = [
  '我的代码报错了，帮我理一下排查思路',
  '讲讲什么是线程池，我该从哪学起？',
  '这个报错我不认识，该去哪查官方文档？',
  '帮我判断这段配置哪里可能有问题'
]
</script>
