package com.voiceupi.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Draws a transparent scan-window with animated corner brackets.
 * Turns green with a pulse when a QR is detected.
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
        shader = null
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // ── State ──────────────────────────────────────────────────────────────
    private var detected    = false
    private var scanLineY   = 0f      // animated position (0f..1f inside window)
    private var pulseAlpha  = 255

    // ── Animators ──────────────────────────────────────────────────────────
    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration        = 2000
        repeatCount     = ValueAnimator.INFINITE
        repeatMode      = ValueAnimator.REVERSE
        interpolator    = LinearInterpolator()
        addUpdateListener { scanLineY = it.animatedValue as Float; invalidate() }
    }
    private val pulseAnimator = ValueAnimator.ofInt(60, 255).apply {
        duration    = 600
        repeatCount = ValueAnimator.INFINITE
        repeatMode  = ValueAnimator.REVERSE
        addUpdateListener { pulseAlpha = it.animatedValue as Int; invalidate() }
    }

    init { scanAnimator.start() }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Call with yes=true when a QR is detected (turns corners green + pulses).
     * Call with yes=false to reset back to scanning state.
     */
    fun showDetected(yes: Boolean) {
        detected = yes
        if (yes) {
            scanAnimator.cancel()
            pulseAnimator.start()
            cornerPaint.color = 0xFF00E676.toInt()   // green
        } else {
            pulseAnimator.cancel()
            scanAnimator.start()
            cornerPaint.color = 0xFFFFFFFF.toInt()   // white
        }
        invalidate()
    }

    // ── Draw ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Scan window: 70% of screen width, centred, square
        val size   = w * 0.70f
        val left   = (w - size) / 2f
        val top    = (h - size) / 2f
        val right  = left + size
        val bottom = top + size
        val window = RectF(left, top, right, bottom)

        // Dim the four borders around the window
        canvas.drawRect(0f,   0f,     w, top,    dimPaint)
        canvas.drawRect(0f,   bottom, w, h,      dimPaint)
        canvas.drawRect(0f,   top,    left, bottom, dimPaint)
        canvas.drawRect(right, top,   w,  bottom, dimPaint)

        // Corner brackets
        val arm = size * 0.12f
        val r   = 12f
        cornerPaint.alpha = if (detected) pulseAlpha else 255
        drawCornerBrackets(canvas, window, arm, r)

        // Animated scan line (idle / non-detected state only)
        if (!detected) {
            val lineY = top + scanLineY * size
            val gradient = LinearGradient(
                left, lineY, right, lineY,
                intArrayOf(0x0000E676.toInt(), 0xFF00E676.toInt(), 0x0000E676.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            scanLinePaint.shader = gradient
            canvas.drawLine(left, lineY, right, lineY, scanLinePaint)
        }
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF, arm: Float, radius: Float) {
        val path = Path()

        // Top-left
        path.moveTo(r.left, r.top + arm)
        path.lineTo(r.left, r.top + radius)
        path.quadTo(r.left, r.top, r.left + radius, r.top)
        path.lineTo(r.left + arm, r.top)

        // Top-right
        path.moveTo(r.right - arm, r.top)
        path.lineTo(r.right - radius, r.top)
        path.quadTo(r.right, r.top, r.right, r.top + radius)
        path.lineTo(r.right, r.top + arm)

        // Bottom-right
        path.moveTo(r.right, r.bottom - arm)
        path.lineTo(r.right, r.bottom - radius)
        path.quadTo(r.right, r.bottom, r.right - radius, r.bottom)
        path.lineTo(r.right - arm, r.bottom)

        // Bottom-left
        path.moveTo(r.left + arm, r.bottom)
        path.lineTo(r.left + radius, r.bottom)
        path.quadTo(r.left, r.bottom, r.left, r.bottom - radius)
        path.lineTo(r.left, r.bottom - arm)

        canvas.drawPath(path, cornerPaint)
    }
}