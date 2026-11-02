package com.kishan.together

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class Person(
    val uid: String,
    val name: String = "",
    val visible: Boolean = true,
    val online: Boolean = false,
    val since: Long = 0,
    val lastSeen: Long = 0,
    val tag: String = "",
    val mood: String = "",
    val body: String = "",
    val talk: Long = 0,
    val msg: String = "",
)

data class Opt(val key: String, val text: String)

/** Everything shown for one connection (shared code). */
data class Room(
    val users: Map<String, Person> = emptyMap(),
    val nicks: Map<String, String> = emptyMap(),
    val pfps: Map<String, String> = emptyMap(),
    val options: Map<String, List<Opt>> = emptyMap(),
)

/** Starting options for each status section; after the first run both phones share and edit them in Firebase. */
val DEFAULT_OPTIONS = linkedMapOf(
    "tag" to listOf("📚 Studying", "📱 Shorting", "📸 Reeling", "🎮 Gaming", "💼 Working", "🍽 Eating", "😴 Sleeping", "💬 Chatting"),
    "mood" to listOf("😊 Happy", "😡 Mad", "😢 Sad", "😞 Depressed", "😤 Hate you"),
    "body" to listOf("🩸 Period", "😣 PMSing"),
)

/**
 * Firebase layout:
 *   people/<uid>          name, rooms/<code>=true   (shared by all of one person's devices)
 *   rooms/<code>/users/<uid>   name, visible, tag, mood, body, msg, talk, dev/<deviceId>/{on, since, last}
 *   rooms/<code>/nicks, pfp, options
 */
object Repo {
    private lateinit var prefs: SharedPreferences
    private val db get() = FirebaseDatabase.getInstance()

    val rooms = MutableStateFlow<List<String>>(emptyList())
    val current = MutableStateFlow("")
    val data = MutableStateFlow<Map<String, Room>>(emptyMap())
    val theme by lazy { MutableStateFlow(prefs.getString("theme", "Lavender")!!) }
    var onEvent: ((String, String) -> Unit)? = null

    private var offset = 0L
    private var startedFor: String? = null
    private var localOnline = false
    private val global = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private val attached = mutableMapOf<String, List<Pair<DatabaseReference, ValueEventListener>>>()
    private val loaded = mutableSetOf<String>()
    private val pushed = mutableSetOf<String>()
    private val prevPartner = mutableMapOf<String, Person?>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("together", Context.MODE_PRIVATE)
        val e = prefs.edit()
        if (!prefs.contains("uid")) e.putString("uid", UUID.randomUUID().toString().replace("-", "").take(12))
        if (!prefs.contains("dev")) e.putString("dev", UUID.randomUUID().toString().replace("-", "").take(8))
        // Older versions kept one code in "room"; it becomes the first connection.
        prefs.getString("room", null)?.let { old ->
            if (old.isNotBlank()) e.putStringSet("rooms", setOf(old)).putString("current", old)
            if (old.isNotBlank() && !prefs.getBoolean("visible", true)) e.putStringSet("hidden", setOf(old))
            e.remove("room").remove("visible")
        }
        e.apply()
        rooms.value = prefs.getStringSet("rooms", emptySet())!!.sorted()
        current.value = prefs.getString("current", "")!!.takeIf { it in rooms.value } ?: rooms.value.firstOrNull().orEmpty()
    }

    val uid get() = prefs.getString("uid", "")!!
    private val devId get() = prefs.getString("dev", "")!!
    val name get() = prefs.getString("name", "")!!
    val configured get() = name.isNotBlank() && rooms.value.isNotEmpty()
    var autostartAsked
        get() = prefs.getBoolean("autostartAsked", false)
        set(v) = prefs.edit().putBoolean("autostartAsked", v).apply()

    fun now() = System.currentTimeMillis() + offset

    fun saveName(n: String) = prefs.edit().putString("name", n.trim()).apply()

    fun display(room: String, p: Person) = data.value[room]?.nicks?.get(p.uid)?.takeIf { it.isNotBlank() } ?: p.name

    fun partner(room: String) = data.value[room]?.users?.values?.firstOrNull { it.uid != uid }

    private fun person() = db.getReference("people/$uid")
    private fun me(room: String) = db.getReference("rooms/$room/users/$uid")
    private fun dev(room: String) = me(room).child("dev/$devId")
    private val cur get() = current.value

    /** Hidden state is cached locally so a phone never reports "online" in a room you're hidden in, even before data loads. */
    private fun hidden() = prefs.getStringSet("hidden", emptySet())!!
    private fun visibleIn(room: String) = room !in hidden()

    fun start() {
        if (uid.isBlank() || name.isBlank() || startedFor == uid) return
        if (startedFor != null) stopAll()
        startedFor = uid

        if (!prefs.getBoolean("migrated", false)) {
            person().updateChildren(mapOf<String, Any>("name" to name) + rooms.value.associate { "rooms/$it" to true })
            prefs.edit().putBoolean("migrated", true).apply()
        }

        watch(db.getReference(".info/serverTimeOffset"), global) { offset = it.getValue(Long::class.java) ?: 0 }
        watch(db.getReference(".info/connected"), global) {
            if (it.getValue(Boolean::class.java) == true) rooms.value.forEach(::pushPresence)
        }
        watch(person().child("rooms"), global) { s -> setRooms(s.children.map { it.key!! }) }
        rooms.value.forEach(::attach)
    }

    private fun stopAll() {
        global.forEach { (r, l) -> r.removeEventListener(l) }
        global.clear()
        attached.keys.toList().forEach(::detach)
    }

    private fun watch(ref: DatabaseReference, into: MutableList<Pair<DatabaseReference, ValueEventListener>>, block: (DataSnapshot) -> Unit) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = block(snapshot)
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        into += ref to l
    }

    private fun setRooms(list: List<String>) {
        val new = list.distinct().sorted()
        (attached.keys - new.toSet()).forEach(::detach)
        new.forEach(::attach)
        rooms.value = new
        prefs.edit().putStringSet("rooms", new.toSet()).apply()
        if (cur !in new) select(new.firstOrNull().orEmpty())
    }

    fun select(room: String) {
        current.value = room
        prefs.edit().putString("current", room).apply()
    }

    private fun attach(room: String) {
        if (room in attached) return
        val ls = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
        attached[room] = ls
        val root = db.getReference("rooms/$room")
        fun edit(f: (Room) -> Room) = data.update { it + (room to f(it[room] ?: Room())) }

        watch(root.child("nicks"), ls) { s ->
            edit { r -> r.copy(nicks = s.children.associate { it.key!! to (it.getValue(String::class.java) ?: "") }) }
        }
        watch(root.child("pfp"), ls) { s ->
            edit { r -> r.copy(pfps = s.children.associate { it.key!! to (it.getValue(String::class.java) ?: "") }) }
        }
        watch(root.child("options"), ls) { s ->
            if (!s.hasChild("seeded")) {
                // Fixed keys, so both phones seeding at once write the same thing.
                val seed = mutableMapOf<String, Any>("seeded" to true)
                DEFAULT_OPTIONS.forEach { (sec, list) -> list.forEachIndexed { i, o -> seed["$sec/$i"] = o } }
                s.ref.updateChildren(seed)
                return@watch
            }
            edit { r ->
                r.copy(options = DEFAULT_OPTIONS.keys.associateWith { sec ->
                    s.child(sec).children.mapNotNull { c -> c.getValue(String::class.java)?.let { Opt(c.key!!, it) } }
                })
            }
        }
        watch(root.child("users"), ls) { s ->
            // A user with no name is a leftover from a device that already left; skip it.
            val map = s.children.filter { it.hasChild("name") }.associate { it.key!! to parse(it) }
            edit { r -> r.copy(users = map) }
            map[uid]?.let { syncHidden(room, !it.visible) }
            val p = map.values.firstOrNull { it.uid != uid }
            if (room in loaded && p != null) {
                val old = prevPartner[room]
                if (p.talk != 0L && p.talk != (old?.talk ?: 0L)) onEvent?.invoke("talk", "${display(room, p)} wants to talk 💬")
                if (old?.visible == true && !p.visible) onEvent?.invoke("off", "${display(room, p)} turned off their online status")
            }
            prevPartner[room] = p
            loaded += room
        }
        pushPresence(room)
    }

    private fun detach(room: String) {
        attached.remove(room)?.forEach { (r, l) -> r.removeEventListener(l) }
        dev(room).onDisconnect().cancel()
        data.update { it - room }
        loaded -= room
        pushed -= room
        prevPartner -= room
    }

    private fun syncHidden(room: String, h: Boolean) {
        val set = hidden()
        if (h != (room in set)) prefs.edit().putStringSet("hidden", if (h) set + room else set - room).apply()
    }

    private fun parse(s: DataSnapshot): Person {
        val devs = s.child("dev").children.toList()
        fun Long?.n() = this ?: 0L
        val base = Person(
            uid = s.key!!,
            name = s.child("name").getValue(String::class.java) ?: "",
            visible = s.child("visible").getValue(Boolean::class.java) ?: true,
            tag = s.child("tag").getValue(String::class.java) ?: "",
            mood = s.child("mood").getValue(String::class.java) ?: "",
            body = s.child("body").getValue(String::class.java) ?: "",
            talk = s.child("talk").getValue(Long::class.java) ?: 0,
            msg = s.child("msg").getValue(String::class.java) ?: "",
        )
        if (devs.isEmpty()) {
            // Not upgraded yet: old single-device fields.
            return base.copy(
                online = s.child("online").getValue(Boolean::class.java) ?: false,
                since = s.child("since").getValue(Long::class.java).n(),
                lastSeen = s.child("lastSeen").getValue(Long::class.java).n(),
            )
        }
        fun DataSnapshot.on() = child("on").getValue(Boolean::class.java) == true
        fun DataSnapshot.since() = child("since").getValue(Long::class.java).n()
        fun DataSnapshot.last() = child("last").getValue(Long::class.java).n()
        val onDevs = devs.filter { it.on() }
        // Online if any device is in use, counted from the earliest; otherwise the device that went off last.
        return if (onDevs.isNotEmpty()) {
            base.copy(online = true, since = onDevs.map { it.since() }.filter { it > 0 }.minOrNull() ?: 0)
        } else {
            val latest = devs.maxBy { it.last() }
            base.copy(online = false, since = latest.since(), lastSeen = latest.last())
        }
    }

    /** If the connection drops while online, the server marks this device offline at once. */
    private fun armDisconnect(room: String) {
        val m: Map<String, Any> =
            if (visibleIn(room) && localOnline) mapOf("on" to false, "last" to ServerValue.TIMESTAMP)
            else mapOf("on" to false)
        dev(room).onDisconnect().updateChildren(m)
    }

    private fun pushPresence(room: String) {
        armDisconnect(room)
        val on = visibleIn(room) && localOnline
        // Clear the old single-device fields; presence now lives under dev/.
        me(room).updateChildren(mapOf("name" to name, "visible" to visibleIn(room), "online" to null, "since" to null, "lastSeen" to null))
        val d = mutableMapOf<String, Any>("on" to on)
        if (on && pushed.add(room)) d["since"] = ServerValue.TIMESTAMP
        dev(room).updateChildren(d)
    }

    /** Called when the phone starts or stops being in use (unlocked and screen on). */
    fun setActive(on: Boolean) {
        val was = localOnline
        localOnline = on
        for (room in rooms.value) {
            if (!visibleIn(room)) continue
            val m = mutableMapOf<String, Any>("on" to on)
            if (on && !was) { m["since"] = ServerValue.TIMESTAMP; pushed += room }
            if (!on && was) m["last"] = ServerValue.TIMESTAMP
            armDisconnect(room)
            dev(room).updateChildren(m)
        }
    }

    fun setVisible(v: Boolean) {
        val room = cur
        syncHidden(room, !v)
        me(room).child("visible").setValue(v)
        val m = mutableMapOf<String, Any>("on" to (v && localOnline))
        if (v && localOnline) m["since"] = ServerValue.TIMESTAMP
        armDisconnect(room)
        dev(room).updateChildren(m)
    }

    /** Joins (or creates) a shared code on this and all of this person's devices. */
    fun join(code: String) {
        val room = code.trim()
        person().updateChildren(mapOf("name" to name, "rooms/$room" to true))
        setRooms(rooms.value + room)
        select(room)
    }

    /** Removes this person (all their devices) from a connection. Deletes the code entirely if nobody else is in it. */
    fun leave(room: String) {
        dev(room).onDisconnect().cancel()
        val others = data.value[room]?.users?.keys.orEmpty() - uid
        val ref = db.getReference("rooms/$room")
        if (others.isEmpty()) ref.removeValue()
        else ref.updateChildren(mapOf("users/$uid" to null, "nicks/$uid" to null, "pfp/$uid" to null))
        person().child("rooms/$room").removeValue()
        setRooms(rooms.value - room)
    }

    /** Makes this device the same person as the device showing [code]. Calls back with false if the code is unknown. */
    fun link(code: String, done: (Boolean) -> Unit) {
        val id = code.trim().lowercase()
        if (id.isBlank()) return done(false)
        db.getReference("people/$id").get().addOnCompleteListener { t ->
            val n = if (t.isSuccessful) t.result.child("name").getValue(String::class.java) else null
            if (n.isNullOrBlank()) return@addOnCompleteListener done(false)
            prefs.edit().putString("uid", id).putString("name", n).putBoolean("migrated", true)
                .putStringSet("rooms", emptySet()).putStringSet("hidden", emptySet()).apply()
            done(true)
        }
    }

    fun setField(key: String, value: String) {
        if (value.isBlank()) me(cur).child(key).removeValue() else me(cur).child(key).setValue(value)
    }

    fun setNick(uid: String, nick: String) {
        val ref = db.getReference("rooms/$cur/nicks/$uid")
        if (nick.isBlank()) ref.removeValue() else ref.setValue(nick.trim())
    }

    /** Either person can set either photo; the latest write wins. Null removes it. */
    fun setPfp(uid: String, b64: String?) {
        val ref = db.getReference("rooms/$cur/pfp/$uid")
        if (b64 == null) ref.removeValue() else ref.setValue(b64)
    }

    /** Adds a shared status option (both phones see it). Skips blanks and duplicates. */
    fun addOption(section: String, text: String) {
        val t = text.trim()
        if (t.isBlank() || data.value[cur]?.options?.get(section).orEmpty().any { it.text == t }) return
        db.getReference("rooms/$cur/options/$section").push().setValue(t)
    }

    fun removeOption(section: String, key: String) {
        db.getReference("rooms/$cur/options/$section/$key").removeValue()
    }

    fun setTheme(name: String) {
        prefs.edit().putString("theme", name).apply()
        theme.value = name
    }

    fun talk() {
        me(cur).child("talk").setValue(ServerValue.TIMESTAMP)
    }
}
