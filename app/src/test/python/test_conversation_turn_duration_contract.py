"""会话跳转的静态契约：限制总时长，不限制最大速度。

旧的 ConversationTurnMotion 是「加速度 + 巡航速度上限 + 单帧半屏钳位」，
距离越长耗时几乎线性增长，长按跳到会话开头/末尾会滚十几秒。现在距离只
决定时长（封顶 650ms），速度由 total / duration 反推，到点必须 snap。
"""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[4]
COMPONENTS = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'
MOTION = (COMPONENTS / 'ConversationTurnMotion.kt').read_text(encoding='utf-8')
JUMP = (COMPONENTS / 'ConversationTurnJump.kt').read_text(encoding='utf-8')
BODY = (COMPONENTS / 'AgentChatBody.kt').read_text(encoding='utf-8')
KOTLIN_TEST = (ROOT / 'app/src/test/kotlin/io/github/mangi/eta/ui/components'
               / 'ConversationTurnMotionTest.kt').read_text(encoding='utf-8')


class ConversationTurnDurationContractTest(unittest.TestCase):

    def test_duration_bounds_are_explicit_and_capped(self):
        numbers = dict((name, int(value)) for name, value in re.findall(
            r'internal const val (ConversationTurn(?:Min|Max)DurationMillis) = (\d+)', MOTION))
        self.assertEqual({'ConversationTurnMinDurationMillis',
                          'ConversationTurnMaxDurationMillis'}, set(numbers))
        self.assertLess(numbers['ConversationTurnMinDurationMillis'],
                        numbers['ConversationTurnMaxDurationMillis'])
        self.assertLessEqual(numbers['ConversationTurnMaxDurationMillis'], 800)
        self.assertIn('ConversationTurnFullScaleScreens = 8f', MOTION)
        resolver = MOTION.split('internal fun resolveConversationJumpDurationMillis(', 1)[1].split(
            'internal val ConversationTurnEasing', 1)[0]
        self.assertIn('(screens / ConversationTurnFullScaleScreens).coerceIn(0f, 1f)', resolver)
        self.assertIn('ConversationTurnMaxDurationMillis', resolver)
        self.assertIn('roundToInt()', resolver)

    def test_no_velocity_cap_or_per_frame_ceiling_remains(self):
        # 这四处正是「距离越长滚越久」的根源，任一回归都会让超长跳转再次变慢。
        for forbidden in ('cruiseSpeed', 'val acceleration =', 'private var speed =',
                          'min(speed * dt, viewport * 0.5f)', 'brakingSpeed', 'sqrt(2f *'):
            self.assertNotIn(forbidden, MOTION)
        self.assertNotIn('import kotlin.math.sqrt', MOTION)
        self.assertIn('没有任何速度或单帧位移上限', MOTION)

    def test_motion_is_time_parametrised_and_uses_real_consumed_pixels(self):
        advance = MOTION.split('fun advance(', 1)[1].split('/** 时间预算用尽', 1)[0]
        self.assertIn('if (dt <= 0f || budgetExhausted()) return 0f', advance)
        self.assertIn('val travelled = travelledPx.coerceAtLeast(0f)', advance)
        self.assertIn('val total = (travelled + distance).coerceAtLeast(viewport)', advance)
        self.assertIn('val duration = budgetFor(total)', advance)
        self.assertIn('elapsedSeconds = (elapsedSeconds + dt).coerceAtMost(duration)', advance)
        self.assertIn('val desiredTravelled = total * conversationJumpEasing(to)', advance)
        self.assertIn('(desiredTravelled - travelled).coerceIn(0f, distance)', advance)
        # 必须用「应到位置 − 实际消费」的绝对差，easing 差分补不回欠消费。
        self.assertNotIn('easing(from)', advance)
        self.assertIn('.coerceIn(0f, distance)', advance)
        self.assertIn('fun budgetExhausted(): Boolean', MOTION)
        self.assertIn('elapsedSeconds >= durationSeconds', MOTION)
        # 时长只许变长，粗估修正时 progress 不许倒退。
        self.assertIn('durationSeconds = maxOf(durationSeconds,', MOTION)

    def test_under_consumed_frames_are_repaid_not_dropped(self):
        self.assertIn('欠消费', MOTION)
        self.assertIn('desiredTravelled - travelled', MOTION)
        self.assertIn('travelledPx += abs(consumed)', JUMP)
        self.assertIn('underConsumedFramesAreMadeUpInsteadOfBeingDropped', KOTLIN_TEST)

    def test_easing_uses_official_m3_expressive_curve_without_overshoot(self):
        # 官方 easing 预设来自 material3 1.5.0-alpha29 AAR 的 MotionTokens
        # (javap 核实 EasingEmphasizedCubicBezier = (0.2, 0, 0, 1)，与 standard 同值)。
        self.assertIn('import androidx.compose.animation.core.CubicBezierEasing', MOTION)
        self.assertIn('import androidx.compose.animation.core.Easing', MOTION)
        self.assertIn(
            'internal val ConversationTurnEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)',
            MOTION)
        self.assertIn('ConversationTurnEasing.transform(progress.coerceIn(0f, 1f))', MOTION)
        self.assertIn('MotionTokens', MOTION)
        self.assertIn('getEasingEmphasizedCubicBezier()', MOTION)
        self.assertIn('EasingStandardCubicBezier', MOTION)
        self.assertIn('CubicBezierEasing(Float, Float, Float, Float)', MOTION)
        # 两类官方预设必须写清：easing 全无过冲；spring 的 spatial 会回弹、
        # effects 阻尼 1.0 不回弹，两套 motionScheme 六规格全是 SpringSpec。
        self.assertIn('MotionScheme.expressive()', MOTION)
        self.assertIn('MotionScheme.standard()', MOTION)
        self.assertIn('全是 SpringSpec', MOTION)
        self.assertIn('y 控制点全部落在 [0,1]', MOTION)
        self.assertIn('0.8 / 0.6、Standard 0.9', MOTION)
        self.assertIn('阻尼 1.0 临界阻尼', MOTION)
        # 手写的 smoothstep 与任何 spring 构造都不许回来。
        self.assertNotIn('p * p * (3f - 2f * p)', MOTION)
        self.assertNotIn('androidx.compose.animation.core.spring', MOTION)
        self.assertNotIn('DampingRatio', MOTION)
        # 无过冲由 y 控制点 0/1 保证，Kotlin 单测逐点验证单调与 [0,1]。
        self.assertIn('[0,1] 且单调不减', MOTION)
        self.assertIn('easingIsOfficialM3ExpressiveCurveWithoutOvershoot', KOTLIN_TEST)

    def test_jump_feeds_measured_and_estimated_distance_into_the_budget(self):
        self.assertIn('var travelledPx = 0f', JUMP)
        self.assertIn('remaining?.let(::abs) ?: estimateDistanceToTarget(target)', JUMP)
        self.assertIn('travelledPx += abs(consumed)', JUMP)
        self.assertIn('motion.advance(elapsed, travelledPx, distance)', JUMP)
        self.assertIn('private fun LazyListState.estimateDistanceToTarget(target: Int): Float', JUMP)
        self.assertIn('items.sumOf { it.size }', JUMP)

    def test_time_budget_hard_stops_with_a_snap(self):
        # 最后一帧先消费再收尾；预算用尽一律定位到目标，不允许继续逐帧滚动。
        tail = JUMP.split('if (motion.budgetExhausted()) {', 1)
        self.assertEqual(2, len(tail))
        self.assertIn('lazyScope.snapToItem(target)', tail[1].split('}', 1)[0])
        self.assertLess(JUMP.index('travelledPx += abs(consumed)'),
                        JUMP.index('if (motion.budgetExhausted()) {'))
        self.assertIn('ConversationTurnMaxFrameSeconds', JUMP)

    def test_all_programmatic_jumps_share_the_duration_capped_animation(self):
        self.assertIn('scrollState.animateToConversationTurn(target)', BODY)
        self.assertIn('scrollState.animateToConversationTurn(index)', BODY)
        self.assertIn('scrollState.animateToConversationTurn(decision.requestIndex)', BODY)
        self.assertNotIn('animateScrollToItem(', BODY)
        self.assertIn('hasClampedScrollReserve(forward)', JUMP)

    def test_kotlin_unit_test_pins_the_time_cap_semantics(self):
        for name in ('distanceOnlyChoosesDurationAndTheDurationIsCapped',
                     'ultraLongJumpEndsWithinTheSameBudgetAsAShortOne',
                     'longDistanceRaisesSpeedInsteadOfExtendingTheAnimation',
                     'frameGapsCannotStretchTheBudgetOrProduceEndlessMotion'):
            self.assertIn(name, KOTLIN_TEST)
        self.assertIn('budgetExhausted()', KOTLIN_TEST)


if __name__ == '__main__':
    unittest.main()
