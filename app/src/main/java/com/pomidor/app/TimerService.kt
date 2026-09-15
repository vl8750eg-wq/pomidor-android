package com.pomidor.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Phase { FOCUS, SHORT, LONG }

data class TimerUiState(
    val phase: Phase = Phase.FOCUS,
    val remainingSec: Int = 25 * 60,
    val totalSec: Int = 25 * 60,
    val running: Boolean = false,
    val inSet: Int = 0,
    val totalDone: Int = 0,
    val focusMinutes: Int = 0,
)

object TimerState {
    private val _flow = MutableStateFlow(TimerUiState())
    val flow: StateFlow<TimerUiState> = _flow
    fun update(s: TimerUiState) {
        _flow.value = s
    }
}

data class FinishPreview(
    val next: Phase,
    val title: String,
    val sub: String,
    val emoji: String,
    val veil: Long,
    val fg: Long,
    val auto: Boolean,
    val nextMin: Int,
    val newInSet: Int,
    val newDone: Int,
    val newMins: Int,
)

data class AlarmPayload(
    val title: String,
    val sub: String,
    val emoji: String,
    val veil: Long,
    val fg: Long,
    val nextTitle: String,
    val nextMin: Int,
    val auto: Boolean,
    val nextPhase: String,
    val nextDurSec: Int,
    val newInSet: Int,
    val newDone: Int,
    val newMins: Int,
    val gen: Long,
    val tsMillis: Long,
)

fun ensureAlarmChannel(ctx: Context) {
    try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                TimerService.CH_ALARM,
                ctx.getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.channel_alarm_desc)
            },
        )
    } catch (_: Exception) {
    }
}

fun fireAlarmNow(ctx: Context, p: AlarmPayload) {
    showAlarmNotification(ctx, p)
    // Дубль поверх всего: overlay-окно пробивает MIUI/HyperOS,
    // где запуск activity из фона зарезан.
    try {
        if (Settings.canDrawOverlays(ctx)) {
            OverlayService.show(ctx, p)
        }
    } catch (_: Exception) {
    }
}

fun showAlarmNotification(ctx: Context, p: AlarmPayload) {
    try {
        ensureAlarmChannel(ctx)
        val i = Intent(ctx, AlarmActivity::class.java)
            .putExtra(AlarmActivity.EXTRA_TITLE, p.title)
            .putExtra(AlarmActivity.EXTRA_SUB, p.sub)
            .putExtra(AlarmActivity.EXTRA_EMOJI, p.emoji)
            .putExtra(AlarmActivity.EXTRA_VEIL, p.veil)
            .putExtra(AlarmActivity.EXTRA_FG, p.fg)
            .putExtra(AlarmActivity.EXTRA_NEXT_TITLE, p.nextTitle)
            .putExtra(AlarmActivity.EXTRA_NEXT_MIN, p.nextMin)
            .putExtra(AlarmActivity.EXTRA_AUTO, p.auto)
        val pi = PendingIntent.getActivity(
            ctx, TimerService.ALARM_NOTIF_ID, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, TimerService.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_tomato)
            .setContentTitle(p.title)
            .setContentText(p.sub)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(TimerService.ALARM_NOTIF_ID, n)
    } catch (_: Exception) {
    }
}

class TimerService : Service() {

    companion object {
        const val ACTION_START_PAUSE = "com.pomidor.app.START_PAUSE"
        const val ACTION_RESET = "com.pomidor.app.RESET"
        const val ACTION_SKIP = "com.pomidor.app.SKIP"
        const val ACTION_SET_PHASE = "com.pomidor.app.SET_PHASE"
        const val ACTION_APPLY_SETTINGS = "com.pomidor.app.APPLY_SETTINGS"
        const val ACTION_TEST_ALARM = "com.pomidor.app.TEST_ALARM"
        const val EXTRA_PHASE = "phase"

        const val CH_TIMER = "pomidor_timer"
        const val CH_ALARM = "pomidor_alarm"
        const val NOTIF_ID = 11
        const val ALARM_NOTIF_ID = 12
        const val ALARM_EXACT_REQ = 4711

        const val VEIL_RED = 0xFFD33F2E
        const val VEIL_GREEN = 0xFF1B7A43
        const val FG_RED = 0xFFFF5733
        const val FG_GREEN = 0xFF3DDC84

        @Volatile
        var alive = false

        @Volatile
        var lastTickMs = 0L

        fun cmd(ctx: Context, action: String, phase: Phase? = null) {
            val i = Intent(ctx, TimerService::class.java).setAction(action)
            if (phase != null) i.putExtra(EXTRA_PHASE, phase.name)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private var state = TimerUiState()
    private var job: Job? = null
    private var lastShownSec = -1
    private var currentGen = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
        val (runPhase, runRemaining, wasRunning) = prefs.loadRun()
        state = if (wasRunning && runRemaining > 0) {
            val dur = durationOf(runPhase)
            TimerUiState(runPhase, runRemaining, dur, running = false, prefs.inSet, prefs.totalDone, prefs.focusMinutes)
        } else {
            prefs.initialState()
        }
        TimerState.update(state)
        alive = true
        lastTickMs = SystemClock.elapsedRealtime()
        if (wasRunning && runRemaining > 0) {
            startCountdown()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, timerNotification())
        when (intent?.action) {
            ACTION_START_PAUSE -> if (state.running) pause() else start()
            ACTION_RESET -> reset()
            ACTION_SKIP -> advance(silent = true)
            ACTION_SET_PHASE -> {
                val ph = try {
                    Phase.valueOf(intent.getStringExtra(EXTRA_PHASE) ?: Phase.FOCUS.name)
                } catch (_: Exception) {
                    Phase.FOCUS
                }
                setPhase(ph)
            }
            ACTION_APPLY_SETTINGS -> applySettings()
            ACTION_TEST_ALARM -> testAlarm()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun durationOf(phase: Phase): Int = when (phase) {
        Phase.FOCUS -> prefs.focus * 60
        Phase.SHORT -> prefs.short * 60
        Phase.LONG -> prefs.long * 60
    }

    private fun durationMin(phase: Phase): Int = when (phase) {
        Phase.FOCUS -> prefs.focus
        Phase.SHORT -> prefs.short
        Phase.LONG -> prefs.long
    }

    private fun publish() {
        TimerState.update(state)
        prefs.saveRun(state.phase, state.remainingSec, state.running)
    }

    private fun start() {
        if (state.remainingSec <= 0) {
            val dur = durationOf(state.phase)
            state = state.copy(remainingSec = dur, totalSec = dur)
        }
        state = state.copy(running = true)
        publish()
        startCountdown()
    }

    private fun startCountdown() {
        job?.cancel()
        state = state.copy(running = true)
        publish()
        startForeground(NOTIF_ID, timerNotification())
        lastTickMs = SystemClock.elapsedRealtime()
        val deadlineElapsed = SystemClock.elapsedRealtime() + state.remainingSec * 1000L
        val deadlineWall = System.currentTimeMillis() + state.remainingSec * 1000L
        armExactAlarm(deadlineWall)
        lastShownSec = -1
        job = scope.launch {
            while (true) {
                lastTickMs = SystemClock.elapsedRealtime()
                val rem = ((deadlineElapsed - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
                if (rem != state.remainingSec) {
                    state = state.copy(remainingSec = rem)
                    publish()
                    if (rem != lastShownSec) {
                        lastShownSec = rem
                        NotificationManagerCompat.from(this@TimerService)
                            .notify(NOTIF_ID, timerNotification())
                    }
                }
                if (rem <= 0) {
                    advance(silent = false)
                    break
                }
                delay(250)
            }
        }
    }

    private fun pause() {
        job?.cancel()
        currentGen = 0L
        cancelExactAlarm()
        prefs.clearConsumed()
        state = state.copy(running = false)
        publish()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
    }

    private fun reset() {
        job?.cancel()
        currentGen = 0L
        cancelExactAlarm()
        prefs.clearConsumed()
        val dur = durationOf(state.phase)
        state = state.copy(remainingSec = dur, totalSec = dur, running = false)
        publish()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
    }

    private fun setPhase(ph: Phase) {
        job?.cancel()
        currentGen = 0L
        cancelExactAlarm()
        prefs.clearConsumed()
        val dur = durationOf(ph)
        state = state.copy(phase = ph, remainingSec = dur, totalSec = dur, running = false)
        publish()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
    }

    private fun applySettings() {
        if (!state.running) {
            val dur = durationOf(state.phase)
            state = state.copy(
                remainingSec = dur,
                totalSec = dur,
                inSet = prefs.inSet,
                totalDone = prefs.totalDone,
                focusMinutes = prefs.focusMinutes,
            )
            publish()
            NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
        }
    }

    private fun testAlarm() {
        // Мгновенная проверка боевым путём: FSI + overlay, без трогания статистики.
        fireAlarmNow(
            this,
            AlarmPayload(
                "🔔 ТЕСТ ЗАСТАВКИ", "Если видишь это поверх приложений — всё работает.",
                "🔔", VEIL_RED, FG_RED, phaseTitle(Phase.FOCUS), durationMin(Phase.FOCUS),
                false, Phase.FOCUS.name, durationOf(Phase.FOCUS),
                state.inSet, state.totalDone, state.focusMinutes, 0L, 0L,
            ),
        )
    }

    private fun previewFinish(): FinishPreview {        val per = prefs.perSet.coerceAtLeast(2)
        return if (state.phase == Phase.FOCUS) {
            val inSet = state.inSet + 1
            val isLong = inSet % per == 0
            val next = if (isLong) Phase.LONG else Phase.SHORT
            val auto = (next != Phase.FOCUS && prefs.autoBreak) || (next == Phase.FOCUS && prefs.autoFocus)
            FinishPreview(
                next,
                "ПОМИДОР ГОТОВ! 🍅",
                if (isLong) "Сделано $inSet в сете. Большой перерыв!" else "Сделано $inSet в сете. Маленький отдых.",
                "🍅", VEIL_RED, FG_RED, auto, durationMin(next),
                inSet, state.totalDone + 1, state.focusMinutes + prefs.focus,
            )
        } else {
            val inSet = if (state.phase == Phase.LONG) 0 else state.inSet
            val (title, sub, emoji) = if (state.phase == Phase.LONG) {
                Triple("ЛОНГ КОНЧИЛСЯ! 🚀", "Ты отдохнул. Новый сет из свежих помидоров.", "🚀")
            } else {
                Triple("ОТДЫХ КОНЧИЛСЯ! 💪", "Пора за работу. Погнали!", "💪")
            }
            FinishPreview(
                Phase.FOCUS, title, sub, emoji, VEIL_GREEN, FG_GREEN,
                prefs.autoFocus, durationMin(Phase.FOCUS),
                inSet, state.totalDone, state.focusMinutes,
            )
        }
    }

    private fun advance(silent: Boolean) {
        job?.cancel()
        val gen = currentGen
        currentGen = 0L
        cancelExactAlarm()
        if (gen != 0L && prefs.consumedGen == gen) {
            // Системный будильник уже сработал и показал заставку:
            // подхватываем следующую фазу без повторного overlay.
            prefs.clearConsumed()
            val preview = previewFinish()
            val dur = durationOf(preview.next)
            state = TimerUiState(preview.next, dur, dur, running = false, prefs.inSet, prefs.totalDone, prefs.focusMinutes)
            publish()
            if (preview.auto) {
                startCountdown()
            } else {
                NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
            }
            return
        }
        val preview = previewFinish()
        prefs.saveStats(preview.newInSet, preview.newDone, preview.newMins)
        val dur = durationOf(preview.next)
        state = TimerUiState(preview.next, dur, dur, running = false, preview.newInSet, preview.newDone, preview.newMins)
        publish()

        if (!silent) {
            showAlarmNotification(
                this,
                AlarmPayload(
                    preview.title, preview.sub, preview.emoji, preview.veil, preview.fg,
                    phaseTitle(preview.next), preview.nextMin, preview.auto,
                    preview.next.name, dur, preview.newInSet, preview.newDone, preview.newMins, 0L, 0L,
                ),
            )
        }
        if (preview.auto) {
            startCountdown()
        } else {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
        }
    }

    private fun armExactAlarm(deadlineWallMs: Long) {
        try {
            val preview = previewFinish()
            val dur = durationOf(preview.next)
            val payload = AlarmPayload(
                preview.title, preview.sub, preview.emoji, preview.veil, preview.fg,
                phaseTitle(preview.next), preview.nextMin, preview.auto,
                preview.next.name, dur, preview.newInSet, preview.newDone, preview.newMins,
                System.nanoTime(), deadlineWallMs,
            )
            currentGen = payload.gen
            prefs.saveAlarmPayload(payload)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val op = PendingIntent.getBroadcast(
                this, ALARM_EXACT_REQ, Intent(this, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val show = PendingIntent.getActivity(
                this, ALARM_EXACT_REQ + 1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // setAlarmClock: точный, пробивает Doze, не требует разрешений,
            // запуск activity из него не блокируется фоном.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(deadlineWallMs, show), op)
        } catch (_: Exception) {
        }
    }

    private fun cancelExactAlarm() {
        try {
            prefs.clearAlarmPayload()
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val op = PendingIntent.getBroadcast(
                this, ALARM_EXACT_REQ, Intent(this, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(op)
        } catch (_: Exception) {
        }
    }

    private fun phaseName(): String = when (state.phase) {
        Phase.FOCUS -> "Фокус"
        Phase.SHORT -> "Отдых"
        Phase.LONG -> "Лонг"
    }

    private fun fmt(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

    private fun serviceAction(action: String, label: String, icon: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(icon, label, pi)
    }

    private fun timerNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_tomato)
            .setContentTitle("🍅 ${phaseName()} — ${fmt(state.remainingSec)}")
            .setContentText(if (state.running) "Таймер идёт… не отвлекайся" else "На паузе")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        b.addAction(serviceAction(ACTION_START_PAUSE, if (state.running) "Пауза" else "Старт", android.R.drawable.ic_media_play))
        b.addAction(serviceAction(ACTION_SKIP, "Пропустить", android.R.drawable.ic_media_next))
        return b.build()
    }

    private fun phaseTitle(ph: Phase): String = when (ph) {
        Phase.FOCUS -> "Фокус"
        Phase.SHORT -> "Короткий отдых"
        Phase.LONG -> "Большой перерыв"
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, getString(R.string.channel_timer_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_timer_desc)
            },
        )
        ensureAlarmChannel(this)
    }
}

/**
 * Страховка на случай убитого сервиса / Doze: срабатывает по системному
 * будильнику и показывает заставку поверх любых приложений. Если сервис жив
 * и здоров (тик < 5 с назад) — ничего не делает, отработает сам сервис.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val appCtx = ctx.applicationContext
            val prefs = Prefs(appCtx)
            val p = prefs.loadAlarmPayload() ?: return
            val healthy = TimerService.alive &&
                SystemClock.elapsedRealtime() - TimerService.lastTickMs < 5000
            if (healthy) return
            prefs.clearAlarmPayload()
            prefs.saveStats(p.newInSet, p.newDone, p.newMins)
            try {
                prefs.saveRun(Phase.valueOf(p.nextPhase), p.nextDurSec, false)
            } catch (_: Exception) {
            }
            prefs.consumedGen = p.gen
            fireAlarmNow(appCtx, p)
        } catch (_: Exception) {
        }
    }
}
