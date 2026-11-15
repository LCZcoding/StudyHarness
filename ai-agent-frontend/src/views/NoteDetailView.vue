<template>
  <div class="page">
    <PageHeader
      eyebrow="NOTE · 笔记"
      :title="title"
      :subtitle="subtitle"
    >
      <template #actions>
        <span v-if="saveState" class="save-state" :class="saveState.kind">
          {{ saveState.text }}
        </span>
        <button type="button" class="btn" @click="backToChat">返回对话</button>
      </template>
    </PageHeader>

    <main class="content">
      <div v-if="error" class="banner">
        <span>{{ error }}</span>
        <button type="button" class="banner-retry" @click="reload">重试</button>
      </div>
      <p v-if="loading" class="loading">正在打开笔记…</p>

      <article v-if="note" class="reader">
        <div class="reader-inner">
          <!-- 笔记页是 M2 里唯一可编辑正文的入口：bodyEditable 打开，
               保存只在组件内交草稿，PUT 由本页负责（组件不 import api/notes.js）。
               :key 用 messageId：从一张笔记跳到另一张时整块重建，批注列表与折叠状态不串。 -->
          <AnnotatableDoc
            ref="docRef"
            :key="note.messageId"
            :markdown="markdown"
            :target-type="'NOTE'"
            :target-id="note.messageId"
            :body-editable="true"
            @save="onSave"
            @unauthorized="onUnauthorized"
          />
        </div>
      </article>
    </main>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import AnnotatableDoc from '../components/AnnotatableDoc.vue'
import { get as getNote, save as saveNote } from '../api/notes.js'
import { redirectOn401, messageOf } from '../utils/apiError.js'
import { noteChars, noteTitle } from '../utils/noteCard.js'

/**
 * 笔记详情页（T020，spec FR-04 / FR-05 / FR-10）。
 *
 * 与阅读页共用 AnnotatableDoc，差别只有两处：
 *   1. targetType 是 NOTE、targetId 是 messageId —— 批注挂在消息上，不挂在章节上；
 *   2. bodyEditable 打开，编辑后的 Markdown 由本页 PUT 覆盖写。
 *
 * 保存链路刻意拆成两半：组件只 emit('save', draft)，不碰接口、不自己退出编辑态；
 * 本页 PUT 成功后才更新 markdown 并调 docRef.finishSave()。这样保存失败时
 * 草稿仍在输入框里，用户不用重打一遍。
 */
const route = useRoute()
const router = useRouter()

const note = ref(null)
const markdown = ref('')
const loading = ref(true)
const error = ref('')
const saveState = ref(null)
const docRef = ref(null)

// 顶栏标题取笔记自己的第一个标题（与聊天的卡片同源，同一个 noteCard 工具），
// 而不是写死一句通用文案 —— 从卡片点进来，卡片上那句话应该还在页头
const title = computed(() => (note.value ? noteTitle(markdown.value) : '笔记'))

const subtitle = computed(() => {
  if (!note.value) return '正在取笔记正文'
  // 字数与聊天卡片、列表页同一个算法（noteChars）：三处数字必须一样。
  // 之前这里是 `markdown.replace(/\s/g,'').length` —— 留着「#」却去掉空格，
  // 于是列表说 133 字、点进来 128 字（T025 验收时发现）。
  const chars = noteChars(markdown.value)
  return `${chars.toLocaleString('zh-CN')} 字 · 更新于 ${formatTime(note.value.updatedAt)}`
})

/** '2026-11-15T05:01:23Z' → '11-15 13:01'。不引日期库，这里只需要一个可读时间。 */
function formatTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function onUnauthorized() {
  redirectOn401({ response: { status: 401 } }, router, route)
}

/** 返回来源对话：NoteView 里有 conversationId，不用依赖浏览器历史，
    从聊天卡片点进来、直接贴链接进来都能回到同一条会话。 */
function backToChat() {
  const cid = note.value?.conversationId
  if (cid) router.push({ name: 'guide-conversation', params: { conversationId: cid } })
  else router.push({ name: 'guide' })
}

async function load(id) {
  loading.value = true
  error.value = ''
  saveState.value = null
  note.value = null
  markdown.value = ''
  try {
    note.value = await getNote(id)
    markdown.value = note.value.content || ''
  } catch (e) {
    if (redirectOn401(e, router, route)) return
    // 403 的三合一文案（别人的 / 不存在 / 非 NOTE）由后端给，原样展示
    error.value = messageOf(e, '笔记加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function reload() {
  const id = route.params.messageId
  if (id) load(id)
}

/**
 * 覆盖写正文。成功后：
 *   - markdown 换成刚提交的草稿 → AnnotatableDoc 重新切块，旧批注按新正文重新定位
 *     （resolve() 是组件内的 computed，props.markdown 一变就重算，块 key 对不上的转 stale）；
 *   - 调 finishSave() 显式退出编辑态（markdown 没变时 watch 不会兜底，必须显式调）。
 */
async function onSave(draft) {
  if (!note.value) return
  error.value = ''
  saveState.value = { kind: 'busy', text: '保存中…' }
  try {
    const res = await saveNote(note.value.messageId, draft)
    markdown.value = draft
    note.value = { ...note.value, content: draft, updatedAt: res.updatedAt }
    docRef.value?.finishSave()
    saveState.value = { kind: 'ok', text: '已保存' }
    if (typeof window !== 'undefined') {
      window.setTimeout(() => {
        if (saveState.value?.kind === 'ok') saveState.value = null
      }, 2500)
    }
  } catch (e) {
    // 失败时不动 markdown、不退出编辑态：草稿留在输入框里等用户重试
    saveState.value = { kind: 'fail', text: '保存失败' }
    if (redirectOn401(e, router, route)) return
    error.value = messageOf(e, '保存失败，请稍后重试')
  }
}

// messageId 只在路径里，切换笔记时组件靠 :key 重建，这里管数据加载
watch(
  () => route.params.messageId,
  (id) => {
    if (!id) return
    // 路径参数非法时直接报错，不发请求（后端会 400，但没必要多跑一趟）
    if (!/^\d+$/.test(String(id))) {
      note.value = null
      loading.value = false
      error.value = '笔记地址无效'
      return
    }
    load(String(id))
  },
  { immediate: true }
)
</script>

<style scoped>
/* 与阅读页共用同一套浅色令牌，两个页面的观感必须一致 */
.page {
  --bg: #fcfcfc;
  --ink: #0d0d0d;
  --ink-soft: #565869;
  --ink-dim: #8e8ea0;
  --line: rgba(0, 0, 0, 0.08);
  --radius-pill: 999px;

  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}
.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 18px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  line-height: 1;
  border: 1px solid rgba(0, 0, 0, 0.14);
  color: var(--ink);
  background: #ffffff;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
  white-space: nowrap;
}
.btn:hover {
  background: #f0f0f2;
}

/* 保存态是本页唯一的全局反馈：编辑按钮在组件里，页面只能靠顶栏这一小块说话 */
.save-state {
  font-size: 12px;
  padding: 5px 10px;
  border-radius: var(--radius-pill);
  white-space: nowrap;
}
.save-state.busy {
  color: var(--ink-soft);
  background: #f0f0f2;
}
.save-state.ok {
  color: #1a7f4b;
  background: #eaf7f0;
}
.save-state.fail {
  color: #b4341a;
  background: #fff1ec;
}

.banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  max-width: 1180px;
  width: calc(100% - 64px);
  margin: 20px auto 0;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff7ed;
  color: #b4341a;
  font-size: 13px;
}
.banner-retry {
  flex: none;
  padding: 5px 12px;
  border-radius: var(--radius-pill);
  border: 1px solid rgba(180, 52, 26, 0.3);
  background: #ffffff;
  color: #b4341a;
  font-size: 12px;
}
.banner-retry:hover {
  background: #fff1ec;
}
.loading {
  margin: 0;
  padding: 60px 0;
  text-align: center;
  color: var(--ink-dim);
  font-size: 12.5px;
}

/* 两栏布局与阅读页一致：组件内 @media(max-width:1024px) 会把右栏移到正文下方 */
.reader {
  flex: 1;
}
.reader-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 36px 32px 64px;
}

@media (max-width: 820px) {
  .banner,
  .reader-inner {
    width: 100%;
    padding-left: 18px;
    padding-right: 18px;
  }
  .reader-inner {
    padding-top: 26px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .btn {
    transition: none;
  }
}
</style>
