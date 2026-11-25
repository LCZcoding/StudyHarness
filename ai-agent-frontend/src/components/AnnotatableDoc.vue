<template>
  <div ref="layoutRef" class="doc-layout" @mouseup="onMouseUp" @click="onLayoutClick">
    <!-- 左栏：正文。逐块渲染，块 key 是选区定位的唯一锚点（C8） -->
    <section class="doc-col">
      <div v-if="bodyEditable && !editing" class="doc-toolbar">
        <button type="button" class="edit-body-btn" @click="startEdit">编辑正文</button>
        <span class="doc-toolbar-hint">整篇 Markdown 原文，保存后覆盖</span>
      </div>

      <div v-if="editing" class="doc-edit">
        <textarea
          v-model="draft"
          class="body-textarea"
          spellcheck="false"
          aria-label="正文 Markdown 原文"
        ></textarea>
        <div class="edit-actions">
          <button type="button" class="soft-btn" @click="cancelEdit">取消</button>
          <button type="button" class="solid-btn" @click="saveEdit">保存</button>
        </div>
      </div>

      <div v-else class="doc-body">
        <div
          v-for="b in renderedBlocks"
          :key="b.key"
          class="doc-block-wrap"
          :class="{ 'block-has-annotation': b.count > 0 && b.hits === 0 }"
          :data-block-key="b.key"
        >
          <span v-if="b.count > 0 && b.hits === 0" class="block-badge">{{ b.count }} 条批注</span>
          <div class="doc-block" v-html="b.html"></div>
        </div>
      </div>
    </section>

    <!-- 右栏：批注。按正文段落分组，stale 单独一组（FR-06 / FR-10） -->
    <aside class="note-rail">
      <div class="rail-head">
        <span class="rail-title">批注</span>
        <span class="rail-count">{{ flat.length }}</span>
      </div>

      <div v-if="notice" class="notice" role="status">{{ notice }}</div>

      <!-- 提问框就地浮在选区旁：面板放在右栏顶部时，页面滚到中段后必须回到第一行才能输入。
           Teleport 到 body，避开祖先容器的 transform / backdrop-filter 影响 fixed 定位。 -->
      <Teleport to="body">
        <div v-if="pending" ref="askPanelRef" class="ask-panel" :style="askStyle">
          <div class="ask-head">
            <span>{{ pending.parentId ? '在解释里选词追问' : '就这句话提问' }}</span>
            <button type="button" class="ask-close" aria-label="取消提问" @click="cancelAsk">×</button>
          </div>
          <div class="ask-quote" :title="pending.quote">{{ pending.quote }}</div>
          <textarea
            ref="askInputRef"
            v-model="question"
            class="ask-input"
            rows="2"
            maxlength="500"
            placeholder="想问什么？例如：它在这一段里指什么？"
            @keydown="onAskKeydown"
          ></textarea>
          <div class="ask-actions">
            <span class="ask-tip">Ctrl + Enter 提交 · Esc 取消</span>
            <button type="button" class="soft-btn" :disabled="busy" @click="cancelAsk">取消</button>
            <button type="button" class="solid-btn" :disabled="busy || !question.trim()" @click="submitAsk">
              {{ busy ? '生成中…' : '提问' }}
            </button>
          </div>
        </div>
      </Teleport>

      <p v-if="!groups.length" class="rail-empty">
        选中正文里的一句话，右侧会出现一段短解释。<br>
        也可以在解释里选中陌生词继续追问。
      </p>

      <!-- 分组与对应正文段落对齐：每个组按块的实际 y 坐标补一段 margin-top。
           用 flex column + gap 而不是绝对定位，右栏不会塌陷、相邻组不会互相盖住。 -->
      <div ref="railGroupsRef" class="rail-groups">
        <section
          v-for="g in groups"
          :key="g.key"
          class="annotation-group"
          :data-group-key="g.key"
          :style="groupMargins[g.key] ? { marginTop: groupMargins[g.key] } : null"
        >
          <h3 class="group-title" :class="{ 'is-stale': g.staleGroup }">
            <span>{{ g.title }}</span>
            <span class="group-count">{{ g.count }}</span>
          </h3>
          <template v-for="root in g.roots" :key="root.id">
            <AnnotationCard
              :annotation="root"
              :depth="0"
              @changed="load"
              @error="showNotice"
            />
          </template>
        </section>
      </div>

      <p v-if="loading" class="rail-loading">批注加载中…</p>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import AnnotationCard from './AnnotationCard.vue'
import { splitBlocks } from '../utils/markdown.js'
import { get, ask } from '../api/annotations.js'
import { messageOf } from '../utils/apiError.js'
import { provideExpandedIds } from '../utils/annotationExpand.js'
import { resolve, groupAnnotations, highlightBlock, depthOf } from '../utils/annotationResolve.js'
import { computeAskPosition } from '../utils/annotationAskPosition.js'
import { computeRailMargins } from '../utils/annotationRailLayout.js'

/**
 * 可批注正文（plan.md 6.2 / spec FR-05~FR-13）。阅读页与笔记页共用这一个组件：
 *   SectionReadView  :bodyEditable="false"  → 只读正文，完全没有编辑按钮
 *   NoteDetailView   :bodyEditable="true"   → 编辑态只 emit('save', draft)，保存由父页面注入
 *
 * 组件内**不 import api/notes.js、不写任何 URL**：M1 阶段那个文件还不存在，
 * 这是「组件不 import 后续阶段模块」的硬约束。
 *
 * 两个 initial* 入参是可控渲染入口：让组件在没有浏览器、没有后端时也能被
 * SSR 自检与静态预览页验证，同时允许父组件注入已经取好的数据 / 展开状态。
 * 生产路径（T017 / T020）都不传，走 onMounted 自己拉真接口。
 */
const props = defineProps({
  markdown: { type: String, default: '' },
  targetType: { type: String, required: true },
  targetId: { type: [String, Number], required: true },
  bodyEditable: { type: Boolean, default: false },
  initialAnnotations: { type: Array, default: null },
  initialExpandedIds: { type: Array, default: null }
})
const emit = defineEmits(['save', 'cancel', 'unauthorized'])

// T015 返工后要求：折叠状态由本组件按 id 共享给整棵卡片树，
// 编辑 / 删除后重新 get() + resolve() 重建列表时不会把用户点开的链合上。
const expanded = provideExpandedIds()
if (Array.isArray(props.initialExpandedIds)) expanded.openAll(props.initialExpandedIds)

const layoutRef = ref(null)
const blocks = computed(() => splitBlocks(props.markdown))
const flat = ref(Array.isArray(props.initialAnnotations) ? [...props.initialAnnotations] : [])
const byId = computed(() => resolve(blocks.value, flat.value))
const groups = computed(() => groupAnnotations(blocks.value, byId.value))

/** 每块找出挂在它上面的顶层批注，做 best-effort 原文高亮。
 *  子批注选的是父答案里的词，正文里根本没有，天然不会留下孤儿高亮。 */
const renderedBlocks = computed(() => {
  const anns = [...byId.value.values()]
  return blocks.value.map((b) => {
    const quotes = anns
      .filter(a => a.parentId == null && !a.stale && a.groupKey === b.key)
      .map(a => a.quote)
    const { html, hits } = highlightBlock(b.html, quotes)
    return { key: b.key, html, count: quotes.length, hits }
  })
})

const loading = ref(false)
const busy = ref(false)
const notice = ref('')
const pending = ref(null)
const question = ref('')
let noticeTimer = null

function showNotice(msg) {
  notice.value = msg || ''
  // 提示条出现 / 消失会把右栏整体推下，分组位置要跟着重算才不偏
  nextTick(alignGroups)
  if (typeof window === 'undefined') return
  clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => {
    notice.value = ''
    nextTick(alignGroups)
  }, 4500)
}
onUnmounted(() => {
  clearTimeout(noticeTimer)
  stopFollowing()
  if (typeof window !== 'undefined') window.removeEventListener('resize', alignGroups)
})

async function load() {
  if (!props.targetType || props.targetId == null || props.targetId === '') return
  loading.value = true
  try {
    const rows = await get(props.targetType, String(props.targetId))
    flat.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    if (e?.response?.status === 401) {
      emit('unauthorized')
      return
    }
    showNotice(messageOf(e, '批注加载失败，请稍后重试'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.initialAnnotations == null) load()
  nextTick(alignGroups)
  if (typeof window !== 'undefined') window.addEventListener('resize', alignGroups)
})
watch(() => [props.targetType, props.targetId], () => {
  if (props.initialAnnotations == null) load()
})
// ---- 编辑 / 预览两态（FR-05）----
const editing = ref(false)
const draft = ref('')

// 正文 / 批注列表 / 编辑态变化后重新量一次，卡片高度变了也要重算。
// 必须放在 editing 声明之后：Vue 的 watch 会在 setup 阶段立即跑一次 getter
// 收集依赖，写在前面会命中 const 的 TDZ，整个组件 setup 抛错、正文白屏。
// SSR 下 watch 不执行 getter，所以 Node 自检抓不到，只能靠真实浏览器兜底。
watch(() => [props.markdown, flat.value, editing.value], () => {
  nextTick(alignGroups)
})

function startEdit() {
  draft.value = props.markdown || ''
  editing.value = true
  cancelAsk()
}
function cancelEdit() {
  editing.value = false
  draft.value = ''
  emit('cancel')
}
function saveEdit() {
  // 只交草稿，不自己调接口、也不自己退出编辑态：保存失败时草稿必须还在。
  emit('save', draft.value)
}
/** 父页面 PUT 成功后显式调用；markdown 真的变了时 watch 也会兜底退出。 */
function finishSave() {
  editing.value = false
  draft.value = ''
}
defineExpose({ finishSave, reload: load })

watch(() => props.markdown, () => {
  if (editing.value) {
    editing.value = false
    draft.value = ''
  }
})

// ---- 右栏分组与正文段落对齐 ----
// 用户实测反馈：批注生成后全挤在右栏第一行，选第 39 段也要回顶部才看得到。
// 按每个正文块的实际 y 坐标给对应分组补 margin-top；目标位置被前一组占住时
// 由 computeRailMargins 顺序顺延，绝不互相盖住。窄屏 / 编辑态回到普通流。
const railGroupsRef = ref(null)
const groupMargins = ref({})

function alignGroups() {
  const layout = layoutRef.value
  const railGroups = railGroupsRef.value
  if (typeof window === 'undefined' || !layout || !railGroups) return
  if (window.innerWidth <= 1024 || editing.value) {
    groupMargins.value = {}
    return
  }
  const layoutTop = layout.getBoundingClientRect().top
  const blockTops = {}
  layout.querySelectorAll('[data-block-key]').forEach(el => {
    blockTops[el.getAttribute('data-block-key')] = el.getBoundingClientRect().top - layoutTop
  })
  const shapes = [...railGroups.querySelectorAll('[data-group-key]')].map(el => ({
    key: el.getAttribute('data-group-key'),
    height: el.offsetHeight
  }))
  const margins = computeRailMargins({
    groups: shapes,
    blockTops,
    startTop: railGroups.getBoundingClientRect().top - layoutTop,
    minGap: 18
  })
  const next = {}
  for (const [key, px] of Object.entries(margins)) next[key] = `${px}px`
  groupMargins.value = next
}

/** 展开 / 收起追问会改分组高度，下一帧重算，后面的组不会越贴越偏 */
function onLayoutClick(e) {
  if (e?.target?.closest?.('[data-toggle-id]')) nextTick(alignGroups)
}

// ---- 选区委托（FR-07 / FR-08 / FR-13）----
function elOf(node) {
  if (!node) return null
  return node.nodeType === 1 ? node : node.parentElement
}

function onMouseUp(e) {
  if (editing.value || busy.value || typeof window === 'undefined') return
  if (e?.target?.closest?.('button, textarea, input')) return
  const sel = window.getSelection?.()
  if (!sel || sel.isCollapsed) return
  const quote = sel.toString().trim()
  if (!quote) return

  const anchorEl = elOf(sel.anchorNode)
  const focusEl = elOf(sel.focusNode)
  const root = layoutRef.value
  if (!anchorEl || !focusEl || !root) return
  if (!root.contains(anchorEl) || !root.contains(focusEl)) return

  // 卡内选词 → 子批注：必须起止落在同一张卡片，且选中的确实是解释里的词
  const card = anchorEl.closest('[data-annotation-id]')
  const cardFocus = focusEl.closest('[data-annotation-id]')
  if (card || cardFocus) {
    if (!card || card !== cardFocus) {
      showNotice('请只在同一条批注内选词')
      return
    }
    const parentId = Number(card.getAttribute('data-annotation-id'))
    const parent = byId.value.get(parentId)
    if (!parent) {
      showNotice('找不到这条批注，请刷新后重试')
      return
    }
    if (!String(parent.answer ?? '').includes(quote)) {
      showNotice('请在批注的解释里选词追问')
      return
    }
    openAsk({ quote, blockKey: parent.blockKey, parentId })
    return
  }

  // 正文选区 → 顶层批注：起止必须是同一段落
  const blockA = anchorEl.closest('[data-block-key]')
  const blockB = focusEl.closest('[data-block-key]')
  if (!blockA || blockA !== blockB) {
    showNotice('请只选一段内的文字')
    return
  }
  openAsk({ quote, blockKey: blockA.getAttribute('data-block-key'), parentId: null })
}

// ---- 提问框就地定位 ----
// 用户实测反馈：面板固定在右栏顶部时，正文滚到中段后每次提问都要回到第一行才能输入。
// 改成贴着选区浮出（宽屏贴右栏横向位置、纵向与选区齐平），打开即聚焦输入框。
const askPanelRef = ref(null)
const askInputRef = ref(null)
const askPos = ref(null)
let anchorRange = null
let following = false

const askStyle = computed(() => askPos.value
  ? { left: askPos.value.left, top: askPos.value.top, right: 'auto' }
  : {})

function positionAsk() {
  if (typeof window === 'undefined' || !anchorRange || !askPanelRef.value) return
  const sel = anchorRange.getBoundingClientRect()
  const railEl = layoutRef.value?.querySelector?.('.note-rail')
  const railRect = railEl?.getBoundingClientRect?.()
  const panel = askPanelRef.value.getBoundingClientRect()
  const pos = computeAskPosition({
    viewport: { width: window.innerWidth, height: window.innerHeight },
    selection: { left: sel.left, right: sel.right, top: sel.top, bottom: sel.bottom },
    rail: railRect && railRect.width >= 240 ? { left: railRect.left, width: railRect.width } : null,
    panel: { width: panel.width || 340, height: panel.height || 220 }
  })
  askPos.value = { left: `${Math.round(pos.left)}px`, top: `${Math.round(pos.top)}px` }
}

// 页面滚动 / 窗口缩放时重算，浮层始终跟着选区走；关闭或卸载时解绑。
function startFollowing() {
  if (following || typeof window === 'undefined') return
  window.addEventListener('scroll', positionAsk, true)
  window.addEventListener('resize', positionAsk)
  following = true
}
function stopFollowing() {
  if (!following || typeof window === 'undefined') return
  window.removeEventListener('scroll', positionAsk, true)
  window.removeEventListener('resize', positionAsk)
  following = false
}

function openAsk(next) {
  if (quoteTooLong(next.quote)) {
    showNotice('选中的文字最多 300 字，请选短一点')
    return
  }
  if (flat.value.length >= 200) {
    showNotice('这篇正文的批注已达上限 200 条')
    return
  }
  if (next.parentId != null && depthOf(flat.value, next.parentId) >= 8) {
    showNotice('追问链最多 8 层')
    return
  }
  // clone 一份 Range：聚焦输入框会清掉浏览器选区，但定位仍要按原选区算
  const sel = typeof window === 'undefined' ? null : window.getSelection?.()
  stopFollowing()
  anchorRange = sel?.rangeCount ? sel.getRangeAt(0).cloneRange() : null
  pending.value = next
  question.value = ''
  showNotice('')
  nextTick(() => {
    positionAsk()
    startFollowing()
    askInputRef.value?.focus({ preventScroll: true })
  })
}

const quoteTooLong = (s) => String(s ?? '').length > 300

function cancelAsk() {
  stopFollowing()
  anchorRange = null
  askPos.value = null
  pending.value = null
  question.value = ''
  if (typeof window !== 'undefined') window.getSelection?.()?.removeAllRanges?.()
}

function onAskKeydown(e) {
  if (e.key === 'Escape') {
    cancelAsk()
    return
  }
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    submitAsk()
  }
}

async function submitAsk() {
  const p = pending.value
  if (!p || busy.value) return
  const q = question.value.trim()
  if (!q) {
    showNotice('请先写下你的问题')
    return
  }
  if (q.length > 500) {
    showNotice('问题最多 500 字')
    return
  }
  busy.value = true
  try {
    await ask({
      targetType: props.targetType,
      targetId: String(props.targetId),
      blockKey: p.blockKey,
      quote: p.quote,
      question: q,
      parentId: p.parentId
    })
    cancelAsk()
    await load()
  } catch (err) {
    if (err?.response?.status === 401) {
      emit('unauthorized')
      return
    }
    showNotice(messageOf(err, '生成批注失败，请稍后重试'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.doc-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(280px, 1fr);
  gap: 24px;
  align-items: start;
  width: 100%;
}
.doc-col {
  min-width: 0;
}
.doc-body {
  max-width: 720px;
  font-size: 15.5px;
  line-height: 1.85;
  color: #1f2023;
}
.doc-block-wrap {
  position: relative;
  min-width: 0;
}
/* 高亮匹配不到时（markdown 标记把 quote 切开）的降级：左侧色条 + 批注数角标 */
.doc-block-wrap.block-has-annotation {
  padding-left: 14px;
  border-left: 3px solid #ef9f27;
}
.block-badge {
  display: inline-block;
  margin-bottom: 4px;
  padding: 1px 8px;
  font-size: 10.5px;
  line-height: 1.7;
  color: #a5650b;
  background: #faeeda;
  border-radius: var(--radius-pill);
}
/* v-html 产物要用 :deep 穿透 scoped */
.doc-body :deep(p) { margin: 0 0 1.15em; overflow-wrap: anywhere; }
.doc-body :deep(h1),
.doc-body :deep(h2),
.doc-body :deep(h3),
.doc-body :deep(h4) {
  margin: 1.6em 0 0.7em;
  font-weight: 600;
  line-height: 1.5;
  color: var(--ink);
}
.doc-body :deep(h1) { font-size: 22px; }
.doc-body :deep(h2) { font-size: 20px; }
.doc-body :deep(h3) { font-size: 18px; }
.doc-body :deep(h4) { font-size: 16px; }
.doc-body :deep(ul),
.doc-body :deep(ol) { margin: 0 0 1.15em; padding-left: 1.5em; }
.doc-body :deep(li) { margin: 0.35em 0; }
.doc-body :deep(strong) { font-weight: 600; color: var(--ink); }
.doc-body :deep(a) { color: var(--accent); }
.doc-body :deep(blockquote) {
  margin: 0 0 1.15em;
  padding: 2px 0 2px 14px;
  border-left: 3px solid var(--line);
  color: var(--ink-soft);
}
.doc-body :deep(code) {
  padding: 1px 5px;
  border-radius: 5px;
  background: rgba(0, 0, 0, 0.05);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}
.doc-body :deep(pre) {
  margin: 0 0 1.15em;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #f7f7f8;
  overflow-x: auto;
}
.doc-body :deep(pre code) { padding: 0; background: none; }
.doc-body :deep(table) {
  display: block;
  width: 100%;
  margin: 0 0 1.15em;
  border-collapse: collapse;
  overflow-x: auto;
  font-size: 14px;
}
.doc-body :deep(th),
.doc-body :deep(td) { padding: 8px 12px; border: 1px solid var(--line); text-align: left; }
.doc-body :deep(th) { background: #f7f7f8; font-weight: 600; }
/* 用户点名要的「引用在原文中的位置高亮配色」：与 T015 预览页同一套荧光黄 */
.doc-body :deep(mark.quote-hit) {
  background: #faeeda;
  color: #854f0b;
  border-bottom: 2px solid #ef9f27;
  border-radius: 2px;
  padding: 1px 2px;
}

.doc-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}
.edit-body-btn,
.soft-btn {
  font-size: 12px;
  padding: 6px 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--ink-soft);
  background: var(--surface);
}
.edit-body-btn:hover,
.soft-btn:hover { border-color: var(--accent); color: var(--accent); }
.doc-toolbar-hint { font-size: 11.5px; color: var(--ink-dim); }
.body-textarea {
  width: 100%;
  min-height: 60vh;
  padding: 14px 16px;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.edit-actions,
.ask-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}
.solid-btn {
  font-size: 12px;
  padding: 6px 16px;
  border-radius: 8px;
  color: #fff;
  background: var(--accent);
  border: 1px solid var(--accent);
}
.solid-btn:disabled,
.soft-btn:disabled { opacity: 0.45; cursor: not-allowed; }

.note-rail {
  min-width: 0;
  border-left: 1px solid var(--line);
  padding-left: 20px;
}
.rail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.rail-title { font-size: 13px; font-weight: 600; color: var(--ink); }
.rail-count {
  font-size: 11px;
  padding: 0 7px;
  border-radius: var(--radius-pill);
  background: var(--surface-soft);
  color: var(--ink-dim);
}
.notice {
  margin-bottom: 10px;
  padding: 8px 11px;
  font-size: 12px;
  line-height: 1.6;
  color: #a5650b;
  background: #fff7e8;
  border: 1px solid #f2dcb3;
  border-radius: 8px;
}
.rail-empty,
.rail-loading {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--ink-dim);
  margin: 4px 0 0;
}
.ask-panel {
  /* 视口浮层：top/right 是 JS 定位前的兜底，算出选区位置后由 :style 覆盖 left/top */
  position: fixed;
  top: 12px;
  right: 12px;
  width: min(360px, calc(100vw - 24px));
  z-index: 60;
  margin: 0;
  padding: 12px;
  background: var(--surface);
  border: 1px solid var(--accent);
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(31, 32, 35, 0.16);
}
.ask-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 8px;
}
.ask-close { font-size: 17px; line-height: 1; color: var(--ink-dim); }
.ask-quote {
  margin-bottom: 8px;
  padding: 5px 8px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #854f0b;
  background: #faeeda;
  border-radius: 5px;
  max-height: 4.8em;
  overflow: hidden;
  word-break: break-word;
}
.ask-input {
  width: 100%;
  padding: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  resize: vertical;
}
.ask-tip { margin-right: auto; font-size: 10.5px; color: var(--ink-dim); }

/* flex 里相邻 margin 不折叠，computeRailMargins 的 cursor 递推才精确 */
.rail-groups {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
/* 组间距交给 flex gap；margin-top 由 :style 按正文块高度注入 */
.annotation-group { margin: 0; }
.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ink-dim);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.group-title.is-stale { color: #a5650b; }
.group-count {
  flex: none;
  font-size: 10.5px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--surface-soft);
}

/* FR-06：窄于 1024px 右栏整体下移，不得出现横向滚动 */
@media (max-width: 1024px) {
  .doc-layout { grid-template-columns: 1fr; }
  .note-rail {
    border-left: none;
    border-top: 1px solid var(--line);
    padding: 16px 0 0;
  }
  /* 右栏已移到正文下方，不再做纵向对齐，分组顺序堆叠即可 */
  .annotation-group { margin-top: 0 !important; }
}
</style>
