package com.pomidor.app

import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUB = "sub"
        const val EXTRA_EMOJI = "emoji"
        const val EXTRA_VEIL = "veil"
        const val EXTRA_FG = "fg"
        const val EXTRA_NEXT_TITLE = "next_title"
        const val EXTRA_NEXT_MIN = "next_min"
        const val EXTRA_AUTO = "auto"
    }

    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Если уже висит overlay-окно — гасим, чтобы не двоилось.
        OverlayService.hide()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val sub = intent.getStringExtra(EXTRA_SUB) ?: ""
        val emoji = intent.getStringExtra(EXTRA_EMOJI) ?: "🍅"
        val veil = Color(intent.getLongExtra(EXTRA_VEIL, TimerService.VEIL_RED))
        val fg = Color(intent.getLongExtra(EXTRA_FG, TimerService.FG_RED))
        val nextTitle = intent.getStringExtra(EXTRA_NEXT_TITLE) ?: ""
        val nextMin = intent.getIntExtra(EXTRA_NEXT_MIN, 0)
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)

        if (Prefs(this).sound) {
            playAlarm()
            vibrate()
        }

        setContent {
            BackHandler { finish() }
            AlarmScreen(title, sub, emoji, veil, fg, nextTitle, nextMin, auto) { finish() }
        }
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }

    private fun playAlarm() {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, Settings.System.DEFAULT_ALARM_ALERT_URI)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { it.release() }
            player = mp
        } catch (_: Exception) {
        }
    }

    private fun stopSound() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun vibrate() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
fun AlarmScreen(
    title: String,
    sub: String,
    emoji: String,
    veil: Color,
    fg: Color,
    nextTitle: String,
    nextMin: Int,
    auto: Boolean,
    onClose: () -> Unit,
) {
    val cardBg = Color(0xFF14161D)
    val btnText = if (fg == Color(TimerService.FG_GREEN)) Color(0xFF0B1410) else Color.White
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(veil.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .border(2.dp, fg, RoundedCornerShape(16.dp))
                .background(cardBg, RoundedCornerShape(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(fg),
            )
            Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(emoji, fontSize = 64.sp)
                Spacer(Modifier.height(10.dp))
                Text(title, color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(sub, color = Color(0xFFC6CBDD), fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                val stateT = if (auto) {
                    "Далее: $nextTitle $nextMin мин — уже запущен ▶"
                } else {
                    "Далее: $nextTitle $nextMin мин — на паузе"
                }
                Text(
                    stateT,
                    color = if (auto) Color(0xFFFFE082) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = btnText),
                ) {
                    Text("ПОНЯТНО, ПРОДОЛЖИТЬ  →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
