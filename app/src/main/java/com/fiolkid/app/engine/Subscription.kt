package com.fiolkid.app.engine

import java.security.MessageDigest

/** Сервер из подписки — уже в виде outbound для sing-box. Ссылки и ключи наружу не выводить. */
data class ProxyServer(val protocol: String, val host: String, val port: Int, val name: String, val outbound: Map<String, Any?>) {
    /** Насколько сервер хорош «по умолчанию»: Reality обходит блокировки лучше всего. */
    val preference: Int
        get() = when {
            protocol == "vless" && (outbound["tls"] as? Map<*, *>)?.get("reality") != null -> 0
            protocol == "hysteria2" -> 1
            protocol == "trojan" || protocol == "vless" -> 2
            else -> 3
        }

    /** Постоянный отпечаток сервера — чтобы запомнить его, не храня адрес открыто. */
    val key: String
        get() = MessageDigest.getInstance("SHA-256").digest("$protocol|$host|$port|$name".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Без адреса и ключей — для журнала. */
    override fun toString() = "$protocol «$name»"
}

/** Что пришло по ссылке-подписке: название и срок — из заголовков ответа. */
data class ParsedSubscription(val title: String?, val expires: Long?, val servers: List<ProxyServer>, val skipped: Int)

/**
 * Разбор подписки, как в версии для компьютера (Subscription.cs): base64 или просто текст со ссылками
 * vless://, hysteria2://, trojan://, ss:// по одной в строке. Чего sing-box не умеет (xhttp, vmess, kcp,
 * плагины shadowsocks), пропускаем — и считаем, сколько пропустили.
 */
object SubscriptionParser {
    private val schemes = listOf("vless://", "hysteria2://", "hy2://", "trojan://", "ss://")

    // Отпечатки TLS, которые понимает sing-box (utls)
    private val fingerprints = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    /** Вставили ссылки серверов, а не адрес подписки. */
    fun isLinkList(text: String) = schemes.any { text.contains(it, ignoreCase = true) } && !text.trim().startsWith("http", ignoreCase = true)

    fun looksLikeSubscription(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true) || isLinkList(t)
    }

    fun parse(body: String, headers: Map<String, String> = emptyMap()): ParsedSubscription {
        val servers = mutableListOf<ProxyServer>()
        var skipped = 0
        for (line in decodeBody(body).split('\n').map { it.trim() }.filter { "://" in it }) {
            val server = parseLink(line)
            if (server == null) skipped++ else servers += server
        }
        val title = headers["profile-title"]?.let(::decodeTitle)
        val expires = headers["subscription-userinfo"]?.let { Regex("""expire=(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.takeIf { it > 0 }
        return ParsedSubscription(title, expires, servers, skipped)
    }

    /** Подписка обычно — base64 со ссылками; бывает и просто текст со ссылками. */
    internal fun decodeBody(body: String): String {
        val trimmed = body.trim()
        if ("://" in trimmed) return trimmed
        val compact = trimmed.replace(Regex("""\s+"""), "")
        return decodeBase64(compact)?.takeIf { "://" in it } ?: trimmed
    }

    private fun decodeTitle(value: String): String? =
        if (!value.startsWith("base64:", ignoreCase = true)) value else decodeBase64(value.substring(7))

    /** Одна ссылка → сервер. Null — протокол или транспорт, который sing-box не поддерживает, или битая ссылка. */
    fun parseLink(link: String): ProxyServer? = try {
        when (link.substringBefore("://").lowercase()) {
            "vless" -> parseVless(link)
            "trojan" -> parseTrojan(link)
            "hysteria2", "hy2" -> parseHysteria2(link)
            "ss" -> parseShadowsocks(link)
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun parseVless(link: String): ProxyServer? {
        val (user, host, port, query, name) = split(link)
        if (!Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""").matches(user)) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "vless", "server" to host, "server_port" to port, "uuid" to user, "packet_encoding" to "xudp",
        )
        val transport = query["type"] ?: "tcp"
        val flow = query["flow"]
        if (!flow.isNullOrEmpty() && (transport == "tcp" || transport == "raw")) outbound["flow"] = flow
        if (!addTls(outbound, query, host) || !addTransport(outbound, query, transport)) return null
        return ProxyServer("vless", host, port, name, outbound)
    }

    private fun parseTrojan(link: String): ProxyServer? {
        val (user, host, port, query, name) = split(link)
        if (user.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>("type" to "trojan", "server" to host, "server_port" to port, "password" to user)
        // У trojan TLS включён по умолчанию
        if ("security" !in query) query["security"] = "tls"
        if (!addTls(outbound, query, host) || !addTransport(outbound, query, query["type"] ?: "tcp")) return null
        return ProxyServer("trojan", host, port, name, outbound)
    }

    private fun parseHysteria2(link: String): ProxyServer? {
        val (user, host, port, query, name) = split(link)
        if (user.isEmpty() || "mport" in query) return null // «прыжки по портам» — без них обойдёмся
        val tls = linkedMapOf<String, Any?>("enabled" to true, "server_name" to (query["sni"] ?: host), "alpn" to listOf("h3"))
        if (query["insecure"] == "1") tls["insecure"] = true
        val outbound = linkedMapOf<String, Any?>(
            "type" to "hysteria2", "server" to host, "server_port" to port, "password" to user, "tls" to tls,
        )
        query["obfs"]?.takeIf { it.isNotEmpty() }?.let { obfs ->
            if (obfs != "salamander") return null
            outbound["obfs"] = mapOf("type" to "salamander", "password" to (query["obfs-password"] ?: ""))
        }
        return ProxyServer("hysteria2", host, port, name, outbound)
    }

    private fun parseShadowsocks(link: String): ProxyServer? {
        // ss://BASE64(метод:пароль)@хост:порт#имя или ss://BASE64(метод:пароль@хост:порт)#имя
        var body = link.substring(5)
        var name = ""
        body.indexOf('#').takeIf { it >= 0 }?.let {
            name = unescape(body.substring(it + 1))
            body = body.substring(0, it)
        }
        body.indexOf('?').takeIf { it >= 0 }?.let {
            if (body.substring(it).contains("plugin", ignoreCase = true)) return null // плагины sing-box не запускает
            body = body.substring(0, it)
        }
        body = body.trimEnd('/')

        val userInfo: String
        val hostPort: String
        var at = body.lastIndexOf('@')
        if (at >= 0) {
            val raw = unescape(body.substring(0, at))
            userInfo = decodeBase64(raw)?.takeIf { ':' in it } ?: raw
            hostPort = body.substring(at + 1)
        } else {
            val decoded = decodeBase64(body) ?: return null
            at = decoded.lastIndexOf('@')
            if (at < 0) return null
            userInfo = decoded.substring(0, at)
            hostPort = decoded.substring(at + 1)
        }
        val colon = userInfo.indexOf(':')
        val portColon = hostPort.lastIndexOf(':')
        val port = hostPort.substring(portColon + 1).toIntOrNull()
        if (colon <= 0 || portColon <= 0 || port == null) return null
        val host = hostPort.substring(0, portColon).trim('[', ']')
        val outbound = linkedMapOf<String, Any?>(
            "type" to "shadowsocks", "server" to host, "server_port" to port,
            "method" to userInfo.substring(0, colon), "password" to userInfo.substring(colon + 1),
        )
        return ProxyServer("shadowsocks", host, port, name, outbound)
    }

    /** TLS и Reality. False — режим, который мы не поддерживаем. */
    private fun addTls(outbound: MutableMap<String, Any?>, query: Map<String, String>, host: String): Boolean {
        val security = query["security"] ?: "none"
        if (security == "none" || security.isEmpty()) return true
        if (security != "tls" && security != "reality") return false

        val tls = linkedMapOf<String, Any?>(
            "enabled" to true,
            "server_name" to (query["sni"]?.takeIf { it.isNotEmpty() } ?: query["host"] ?: host),
        )
        query["alpn"]?.takeIf { it.isNotEmpty() }?.let { alpn -> tls["alpn"] = alpn.split(',').map { it.trim() } }
        if (query["allowInsecure"] == "1" || query["allowInsecure"] == "true") tls["insecure"] = true

        var fp = (query["fp"] ?: "").lowercase()
        if (security == "reality") {
            val publicKey = query["pbk"]?.takeIf { it.isNotEmpty() } ?: return false
            tls["reality"] = mapOf("enabled" to true, "public_key" to publicKey, "short_id" to (query["sid"] ?: ""))
            // Reality без uTLS не работает
            if (fp !in fingerprints) fp = "chrome"
        }
        if (fp in fingerprints) tls["utls"] = mapOf("enabled" to true, "fingerprint" to fp)
        outbound["tls"] = tls
        return true
    }

    /** Транспорт поверх TCP. False — такой sing-box не умеет (xhttp, kcp и т. п.). */
    private fun addTransport(outbound: MutableMap<String, Any?>, query: Map<String, String>, transport: String): Boolean {
        val host = query["host"]?.takeIf { it.isNotEmpty() }
        when (transport) {
            // «Маскировка под HTTP» у TCP — редкость, sing-box её не поддерживает
            "tcp", "raw", "" -> return (query["headerType"] ?: "none").let { it == "none" || it.isEmpty() }
            "grpc" -> outbound["transport"] = mapOf("type" to "grpc", "service_name" to (query["serviceName"] ?: ""))
            "ws" -> outbound["transport"] = linkedMapOf<String, Any?>("type" to "ws", "path" to (query["path"] ?: "/"))
                .apply { if (host != null) put("headers", mapOf("Host" to host)) }
            "httpupgrade" -> outbound["transport"] = linkedMapOf<String, Any?>("type" to "httpupgrade", "path" to (query["path"] ?: "/"))
                .apply { if (host != null) put("host", host) }
            "http", "h2" -> outbound["transport"] = linkedMapOf<String, Any?>("type" to "http", "path" to (query["path"] ?: "/"))
                .apply { if (host != null) put("host", host.split(',').map { it.trim() }) }
            else -> return false
        }
        return true
    }

    private data class Parts(val user: String, val host: String, val port: Int, val query: MutableMap<String, String>, val name: String)

    /** scheme://пользователь@хост:порт?параметры#имя — разбираем сами: в именах серверов бывают пробелы и «|». */
    private fun split(link: String): Parts {
        var rest = link.substringAfter("://")
        val name = if ('#' in rest) unescape(rest.substringAfter('#')).also { rest = rest.substringBefore('#') } else ""
        val queryText = if ('?' in rest) rest.substringAfter('?').also { rest = rest.substringBefore('?') } else ""
        rest = rest.trimEnd('/')
        val at = rest.lastIndexOf('@')
        val user = if (at >= 0) unescape(rest.substring(0, at)) else ""
        val hostPort = rest.substring(at + 1)
        val host: String
        val portText: String
        if (hostPort.startsWith("[")) {
            host = hostPort.substring(1, hostPort.indexOf(']'))
            portText = hostPort.substringAfter("]:", "")
        } else {
            host = hostPort.substringBeforeLast(':')
            portText = hostPort.substringAfterLast(':', "")
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("нет порта")
        if (host.isEmpty()) throw IllegalArgumentException("нет адреса")
        val query = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (pair in queryText.split('&').filter { it.isNotEmpty() }) {
            val key = unescape(pair.substringBefore('='))
            val value = if ('=' in pair) unescape(pair.substringAfter('=').replace('+', ' ')) else ""
            query.putIfAbsent(key, value)
        }
        return Parts(user, host, port, query, name)
    }

    /** %XX → символы (UTF-8); битые последовательности оставляем как есть. */
    private fun unescape(text: String): String {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray())
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    /** base64 и base64url как текст UTF-8; не текст — null. */
    internal fun decodeBase64(value: String): String? {
        val bytes = base64Bytes(value) ?: return null
        val text = String(bytes, Charsets.UTF_8)
        // Нечитаемое (не текст) — значит, это был не base64
        return text.takeIf { !it.contains('�') }
    }

    /** base64 и base64url, с «=» в конце и без (java.util.Base64 на Android есть только с версии 8). */
    internal fun base64Bytes(value: String): ByteArray? {
        val s = value.replace('-', '+').replace('_', '/').trimEnd('=')
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = abc.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        if (bits >= 6) return null // лишний символ — не base64
        return out.toByteArray()
    }
}

/** Простейшая запись в JSON — для конфига sing-box (org.json на компьютере в тестах недоступен). */
object Json {
    fun write(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Number -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
            "$indent  ${quote(k.toString())}: ${write(v, "$indent  ")}"
        }
        is Iterable<*> -> {
            val items = value.toList()
            if (items.isEmpty()) "[]"
            else if (items.all { it !is Map<*, *> && it !is Iterable<*> }) items.joinToString(", ", "[", "]") { write(it) }
            else items.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${write(it, "$indent  ")}" }
        }
        else -> quote(value.toString())
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
