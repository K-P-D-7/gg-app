package com.kishan.together

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Phone makers that block background apps unless "Autostart" is on.
private val AUTOSTART = listOf(
    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
)

/** Opens this phone's "Autostart" screen if it has one we know. */
fun openAutostart(ctx: android.content.Context): Boolean = AUTOSTART.any { c ->
    runCatching { ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBattery() }
    private val batteryReq = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { askAutostart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.configured) askPermissions()
        setContent {
            val theme by Repo.theme.collectAsState()
            MaterialTheme(colorScheme = THEMES[theme] ?: darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(onSetup = ::askPermissions) }
            }
        }
    }

    /** Asks for everything the app needs, one system prompt after another. */
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        else askBattery()
    }

    private fun askBattery() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return askAutostart()
        runCatching {
            batteryReq.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { askAutostart() }
    }

    private fun askAutostart() {
        if (Repo.autostartAsked) return
        Repo.autostartAsked = true
        if (openAutostart(this)) Toast.makeText(this, "Turn ON claude here, then come back", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (Repo.configured) PresenceService.start(this)
    }
}

@Composable
fun App(onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val rooms by Repo.rooms.collectAsState()
    var named by remember { mutableStateOf(Repo.name.isNotBlank()) }
    if (named && rooms.isNotEmpty()) Home()
    else Setup { named = true; PresenceService.start(ctx); Repo.start(); onSetup() }
}

@Composable
fun Setup(onDone: () -> Unit) {
    var name by remember { mutableStateOf(Repo.name) }
    var room by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("claude", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (!linking) {
            Text("Make a code on one phone and type the same code on the other.")
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(room, { room = it }, label = { Text("Shared code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { room = newCode() }) { Text("Make a new code") }
            Button(
                onClick = { Repo.saveName(name); Repo.join(room); onDone() },
                enabled = name.isNotBlank() && room.trim().length >= 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start") }
            TextButton(onClick = { linking = true }) { Text("Already use claude on another device? Link it") }
        } else {
            Text("On your other device open Set my status → Use on another device, and type the link code shown there.")
            OutlinedTextField(link, { link = it; error = "" }, label = { Text("Link code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    busy = true
                    Repo.link(link) { ok ->
                        busy = false
                        if (ok) onDone() else error = "No claude account found with that code (or no internet)"
                    }
                },
                enabled = !busy && link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Linking…" else "Link") }
            TextButton(onClick = { linking = false }) { Text("Back") }
        }
    }
}

fun newCode() = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {
    val ctx = LocalContext.current
    val rooms by Repo.rooms.collectAsState()
    val cur by Repo.current.collectAsState()
    val all by Repo.data.collectAsState()
    val room = all[cur] ?: Room()
    val users = room.users
    val nicks = room.nicks
    val pfps = room.pfps
    var now by remember { mutableLongStateOf(Repo.now()) }
    LaunchedEffect(Unit) { while (true) { now = Repo.now(); delay(1000) } }
    var editNick by remember { mutableStateOf<Person?>(null) }
    var profileFor by remember { mutableStateOf<Person?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<Person?>(null) }
    var showChecklist by remember { mutableStateOf(false) }

    var photoFor by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = photoFor
        if (uri != null && target != null) {
            val b64 = encodePhoto(ctx, uri)
            if (b64 != null) Repo.setPfp(target, b64)
            else Toast.makeText(ctx, "Couldn't read that photo", Toast.LENGTH_SHORT).show()
        }
    }

    val me = users[Repo.uid] ?: Person(Repo.uid, Repo.name)
    val partner = users.values.firstOrNull { it.uid != Repo.uid }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("claude", fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.weight(1f))
            Text(if (me.visible) "Sharing" else "Hidden", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(checked = me.visible, onCheckedChange = { Repo.setVisible(it) })
        }
        if (rooms.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.forEach { r ->
                    val p = all[r]?.users?.values?.firstOrNull { it.uid != Repo.uid }
                    FilterChip(
                        selected = r == cur,
                        onClick = { Repo.select(r) },
                        label = { Text(p?.let { Repo.display(r, it) } ?: "Waiting · $r") },
                    )
                }
            }
        }
        SetupCard { showChecklist = true }
        val connected by Repo.connected.collectAsState()
        if (!connected) Text("⚠ No internet on this phone — status may be out of date", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (partner == null) {
                Text("Waiting for your partner", fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("Code: $cur", color = Color.Gray)
            } else {
                PartnerStatus(partner, nicks, pfps[partner.uid], now) { profileFor = partner }
                if (partner.hist) TextButton(onClick = { historyFor = partner }) { Text("🕒 Activity history") }
            }
        }

        if (partner != null) {
            Button(onClick = {
                Repo.talk()
                Toast.makeText(ctx, "Sent! ${Repo.display(cur, partner)} will get a notification", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("💬 I want to talk")
            }
            Spacer(Modifier.height(8.dp))
        }
        val mine = listOf(me.tag, me.mood, me.body).filter { it.isNotBlank() }
        OutlinedButton(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (mine.isEmpty()) "Set my status (optional)" else "Me: " + mine.joinToString(" · "))
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            MySheet(cur, me, nicks, pfps[me.uid], room.options, onLeft = { showSheet = false }, onChecklist = { showSheet = false; showChecklist = true }) { profileFor = me }
        }
    }
    profileFor?.let { p ->
        ProfileDialog(
            title = if (p.uid == Repo.uid) "Me" else Repo.display(cur, p),
            hasPhoto = !pfps[p.uid].isNullOrBlank(),
            onDismiss = { profileFor = null },
            onPhoto = {
                profileFor = null
                photoFor = p.uid
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRemovePhoto = { Repo.setPfp(p.uid, null); profileFor = null },
            onNick = { profileFor = null; editNick = p },
        )
    }
    if (showChecklist) ChecklistDialog { showChecklist = false }
    historyFor?.let { p -> HistoryDialog(p, Repo.display(cur, p)) { historyFor = null } }
    editNick?.let { p ->
        NickDialog(nicks[p.uid] ?: "", onDismiss = { editNick = null }) { Repo.setNick(p.uid, it); editNick = null }
    }
}

/** Profile photo with a coloured status ring, or a plain coloured dot when there is no photo. */
@Composable
fun Avatar(photo: String?, ring: Color, size: Dp, onClick: () -> Unit) {
    val img = remember(photo) { photo?.takeIf { it.isNotBlank() }?.let { decodePhoto(it) } }
    if (img == null) {
        Box(Modifier.size(size).clip(CircleShape).background(ring).clickable(onClick = onClick))
        return
    }
    Box(contentAlignment = Alignment.BottomEnd) {
        Image(
            img, null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).border(5.dp, ring, CircleShape).padding(9.dp).clip(CircleShape).clickable(onClick = onClick),
        )
        Box(
            Modifier.padding(4.dp).size(size / 5).border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                .padding(2.dp).clip(CircleShape).background(ring)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartnerStatus(p: Person, nicks: Map<String, String>, photo: String?, now: Long, onProfile: () -> Unit) {
    val online = p.state(now).first
    val dot = when {
        !p.visible -> Color(0xFFFF9800)
        online -> Color(0xFF2ECC71)
        else -> Color(0xFF666666)
    }
    if (p.msg.isNotBlank()) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                p.msg, Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
    val extras = if (p.visible) listOf(p.mood, p.tag, p.body).filter { it.isNotBlank() } else emptyList()
    if (extras.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            extras.forEach { e ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(e, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    Avatar(photo, dot, if (photo.isNullOrBlank()) 96.dp else 150.dp, onProfile)
    Spacer(Modifier.height(20.dp))
    TextButton(onClick = onProfile) {
        Text(nicks[p.uid]?.takeIf { it.isNotBlank() } ?: p.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
    val (main, sub) = status(p, now)
    Text(main, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = dot)
    if (sub.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(sub, color = Color.Gray, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MySheet(
    cur: String, me: Person, nicks: Map<String, String>, myPhoto: String?, options: Map<String, List<Opt>>,
    onLeft: () -> Unit, onChecklist: () -> Unit, onProfile: () -> Unit,
) {
    var customFor by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var msg by remember(cur) { mutableStateOf(me.msg) }
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    val theme by Repo.theme.collectAsState()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All optional. Tap again to remove.", color = Color.Gray, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
            TextButton(onClick = { listOf("tag", "mood", "body").forEach { Repo.setField(it, "") } }) { Text("Clear all") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save my activity history", fontWeight = FontWeight.Bold)
                Text("Lets them see when you were online (last 30 days). Turning it off deletes it.", color = Color.Gray, fontSize = 13.sp)
            }
            Switch(checked = me.hist, onCheckedChange = { Repo.setHistory(it) })
        }

        Text("Display a message", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            msg, { if (it.length <= 200) msg = it },
            placeholder = { Text("Shows on their screen") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Button(onClick = { Repo.setField("msg", msg.trim()) }, enabled = msg.trim() != me.msg) { Text("Show") }
            Spacer(Modifier.width(8.dp))
            if (me.msg.isNotBlank()) TextButton(onClick = { msg = ""; Repo.setField("msg", "") }) { Text("Remove") }
        }

        listOf("tag" to "Doing", "mood" to "Mood", "body" to "Cycle").forEach { (sec, title) ->
            val current = when (sec) { "tag" -> me.tag; "mood" -> me.mood; else -> me.body }
            Section(
                title, options[sec].orEmpty(), current, editing,
                onDelete = { Repo.removeOption(sec, it.key) },
                onCustom = { customFor = sec },
            ) { Repo.setField(sec, it) }
        }

        Text("Theme", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            THEMES.forEach { (name, scheme) ->
                FilterChip(
                    selected = theme == name,
                    onClick = { Repo.setTheme(name) },
                    label = { Text(name) },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(scheme.primary)) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(myPhoto, MaterialTheme.colorScheme.primary, 44.dp, onProfile)
            TextButton(onClick = onProfile) {
                Text("My photo & nickname: ${nicks[me.uid]?.takeIf { it.isNotBlank() } ?: me.name}")
            }
        }

        Text("Connections", fontWeight = FontWeight.Bold)
        TextButton(onClick = { adding = true }) { Text("➕ Add connection") }
        TextButton(onClick = { showLink = true }) { Text("📱 Use on another device") }
        TextButton(onClick = onChecklist) { Text("✅ Setup checklist") }
        TextButton(onClick = { leaving = true }) { Text("🚪 Leave this connection", color = MaterialTheme.colorScheme.error) }
    }
    if (adding) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add connection") },
            text = {
                Column {
                    Text("Type the other person's code, or make a new one and share it.")
                    OutlinedTextField(code, { code = it }, label = { Text("Shared code") }, singleLine = true)
                    TextButton(onClick = { code = newCode() }) { Text("Make a new code") }
                }
            },
            confirmButton = {
                TextButton(onClick = { Repo.join(code); adding = false }, enabled = code.trim().length >= 6) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave this connection?") },
            text = { Text("Your status, photo, nickname and message are removed from code $cur on all your devices.") },
            confirmButton = {
                TextButton(onClick = { leaving = false; onLeft(); Repo.leave(cur) }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }
    if (showLink) {
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text("Use claude on another device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("On the other device tap \"Already use claude on another device? Link it\" and type:")
                    SelectionContainer { Text(Repo.uid, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    Text("Anyone with this code can act as you, so only type it on your own devices.", color = Color.Gray, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showLink = false }) { Text("Done") } },
        )
    }
    customFor?.let { sec ->
        NickDialog("", title = "New status", onDismiss = { customFor = null }) {
            val t = it.trim()
            if (t.isNotBlank()) { Repo.addOption(sec, t); Repo.setField(sec, t) }
            customFor = null
        }
    }
}

@Composable
fun ProfileDialog(title: String, hasPhoto: Boolean, onDismiss: () -> Unit, onPhoto: () -> Unit, onRemovePhoto: () -> Unit, onNick: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextButton(onClick = onPhoto) { Text("🖼 Change photo") }
                if (hasPhoto) TextButton(onClick = onRemovePhoto) { Text("🗑 Remove photo") }
                TextButton(onClick = onNick) { Text("✏️ Change nickname") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Section(
    title: String, options: List<Opt>, current: String, editing: Boolean,
    onDelete: (Opt) -> Unit, onCustom: () -> Unit, onPick: (String) -> Unit,
) {
    val x: (@Composable () -> Unit)? = if (editing) { { Text("✕") } } else null
    Text(title, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = !editing && current == o.text,
                onClick = { if (editing) onDelete(o) else onPick(if (current == o.text) "" else o.text) },
                label = { Text(o.text) },
                trailingIcon = x,
            )
        }
        if (!editing && current.isNotBlank() && options.none { it.text == current }) {
            FilterChip(selected = true, onClick = { onPick("") }, label = { Text(current) })
        }
        if (!editing) FilterChip(selected = false, onClick = onCustom, label = { Text("+ Custom") })
    }
}

@Composable
fun NickDialog(current: String, title: String = "Nickname (both of you see it)", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Big status word, plus the smaller line under it. */
fun status(p: Person, now: Long): Pair<String, String> {
    val (online, since, lastSeen) = p.state(now)
    return when {
        !p.visible -> "Hidden" to "Turned off their online status"
        online -> "Online" to "for ${dur(now - since)}"
        lastSeen == 0L -> "Offline" to ""
        else -> "Offline" to "Last online ${ago(now - lastSeen)} · was on for ${dur(lastSeen - since)}"
    }
}

/** Last 30 days of someone's online sessions, grouped by day. */
@Composable
fun HistoryDialog(p: Person, title: String, onDismiss: () -> Unit) {
    var list by remember(p.uid) { mutableStateOf<List<Session>?>(null) }
    LaunchedEffect(p.uid) { Repo.loadHistory(p.uid) { list = it } }
    val now = Repo.now()
    val day = remember { java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()) }
    val time = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title · last 30 days") },
        text = {
            val l = list
            when {
                l == null -> Text("Loading…")
                l.isEmpty() -> Text("Nothing saved yet.")
                else -> Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    l.groupBy { day.format(java.util.Date(it.start)) }.forEach { (d, items) ->
                        val total = items.sumOf { (if (it.end == 0L) now else it.end) - it.start }
                        Text("$d · ${dur(total)} total", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        items.forEach { sesh ->
                            val end = if (sesh.end == 0L) "now" else time.format(java.util.Date(sesh.end))
                            val len = dur((if (sesh.end == 0L) now else sesh.end) - sesh.start)
                            Text("${time.format(java.util.Date(sesh.start))} – $end   ($len)", fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun dur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }
}

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
