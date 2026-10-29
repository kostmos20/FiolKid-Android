package com.fiolkid.app.engine

import com.fiolkid.app.Service
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Проверка доступа, как в версии для компьютера: открывается ли сайт сервиса через byedpi. Само приложение
 * в VPN не входит (иначе byedpi отправлял бы свои соединения сам себе), поэтому проверяем прямо через его SOCKS-порт.
 */
object Checks {
    private val targets = mapOf(
        Service.YouTube to listOf("https://www.youtube.com/generate_204", "https://redirector.googlevideo.com/report_mapping"),
        Service.Discord to listOf("https://discord.com/api/v9/gateway", "https://cdn.discordapp.com/"),
    )

    val services get() = targets.keys

    /** Пустой список — всё открылось; иначе — что не открылось и почему. */
    fun run(service: Service, port: Int): List<String> =
        targets.getValue(service).mapNotNull { url -> open(url, port) }

    private fun open(url: String, port: Int): String? {
        val host = URL(url).host
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val connection = URL(url).openConnection(proxy) as HttpsURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            try {
                // Любой ответ сервера (даже 404) — значит, соединение прошло DPI
                connection.responseCode
                null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            "$host: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
