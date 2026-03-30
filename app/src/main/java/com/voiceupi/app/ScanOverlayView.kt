package com.voiceupi.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Draws a transparent scan-window with animated corner brackets.
 *
 * Spatial guidance additions:
 *   • [updateQrIndicator] — pulsing orange dot at the QR's actual position.
 *   • Radar line (Requirement 5) — a line drawn from the screen centre to
 *     the indicator dot, with a small arrowhead, so sighted helpers can see
 *     at a glance where the QR is relative to the target window.
 *   • Mini corner ticks around the approximate QR boundary.
 *   • Everything turns green and the radar line disappears on lock-in.
 */
class ScanOverlayView(context: Context) : View(context) {

    // ── Paints ─────────────────────────────────────────────────────────────

    private val dimPaint = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val scanLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // Orange dot while guiding; green on lock-in
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // Halo ring around the dot
    private val haloPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Mini corner ticks around the detected QR boundary
    private val miniCornerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF9800.toInt()
        alpha = 110
    }

    /**
     * Radar line (Requirement 5):
     * Dashed line from screen centre to the QR dot, with a small arrowhead.
     * Drawn at 55 % opacity so it never distracts from the camera preview.
     */
    private val radarLinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFF9800.toInt()
        alpha = 140
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
        strokeCap = Paint.Cap.ROUND
    }

    // Solid arrowhead at the end of the radar line
    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0xFFFF9800.toInt()
        alpha = 180
    }

    // ── State ──────────────────────────────────────────────────────────────

    private var detected = false
    private var scanLineY = 0f
    private var pulseAlpha = 255
    private var dotAlpha = 200

    private var qrViewX: Float? = null
    private var qrViewY: Float? = null
    private var qrHalfSize: Float = 0f

    // ── Animators ──────────────────────────────────────────────────────────

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { scanLineY = it.animatedValue as Float; invalidate() }
    }

    private val lockPulseAnimator = ValueAnimator.ofInt(60, 255).apply {
        duration = 600
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulseAlpha = it.animatedValue as Int; invalidate() }
    }

    private val dotPulseAnimator = ValueAnimator.ofInt(130, 220).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { dotAlpha = it.animatedValue as Int; invalidate() }
    }

    init { scanAnimator.start() }

    // ══════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Call every analysis frame.
     * @param bbox    Barcode bounding box in camera-frame coordinates; null = clear.
     * @param frameW  Camera frame width in pixels.
     * @param frameH  Camera frame height in pixels.
     */
    fun updateQrIndicator(bbox: android.graphics.Rect?, frameW: Float, frameH: Float) {
        if (bbox == null || frameW == 0f || frameH == 0f) {
            if (qrViewX != null) {
                qrViewX = null; qrViewY = null; qrHalfSize = 0f
                if (!detected) dotPulseAnimator.cancel()
                invalidate()
            }
            return
        }
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return

        val sx = vw / frameW; val sy = vh / frameH
        qrViewX    = ((bbox.left + bbox.right)  / 2f) * sx
        qrViewY    = ((bbox.top  + bbox.bottom) / 2f) * sy
        qrHalfSize = (maxOf(bbox.width(), bbox.height()) / 2f) *
                ((sx + sy) / 2f).coerceAtLeast(20f)

        if (!detected && !dotPulseAnimator.isRunning) dotPulseAnimator.start()
        invalidate()
    }

    /** Call true on lock-in, false on reset. */
    fun showDetected(yes: Boolean) {
        detected = yes
        if (yes) {
            scanAnimator.cancel(); dotPulseAnimator.cancel()
            dotAlpha = 255
            lockPulseAnimator.start()
            cornerPaint.color = 0xFF00E676.toInt()
        } else {
            lockPulseAnimator.cancel()
            qrViewX = null; qrViewY = null; qrHalfSize = 0f
            scanAnimator.start()
            cornerPaint.color = 0xFFFFFFFF.toInt()
        }
        invalidate()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Draw
    // ══════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        // Scan window — 70 % of screen width, square, centred
        val size   = w * 0.70f
        val left   = (w - size) / 2f
        val top    = (h - size) / 2f
        val right  = left + size
        val bottom = top + size
        val window = RectF(left, top, right, bottom)

        // Dim overlay
        canvas.drawRect(0f,    0f,    w,     top,    dimPaint)
        canvas.drawRect(0f,    bottom, w,    h,      dimPaint)
        canvas.drawRect(0f,    top,   left,  bottom, dimPaint)
        canvas.drawRect(right, top,   w,     bottom, dimPaint)

        // Corner brackets
        cornerPaint.alpha = if (detected) pulseAlpha else 255
        drawCornerBrackets(canvas, window, size * 0.12f, 12f)

        // Scan line (idle only)
        if (!detected) {
            val lineY = top + scanLineY * size
            scanLinePaint.shader = LinearGradient(
                left, lineY, right, lineY,
                intArrayOf(0x0000E676.toInt(), 0xFF00E676.toInt(), 0x0000E676.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawLine(left, lineY, right, lineY, scanLinePaint)
        }

        // QR indicator (dot + radar line + mini boundary)
        val ix = qrViewX; val iy = qrViewY
        if (ix != null && iy != null) {
            // ── Requirement 5: Radar line from screen centre to QR dot ──────
            if (!detected) {
                val cx = w / 2f; val cy = h / 2f
                drawRadarLine(canvas, cx, cy, ix, iy)
            }
            drawQrIndicator(canvas, ix, iy, qrHalfSize)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Draws a dashed line from (fromX, fromY) to (toX, toY) with a small filled
     * arrowhead pointing toward (toX, toY).  The line stops SHORT of the dot by
     * [DOT_RADIUS] pixels so it doesn't visually overlap the indicator.
     */
    private fun drawRadarLine(
        canvas: Canvas,
        fromX: Float, fromY: Float,
        toX: Float, toY: Float
    ) {
        val DOT_RADIUS   = 28f   // leave a gap near the dot
        val ARROW_LENGTH = 22f
        val ARROW_HALF   = 9f

        val dx = toX - fromX
        val dy = toY - fromY
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < DOT_RADIUS + 10f) return   // too close — don't draw

        // Unit vector toward the dot
        val ux = dx / dist; val uy = dy / dist

        // Line end point — stop before the dot
        val lineEndX = toX - ux * DOT_RADIUS
        val lineEndY = toY - uy * DOT_RADIUS

        // Draw dashed line
        radarLinePaint.alpha = (dotAlpha * 0.7f).toInt()
        canvas.drawLine(fromX, fromY, lineEndX, lineEndY, radarLinePaint)

        // Arrowhead: equilateral triangle at the line end, pointing toward QR
        val arrowTipX = lineEndX
        val arrowTipY = lineEndY
        val arrowBaseX = arrowTipX - ux * ARROW_LENGTH
        val arrowBaseY = arrowTipY - uy * ARROW_LENGTH

        // Perpendicular vector for the base width
        val px = -uy; val py = ux

        val path = Path().apply {
            moveTo(arrowTipX, arrowTipY)
            lineTo(arrowBaseX + px * ARROW_HALF, arrowBaseY + py * ARROW_HALF)
            lineTo(arrowBaseX - px * ARROW_HALF, arrowBaseY - py * ARROW_HALF)
            close()
        }
        arrowPaint.alpha = (dotAlpha * 0.85f).toInt()
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawQrIndicator(canvas: Canvas, cx: Float, cy: Float, halfSize: Float) {
        val dotR  = 14f
        val haloR = dotR + 10f
        val colour = if (detected) 0xFF00E676.toInt() else 0xFFFF9800.toInt()
        val alpha  = if (detected) pulseAlpha else dotAlpha

        dotPaint.color  = colour; dotPaint.alpha  = alpha
        haloPaint.color = colour; haloPaint.alpha = (alpha * 0.55f).toInt()

        canvas.drawCircle(cx, cy, dotR,  dotPaint)
        canvas.drawCircle(cx, cy, haloR, haloPaint)

        if (!detected && halfSize > dotR + 20f) {
            drawMiniBoundary(canvas, cx, cy, halfSize * 0.75f)
        }
    }

    private fun drawMiniBoundary(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arm = r * 0.28f
        val path = Path().apply {
            moveTo(cx - r, cy - r + arm); lineTo(cx - r, cy - r); lineTo(cx - r + arm, cy - r)
            moveTo(cx + r - arm, cy - r); lineTo(cx + r, cy - r); lineTo(cx + r, cy - r + arm)
            moveTo(cx + r, cy + r - arm); lineTo(cx + r, cy + r); lineTo(cx + r - arm, cy + r)
            moveTo(cx - r + arm, cy + r); lineTo(cx - r, cy + r); lineTo(cx - r, cy + r - arm)
        }
        canvas.drawPath(path, miniCornerPaint)
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF, arm: Float, radius: Float) {
        val path = Path().apply {
            moveTo(r.left, r.top + arm);       lineTo(r.left, r.top + radius)
            quadTo(r.left, r.top, r.left + radius, r.top); lineTo(r.left + arm, r.top)

            moveTo(r.right - arm, r.top);      lineTo(r.right - radius, r.top)
            quadTo(r.right, r.top, r.right, r.top + radius); lineTo(r.right, r.top + arm)

            moveTo(r.right, r.bottom - arm);   lineTo(r.right, r.bottom - radius)
            quadTo(r.right, r.bottom, r.right - radius, r.bottom); lineTo(r.right - arm, r.bottom)

            moveTo(r.left + arm, r.bottom);    lineTo(r.left + radius, r.bottom)
            quadTo(r.left, r.bottom, r.left, r.bottom - radius); lineTo(r.left, r.bottom - arm)
        }
        canvas.drawPath(path, cornerPaint)
    }
}