package io.github.mangi.eta.ui.components

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.isActive

/**
 * 浅色点阵的混白比例：点阵走「发光发白」路线——比底亮的点半透明混合只会更亮、
 * 不会发灰（发灰的是比底暗的低饱和色斑）。原设计档 0.55 在带色相的新底色上偏弱，
 * 2026-11-11 用户定稿「发光发白」后起步 0.7；嫌不够白调大、想更跟主题色调小。
 */
private const val LightDotWhiteMix = 0.7f

/**
 * 口述语音条里的「流光胶囊」，逐项移植自语音条原型 `index.html` 的 `createVoiceCapsule`。
 * 该原型已不在参考仓库 D:\gemini-voice-light 里（在案只剩纯灯效 demo
 * `capsule_light_triangular_dot_matrix.html`，效果层与它逐项一致）；数值以本文件各处注释为准。
 * 与在案示例的已知刻意差异：光层模糊 46u（示例为 blur(30px)），系刻意设计，勿当作偏差对齐。
 * 本仓新增「启动预热」动画：口述会话开始时光效从全灭在约 600ms 内缓升到实际强度
 * （easeOutCubic），光团、波浪、色相水洗与点阵一起升，暗示启动过程；每个会话只播一次。
 * 退场对称：退出听写时（pill 槽淡出转场期间）光效按镜像曲线在约 350ms 内缓降到全灭，
 * 强度与透明度一起收——转场结束时光恰好熄灭，不会闪断。
 *
 * 三层结构（与原型同序）：
 * 1. metaball 层：9 颗骑在胶囊下轮廓上的光团，径向贝尔衰减叠加（深色 + 相加、浅色 + 普通叠加），
 *    邻团自然熔成一体；
 * 2. 受光层：metaball 与沿下轮廓起伏的波浪主体一起做一次高斯扩散（RenderEffect 模糊），
 *    再过一层 AGSL「微微色散」（红/蓝通道沿径向错开几 px，见 DISPERSION_AGSL）；
 *    浅色额外叠一层横向色相水洗（SRC_ATOP 只染已有光的像素）；
 * 3. 点阵层：透视三角点阵被光推起（整列抬升 + 局部抬升），近处大、远处小，
 *    常驻铺满整颗胶囊（「整张网」，透明度只由顶/底渐隐带、光挖空、左右边缘淡出与
 *    闪烁调制）；深色 additive 提亮、浅色发白（palette.dots 混白点色）。
 *
 * 所有几何按胶囊高度纯等比缩放：基准 337（参考预览图的胶囊高度），
 * `u = 高度px / 337`——不做任何下限，等价于把参考胶囊等比缩到本胶囊的高度。
 *
 * 配色跟随 M3 主题：浅色光效纯从 `tertiaryContainer` 派生（White 只做提亮高光，
 * 不混黑、不引 tertiary——2026-11-11 用户定稿）；胶囊底色浅色取 `surfaceBright`
 * （与工具调用卡片同档表面）、深色取 `surfaceContainerLowest`。深色光效由
 * `colorScheme.primary` / `inversePrimary` 派生（偏薰衣草白），不写任何颜色字面量。
 */
@Composable
internal fun VoiceGlowCapsule(
    level: () -> Float,
    exiting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    // 与 EtaStatus 等现有代码同一判据：surface 亮 → 浅色，暗 → 深色。
    val lightMode = colorScheme.surface.luminance() > 0.5f
    val palette = remember(
        colorScheme.tertiaryContainer,
        colorScheme.primary,
        colorScheme.inversePrimary,
        lightMode,
    ) {
        glowPalette(
            base = if (lightMode) colorScheme.tertiaryContainer else colorScheme.primary,
            // 深一档的锚色只服务深色模式（inversePrimary = 暗色 scheme 里主色的深端）；
            // 浅色纯从 tertiaryContainer 取、不引 tertiary（2026-11-11 用户定稿），不用此参数。
            deeper = colorScheme.inversePrimary,
            lightMode = lightMode,
        )
    }
    // 浅色底取 surfaceBright：与工具调用卡片同一档表面（从页面背景浮出一档），
    // 2026-11-11 用户定稿（此前短暂用过输入框同色 surfaceContainer）。
    // 低 alpha 光残尾叠浅底会更淡、色相仍纯（光效纯从 tertiaryContainer 取）；
    // 实机嫌顶部太淡的杠杆是光团贝尔包络 0.62 提档，不是给底色回填染色。
    // 深色底近黑维持表面角色不动（近黑上低 alpha 光读作发光）。
    val capsuleFill = if (lightMode) {
        colorScheme.surfaceBright
    } else {
        colorScheme.surfaceContainerLowest
    }
    val engine = remember { VoiceGlowEngine() }
    // 音量来自音频线程的普通字段：每帧现读，不参与重组快照。
    val currentLevel by rememberUpdatedState(level)
    // 帧计数只在绘制阶段读取：每帧只失效绘制，不触发重组。
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(engine) {
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                // 以 60fps 为一帧基准：高刷屏上按实际帧间隔折算，动画速度与原型一致。
                val frames = if (lastNanos == 0L) {
                    1f
                } else {
                    ((now - lastNanos) / 16_666_667f).coerceIn(0.2f, 4f)
                }
                lastNanos = now
                engine.advance(frames, currentLevel())
                frame++
            }
        }
    }
    // 退场冷却：口述退出时本内容仍在 pill 槽转场里组合，通知引擎点燃冷却——光效随
    // 转场淡出同步缓降到全灭；冷却期内若重新开始听写，撤销冷却、光效原地复燃。
    LaunchedEffect(engine, exiting) {
        engine.setExiting(exiting)
    }
    Canvas(modifier) {
        if (frame >= 0L) engine.draw(this, palette, capsuleFill, lightMode)
    }
}

/** 主题派生的光系：5 个光团色 + 点阵色 + 深色白热核心。 */
private class GlowPalette(val blobs: List<Color>, val dots: Color, val core: Color)

private fun glowPalette(base: Color, deeper: Color, lightMode: Boolean): GlowPalette = if (lightMode) {
    // 浅色：纯从 tertiaryContainer 取、不引 tertiary（2026-11-11 用户定稿，取代上一版
    // 「tertiary 全彩度锚」方案）；饱和度上限即 tertiaryContainer 本色。深浅变化只用
    // White 提亮档表达、不混黑——混黑在浅底低 alpha 区就是发灰的来源。底色
    // （surfaceContainer，与输入框同色）不在本调色板内，由调用方给定。
    GlowPalette(
        blobs = listOf(
            base,                           // 0 主体：波浪底档与光团本色
            lerp(base, Color.White, 0.20f), // 1 波浪上段渐隐档：明显浅一档
            lerp(base, Color.White, 0.16f), // 2 光团变体 / 水洗
            lerp(base, Color.White, 0.34f), // 3 高光档：水洗两端 + 最亮变体
            lerp(base, Color.White, 0.08f), // 4 微提亮变体
        ),
        // 点阵发白（见 [LightDotWhiteMix]）：比底亮的点半透明混合只会更亮、不会发灰。
        dots = lerp(base, Color.White, LightDotWhiteMix),
        // 浅色不点白热核心：核心就是光团本色，否则底部会像在发白光。
        core = base,
    )
} else {
    // 深色：基准 primary；暗底上叠加白热核心后整体偏薰衣草白。深一档的光团混向
    // inversePrimary（暗色 scheme 里是同一主色的深端），比混黑更保饱和，
    // 对应原型 primary70/60 的色阶。
    GlowPalette(
        blobs = listOf(
            base,
            lerp(base, deeper, 0.15f),
            lerp(base, deeper, 0.34f),
            lerp(base, deeper, 0.10f),
            lerp(base, deeper, 0.24f),
        ),
        dots = lerp(base, Color.White, 0.36f),
        core = lerp(base, Color.White, 0.92f),
    )
}

/** 点阵里的单颗点：基准坐标固定，实际 y 由光场弹性抬升，x 严格垂直（不会交叉）。 */
private class GlowDot(
    val baseX: Float,
    val baseY: Float,
    val depth: Float,
    val pulseSpeed: Float,
) {
    var y: Float = baseY
    var phase: Float = Random.nextFloat() * (2f * PI.toFloat())
}

/** 光团：基准位置 + 缓慢漂移 + 端部渐隐权重。 */
private class GlowBlob(
    val baseX: Float,
    val baseRadius: Float,
    val speed: Float,
    val amp: Float,
    phase0: Float,
) {
    var phase: Float = phase0
}

/**
 * 流光胶囊的渲染引擎。所有绘制都在 px 空间完成，等比缩放系数 `u = 高度px / 337`。
 * 状态（相位、时间、点阵）全部挂在实例上，由外部帧循环推进，绘制本身是纯计算。
 */
private class VoiceGlowEngine {

    private var width = 0f
    private var height = 0f
    private var u = 1f
    private var capsuleRadius = 0f
    private var capsuleBottom = 0f
    private var bottomPad = 0f

    private var time = 0f

    // 麦克风音量：原始值每帧从外部读取，这里再按原型同款指数平滑一次。
    private var smoothedLevel = 0f
    // 当前帧的发光强度：原型 activeGlowIntensity = 0.8 * (1 + volume * 1.4)。
    private var glowIntensity = BaseGlowIntensity
    // 本帧相对 60fps 的倍率：弹性插值按它折算，高刷 / 掉帧时手感与原型一致。
    private var frameScale = 1f
    // 启动预热：会话开始时光效从全灭缓升到实际强度（已推进的 60fps 帧数与缓出后的系数）。
    private var warmupFrames = 0f
    private var warmupEase = 0f
    // 退场冷却：退出听写时光效从当前强度缓降到全灭（<0 = 未启动；已推进帧数与剩余系数）。
    private var cooldownFrames = -1f
    private var cooldownEase = 1f

    // 与原型一一对应的 9 颗光团（基准轴、半径、漂移相位/速度/振幅）。
    private val blobs = arrayOf(
        GlowBlob(0.03f, 120f, 0.011f, 0.030f, 0.0f),
        GlowBlob(0.10f, 135f, 0.013f, 0.035f, 0.9f),
        GlowBlob(0.17f, 145f, 0.010f, 0.040f, 1.8f),
        GlowBlob(0.31f, 155f, 0.014f, 0.050f, 2.6f),
        GlowBlob(0.50f, 170f, 0.009f, 0.045f, 3.4f),
        GlowBlob(0.69f, 155f, 0.013f, 0.050f, 4.2f),
        GlowBlob(0.83f, 145f, 0.011f, 0.040f, 5.0f),
        GlowBlob(0.90f, 135f, 0.012f, 0.035f, 5.8f),
        GlowBlob(0.97f, 120f, 0.010f, 0.030f, 6.6f),
    )
    private val blobX = FloatArray(blobs.size)
    private val blobY = FloatArray(blobs.size)
    private val blobRadius = FloatArray(blobs.size)
    // 点阵专用半径：忽略端部弧线的收缩（点阵只认平底），与原型注释一致。
    private val blobDotRadius = FloatArray(blobs.size)
    private val blobEdge = FloatArray(blobs.size)

    private val dots = ArrayList<GlowDot>(2048)
    private var gridWidth = -1f
    private var gridHeight = -1f

    private val lightNode = RenderNode("eta-voice-glow")
    // 微微色散：lightNode 的包装层——高斯在 lightNode 上先跑，AGSL 色散在本层后跑
    // （顺序反了彩边会被 46u 糊平）。RuntimeShader(AGSL) 需 API 33，minSdk 34 直接用、
    // 无需降级分支；RuntimeShader(String) / setFloatUniform(String,float,float) /
    // setFloatUniform(String,float) / createRuntimeShaderEffect(RuntimeShader,String)
    // 均已对 android-37.0 android.jar javap 核证（createRuntimeShaderEffect 无 input
    // 重载，所以必须包一层节点，不能把两个 effect 直接串起来）。
    private val dispersedNode = RenderNode("eta-voice-dispersed")
    private val dispersionShader = RuntimeShader(DISPERSION_AGSL)
    // 点层单独离屏：4x 超采样后再缩回，亚像素点不会在光栅化时被吃掉（原型 DOT_SS）。
    private val dotNode = RenderNode("eta-voice-dots")
    private var dotBlurAppliedFor: Boolean? = null
    private val capsulePath = Path()
    private val wavePath = Path()

    private val metaballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotLayerPaint = Paint()

    private val radialColors = IntArray(RadialStops + 1)
    private val radialStops = FloatArray(RadialStops + 1) { it / RadialStops.toFloat() }
    private val waveColors = IntArray(6)
    private val waveStops = floatArrayOf(0f, 0.10f, 0.30f, 0.55f, 0.80f, 1f)
    private val tintColors = IntArray(5)
    private val tintStops = floatArrayOf(0f, 0.27f, 0.52f, 0.77f, 1f)

    private var pushField = FloatArray(0)

    /**
     * 退场信号：[exiting] 为 true 时点燃冷却（一次性），光效随后在 [CooldownFrames] 内
     * 缓降到全灭；冷却期内若重新开始听写，传 false 撤销冷却、光效原地复燃。
     */
    fun setExiting(exiting: Boolean) {
        if (exiting) {
            if (cooldownFrames < 0f) cooldownFrames = 0f
        } else {
            cooldownFrames = -1f
            cooldownEase = 1f
        }
    }

    /**
     * 推进所有相位；[frames] 是以 60fps 为 1 的帧倍率，[level] 是本帧麦克风音量（0..1）。
     *
     * 与原型的音频惯性一致：音量先做 0.18/帧的指数平滑，再同时驱动速度与发光强度——
     * 说话越响，流光漂移越快、光团与点阵抬得越高。麦克风关闭时音量归零，平滑回落。
     * 会话刚开始还有一段启动预热：光效从全灭在约 600ms 内缓升到实际强度，暗示启动过程。
     * 退出会话则相反：冷却期内光效缓降到全灭，与 pill 槽的退场淡出同步。
     */
    fun advance(frames: Float, level: Float) {
        frameScale = frames
        smoothedLevel += (level.coerceIn(0f, 1f) - smoothedLevel) *
            (1f - LevelInertia.pow(frames))
        // 启动预热：先推进预热进度（easeOutCubic 缓出），再乘进发光强度——
        // 光团半径、光场与推力随它一起从全灭升到实际强度。
        warmupFrames = min(warmupFrames + frames, WarmupFrames)
        val warmupT = (warmupFrames / WarmupFrames).coerceIn(0f, 1f)
        warmupEase = 1f - (1f - warmupT).pow(3f)
        // 退场冷却：预热曲线的镜像（(1-t)³，先快后缓）——按下 ✓ / × 光效立刻开始收拢，
        // 残光随转场淡出慢慢收尾；时长与 pill 槽的退场淡出同长（约 350ms）。
        if (cooldownFrames >= 0f) {
            cooldownFrames = min(cooldownFrames + frames, CooldownFrames)
            val cooldownT = (cooldownFrames / CooldownFrames).coerceIn(0f, 1f)
            cooldownEase = (1f - cooldownT).pow(3f)
        }
        val activeSpeed = BaseSpeed + smoothedLevel * 2.6f
        glowIntensity = BaseGlowIntensity * (1f + smoothedLevel * 1.4f) * warmupEase * cooldownEase
        time += 0.019f * activeSpeed * frames
        for (blob in blobs) blob.phase += blob.speed * activeSpeed * frames
        for (dot in dots) dot.phase += dot.pulseSpeed * activeSpeed * frames
    }

    fun draw(scope: DrawScope, palette: GlowPalette, capsuleFill: Color, lightMode: Boolean) {
        val w = scope.size.width
        val h = scope.size.height
        if (w < 4f || h < 4f) return
        if (w != width || h != height) {
            width = w
            height = h
            rebuildGeometry()
        }
        computeBlobs()

        val canvas = scope.drawContext.canvas.nativeCanvas
        capsulePath.reset()
        capsulePath.addRoundRect(
            0f, 0f, w, h, capsuleRadius, capsuleRadius, Path.Direction.CW,
        )

        configureDotNode(lightMode)
        recordLightLayer(palette, lightMode)

        // 主画布：胶囊底 → 模糊+微微色散的受光层 → 点阵，全部裁在胶囊内。
        val saveCount = canvas.save()
        canvas.clipPath(capsulePath)
        canvas.drawColor(capsuleFill.toArgb())
        canvas.drawRenderNode(dispersedNode)
        drawDots(canvas, palette, lightMode)
        canvas.restoreToCount(saveCount)
    }

    private fun rebuildGeometry() {
        u = height / ReferenceCapsuleHeight
        capsuleRadius = height / 2f
        capsuleBottom = height
        // 画一层往下多出的缓冲，让模糊核在胶囊下缘也能采到真实像素，
        // 底边不会因为采样到透明而变淡（原型 bottomPad 的同一条理由）。
        bottomPad = BottomPadBase * u
        if (gridWidth != width || gridHeight != height) {
            gridWidth = width
            gridHeight = height
            buildTriangularGrid()
        }
        val blurRadius = 46f * u
        lightNode.setRenderEffect(
            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP),
        )
        // 微微色散层：与 lightNode 同尺寸（含 bottomPad 缓冲），内容 = lightNode
        // （子节点是实时引用，这里只需随尺寸变化重录一次，光层逐帧重录自动透传）。
        // 色散中心锚在平底中点，红通道离心外推、蓝通道向心内拉，量级 [DispersionAmount]。
        val nodeWidth = ceil(width).toInt().coerceAtLeast(1)
        val nodeHeight = ceil(height + bottomPad).toInt().coerceAtLeast(1)
        dispersedNode.setPosition(0, 0, nodeWidth, nodeHeight)
        dispersedNode.setRenderEffect(
            RenderEffect.createRuntimeShaderEffect(dispersionShader, "content"),
        )
        dispersionShader.setFloatUniform("uCenter", width / 2f, capsuleBottom)
        dispersionShader.setFloatUniform("uAmount", DispersionAmount * u)
        val dispersed = dispersedNode.beginRecording(nodeWidth, nodeHeight)
        dispersed.drawRenderNode(lightNode)
        dispersedNode.endRecording()
        // 点层 backing 尺寸 = 画布 × DOT_SS；缩回时亚像素点仍有足够像素能量。
        dotNode.setPosition(
            0,
            0,
            ceil(width * DotSupersample).toInt().coerceAtLeast(1),
            ceil(height * DotSupersample).toInt().coerceAtLeast(1),
        )
        dotBlurAppliedFor = null
    }

    /**
     * 点层的轻模糊：原型在 drawImage 缩回时挂 blur((light ? 0.3 : 0.45) * S)。
     * 这里模糊发生在 4x backing 上，半径乘 [DotSupersample] 缩回后即等效。
     */
    private fun configureDotNode(lightMode: Boolean) {
        if (dotBlurAppliedFor == lightMode) return
        dotBlurAppliedFor = lightMode
        val radius = (if (lightMode) 0.3f else 0.45f) * u * DotSupersample
        dotNode.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
        )
    }

    /**
     * 透视三角点阵：近处（底）行距/列距/点径都大，远处（顶）被压扁。
     * 起点从胶囊下缘再往下几行，向上抬升后底边不会露出空隙。
     */
    private fun buildTriangularGrid() {
        dots.clear()
        // 列距故意偏离原型（25）：等比缩到 ~56dp 胶囊后只有 ~4dp，整片糊成纹理、
        // 单点几乎不可见（2026-11-11 用户定稿调疏）。48u ≈ 底行列距 8dp、行距 ~7dp，
        // 浅深两色共用同一套网格；其余透视/交错结构照旧。
        val spacing = 48f * u
        val baseRowH = spacing * (sqrt(3f) / 2f)
        val perspective = 2.2f
        val centerX = width / 2f
        val extraBottomRows = 5

        var y = capsuleBottom - 4f * u + extraBottomRows * baseRowH
        var row = -extraBottomRows
        while (y > -40f * u) {
            val t = ((capsuleBottom - y) / height).coerceIn(0f, 1f)
            val depth = 1f / (1f + perspective * t)
            val rowGap = baseRowH * depth
            val xGap = spacing * depth
            val xOffset = if (abs(row) % 2 == 1) xGap * 0.5f else 0f
            val halfCols = ceil((width / 2f) / xGap).toInt() + 3
            for (c in -halfCols..halfCols) {
                dots.add(
                    GlowDot(
                        baseX = centerX + c * xGap + xOffset,
                        baseY = y,
                        depth = depth,
                        pulseSpeed = 0.015f + Random.nextFloat() * 0.02f,
                    ),
                )
            }
            y -= rowGap
            row++
        }
    }

    /** 光团当前位置：沿胶囊下轮廓漂移，端部按 baseX 的正弦权重渐隐到 0。 */
    private fun computeBlobs() {
        val activeGlow = glowIntensity
        for (i in blobs.indices) {
            val b = blobs[i]
            val idx = i.toFloat()
            val offset = sin(b.phase + sin(time * 0.5f + idx)) * (width * b.amp)
            val x = b.baseX * width + offset
            val pulse = 0.95f + sin(b.phase * 1.5f) * 0.15f
            val flatRadius = b.baseRadius * u * activeGlow * pulse
            val edge = sin(PI.toFloat() * b.baseX.coerceIn(0f, 1f))
            blobX[i] = x
            blobY[i] = lowerEdgeY(x)
            blobEdge[i] = edge
            blobRadius[i] = flatRadius * edge
            blobDotRadius[i] = flatRadius
        }
    }

    /** 胶囊下轮廓：中段平底，两端沿半圆向上弯曲。 */
    private fun lowerEdgeY(x: Float): Float {
        val r = capsuleRadius
        val centerY = r
        val leftCenter = r
        val rightCenter = width - r
        if (x < leftCenter) {
            val dx = leftCenter - x
            return centerY + sqrt(max(0f, r * r - dx * dx))
        }
        if (x > rightCenter) {
            val dx = x - rightCenter
            return centerY + sqrt(max(0f, r * r - dx * dx))
        }
        return capsuleBottom
    }

    /**
     * 把 metaball 与波浪主体录进离屏 RenderNode，再整体做一次高斯扩散。
     * 节点高度多出 bottomPad，模糊在胶囊下缘仍有内容可采。
     */
    private fun recordLightLayer(palette: GlowPalette, lightMode: Boolean) {
        val nodeWidth = ceil(width).toInt().coerceAtLeast(1)
        val nodeHeight = ceil(height + bottomPad).toInt().coerceAtLeast(1)
        lightNode.setPosition(0, 0, nodeWidth, nodeHeight)
        val canvas = lightNode.beginRecording(nodeWidth, nodeHeight)

        canvas.save()
        // 深色：相加累积，邻团熔成一体；浅色：普通叠加，底部保持主题色而不是发白。
        metaballPaint.xfermode = if (lightMode) null else AddXfermode
        // 启动预热升、退场冷却降：光团透明度随两者乘积缓升 / 缓降——开场不会突然出现
        // 小而烫的光点，退场也不会留下缩成一点仍在烧的光团。
        metaballPaint.alpha = (warmupEase * cooldownEase * 255f).roundToInt()
        for (i in blobs.indices) {
            if (blobRadius[i] < 0.05f) continue
            val fuseRadius = blobRadius[i] * 2.9f
            val color = palette.blobs[i % palette.blobs.size]
            // 浅色不点白热核心：核心就是光团本色；深色核心取调色板里的近白色。
            val core = if (lightMode) color else palette.core
            val stopCount = RadialStops
            for (s in 0..stopCount) {
                val t = s / stopCount.toFloat()
                // 平滑贝尔：边缘精确归零，避免出现硬边界圆环；端部权重压暗两侧。
                val alpha = exp(-3.0f * t * t) * (1f - t * t) * 0.62f * blobEdge[i]
                val mix = min(1f, t / 0.45f)
                val r = (core.red + (color.red - core.red) * mix) * 255f
                val g = (core.green + (color.green - core.green) * mix) * 255f
                val b = (core.blue + (color.blue - core.blue) * mix) * 255f
                radialColors[s] = android.graphics.Color.argb(
                    (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
                    r.roundToInt().coerceIn(0, 255),
                    g.roundToInt().coerceIn(0, 255),
                    b.roundToInt().coerceIn(0, 255),
                )
            }
            metaballPaint.shader = RadialGradient(
                blobX[i], blobY[i], fuseRadius,
                radialColors, radialStops, Shader.TileMode.CLAMP,
            )
            // 画整圆：下半圈落在 bottomPad 缓冲里，模糊后胶囊底边不会变淡。
            canvas.drawCircle(blobX[i], blobY[i], fuseRadius, metaballPaint)
        }
        canvas.restore()

        drawWave(canvas, palette, lightMode)

        if (lightMode) {
            // 横向色相水洗：SRC_ATOP 只染已有光的像素，亮度与透明度都不动。
            val save = canvas.save()
            tintPaint.xfermode = TintXfermode
            // 预热 / 冷却期色相水洗随光一起淡入、淡出。
            tintPaint.alpha = (warmupEase * cooldownEase * 255f).roundToInt()
            tintColors[0] = argb(palette.blobs[3], 0.26f)
            tintColors[1] = argb(palette.blobs[4], 0.06f)
            tintColors[2] = argb(palette.blobs[2], 0.26f)
            tintColors[3] = argb(palette.blobs[0], 0.06f)
            tintColors[4] = argb(palette.blobs[3], 0.26f)
            tintPaint.shader = LinearGradient(
                0f, 0f, width, 0f, tintColors, tintStops, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width, height, tintPaint)
            canvas.restoreToCount(save)
        }

        lightNode.endRecording()
    }

    /**
     * 液体基底：上缘是沿下轮廓起伏的波峰，下缘沿轮廓再向下超出 wavePad，
     * 让模糊始终有真实像素（超出的部分随后被胶囊裁掉）。
     */
    private fun drawWave(canvas: android.graphics.Canvas, palette: GlowPalette, lightMode: Boolean) {
        val wavePad = 70f * u
        val waveStep = max(1f, 6f * u)
        wavePath.reset()
        var x = 0f
        var first = true
        while (x <= width) {
            var yOffset = 0f
            for (i in blobs.indices) {
                val influence = blobRadius[i] * 2.6f
                val dist = abs(x - blobX[i])
                if (dist < influence) {
                    val factor = cos((dist / influence) * (PI.toFloat() / 2f))
                    yOffset += factor.pow(1.3f) * (blobRadius[i] * 0.95f)
                }
            }
            val baseY = lowerEdgeY(x)
            val crestY = baseY - yOffset * 0.55f -
                sin(x * (0.015f / u) + time * 1.2f) * 5f * u
            if (first) {
                wavePath.moveTo(x, crestY)
                first = false
            } else {
                wavePath.lineTo(x, crestY)
            }
            x += waveStep
        }
        x = width
        while (x >= 0f) {
            wavePath.lineTo(x, lowerEdgeY(x) + wavePad)
            x -= waveStep
        }
        wavePath.close()

        // 渐变锚在平底：中段最亮，沿两端弧线自然变暗、消失。
        // 两端色取调色板前两档（浅色：全彩度主体 + 向底色浅端退的一档；深色：primary 两档）。
        val baseColor = palette.blobs[0]
        val deeperColor = palette.blobs[1]
        waveColors[0] = if (lightMode) argb(baseColor, 0.95f) else argb(Color.White, 0.95f)
        waveColors[1] = argb(baseColor, 0.80f)
        waveColors[2] = argb(deeperColor, 0.55f)
        waveColors[3] = argb(deeperColor, 0.25f)
        waveColors[4] = argb(deeperColor, 0.06f)
        waveColors[5] = 0
        wavePaint.xfermode = null
        // 预热 / 冷却期波浪主体随光一起淡入、淡出（paint alpha 直接调制渐变透明度）。
        wavePaint.alpha = (warmupEase * cooldownEase * 255f).roundToInt()
        wavePaint.shader = LinearGradient(
            0f, capsuleBottom, 0f, 0f, waveColors, waveStops, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawDots(
        canvas: android.graphics.Canvas,
        palette: GlowPalette,
        lightMode: Boolean,
    ) {
        // 光强沿胶囊底每列采样一次：整列一起被抬升，抬升能到达更高的行。
        val pushStep = max(1f, 4f * u)
        val pushCount = ceil(width / pushStep).toInt() + 2
        if (pushField.size < pushCount) pushField = FloatArray(pushCount)
        for (i in 0 until pushCount) {
            val px = i * pushStep
            val strength = lightIntensity(px, capsuleBottom - 4f * u)
            val nx = (px / width).coerceIn(0f, 1f)
            val pushEdge = sin(PI.toFloat() * nx)
            pushField[i] = strength.pow(1.25f) * 30f * u * glowIntensity * pushEdge
        }

        // 弹性插值按帧率折算：原型每帧 0.12，这里保证 120Hz 与 60Hz 手感一致。
        val elastic = 1f - (1f - 0.12f).pow(frameScale)
        // 点径同样故意偏离原型（1.8）：缩放后底行直径 ~0.75dp（2px@3x）几乎看不见。
        // 4.0u → 底行半径 ~0.85dp、直径 ~1.7dp，配上 48u 列距单点清晰可辨
        // （2026-11-11 用户定稿；深度衰减 0.15+1.1·depth 照旧，顶部仍渐隐归零）。
        val dotBase = 4f * u
        val nodeWidth = ceil(width * DotSupersample).toInt().coerceAtLeast(1)
        val nodeHeight = ceil(height * DotSupersample).toInt().coerceAtLeast(1)
        val dotCanvas = dotNode.beginRecording(nodeWidth, nodeHeight)
        dotCanvas.save()
        dotCanvas.scale(DotSupersample, DotSupersample)
        for (dot in dots) {
            val shimmer = sin(dot.phase) * 0.2f + 0.8f
            val light = lightIntensity(dot.baseX, dot.baseY)
            val nx = (dot.baseX / width).coerceIn(0f, 1f)
            val pushEdge = sin(PI.toFloat() * nx)

            val t = (dot.baseX / pushStep).coerceIn(0f, pushCount - 1f)
            val i0 = floorInt(t)
            val i1 = min(pushCount - 1, i0 + 1)
            val f = (t - i0).coerceIn(0f, 1f)
            val columnPush = pushField[i0] * (1f - f) + pushField[i1] * f
            val vertFade = exp(-((capsuleBottom - dot.baseY) / (height * 0.95f)).pow(2f))
            val localPush = light.pow(1.25f) * 26f * u * glowIntensity * pushEdge

            // 弹性跟随目标 Y：整列抬升 + 局部抬升；x 严格不变，点阵不会交叉。
            val targetY = dot.baseY - (columnPush * vertFade + localPush)
            dot.y += (targetY - dot.y) * elastic

            // 整张网模型（2026-11-11 用户定稿，故意偏离草稿的 baseBrightness 60% 硬截断
            // ——勿对齐回去）：点常驻铺满整颗胶囊，透明度由五件事调制——顶部渐隐带
            // 与底部淡入带（均随光抬升）、光挖空（越亮越淡，草稿的通透感设计，保留）、
            // 左右边缘淡出与闪烁。
            val yProgress = ((capsuleBottom - dot.baseY) / height).coerceIn(0f, 1f)
            // 光抬升（草稿同款 yProgress - light·0.3）：光晕越亮，点的有效高度越低。
            // 顶部：亮柱上方的点被渐隐带压得更少，淡出位置随光往上推；
            // 底部：淡入起点随光往上移——光晕越强，点出现得越远（2026-11-11 用户定稿；
            // 草稿底部用原始 yProgress，系故意偏离）。一个系数喂两条带，调档两处同步。
            val litYProgress = max(0f, yProgress - light * 0.3f)
            // 顶部渐隐带：带底满亮 → 顶缘 0（带宽 [DotTopFadeBand]，1.4 幂曲线与
            // 草稿 baseBrightness 同形——顶部读得出长渐变，而不是几乎铺满）。
            val topFade = ((1f - litYProgress) / DotTopFadeBand).coerceIn(0f, 1f).pow(1.4f)
            // 底部淡入带：底缘 0 → 带顶 1。带宽从草稿的 1/3 拉长到 1/2——48u 疏网格下
            // 1/3 带内只剩 3~4 行，渐变读不出来（[DotBottomFadeBand]）。
            val bottomFadeFactor = min(1f, litYProgress / DotBottomFadeBand)
            val lightCutout = min(1f, light * MaskForce)
            val maskDepth = (dot.baseY / height).coerceIn(0f, 1f).pow(2f)
            val cutout = if (lightMode) lightCutout * 0.4f else lightCutout
            var opacity = topFade * bottomFadeFactor *
                (1f - cutout * 0.85f * maskDepth) * shimmer * pushEdge
            if (lightMode) opacity *= 1.6f
            // 启动预热升、退场冷却降：点阵亮度随光一起升 / 降。
            opacity *= warmupEase * cooldownEase
            opacity = opacity.coerceIn(0f, if (lightMode) 1f else 0.95f)

            // 胶囊外的点本来就会被裁掉，直接跳过省一次绘制。
            if (opacity <= 0.01f || dot.y > capsuleBottom + 1f) continue

            val radius = dotBase * (0.15f + 1.1f * dot.depth)
            // 浅深两色同一发白点阵色（palette.dots）：浅色不再按最近光团取鲜艳色——
            // 混黑档光团色与低 opacity 叠浅底正是中段点阵发灰的来源（2026-11-11 定稿）。
            val color = palette.dots
            dotPaint.color = android.graphics.Color.argb(
                (opacity * 255f).roundToInt().coerceIn(0, 255),
                (color.red * 255f).roundToInt().coerceIn(0, 255),
                (color.green * 255f).roundToInt().coerceIn(0, 255),
                (color.blue * 255f).roundToInt().coerceIn(0, 255),
            )
            dotCanvas.drawCircle(dot.baseX, dot.y, radius, dotPaint)
        }
        dotCanvas.restore()
        dotNode.endRecording()

        // 缩回主画布：深色 additive 'screen'（提亮受光层），浅色普通叠加。
        if (lightMode) {
            val save = canvas.save()
            canvas.scale(1f / DotSupersample, 1f / DotSupersample)
            canvas.drawRenderNode(dotNode)
            canvas.restoreToCount(save)
        } else {
            dotLayerPaint.xfermode = ScreenXfermode
            val layer = canvas.saveLayer(0f, 0f, width, height, dotLayerPaint)
            canvas.scale(1f / DotSupersample, 1f / DotSupersample)
            canvas.drawRenderNode(dotNode)
            canvas.restoreToCount(layer)
        }
    }

    /**
     * 点阵专用光场：用光团的平底半径、从平底线量高度，
     * 所以端部弧线只影响渲染出来的光，不会扭曲点阵的推挤与遮罩。
     */
    private fun lightIntensity(x: Float, y: Float): Float {
        var intensity = 0f
        for (i in blobs.indices) {
            val r = blobDotRadius[i]
            val dx = abs(x - blobX[i])
            val widthFactor = r * 1.5f
            val heightFactor = r * 1.4f
            if (dx < widthFactor) {
                val hFactor = cos((dx / widthFactor) * (PI.toFloat() / 2f))
                val vertDist = max(0f, capsuleBottom - y)
                if (vertDist < heightFactor) {
                    val vFactor = sin((1f - vertDist / heightFactor) * PI.toFloat() * 0.5f)
                    intensity += hFactor * hFactor * vFactor
                }
            }
        }
        return intensity
    }

    private fun argb(color: Color, alpha: Float): Int = android.graphics.Color.argb(
        (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
        (color.red * 255f).roundToInt().coerceIn(0, 255),
        (color.green * 255f).roundToInt().coerceIn(0, 255),
        (color.blue * 255f).roundToInt().coerceIn(0, 255),
    )

    private fun floorInt(value: Float): Int {
        val truncated = value.toInt()
        return if (truncated > value) truncated - 1 else truncated
    }

    private companion object {
        /** 参考预览图的胶囊高度：所有几何以此为基准纯等比缩放。 */
        const val ReferenceCapsuleHeight = 337f

        /** 胶囊下缘多留的缓冲高度（按缩放系数折算），供模糊核采样。 */
        const val BottomPadBase = 260f

        /** 光团贝尔衰减的采样段数：足够细，叠加后看不出同心圆环。 */
        const val RadialStops = 30

        /** 原型 state.speed / state.glowIntensity。 */
        const val BaseSpeed = 1.5f
        const val BaseGlowIntensity = 0.8f
        const val MaskForce = 1.2f

        /** 音量指数平滑的保留系数：原型 smoothed += (target - smoothed) * 0.18（每 60fps 帧）。 */
        const val LevelInertia = 0.82f

        /** 点层超采样倍率（原型 DOT_SS）。 */
        const val DotSupersample = 4f

        /** 微微色散：红/蓝通道沿径向错开的像素量（u 缩放，6u ≈ 2.6px @56dp 胶囊）。
         *  0 = 关闭。2026-11-11 用户定稿「微微色散的感觉可以来一点」；嫌彩边重就调小。 */
        const val DispersionAmount = 6f

        /** 点阵顶部渐隐带（占胶囊高度比例）：整张网常驻铺满，带内从满亮渐隐到顶缘 0，
         *  曲线套 baseBrightness 同款 1.4 幂（见绘制处）。带宽 0.65：渐变覆盖上半段，
         *  与旧模型「60% 高度以上无点」的长渐变观感对齐——首版 0.15 太窄，顶几乎铺满
         *  （2026-11-11 用户看截图后定稿）。故意偏离草稿的硬截断模型，勿对齐回去。 */
        const val DotTopFadeBand = 0.65f

        /** 点阵底部淡入带（占胶囊高度比例）：底缘 0 → 带顶 1。故意偏离草稿的 1/3——
         *  48u 疏网格下 1/3 带内只剩 3~4 行读不出渐变，2026-11-11 用户定稿拉长到 1/2。 */
        const val DotBottomFadeBand = 0.5f

        /** 启动预热的时长（60fps 帧数，约 600ms）：会话开始时光效从全灭升到实际强度。 */
        const val WarmupFrames = 36f

        /** 退场冷却的时长（60fps 帧数，约 350ms）：退出听写时光效缓降到全灭，与 pill 槽退场淡出同长。 */
        const val CooldownFrames = 21f

        val AddXfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val ScreenXfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val TintXfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)

        /**
         * 微微色散（AGSL，API 33+，minSdk 34 直用）：以平底中点为色散中心，
         * 红通道沿离心方向外推、蓝通道向心内拉、绿通道原位，alpha 取三采样最大——
         * 让彩边能越过光晕轮廓（预乘空间下边缘略暗属正常，量级只有几 px 不显）。
         * 入参：content = 光层内容（已过 46u 高斯），uCenter = 平底中点 px，
         * uAmount = 通道错位 px（[DispersionAmount]·u）。
         */
        private const val DISPERSION_AGSL = """
            uniform shader content;
            uniform float2 uCenter;
            uniform float uAmount;
            half4 main(float2 p) {
                half4 center = content.eval(p);
                float2 d = p - uCenter;
                float len = length(d) + 0.0001;
                float2 dir = d / len;
                half4 sr = content.eval(p + dir * uAmount);
                half4 sb = content.eval(p - dir * uAmount);
                return half4(sr.r, center.g, sb.b, max(center.a, max(sr.a, sb.a)));
            }
        """
    }
}
