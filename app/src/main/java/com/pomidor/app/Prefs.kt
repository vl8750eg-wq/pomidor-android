package com.pomidor.app

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("pomidor", Context.MODE_PRIVATE)

    var focus: Int
        get() = sp.getInt("focus", 25)
        set(v) = sp.edit().putInt("focus", v.coerceIn(1, 120)).apply()
    var short: Int
        get() = sp.getInt("short", 5)
        set(v) = sp.edit().putInt("short", v.coerceIn(1, 60)).apply()
    var long: Int
        get() = sp.getInt("long", 15)
        set(v) = sp.edit().putInt("long", v.coerceIn(5, 90)).apply()
    var perSet: Int
        get() = sp.getInt("per_set", 4)
        set(v) = sp.edit().putInt("per_set", v.coerceIn(2, 8)).apply()
    var autoBreak: Boolean
        get() = sp.getBoolean("auto_break", true)
        set(v) = sp.edit().putBoolean("auto_break", v).apply()
    var autoFocus: Boolean
        get() = sp.getBoolean("auto_focus", true)
        set(v) = sp.edit().putBoolean("auto_focus", v).apply()
    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var inSet: Int
        get() = sp.getInt("in_set", 0)
        set(v) = sp.edit().putInt("in_set", maxOf(0, v)).apply()

    private fun today(): String = LocalDate.now().toString()

    private fun rollover() {
        if (sp.getString("stat_date", "") != today()) {
            sp.edit()
                .putInt("total_done", 0)
                .putInt("focus_minutes", 0)
                .putString("stat_date", today())
                .apply()
        }
    }

    var totalDone: Int
        get() {
            rollover()
            return sp.getInt("total_done", 0)
        }
        set(v) = sp.edit().putInt("total_done", maxOf(0, v)).apply()

    var focusMinutes: Int
        get() {
            rollover()
            return sp.getInt("focus_minutes", 0)
        }
        set(v) = sp.edit().putInt("focus_minutes", maxOf(0, v)).apply()

    fun saveStats(inSet: Int, totalDone: Int, focusMinutes: Int) {
        sp.edit()
            .putInt("in_set", maxOf(0, inSet))
            .putInt("total_done", maxOf(0, totalDone))
            .putInt("focus_minutes", maxOf(0, focusMinutes))
            .putString("stat_date", today())
            .apply()
    }

    fun saveRun(phase: Phase, remainingSec: Int, running: Boolean) {
        sp.edit()
            .putString("run_phase", phase.name)
            .putInt("run_remaining", maxOf(0, remainingSec))
            .putBoolean("run_running", running)
            .apply()
    }

    fun saveAlarmPayload(p: AlarmPayload) {
        sp.edit()
            .putBoolean("alarm_set", true)
            .putString("alarm_title", p.title)
            .putString("alarm_sub", p.sub)
            .putString("alarm_emoji", p.emoji)
            .putLong("alarm_veil", p.veil)
            .putLong("alarm_fg", p.fg)
            .putString("alarm_next_title", p.nextTitle)
            .putInt("alarm_next_min", p.nextMin)
            .putBoolean("alarm_auto", p.auto)
            .putString("alarm_next_phase", p.nextPhase)
            .putInt("alarm_next_dur", p.nextDurSec)
            .putInt("alarm_in_set", p.newInSet)
            .putInt("alarm_done", p.newDone)
            .putInt("alarm_mins", p.newMins)
            .putLong("alarm_gen", p.gen)
            .putLong("alarm_ts", p.tsMillis)
            .apply()
    }

    fun loadAlarmPayload(): AlarmPayload? {
        if (!sp.getBoolean("alarm_set", false)) return null
        return try {
            AlarmPayload(
                sp.getString("alarm_title", "") ?: "",
                sp.getString("alarm_sub", "") ?: "",
                sp.getString("alarm_emoji", "") ?: "",
                sp.getLong("alarm_veil", TimerService.VEIL_RED),
                sp.getLong("alarm_fg", TimerService.FG_RED),
                sp.getString("alarm_next_title", "") ?: "",
                sp.getInt("alarm_next_min", 0),
                sp.getBoolean("alarm_auto", false),
                sp.getString("alarm_next_phase", Phase.FOCUS.name) ?: Phase.FOCUS.name,
                sp.getInt("alarm_next_dur", 0),
                sp.getInt("alarm_in_set", 0),
                sp.getInt("alarm_done", 0),
                sp.getInt("alarm_mins", 0),
                sp.getLong("alarm_gen", 0L),
                sp.getLong("alarm_ts", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearAlarmPayload() {
        sp.edit().putBoolean("alarm_set", false).apply()
    }

    var consumedGen: Long
        get() = sp.getLong("alarm_consumed", 0L)
        set(v) = sp.edit().putLong("alarm_consumed", v).apply()

    fun clearConsumed() {
        sp.edit().putLong("alarm_consumed", 0L).apply()
    }

    fun loadRun(): Triple<Phase, Int, Boolean> {
        val phase = try {
            Phase.valueOf(sp.getString("run_phase", Phase.FOCUS.name) ?: Phase.FOCUS.name)
        } catch (_: Exception) {
            Phase.FOCUS
        }
        return Triple(phase, sp.getInt("run_remaining", 0), sp.getBoolean("run_running", false))
    }

    fun initialState(): TimerUiState {
        rollover()
        val dur = focus * 60
        return TimerUiState(
            phase = Phase.FOCUS,
            remainingSec = dur,
            totalSec = dur,
            running = false,
            inSet = inSet,
            totalDone = sp.getInt("total_done", 0),
            focusMinutes = sp.getInt("focus_minutes", 0),
        )
    }
}
