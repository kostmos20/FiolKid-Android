package com.fiolkid.app.engine

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Минимальный клиент WebSocket для моста Telegram (порт raw_websocket.py из tg-ws-proxy, MIT): TLS к серверу,
 * рукопожатие на /apiws и двоичные кадры. Отправка из одного потока, приём — из другого.
 */
class TgWebSocket private constructor(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
    private val output: OutputStream = socket.getOutputStream()
    private val fragments = java.io.ByteArrayOutputStream()

    @Volatile
    var closed = false
        private set

    companion object {
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 16 * 1024 * 1024
        private val random = SecureRandom()

        /**
         * Подключиться к [host] (IP или имя) на 443 и открыть WebSocket к [domain]. [sni] — другое имя в TLS
         * («фронтинг»): тогда имя в сертификате не сверяем, как check_hostname=False в оригинале.
         * [socks] — порт byedpi: соединение идёт через обход, имя сервера byedpi узнаёт сам.
         */
        fun connect(
            host: String, domain: String, path: String = "/apiws", sni: String? = null, timeoutMs: Int = 8000, socks: Int? = null,
            protocol: String? = "binary",
        ): TgWebSocket {
            val raw = openSocket(socks)
            try {
                raw.tcpNoDelay = true
                raw.connect(if (socks != null) InetSocketAddress.createUnresolved(host, 443) else InetSocketAddress(host, 443), timeoutMs)
                raw.soTimeout = timeoutMs
                val tlsName = sni ?: domain
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, tlsName, 443, true) as SSLSocket
                tls.startHandshake()
                if (sni == null && !certFor(tls, domain)) throw IOException("сертификат не для $domain")

                val key = base64(ByteArray(16).also(random::nextBytes))
                val request = "GET $path HTTP/1.1\r\nHost: $domain\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n" +
                    (if (protocol != null) "Sec-WebSocket-Protocol: $protocol\r\n" else "") + "\r\n"
                tls.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                tls.outputStream.flush()

                val ws = TgWebSocket(tls)
                val status = ws.readLine()
                while (ws.readLine().isNotEmpty()) Unit
                if (status.split(' ').getOrNull(1) != "101") {
                    ws.close()
                    throw IOException("ответ сервера: $status")
                }
                tls.soTimeout = 0
                return ws
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        }
    }

    private fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("соединение закрыто при рукопожатии")
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
            if (line.length > 8192) throw IOException("слишком длинный заголовок")
        }
    }

    /** Сколько ждать следующего сообщения (0 — сколько угодно). */
    fun timeout(ms: Int) {
        socket.soTimeout = ms
    }

    fun send(data: ByteArray) = sendAll(listOf(data))

    /** Несколько кадров за одну запись — как send_batch. */
    @Synchronized
    fun sendAll(parts: List<ByteArray>) {
        if (closed) throw IOException("WebSocket закрыт")
        val buffer = java.io.ByteArrayOutputStream(parts.sumOf { it.size + 14 })
        parts.forEach { buffer.write(frame(OP_BINARY, it)) }
        output.write(buffer.toByteArray())
        output.flush()
    }

    /** Следующее сообщение или null, если сервер закрыл соединение. */
    fun receive(): ByteArray? {
        while (!closed) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length > MAX_MESSAGE) throw IOException("слишком большой кадр: $length")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also(input::readFully) else null
            val payload = ByteArray(length.toInt()).also(input::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            when (opcode) {
                OP_CLOSE -> {
                    runCatching { synchronized(this) { output.write(frame(OP_CLOSE, payload.copyOf(minOf(2, payload.size)))) } }
                    closed = true
                    return null
                }
                OP_PING -> runCatching { synchronized(this) { output.write(frame(OP_PONG, payload)); output.flush() } }
                OP_PONG -> Unit
                OP_CONT, OP_TEXT, OP_BINARY -> {
                    if (fin && fragments.size() == 0) return payload
                    fragments.write(payload)
                    if (fragments.size() > MAX_MESSAGE) throw IOException("слишком большое сообщение")
                    if (fin) return fragments.toByteArray().also { fragments.reset() }
                }
            }
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { synchronized(this) { output.write(frame(OP_CLOSE, ByteArray(0))); output.flush() } }
        runCatching { socket.close() }
    }

    /** Кадр от клиента — всегда с маской. */
    private fun frame(opcode: Int, data: ByteArray): ByteArray {
        val length = data.size
        val header = java.io.ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            length < 126 -> header.write(0x80 or length)
            length < 65536 -> {
                header.write(0x80 or 126)
                header.write(length ushr 8)
                header.write(length and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((length.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(length) { (data[it].toInt() xor mask[it and 3].toInt()).toByte() }
        header.write(masked)
        return header.toByteArray()
    }
}

/**
 * Сертификат выдан на [domain]: цепочку доверия уже проверил TLS, здесь — имя (в том числе *.домен).
 * Своя проверка, а не HostnameVerifier: на компьютере (в тестах) стандартный всегда отвечает «нет».
 */
private fun certFor(tls: SSLSocket, domain: String): Boolean {
    val cert = tls.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate ?: return false
    val names = cert.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.map { (it[1] as String).lowercase() }
    val host = domain.lowercase()
    return names.any { name ->
        name == host || (name.startsWith("*.") && host.endsWith(name.substring(1)) && host.count { it == '.' } == name.count { it == '.' })
    }
}

/** Обычный сокет или сокет через SOCKS-порт byedpi на этом телефоне. */
internal fun openSocket(socks: Int?): Socket =
    if (socks == null) Socket() else Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks)))

/** Base64 для ключа рукопожатия (java.util.Base64 есть только с Android 8). */
private fun base64(data: ByteArray): String {
    val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val out = StringBuilder()
    for (i in data.indices step 3) {
        val n = (data[i].toInt() and 0xFF shl 16) or
            ((data.getOrNull(i + 1)?.toInt() ?: 0) and 0xFF shl 8) or
            ((data.getOrNull(i + 2)?.toInt() ?: 0) and 0xFF)
        out.append(abc[n shr 18 and 63]).append(abc[n shr 12 and 63])
        out.append(if (i + 1 < data.size) abc[n shr 6 and 63] else '=')
        out.append(if (i + 2 < data.size) abc[n and 63] else '=')
    }
    return out.toString()
}
