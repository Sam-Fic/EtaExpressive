package io.github.mangi.eta.ui.components

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.localizedText
import io.github.mangi.eta.data.model.GptSpeedMode

/**
 * GPT 速度档位切换的纯动画策略与文案。
 *
 * 档位颜色已迁到官方 colorScheme 角色：见 [io.github.mangi.eta.ui.adaptive.etaGptSpeedColor]
 * （快速=tertiary、极速=error，不写死品牌色）。本文件只保留旋转/缩放曲线、闸门逻辑与文案，
 * 拆成纯函数/纯类是为了在没有 Compose 运行时的 JVM 单测里直接验证
 * “一次长按 = 一圈旋转 + 一次缩放”以及“动画中拒绝新的长按”，而不必渲染 UI。
 */

/** 一次速度切换动画的时长：旋转、缩放、颜色渐变共用，约 450ms。 */
internal const val GptSpeedAnimationDurationMillis = 450

/** 一次接受的长按恰好旋转一整圈。 */
internal const val GptSpeedRotationDegreesPerCycle = 360f

/** 缩放从 1 起步。 */
internal const val GptSpeedScaleStart = 1f

/** 缩放在动画中点达到峰值。 */
internal const val GptSpeedScalePeak = 1.12f

/** 动画进度映射出的旋转角度，进度会被夹取到 0..1。 */
internal fun gptSpeedRotationDegrees(progress: Float): Float =
    GptSpeedRotationDegreesPerCycle * progress.coerceIn(0f, 1f)

/**
 * 动画进度映射出的缩放：0 → 1.0，0.5 → 1.12，1 → 1.0 的单峰曲线。
 * 进度越界会被夹取，保证边界安全。
 */
internal fun gptSpeedScale(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    val peakWeight = 1f - kotlin.math.abs(clamped * 2f - 1f)
    return GptSpeedScaleStart + (GptSpeedScalePeak - GptSpeedScaleStart) * peakWeight
}

/**
 * 速度动画闸门：动画进行中拒绝新的长按。
 *
 * 不排队、不累计圈数，避免动画被截断成半圈或产生过期的切换请求。
 * 用 token 而非布尔：旧动画被取消（如模型切到非 GPT）后其 `finally` 才延迟执行，
 * 携带旧 token 的 finish 不能关掉随后新动画的门禁。
 * 纯逻辑（无 Compose 依赖），可直接单测。
 */
internal class GptSpeedAnimationGate {
    private var current: Int = 0
    private var issued: Int = 0

    /** 当前是否有动画在进行。 */
    val animating: Boolean
        get() = current != 0

    /**
     * 申请开始一次动画：
     * - 空闲时返回非 0 token 并进入“进行中”，调用方随后启动一圈动画；
     * - 进行中返回 0，调用方必须丢弃这次长按（不排队）。
     */
    fun request(): Int {
        if (current != 0) return 0
        issued += 1
        current = issued
        return current
    }

    /** 动画结束（含被取消）时携带自己的 token 调用，只有仍持锁的 token 才生效。 */
    fun finish(token: Int) {
        if (token != 0 && token == current) current = 0
    }

    /** 模型切到非 GPT 时立即清动画：丢弃进行中状态，无需等待动画结束。 */
    fun reset() {
        current = 0
    }
}

/** 档位展示名，仅用于 Toast 与无障碍描述，不代表业务状态。走资源，纯 JVM 单测回退中文。 */
internal fun gptSpeedDisplayName(mode: GptSpeedMode): String = when (mode) {
    GptSpeedMode.FAST -> localizedText(R.string.gpt_speed_fast, "快速")
    GptSpeedMode.ULTRA_FAST -> localizedText(R.string.gpt_speed_ultra_fast, "极速")
    GptSpeedMode.NORMAL -> localizedText(R.string.gpt_speed_normal, "标准")
}

/**
 * 所选速度的短提示，明确可能增加费用。
 * 只在长按切换（含首次）时弹出一次，不做常驻说明。
 */
internal fun gptSpeedToastMessage(mode: GptSpeedMode): String =
    localizedText(R.string.gpt_speed_toast, "已切换为%1\$s速度，可能增加费用", gptSpeedDisplayName(mode))

/**
 * 无障碍描述：把当前速度档位说清楚，并说明长按可切换。
 * 基础描述沿用既有“思考强度”文案，避免覆盖旧的思考强度语义。
 */
internal fun gptSpeedChipDescription(baseDescription: String, mode: GptSpeedMode): String =
    localizedText(R.string.gpt_speed_chip_description, "%1\$s，当前%2\$s速度，长按切换速度",
        baseDescription, gptSpeedDisplayName(mode))

/** 长按的无障碍 label，明确是切换速度而不是默认的协作设置。 */
internal fun gptSpeedLongPressLabel(): String = localizedText(R.string.gpt_speed_long_press, "切换速度")
