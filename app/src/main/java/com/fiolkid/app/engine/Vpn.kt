package com.fiolkid.app.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Сервер подписки в списке настроек: имя, протокол и последний замер. Без адреса и ключей. */
data class ServerRow(val key: String, val name: String, val protocol: String, val score: ServerScore?)

/** Что показывать про ключ, подписку и VPN — как панели 01–03 на компьютере. Адреса и ключи сюда не попадают. */
data class VpnState(
    val hasKey: Boolean = false,
    val keyOk: Boolean = false,
    val keyText: String = "Ключа нет",
    val hasLink: Boolean = false,
    val linkOk: Boolean = false,
    val linkText: String = "Подписки нет",
    val servers: List<ServerRow> = emptyList(),
    /** Выбирать самый быстрый сервер сам (иначе — только [manual]). */
    val auto: Boolean = true,
    val manual: String? = null,
    /** Через какие серверы подключено сейчас. */
    val chosen: List<String> = emptyList(),
    val measuring: Boolean = false,
    val running: Boolean = false,
    /** Чем кончилась последняя попытка подключиться, если не вышло. */
    val error: String? = null,
    /** Название подписки — для «Подключено: …». */
    val profile: String? = null,
    /** На этом телефоне sing-box нет (процессор x86) — VPN недоступен. */
    val supported: Boolean = true,
)

/**
 * VPN по подписке, как на компьютере: sing-box (тот же 1.14.3) работает локальным прокси и сам раскладывает
 * соединения. Telegram со звонками, YouTube, нейросети и заблокированные сайты — через серверы подписки,
 * остальное — в byedpi, как и без подписки; Discord — в VPN только в запасном режиме, если через обход
 * не открылся. hev-socks5-tunnel тогда отдаёт трафик не в byedpi, а сюда.
 *
 * sing-box — отдельная программа: лежит в библиотеках приложения как libsingbox.so (только оттуда Android
 * разрешает запуск) и работает под тем же пользователем, что и FiolKid, — значит, тоже мимо нашего VPN.
 */
object Vpn {
    const val PORT = 10809
    private const val PREFS = "vpn"
    private const val USER_AGENT = "v2rayN/7.10.0" // как на компьютере: так сервисы отдают обычный список ссылок
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    /** Замер серверов устаревает за 6 часов — тогда перед подключением меряем заново. */
    private const val SCORES_MS = 6 * 60 * 60 * 1000L
    /** Сколько серверов держать в автовыборе без замера: больше — дольше и шумнее проверки. */
    private const val MAX_SERVERS = 20
    /** С замером — как на компьютере: три лучших. */
    private const val BEST = 3

    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private lateinit var files: File
    private lateinit var exe: File
    private var process: Process? = null
    private val output = ArrayDeque<String>()
    private var chosen: List<String> = emptyList()
    private var error: String? = null
    @Volatile
    private var measuring = false

    /** Запасной режим: Discord через обход не открылся — пускаем его через VPN, как на компьютере. */
    @Volatile
    var discordViaVpn = false

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        files = File(context.filesDir, "sing-box").apply { mkdirs() }
        exe = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
        Lists.load(context)
        killLeftover()
        publish()
    }

    private val ready get() = ::prefs.isInitialized
    val hasLink get() = ready && prefs.getString("source", null) != null
    val hasKey get() = ready && prefs.getString("key", null) != null
    /** Есть чем подключаться: подписка (ключ Amnezia на телефоне пока не подключается). */
    val canConnect get() = hasLink
    val running get() = process?.isAlive == true

    private fun parsed(): ParsedSubscription? {
        val body = prefs.getString("body", null) ?: return null
        val headers = prefs.getStringSet("headers", emptySet()).orEmpty()
            .associate { it.substringBefore('\n') to it.substringAfter('\n') }
        return runCatching { SubscriptionParser.parse(body, headers) }.getOrNull()
    }

    private fun scores(): Map<String, ServerScore> =
        prefs.getStringSet("scores", emptySet()).orEmpty().mapNotNull(ServerScore::parse).associateBy { it.key }

    fun publish() {
        if (!ready) return
        val sub = parsed()
        val scores = scores()
        val (linkOk, linkText) = linkState(sub)
        _state.value = VpnState(
            hasKey = hasKey,
            keyOk = false,
            keyText = if (hasKey) "Ключ сохранён, но на телефоне ключи Amnezia пока не подключаются — нужна подписка" else "Ключа нет",
            hasLink = hasLink,
            linkOk = linkOk,
            linkText = linkText,
            servers = sub?.servers.orEmpty().map { ServerRow(it.key, it.name, it.protocol, scores[it.key]) },
            auto = prefs.getBoolean("auto", true),
            manual = prefs.getString("manual", null),
            chosen = if (running) chosen else emptyList(),
            measuring = measuring,
            running = running,
            error = error,
            profile = sub?.title?.let { "подписка «$it»" } ?: "подписка",
            supported = exe.exists(),
        )
    }

    /** Как SubscriptionState на компьютере: название, сколько серверов, до какого числа. */
    private fun linkState(sub: ParsedSubscription?): Pair<Boolean, String> {
        if (!hasLink) return false to "Подписки нет"
        if (sub == null) return false to "Подписка вставлена, но ещё не скачалась"
        val title = sub.title?.takeIf { it.isNotEmpty() }?.let { "«$it»" } ?: ""
        val until = sub.expires?.let {
            ", до " + Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        } ?: ""
        val expired = sub.expires != null && sub.expires * 1000 < System.currentTimeMillis()
        return (sub.servers.isNotEmpty() && !expired) to
            if (expired) "Подписка $title: срок закончился$until" else "Подписка $title: серверов ${sub.servers.size}$until"
    }

    /**
     * Вставили из буфера: ключ Amnezia (vpn://) или подписку (https://… или ссылки серверов).
     * Возвращает, что сказать человеку.
     */
    fun paste(text: String): String {
        val source = text.trim()
        if (source.startsWith("vpn://", ignoreCase = true)) return saveKey(source)
        if (source.startsWith("happ://")) return "Это зашифрованная ссылка Happ — попроси у сервиса обычную ссылку-подписку (https://…)"
        if (!SubscriptionParser.looksLikeSubscription(source)) return "Это не ключ и не подписка: нужен vpn://… или https://…"
        return saveLink(source)
    }

    private fun saveKey(key: String): String {
        val problem = AmneziaKey.check(key)
        if (problem != null) return problem
        prefs.edit().putString("key", key.filterNot { it.isWhitespace() }).apply()
        Engine.note("Ключ Amnezia сохранён")
        publish()
        return "Ключ сохранён. На телефоне ключи Amnezia пока не подключаются — добавь подписку"
    }

    private fun saveLink(source: String): String = try {
        val (body, headers) = download(source)
        val sub = SubscriptionParser.parse(body, headers)
        if (sub.servers.isEmpty()) {
            if (sub.skipped > 0) "В подписке ${sub.skipped} серверов, но ни один не подходит (xhttp и т. п.)"
            else "По ссылке не нашлось ни одного сервера"
        } else {
            save(source, body, headers)
            // Новый список — старые замеры и выбор сервера ни к чему
            prefs.edit().remove("scores").remove("scoresAt").remove("manual").apply()
            Engine.note("Подписка добавлена: серверов ${sub.servers.size}" + if (sub.skipped > 0) ", пропущено ${sub.skipped}" else "")
            publish()
            "Подписка добавлена: серверов ${sub.servers.size}"
        }
    } catch (e: Exception) {
        "Подписка не скачалась: ${e.message ?: e.javaClass.simpleName}"
    }

    fun deleteKey() {
        prefs.edit().remove("key").apply()
        Engine.note("Ключ удалён")
        publish()
    }

    fun deleteLink() {
        prefs.edit().remove("source").remove("body").remove("headers").remove("updated")
            .remove("scores").remove("scoresAt").remove("manual").apply()
        Engine.note("Подписка удалена")
        publish()
    }

    /** «Выбирать самый быстрый» или подключаться только к [manual]. */
    fun setChoice(auto: Boolean, manual: String?) {
        prefs.edit().putBoolean("auto", auto).putString("manual", manual).apply()
        publish()
    }

    /** Раз в 6 часов — обновить список серверов. Не вышло — работаем со старым. True — список обновился. */
    fun refreshIfDue(force: Boolean = false): Boolean {
        val source = (if (ready) prefs.getString("source", null) else null) ?: return false
        if (!force && System.currentTimeMillis() - prefs.getLong("updated", 0) < REFRESH_MS) return false
        if (SubscriptionParser.isLinkList(source)) return false
        try {
            val (body, headers) = download(source)
            if (SubscriptionParser.parse(body, headers).servers.isNotEmpty()) {
                save(source, body, headers)
                publish()
                return true
            }
        } catch (e: Exception) {
            Engine.note("Подписка не обновилась: ${e.message ?: e.javaClass.simpleName}")
        }
        return false
    }

    private fun save(source: String, body: String, headers: Map<String, String>) {
        prefs.edit()
            .putString("source", source)
            .putString("body", body)
            .putStringSet("headers", headers.map { (k, v) -> "$k\n$v" }.toSet())
            .putLong("updated", System.currentTimeMillis())
            .apply()
    }

    /** Ссылки серверов разбираем как есть, подписку скачиваем (само приложение — мимо VPN, напрямую). */
    private fun download(source: String): Pair<String, Map<String, String>> {
        if (SubscriptionParser.isLinkList(source)) return source to emptyMap()
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("сервис ответил $code")
            val headers = connection.headerFields.filterKeys { it != null }
                .map { (k, v) -> k.lowercase() to v.joinToString(",") }.toMap()
            return connection.inputStream.bufferedReader().readText() to headers
        } finally {
            connection.disconnect()
        }
    }

    /** Пора ли мерить серверы: выбор самый быстрый, а замера нет или он старый. */
    val measureDue
        get() = hasLink && prefs.getBoolean("auto", true) && !measuring &&
            System.currentTimeMillis() - prefs.getLong("scoresAt", 0) > SCORES_MS

    /** Замер серверов подписки (задержка, скорость, страна выхода) — как «Замерить серверы» на компьютере. */
    fun measure(): Boolean {
        if (measuring || !exe.exists()) return false
        val servers = parsed()?.servers.orEmpty().take(ServerBenchmark.MAX_SERVERS)
        if (servers.isEmpty()) return false
        measuring = true
        publish()
        return try {
            val scores = ServerBenchmark(exe, files).run(servers)
            // Не ответил ни один — скорее всего, пропала сеть: прошлый замер полезнее
            if (scores.none { it.works }) {
                Engine.note("Замер серверов: не ответил ни один из ${scores.size} — похоже, нет сети; оставляю прошлый замер")
                return false
            }
            prefs.edit().putStringSet("scores", scores.map { it.line() }.toSet()).putLong("scoresAt", System.currentTimeMillis()).apply()
            val best = scores.filter { it.works }.maxByOrNull { it.score }
            Engine.note("Замер серверов: отвечают ${scores.count { it.works }} из ${scores.size}" +
                (best?.let { s -> "; лучший «${servers.first { it.key == s.key }.name}» — ${s.text}" } ?: ""))
            true
        } catch (e: Exception) {
            Engine.note("Замер серверов не удался: ${e.message}")
            false
        } finally {
            measuring = false
            publish()
        }
    }

    /**
     * Через какие серверы подключаться: выбранный вручную, три лучших по замеру или все (sing-box выберет сам).
     * На телефоне сначала серверы по TCP (VLESS, Trojan, Shadowsocks): Hysteria2 идёт по UDP, а его мобильные
     * операторы режут — на замере он быстрый, а через полчаса не отвечает. Hysteria2 — только если TCP не отвечают.
     */
    internal fun pick(servers: List<ProxyServer>, scores: Map<String, ServerScore>, auto: Boolean, manual: String?): List<ProxyServer> {
        if (!auto) servers.firstOrNull { it.key == manual }?.let { return listOf(it) }
        val working = servers.filter { scores[it.key]?.works == true }.sortedByDescending { scores.getValue(it.key).score }
        val good = working.filter { !scores.getValue(it.key).badCountry }.ifEmpty { working }
        val best = good.filter { !it.udp }.ifEmpty { good }.take(BEST)
        return best.ifEmpty { select(servers) }
    }

    /**
     * Запустить sing-box и дождаться его порта. [dpiPort] — byedpi, куда идёт всё, что не через VPN;
     * [dns] — DNS сети телефона, через него sing-box узнаёт адреса серверов подписки.
     */
    fun start(dpiPort: Int, dns: String?): Boolean {
        stop()
        error = null
        if (!exe.exists()) return false
        val servers = parsed()?.servers.orEmpty()
        if (servers.isEmpty()) return false
        val use = pick(servers, scores(), prefs.getBoolean("auto", true), prefs.getString("manual", null))
        val config = File(files, "config.json")
        config.writeText(SingBoxConfig.build(use, PORT, dpiPort, dns ?: "77.88.8.8", discordViaVpn))
        val started = try {
            ProcessBuilder(exe.absolutePath, "run", "-c", config.absolutePath, "-D", files.absolutePath, "--disable-color")
                .directory(files).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return fail("sing-box не запустился: ${e.message}")
        }
        process = started
        rememberPid(started)
        synchronized(output) { output.clear() }
        thread(name = "sing-box-log", isDaemon = true) {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        output.addLast(line)
                        while (output.size > 20) output.removeFirst()
                    }
                }
            }
        }
        repeat(50) {
            if (!started.isAlive) {
                process = null
                return fail("sing-box завершился: ${lastOutput()}")
            }
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 200) }
                chosen = use.map { it.name }
                Engine.note("VPN подключён через: ${chosen.joinToString()}" + if (discordViaVpn) " (Discord — тоже через VPN)" else "")
                publish()
                return true
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        stop()
        return fail("sing-box не открыл порт: ${lastOutput()}")
    }

    private fun fail(message: String): Boolean {
        error = message
        Engine.note(message)
        publish()
        return false
    }

    fun stop() {
        val p = process ?: return
        p.destroy()
        runCatching { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null
        File(files, "pid").delete()
        publish()
    }

    /** Последние строки sing-box — без адресов серверов (их он пишет только на уровне debug). */
    private fun lastOutput() = synchronized(output) { output.takeLast(3).joinToString(" | ").take(300) }.ifEmpty { "без сообщений" }

    /**
     * Если прошлый раз Android убил FiolKid вместе с VpnService, sing-box мог остаться жить и держать порт.
     * Номер его процесса записан в файл — того же пользователя, значит, остановить его можно.
     */
    private fun killLeftover() {
        val file = File(files, "pid")
        val pid = file.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
        if (cmdline != null && "libsingbox" in cmdline) android.os.Process.killProcess(pid)
        file.delete()
    }

    private fun rememberPid(p: Process) {
        val pid = runCatching {
            p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
        }.getOrNull() ?: return
        File(files, "pid").writeText(pid.toString())
    }

    /** Без замера: сначала VLESS Reality, потом Hysteria2, потом остальные; внутри группы — случайно. */
    fun select(servers: List<ProxyServer>): List<ProxyServer> =
        servers.groupBy { it.preference }.toSortedMap().values.flatMap { it.shuffled() }.take(MAX_SERVERS)

    /** Списки адресов из assets/vpn — те же, что в версии для компьютера. */
    object Lists {
        var telegramCidr: List<String> = emptyList()
        var blockedCidr: List<String> = emptyList()
        var telegram: List<String> = emptyList()
        var ai: List<String> = emptyList()
        var blocked: List<String> = emptyList()
        var discord: List<String> = emptyList()

        val youTube = listOf(
            "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com", "youtubekids.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "youtubeembeddedplayer.googleapis.com", "jnn-pa.googleapis.com",
            "wide-youtube.l.google.com", "youtube-ui.l.google.com", "yt.be",
        )

        fun load(context: Context) {
            fun read(name: String) = runCatching {
                context.assets.open("vpn/$name").bufferedReader().readLines()
            }.getOrDefault(emptyList()).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            telegramCidr = read("telegram-cidr.txt")
            blockedCidr = read("blocked-cidr.txt")
            telegram = read("telegram-domains.txt")
            ai = read("ai-domains.txt")
            blocked = read("blocked-domains.txt")
            discord = (read("discord-domains.txt") + blocked.filter { "discord" in it }).distinct()
        }
    }
}

/** Ключ Amnezia: «vpn://» + base64url от qCompress(JSON) — как KeyParser на компьютере. */
object AmneziaKey {
    /** Null — ключ читается; иначе — что с ним не так (тексты как на компьютере). */
    fun check(key: String): String? {
        val compact = key.filterNot { it.isWhitespace() }
        if (!compact.startsWith("vpn://", ignoreCase = true)) return "Это не ключ: он должен начинаться с vpn://"
        val payload = compact.substring(6).trimEnd('=')
        if (payload.isEmpty()) return "Ключ пустой: после vpn:// ничего нет."
        val raw = SubscriptionParser.base64Bytes(payload)
            ?: return "Ключ повреждён: в нём есть лишние символы. Скопируй его ещё раз целиком."
        val json = inflate(raw) ?: String(raw, Charsets.UTF_8)
        if (!json.trimStart().startsWith("{")) return "Ключ повреждён или обрезан. Скопируй его ещё раз целиком."
        if ("\"containers\"" !in json) return "В ключе нет настроек подключения. Попроси у знакомого другой ключ."
        return null
    }

    private fun inflate(data: ByteArray): String? {
        if (data.size <= 4) return null
        return runCatching {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(data, 4, data.size - 4)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > 1024 * 1024) return null
            }
            inflater.end()
            out.toString("UTF-8").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

/** Конфиг sing-box для телефона: локальный прокси, раскладка по спискам, автовыбор сервера. */
object SingBoxConfig {
    fun build(servers: List<ProxyServer>, port: Int, dpiPort: Int, dns: String, discordViaVpn: Boolean): String {
        require(servers.isNotEmpty()) { "Нет ни одного сервера" }
        val lists = Vpn.Lists
        val tags = servers.indices.map { "s$it" }
        val outbounds = mutableListOf<Any?>(
            mapOf(
                "type" to "urltest", "tag" to "vpn", "outbounds" to tags,
                "url" to "https://www.gstatic.com/generate_204", "interval" to "3m",
                // Переходить на другой сервер, только если он быстрее на заметную величину
                "tolerance" to 80,
            ),
        )
        servers.forEachIndexed { i, server -> outbounds += server.outbound + ("tag" to tags[i]) }
        outbounds += mapOf("type" to "socks", "tag" to "dpi", "server" to "127.0.0.1", "server_port" to dpiPort, "version" to "5")

        fun suffixes(list: List<String>) = list.map { it.trimStart('.') }.filter { it.isNotEmpty() && '/' !in it }.distinct()
        val rules = mutableListOf<Any?>(
            // Имя сайта — из начала TLS-соединения: адреса приходят из VPN уже числами
            mapOf("action" to "sniff"),
            // DNS — как без подписки, через byedpi
            mapOf("port" to 53, "outbound" to "dpi"),
            mapOf("protocol" to "bittorrent", "outbound" to "dpi"),
        )
        // Discord, как на компьютере, — через обход; в VPN только в запасном режиме
        if (!discordViaVpn) rules += mapOf("domain_suffix" to suffixes(lists.discord), "network" to "tcp", "outbound" to "dpi")
        rules += mapOf("ip_cidr" to (lists.telegramCidr + lists.blockedCidr).distinct(), "outbound" to "vpn")
        rules += mapOf(
            "domain_suffix" to suffixes(lists.telegram + lists.ai + lists.blocked + lists.youTube + lists.discord),
            "outbound" to "vpn",
        )
        // Звонки Telegram и WhatsApp, голос Discord — UDP на меняющиеся адреса: весь UDP (кроме DNS) — через VPN
        rules += mapOf("network" to "udp", "outbound" to "vpn")

        val config = mapOf(
            "log" to mapOf("level" to "warn"),
            "dns" to mapOf("servers" to listOf(mapOf("type" to "udp", "tag" to "net", "server" to dns))),
            "inbounds" to listOf(mapOf("type" to "mixed", "tag" to "in", "listen" to "127.0.0.1", "listen_port" to port)),
            "outbounds" to outbounds,
            "route" to mapOf("rules" to rules, "final" to "dpi", "default_domain_resolver" to "net"),
        )
        return Json.write(config)
    }
}
