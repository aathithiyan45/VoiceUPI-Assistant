package com.voiceupi.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * SonarBeeper — "Geiger counter" proximity feedback for the QR scanner.
 *
 * Works entirely on a dedicated background [HandlerThread] so it never
 * touches the UI thread or the camera analysis thread.
 *
 * Usage:
 *   1. Call [start] once (e.g. in Activity.onCreate or when the camera is ready).
 *   2. Call [updateDistance] every analysis frame with a normalised distance
 *      value (0.0 = perfectly centred, 1.0 = at the very edge of the frame).
 *   3. Call [stopBeeping] when the QR locks in or leaves the frame.
 *   4. Call [release] in Activity.onDestroy.
 *
 * Beep interval mapping (Requirement 2):
 *   dist ≥ 0.60  →  900 ms  (far away  — slow pulse)
 *   dist ≥ 0.40  →  600 ms
 *   dist ≥ 0.25  →  350 ms
 *   dist ≥ 0.12  →  180 ms
 *   dist < 0.12  →  continuous rapid ticking at 80 ms (almost locked)
 *
 * No beeps fire when [stopBeeping] has been called (e.g. during lock-in or
 * when no QR is visible).
 */
class SonarBeeper {

    companion object {
        private const val TAG = "SonarBeeper"
        private const val VOLUME = 40       // ToneGenerator volume 0–100
        private const val BEEP_DURATION_MS = 40  // each beep is 40 ms long
    }

    // ── Background thread ──────────────────────────────────────────────────
    private val thread = HandlerThread("SonarBeeperThread").also { it.start() }
    private val bgHandler = Handler(thread.looper)

    // ── ToneGenerator (created on the background thread) ──────────────────
    private var toneGen: ToneGenerator? = null

    // ── State (read/written only from bgHandler) ───────────────────────────
    @Volatile private var active = false          // false = don't schedule next beep
    @Volatile private var intervalMs: Long = 900L // current beep interval

    // ── Public API ─────────────────────────────────────────────────────────

    /** Call once after construction to initialise the ToneGenerator. */
    fun start() {
        bgHandler.post {
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
                Log.d(TAG, "ToneGenerator ready")
            } catch (e: Exception) {
                Log.e(TAG, "ToneGenerator init failed: ${e.message}")
            }
        }
    }

    /**
     * Update the beep interval based on how far the QR centre is from
     * the screen centre.
     *
     * @param normDist  Normalised distance in [0.0, 1.0].
     *                  0.0 = perfectly centred, 1.0 = at frame corner.
     *                  Compute as: sqrt((dx/0.5)² + (dy/0.5)²) / sqrt(2),
     *                  where dx/dy are distances from 0.5 in each axis.
     */
    fun updateDistance(normDist: Float) {
        val newInterval: Long = when {
            normDist < 0.12f -> 80L
            normDist < 0.25f -> 180L
            normDist < 0.40f -> 350L
            normDist < 0.60f -> 600L
            else             -> 900L
        }

        val wasActive = active
        active = true

        if (newInterval != intervalMs || !wasActive) {
            intervalMs = newInterval
            // Cancel any pending beep and reschedule immediately so the
            // interval change is felt without waiting out the old interval.
            bgHandler.removeCallbacksAndMessages(null)
            bgHandler.post(beepRunnable)
        }
    }

    /** Silence all beeping immediately (call on lock-in or QR lost). */
    fun stopBeeping() {
        active = false
        bgHandler.removeCallbacksAndMessages(null)
    }

    /** Release resources. Call in Activity.onDestroy. */
    fun release() {
        stopBeeping()
        bgHandler.post {
            toneGen?.release()
            toneGen = null
        }
        // Give the background thread a moment to process the release, then quit.
        bgHandler.postDelayed({ thread.quitSafely() }, 200)
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private val beepRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Beep failed: ${e.message}")
            }
            if (active) {
                bgHandler.postDelayed(this, intervalMs)
            }
        }
    }
}
