package io.github.mangi.eta.agent.live

import java.util.Locale

/**
 * 通知与超级岛的固定语义色板。
 *
 * 通知路径没有 Compose 组合，且系统动态色会随壁纸漂移，无法承担「餐食/饮品/快递」
 * 或「运行/完成」的稳定识别，因此颜色在本文件统一定义。强调色取仓内既有 Material
 * 种子色；深色圆砖与两档浅色前景构成 tonal 对比，提示字与按钮白字的对比度均不低于 4.5:1。
 */
internal data class IslandPalette(
    val accentArgb: Int,
    val containerArgb: Int,
    val contentLightArgb: Int,
    val contentDarkArgb: Int,
) {
    /** 小米模板 16 使用 #RRGGBB；统一 Locale 避免不同系统语言改变十六进制输出。 */
    val accentHex: String get() = accentArgb.toHex()
    val containerHex: String get() = containerArgb.toHex()
    val contentLightHex: String get() = contentLightArgb.toHex()
    val contentDarkHex: String get() = contentDarkArgb.toHex()

    private fun Int.toHex(): String = "#" + (this and 0xFFFFFF).toString(16)
        .uppercase(Locale.ROOT)
        .padStart(6, '0')

    companion object {
        /** Agent 运行：Material 蓝，与应用既有蓝色种子同语义。 */
        val AgentRunning = IslandPalette(
            accentArgb = 0xFF0B57D0.toInt(),
            containerArgb = 0xFF12305A.toInt(),
            contentLightArgb = 0xFF6EA8FF.toInt(),
            contentDarkArgb = 0xFFAECBFA.toInt(),
        )

        /** Agent 完成：复用 EtaStatus 的固定成功绿，避免完成态被动态主题染成其他颜色。 */
        val AgentCompleted = IslandPalette(
            accentArgb = 0xFF386A20.toInt(),
            containerArgb = 0xFF203515.toInt(),
            contentLightArgb = 0xFF86C86A.toInt(),
            contentDarkArgb = 0xFF9CD67D.toInt(),
        )

        /** Agent 失败：Material 错误红，仅用于持久终态横幅。 */
        val AgentFailed = IslandPalette(
            accentArgb = 0xFFBA1A1A.toInt(),
            containerArgb = 0xFF3B1010.toInt(),
            contentLightArgb = 0xFFFF8A80.toInt(),
            contentDarkArgb = 0xFFFFB4AB.toInt(),
        )

        /** Agent 停止：Material 中性色，不借用成功/错误语义。 */
        val AgentStopped = IslandPalette(
            accentArgb = 0xFF605D64.toInt(),
            containerArgb = 0xFF1D1B20.toInt(),
            contentLightArgb = 0xFFBDB8C2.toInt(),
            contentDarkArgb = 0xFFCAC4D0.toInt(),
        )

        /** 餐食：暖橙色，与原有餐食提醒的琥珀识别一致。 */
        val PickupFood = IslandPalette(
            accentArgb = 0xFF9A4D00.toInt(),
            containerArgb = 0xFF3B2410.toInt(),
            contentLightArgb = 0xFFFFC066.toInt(),
            contentDarkArgb = 0xFFFFD08A.toInt(),
        )

        /** 饮品：青色，对应仓内既有 teal 强调色。 */
        val PickupDrink = IslandPalette(
            accentArgb = 0xFF006A6A.toInt(),
            containerArgb = 0xFF103B3B.toInt(),
            contentLightArgb = 0xFF4DDADA.toInt(),
            contentDarkArgb = 0xFF80EAEA.toInt(),
        )

        /** 快递：紫色，对应仓内既有紫色强调色。 */
        val PickupExpress = IslandPalette(
            accentArgb = 0xFF6750A4.toInt(),
            containerArgb = 0xFF2E1D4D.toInt(),
            contentLightArgb = 0xFFCFA8FF.toInt(),
            contentDarkArgb = 0xFFE8D5FF.toInt(),
        )

    }
}
