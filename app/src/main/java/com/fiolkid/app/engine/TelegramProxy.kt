package com.fiolkid.app.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Состояние прокси Telegram для экрана. */
data class TelegramState(
    val running: Boolean = false,
    val active: Int = 0,
    val total: Int = 0,
    /** Telegram хоть раз подключался к прокси (значит, прокси в нём добавлен). */
    val connectedOnce: Boolean = false,
)

/**
 * Прокси Telegram — то же, что tg-ws-proxy (Flowseal, MIT) в версии для компьютера. Telegram подключается к нему
 * как к MTProto-прокси (ссылка tg://proxy на 127.0.0.1), прокси расшифровывает его поток своим секретом,
 * шифрует заново обычной обфускацией Telegram и отправляет через WebSocket на kws*.web.telegram.org — их
 * не блокируют, ими пользуется веб-версия. DC 1, 3 и 5 — через домены за Cloudflare, в крайнем случае напрямую.
 *
 * Приложение не входит в свой VPN, поэтому соединения прокси сами идут через SOCKS-порт byedpi — как на компьютере
 * соединения tg-ws-proxy проходят через zapret, — а если не вышло, напрямую.
 */
object TelegramProxy {
    const val PORT = 1443
    private const val PREFS = "telegram"

    private const val HANDSHAKE = 64
    private const val SKIP = 8
    private const val PREKEY = 32
    private const val IV = 16
    private const val TAG_POS = 56
    private const val DC_POS = 60

    private val TAG_ABRIDGED = byteArrayOf(-17, -17, -17, -17) // ef ef ef ef
    private val TAG_INTERMEDIATE = byteArrayOf(-18, -18, -18, -18) // ee ee ee ee
    private val TAG_SECURE = byteArrayOf(-35, -35, -35, -35) // dd dd dd dd

    /** DC 2 и 4 — напрямую через kws-сервер Telegram (как dc_redirects по умолчанию). */
    private val DIRECT = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220")
    private val DC_IPS = mapOf(1 to "149.154.175.50", 2 to "149.154.167.51", 3 to "149.154.175.100", 4 to "149.154.167.91", 5 to "149.154.171.5", 203 to "91.105.192.100")
    private val DC_TEST_IPS = mapOf(1 to "149.154.175.10", 2 to "149.154.167.40", 3 to "149.154.175.117")

    // Домены за Cloudflare для DC без прямого kws: в оригинале они записаны со сдвигом букв, расшифровываются
    // так же; свежий список раз в запуск берём с GitHub проекта
    private val CF_ENCODED = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com", "awzwsldi.com",
        "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com", "vwbmtmoi.com", "khgrre.com",
        "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com", "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com",
        "efabnxaowuzs.com", "zaftuzsftqdq.com",
    )
    private const val CF_LIST_URL = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    @Volatile
    private var cfDomains: List<String> = CF_ENCODED.map(::decodeDomain)

    private const val ATTEMPT_MS = 6000
    private const val STAGGER_MS = 400L

    /** Порт byedpi: через него соединения прокси проходят обход. null — только напрямую (тест на компьютере). */
    @Volatile
    var socksPort: Int? = null

    /** Каким путём в последний раз дошли до каждого DC. */
    private val best = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val complained = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val random = SecureRandom()
    private lateinit var secret: ByteArray
    private lateinit var prefs: android.content.SharedPreferences
    private var server: ServerSocket? = null
    private val active = AtomicInteger()
    private val total = AtomicInteger()
    private val clients = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    private val _state = MutableStateFlow(TelegramState())
    val state: StateFlow<TelegramState> = _state.asStateFlow()

    /** Вызывается, когда Telegram впервые подключился к прокси (чтобы обновить карточку). */
    var onFirstClient: (() -> Unit)? = null

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hex = prefs.getString("secret", null)
            ?: ByteArray(16).also(random::nextBytes).toHex().also { prefs.edit().putString("secret", it).apply() }
        secret = hex.fromHex()
        publish()
    }

    /** Ссылка, по которой Telegram сам предложит добавить прокси. */
    val link get() = "tg://proxy?server=127.0.0.1&port=$PORT&secret=dd${secret.toHex()}"

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
        server = socket
        thread(name = "tg-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "tg-client", isDaemon = true) { handle(client) }
            }
        }
        thread(name = "tg-domains", isDaemon = true) { refreshDomains() }
        Engine.note("Прокси Telegram запущен")
        publish()
    }

    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        Engine.note("Прокси Telegram остановлен")
        publish()
    }

    fun restart() {
        stop()
        start()
    }

    private fun publish() {
        _state.value = TelegramState(server != null, active.get(), total.get(), connectedOnce())
    }

    private fun connectedOnce() = ::prefs.isInitialized && prefs.getBoolean("connected", false)

    /** Для проверки на компьютере: свой секрет без настроек Android. */
    internal fun useSecret(value: ByteArray) {
        secret = value
    }

    /** Для проверки на компьютере: забыть, какими путями доходили до DC. */
    internal fun forgetRoutes() = best.clear()

    /** Проверка: открывается ли WebSocket к Telegram. Пусто — всё хорошо, иначе — причина. */
    fun probe(): String? = try {
        openWs(2, media = false, test = false).close()
        null
    } catch (e: Exception) {
        "Telegram: ${e.message ?: e.javaClass.simpleName}"
    }

    // --- одно подключение Telegram ---

    private fun handle(client: Socket) {
        clients += client
        active.incrementAndGet()
        total.incrementAndGet()
        if (::prefs.isInitialized && !connectedOnce()) {
            prefs.edit().putBoolean("connected", true).apply()
            Engine.note("Telegram подключился к прокси")
            onFirstClient?.invoke()
        }
        publish()
        try {
            client.tcpNoDelay = true
            client.soTimeout = 10_000
            val input = DataInputStream(client.getInputStream())
            val handshake = ByteArray(HANDSHAKE).also(input::readFully)
            client.soTimeout = 0
            val parsed = parseHandshake(handshake) ?: return
            var dc = parsed.dc
            val test = dc >= 10000
            if (test) dc -= 10000
            val relayInit = relayInit(parsed.tag, if (parsed.media) -dc else dc)
            val crypto = crypto(parsed.prekeyIv, relayInit)
            val proto = when {
                parsed.tag.contentEquals(TAG_ABRIDGED) -> Splitter.ABRIDGED
                parsed.tag.contentEquals(TAG_INTERMEDIATE) -> Splitter.INTERMEDIATE
                else -> Splitter.PADDED
            }

            val ws = try {
                openWs(dc, parsed.media, test)
            } catch (e: Exception) {
                complain(dc, parsed.media, "WebSocket не открылся (${e.message ?: e.javaClass.simpleName}), пробую напрямую на IP")
                null
            }
            if (ws != null) {
                ws.send(relayInit)
                bridgeWs(client, input, ws, crypto, Splitter(proto))
            } else {
                tcpFallback(client, input, dc, parsed.media, test, relayInit, crypto)
            }
        } catch (_: Exception) {
            // Telegram сам переподключится
        } finally {
            runCatching { client.close() }
            clients -= client
            active.decrementAndGet()
            publish()
        }
    }

    private class Handshake(val dc: Int, val media: Boolean, val tag: ByteArray, val prekeyIv: ByteArray)

    private fun parseHandshake(handshake: ByteArray): Handshake? {
        val prekeyIv = handshake.copyOfRange(SKIP, SKIP + PREKEY + IV)
        val key = sha256(prekeyIv.copyOfRange(0, PREKEY) + secret)
        val plain = ctr(key, prekeyIv.copyOfRange(PREKEY, PREKEY + IV)).update(handshake)
        val tag = plain.copyOfRange(TAG_POS, TAG_POS + 4)
        if (!(tag.contentEquals(TAG_ABRIDGED) || tag.contentEquals(TAG_INTERMEDIATE) || tag.contentEquals(TAG_SECURE))) return null
        val dcIdx = ((plain[DC_POS].toInt() and 0xFF) or (plain[DC_POS + 1].toInt() shl 8)).toShort().toInt()
        return Handshake(kotlin.math.abs(dcIdx), dcIdx < 0, tag, prekeyIv)
    }

    /** Начало потока к Telegram: случайные 64 байта (без запрещённых начал) с зашифрованным хвостом. */
    private fun relayInit(tag: ByteArray, dcIdx: Int): ByteArray {
        val reserved = listOf("HEAD", "POST", "GET ", "OPTI").map { it.toByteArray(Charsets.US_ASCII) } +
            listOf(TAG_INTERMEDIATE, TAG_SECURE, byteArrayOf(0x16, 0x03, 0x01, 0x02))
        var rnd: ByteArray
        while (true) {
            rnd = ByteArray(HANDSHAKE).also(random::nextBytes)
            if (rnd[0] == (-17).toByte()) continue
            val start = rnd.copyOfRange(0, 4)
            if (reserved.any { it.contentEquals(start) }) continue
            if (rnd.copyOfRange(4, 8).all { it == 0.toByte() }) continue
            break
        }
        val enc = ctr(rnd.copyOfRange(SKIP, SKIP + PREKEY), rnd.copyOfRange(SKIP + PREKEY, SKIP + PREKEY + IV)).update(rnd)
        val tail = tag + byteArrayOf((dcIdx and 0xFF).toByte(), ((dcIdx shr 8) and 0xFF).toByte()) + ByteArray(2).also(random::nextBytes)
        val result = rnd.copyOf()
        for (i in 0 until 8) result[TAG_POS + i] = (tail[i].toInt() xor (enc[TAG_POS + i].toInt() xor rnd[TAG_POS + i].toInt())).toByte()
        return result
    }

    private class Crypto(val cltDec: Cipher, val cltEnc: Cipher, val tgEnc: Cipher, val tgDec: Cipher)

    private fun crypto(prekeyIv: ByteArray, relayInit: ByteArray): Crypto {
        val cltDec = ctr(sha256(prekeyIv.copyOfRange(0, PREKEY) + secret), prekeyIv.copyOfRange(PREKEY, PREKEY + IV))
        cltDec.update(ByteArray(HANDSHAKE))
        val reversed = prekeyIv.reversedArray()
        val cltEnc = ctr(sha256(reversed.copyOfRange(0, PREKEY) + secret), reversed.copyOfRange(PREKEY, PREKEY + IV))

        val relay = relayInit.copyOfRange(SKIP, SKIP + PREKEY + IV)
        val tgEnc = ctr(relay.copyOfRange(0, PREKEY), relay.copyOfRange(PREKEY, PREKEY + IV))
        tgEnc.update(ByteArray(HANDSHAKE))
        val relayReversed = relay.reversedArray()
        val tgDec = ctr(relayReversed.copyOfRange(0, PREKEY), relayReversed.copyOfRange(PREKEY, PREKEY + IV))
        return Crypto(cltDec, cltEnc, tgEnc, tgDec)
    }

    // --- куда отправлять ---

    /** Один из путей к DC: как он называется в журнале и как его открыть. */
    private class Route(val name: String, val open: (timeoutMs: Int) -> TgWebSocket)

    /**
     * Все пути к DC по порядку: kws-сервер Telegram (обычный TLS, потом с «фронтингом»), затем домены за
     * Cloudflare. Каждый — сначала через byedpi (как на компьютере соединения tg-ws-proxy проходят через zapret:
     * провайдер может душить и их), потом напрямую.
     */
    private fun routes(dc: Int, media: Boolean, test: Boolean): List<Route> {
        val path = if (test) "/apiws_test" else "/apiws"
        val ways = listOfNotNull(socksPort?.let { "через обход" to it }, "напрямую" to null)
        val list = mutableListOf<Route>()
        DIRECT[dc]?.let { target ->
            val domains = if (media) listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org") else listOf("kws$dc.web.telegram.org")
            for (sni in listOf(null, "sprinthost.ru")) for (domain in domains) for ((way, port) in ways) {
                val name = "${domain.substringBefore('.')}${if (sni != null) " с подменой имени" else ""}, $way"
                list += Route(name) { timeout -> TgWebSocket.connect(target, domain, path, sni, timeout, port) }
            }
        }
        if (!test) {
            val dcKey = if (dc == 203) 2 else dc
            for (base in cfDomains.shuffled().take(3)) for ((way, port) in ways) {
                val domain = "kws$dcKey.$base"
                list += Route("Cloudflare $base, $way") { timeout -> TgWebSocket.connect(domain, domain, timeoutMs = timeout, socks = port) }
            }
        }
        // Путь, который сработал в прошлый раз, — первым
        val known = best[key(dc, media)]
        return list.sortedBy { if (it.name == known) 0 else 1 }
    }

    /**
     * WebSocket к нужному DC. Пути пробуются почти одновременно, с шагом [STAGGER_MS]: Telegram ждёт ответа
     * секунд 10, перебирать по очереди с долгими тайм-аутами нельзя. Первый открывшийся побеждает.
     */
    private fun openWs(dc: Int, media: Boolean, test: Boolean): TgWebSocket {
        val routes = routes(dc, media, test)
        if (routes.isEmpty()) throw IOException("нет пути к DC$dc")
        val results = java.util.concurrent.LinkedBlockingQueue<Any>()
        val won = java.util.concurrent.atomic.AtomicBoolean(false)
        routes.forEachIndexed { index, route ->
            thread(name = "tg-route", isDaemon = true) {
                if (index > 0) Thread.sleep(STAGGER_MS * index)
                if (won.get()) return@thread results.put(Unit)
                val result: Any = try {
                    route to route.open(ATTEMPT_MS)
                } catch (e: Exception) {
                    e
                }
                if (result is Pair<*, *> && !won.compareAndSet(false, true)) (result.second as TgWebSocket).close()
                else results.put(result)
            }
        }
        val deadline = System.currentTimeMillis() + ATTEMPT_MS + STAGGER_MS * routes.size + 1000
        var last: Exception? = null
        repeat(routes.size) {
            val result = results.poll(maxOf(0, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return@repeat
            if (result is Pair<*, *>) {
                val route = result.first as Route
                remember(dc, media, route.name)
                return result.second as TgWebSocket
            }
            if (result is Exception) last = result
        }
        // Кто-то мог открыться уже после срока — его закроет поток, увидев won
        won.set(true)
        throw last ?: IOException("нет ответа")
    }

    private fun key(dc: Int, media: Boolean) = "DC$dc${if (media) " (медиа)" else ""}"

    /** Каким путём дошли до DC — в журнал, только когда путь поменялся. */
    private fun remember(dc: Int, media: Boolean, route: String) {
        val key = key(dc, media)
        if (best.put(key, route) != route) Engine.note("Telegram $key: $route")
    }

    /** Неудача — в журнал не чаще раза в минуту для каждого DC, иначе Telegram с переподключениями завалит журнал. */
    private fun complain(dc: Int, media: Boolean, message: String) {
        val key = key(dc, media)
        val now = System.currentTimeMillis()
        if (now - (complained[key] ?: 0) < 60_000) return
        complained[key] = now
        Engine.note("Telegram $key: $message")
    }

    /** Пути до каждого DC — для отчёта. */
    fun routesReport(): List<String> = best.entries.sortedBy { it.key }.map { "${it.key}: ${it.value}" }

    private fun refreshDomains() {
        runCatching {
            val text = URL("$CF_LIST_URL?${random.nextInt(1_000_000)}").openStream().use { it.readBytes().toString(Charsets.UTF_8) }
            val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::decodeDomain)
                .filter { it.matches(Regex("""[a-z0-9.-]+\.[a-z]{2,}""")) }.distinct()
            if (list.size >= 3) cfDomains = list
        }
    }

    /** Расшифровка доменов как _dd в config.py: сдвиг букв на их количество, окончание .com → .co.uk. */
    private fun decodeDomain(s: String): String {
        if (!s.endsWith(".com")) return s
        val body = s.dropLast(4)
        val n = body.count { it.isLetter() }
        return body.map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + ((c - 'a' - n) % 26 + 26) % 26
                in 'A'..'Z' -> 'A' + ((c - 'A' - n) % 26 + 26) % 26
                else -> c
            }
        }.joinToString("") + ".co.uk"
    }

    // --- пересылка ---

    private fun bridgeWs(client: Socket, input: InputStream, ws: TgWebSocket, crypto: Crypto, splitter: Splitter) {
        val output = client.getOutputStream()
        val down = thread(name = "tg-down", isDaemon = true) {
            try {
                while (true) {
                    val data = ws.receive() ?: break
                    val plain = crypto.tgDec.update(data)
                    output.write(crypto.cltEnc.update(plain))
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
            }
        }
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) {
                    splitter.flush()?.let(ws::send)
                    break
                }
                val plain = crypto.cltDec.update(buffer, 0, n)
                val cipher = crypto.tgEnc.update(plain)
                val parts = splitter.split(cipher, plain)
                if (parts.isNotEmpty()) ws.sendAll(parts)
            }
        } catch (_: Exception) {
        } finally {
            ws.close()
            runCatching { client.close() }
            down.join(2000)
        }
    }

    /** Последняя попытка — на IP дата-центра, через обход или напрямую (часто заблокирован, но вдруг). */
    private fun tcpFallback(client: Socket, input: InputStream, dc: Int, media: Boolean, test: Boolean, relayInit: ByteArray, crypto: Crypto) {
        val ip = (if (test) DC_TEST_IPS else DC_IPS)[dc] ?: return
        var opened: Socket? = null
        for ((way, port) in listOfNotNull(socksPort?.let { "через обход" to it }, "напрямую" to null)) {
            val socket = openSocket(port)
            try {
                socket.connect(InetSocketAddress(ip, 443), ATTEMPT_MS)
                opened = socket
                remember(dc, media, "IP дата-центра, $way")
                break
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        val remote = opened ?: return complain(dc, media, "не открылся ни один путь — Telegram будет висеть на «Соединение»")
        try {
            remote.tcpNoDelay = true
            remote.getOutputStream().write(relayInit)
            val down = pump(remote.getInputStream(), client.getOutputStream(), crypto.tgDec, crypto.cltEnc) { runCatching { client.close() } }
            pumpHere(input, remote.getOutputStream(), crypto.cltDec, crypto.tgEnc)
            down.join(2000)
        } finally {
            runCatching { remote.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream, dec: Cipher, enc: Cipher, done: () -> Unit) =
        thread(name = "tg-pump", isDaemon = true) {
            try {
                pumpHere(from, to, dec, enc)
            } finally {
                done()
            }
        }

    private fun pumpHere(from: InputStream, to: OutputStream, dec: Cipher, enc: Cipher) {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(enc.update(dec.update(buffer, 0, n)))
                to.flush()
            }
        } catch (_: Exception) {
        }
    }

    // --- шифрование ---

    private fun ctr(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Делит поток на отдельные пакеты MTProto, чтобы каждый ушёл своим кадром WebSocket (MsgSplitter из bridge.py).
 * Длины читаются из расшифрованного потока, отправляется зашифрованный.
 */
private class Splitter(private val proto: Int) {
    companion object {
        const val ABRIDGED = 1
        const val INTERMEDIATE = 2
        const val PADDED = 3
    }

    private val cipherBuf = java.io.ByteArrayOutputStream()
    private val plainBuf = java.io.ByteArrayOutputStream()
    private var disabled = false

    fun split(cipher: ByteArray, plain: ByteArray): List<ByteArray> {
        if (cipher.isEmpty()) return emptyList()
        if (disabled) return listOf(cipher)
        cipherBuf.write(cipher)
        plainBuf.write(plain)
        val c = cipherBuf.toByteArray()
        val p = plainBuf.toByteArray()
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < c.size) {
            val length = nextLength(p, offset, c.size - offset) ?: break
            if (length <= 0) {
                parts += c.copyOfRange(offset, c.size)
                offset = c.size
                disabled = true
                break
            }
            parts += c.copyOfRange(offset, offset + length)
            offset += length
        }
        cipherBuf.reset()
        plainBuf.reset()
        if (offset < c.size) {
            cipherBuf.write(c, offset, c.size - offset)
            plainBuf.write(p, offset, p.size - offset)
        }
        return parts
    }

    fun flush(): ByteArray? {
        if (cipherBuf.size() == 0) return null
        return cipherBuf.toByteArray().also {
            cipherBuf.reset()
            plainBuf.reset()
        }
    }

    private fun nextLength(p: ByteArray, offset: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (proto) {
            ABRIDGED -> {
                val first = p[offset].toInt() and 0xFF
                val (payload, header) = if (first == 0x7F || first == 0xFF) {
                    if (avail < 4) return null
                    ((p[offset + 1].toInt() and 0xFF) or ((p[offset + 2].toInt() and 0xFF) shl 8) or ((p[offset + 3].toInt() and 0xFF) shl 16)) * 4 to 4
                } else {
                    (first and 0x7F) * 4 to 1
                }
                if (payload <= 0) return 0
                if (avail < header + payload) null else header + payload
            }
            else -> {
                if (avail < 4) return null
                val payload = ((p[offset].toInt() and 0xFF) or ((p[offset + 1].toInt() and 0xFF) shl 8) or
                    ((p[offset + 2].toInt() and 0xFF) shl 16) or ((p[offset + 3].toInt() and 0x7F) shl 24))
                if (payload <= 0) return 0
                if (avail < 4 + payload) null else 4 + payload
            }
        }
    }
}
