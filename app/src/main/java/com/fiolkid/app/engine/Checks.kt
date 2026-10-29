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
    private val targets = mapOf(
        Service.YouTube to listOf("https://www.youtube.com/generate_204", "https://redirector.googlevideo.com/report_mapping"),
        Service.Discord to listOf("https://discord.com/api/v9/gateway", "https://cdn.discordapp.com/"),
    )

    val services get() = targets.keys

    fun run(port: Int): CheckResult {
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

    private fun open(url: String, port: Int): String? {
        val host = URL(url).host
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val connection = URL(url).openConnection(proxy) as HttpsURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
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
            "$host: ${if ("timeout" in reason.lowercase() || "timed out" in reason) "нет ответа за 7 с" else reason}"
        }
    }
}
