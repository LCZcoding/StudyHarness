import { provide, ref } from 'vue'

/**
 * 批注追问链的折叠状态。按批注 id 记录「哪几张卡片被展开了」。
 *
 * 为什么要放在卡片外面：编辑 / 删除成功后卡片 emit('changed')，父组件会重新
 * get() + resolve() 重建整棵树；状态若挂在卡片实例自己的 ref 上，一重建就全部合上。
 * T016 的 AnnotatableDoc 调一次 provideExpandedIds() 并持有返回值，所有卡片共享同一个
 * Set，只要 id 不变，展开状态就能跨列表刷新保留。
 *
 * 卡片 inject 不到时各自回落成一个局部状态，组件仍可独立使用（SSR 自检 / 预览页）。
 * 因此本模块不依赖任何页面，也不依赖后端数据形状。
 */

// 用字符串而不是 Symbol：自检脚本直接 import 本模块，组件走 Vite SSR 的模块实例，
// 两份模块会各生成一个 Symbol，provide / inject 就对不上。字符串常量跨实例恒等。
export const ANNOTATION_EXPANDED_KEY = 'studyharness:annotation-expanded'

export function createExpandedIds(initial = []) {
  const ids = ref(new Set(initial))

  return {
    ids,
    has(id) {
      return ids.value.has(id)
    },
    toggle(id) {
      const next = new Set(ids.value)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      ids.value = next
    },
    openAll(list) {
      ids.value = new Set([...ids.value, ...list])
    },
    clear() {
      ids.value = new Set()
    }
  }
}

/** T016 在 setup 里调用一次：provide 给整棵卡片树，并拿到可清理的状态对象。 */
export function provideExpandedIds() {
  const state = createExpandedIds()
  provide(ANNOTATION_EXPANDED_KEY, state)
  return state
}
