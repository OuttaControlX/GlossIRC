package com.glossirc.app.irc

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/** Each connection has its own socket, reader, writer and coroutine. */
class IrcEngine(
    private val scope: CoroutineScope,
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    private val initialNick: String,
    private val ident: String,
    private val onLine: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val autoReconnect: Boolean = true
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var job: Job? = null
    @Volatile private var manuallyStopped = false
    private val writeLock = Any()
    @Volatile var nick: String = initialNick
        private set

    fun connect() {
        if (job?.isActive == true) return
        manuallyStopped = false
        job = scope.launch(Dispatchers.IO) {
          var attempt = 0
          while (isActive && !manuallyStopped) {
            try {
                onStatus("Connecting to $host:$port…")
                val s = if (tls) SSLSocketFactory.getDefault().createSocket(host, port) else Socket(host, port)
                socket = s
                val output = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                writer = output
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                send("NICK $nick")
                send("USER ${ident.ifBlank { "glossirc" }.take(32)} 0 * :GlossIRC Android")
                attempt = 0
                onStatus("Connected to $host")
                while (isActive) {
                    val line = input.readLine() ?: break
                    if (line.startsWith("PING ")) send("PONG " + line.removePrefix("PING "))
                    if (line.startsWith(":")) {
                        val parts = line.split(' ', limit = 4)
                        if (parts.size >= 3 && parts[1] == "NICK" && parts[0].substringAfter(':').substringBefore('!').equals(nick, true)) {
                            nick = parts[2].removePrefix(":")
                        }
                    }
                    onLine(line)
                }
            } catch (e: Exception) {
                onStatus("Connection error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                writer = null
                onStatus("Disconnected from $host")
            }
            if (!autoReconnect || manuallyStopped || !isActive) break
            attempt++
            val delayMs = (1000L shl minOf(attempt, 6)).coerceAtMost(60000L)
            onStatus("Reconnecting to $host in ${delayMs / 1000}s (attempt $attempt)")
            delay(delayMs)
          }
        }
    }

    fun send(line: String) {
        if (line.contains('\r') || line.contains('\n')) return
        scope.launch(Dispatchers.IO) {
            try { synchronized(writeLock) { writer?.apply { write(line); write("\r\n"); flush() } } }
            catch (e: Exception) { onStatus("Send failed: ${e.message}") }
        }
    }

    fun disconnect() {
        manuallyStopped = true
        try { socket?.close() } catch (_: Exception) {}
        job?.cancel()
    }
}

/** Minimal RFC 1459 line parser; retains raw trailing text. */
data class IrcMessage(val prefix: String?, val command: String, val params: List<String>) {
    val sender: String get() = prefix?.substringBefore('!') ?: "server"
    companion object {
        fun parse(raw: String): IrcMessage {
            var rest = raw
            var prefix: String? = null
            if (rest.startsWith(':')) { prefix = rest.substring(1).substringBefore(' '); rest = rest.substringAfter(' ', "") }
            val trailingIndex = rest.indexOf(" :")
            val head = if (trailingIndex >= 0) rest.substring(0, trailingIndex) else rest
            val words = head.split(' ').filter { it.isNotEmpty() }
            val args = words.drop(1).toMutableList()
            if (trailingIndex >= 0) args.add(rest.substring(trailingIndex + 2))
            return IrcMessage(prefix, words.firstOrNull() ?: "", args)
        }
    }
}
