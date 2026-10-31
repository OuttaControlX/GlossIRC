package com.glossirc.app.dcc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Classic active DCC SEND (not passive/reverse DCC). Peer IP is untrusted. */
data class DccOffer(val id: Int, val serverId: Int, val sender: String, val name: String, val host: String, val port: Int, val size: Long, val token: String? = null)
class DccTransfer(val id: Int, val name: String, val peer: String, val size: Long) {
    var transferred by mutableLongStateOf(0L)
    var state by mutableStateOf("Waiting")
    var error by mutableStateOf("")
}
class DccManager(private val context: Context, private val scope: CoroutineScope) {
    val offers = mutableStateListOf<DccOffer>()
    val transfers = mutableStateListOf<DccTransfer>()
    private val next = AtomicInteger(1)
    private val jobs = mutableMapOf<Int, Job>()
    private val listeners = mutableMapOf<Int, ServerSocket>()
    private data class Outgoing(val serverId: Int, val target: String, val filename: String, val port: Int, val size: Long, val resumeOffset: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0))
    private val outgoing = java.util.concurrent.ConcurrentHashMap<Int, Outgoing>()
    private data class PendingResume(val offer: DccOffer, val uri: Uri, val offset: Long, val transfer: DccTransfer, val sendIrc: (String) -> Unit)
    private val pending = mutableMapOf<Int, PendingResume>()
    private fun safeName(name: String) = name.length in 1..255 && name.none { it == '/' || it == '\\' || it.code < 32 }

    fun parseOffer(serverId: Int, sender: String, text: String): DccOffer? {
        if (!text.startsWith("\u0001DCC SEND ") || !text.endsWith("\u0001")) return null
        val payload = text.removePrefix("\u0001DCC SEND ").removeSuffix("\u0001")
        val m = Regex("^(\"[^\"]+\"|\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)(?:\\s+(\\S+))?$").matchEntire(payload) ?: return null
        val name = m.groupValues[1].trim('"')
        val address = m.groupValues[2]
        val port = m.groupValues[3].toIntOrNull() ?: return null
        val size = m.groupValues[4].toLongOrNull() ?: return null
        if (!safeName(name) || port !in 1..65535 || size < 0L) return null
        val host = if (address.all(Char::isDigit)) {
            val n = address.toLongOrNull() ?: return null
            if (n > 0xffffffffL) return null
            listOf(24,16,8,0).joinToString(".") { ((n shr it) and 255).toString() }
        } else address
        if (host.length > 255 || host.isBlank()) return null
        return DccOffer(next.getAndIncrement(), serverId, sender, name, host, port, size, m.groupValues.getOrNull(5)?.ifBlank { null }).also { offers.add(it) }
    }

    /** RESUME is requested only for a user-selected existing document. */
    fun receive(offer: DccOffer, uri: Uri, resume: Boolean, sendIrc: (String) -> Unit) {
        offers.remove(offer)
        val transfer = DccTransfer(next.getAndIncrement(), offer.name, offer.sender, offer.size)
        transfers.add(transfer)
        jobs[transfer.id] = scope.launch(Dispatchers.IO) {
            try {
                val offset = if (resume) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { FileInputStream(it.fileDescriptor).channel.size() }
                        ?: error("Cannot read partial file")
                } else 0L
                require(offset <= offer.size) { "Partial file exceeds offered size" }
                transfer.transferred = offset
                if (offset == offer.size) { transfer.state = "Complete"; return@launch }
                if (offset > 0) {
                    pending[transfer.id] = PendingResume(offer, uri, offset, transfer, sendIrc)
                    transfer.state = "Waiting for DCC ACCEPT"
                    sendIrc("PRIVMSG ${offer.sender} :\u0001DCC RESUME \"${offer.name}\" ${offer.port} $offset${offer.token?.let { " $it" } ?: ""}\u0001")
                } else download(offer, uri, 0, transfer)
            } catch (e: CancellationException) { transfer.state = "Cancelled"; throw e }
            catch (e: Exception) { transfer.state = "Failed"; transfer.error = e.message ?: "Transfer error" }
        }
    }

    /** Match ACCEPT to the original offer, server and sender; never trust a stray CTCP. */
    fun handleAccept(serverId: Int, sender: String, text: String): Boolean {
        if (!text.startsWith("\u0001DCC ACCEPT ") || !text.endsWith("\u0001")) return false
        val match = Regex("^(\"[^\"]+\"|\\S+)\\s+(\\d+)\\s+(\\d+)(?:\\s+(\\S+))?$")
            .matchEntire(text.removePrefix("\u0001DCC ACCEPT ").removeSuffix("\u0001")) ?: return false
        val name = match.groupValues[1].trim('"')
        val port = match.groupValues[2].toIntOrNull() ?: return false
        val offset = match.groupValues[3].toLongOrNull() ?: return false
        val token = match.groupValues.getOrNull(4)?.ifBlank { null }
        val entry = pending.entries.firstOrNull { (_, p) ->
            p.offer.serverId == serverId && p.offer.sender.equals(sender, true) &&
            p.offer.name == name && p.offer.port == port && p.offset == offset && p.offer.token == token
        } ?: return false
        val (id, item) = entry
        pending.remove(id)
        jobs[id] = scope.launch(Dispatchers.IO) {
            try { download(item.offer, item.uri, item.offset, item.transfer) }
            catch (e: CancellationException) { item.transfer.state = "Cancelled"; throw e }
            catch (e: Exception) { item.transfer.state = "Failed"; item.transfer.error = e.message ?: "Transfer error" }
        }
        return true
    }

    /** Handle a peer's RESUME for a file we are serving. Only accept a matching active offer. */
    fun handleResume(serverId: Int, sender: String, text: String, sendIrc: (String) -> Unit): Boolean {
        if (!text.startsWith("\u0001DCC RESUME ") || !text.endsWith("\u0001")) return false
        val match = Regex("^(\"[^\"]+\"|\\S+)\\s+(\\d+)\\s+(\\d+)$")
            .matchEntire(text.removePrefix("\u0001DCC RESUME ").removeSuffix("\u0001")) ?: return false
        val name = match.groupValues[1].trim('"')
        val port = match.groupValues[2].toIntOrNull() ?: return false
        val offset = match.groupValues[3].toLongOrNull() ?: return false
        val entry = outgoing.values.firstOrNull {
            it.serverId == serverId && it.target.equals(sender, true) && it.filename == name &&
                it.port == port && offset >= 0 && offset < it.size
        } ?: return false
        entry.resumeOffset.set(offset)
        sendIrc("PRIVMSG $sender :\u0001DCC ACCEPT \"$name\" $port $offset\u0001")
        return true
    }

    private suspend fun download(offer: DccOffer, uri: Uri, offset: Long, transfer: DccTransfer) {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
            FileOutputStream(fd.fileDescriptor).channel.use { channel ->
                if (offset == 0L) channel.truncate(0L)
                channel.position(offset)
                transfer.state = "Connecting"
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(offer.host, offer.port), 12000)
                    socket.soTimeout = 30000
                    transfer.state = "Receiving"
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val buf = ByteArray(32768)
                    while (transfer.transferred < offer.size) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), offer.size - transfer.transferred).toInt())
                        if (n < 0) error("Peer closed before transfer completed")
                        var chunk = ByteBuffer.wrap(buf, 0, n)
                        while (chunk.hasRemaining()) channel.write(chunk)
                        transfer.transferred += n
                        output.write(ByteBuffer.allocate(4).putInt(transfer.transferred.toInt()).array())
                    }
                    transfer.state = "Complete"
                }
            }
        } ?: error("Cannot open destination")
    }

    /** Sends an Android document via classic active DCC. Public reachable address is supplied by user. */
    fun send(serverId: Int, target: String, uri: Uri, advertisedIpv4: String, sendIrc: (String) -> Unit) {
        val ip = advertisedIpv4.split('.')
        require(ip.size == 4 && ip.all { (it.toIntOrNull() ?: -1) in 0..255 }) { "Enter a valid reachable IPv4 address" }
        val address = ip.fold(0L) { a, part -> (a shl 8) or part.toLong() }
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        require(safeName(name) && size >= 0) { "Invalid file metadata" }
        val t = DccTransfer(next.getAndIncrement(), name, target, size)
        transfers.add(t)
        jobs[t.id] = scope.launch(Dispatchers.IO) {
            try {
                ServerSocket(0).use { server ->
                    listeners[t.id] = server
                    server.soTimeout = 120000
                    outgoing[t.id] = Outgoing(serverId, target, name, server.localPort, size)
                    t.state = "Waiting for peer"
                    sendIrc("PRIVMSG $target :\u0001DCC SEND \"$name\" $address ${server.localPort} $size\u0001")
                    server.accept().use { socket ->
                        socket.soTimeout = 30000
                        t.state = "Sending"
                        val offset = outgoing[t.id]?.resumeOffset?.get() ?: 0L
                        t.transferred = offset
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                                channel.position(offset)
                                val out = socket.getOutputStream()
                                val buf = ByteBuffer.allocate(32768)
                                while (t.transferred < size) {
                                    currentCoroutineContext().ensureActive()
                                    buf.clear()
                                    buf.limit(minOf(buf.capacity().toLong(), size - t.transferred).toInt())
                                    val n = channel.read(buf)
                                    if (n < 0) error("Source file ended early")
                                    if (n == 0) continue
                                    out.write(buf.array(), 0, n)
                                    t.transferred += n
                                }
                                out.flush()
                            }
                        } ?: error("Cannot read selected file")
                        t.state = "Complete"
                    }
                }
            } catch (e: CancellationException) { t.state = "Cancelled"; throw e }
            catch (e: Exception) { t.state = "Failed"; t.error = e.message ?: "Transfer error" }
            finally { listeners.remove(t.id); outgoing.remove(t.id) }
        }
    }
    fun cancel(id: Int) { pending.remove(id); listeners[id]?.close(); jobs[id]?.cancel(); transfers.firstOrNull { it.id == id }?.state = "Cancelled" }
    fun close() { jobs.values.forEach { it.cancel() }; listeners.values.forEach { runCatching { it.close() } } }
}
