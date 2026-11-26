package app.mindstop.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import app.mindstop.core.GardenPlant
import app.mindstop.core.GardenStage
import app.mindstop.core.GrowthWorldState

/**
 * Lightweight native 2D Canvas garden theme for Stage 4 (SPEC §12).
 * Renders a serene botanical meadow where confirmed rewards root as permanent trees and pending
 * rewards appear as glowing foreground sprouts awaiting confirmation. Purely visual: all growth
 * numbers come from [GrowthWorldState].
 */
class MindstopGardenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state: GrowthWorldState? = null
    private var pulsePhase = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trunkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foliagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val hillPath = Path()
    private val trailPath = Path()
    private val treePath = Path()
    private val clipRect = RectF()
    private var cachedSkyHeight = 0f
    private var cachedSkyNight: Boolean? = null

    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildSkyShader(h.toFloat(), isNight)
    }

    private fun rebuildSkyShader(h: Float, night: Boolean) {
        if (h <= 0f) return
        cachedSkyHeight = h
        cachedSkyNight = night
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.65f,
            if (night) 0xFF13261E.toInt() else 0xFFE2F5EB.toInt(),
            if (night) 0xFF1A3529.toInt() else 0xFFF4FBF7.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    fun render(newState: GrowthWorldState) {
        state = newState
        updatePulseAnimation(newState.pendingSaplings > 0)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulseAnimation((state?.pendingSaplings ?: 0) > 0)
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    private fun updatePulseAnimation(active: Boolean) {
        if (!active || !isAttachedToWindow) {
            stopPulse()
            return
        }
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val night = isNight
        val world = state
        val stage = world?.stage ?: GardenStage.CLEARING

        // 1. Sky gradient (cached by height & night mode to avoid draw allocations)
        if (cachedSkyHeight != h || cachedSkyNight != night) {
            rebuildSkyShader(h, night)
        }
        clipRect.set(0f, 0f, w, h)
        canvas.drawRect(clipRect, skyPaint)

        // 2. Soft sun / moon orb
        val orbX = w * 0.80f
        val orbY = h * 0.22f
        val orbR = h * 0.11f
        orbPaint.color = if (night) 0x26A8E8C7.toInt() else 0x33F5D37A.toInt()
        canvas.drawCircle(orbX, orbY, orbR * 1.65f, orbPaint)
        orbPaint.color = if (night) 0xFFBDEFD5.toInt() else 0xFFFCE59A.toInt()
        canvas.drawCircle(orbX, orbY, orbR, orbPaint)

        // 3. Back rolling hill
        hillPath.reset()
        hillPath.moveTo(0f, h * 0.56f)
        hillPath.quadTo(w * 0.28f, h * 0.42f, w * 0.58f, h * 0.53f)
        hillPath.quadTo(w * 0.82f, h * 0.61f, w, h * 0.49f)
        hillPath.lineTo(w, h)
        hillPath.lineTo(0f, h)
        hillPath.close()
        hillPaint.color = if (night) 0xFF1D3F30.toInt() else 0xFFBBE7D0.toInt()
        canvas.drawPath(hillPath, hillPaint)

        // 4. Mid meadow hill
        hillPath.reset()
        hillPath.moveTo(0f, h * 0.65f)
        hillPath.quadTo(w * 0.35f, h * 0.55f, w * 0.68f, h * 0.64f)
        hillPath.quadTo(w * 0.88f, h * 0.69f, w, h * 0.62f)
        hillPath.lineTo(w, h)
        hillPath.lineTo(0f, h)
        hillPath.close()
        hillPaint.color = if (night) 0xFF234D3A.toInt() else 0xFF9DDAB9.toInt()
        canvas.drawPath(hillPath, hillPaint)

        // 5. Foreground gentle lawn
        hillPath.reset()
        hillPath.moveTo(0f, h * 0.77f)
        hillPath.quadTo(w * 0.50f, h * 0.71f, w, h * 0.79f)
        hillPath.lineTo(w, h)
        hillPath.lineTo(0f, h)
        hillPath.close()
        hillPaint.color = if (night) 0xFF2A5B44.toInt() else 0xFF81CCA4.toInt()
        canvas.drawPath(hillPath, hillPaint)

        // 6. Winding path appears once garden reaches Forest Path or higher
        if (stage.level >= GardenStage.GROVE.level) {
            trailPath.reset()
            trailPath.moveTo(w * 0.46f, h)
            trailPath.quadTo(w * 0.52f, h * 0.85f, w * 0.44f, h * 0.74f)
            trailPath.quadTo(w * 0.40f, h * 0.66f, w * 0.48f, h * 0.61f)
            pathPaint.style = Paint.Style.STROKE
            pathPaint.strokeCap = Paint.Cap.ROUND
            pathPaint.strokeWidth = w * 0.042f
            pathPaint.color = if (night) 0x40A8D5BE.toInt() else 0x66F5FBF7.toInt()
            canvas.drawPath(trailPath, pathPaint)
        }

        // 7. Ground grass tufts for liveliness
        drawGrassTufts(canvas, w, h, night)

        // 8. Draw plants sorted back to front
        val plants = world?.plants.orEmpty()
        if (plants.isEmpty()) {
            // Peaceful starter sprout silhouette in the center clearing
            drawStarterSeedling(canvas, w * 0.50f, h * 0.82f, h * 0.09f, night)
        } else {
            for (plant in plants) {
                val px = w * plant.xRatio
                val py = h * (0.55f + plant.depthRatio * 0.36f)
                val baseSize = h * (0.13f + plant.depthRatio * 0.09f) * plant.scale
                if (plant.isPending) {
                    drawPendingSprout(canvas, px, py, baseSize, night)
                } else {
                    drawTree(canvas, px, py, baseSize, plant, night)
                }
            }
        }
    }

    private fun drawStarterSeedling(canvas: Canvas, x: Float, y: Float, size: Float, night: Boolean) {
        trunkPaint.color = if (night) 0xFF6EC498.toInt() else 0xFF2B7A56.toInt()
        trunkPaint.strokeWidth = size * 0.14f
        trunkPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, y, x, y - size * 0.85f, trunkPaint)
        foliagePaint.color = if (night) 0xFF7CDAA9.toInt() else 0xFF399368.toInt()
        canvas.drawOval(x - size * 0.55f, y - size * 1.15f, x, y - size * 0.65f, foliagePaint)
        canvas.drawOval(x, y - size * 1.25f, x + size * 0.55f, y - size * 0.72f, foliagePaint)
    }

    private fun drawPendingSprout(canvas: Canvas, x: Float, y: Float, size: Float, night: Boolean) {
        // Soft pulsing halo to signify "awaiting verification"
        val haloRadius = size * (0.52f + pulsePhase * 0.14f)
        haloPaint.strokeWidth = size * 0.06f
        haloPaint.color = if (night) 0x889AF0C5.toInt() else 0x882B7A56.toInt()
        canvas.drawCircle(x, y - size * 0.35f, haloRadius, haloPaint)

        // Soft glowing ground ring
        orbPaint.color = if (night) 0x337CDAA9.toInt() else 0x33FFFFFF.toInt()
        canvas.drawCircle(x, y - size * 0.35f, size * 0.45f, orbPaint)

        // Stem + two bright young leaves
        trunkPaint.color = if (night) 0xFFB8F6D7.toInt() else 0xFF1F6646.toInt()
        trunkPaint.strokeWidth = size * 0.11f
        trunkPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x, y, x, y - size * 0.68f, trunkPaint)

        foliagePaint.color = if (night) 0xCC9AF0C5.toInt() else 0xCC2B7A56.toInt()
        canvas.drawOval(x - size * 0.42f, y - size * 0.90f, x + size * 0.02f, y - size * 0.50f, foliagePaint)
        canvas.drawOval(x - size * 0.02f, y - size * 0.96f, x + size * 0.42f, y - size * 0.54f, foliagePaint)
    }

    private fun drawTree(canvas: Canvas, x: Float, y: Float, size: Float, plant: GardenPlant, night: Boolean) {
        // Subtle ground shadow
        orbPaint.color = if (night) 0x3308140E.toInt() else 0x2216422E.toInt()
        canvas.drawOval(x - size * 0.36f, y - size * 0.06f, x + size * 0.36f, y + size * 0.10f, orbPaint)

        // Trunk
        trunkPaint.color = if (night) 0xFF6B5343.toInt() else 0xFF7B5E48.toInt()
        val trunkW = size * 0.14f
        val trunkH = size * 0.46f
        canvas.drawRoundRect(
            x - trunkW / 2f,
            y - trunkH,
            x + trunkW / 2f,
            y + size * 0.03f,
            trunkW / 3f,
            trunkW / 3f,
            trunkPaint
        )

        when (plant.variant) {
            1 -> {
                // Layered pine / cypress
                foliagePaint.color = if (night) 0xFF3E8E67.toInt() else 0xFF246A4A.toInt()
                drawTriangle(canvas, x, y - size * 1.12f, size * 0.48f, size * 0.56f, foliagePaint)
                foliagePaint.color = if (night) 0xFF4CA579.toInt() else 0xFF2E7F5A.toInt()
                drawTriangle(canvas, x, y - size * 1.38f, size * 0.36f, size * 0.48f, foliagePaint)
            }
            2 -> {
                // Flowering botanical canopy
                foliagePaint.color = if (night) 0xFF48A074.toInt() else 0xFF31855E.toInt()
                canvas.drawCircle(x, y - size * 0.78f, size * 0.42f, foliagePaint)
                highlightPaint.color = if (night) 0xFF74CFA0.toInt() else 0xFF55AE81.toInt()
                canvas.drawCircle(x - size * 0.16f, y - size * 0.90f, size * 0.24f, highlightPaint)
                // Gentle blossom dots
                orbPaint.color = if (night) 0xFFE9F8EE.toInt() else 0xFFFFF6DC.toInt()
                canvas.drawCircle(x + size * 0.14f, y - size * 0.88f, size * 0.06f, orbPaint)
                canvas.drawCircle(x - size * 0.12f, y - size * 0.70f, size * 0.055f, orbPaint)
                canvas.drawCircle(x + size * 0.05f, y - size * 0.62f, size * 0.05f, orbPaint)
            }
            else -> {
                // Lush layered broad-leaf canopy
                foliagePaint.color = if (night) 0xFF3F946A.toInt() else 0xFF2B7A56.toInt()
                canvas.drawCircle(x - size * 0.18f, y - size * 0.74f, size * 0.32f, foliagePaint)
                canvas.drawCircle(x + size * 0.18f, y - size * 0.74f, size * 0.30f, foliagePaint)
                highlightPaint.color = if (night) 0xFF63BE90.toInt() else 0xFF47A175.toInt()
                canvas.drawCircle(x, y - size * 0.94f, size * 0.34f, highlightPaint)
            }
        }
    }

    private fun drawTriangle(canvas: Canvas, cx: Float, topY: Float, halfW: Float, height: Float, paint: Paint) {
        treePath.reset()
        treePath.moveTo(cx, topY)
        treePath.lineTo(cx - halfW, topY + height)
        treePath.lineTo(cx + halfW, topY + height)
        treePath.close()
        canvas.drawPath(treePath, paint)
    }

    private fun drawGrassTufts(canvas: Canvas, w: Float, h: Float, night: Boolean) {
        trunkPaint.color = if (night) 0x557CDAA9.toInt() else 0x552B7A56.toInt()
        trunkPaint.strokeWidth = (h * 0.009f).coerceAtLeast(2f)
        trunkPaint.strokeCap = Paint.Cap.ROUND
        val g = h * 0.032f
        for (i in GRASS_X.indices) {
            val gx = w * GRASS_X[i]
            val gy = h * GRASS_Y[i]
            canvas.drawLine(gx, gy, gx - g * 0.5f, gy - g, trunkPaint)
            canvas.drawLine(gx, gy, gx + g * 0.45f, gy - g * 0.9f, trunkPaint)
        }
    }

    companion object {
        private val GRASS_X = floatArrayOf(0.14f, 0.32f, 0.66f, 0.85f)
        private val GRASS_Y = floatArrayOf(0.84f, 0.90f, 0.87f, 0.83f)
    }
}
