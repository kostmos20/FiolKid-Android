package com.fiolkid.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Разбор подписки — на тех же выдуманных серверах, что и тесты версии для компьютера (SubscriptionTests.cs). */
class SubscriptionTest {
    private val uuid = "a3482e88-686a-4a58-8126-99c9df64b7bf"
    private val links = listOf(
        "vless://$uuid@203.0.113.10:443?type=tcp&security=reality&flow=xtls-rprx-vision&fp=edge&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=0123abcd&sni=www.microsoft.com&encryption=none&headerType=none#%F0%9F%87%B3%F0%9F%87%B1%20Netherlands",
        "vless://$uuid@grpc.example.com:443?type=grpc&security=tls&serviceName=svc&mode=gun&fp=qq&sni=grpc.example.com&encryption=none#Germany",
        "hysteria2://hy-secret@203.0.113.11:8443?sni=hy.example.com&obfs=salamander&obfs-password=obfs-secret#Finland",
        "trojan://trojan-secret@203.0.113.12:443?type=ws&path=%2Fws&host=cdn.example.com&sni=cdn.example.com#Poland",
        "ss://YWVzLTI1Ni1nY206c3Mtc2VjcmV0@203.0.113.13:8388#Sweden",
        "vless://$uuid@203.0.113.14:443?type=xhttp&security=reality&pbk=abc#Unsupported-xhttp",
        "vmess://eyJ2IjoiMiJ9#Unsupported-vmess",
    )

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun base64SubscriptionWithServiceHeaders() {
        val headers = mapOf(
            "profile-title" to "base64:" + base64("🔥 Quattro VPN"),
            "subscription-userinfo" to "upload=1; download=2; total=3; expire=1793712905",
        )
        val sub = SubscriptionParser.parse(base64(links.joinToString("\n")), headers)
        assertEquals("🔥 Quattro VPN", sub.title)
        assertEquals(1793712905L, sub.expires)
        assertEquals(listOf("vless", "vless", "hysteria2", "trojan", "shadowsocks"), sub.servers.map { it.protocol })
        assertEquals(2, sub.skipped) // xhttp и vmess sing-box не берём
    }

    @Test
    fun plainTextAndWrappedBase64() {
        assertEquals(5, SubscriptionParser.parse(links.joinToString("\r\n")).servers.size)
        val wrapped = base64(links.joinToString("\n")).chunked(76).joinToString("\n")
        assertEquals(5, SubscriptionParser.parse(wrapped).servers.size)
    }

    @Test
    fun vlessRealityBecomesOutbound() {
        val server = SubscriptionParser.parseLink(links[0])!!
        val o = server.outbound
        assertEquals("🇳🇱 Netherlands", server.name)
        assertEquals("vless", o["type"])
        assertEquals("203.0.113.10", o["server"])
        assertEquals(443, o["server_port"])
        assertEquals(uuid, o["uuid"])
        assertEquals("xtls-rprx-vision", o["flow"])
        val tls = o["tls"] as Map<*, *>
        assertEquals("www.microsoft.com", tls["server_name"])
        assertEquals("edge", (tls["utls"] as Map<*, *>)["fingerprint"])
        assertEquals("jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0", (tls["reality"] as Map<*, *>)["public_key"])
        assertEquals("0123abcd", (tls["reality"] as Map<*, *>)["short_id"])
        assertNull(o["transport"])
        assertEquals(0, server.preference)
    }

    @Test
    fun grpcHysteriaTrojanShadowsocks() {
        val grpc = SubscriptionParser.parseLink(links[1])!!.outbound
        assertEquals("grpc", (grpc["transport"] as Map<*, *>)["type"])
        assertEquals("svc", (grpc["transport"] as Map<*, *>)["service_name"])
        assertNull(grpc["flow"])

        val hy = SubscriptionParser.parseLink(links[2])!!.outbound
        assertEquals("hy-secret", hy["password"])
        assertEquals("obfs-secret", (hy["obfs"] as Map<*, *>)["password"])
        assertEquals("hy.example.com", (hy["tls"] as Map<*, *>)["server_name"])

        val trojan = SubscriptionParser.parseLink(links[3])!!.outbound
        assertEquals("/ws", (trojan["transport"] as Map<*, *>)["path"])
        assertEquals("cdn.example.com", ((trojan["transport"] as Map<*, *>)["headers"] as Map<*, *>)["Host"])
        assertEquals(true, (trojan["tls"] as Map<*, *>)["enabled"])

        val ss = SubscriptionParser.parseLink(links[4])!!.outbound
        assertEquals("aes-256-gcm", ss["method"])
        assertEquals("ss-secret", ss["password"])
        val legacy = SubscriptionParser.parseLink("ss://" + base64("aes-256-gcm:pw@203.0.113.15:8388") + "#old")!!
        assertEquals("203.0.113.15", legacy.host)
        assertEquals(8388, legacy.port)
    }

    @Test
    fun brokenOrUnsupportedLinksAreSkipped() {
        listOf(
            "vless://not-a-uuid@host:443?security=none",
            "vless://$uuid@host:443?security=reality", // без ключа Reality
            "hysteria2://pw@host:443?mport=1000-2000",
            "ss://bWV0aG9k@host:8388?plugin=obfs-local",
            "vless://$uuid@host?security=none", // без порта
            "garbage",
        ).forEach { assertNull(it, SubscriptionParser.parseLink(it)) }
    }

    @Test
    fun recognizesWhatWasPasted() {
        assertTrue(SubscriptionParser.looksLikeSubscription("  https://sub.example.com/abc  "))
        assertTrue(SubscriptionParser.looksLikeSubscription(links[0]))
        assertTrue(!SubscriptionParser.looksLikeSubscription("просто текст"))
        assertTrue(SubscriptionParser.isLinkList(links.joinToString("\n")))
        assertTrue(!SubscriptionParser.isLinkList("https://sub.example.com/?vless://"))
    }

    @Test
    fun countryFromCloudflareTrace() {
        assertEquals("NL", Checks.parseCountry("fl=1\nip=203.0.113.1\nloc=NL\ntls=TLSv1.3\n"))
        assertNull(Checks.parseCountry("fl=1\n"))
    }

    @Test
    fun serverChoiceLikeComputer() {
        val servers = SubscriptionParser.parse(links.joinToString("\n")).servers
        val (a, b, c, d, e) = servers
        val scores = mapOf(
            a.key to ServerScore(a.key, 80, 30.0, "NL", null),
            b.key to ServerScore(b.key, 40, 60.0, "RU", null), // быстрый, но из России — для нейросетей не годится
            c.key to ServerScore(c.key, 120, 10.0, "FI", null),
            d.key to ServerScore(d.key, 90, 20.0, "DE", null),
            e.key to ServerScore(e.key, null, null, null, "не отвечает"),
        )
        assertEquals(listOf(a, d, c), Vpn.pick(servers, scores, auto = true, manual = null))
        assertEquals(listOf(b), Vpn.pick(servers, scores, auto = false, manual = b.key))
        // Без замера — все (sing-box выберет сам)
        assertEquals(servers.toSet(), Vpn.pick(servers, emptyMap(), auto = true, manual = null).toSet())
        val line = scores.getValue(a.key).line()
        assertEquals(scores[a.key], ServerScore.parse(line))
        assertEquals("80 мс · 30.0 Мбит/с · NL", scores.getValue(a.key).text)
    }

    @Test
    fun amneziaKeyIsChecked() {
        // Как ключи Amnezia: 4 байта длины и zlib от JSON, всё в base64url
        val json = """{"containers":[{"container":"amnezia-awg"}],"defaultContainer":"amnezia-awg"}""".toByteArray()
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(json) }
        }.toByteArray()
        val payload = java.nio.ByteBuffer.allocate(4).putInt(json.size).array() + zip
        val key = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        assertNull(AmneziaKey.check(key))
        assertNull(AmneziaKey.check(key.chunked(40).joinToString("\n"))) // мессенджеры переносят строки
        assertEquals("Это не ключ: он должен начинаться с vpn://", AmneziaKey.check("https://x"))
        assertTrue(AmneziaKey.check("vpn://" + Base64.getUrlEncoder().encodeToString("{}".toByteArray()))!!.startsWith("В ключе нет"))
    }

    /** Конфиг замера серверов тоже проверяет настоящий sing-box. */
    @Test
    fun singBoxAcceptsBenchmarkConfig() {
        val servers = SubscriptionParser.parse(links.joinToString("\n")).servers
        val config = ServerBenchmark(File("."), File(".")).buildConfig(servers, servers.indices.map { 30000 + it })
        check(config)
    }

    private fun check(config: String) {
        val exe = File("../../PC/dist/sing-box/sing-box.exe").takeIf { it.exists() } ?: return
        val file = File.createTempFile("sing-box", ".json").apply { writeText(config) }
        try {
            val process = ProcessBuilder(exe.absolutePath, "check", "-c", file.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals("sing-box check: $output\n$config", 0, process.exitValue())
        } finally {
            file.delete()
        }
    }

    /**
     * Конфиг для телефона проверяет настоящий sing-box той же версии (из версии для компьютера, если она
     * собрана рядом): `sing-box check` разбирает его так же, как при запуске.
     */
    @Test
    fun singBoxAcceptsConfig() {
        Vpn.Lists.telegramCidr = listOf("149.154.160.0/20", "91.108.4.0/22")
        Vpn.Lists.blockedCidr = listOf("31.13.24.0/21")
        Vpn.Lists.telegram = listOf("telegram.org", "t.me")
        Vpn.Lists.ai = listOf("chatgpt.com", "claude.ai")
        Vpn.Lists.blocked = listOf(".ua", "discord.com", "youtube.com", "instagram.com")
        Vpn.Lists.discord = listOf("discord.com", "discord.gg")
        val servers = SubscriptionParser.parse(links.joinToString("\n")).servers
        for (discordViaVpn in listOf(true, false)) {
            val config = SingBoxConfig.build(Vpn.select(servers), Vpn.PORT, Engine.PORT, "77.88.8.8", discordViaVpn)
            assertTrue(config, "\"final\": \"dpi\"" in config && "\"outbound\": \"vpn\"" in config)
            check(config)
        }
    }
}
