package com.pomidor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
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

class TimerService : Service() {

    companion object {
        const val ACTION_START_PAUSE = "com.pomidor.app.START_PAUSE"
        const val ACTION_RESET = "com.pomidor.app.RESET"
        const val ACTION_SKIP = "com.pomidor.app.SKIP"
        const val ACTION_SET_PHASE = "com.pomidor.app.SET_PHASE"
        const val ACTION_APPLY_SETTINGS = "com.pomidor.app.APPLY_SETTINGS"
        const val EXTRA_PHASE = "phase"

        const val CH_TIMER = "pomidor_timer"
        const val CH_ALARM = "pomidor_alarm"
        const val NOTIF_ID = 11
        const val ALARM_NOTIF_ID = 12

        const val VEIL_RED = 0xFFD33F2E
        const val VEIL_GREEN = 0xFF1B7A43
        const val FG_RED = 0xFFFF5733
        const val FG_GREEN = 0xFF3DDC84

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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun durationOf(phase: Phase): Int = when (phase) {
        Phase.FOCUS -> prefs.focus * 60
        Phase.SHORT -> prefs.short * 60
        Phase.LONG -> prefs.long * 60
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
        val deadline = SystemClock.elapsedRealtime() + state.remainingSec * 1000L
        lastShownSec = -1
        job = scope.launch {
            while (true) {
                val rem = ((deadline - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
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
        state = state.copy(running = false)
        publish()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
    }

    private fun reset() {
        job?.cancel()
        val dur = durationOf(state.phase)
        state = state.copy(remainingSec = dur, totalSec = dur, running = false)
        publish()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
    }

    private fun setPhase(ph: Phase) {
        job?.cancel()
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

    private fun advance(silent: Boolean) {
        job?.cancel()
        val per = prefs.perSet.coerceAtLeast(2)
        val finishedFocus = state.phase == Phase.FOCUS
        var inSet = state.inSet
        var done = state.totalDone
        var mins = state.focusMinutes

        val next: Phase
        val title: String
        val sub: String
        val emoji: String
        val veil: Long
        val fg: Long

        if (finishedFocus) {
            done += 1
            inSet += 1
            mins += prefs.focus
            val isLong = inSet % per == 0
            next = if (isLong) Phase.LONG else Phase.SHORT
            title = "ПОМИДОР ГОТОВ! 🍅"
            sub = if (isLong) "Сделано $inSet в сете. Большой перерыв!" else "Сделано $inSet в сете. Маленький отдых."
            emoji = "🍅"
            veil = VEIL_RED
            fg = FG_RED
        } else {
            if (state.phase == Phase.LONG) inSet = 0
            next = Phase.FOCUS
            if (state.phase == Phase.LONG) {
                title = "ЛОНГ КОНЧИЛСЯ! 🚀"
                sub = "Ты отдохнул. Новый сет из свежих помидоров."
                emoji = "🚀"
            } else {
                title = "ОТДЫХ КОНЧИЛСЯ! 💪"
                sub = "Пора за работу. Погнали!"
                emoji = "💪"
            }
            veil = VEIL_GREEN
            fg = FG_GREEN
        }

        prefs.saveStats(inSet, done, mins)
        val auto = (next != Phase.FOCUS && prefs.autoBreak) || (next == Phase.FOCUS && prefs.autoFocus)
        val dur = when (next) {
            Phase.FOCUS -> prefs.focus * 60
            Phase.SHORT -> prefs.short * 60
            Phase.LONG -> prefs.long * 60
        }
        state = TimerUiState(next, dur, dur, running = false, inSet, done, mins)
        publish()

        if (!silent) {
            fireAlarm(title, sub, emoji, veil, fg, next, dur / 60, auto)
        }
        if (auto) {
            startCountdown()
        } else {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, timerNotification())
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

    private fun fireAlarm(
        title: String, sub: String, emoji: String,
        veil: Long, fg: Long, next: Phase, nextMin: Int, auto: Boolean,
    ) {
        val i = Intent(this, AlarmActivity::class.java)
            .putExtra(AlarmActivity.EXTRA_TITLE, title)
            .putExtra(AlarmActivity.EXTRA_SUB, sub)
            .putExtra(AlarmActivity.EXTRA_EMOJI, emoji)
            .putExtra(AlarmActivity.EXTRA_VEIL, veil)
            .putExtra(AlarmActivity.EXTRA_FG, fg)
            .putExtra(AlarmActivity.EXTRA_NEXT_TITLE, phaseTitle(next))
            .putExtra(AlarmActivity.EXTRA_NEXT_MIN, nextMin)
            .putExtra(AlarmActivity.EXTRA_AUTO, auto)
        val pi = PendingIntent.getActivity(
            this, ALARM_NOTIF_ID, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_tomato)
            .setContentTitle(title)
            .setContentText(sub)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(ALARM_NOTIF_ID, n)
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
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, getString(R.string.channel_alarm_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_alarm_desc)
            },
        )
    }
}
