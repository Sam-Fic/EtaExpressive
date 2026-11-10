package io.github.mangi.eta.ui.components

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
 * 口述语音条里的「流光胶囊」，逐项移植自原型 `index.html` 的 `createVoiceCapsule`。
 *
 * 三层结构（与原型同序）：
 * 1. metaball 层：9 颗骑在胶囊下轮廓上的光团，径向贝尔衰减叠加（深色 + 相加、浅色 + 普通叠加），
 *    邻团自然熔成一体；
 * 2. 受光层：metaball 与沿下轮廓起伏的波浪主体一起做一次高斯扩散（RenderEffect 模糊），
 *    浅色额外叠一层横向色相水洗（SRC_ATOP 只染已有光的像素）；
 * 3. 点阵层：透视三角点阵被光推起（整列抬升 + 局部抬升），近处大、远处小，
 *    深色 additive 提亮、浅色按最近光团取色。
 *
 * 所有几何按胶囊高度纯等比缩放：基准 337（参考预览图的胶囊高度），
 * `u = 高度px / 337`——不做任何下限，等价于把参考胶囊等比缩到本胶囊的高度。
 *
 * 配色跟随 M3 主题：光团由 `colorScheme.primary` 派生（浅色更饱和、深色偏薰衣草白），
 * 胶囊底色取表面角色，不写任何颜色字面量。
 */
@Composable
internal fun VoiceGlowCapsule(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    // 与 EtaStatus 等现有代码同一判据：由 surface 亮度判断当前是深色还是浅色。
    val lightMode = colorScheme.surface.luminance() < 0.5f
    val palette = remember(colorScheme.primary, lightMode) {
        glowPalette(colorScheme.primary, lightMode)
    }
    val capsuleFill = if (lightMode) {
        colorScheme.surfaceContainerHigh
    } else {
        colorScheme.surfaceContainerLowest
    }
    val engine = remember { VoiceGlowEngine() }
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
                engine.advance(frames)
                frame++
            }
        }
    }
    Canvas(modifier) {
        if (frame >= 0L) engine.draw(this, palette, capsuleFill, lightMode)
    }
}

/** 主题派生的光系：5 个光团色 + 点阵色 + 深色白热核心。 */
private class GlowPalette(val blobs: List<Color>, val dots: Color, val core: Color)

private fun glowPalette(primary: Color, lightMode: Boolean): GlowPalette = if (lightMode) {
    // 浅色：浅底胶囊上要读作「发光」，光团比 primary 更饱和、更深的几档混在一起。
    GlowPalette(
        blobs = listOf(
            primary,
            lerp(primary, Color.Black, 0.16f),
            lerp(primary, Color.White, 0.16f),
            lerp(primary, Color.White, 0.34f),
            lerp(primary, Color.Black, 0.08f),
        ),
        dots = lerp(primary, Color.White, 0.55f),
        // 浅色不点白热核心：核心就是光团本色，否则底部会像在发白光。
        core = primary,
    )
} else {
    // 深色：暗底上叠加白热核心后整体偏薰衣草白，点阵取浅紫。
    GlowPalette(
        blobs = listOf(
            primary,
            lerp(primary, Color.Black, 0.14f),
            lerp(primary, Color.Black, 0.34f),
            lerp(primary, Color.White, 0.10f),
            lerp(primary, Color.Black, 0.24f),
        ),
        dots = lerp(primary, Color.White, 0.36f),
        core = lerp(primary, Color.White, 0.92f),
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
    private val capsulePath = Path()
    private val wavePath = Path()

    private val metaballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val radialColors = IntArray(RadialStops + 1)
    private val radialStops = FloatArray(RadialStops + 1) { it / RadialStops.toFloat() }
    private val waveColors = IntArray(6)
    private val waveStops = floatArrayOf(0f, 0.10f, 0.30f, 0.55f, 0.80f, 1f)
    private val tintColors = IntArray(5)
    private val tintStops = floatArrayOf(0f, 0.27f, 0.52f, 0.77f, 1f)

    private var pushField = FloatArray(0)

    /** 推进所有相位；[frames] 是以 60fps 为 1 的帧倍率。 */
    fun advance(frames: Float) {
        // 原型 state.speed = 1.5；没有音量输入时速度恒定。
        val activeSpeed = 1.5f
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

        recordLightLayer(palette, lightMode)

        // 主画布：胶囊底 → 模糊受光层 → 点阵，全部裁在胶囊内。
        val saveCount = canvas.save()
        canvas.clipPath(capsulePath)
        canvas.drawColor(capsuleFill.toArgb())
        canvas.drawRenderNode(lightNode)
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
    }

    /**
     * 透视三角点阵：近处（底）行距/列距/点径都大，远处（顶）被压扁。
     * 起点从胶囊下缘再往下几行，向上抬升后底边不会露出空隙。
     */
    private fun buildTriangularGrid() {
        dots.clear()
        val spacing = 25f * u
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
        val activeGlow = GlowIntensity
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
        val primary = palette.blobs[0]
        val secondary = palette.blobs[1]
        waveColors[0] = if (lightMode) argb(primary, 0.95f) else argb(Color.White, 0.95f)
        waveColors[1] = argb(primary, 0.80f)
        waveColors[2] = argb(secondary, 0.55f)
        waveColors[3] = argb(secondary, 0.25f)
        waveColors[4] = argb(secondary, 0.06f)
        waveColors[5] = 0
        wavePaint.xfermode = null
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
            pushField[i] = strength.pow(1.25f) * 30f * u * GlowIntensity * pushEdge
        }

        dotPaint.xfermode = if (lightMode) null else ScreenXfermode
        val dotBase = 1.8f * u
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
            val localPush = light.pow(1.25f) * 26f * u * GlowIntensity * pushEdge

            // 弹性跟随目标 Y：整列抬升 + 局部抬升；x 严格不变，点阵不会交叉。
            val targetY = dot.baseY - (columnPush * vertFade + localPush)
            dot.y += (targetY - dot.y) * 0.12f

            val yProgress = ((capsuleBottom - dot.baseY) / height).coerceIn(0f, 1f)
            val adjustedYProgress = max(0f, yProgress - light * 0.3f)
            var baseBrightness = max(0f, 1f - adjustedYProgress / 0.6f)
            baseBrightness = baseBrightness.pow(1.4f)
            val bottomFadeFactor = min(1f, yProgress / 0.333f)
            val lightCutout = min(1f, light * MaskForce)
            val maskDepth = ((dot.baseY) / height).coerceIn(0f, 1f).pow(2f)
            val cutout = if (lightMode) lightCutout * 0.4f else lightCutout
            var opacity = baseBrightness * bottomFadeFactor *
                (1f - cutout * 0.85f * maskDepth) * shimmer * pushEdge
            if (lightMode) opacity *= 1.6f
            opacity = opacity.coerceIn(0f, if (lightMode) 1f else 0.95f)

            // 胶囊外的点本来就会被裁掉，直接跳过省一次绘制。
            if (opacity <= 0.01f || dot.y > capsuleBottom + 1f) continue

            val radius = dotBase * (0.15f + 1.1f * dot.depth)
            val color = if (lightMode) nearestBlobColor(dot.baseX, palette) else palette.dots
            dotPaint.color = android.graphics.Color.argb(
                (opacity * 255f).roundToInt().coerceIn(0, 255),
                (color.red * 255f).roundToInt().coerceIn(0, 255),
                (color.green * 255f).roundToInt().coerceIn(0, 255),
                (color.blue * 255f).roundToInt().coerceIn(0, 255),
            )
            canvas.drawCircle(dot.baseX, dot.y, radius, dotPaint)
        }
    }

    /** 浅色点阵取最近光团的鲜艳色：点阵跟着流光的多色一起变。 */
    private fun nearestBlobColor(x: Float, palette: GlowPalette): Color {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in blobs.indices) {
            val distance = abs(x - blobX[i])
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return palette.blobs[best % palette.blobs.size]
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

        const val GlowIntensity = 0.8f
        const val MaskForce = 1.2f

        val AddXfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val ScreenXfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val TintXfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
}
