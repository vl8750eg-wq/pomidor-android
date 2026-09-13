package com.pomidor.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

val BG = Color(0xFF0F1115)
val CARD = Color(0xFF1A1D27)
val CARD2 = Color(0xFF242836)
val LINE = Color(0xFF2E3348)
val TEXT = Color.White
val MUTED = Color(0xFF9AA0B4)
val ACCENT = Color(0xFFFF5733)
val ACCENT_DARK = Color(0xFFD6492B)
val GREEN = Color(0xFF3DDC84)
val BLUE = Color(0xFF4AA8FF)
val GOLD = Color(0xFFFFBD2E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TimerState.flow.value == TimerUiState()) {
            TimerState.update(Prefs(this).initialState())
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = CARD)) {
                PomidorApp()
            }
        }
    }
}

@Composable
fun PomidorApp() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val state by TimerState.flow.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var notifOk by remember { mutableStateOf(true) }
    var overlayOk by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(false) }

    fun refreshPerms() {
        try {
            notifOk = (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        } catch (_: Exception) {
        }
        try {
            overlayOk = Settings.canDrawOverlays(ctx)
        } catch (_: Exception) {
        }
        try {
            batteryOk = (ctx.getSystemService(POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Exception) {
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPerms()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var focusMin by remember { mutableIntStateOf(prefs.focus) }
    var shortMin by remember { mutableIntStateOf(prefs.short) }
    var longMin by remember { mutableIntStateOf(prefs.long) }
    var perSet by remember { mutableIntStateOf(prefs.perSet) }
    var autoBreak by remember { mutableStateOf(prefs.autoBreak) }
    var autoFocus by remember { mutableStateOf(prefs.autoFocus) }
    var sound by remember { mutableStateOf(prefs.sound) }

    fun applySettings() = TimerService.cmd(ctx, TimerService.ACTION_APPLY_SETTINGS)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
    ) {
        val wide = maxWidth >= 840.dp
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state.totalDone)
            Spacer(Modifier.height(12.dp))
            if (!notifOk || !overlayOk || !batteryOk) {
                PermissionCard(notifOk, overlayOk, batteryOk)
                Spacer(Modifier.height(12.dp))
            }
            PhaseTabs(state.phase) { TimerService.cmd(ctx, TimerService.ACTION_SET_PHASE, it) }
            Spacer(Modifier.height(12.dp))
            if (wide) {
                Row(
                    modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TimerCard(
                        state,
                        modifier = Modifier.weight(1f),
                        onStartPause = { TimerService.cmd(ctx, TimerService.ACTION_START_PAUSE) },
                        onReset = { TimerService.cmd(ctx, TimerService.ACTION_RESET) },
                        onSkip = { TimerService.cmd(ctx, TimerService.ACTION_SKIP) },
                    )
                    SettingsCard(
                        modifier = Modifier.weight(1f),
                        focusMin, shortMin, longMin, perSet, autoBreak, autoFocus, sound,
                        onFocus = { focusMin = it; prefs.focus = it; applySettings() },
                        onShort = { shortMin = it; prefs.short = it; applySettings() },
                        onLong = { longMin = it; prefs.long = it; applySettings() },
                        onPer = { perSet = it; prefs.perSet = it },
                        onAutoBreak = { autoBreak = it; prefs.autoBreak = it },
                        onAutoFocus = { autoFocus = it; prefs.autoFocus = it },
                        onSound = { sound = it; prefs.sound = it },
                    )
                }
            } else {
                TimerCard(
                    state,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    onStartPause = { TimerService.cmd(ctx, TimerService.ACTION_START_PAUSE) },
                    onReset = { TimerService.cmd(ctx, TimerService.ACTION_RESET) },
                    onSkip = { TimerService.cmd(ctx, TimerService.ACTION_SKIP) },
                )
                Spacer(Modifier.height(12.dp))
                SettingsCard(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    focusMin, shortMin, longMin, perSet, autoBreak, autoFocus, sound,
                    onFocus = { focusMin = it; prefs.focus = it; applySettings() },
                    onShort = { shortMin = it; prefs.short = it; applySettings() },
                    onLong = { longMin = it; prefs.long = it; applySettings() },
                    onPer = { perSet = it; prefs.perSet = it },
                    onAutoBreak = { autoBreak = it; prefs.autoBreak = it },
                    onAutoFocus = { autoFocus = it; prefs.autoFocus = it },
                    onSound = { sound = it; prefs.sound = it },
                )
            }
            Spacer(Modifier.height(12.dp))
            StatsRow(state)
            Spacer(Modifier.height(4.dp))
            Text(
                "Пробел не нужен — просто жми старт",
                color = MUTED,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun PermissionCard(notifOk: Boolean, overlayOk: Boolean, batteryOk: Boolean) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CARD)
            .padding(16.dp),
    ) {
        Text("🔔 ДОСТУПЫ ДЛЯ БУДИЛЬНИКА", color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Без них заставка не выйдет поверх других приложений (особенно на Xiaomi/POCO).",
            color = MUTED,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (!notifOk) {
            PermRow("Уведомления", "ДАТЬ") {
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                } catch (_: Exception) {
                }
            }
        }
        if (!overlayOk) {
            PermRow("Поверх приложений", "ДАТЬ") {
                try {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        }
        if (!batteryOk) {
            PermRow("Без экономии батареи", "ДАТЬ") {
                try {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}

@Composable
fun PermRow(text: String, btn: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✖  $text", color = TEXT, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = TEXT),
        ) {
            Text(btn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Header(totalDone: Int) {
    Row(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("🍅  POMIDOR", color = TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("4 помидора → большой перерыв", color = MUTED, fontSize = 12.sp)
        }
        Text(
            "🔥 $totalDone",
            color = GOLD,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CARD)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun PhaseTabs(phase: Phase, onSelect: (Phase) -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .padding(4.dp),
    ) {
        Tab("Фокус", phase == Phase.FOCUS, Modifier.weight(1f)) { onSelect(Phase.FOCUS) }
        Tab("Отдых", phase == Phase.SHORT, Modifier.weight(1f)) { onSelect(Phase.SHORT) }
        Tab("Лонг", phase == Phase.LONG, Modifier.weight(1f)) { onSelect(Phase.LONG) }
    }
}

@Composable
fun Tab(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CARD2 else CARD)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (selected) TEXT else MUTED, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun phaseColor(phase: Phase): Color = when (phase) {
    Phase.FOCUS -> ACCENT
    Phase.SHORT -> GREEN
    Phase.LONG -> BLUE
}

@Composable
fun phaseTitle(phase: Phase): String = when (phase) {
    Phase.FOCUS -> "ФОКУС"
    Phase.SHORT -> "ОТДЫХ"
    Phase.LONG -> "ЛОНГ"
}

@Composable
fun TimerCard(state: TimerUiState, modifier: Modifier, onStartPause: () -> Unit, onReset: () -> Unit, onSkip: () -> Unit) {
    val color = phaseColor(state.phase)
    val per = 4
    val shown = if (state.phase == Phase.LONG) per else state.inSet % per
    val dots = (0 until per).joinToString(" ") { if (it < shown) "●" else "○" }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CARD)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("СЕТ ${minOf(state.inSet + 1, per)}/$per", color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(phaseTitle(state.phase), color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(250.dp)) {
            val progress = if (state.totalSec > 0) 1f - state.remainingSec.toFloat() / state.totalSec else 0f
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 13.dp.toPx()
                drawArc(LINE, 0f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawArc(color, 270f, -360f * progress.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%02d:%02d".format(state.remainingSec / 60, state.remainingSec % 60),
                    color = TEXT,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("осталось", color = MUTED, fontSize = 12.sp)
            }
        }
        Text(dots, color = color, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onStartPause,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White),
        ) {
            Text(if (state.running) "⏸   ПАУЗА" else "▶   СТАРТ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onReset,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CARD2, contentColor = MUTED),
            ) {
                Text("↺  Сброс", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onSkip,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CARD2, contentColor = MUTED),
            ) {
                Text("⏭  Пропустить", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingsCard(
    modifier: Modifier,
    focusMin: Int, shortMin: Int, longMin: Int, perSet: Int,
    autoBreak: Boolean, autoFocus: Boolean, sound: Boolean,
    onFocus: (Int) -> Unit, onShort: (Int) -> Unit, onLong: (Int) -> Unit, onPer: (Int) -> Unit,
    onAutoBreak: (Boolean) -> Unit, onAutoFocus: (Boolean) -> Unit, onSound: (Boolean) -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CARD)
            .padding(16.dp),
    ) {
        Text("НАСТРОЙКИ ВРЕМЕНИ", color = MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("Фокус, мин", focusMin, 1, 120, Modifier.weight(1f), onFocus)
            Stepper("Отдых, мин", shortMin, 1, 60, Modifier.weight(1f), onShort)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("Лонг, мин", longMin, 5, 90, Modifier.weight(1f), onLong)
            Stepper("До лонга", perSet, 2, 8, Modifier.weight(1f), onPer)
        }
        Spacer(Modifier.height(4.dp))
        ToggleRow("Автостарт отдыха", autoBreak, onAutoBreak)
        ToggleRow("Автостарт фокуса после отдыха", autoFocus, onAutoFocus)
        ToggleRow("Звук", sound, onSound)
    }
}

@Composable
fun Stepper(title: String, value: Int, lo: Int, hi: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CARD2)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = MUTED, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "−",
                color = TEXT,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(LINE)
                    .clickable { onChange(maxOf(lo, value - 1)) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("$value", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "+",
                color = TEXT,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(LINE)
                    .clickable { onChange(minOf(hi, value + 1)) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = TEXT, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = GREEN),
        )
    }
}

@Composable
fun StatsRow(state: TimerUiState) {
    Row(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Text("Сегодня: ${state.totalDone} 🍅", color = TEXT, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("Фокуса: ${state.focusMinutes} мин", color = MUTED, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}
