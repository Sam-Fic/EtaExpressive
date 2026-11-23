// SPDX-License-Identifier: GPL-3.0-only
// Part of the OpenMinis browser port package (agent/browser/ported); provenance and
// adaptation scope are tracked in third_party/openminis-browser/PORT_STATUS.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

/**
 * 标签栏里「宽度份额」的取值下界：收拢到底时的极小正数。
 *
 * 取极小正数而非 0 有两个原因：
 * 1. `weight` 只在大于 0 时才参与「按权重比例分配剩余空间」，份额恰好为 0 的子项会被当成
 *    非权重子项、按内容自然宽度（文案 + 关闭钮）占位，于是过渡的首帧 / 末帧会闪一下原始宽度。
 * 2. 更要紧的是 Compose 的 `Modifier.weight` 对 **小于等于 0** 的权重直接抛
 *    IllegalArgumentException（"invalid weight; must be greater than zero"），而且这个检查
 *    发生在**组合期**（构造 Modifier 的那一刻），不是测量期。
 */
internal const val BrowserTabCollapsedFraction = 0.001f

/**
 * 标签本体的宽度份额。
 *
 * 稳态是 1f；展开 / 收拢途中按过渡进度插值。M3E 的默认空间规格是欠阻尼弹簧，插值会越过
 * 目标值（展开时 > 1f、收拢时 < 0），因此这里统一夹进 (0, 1]。
 *
 * @param revealFraction 过渡进度（0 → 1）。
 * @return 恒为正的权重份额。
 */
internal fun browserTabChipWeight(revealFraction: Float): Float {
    // NaN 与任何值比较都是 false，`coerceIn` 会原样放行；这里显式挡掉。
    if (revealFraction.isNaN()) return BrowserTabCollapsedFraction
    return revealFraction.coerceIn(BrowserTabCollapsedFraction, 1f)
}

/**
 * 互补占位的宽度份额（整行只剩一个标签时补一份，承接腾出来的宽度）。
 *
 * 同样必须恒为正：`1f - 1f == 0f` 的稳态值会让 Compose 在组合期直接抛异常，
 * 于是「点打开浏览器 → 标签栏首帧组合」当场闪退、浏览器页面一个像素都画不出来。
 *
 * @param revealFraction 过渡进度（0 → 1）。
 * @return 恒为正的权重份额，与本体份额之和约为 1f（至多 1.001f），行尾「+」不跳位。
 */
internal fun browserTabSpacerWeight(revealFraction: Float): Float {
    if (revealFraction.isNaN()) return 1f - BrowserTabCollapsedFraction
    return (1f - browserTabChipWeight(revealFraction)).coerceAtLeast(BrowserTabCollapsedFraction)
}
