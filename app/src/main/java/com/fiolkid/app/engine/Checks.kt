package com.fiolkid.app.engine

import com.fiolkid.app.Look
import com.fiolkid.app.Service
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Итог проверки: что открылось и за сколько. */
data class CheckResult(val problems: Map<Service, List<String>>, val passed: Int, val total: Int, val seconds: Double) {
    val looks: Map<Service, Look>
        get() = problems.mapValues { (_, list) -> if (list.isEmpty()) Look.Working else Look.NotWorking }
    val allPassed get() = passed == total
}

/**
 * Проверка доступа, как в версии для компьютера: открывается ли сайт сервиса через byedpi. Само приложение
 * в VPN не входит (иначе byedpi отправлял бы свои соединения сам себе), поэтому проверяем прямо через его SOCKS-порт.
 * Все адреса проверяются одновременно; время — пока не ответит последний.
 */
object Checks {
    /** Дольше, чем byedpi ждёт до запасной части способа (-T 3) плюс её попытка. */
    const val TIMEOUT_MS = 10_000
    private val targets = mapOf(
        Service.YouTube to listOf("https://www.youtube.com/generate_204", "https://redirector.googlevideo.com/report_mapping"),
        // Приложение Discord держит постоянное соединение с gateway: без него оно висит на загрузке,
        // даже если discord.com открывается
        Service.Discord to listOf("https://discord.com/api/v9/gateway", "https://cdn.discordapp.com/", "wss://gateway.discord.gg/?v=10&encoding=json"),
    )

    val services get() = targets.keys

    /** [port] — SOCKS-порт byedpi; null — напрямую (тесты на компьютере). */
    fun run(port: Int?): CheckResult {
        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(targets.values.sumOf { it.size })
        try {
            val jobs = targets.flatMap { (service, urls) -> urls.map { url -> service to pool.submit(Callable { open(url, port) }) } }
            val problems = targets.keys.associateWith { service ->
                jobs.filter { it.first == service }.mapNotNull { it.second.get() }
            }
            val total = jobs.size
            val passed = total - problems.values.sumOf { it.size }
            return CheckResult(problems, passed, total, (System.nanoTime() - started) / 1e9)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun open(url: String, port: Int?): String? {
        val host = URL(url.replace("wss://", "https://")).host
        return try {
            if (url.startsWith("wss://")) return gateway(url, port)
            val proxy = if (port == null) Proxy.NO_PROXY else Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val connection = URL(url).openConnection(proxy) as HttpsURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            try {
                // Любой ответ сервера (даже 404) — значит, соединение прошло DPI
                connection.responseCode
                null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            "$host: ${if ("timeout" in reason.lowercase() || "timed out" in reason) "нет ответа за ${TIMEOUT_MS / 1000} с" else reason}"
        }
    }

    /** WebSocket к gateway Discord: проходит, если сервер прислал первое сообщение (HELLO, op 10). */
    private fun gateway(url: String, port: Int?): String? {
        val address = URL(url.replace("wss://", "https://"))
        val ws = TgWebSocket.connect(address.host, address.host, address.file, timeoutMs = TIMEOUT_MS, socks = port, protocol = null)
        try {
            ws.timeout(TIMEOUT_MS)
            val hello = ws.receive()?.toString(Charsets.UTF_8) ?: return "${address.host}: соединение закрыто"
            return if ("\"op\":10" in hello) null else "${address.host}: неожиданный ответ"
        } finally {
            ws.close()
        }
    }
}
