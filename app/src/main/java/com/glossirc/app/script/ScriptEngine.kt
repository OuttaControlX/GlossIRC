package com.glossirc.app.script

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Small, deliberately bounded mIRC-like subset. Never executes Android shell commands. */
class ScriptEngine(context: Context) {
    data class Script(val name: String, val source: String, val enabled: Boolean = true)
    data class Event(val type: String, val nick: String = "", val channel: String = "", val text: String = "", val me: String = "", val server: String = "", val network: String = "")
    private val prefs = context.getSharedPreferences("scripts", Context.MODE_PRIVATE)
    val scripts = mutableListOf<Script>()
    private val variables = mutableMapOf<String, String>()
    private val variablePrefs = context.getSharedPreferences("script_variables", Context.MODE_PRIVATE)
    private fun saveVariables() {
        val json = JSONObject()
        variables.forEach { (key, value) -> json.put(key, value) }
        variablePrefs.edit().putString("values", json.toString()).apply()
    }
    private fun loadVariables() {
        val json = runCatching { JSONObject(variablePrefs.getString("values", "{}") ?: "{}") }.getOrDefault(JSONObject())
        json.keys().forEach { key -> variables[key] = json.optString(key) }
    }
    private val timerHandler = Handler(Looper.getMainLooper())
    data class Popup(val label: String, val command: String)
    private val popups = mutableMapOf<String, MutableList<Popup>>()
    fun popupMenu(context: String): List<Popup> = popups[context.lowercase()]?.toList().orEmpty()
    private val aliases = mutableMapOf<String, String>()
    private data class Handler(val type: String, val pattern: String, val target: String, val body: String)
    private val handlers = mutableListOf<Handler>()

    init { loadVariables(); load() }
    fun load() {
        scripts.clear()
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            scripts.add(Script(obj.optString("name"), obj.optString("source"), obj.optBoolean("enabled", true)))
        }
        rebuild()
    }
    fun save(script: Script) {
        require(script.name.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Invalid script filename" }
        val index = scripts.indexOfFirst { it.name.equals(script.name, true) }
        if (index < 0) scripts.add(script) else scripts[index] = script
        persist()
    }
    fun remove(name: String) { scripts.removeAll { it.name == name }; persist() }
    fun setEnabled(name: String, enabled: Boolean) {
        val index = scripts.indexOfFirst { it.name == name }
        if (index >= 0) { scripts[index] = scripts[index].copy(enabled = enabled); persist() }
    }
    private fun persist() {
        val array = JSONArray()
        scripts.forEach { array.put(JSONObject().put("name", it.name).put("source", it.source).put("enabled", it.enabled)) }
        prefs.edit().putString("items", array.toString()).apply()
        rebuild()
    }
    private fun rebuild() {
        aliases.clear(); handlers.clear(); popups.clear()
        scripts.filter { it.enabled }.forEach { script ->
            val source = script.source.replace("\r\n", "\n")
            // Balanced braces allow nested control-flow blocks inside aliases and events.
            val definitions = Regex("(?im)^\\s*(alias\\s+[\\w-]+|on\\s+[^\\n{]+|menu\\s+(?:nicklist|channel|status))\\s*\\{")
            definitions.findAll(source).forEach { match ->
                val open = source.indexOf('{', match.range.first)
                val close = matchingBrace(source, open)
                if (close < 0) return@forEach
                val header = match.groupValues[1].trim()
                val body = source.substring(open + 1, close)
                when {
                    header.startsWith("alias ", true) -> aliases[header.substringAfter(' ').trim().lowercase()] = body
                    header.startsWith("on ", true) -> {
                        val fields = header.substringAfter(' ').split(':')
                        // TEXT/ACTION/NOTICE: on level:TYPE:pattern:target:{...}
                        // JOIN/PART/CONNECT: on level:TYPE:target:{...}
                        if (fields.size >= 3) {
                            val type = fields[1].uppercase()
                            val messageEvent = type in setOf("TEXT", "ACTION", "NOTICE")
                            if (!messageEvent || fields.size >= 4) {
                                handlers.add(Handler(type, if (messageEvent) fields[2].trim() else "*", if (messageEvent) fields[3].trim() else fields[2].trim(), body))
                            }
                        }
                    }
                    header.startsWith("menu ", true) -> {
                        val menu = popups.getOrPut(header.substringAfter(' ').trim().lowercase()) { mutableListOf() }
                        body.lines().forEach { row ->
                            val item = row.trim()
                            if (item.contains(':') && !item.startsWith(';') && item != "-") menu.add(Popup(item.substringBefore(':').trim(), item.substringAfter(':').trim()))
                        }
                    }
                }
            }
        }
    }
    private fun matchingBrace(source: String, start: Int): Int {
        var depth = 0
        for (i in start until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }
    /** Return true if the command was handled by the script engine. */
    fun command(input: String, event: Event, send: (String) -> Unit, echo: (String) -> Unit): Boolean {
        val cmd = input.trim().removePrefix("/").substringBefore(' ').lowercase()
        val args = input.trim().removePrefix("/").substringAfter(' ', "")
        if (cmd == "set") {
            val key = args.substringBefore(' ').trim()
            if (!key.startsWith("%") || key.length < 2) { echo("Usage: /set %name value"); return true }
            variables[key.lowercase()] = expand(args.substringAfter(' ', ""), event, emptyList()); saveVariables(); return true
        }
        if (cmd == "unset") { variables.remove(args.trim().lowercase()); saveVariables(); return true }
        if (cmd == "echo") { echo(expand(args, event, emptyList())); return true }
        if (cmd !in aliases) return false
        execute(aliases.getValue(cmd), event, args.split(' ').filter { it.isNotEmpty() }, send, echo, 0)
        return true
    }
    fun dispatch(event: Event, send: (String) -> Unit, echo: (String) -> Unit) {
        handlers.filter { it.type == event.type.uppercase() && (event.type.uppercase() !in setOf("TEXT", "ACTION", "NOTICE") || match(it.pattern, event.text)) && (it.target == "*" || it.target == "#" && event.channel.startsWith("#") || it.target.equals(event.channel, true)) }
            .take(20).forEach { execute(it.body, event, emptyList(), send, echo, 0) }
    }
    private fun match(pattern: String, value: String): Boolean {
        if (pattern == "*") return true
        val regex = "^" + pattern.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("") + "$"
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(value) }.getOrDefault(false)
    }
    private fun expand(value: String, event: Event, args: List<String>, locals: Map<String, String> = emptyMap()): String {
        val identifier = Regex("(?i)\\$(nick|chan|me|server|network|time|date|[0-9]|\\*)\\b")
        val replaced = identifier.replace(value) { match -> when (match.groupValues[1].lowercase()) {
            "nick" -> event.nick; "chan" -> event.channel; "me" -> event.me
            "time" -> java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            "date" -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.util.Date())
            "server" -> event.server; "network" -> event.network
            "0" -> args.size.toString()
            "*" -> args.joinToString(" "); else -> args.getOrNull(match.groupValues[1].toInt() - 1) ?: ""
        } }
        // Evaluate innermost supported function calls first, permitting nested calls.
        var withFunctions = replaced
        val functionCall = Regex("(?i)\\$(upper|lower|len|left|right)\\(([^()]*)\\)")
        repeat(16) {
            if (!functionCall.containsMatchIn(withFunctions)) return@repeat
            withFunctions = functionCall.replace(withFunctions) { call ->
                val argument = call.groupValues[2]
                when (call.groupValues[1].lowercase()) {
                    "upper" -> argument.uppercase()
                    "lower" -> argument.lowercase()
                    "len" -> argument.length.toString()
                    "left" -> { val parts = argument.split(',', limit = 2); parts[0].take(parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0) }
                    else -> { val parts = argument.split(',', limit = 2); parts[0].takeLast(parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0) }
                }
            }
        }
        return Regex("%[A-Za-z_][A-Za-z0-9_]*").replace(withFunctions) { locals[it.value.lowercase()] ?: variables[it.value.lowercase()] ?: it.value }
    }
    private fun condition(expression: String, event: Event, args: List<String>, locals: Map<String, String> = emptyMap()): Boolean {
        val tokens = expand(expression.trim(), event, args, locals).split(Regex("\\s+"), limit = 3)
        if (tokens.size == 1) return tokens[0].isNotBlank() && tokens[0] != "0" && !tokens[0].equals("false", true)
        if (tokens.size != 3) return false
        val (left, operator, right) = tokens
        return when (operator) {
            "==", "iswm" -> if (operator == "iswm") match(right, left) else left.equals(right, true)
            "!=" -> !left.equals(right, true)
            ">" -> (left.toDoubleOrNull() ?: return false) > (right.toDoubleOrNull() ?: return false)
            "<" -> (left.toDoubleOrNull() ?: return false) < (right.toDoubleOrNull() ?: return false)
            ">=" -> (left.toDoubleOrNull() ?: return false) >= (right.toDoubleOrNull() ?: return false)
            "<=" -> (left.toDoubleOrNull() ?: return false) <= (right.toDoubleOrNull() ?: return false)
            else -> false
        }
    }
    private fun statements(body: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        body.forEach { c ->
            when (c) { '{' -> depth++; '}' -> depth-- }
            if ((c == '\n' || c == '|') && depth == 0) {
                if (current.isNotBlank()) result.add(current.toString().trim())
                current.clear()
            } else current.append(c)
        }
        if (current.isNotBlank()) result.add(current.toString().trim())
        return result
    }
    private fun execute(body: String, event: Event, args: List<String>, send: (String) -> Unit, echo: (String) -> Unit, depth: Int, locals: MutableMap<String, String> = mutableMapOf()) {
        if (depth > 12) { echo("Script recursion limit reached"); return }
        val lines = statements(body)
        var index = 0
        var budget = 0
        while (index < lines.size && budget++ < 500) {
            val raw = lines[index++].trim().removePrefix("/")
            if (raw.isEmpty() || raw.startsWith(';')) continue
            if (raw.startsWith("if ", true) || raw.startsWith("while ", true)) {
                val isWhile = raw.startsWith("while ", true)
                val open = raw.indexOf('(')
                if (open < 0) continue
                var nesting = 0
                var end = -1
                for (i in open until raw.length) {
                    if (raw[i] == '(') nesting++
                    if (raw[i] == ')') { nesting--; if (nesting == 0) { end = i; break } }
                }
                if (end < 0) continue
                val expr = raw.substring(open + 1, end)
                val tail = raw.substring(end + 1).trim()
                val thenBody = if (tail.startsWith('{') && matchingBrace(tail, 0) >= 0) tail.substring(1, matchingBrace(tail, 0)) else tail.substringBefore(" else ")
                val branches = mutableListOf<Pair<String?, String>>()
                branches.add(expr to thenBody)
                var fallback = if (!tail.startsWith('{')) tail.substringAfter(" else ", "") else ""
                while (index < lines.size) {
                    val sibling = lines[index].trim()
                    if (sibling.startsWith("elseif ", true)) {
                        val siblingOpen = sibling.indexOf('(')
                        if (siblingOpen < 0) break
                        var level = 0
                        var siblingEnd = -1
                        for (i in siblingOpen until sibling.length) {
                            if (sibling[i] == '(') level++
                            if (sibling[i] == ')') { level--; if (level == 0) { siblingEnd = i; break } }
                        }
                        if (siblingEnd < 0) break
                        val test = sibling.substring(siblingOpen + 1, siblingEnd)
                        val content = sibling.substring(siblingEnd + 1).trim()
                        val block = if (content.startsWith('{') && matchingBrace(content, 0) >= 0) content.substring(1, matchingBrace(content, 0)) else content
                        branches.add(test to block)
                        index++
                    } else if (sibling.startsWith("else ", true) || sibling.equals("else", true)) {
                        val content = sibling.substringAfter("else", "").trim()
                        fallback = if (content.startsWith('{') && matchingBrace(content, 0) >= 0) content.substring(1, matchingBrace(content, 0)) else content
                        index++
                        break
                    } else break
                }
                if (isWhile) {
                    var iterations = 0
                    while (condition(expr, event, args, locals) && iterations++ < 100) execute(thenBody, event, args, send, echo, depth + 1, locals)
                    if (iterations >= 100) echo("Script while loop capped at 100 iterations")
                } else {
                    val selected = branches.firstOrNull { (test, _) -> test != null && condition(test, event, args, locals) }?.second ?: fallback
                    if (selected.isNotBlank()) execute(selected, event, args, send, echo, depth + 1, locals)
                }
                continue
            }
            val line = expand(raw, event, args, locals)
            val cmd = line.substringBefore(' ').lowercase()
            val rest = line.substringAfter(' ', "")
            when (cmd) {
                "timer" -> {
                    val pieces = rest.split(' ', limit = 3)
                    val count = pieces.getOrNull(0)?.toIntOrNull()
                    val seconds = pieces.getOrNull(1)?.toLongOrNull()
                    val action = pieces.getOrNull(2)
                    if (count != null && count in 1..20 && seconds != null && seconds in 1..3600 && !action.isNullOrBlank()) {
                        repeat(count) { n -> timerHandler.postDelayed({ execute(action, event, args, send, echo, depth + 1, locals.toMutableMap()) }, seconds * 1000L * (n + 1)) }
                    }
                }
                "msg", "notice" -> {
                    val target = rest.substringBefore(' ')
                    val message = rest.substringAfter(' ', "")
                    if (target.isNotBlank() && message.isNotBlank()) send("${cmd.uppercase().let { if (it == "MSG") "PRIVMSG" else it }} $target :$message")
                }
                "echo" -> echo(rest)
                "halt" -> return
                "return" -> return
                "var" -> {
                    val key = rest.substringBefore(' ').lowercase()
                    if (key.startsWith("%") && key.length > 1) locals[key] = rest.substringAfter(' ', "")
                }
                "set" -> { val key = rest.substringBefore(' ').lowercase(); if (key.startsWith("%")) { variables[key] = rest.substringAfter(' ', ""); saveVariables() } }
                "unset" -> { variables.remove(rest.trim().lowercase()); saveVariables() }
                "inc", "dec" -> {
                    val key = rest.substringBefore(' ').lowercase()
                    val step = rest.substringAfter(' ', "1").toLongOrNull() ?: 1L
                    if (key.startsWith("%")) {
                        val current = (locals[key] ?: variables[key])?.toLongOrNull() ?: 0L
                        val updated = (current + if (cmd == "inc") step else -step).toString()
                        if (key in locals) locals[key] = updated else { variables[key] = updated; saveVariables() }
                    }
                }
                "join" -> if (rest.isNotBlank()) send("JOIN $rest")
                "part" -> if (rest.isNotBlank()) send("PART $rest")
                "nick" -> if (rest.isNotBlank()) send("NICK $rest")
                "me" -> if (event.channel.isNotBlank()) send("PRIVMSG ${event.channel} :\u0001ACTION $rest\u0001")
                "raw" -> if (rest.isNotBlank()) send(rest)
                in aliases -> execute(aliases.getValue(cmd), event, rest.split(' ').filter { it.isNotBlank() }, send, echo, depth + 1, mutableMapOf())
                else -> echo("Script command not supported: $cmd")
            }
        }
        if (budget >= 500) echo("Script statement limit reached")
    }
}
