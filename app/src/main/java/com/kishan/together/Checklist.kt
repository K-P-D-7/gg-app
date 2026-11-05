package com.kishan.together

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * One setup step. [check] reads the real setting when Android lets us; otherwise the person taps Done
 * and that is remembered on this phone.
 */
class Step(
    val key: String,
    val title: String,
    val how: String,
    val button: String,
    val open: (Context) -> Unit,
    val check: ((Context) -> Boolean)? = null,
)

/** Oppo, Realme and OnePlus all run ColorOS, which closes background apps unless told not to. */
val isColorOs = Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")

private fun appInfo(ctx: Context) =
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

fun steps(): List<Step> = buildList {
    add(Step(
        "notif", "Notifications",
        "So you hear when they want to talk.",
        "Turn on",
        open = { ctx ->
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        },
        check = { ctx ->
            Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        },
    ))
    add(Step(
        "battery", "Always run in background",
        "Tap Allow on the pop-up.",
        "Allow",
        open = { ctx ->
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        },
        check = { ctx -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) },
    ))
    if (Build.VERSION.SDK_INT >= 28) add(Step(
        "restricted", "Background use not restricted",
        "In App info → Battery, choose Unrestricted.",
        "Open App info",
        open = ::appInfo,
        check = { ctx -> !ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted },
    ))
    if (isColorOs) {
        add(Step(
            "autostart", "Allow auto-launch",
            "Turn claude ON in the list. If App info opens instead, look for \"Allow auto-launch\" (or Settings → Apps → Auto launch).",
            "Open",
            open = { ctx -> if (!openAutostart(ctx)) appInfo(ctx) },
        ))
        add(Step(
            "bgactivity", "Allow background activity",
            "In App info → Battery usage, turn on \"Allow background activity\" (and \"Allow foreground activity\" if it's there).",
            "Open App info",
            open = ::appInfo,
        ))
        add(Step(
            "lock", "Lock in recent apps",
            "Open recent apps, tap the icon or ⋮ above the claude card, and tap Lock. A padlock appears and Clear all won't close it.",
            "",
            open = {},
        ))
    }
}

fun Step.isDone(ctx: Context) = check?.invoke(ctx) ?: Repo.done(key)

fun stepsLeft(ctx: Context) = steps().count { !it.isDone(ctx) }

/** Card at the top of Home while any step is left. */
@Composable
fun SetupCard(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { left = stepsLeft(ctx); delay(2000) } }
    if (left == 0) return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Finish setup so your status stays accurate ($left ${if (left == 1) "step" else "steps"} left)",
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
fun ChecklistDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember { steps() }
    // Re-read the real settings while the dialog is open, so ticks appear when coming back from Settings.
    var done by remember { mutableStateOf(list.map { it.isDone(ctx) }) }
    LaunchedEffect(Unit) { while (true) { done = list.map { it.isDone(ctx) }; delay(1000) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Setup checklist") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                list.forEachIndexed { i, s ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (done[i]) "✅" else "⬜", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(s.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        Text(s.how, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(start = 30.dp))
                        Row(Modifier.padding(start = 22.dp)) {
                            if (s.button.isNotBlank() && !(s.check != null && done[i])) {
                                TextButton(onClick = { runCatching { s.open(ctx) } }) { Text(s.button) }
                            }
                            if (s.check == null) {
                                TextButton(onClick = {
                                    Repo.setDone(s.key, !done[i])
                                    done = list.map { it.isDone(ctx) }
                                }) { Text(if (done[i]) "Undo" else "Done") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
