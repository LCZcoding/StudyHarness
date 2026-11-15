<template>
  <div
    class="annotation-card"
    :class="{ 'is-stale': !!annotation.stale }"
    :data-annotation-id="annotation.id"
  >
    <div class="card-head">
      <span class="quote-label">{{ depth === 0 ? '引用' : '选中词' }}</span>
      <span class="quote" :title="annotation.quote">{{ annotation.quote }}</span>
      <span v-if="depth > 0" class="tag level">第 {{ depth + 1 }} 层</span>
      <span v-if="annotation.edited" class="tag edited">已编辑</span>
      <span v-if="annotation.stale" class="tag stale">原文已修改</span>
    </div>

    <div class="question">{{ annotation.question }}</div>

    <!-- 安全要求：答案、问题、引用一律用 {{ }} 文本插值；分段高亮只切纯文本，不拼 HTML -->
    <div v-if="!editing" class="answer"><template v-for="(seg, i) in answerSegments" :key="i"><mark v-if="seg.hit" class="quote-hit">{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></div>
    <div v-else class="edit-box">
      <textarea v-model="draft" rows="4" maxlength="2000" :disabled="busy"></textarea>
      <div class="edit-actions">
        <button class="link-btn" :disabled="busy" @click="cancelEdit">取消</button>
        <button class="primary" :disabled="busy || !draft.trim()" @click="saveEdit">
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>

    <div class="card-actions">
      <button v-if="!editing" class="link-btn" :disabled="busy" @click="startEdit">编辑</button>
      <button class="link-btn danger" :title="deleteHint" :disabled="busy" @click="removeSelf">删除</button>
    </div>

    <button
      v-if="descendantCount > 0"
      type="button"
      class="fold-toggle"
      :class="{ open }"
      :data-toggle-id="annotation.id"
      :aria-expanded="open"
      @click="toggle"
    >
      <span class="chev">{{ open ? '▾' : '▸' }}</span>
      <span>{{ foldLabel }}</span>
    </button>
  </div>

  <div v-if="open && kids.length" class="annotation-children" :data-children-of="annotation.id">
    <AnnotationCard
      v-for="kid in kids"
      :key="kid.id"
      :annotation="kid"
      :depth="depth + 1"
      @changed="forwardChanged"
      @error="forwardError"
    />
  </div>
</template>

<script setup>
import { computed, inject, ref } from 'vue'
import { patch, remove } from '../api/annotations.js'
import { messageOf } from '../utils/apiError.js'
import { ANNOTATION_EXPANDED_KEY, createExpandedIds } from '../utils/annotationExpand.js'
import { splitAnswerByQuotes } from '../utils/annotationResolve.js'

/**
 * 批注卡片，对应 plan.md 6.2 与 spec FR-08 / FR-09 / NFR-03。
 *
 * 视觉口径（2026-11-14 用户调整）：追问默认折叠成一条小横条，手动点开才渲染；
 * 展开后子卡片是**独立平铺**的兄弟节点，不再套在父卡片边框里、不再缩进，层级靠
 * 「第 N 层」标签表达。所以这里是多根 fragment：第一根是卡片本体，第二根是子卡片容器。
 *
 * 入参 annotation 是 T016 的 resolve() 组树后的节点：
 *   { id, parentId, blockKey, quote, question, answer, edited, createdAt,
 *     children: [], stale: false, groupKey: 'p-1' }
 * depth 由父级传入，顶层为 0；后端链深硬限 8。
 *
 * 折叠状态优先 inject 父组件（T016）按 id 共享的 Set；inject 不到时回落到局部状态，
 * 组件仍可独立渲染。折叠用 v-if 而非 CSS 隐藏，避免 T016 的选区委托
 * closest('[data-annotation-id]') 命中看不见的卡片。
 *
 * 卡片自己调 T014 的 patch / remove，成功后只 emit('changed') 让父组件重新拉列表；
 * 失败 emit('error', 中文文案)，由父组件统一展示。
 */
const props = defineProps({
  annotation: { type: Object, required: true },
  depth: { type: Number, default: 0 }
})
const emit = defineEmits(['changed', 'error'])

const shared = inject(ANNOTATION_EXPANDED_KEY, null)
const expanded = shared || createExpandedIds()
const open = computed(() => expanded.has(props.annotation.id))
function toggle() {
  expanded.toggle(props.annotation.id)
}

const editing = ref(false)
const draft = ref('')
const busy = ref(false)

const kids = computed(() =>
  Array.isArray(props.annotation.children) ? props.annotation.children : [])

/** 追问锚点高亮：只标「子批注在父答案里选中的词」，不标本卡片自己的引用词。
 *  children 由 resolve() 组好，追问折叠与否都不影响；stale 子批注的词找不到会自然跳过。 */
const answerSegments = computed(() =>
  splitAnswerByQuotes(props.annotation.answer, kids.value.map(k => k.quote))
)

function countDescendants(node) {
  const children = Array.isArray(node?.children) ? node.children : []
  return children.reduce((n, child) => n + 1 + countDescendants(child), 0)
}
const descendantCount = computed(() => countDescendants(props.annotation))
const foldLabel = computed(() =>
  open.value ? '收起追问' : `展开 ${descendantCount.value} 条追问`)

/** FR-09：删除前必须提示「将同时删除 N 条追问」。按钮 title 也挂同一句，便于自检与悬停。 */
const deleteHint = computed(() => descendantCount.value > 0
  ? `将同时删除 ${descendantCount.value} 条追问，删除后不可恢复`
  : '删除这条批注，删除后不可恢复')

function startEdit() {
  draft.value = props.annotation.answer || ''
  editing.value = true
}
function cancelEdit() {
  editing.value = false
  draft.value = ''
}

async function saveEdit() {
  const answer = draft.value.trim()
  if (!answer) {
    emit('error', '批注答案不能为空')
    return
  }
  if (answer.length > 2000) {
    emit('error', '批注答案最多 2000 字')
    return
  }
  busy.value = true
  try {
    await patch(props.annotation.id, answer)
    editing.value = false
    emit('changed') // 父组件重新 get() + resolve()，edited/stale 才会刷新
  } catch (e) {
    // 失败保留编辑态，别把用户刚写的草稿弄丢
    emit('error', messageOf(e, '保存失败，请稍后重试'))
  } finally {
    busy.value = false
  }
}

async function removeSelf() {
  const n = descendantCount.value
  const ok = window.confirm(n > 0
    ? `将同时删除 ${n} 条追问，确定删除吗？`
    : '确定删除这条批注吗？')
  if (!ok) return
  busy.value = true
  try {
    await remove(props.annotation.id)
    emit('changed')
  } catch (e) {
    emit('error', messageOf(e, '删除失败，请稍后重试'))
  } finally {
    busy.value = false
  }
}

// 递归子卡片的成功/失败事件要逐层转发，否则顶层 AnnotatableDoc 收不到
function forwardChanged() { emit('changed') }
function forwardError(msg) { emit('error', msg) }
</script>

<style scoped>
.annotation-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}
.annotation-card.is-stale {
  border-style: dashed;
}
.card-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.quote-label {
  flex: none;
  font-size: 11px;
  color: var(--ink-dim);
}
.quote {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  color: var(--ink-soft);
  background: var(--surface-soft);
  border-radius: 4px;
  padding: 1px 6px;
}
.tag {
  flex: none;
  font-size: 10.5px;
  line-height: 1.6;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--line);
  color: var(--ink-dim);
}
.tag.edited { color: var(--accent); border-color: rgba(77, 107, 254, 0.35); }
.tag.stale { color: #a5650b; border-color: rgba(165, 101, 11, 0.35); }
.tag.level { background: var(--surface-soft); }
.question {
  font-size: 12.5px;
  font-weight: 600;
  margin-bottom: 6px;
  word-break: break-word;
}
.answer {
  font-size: 13px;
  line-height: 1.65;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
}
/* 与正文 .quote-hit 同一套荧光黄。正文那条是 scoped + .doc-body :deep()，
   管不到右栏卡片，所以这里单独补一份（不进全局样式，避免污染其它页面）。 */
.answer mark.quote-hit {
  background: #faeeda;
  color: #854f0b;
  border-bottom: 2px solid #ef9f27;
  border-radius: 2px;
  padding: 1px 2px;
}
.card-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
.link-btn {
  padding: 0;
  font-size: 11.5px;
  color: var(--ink-dim);
}
.link-btn:hover { color: var(--accent); }
.link-btn.danger:hover { color: #d3453b; }
.link-btn:disabled, .primary:disabled { opacity: 0.5; cursor: not-allowed; }
.edit-box textarea {
  width: 100%;
  resize: vertical;
  font-size: 13px;
  line-height: 1.6;
  padding: 8px;
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.edit-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
.edit-actions .primary {
  font-size: 11.5px;
  padding: 4px 12px;
  border-radius: 6px;
  color: #fff;
  background: var(--accent);
  border: 1px solid var(--accent);
}
.fold-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding: 3px 10px;
  font-size: 11.5px;
  color: #185fa5;
  background: #e6f1fb;
  border: 1px solid #b5d4f4;
  border-radius: 6px;
  line-height: 1.6;
}
.fold-toggle:hover { background: #d3e6fa; }
.fold-toggle:disabled { opacity: 0.5; cursor: not-allowed; }
.fold-toggle .chev { font-size: 12px; width: 9px; }
.annotation-children { margin: 0; }
</style>
