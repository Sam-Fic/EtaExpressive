package io.github.mangi.eta.ui.adaptive

/**
 * 透明度令牌：官方没有直接给出、但项目内反复出现的 alpha 值。
 *
 * 取值来自官方禁用态规范（disabled content alpha ＝ 38%）：任何「禁用 / 未授权 /
 * 不可用」内容的弱化统一用它，不再散落字面量。几何比例、渐变蒙版等非禁用语义的
 * 0.38 是巧合值，不引用本令牌。
 */
object EtaAlphas {
    /** 禁用态内容透明度（图标、文字、开关拇指、按钮内容）。 */
    const val DisabledContent = 0.38f
}
