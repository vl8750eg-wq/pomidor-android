package com.pomidor.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Прямое системное окно поверх любых приложений (TYPE_APPLICATION_OVERLAY).
 * Дубль к full-screen intent: на MIUI/HyperOS запуск activity из фона часто
 * зарезан разрешением «Всплывающие окна», а overlay-окно при выданном
 * «Показ поверх других приложений» пробивает всегда.
 */
object OverlayService {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var media: MediaPlayer? = null
    private var vibe: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { hide() }

    fun show(ctx: Context, p: AlarmPayload) {
        try {
            hide()
            val app = ctx.applicationContext
            val density = app.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val veilInt = ((p.veil and 0xFFFFFF) or 0x4D000000).toInt()
            val fgInt = p.fg.toInt()
            val isGreen = p.fg == TimerService.FG_GREEN
            val btnText = if (isGreen) 0xFF0B1410.toInt() else 0xFFFFFFFF.toInt()

            val root = FrameLayout(app)
            root.setBackgroundColor(veilInt)

            val cardBg = GradientDrawable().apply {
                setColor(0xFF14161D.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), fgInt)
            }
            val card = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = cardBg
            }
            val strip = View(app).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
                setBackgroundColor(fgInt)
            }
            val emoji = TextView(app).apply {
                text = p.emoji.ifBlank { "🍅" }
                textSize = 64f
                gravity = Gravity.CENTER
            }
            val title = TextView(app).apply {
                text = p.title
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                setTextColor(fgInt)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }
            val sub = TextView(app).apply {
                text = p.sub
                textSize = 15f
                setTextColor(0xFFC6CBDD.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            val btn = Button(app).apply {
                text = "ПОНЯТНО, ПРОДОЛЖИТЬ"
                setBackgroundColor(fgInt)
                setTextColor(btnText)
                setOnClickListener { hide() }
            }
            val btnWrap = LinearLayout(app).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(20), dp(24), dp(26))
                addView(btn)
            }
            card.addView(strip)
            card.addView(emoji)
            card.addView(title)
            card.addView(sub)
            card.addView(btnWrap)

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
            root.addView(card, cardParams)

            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )
            w.addView(root, params)
            wm = w
            view = root

            if (Prefs(app).sound) {
                startSound(app)
                startVibro(app)
            }
            handler.postDelayed(hideTask, 120000)
        } catch (_: Exception) {
        }
    }

    fun hide() {
        try {
            handler.removeCallbacks(hideTask)
        } catch (_: Exception) {
        }
        try {
            val v = view
            view = null
            if (v != null) wm?.removeView(v)
        } catch (_: Exception) {
        }
        wm = null
        try {
            val m = media
            media = null
            if (m != null) {
                if (m.isPlaying) m.stop()
                m.release()
            }
        } catch (_: Exception) {
        }
        try {
            vibe?.cancel()
        } catch (_: Exception) {
        }
        vibe = null
    }

    private fun startSound(app: Context) {
        try {
            var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (uri == null) return
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            media = mp
        } catch (_: Exception) {
        }
    }

    private fun startVibro(app: Context) {
        try {
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (v == null || !v.hasVibrator()) return
            vibe = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } catch (_: Exception) {
        }
    }
}
