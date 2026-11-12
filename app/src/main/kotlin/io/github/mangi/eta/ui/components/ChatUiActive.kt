package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether this Home/Chat route is the current destination.
 * miuix NavDisplay keeps the covered entry (1 层覆盖) composed for the back stack; those
 * entries must not apply streaming message updates, or they share the frame with Settings.
 * Default true so standalone previews and the voice panel keep live content.
 */
val LocalChatUiActive = staticCompositionLocalOf { true }

/**
 * 聊天还在组合里，但已经不是栈顶。半遮住时仍要显示实时消息；
 * 整列离屏裁剪只留给完全打开的聊天，避免被盖住时每帧重录。
 */
// This value changes on navigation: invalidate readers, not the entire routed subtree.
val LocalChatRouteCovered = compositionLocalOf { false }

/**
 * 推入、弹出或横滑返回还在动。只在开始和结束变化，不随每一帧翻转。
 * 停稳且被盖住时聊天不再跟着流式正文排版；动画期间露出的部分仍要实时。
 */
val LocalChatTransitionActive = compositionLocalOf { false }

/** 只有停稳的覆盖才允许挂起 UI 时钟；推移、弹出、横滑返回过程中仍保持实时。 */
internal fun isChatSettledCovered(routeCovered: Boolean, transitionActive: Boolean): Boolean =
    routeCovered && !transitionActive

/**
 * 系统助手在主应用前台唤醒的请求令牌：聊天输入区按唤起方式分派响应——
 * 非零听写令牌直接开录听写胶囊（首帧桥让胶囊就位）、聚焦令牌聚焦输入框并弹键盘。
 * 令牌单调递增不复位；防重放靠进程级已消费水位（见下）——composer 实例随切会话
 * 销毁重建时播种水位，旧令牌不再被当成新唤起重放（与浮窗 dictationStartRequest
 * 不同：浮窗面板重建即重放是刻意设计）。
 */
val LocalAssistantDictationRequest = compositionLocalOf { 0 }
val LocalAssistantFocusRequest = compositionLocalOf { 0 }

/**
 * 系统助手唤起令牌的已消费水位：主应用侧令牌单调递增不复位，而消费它的 composer
 * 实例随会话切换销毁重建——重建实例播种水位后旧令牌不再重放（否则每次切会话
 * 都会误开听写/重弹键盘）。消费方在消费时经 MainActivity.markInApp*Consumed
 * 上报（取 max）；浮窗不走这套水位，不传即无感。
 */
val LocalAssistantDictationConsumed = compositionLocalOf { 0 }
val LocalAssistantFocusConsumed = compositionLocalOf { 0 }
/** 完成且不再逐帧变化的内容复用一张离屏纹理。层始终挂着，只切换合成策略，避免插入时重挂。 */
@Composable
internal fun completedContentDrawLayer(modifier: Modifier, enabled: Boolean): Modifier {
    var heightPx by remember { mutableIntStateOf(0) }
    val retain = enabled && heightPx in 1..8192
    return modifier
        .onSizeChanged { heightPx = it.height }
        .graphicsLayer(
            compositingStrategy = if (retain) CompositingStrategy.Offscreen else CompositingStrategy.Auto,
        )
}
