package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/**
 * 帧循环的行为测试：用虚拟帧钟把 [runCapsuleMotion] 在无设备环境下逐帧跑起来。
 *
 * 这里记录的是**宿主真正收到的下发序列**（emit 的半像素去重在循环内侧），所以「动画末尾抽搐」
 * 能直接量化成「相邻两次下发的位移」：飞行段本来就有大位移，落定前最后一步必须收敛掉。
 * 超出预算帧数则对应「循环永不休眠」——它会冻结 flingRunning 的「晚缩」，收窗动作被拖到
 * 下一次动画末尾才发生，实机同样是末尾那一下。
 *
 * 驱动方式与生产同构：一次调用落定后重新进入（生产的 `while(true) { wakeups.receive(); runCapsuleMotion }`），
 * 场景脚本靠每帧取实测尺寸时的回调推进——尺寸变化在生产里正是唤醒源。
 */
private const val FrameNanos = 16_666_667L

/** 1080×2400、密度 3：物理常量按 dp 表达，换算成 px 后与参考实现量纲一致。 */
private val Screen = Rect(0f, 0f, 1080f, 2400f)
private const val Density = 3f
private val BallSize = IntSize(168, 168)

/** 虚拟帧钟：每次取帧推进一帧；超出预算直接判定循环没能休眠。 */
private class VirtualFrameClock(private val budgetFrames: Int) : MonotonicFrameClock {
    private var ticks = 0
    var nanos = 0L
        private set

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        ticks += 1
        check(ticks <= budgetFrames) {
            "帧循环跑了 $budgetFrames 帧仍未落定：应收敛并回调 onSettled 后休眠"
        }
        nanos += FrameNanos
        return onFrame(nanos)
    }
}

/** 一次运行的观测结果。emits 的 x = 内容中心 x，y = 内容顶边 y。 */
private class MotionRun(
    val emits: List<Offset>,
    val settled: Boolean,
    val frames: Int,
) {
    val maxStep: Float = run {
        var best = 0f
        for (i in 1 until emits.size) {
            best = maxOf(best, hypot(emits[i].x - emits[i - 1].x, emits[i].y - emits[i - 1].y))
        }
        best
    }

    /** 动画末尾那一下：最后一次下发相对上一次的位移。 */
    val lastStep: Float =
        if (emits.size < 2) 0f
        else hypot(
            emits.last().x - emits[emits.size - 2].x,
            emits.last().y - emits[emits.size - 2].y,
        )

    /** 落点相对首次下发的总位移。 */
    val travel: Float =
        if (emits.isEmpty()) 0f
        else hypot(emits.last().x - emits.first().x, emits.last().y - emits.first().y)

    val finalCenterX: Float get() = emits.last().x
    val finalTopY: Float get() = emits.last().y
}

/**
 * 驱动循环到脚本结束。[frameHook] 在每帧取实测尺寸前被调用（用它改拖动目标、模拟抬手），
 * [sizeOf] 给出该帧的实测内容尺寸，[stopWhen] 为真且本趟落定后停止重进。
 */
private fun runMotion(
    motion: CapsuleMotion,
    widthDp: Animatable<Float, AnimationVector1D>,
    viewport: Rect = Screen,
    budgetFrames: Int = 1_200,
    sizeOf: (frame: Int) -> IntSize,
    frameHook: (frame: Int) -> Unit = {},
    stopWhen: (frame: Int) -> Boolean = { true },
): MotionRun {
    val emits = mutableListOf<Offset>()
    var settled = false
    var frame = 0
    val clock = VirtualFrameClock(budgetFrames)
    runBlocking {
        withContext(clock) {
            while (true) {
                var settledThisCall = false
                runCapsuleMotion(
                    motion = motion,
                    widthDp = widthDp,
                    liveSize = {
                        val current = frame
                        frame += 1
                        frameHook(current)
                        sizeOf(current)
                    },
                    viewport = { viewport },
                    onPosition = { centerX, contentTopY -> emits += Offset(centerX, contentTopY) },
                    onSettled = { settledThisCall = true },
                )
                settled = settledThisCall
                if (settledThisCall && stopWhen(frame)) break
            }
        }
    }
    return MotionRun(emits, settled, frame)
}

/** 静止落位的初始状态：中心、顶边锚、已播种标记与宿主去重基线一次配好。 */
private fun restingMotion(center: Offset, size: IntSize, density: Float = Density): CapsuleMotion =
    CapsuleMotion(density).apply {
        positioned = true
        centerX = center.x
        centerY = center.y
        anchorTopY = center.y - size.height / 2f
        phase = CapsuleMotionPhase.Rest
        resetEmitted()
    }

class CapsuleMotionLoopTest {
    /** 循环必须收敛并休眠：判据只看实测尺寸是否连续几帧不变，与解析目标无关。 */
    @Test fun loopSleepsOnceMeasuredSizeStopsChanging() {
        val motion = restingMotion(Offset(540f, 1200f), BallSize)
        val run = runMotion(motion, Animatable(56f), sizeOf = { BallSize })
        assertTrue("循环未落定：尺寸早已不变却仍在逐帧空转", run.settled)
        // 落定只要「连续 2 帧实测不变」，稳定态就是三五帧的事。
        assertTrue("落定用了 ${run.frames} 帧，远超稳定态应有帧数", run.frames <= 8)
    }

    /** 甩出必须真的飞出去：抬手写进的速度要进得了积分（曾因换相位不重读速度而丢掉）。 */
    @Test fun flingTravelsAndDocksOnTheNearestEdge() {
        val motion = restingMotion(Offset(540f, 1200f), BallSize).apply {
            // 顶到右边界需要 456px：取高速档整速（阻力 2.5/s），飞行距离 ~1270px，必然越界。
            velocityX = CapsuleFlingPhysics.FastDragSpeedDpPerSec * Density
            phase = CapsuleMotionPhase.Free
        }
        val run = runMotion(motion, Animatable(56f), sizeOf = { BallSize })
        assertTrue(run.settled)
        // 逐帧下发才看得见「飞」：一次飞行只有一两下发，就是去重基线没建立起来（NaN 比较恒 false）。
        assertTrue("整趟飞行只下发了 ${run.emits.size} 次", run.emits.size > 10)
        assertTrue("甩出只跑了 ${run.travel}px，速度没有进积分", run.travel > 300f)
        // 向右飞 → 最近边是右边 → 本体贴死可用边界（内容盒口径，不含阴影外衬）。
        assertEquals(Screen.right - BallSize.width / 2f, run.finalCenterX, 2f)
        assertEquals(CapsuleMotionPhase.Docked, motion.phase)
        assertTrue("末尾一步跳了 ${run.lastStep}px", run.lastStep <= 6f)
    }

    /** 向上甩出屏后由弹簧拉回贴上边：落定末步必须收敛，否则就是回弹尾巴上的急刹。 */
    @Test fun tailStepIsSubPixelAfterAFlight() {
        val motion = restingMotion(Offset(300f, 700f), BallSize).apply {
            // 4200dp/s 换算后正好落在高速档（阻力 2.5/s），飞行距离足以顶出上边界。
            velocityY = -CapsuleFlingPhysics.FastDragSpeedDpPerSec * Density
            phase = CapsuleMotionPhase.Free
        }
        val run = runMotion(motion, Animatable(56f), sizeOf = { BallSize })
        assertTrue(run.settled)
        assertEquals(CapsuleMotionPhase.Docked, motion.phase)
        // 贴的是上边：法向（y）贴死，切向（x）不受弹簧力，只受飞行阻力 → 停在甩出时的横向位置。
        assertEquals(0f, run.finalTopY, 2f)
        assertEquals(300f, run.finalCenterX, 2f)
        assertTrue("末尾一步跳了 ${run.lastStep}px", run.lastStep <= 6f)
    }

    /** 跟手：指针每帧推进 20px 时落点残差必须在一帧量级内（拖影过大就是「卡手」）。 */
    @Test fun dragTracksTheFingerWithinOneFrame() {
        val motion = restingMotion(Offset(540f, 1200f), BallSize).apply {
            pointerDown = true
            phase = CapsuleMotionPhase.Drag
            dragTargetX = 540f
            dragTargetY = 1200f
        }
        var worstTrail = 0f
        val run = runMotion(
            motion,
            Animatable(56f),
            sizeOf = { BallSize },
            stopWhen = { frame -> frame >= 24 },
            frameHook = { frame ->
                if (frame < 20) {
                    motion.dragTargetX = 540f + frame * 20f
                    motion.dragTargetY = 1200f - frame * 12f
                } else {
                    // 手指停住后抬手：无甩出速度，循环就地收口成静止，不许回弹或瞬移。
                    motion.pointerDown = false
                }
                if (frame > 2) {
                    worstTrail = maxOf(
                        worstTrail,
                        hypot(motion.dragTargetX - motion.centerX, motion.dragTargetY - motion.centerY),
                    )
                }
            },
        )
        assertTrue(run.settled)
        assertEquals(CapsuleMotionPhase.Rest, motion.phase)
        // 每帧一次下发：拖动若只在末了下发一次，窗口就是「按住不动、松手才跳」。
        assertTrue("整趟拖动只下发了 ${run.emits.size} 次", run.emits.size > 12)
        assertTrue("拖动跟手残差 ${worstTrail}px，指针一帧才走 23px，落后就是黏手", worstTrail <= 30f)
        assertEquals(920f, run.finalCenterX, 4f)
        assertEquals(972f - BallSize.height / 2f, run.finalTopY, 4f)
        assertTrue("末尾一步跳了 ${run.lastStep}px", run.lastStep <= 6f)
    }

    /**
     * 左边缘展开：尺寸逐帧变宽时落点只能逐帧爬行。
     *
     * 跳变的老成因是按**解析目标**尺寸钳位——「早涨」那一瞬 minCenterX 一步跨掉 Δ宽/2，
     * 实机就是贴左边展开时整块卡片横向 teleport。这里断言最大单步远小于总位移。
     */
    @Test fun expandingAtTheLeftEdgeCreepsInsteadOfTeleporting() {
        val small = IntSize(168, 168)
        val large = IntSize(924, 168)
        val motion = restingMotion(Offset(small.width / 2f, 300f), small)
        val run = runMotion(
            motion,
            Animatable(56f),
            budgetFrames = 600,
            // 前 4 帧建立稳定基线（循环会先落定一次），随后 20 帧按主轴节奏长宽。
            sizeOf = { frame ->
                when {
                    frame < 4 -> small
                    frame < 24 -> IntSize(
                        small.width + (large.width - small.width) * (frame - 4) / 20,
                        small.height,
                    )
                    else -> large
                }
            },
            stopWhen = { frame -> frame >= 26 },
        )
        assertTrue(run.settled)
        val totalShift = (large.width - small.width) / 2f
        assertTrue("左边缘展开只爬了 ${run.travel}px，应约 ${totalShift}px", run.travel > totalShift * 0.9f)
        assertTrue("单步最大 ${run.maxStep}px：展开把位置一次推到位就是跳变", run.maxStep <= 30f)
        assertEquals(large.width / 2f, run.finalCenterX, 3f)
        assertTrue("展开末尾一步跳了 ${run.lastStep}px", run.lastStep <= 6f)
    }

    /** 拖到左边缘之外：硬钳停在界内（参考实现 drag 分支没有橡皮筋），松手就地落定。 */
    @Test fun draggingPastTheLeftEdgeStaysClampedWithoutRubberBand() {
        val motion = restingMotion(Offset(84f, 600f), BallSize).apply {
            pointerDown = true
            phase = CapsuleMotionPhase.Drag
            dragTargetX = 84f
            dragTargetY = 600f
        }
        val run = runMotion(
            motion,
            Animatable(56f),
            sizeOf = { BallSize },
            stopWhen = { frame -> frame >= 10 },
            frameHook = { frame ->
                // 指针一路往左戳出屏外 400px，本体只能停在内容盒左界，不许被拉回去再弹回来。
                motion.dragTargetX = 84f - frame * 40f
                if (frame >= 8) motion.pointerDown = false
            },
        )
        assertTrue(run.settled)
        assertEquals(84f, run.finalCenterX, 1f)
        assertTrue("贴边收尾一步跳了 ${run.lastStep}px", run.lastStep <= 6f)
    }

    /** 顶边锚：静止态尺寸长高只向下长，顶边不上下窜（大卡是下拉式容器）。 */
    @Test fun restingSizeGrowthKeepsTheTopEdgeAnchored() {
        val short = IntSize(924, 168)
        val tall = IntSize(924, 900)
        val motion = restingMotion(Offset(462f, 300f), short)
        val run = runMotion(
            motion,
            Animatable(328f),
            budgetFrames = 400,
            sizeOf = { frame -> if (frame < 4) short else tall },
            stopWhen = { frame -> frame >= 8 },
        )
        assertTrue(run.settled)
        // 顶边锚 = 300 − 168/2 = 216：长高只往下长，顶边一帧都不许动。
        assertEquals(216f, run.finalTopY, 1.5f)
    }

    /** 甩出缩球：形变与物理同帧推进，宽度收到球径时落点仍按实时尺寸留在界内，末帧不再补一跳。 */
    @Test fun ballMorphDuringFlightConvergesWithThePhysics() {
        val motion = restingMotion(Offset(540f, 1200f), IntSize(984, 168)).apply {
            velocityX = 2400f
            phase = CapsuleMotionPhase.Free
            morphWidth = true
            widthTargetDp = 56f
        }
        val widthDp = Animatable(328f)
        val run = runMotion(
            motion,
            widthDp,
            sizeOf = { IntSize((widthDp.value * Density).toInt(), 168) },
        )
        assertTrue(run.settled)
        assertEquals(56f, widthDp.value, 0.05f)
        val ballHalf = 56f * Density / 2f
        // 参考实现的 free→settle 就地停在「当下尺寸钳过的落点」，缩球后不再补贴边，
        // 所以这里只要求落在内容盒界内且末步收敛，不要求它一定贴死屏幕边。
        assertTrue("缩球落点 ${run.finalCenterX} 越过内容盒右界", run.finalCenterX <= Screen.right - ballHalf + 1f)
        assertTrue("缩球落点 ${run.finalCenterX} 没跟着形变往右走", run.finalCenterX > 540f)
        assertTrue("末尾一步跳了 ${run.lastStep}px", run.lastStep <= 6f)
    }

    /** 越界回弹：用力甩出去仍由一根弹簧连续拉回贴边，单步不超过飞行节奏。 */
    @Test fun overshootIsPulledBackByOneContinuousSpring() {
        val motion = restingMotion(Offset(540f, 1200f), BallSize).apply {
            velocityX = 6000f
            phase = CapsuleMotionPhase.Free
        }
        val run = runMotion(motion, Animatable(56f), sizeOf = { BallSize })
        assertTrue(run.settled)
        assertEquals(CapsuleMotionPhase.Docked, motion.phase)
        assertEquals(Screen.right - BallSize.width / 2f, run.finalCenterX, 2f)
        // 6000px/s 的飞行本身就是每帧 100px；回弹单步若远超这个量级就是急刹/闪现。
        assertTrue("回弹单步 ${run.maxStep}px", run.maxStep <= 110f)
    }
}
