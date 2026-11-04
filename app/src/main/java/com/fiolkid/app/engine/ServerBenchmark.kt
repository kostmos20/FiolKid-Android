package com.fiolkid.app.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Результат замера одного сервера подписки. Без адреса и ключей — можно показывать и хранить. */
data class ServerScore(val key: String, val pingMs: Long?, val mbps: Double?, val country: String?, val problem: String?) {
    val works get() = pingMs != null

    /** Нейросети с него работать не будут: выходит в интернет из России и т. п. */
    val badCountry get() = country != null && country in Checks.aiBlocked

    /** Чем больше, тем лучше: скорость с поправкой на задержку; без замера скорости — после всех, по задержке. */
    val score: Double
        get() = when {
            pingMs == null -> 0.0
            mbps != null -> 1000 + mbps / (1 + pingMs / 300.0)
            else -> 1000.0 / (1 + pingMs)
        }

    /** Как на компьютере: «84 мс · 37.5 Мбит/с · NL» или что с сервером не так. */
    val text: String
        get() = if (!works) problem ?: "не отвечает"
        else listOfNotNull("$pingMs мс", mbps?.let { String.format(Locale.US, "%.1f Мбит/с", it) }, country).joinToString(" · ")

    fun line() = listOf(key, pingMs ?: "", mbps ?: "", country ?: "", problem ?: "").joinToString("|")

    companion object {
        fun parse(line: String): ServerScore? {
            val p = line.split('|')
            if (p.size != 5) return null
            return ServerScore(p[0], p[1].toLongOrNull(), p[2].toDoubleOrNull(), p[3].ifEmpty { null }, p[4].ifEmpty { null })
        }
    }
}

/**
 * Замер серверов подписки, как на компьютере (ServerBenchmark.cs): один временный sing-box, на каждый сервер —
 * свой локальный прокси. Сначала у всех сразу задержку и страну выхода, потом по очереди скорость скачивания
 * у лучших по задержке (одновременно мерить скорость нельзя — серверы делили бы одну линию).
 */
class ServerBenchmark(private val exe: File, private val dir: File) {
    companion object {
        const val MAX_SERVERS = 40
        private const val SPEED_TEST_COUNT = 5
        private const val SPEED_BYTES = 4_000_000
        private const val SPEED_LIMIT_MS = 6_000L
        private const val TIMEOUT_MS = 6_000
        private const val PING_URL = "https://www.gstatic.com/generate_204"
        private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=$SPEED_BYTES"
    }

    fun run(servers: List<ProxyServer>): List<ServerScore> {
        val list = servers.take(MAX_SERVERS)
        val ports = freePorts(list.size)
        val config = File(dir, "benchmark.json")
        config.writeText(buildConfig(list, ports))
        val process = ProcessBuilder(exe.absolutePath, "run", "-c", config.absolutePath, "--disable-color")
            .directory(dir).redirectErrorStream(true).start()
        // Вывод читаем, чтобы sing-box не встал на заполненном канале
        Thread { runCatching { val b = ByteArray(4096); while (process.inputStream.read(b) >= 0) Unit } }.apply { isDaemon = true }.start()
        try {
            waitForPort(process, ports.last())
            val pool = Executors.newFixedThreadPool(8)
            val scores = try {
                list.mapIndexed { i, server -> pool.submit(Callable { ping(server, ports[i]) }) }.map { it.get() }.toMutableList()
            } finally {
                pool.shutdownNow()
            }
            // Скорость — у лучших по задержке, по очереди; из «плохих» стран — в последнюю очередь
            scores.withIndex().filter { it.value.works }
                .sortedWith(compareBy({ it.value.badCountry }, { it.value.pingMs }))
                .take(SPEED_TEST_COUNT)
                .forEach { (i, score) -> scores[i] = score.copy(mbps = speed(ports[i])) }
            return scores
        } finally {
            process.destroy()
            runCatching { if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly() }
            config.delete() // в нём пароли серверов
        }
    }

    internal fun buildConfig(servers: List<ProxyServer>, ports: List<Int>): String {
        val inbounds = servers.indices.map { mapOf("type" to "mixed", "tag" to "in$it", "listen" to "127.0.0.1", "listen_port" to ports[it]) }
        val outbounds = servers.mapIndexed { i, s -> s.outbound + ("tag" to "s$i") } + mapOf("type" to "direct", "tag" to "direct")
        val rules = servers.indices.map { mapOf("inbound" to listOf("in$it"), "action" to "route", "outbound" to "s$it") }
        return Json.write(
            mapOf(
                "log" to mapOf("level" to "error"),
                "dns" to mapOf("servers" to listOf(mapOf("type" to "udp", "tag" to "net", "server" to "77.88.8.8"))),
                "inbounds" to inbounds,
                "outbounds" to outbounds,
                "route" to mapOf("rules" to rules, "final" to "direct", "default_domain_resolver" to "net"),
            ),
        )
    }

    private fun open(url: String, port: Int, timeout: Int): HttpURLConnection {
        val connection = URL(url).openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        // Каждый раз новое соединение — меряем сервер целиком, как на компьютере
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
        return connection
    }

    private fun ping(server: ProxyServer, port: Int): ServerScore {
        var best: Long? = null
        var problem: String? = null
        // Первый запрос дольше (рукопожатие с сервером), поэтому три раза и лучшее время
        for (attempt in 0 until 3) {
            val started = System.nanoTime()
            try {
                val connection = open(PING_URL, port, TIMEOUT_MS)
                try {
                    val code = connection.responseCode
                    // Не дозвонившись до сервера, sing-box сам отвечает ошибкой — это не ответ сайта
                    if (code !in 200..399) throw java.io.IOException("ответ $code")
                } finally {
                    connection.disconnect()
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                if (best == null || ms < best) best = ms
            } catch (e: Exception) {
                problem = if (e is java.net.SocketTimeoutException) "не ответил" else "не подключается"
                if (best == null && attempt == 1) break // два провала подряд — не тратим время
            }
        }
        var country: String? = null
        if (best != null) runCatching {
            val connection = open(TRACE_URL, port, TIMEOUT_MS)
            try {
                country = Checks.parseCountry(connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }
        return ServerScore(server.key, best, null, country, if (best == null) problem else null)
    }

    private fun speed(port: Int): Double? = try {
        val connection = open(SPEED_URL, port, TIMEOUT_MS + 4000)
        try {
            if (connection.responseCode !in 200..299) null
            else {
                val input = connection.inputStream
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                val started = System.nanoTime()
                val deadline = started + SPEED_LIMIT_MS * 1_000_000
                // Не скачалось за отведённое время — считаем по тому, что успело
                while (System.nanoTime() < deadline) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                }
                val seconds = maxOf((System.nanoTime() - started) / 1e9, 0.05)
                if (total == 0L) null else Math.round(total * 8 / seconds / 100_000.0) / 10.0
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun waitForPort(process: Process, port: Int) {
        repeat(50) {
            if (!process.isAlive) throw IllegalStateException("sing-box для замера завершился")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                return
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        throw IllegalStateException("sing-box для замера не запустился за 10 с")
    }

    private fun freePorts(count: Int): List<Int> {
        val sockets = List(count) { ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) }
        return sockets.map { it.localPort }.also { sockets.forEach(ServerSocket::close) }
    }
}
