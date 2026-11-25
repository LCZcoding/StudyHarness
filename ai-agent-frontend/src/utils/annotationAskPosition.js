/**
 * 提问面板「就地」定位（plan.md 6.2 / T018 前的实测反馈修补口径）。
 * 纯函数：只吃几何数字、不碰 DOM，好让 Node 自检覆盖定位边界。
 *
 * @param {object} o
 * @param {{width:number,height:number}} o.viewport 视口尺寸
 * @param {{left:number,right:number,top:number,bottom:number}} o.selection 选区矩形（视口坐标）
 * @param {{left:number,width:number}|null} o.rail 右栏矩形；窄屏右栏已下移时传 null
 * @param {{width:number,height:number}} o.panel 面板尺寸
 * @returns {{left:number,top:number}} 面板左上角（视口坐标，px）
 */
export function computeAskPosition({ viewport, selection, rail = null, panel }) {
  const gap = 12
  const vw = Math.max(0, viewport.width)
  const vh = Math.max(0, viewport.height)
  const pw = Math.min(Math.max(0, panel.width), Math.max(0, vw - gap * 2))
  const ph = Math.max(0, panel.height)
  const maxLeft = Math.max(gap, vw - pw - gap)
  const maxTop = Math.max(gap, vh - ph - gap)
  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), Math.max(lo, hi))

  // 宽屏：横向贴右栏、纵向与选区齐平 —— 不用滚回页面第一行才能输入问题
  if (rail && rail.width >= 240 && vw > 1024) {
    return {
      left: clamp(rail.left + 8, gap, maxLeft),
      top: clamp(selection.top - 8, gap, maxTop)
    }
  }

  // 窄屏（右栏已移到正文下方）：优先落在选区下方，放不下就翻到选区上方
  const below = selection.bottom + gap
  const above = selection.top - ph - gap
  const top = below + ph <= vh - gap ? below : clamp(above, gap, maxTop)
  return { left: clamp(selection.left, gap, maxLeft), top }
}
