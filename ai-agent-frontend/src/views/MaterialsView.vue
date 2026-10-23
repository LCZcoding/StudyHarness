<template>
  <div class="page">
    <PageHeader
      eyebrow="MATERIALS · 资料库"
      title="把学过的东西变成题目"
      subtitle="粘贴或上传讲义，系统按材料出 3 道题：2 道回忆、1 道讲解。"
    >
      <template #actions>
        <button type="button" class="btn ghost" :disabled="uploading" @click="pickFile">
          {{ uploading ? '上传中…' : '上传文件' }}
        </button>
        <button type="button" class="btn primary" @click="openPaste">粘贴文本</button>
      </template>
    </PageHeader>

    <main class="content">
      <div v-if="error" class="banner">{{ error }}</div>

      <!-- 空态 -->
      <div v-if="!loading && !items.length" class="empty">
        <strong>还没有资料</strong>
        把讲义、笔记、博客正文粘进来，或者上传 .md / .txt / .pdf 文件。
      </div>

      <div v-else class="grid">
        <article v-for="item in items" :key="item.knowledgeItemId" class="item">
          <div class="item-head">
            <h2 class="item-title">{{ item.title }}</h2>
            <span class="chip">{{ item.sourceUrl ? '文件' : '粘贴' }}</span>
          </div>
          <p class="item-summary">{{ item.summary || '暂无摘要' }}</p>
          <div class="item-meta">
            <span>{{ formatTime(item.createdAt) }}</span>
            <button
              type="button"
              class="btn ghost"
              :disabled="generatingId !== null"
              @click="generate(item)"
            >
              {{ generatingId === item.knowledgeItemId ? '出题中…' : '出题' }}
            </button>
          </div>
        </article>
      </div>

      <p v-if="loading" class="loading">加载中…</p>
    </main>

    <!-- 粘贴文本弹层 -->
    <div v-if="pasteOpen" class="mask" @click.self="pasteOpen = false">
      <div class="dialog">
        <h3>粘贴文本</h3>
        <label class="field">
          <span>标题</span>
          <input v-model="pasteTitle" maxlength="100" placeholder="例如：HNSW 参数与查询流程" />
        </label>
        <label class="field">
          <span>正文（{{ pasteContent.length }} / 20000）</span>
          <textarea v-model="pasteContent" maxlength="20000" placeholder="把材料正文粘到这里…" />
        </label>
        <p v-if="pasteError" class="dialog-error">{{ pasteError }}</p>
        <div class="dialog-foot">
          <button type="button" class="btn" @click="pasteOpen = false">取消</button>
          <button type="button" class="btn primary" :disabled="saving" @click="savePaste">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 出题骨架：POST /quiz/generate 实测约 4.4 秒，没有这个态用户会以为点坏了 -->
    <div v-if="generatingId" class="mask center">
      <div class="skeleton-card">
        <div class="spinner"></div>
        <p>正在出 3 道题…</p>
        <div class="hint">2 道回忆题 + 1 道讲解题，约 4 秒</div>
        <div class="sk-lines"><i style="width: 92%"></i><i style="width: 76%"></i><i style="width: 84%"></i></div>
      </div>
    </div>

    <input ref="fileInput" type="file" accept=".md,.txt,.pdf" hidden @change="onFilePicked" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { listItems, createItem, uploadItem } from '../api/knowledge.js'
import { generate as generateQuiz } from '../api/quiz.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'

const route = useRoute()
const router = useRouter()

const items = ref([])
const loading = ref(true)
const error = ref('')

const pasteOpen = ref(false)
const pasteTitle = ref('')
const pasteContent = ref('')
const pasteError = ref('')
const saving = ref(false)

const uploading = ref(false)
const generatingId = ref(null)
const fileInput = ref(null)

async function load() {
  try {
    items.value = await listItems()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '资料加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openPaste() {
  pasteTitle.value = ''
  pasteContent.value = ''
  pasteError.value = ''
  pasteOpen.value = true
}

async function savePaste() {
  pasteError.value = ''
  if (!pasteTitle.value.trim() || !pasteContent.value.trim()) {
    pasteError.value = '标题和正文都不能为空'
    return
  }
  saving.value = true
  try {
    await createItem(pasteTitle.value.trim(), pasteContent.value)
    pasteOpen.value = false
    await load()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    pasteError.value = messageOf(e, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFilePicked(event) {
  const file = event.target.files?.[0]
  // 先清空 value：否则连续选同一个文件不会再触发 change
  event.target.value = ''
  if (!file) return
  error.value = ''
  uploading.value = true
  try {
    await uploadItem(file)
    await load()
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '上传失败，请检查文件格式与大小')
  } finally {
    uploading.value = false
  }
}

async function generate(item) {
  error.value = ''
  generatingId.value = item.knowledgeItemId
  try {
    const quiz = await generateQuiz(item.knowledgeItemId)
    router.push({ name: 'quiz', params: { conversationId: quiz.conversationId } })
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '出题失败，请稍后重试')
  } finally {
    generatingId.value = null
  }
}

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(load)
</script>

<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0e1116;
}
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 32px 44px;
  color: #dbe6f0;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 1;
  border: 1px solid rgba(148, 163, 184, 0.12);
  color: #dbe6f0;
  transition: background 0.18s ease, border-color 0.18s ease, color 0.18s ease;
  white-space: nowrap;
}
.btn:hover {
  background: rgba(148, 163, 184, 0.1);
}
.btn.primary {
  background: linear-gradient(140deg, #0284c7, #0ea5c9);
  border-color: transparent;
  color: #04121b;
  font-weight: 700;
}
.btn.primary:hover {
  filter: brightness(1.08);
}
.btn.ghost {
  border-color: rgba(34, 211, 238, 0.35);
  color: #22d3ee;
}
.btn.ghost:hover {
  background: rgba(34, 211, 238, 0.12);
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.banner {
  margin-bottom: 16px;
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: rgba(248, 113, 113, 0.08);
  color: #f9a8a8;
  font-size: 13px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.item {
  display: flex;
  flex-direction: column;
  padding: 18px 18px 14px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #141b23;
  transition: border-color 0.2s ease, transform 0.25s cubic-bezier(0.22, 1, 0.36, 1);
}
.item:hover {
  border-color: rgba(34, 211, 238, 0.32);
  transform: translateY(-2px);
}
.item-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.item-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #eef4fa;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.chip {
  flex: none;
  padding: 2px 9px;
  border-radius: 999px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 10px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  color: #8ba3b8;
}
.item-summary {
  margin: 10px 0 14px;
  font-size: 12.5px;
  color: #8ba3b8;
  line-height: 1.75;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.item-meta {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px dashed rgba(148, 163, 184, 0.12);
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}

.empty {
  padding: 60px 20px;
  text-align: center;
  border: 1px dashed rgba(148, 163, 184, 0.12);
  border-radius: 16px;
  color: #6b7f92;
  font-size: 13px;
}
.empty strong {
  display: block;
  margin-bottom: 6px;
  color: #dbe6f0;
  font-size: 15px;
}
.loading {
  margin: 0;
  padding: 40px 0;
  text-align: center;
  color: #6b7f92;
  font-size: 12px;
}

/* 弹层与骨架共用一层遮罩 */
.mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(6, 10, 14, 0.72);
  backdrop-filter: blur(3px);
  padding: 20px;
}

.dialog {
  width: min(560px, 100%);
  max-height: 88vh;
  overflow-y: auto;
  padding: 22px;
  border-radius: 16px;
  border: 1px solid rgba(34, 211, 238, 0.18);
  background: #141b23;
}
.dialog h3 {
  margin: 0 0 16px;
  font-size: 16px;
  color: #eef4fa;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}
.field span {
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}
.field input,
.field textarea {
  width: 100%;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: #0f151c;
  color: #dbe6f0;
  outline: none;
  transition: border-color 0.18s ease;
}
.field input:focus,
.field textarea:focus {
  border-color: rgba(34, 211, 238, 0.45);
}
.field textarea {
  min-height: 220px;
  resize: vertical;
  line-height: 1.7;
}
.dialog-error {
  margin: 0 0 12px;
  color: #f9a8a8;
  font-size: 12.5px;
}
.dialog-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.skeleton-card {
  width: 340px;
  padding: 24px;
  border-radius: 16px;
  border: 1px solid rgba(34, 211, 238, 0.18);
  background: #141b23;
  text-align: center;
}
.skeleton-card p {
  margin: 0;
  font-size: 13px;
  color: #dbe6f0;
}
.skeleton-card .hint {
  margin-top: 6px;
  font-family: 'IBM Plex Mono', monospace;
  font-size: 11px;
  color: #6b7f92;
}
.spinner {
  width: 26px;
  height: 26px;
  margin: 0 auto 14px;
  border-radius: 50%;
  border: 2px solid rgba(34, 211, 238, 0.22);
  border-top-color: #22d3ee;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.sk-lines {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sk-lines i {
  display: block;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, rgba(34, 211, 238, 0.08), rgba(34, 211, 238, 0.2), rgba(34, 211, 238, 0.08));
  background-size: 200% 100%;
  animation: shimmer 1.4s linear infinite;
}
@keyframes shimmer {
  to { background-position: -200% 0; }
}

@media (max-width: 1080px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 820px) {
  .content { padding: 18px; }
  .grid { grid-template-columns: minmax(0, 1fr); }
}
@media (prefers-reduced-motion: reduce) {
  .item, .spinner, .sk-lines i { animation: none; transition: none; }
}
</style>
