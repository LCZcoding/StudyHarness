/**
 * 右栏批注分组与正文段落对齐（用户实测反馈：批注全挤在右栏第一行）。
 *
 * 不用绝对定位：那会让右栏塌陷、相邻段落的批注互相盖住。改为给每个分组补一个
 * margin-top，把它从当前文档流位置「推」到对应正文块的高度；目标位置被前一个
 * 分组占住时顺序顺延。要求 .rail-groups 是 flex column + gap（flex 里 margin
 * 不折叠），否则相邻 margin 折叠会让下面的计算差一个 gap。
 *
 * 纯函数，只吃几何数字，便于 Node 自检覆盖「对齐 / 顺延 / 特殊组」三种分支。
 *
 * @param {object} o
 * @param {Array<{key:string,height:number}>} o.groups 按正文顺序的分组及其渲染高度
 * @param {Record<string,number>} o.blockTops 各正文块相对布局容器顶部的 y 坐标
 * @param {number} o.startTop 分组容器相对布局容器顶部的 y 坐标（未加 margin 时）
 * @param {number} [o.minGap=18] 分组间距，与 CSS flex gap 保持一致
 * @returns {Record<string,number>} key → marginTop(px)
 */
export function computeRailMargins({ groups = [], blockTops = {}, startTop = 0, minGap = 18 }) {
  const out = {}
  let cursor = Math.max(0, Number(startTop) || 0)
  for (const g of Array.isArray(groups) ? groups : []) {
    if (!g || g.key == null) continue
    const h = Math.max(0, Number(g.height) || 0)
    const desired = blockTops[g.key]
    // __rest__ / __stale__ 没有对应正文块：紧接上一组，不参与对齐
    const margin = Number.isFinite(desired) ? Math.max(0, desired - cursor) : minGap
    out[g.key] = Math.round(margin)
    cursor += margin + h + Math.max(0, Number(minGap) || 0)
  }
  return out
}
