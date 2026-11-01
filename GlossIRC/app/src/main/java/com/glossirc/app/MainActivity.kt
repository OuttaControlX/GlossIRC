package com.glossirc.app

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import androidx.core.content.ContextCompat
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.ui.platform.LocalContext
import com.glossirc.app.script.ScriptEngine
import com.glossirc.app.dcc.DccManager
import com.glossirc.app.dcc.DccOffer
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glossirc.app.irc.IrcEngine
import com.glossirc.app.irc.IrcMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val bg = Color(0xFF080D1B)
private val panel = Color(0xFF18223B)
private val cyan = Color(0xFF6BE6F5)
private val lavender = Color(0xFFA9A0FF)

/** RFC1459 default casemapping (includes []\ equivalence and ^/~). */
private fun ircKey(value: String): String = value.lowercase(java.util.Locale.ROOT)
    .replace('[', '{').replace(']', '}').replace('\\', '|').replace('^', '~')

data class ChannelEntry(val name: String, val count: Int, val topic: String)
data class ChatLine(val author: String, val text: String, val system: Boolean = false)
class ServerSession(val id: Int, val host: String, val nick: String) {
    val buffers = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatLine>>()
    val channelList = mutableStateListOf<ChannelEntry>()
    var listingChannels by mutableStateOf(false)
    val pendingJoins = mutableStateMapOf<String, String>()
    val joinedChannels = mutableStateMapOf<String, String>()
    val users = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<String>>()
    var status by mutableStateOf("Disconnected")
    var engine: IrcEngine? = null
    init { buffer("*status") }
    fun canonical(name: String): String = buffers.keys.firstOrNull { ircKey(it) == ircKey(name) } ?: name
    fun buffer(name: String) = buffers.getOrPut(canonical(name)) { mutableStateListOf() }
    fun userList(name: String) = users.getOrPut(users.keys.firstOrNull { ircKey(it) == ircKey(name) } ?: name) { mutableStateListOf() }
}

data class ServerProfile(val host: String, val port: Int, val tls: Boolean, val nick: String, val autoConnect: Boolean = false, val ident: String = "glossirc")

class IrcViewModel(application: Application) : androidx.lifecycle.AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("glossirc_profiles", android.content.Context.MODE_PRIVATE)
    val profiles = mutableStateListOf<ServerProfile>()
    init {
        runCatching {
            val array = JSONArray(preferences.getString("profiles", "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                profiles.add(ServerProfile(o.getString("host"), o.getInt("port"), o.getBoolean("tls"), o.getString("nick"), o.optBoolean("autoConnect", false), o.optString("ident", "glossirc")))
            }
        }
    }
    private fun persistProfiles() {
        val array = JSONArray()
        profiles.forEach { profile -> array.put(JSONObject().put("host", profile.host).put("port", profile.port).put("tls", profile.tls).put("nick", profile.nick).put("autoConnect", profile.autoConnect).put("ident", profile.ident)) }
        preferences.edit().putString("profiles", array.toString()).apply()
    }
    fun saveProfile(profile: ServerProfile) {
        profiles.removeAll { it.host.equals(profile.host, true) && it.port == profile.port }
        profiles.add(profile); persistProfiles()
    }
    fun removeProfile(profile: ServerProfile) { profiles.remove(profile); persistProfiles() }
    fun toggleAutoConnect(profile: ServerProfile) {
        val i = profiles.indexOf(profile)
        if (i >= 0) { profiles[i] = profile.copy(autoConnect = !profile.autoConnect); persistProfiles() }
    }

    val scriptEngine = ScriptEngine(application)
    val dcc = DccManager(application, viewModelScope)
    val servers = mutableStateListOf<ServerSession>()
    var activeServer by mutableIntStateOf(-1)
    var activeBuffer by mutableStateOf("*status")
    private var nextId = 1
    init { profiles.filter { it.autoConnect }.forEach { connect(it.host, it.port, it.tls, it.nick, it.ident) } }
    fun connect(host: String, port: Int, tls: Boolean, nick: String, ident: String = "glossirc") {
        runCatching { ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), IrcForegroundService::class.java)) }
            .onFailure { android.util.Log.w("GlossIRC", "Foreground service could not start", it) }
        if (servers.any { it.host.equals(host.trim(), true) && it.engine != null && it.status.startsWith("Connected") }) return
        val s = ServerSession(nextId++, host.trim(), nick.trim())
        servers.add(s); activeServer = s.id; activeBuffer = "*status"
        s.engine = IrcEngine(viewModelScope, s.host, port, tls, nick, ident,
            onLine = { raw -> viewModelScope.launch(Dispatchers.Main) { handle(s, raw) } },
            onStatus = { msg -> viewModelScope.launch(Dispatchers.Main) { s.status = msg; s.buffer("*status").add(ChatLine("*", msg, true)) } })
        s.engine?.connect()
    }
    fun choose(server: ServerSession, buffer: String) { activeServer = server.id; activeBuffer = server.canonical(buffer) }
    fun disconnect(server: ServerSession) {
        server.engine?.send("QUIT :Leaving GlossIRC")
        server.engine?.disconnect()
        servers.remove(server)
        if (activeServer == server.id) {
            activeServer = servers.firstOrNull()?.id ?: -1
            activeBuffer = "*status"
        }
    }
    fun requestJoin(server: ServerSession, channel: String) {
        val name = channel.trim().substringBefore(' ')
        if (!Regex("^[#&+!][^\\s,\\u0007]{1,100}$").matches(name)) return
        if (server.joinedChannels.containsKey(ircKey(name))) { choose(server, server.joinedChannels[ircKey(name)] ?: name); return }
        server.pendingJoins[ircKey(name)] = name
        server.buffer("*status").add(ChatLine("*", "Joining $name…", true))
        server.engine?.send("JOIN $name")
    }
    fun requestChannels(server: ServerSession) {
        server.channelList.clear()
        server.listingChannels = true
        server.engine?.send("LIST")
    }
    private fun handle(s: ServerSession, raw: String) {
        val m = IrcMessage.parse(raw)
        val p = m.params
        if (m.command.equals("PRIVMSG", true) && p.size >= 2 && p[1].startsWith("\u0001DCC ACCEPT ")) {
            val accepted = dcc.handleAccept(s.id, m.sender, p[1])
            s.buffer("*status").add(ChatLine("*", if (accepted) "DCC resume accepted by ${m.sender}" else "Unmatched DCC ACCEPT from ${m.sender}", true))
            return
        }
        if (m.command.equals("PRIVMSG", true) && p.size >= 2 && p[1].startsWith("\u0001DCC RESUME ")) {
            val resumed = dcc.handleResume(s.id, m.sender, p[1]) { s.engine?.send(it) }
            s.buffer("*status").add(ChatLine("*", if (resumed) "DCC outgoing resume accepted for ${m.sender}" else "Unmatched DCC RESUME from ${m.sender}", true))
            return
        }
        if (m.command.equals("PRIVMSG", true) && p.size >= 2 && p[1].startsWith("\u0001DCC SEND ")) {
            val offer = dcc.parseOffer(s.id, m.sender, p[1])
            s.buffer("*status").add(ChatLine("*", if (offer != null) "DCC offer from ${m.sender}: ${offer.name} (${offer.size} bytes). Open Transfers to accept." else "Unsupported DCC offer from ${m.sender}", true))
            return
        }
        val eventType = when (m.command.uppercase()) { "PRIVMSG" -> if (p.getOrNull(1)?.startsWith("\u0001ACTION ") == true) "ACTION" else "TEXT"; "NOTICE" -> "NOTICE"; "JOIN" -> "JOIN"; "PART" -> "PART"; "001" -> "CONNECT"; else -> null }
        if (eventType != null) {
            val channel = p.firstOrNull().orEmpty()
            val text = p.getOrNull(1).orEmpty().removePrefix("\u0001ACTION ").removeSuffix("\u0001")
            scriptEngine.dispatch(ScriptEngine.Event(eventType, m.sender, channel, text, s.engine?.nick.orEmpty()), { s.engine?.send(it) }, { s.buffer(channel.ifBlank { "*status" }).add(ChatLine("*", it, true)) })
        }
        when (m.command.uppercase()) {
            "321" -> { s.channelList.clear(); s.listingChannels = true }
            "322" -> if (p.size >= 4) {
                val entry = ChannelEntry(p[1], p[2].toIntOrNull() ?: 0, p[3])
                if (s.channelList.size < 10000) s.channelList.add(entry)
            }
            "323" -> { s.listingChannels = false }
            "PRIVMSG", "NOTICE" -> if (p.size >= 2) {
                val dest = if (ircKey(p[0]) == ircKey(s.engine?.nick.orEmpty())) m.sender else p[0]
                val action = p[1].startsWith("\u0001ACTION ") && p[1].endsWith("\u0001")
                s.buffer(dest).add(ChatLine(if (action) "* ${m.sender}" else m.sender, if (action) p[1].removePrefix("\u0001ACTION ").removeSuffix("\u0001") else p[1]))
            }
            "JOIN" -> if (p.isNotEmpty()) {
                val chan = p[0]
                if (ircKey(m.sender) == ircKey(s.engine?.nick.orEmpty())) {
                    s.pendingJoins.remove(ircKey(chan))
                    s.joinedChannels[ircKey(chan)] = chan
                    choose(s, chan)
                }
                s.buffer(chan).add(ChatLine("*", "${m.sender} joined $chan", true))
                val users = s.userList(chan)
                if (users.none { it.equals(m.sender, true) }) users.add(m.sender)
            }
            "403", "405", "471", "473", "474", "475", "476", "477", "479" -> {
                val channel = p.getOrNull(1).orEmpty()
                s.pendingJoins.remove(ircKey(channel))
                s.buffer("*status").add(ChatLine("*", "Join failed for $channel: ${p.lastOrNull().orEmpty()}", true))
            }
            "PART", "QUIT" -> {
                if (m.command.equals("PART", true) && ircKey(m.sender) == ircKey(s.engine?.nick.orEmpty())) {
                    p.firstOrNull()?.let { s.joinedChannels.remove(ircKey(it)) }
                }
                val channels = if (m.command == "PART") p.take(1) else s.users.keys.toList()
                channels.forEach { ch -> s.users.entries.firstOrNull { ircKey(it.key) == ircKey(ch) }?.value?.removeAll { it.equals(m.sender, true) }; s.buffer(ch).add(ChatLine("*", "${m.sender} left", true)) }
            }
            "353" -> if (p.size >= 4) {
                val ch = p[2]; val users = s.users.getOrPut(ch) { mutableStateListOf() }
                p[3].split(' ').filter { it.isNotBlank() }.forEach { n -> if (n !in users) users.add(n) }
            }
            "366" -> Unit
            "NICK" -> if (p.isNotEmpty()) {
                s.users.forEach { (ch, names) ->
                    val old = names.indexOfFirst { it.trimStart('@','+','%','&','~').equals(m.sender, true) }
                    if (old >= 0) { names[old] = p[0]; s.buffer(ch).add(ChatLine("*", "${m.sender} is now ${p[0]}", true)) }
                }
            }
            "PING", "PONG" -> Unit
            else -> s.buffer("*status").add(ChatLine("*", raw, true))
        }
    }
    fun submit(text: String) {
        val s = servers.firstOrNull { it.id == activeServer } ?: return
        val t = text.trim(); if (t.isEmpty()) return
        val e = s.engine ?: return
        if (t.startsWith('/')) {
            val command = t.drop(1).substringBefore(' ').lowercase()
            val args = t.drop(1).substringAfter(' ', "")
            if (scriptEngine.command(t, ScriptEngine.Event("COMMAND", channel = activeBuffer, me = e.nick), { e.send(it) }, { s.buffer(activeBuffer).add(ChatLine("*", it, true)) })) return
            when (command) {
                "load" -> { scriptEngine.setEnabled(args.trim(), true) }
                "unload" -> { scriptEngine.setEnabled(args.trim(), false) }
                "join", "j" -> { if (args.isNotBlank()) requestJoin(s, args) }
                "part" -> { val ch = args.ifBlank { activeBuffer }; e.send("PART $ch") }
                "nick" -> e.send("NICK $args")
                "msg", "query" -> { val target = args.substringBefore(' '); val body = args.substringAfter(' ', ""); if (target.isNotBlank() && body.isNotBlank()) { e.send("PRIVMSG $target :$body"); s.buffer(target).add(ChatLine("me", body)); activeBuffer = s.canonical(target) } }
                "me" -> if (activeBuffer != "*status") { e.send("PRIVMSG $activeBuffer :\u0001ACTION $args\u0001"); s.buffer(activeBuffer).add(ChatLine("* me", args)) }
                "notice" -> { val target = args.substringBefore(' '); val body = args.substringAfter(' ', ""); if (target.isNotBlank() && body.isNotBlank()) e.send("NOTICE $target :$body") }
                "raw", "quote" -> e.send(args)
                "quit" -> disconnect(s)
                else -> s.buffer(activeBuffer).add(ChatLine("*", "Unknown command: /$command (use /raw for protocol commands)", true))
            }
        } else if (activeBuffer != "*status") { e.send("PRIVMSG $activeBuffer :$t"); s.buffer(activeBuffer).add(ChatLine("me", t)) }
    }
    fun shutdown() { dcc.close(); servers.forEach { it.engine?.disconnect() } }
    override fun onCleared() { shutdown(); super.onCleared() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { GlossApp() } }
}

@Composable fun GlossApp() {
    val context = LocalContext.current
    val vm = remember { GlossRuntime.model(context.applicationContext as Application) }
    var showConnect by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var showScripts by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    val expandedServers = remember { mutableStateMapOf<Int, Boolean>() }
    var showUsers by remember { mutableStateOf(false) }
    var showChannels by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var proposedChannel by remember { mutableStateOf<String?>(null) }
    val session = vm.servers.firstOrNull { it.id == vm.activeServer }
    val lines = session?.buffer(vm.activeBuffer) ?: emptyList()
    MaterialTheme(colorScheme = darkColorScheme(primary = cyan, secondary = lavender, background = bg, surface = panel, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2850), bg, bg)))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showDrawer = !showDrawer }) { Icon(Icons.Default.Menu, "Servers and channels", tint = cyan) }
                    Column(Modifier.weight(1f)) {
                        Text("GlossIRC", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(session?.let { "${it.host}  •  ${vm.activeBuffer}" } ?: "Connect to an IRC server", color = lavender, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    IconButton(onClick = { showTransfers = true }) { Icon(Icons.Default.FileDownload, "DCC transfers", tint = cyan) }
                    IconButton(onClick = { showScripts = true }) { Icon(Icons.Default.Code, "Script editor", tint = lavender) }
                    IconButton(onClick = { showChannels = true; session?.let { vm.requestChannels(it) } }) { Icon(Icons.Default.List, "Channel list", tint = lavender) }
                    IconButton(onClick = { showUsers = !showUsers }) { Icon(Icons.Default.People, "Nicknames", tint = cyan) }
                    IconButton(onClick = { showConnect = true }) { Icon(Icons.Default.AddCircle, "Add server", tint = cyan) }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .12f))
                if (session == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Forum, null, tint = cyan, modifier = Modifier.size(68.dp))
                            Spacer(Modifier.height(14.dp)); Text("Welcome to GlossIRC", color = Color.White)
                            Spacer(Modifier.height(12.dp)); Button(onClick = { showConnect = true }) { Text("Connect to server") }
                        }
                    }
                } else {
                    Text(session.status, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = lavender, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), reverseLayout = true) {
                        items(lines.asReversed()) { line ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(if (line.system) "• " else "${line.author}  ", color = if (line.system) lavender else cyan, fontWeight = FontWeight.SemiBold)
                                ClickableText(
                                    text = linkedIrcText(line.text),
                                    modifier = Modifier.weight(1f),
                                    onClick = { offset ->
                                        linkedIrcText(line.text).getStringAnnotations("CHANNEL", offset, offset).firstOrNull()?.let { proposedChannel = it.item }
                                    }
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(10.dp).background(panel, RoundedCornerShape(22.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("Message or /command") }, modifier = Modifier.weight(1f), singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                        IconButton(onClick = { colorPicker = true }) { Icon(Icons.Default.Palette, "Text color", tint = lavender) }
                        IconButton(onClick = { vm.submit(draft); draft = "" }) { Icon(Icons.Default.Send, "Send", tint = cyan) }
                    }
                }
            }
            if (showDrawer) {
                Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))) {
                    Column(Modifier.fillMaxHeight().fillMaxWidth(.78f).background(Brush.verticalGradient(listOf(Color(0xFF26355F), panel, bg))).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Servers & channels", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = { showDrawer = false }) { Icon(Icons.Default.Close, "Close") } }
                        Button(onClick = { showDrawer = false; showConnect = true }) { Text("+ Add server") }
                        Text("Saved server list", color = lavender, fontWeight = FontWeight.Bold)
                        vm.profiles.forEach { profile ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text("${profile.host}:${profile.port} · ${profile.nick} · ${profile.ident}", color = Color.White)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { vm.connect(profile.host, profile.port, profile.tls, profile.nick, profile.ident); showDrawer = false }) { Text("Connect") }
                                    TextButton(onClick = { vm.toggleAutoConnect(profile) }) { Text(if (profile.autoConnect) "Auto: on" else "Auto: off") }
                                    TextButton(onClick = { vm.removeProfile(profile) }) { Text("Remove") }
                                }
                            }
                        }
                        Text("Active connections", color = lavender, fontWeight = FontWeight.Bold)
                        vm.servers.forEach { s ->
                            Spacer(Modifier.height(9.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (expandedServers[s.id] != false) "▾" else "▸", color = cyan,
                                    modifier = Modifier.clickable { expandedServers[s.id] = expandedServers[s.id] == false }.padding(8.dp))
                                Text(s.host, color = cyan, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f).clickable { vm.choose(s, "*status"); showDrawer = false })
                                TextButton(onClick = { vm.disconnect(s); expandedServers.remove(s.id) }) { Text("Disconnect") }
                            }
                            if (expandedServers[s.id] != false) {
                                val names = s.buffers.keys.toList().sortedWith(compareBy<String> { if (it == "*status") 0 else if (it.startsWith("#") || it.startsWith("&")) 1 else 2 }.thenBy { it.lowercase() })
                                names.forEach { ch ->
                                    val symbol = if (ch == "*status") "◉" else if (ch.startsWith("#") || ch.startsWith("&")) "#" else "✉"
                                    Text("    $symbol  $ch", color = if (vm.activeServer == s.id && ircKey(vm.activeBuffer) == ircKey(ch)) cyan else Color.White,
                                        modifier = Modifier.fillMaxWidth().clickable { vm.choose(s, ch); showDrawer = false }.padding(vertical = 9.dp))
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize().clickable { showDrawer = false })
                }
            }
        }
        if (showChannels && session != null) ChannelListDialog(session, onJoin = { channel ->
            proposedChannel = channel
            showChannels = false
        }, onDismiss = { showChannels = false })
        if (proposedChannel != null && session != null) {
            val target = proposedChannel!!
            AlertDialog(onDismissRequest = { proposedChannel = null },
                title = { Text("Join channel?") },
                text = { Text("Join $target on ${session.host}? The channel will open only after the server confirms your JOIN.") },
                confirmButton = { TextButton(onClick = { vm.requestJoin(session, target); proposedChannel = null }) { Text("Join") } },
                dismissButton = { TextButton(onClick = { proposedChannel = null }) { Text("Cancel") } })
        }
        if (colorPicker) AlertDialog(onDismissRequest = { colorPicker = false }, title = { Text("mIRC text colors") }, text = {
            Column {
                Text("Insert a color code at the end of your message. You can also type mIRC control codes directly.")
                mircPalette.chunked(4).forEachIndexed { rowIndex, colors ->
                    Row { colors.forEachIndexed { index, color ->
                        val code = rowIndex * 4 + index
                        Box(Modifier.padding(5.dp).size(43.dp).background(Color(color), RoundedCornerShape(10.dp)).border(1.dp, Color.Gray, RoundedCornerShape(10.dp)).clickable { draft += "\u0003" + code.toString().padStart(2, '0'); colorPicker = false })
                    } }
                }
                TextButton(onClick = { draft += "\u000f"; colorPicker = false }) { Text("Reset formatting") }
            }
        }, confirmButton = { TextButton(onClick = { colorPicker = false }) { Text("Close") } })
        if (showTransfers) DccDialog(vm, onDismiss = { showTransfers = false })
        if (showScripts) ScriptEditor(vm.scriptEngine, onDismiss = { showScripts = false })
        if (showConnect) ConnectDialog(onDismiss = { showConnect = false }, onConnect = { h,p,t,n,i -> vm.saveProfile(ServerProfile(h.trim(),p,t,n.trim(), ident = i.trim())); vm.connect(h,p,t,n,i); showConnect = false; showDrawer = false })
        if (showUsers && session != null) {
            Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)), horizontalArrangement = Arrangement.End) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { showUsers = false })
                Column(Modifier.fillMaxHeight().fillMaxWidth(.76f).background(Brush.verticalGradient(listOf(Color(0xFF27375F), panel, bg))).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nicknames · ${vm.activeBuffer}", color = cyan, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showUsers = false }) { Icon(Icons.Default.Close, "Close") }
                    }
                    val nicks = session.users[vm.activeBuffer]?.sorted().orEmpty()
                    LazyColumn {
                        items(nicks) { nick ->
                            var expanded by remember(nick) { mutableStateOf(false) }
                            Box {
                                Text(nick, Modifier.fillMaxWidth().clickable { expanded = true }.padding(10.dp), color = Color.White)
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    vm.scriptEngine.popupMenu("nicklist").forEach { popup ->
                                        DropdownMenuItem(text = { Text(popup.label) }, onClick = {
                                            expanded = false
                                            vm.submit("/" + popup.command.replace("$" + "1", nick).replace("#", vm.activeBuffer))
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Whois") }, onClick = { expanded = false; vm.submit("/raw WHOIS $nick") })
                                    DropdownMenuItem(text = { Text("Open private chat") }, onClick = {
                                        expanded = false; vm.choose(session, nick); showUsers = false
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ConnectDialog(onDismiss: () -> Unit, onConnect: (String,Int,Boolean,String,String) -> Unit) {
    var host by remember { mutableStateOf("irc.libera.chat") }; var port by remember { mutableStateOf("6697") }; var nick by remember { mutableStateOf("GlossUser") }; var ident by remember { mutableStateOf("glossirc") }; var tls by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect to IRC") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("Server hostname") }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true)
            OutlinedTextField(nick, { nick = it }, label = { Text("Preferred nickname") }, singleLine = true)
            OutlinedTextField(ident, { ident = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(32) }, label = { Text("Ident / username") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(tls, { tls = it }); Text("TLS encryption") }
        }
    }, confirmButton = { TextButton(enabled = host.isNotBlank() && nick.isNotBlank() && ident.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } == true), onClick = { onConnect(host, port.toInt(), tls, nick, ident) }) { Text("Connect") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private val mircPalette = listOf(0xFFFFFFFF,0xFF000000,0xFF00007F,0xFF009300,0xFFFF0000,0xFF7F0000,0xFF9C009C,0xFFFC7F00,0xFFFFFF00,0xFF00FC00,0xFF009393,0xFF00FFFF,0xFF0000FC,0xFFFF00FF,0xFF7F7F7F,0xFFD2D2D2)
private fun ircStyled(input: String): AnnotatedString = buildAnnotatedString {
    var bold = false; var italic = false; var underline = false; var color: Color? = null
    var i = 0
    while (i < input.length) {
        when (input[i]) {
            '\u0002' -> { bold = !bold; i++ }
            '\u001D' -> { italic = !italic; i++ }
            '\u001F' -> { underline = !underline; i++ }
            '\u000F' -> { bold = false; italic = false; underline = false; color = null; i++ }
            '\u0003' -> {
                i++; val start = i
                while (i < input.length && i - start < 2 && input[i].isDigit()) i++
                color = if (i > start) input.substring(start, i).toIntOrNull()?.let { if (it in mircPalette.indices) Color(mircPalette[it]) else null } else null
                if (i < input.length && input[i] == ',') { val comma = i; i++; val bgStart = i; while (i < input.length && i - bgStart < 2 && input[i].isDigit()) i++; if (i == bgStart) i = comma }
            }
            else -> {
                pushStyle(SpanStyle(color = color ?: Color(0xFFE6EAF9), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal, textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null))
                append(input[i]); pop(); i++
            }
        }
    }
}

private val channelLinkPattern = Regex("(?<![A-Za-z0-9_])[#&+!][A-Za-z0-9_\\-\\[\\]\\{\\}\\\\|`^~.]{1,99}")

private fun linkedIrcText(input: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    channelLinkPattern.findAll(input).forEach { match ->
        if (match.range.first > cursor) append(ircStyled(input.substring(cursor, match.range.first)))
        val start = length
        append(match.value)
        addStyle(SpanStyle(color = cyan, textDecoration = TextDecoration.Underline), start, length)
        addStringAnnotation("CHANNEL", match.value, start, length)
        cursor = match.range.last + 1
    }
    if (cursor < input.length) append(ircStyled(input.substring(cursor)))
}

@Composable private fun ScriptEditor(engine: ScriptEngine, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var scriptTab by remember { mutableStateOf("Remote") }
    var selected by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("example.mrc") }
    var source by remember { mutableStateOf("on *:TEXT:!hello:#:{\n  msg ${'$'}chan Hello ${'$'}nick!\n}\n\nalias greet {\n  echo -a Hello from GlossIRC\n}") }
    var enabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read file")
            require(text.length <= 200_000) { "Script exceeds 200 KB limit" }
            source = text
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: "imported.mrc"
            selected = ""; error = "Imported into editor; tap Save / Load to enable"
        }.onFailure { error = it.message ?: "Import failed" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(source) } ?: error("Cannot write file")
            error = "Exported"
        }.onFailure { error = it.message ?: "Export failed" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("mIRC Script Editor") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("Remote", "Aliases", "Popups", "Variables").forEach { tab ->
                    TextButton(onClick = {
                        scriptTab = tab
                        if (tab != "Variables") {
                            name = when (tab) { "Aliases" -> "aliases.mrc"; "Popups" -> "popups.mrc"; else -> "remote.mrc" }
                            val existing = engine.scripts.firstOrNull { it.name == name }
                            source = existing?.source ?: when (tab) {
                                "Aliases" -> "alias greet {\n  echo Hello from GlossIRC!\n}"
                                "Popups" -> "menu nicklist {\n  Whois:raw WHOIS $" + "1\n}"
                                else -> "on *:TEXT:!hello:#:{\n  msg $" + "chan Hello $" + "nick!\n}"
                            }
                            enabled = existing?.enabled ?: true
                            selected = existing?.name ?: ""
                        }
                    }) { Text(tab, color = if (scriptTab == tab) cyan else lavender) }
                }
            }
            if (scriptTab == "Variables") {
                Text("Persistent global %variables (read-only). Use /set %name value and /unset %name in chat to modify.")
                engine.globalVariables().toSortedMap().forEach { (key, value) ->
                    Text("$key = $value", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            } else {
            Text("Edit $scriptTab scripts. Save / Load activates changes.", style = MaterialTheme.typography.bodySmall)
            engine.scripts.toList().forEach { script ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = script.name; name = script.name; source = script.source; enabled = script.enabled }) { Text(script.name, maxLines = 1) }
                    Spacer(Modifier.weight(1f))
                    Checkbox(script.enabled, onCheckedChange = { engine.setEnabled(script.name, it); revision++ })
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Script filename (.mrc)") }, singleLine = true)
            OutlinedTextField(source, { source = it }, label = { Text("Script source") }, modifier = Modifier.fillMaxWidth().height(240.dp), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled, { enabled = it }); Text("Load / enable") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { selected = ""; name = "new.mrc"; source = ""; enabled = true }) { Text("New") }
                TextButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import") }
                TextButton(onClick = { exportLauncher.launch(name.ifBlank { "script.mrc" }) }) { Text("Export") }
                TextButton(onClick = { runCatching { engine.save(ScriptEngine.Script(name, source, enabled)); selected = name; revision++; error = "Saved" }.onFailure { error = it.message ?: "Save failed" } }) { Text("Save / Load") }
                TextButton(enabled = selected.isNotBlank(), onClick = { engine.remove(selected); selected = ""; revision++; error = "Unloaded and deleted" }) { Text("Delete") }
            }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}



@Composable private fun DccDialog(vm: IrcViewModel, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<DccOffer?>(null) }
    var target by remember { mutableStateOf("") }
    var publicIp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val receivePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val offer = selected
        if (uri != null && offer != null) {
            val session = vm.servers.firstOrNull { it.id == offer.serverId }
            vm.dcc.receive(offer, uri, false) { session?.engine?.send(it) }
        }
        selected = null
    }
    val resumePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val offer = selected
        if (uri != null && offer != null) {
            val session = vm.servers.firstOrNull { it.id == offer.serverId }
            vm.dcc.receive(offer, uri, true) { session?.engine?.send(it) }
        }
        selected = null
    }
    val sendPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val session = vm.servers.firstOrNull { it.id == vm.activeServer }
        if (uri != null && session != null) runCatching {
            vm.dcc.send(session.id, target.trim(), uri, publicIp.trim()) { session.engine?.send(it) }
        }.onFailure { error = it.message.orEmpty() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("DCC transfers") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
            Text("Incoming offers", fontWeight = FontWeight.Bold)
            vm.dcc.offers.forEach { offer ->
                Text("${offer.sender}: ${offer.name} (${offer.size} bytes)")
                Row {
                    TextButton(onClick = { selected = offer; receivePicker.launch(offer.name) }) { Text("Receive") }
                    TextButton(onClick = { selected = offer; resumePicker.launch(arrayOf("*/*")) }) { Text("Resume") }
                    TextButton(onClick = { vm.dcc.offers.remove(offer) }) { Text("Decline") }
                }
            }
            HorizontalDivider()
            Text("Send file", fontWeight = FontWeight.Bold)
            OutlinedTextField(target, { target = it }, label = { Text("Recipient nickname") }, singleLine = true)
            OutlinedTextField(publicIp, { publicIp = it }, label = { Text("Your reachable public IPv4") }, singleLine = true)
            Text("Active DCC requires inbound port forwarding. No NAT traversal is provided.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = target.isNotBlank() && publicIp.isNotBlank() && vm.activeServer >= 0, onClick = { sendPicker.launch(arrayOf("*/*")) }) { Text("Choose file to send") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("Transfers", fontWeight = FontWeight.Bold)
            vm.dcc.transfers.forEach { t ->
                Text("${t.name} · ${t.peer}")
                Text("${t.state}: ${t.transferred}/${t.size} bytes ${t.error}", style = MaterialTheme.typography.bodySmall)
                if (t.state !in listOf("Complete", "Failed", "Cancelled")) TextButton(onClick = { vm.dcc.cancel(t.id) }) { Text("Cancel") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable private fun ChannelListDialog(server: ServerSession, onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var sortByUsers by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Channels · ${server.host}") }, text = {
        Column {
            OutlinedTextField(filter, { filter = it }, label = { Text("Search channels and topics") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (server.listingChannels) "Loading… ${server.channelList.size}" else "${server.channelList.size} channels", modifier = Modifier.weight(1f))
                TextButton(onClick = { sortByUsers = !sortByUsers }) { Text(if (sortByUsers) "Most users" else "A–Z") }
            }
            val entries = server.channelList.filter { it.name.contains(filter, true) || it.topic.contains(filter, true) }
                .let { if (sortByUsers) it.sortedByDescending { entry -> entry.count } else it.sortedBy { entry -> entry.name.lowercase() } }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries.take(1500)) { entry ->
                    Column(Modifier.fillMaxWidth().clickable { onJoin(entry.name) }.padding(vertical = 8.dp)) {
                        Text("${entry.name}  ·  ${entry.count} users", color = cyan, fontWeight = FontWeight.Bold)
                        Text(ircStyled(entry.topic), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .08f))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
