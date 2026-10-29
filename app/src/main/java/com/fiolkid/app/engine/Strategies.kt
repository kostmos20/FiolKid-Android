package com.fiolkid.app.engine

/**
 * Способы обхода («альты») для byedpi — аналог стратегий zapret в версии для компьютера. Перебираем по порядку,
 * пока YouTube и Discord не откроются. Перед каждым — отдельная группа для UDP (QUIC, голос Discord):
 * один поддельный пакет, дальше — TCP-группы стратегии.
 */
data class Strategy(val name: String, val args: String)

object Strategies {
    private const val Udp = "-Ku -a1 -An -Kt,h"

    /** Остальные способы — после основного и сильных под Дом.ру (как ALT4 и ALT9 на компьютере), чтобы тех не ждать. */
    private val base = listOf(
        Strategy("DISORDER TLSREC", "-d1 -r1+s -At,r,s -d1 -f-1 -t8"),
        Strategy("OOB", "-o1 -d1 -r1+s -At,r,s -o2 -s1+s -d3+s"),
        Strategy("FAKE TTL", "-f-1 -t6 -r1+s -At,r,s -d1+s -f-1 -t8"),
        Strategy("DISOOB", "-q1 -r1+s -At,r,s -d1 -q3+s"),
        Strategy("SPLIT SNI", "-s1+s -d3+s -r1+s"),
        Strategy("FAKE SNI", "-f-1 -t8 -n www.google.com -r1+s -At,r,s -d1 -s1+s"),
        // Посильнее: поддельный ClientHello со случайными данными, разрез посередине имени сайта
        Strategy("FAKE RAND", "-f-1 -Qr -t6 -s1+sm -d3+s -r1+s -At,r,s -o1 -d1 -r1+s"),
        Strategy("FAKE GOOGLE", "-f-1 -Qr -t8 -n www.google.com -d1+sm -s3+s -r1+s -At,r,s -o2 -d1+s"),
        Strategy("MID SNI", "-d1+sm -r1+s -At,r,s -s1 -d1+s -s3+s -r1+s"),
        Strategy("TLSREC MULTI", "-r1+s -r3+s -d1 -s5+s -At,r,s -d1+s -f-1 -t8"),
        Strategy("OOB SNI", "-o1+s -d1 -s1+sm -r1+s -At,r,s -q1 -d3+s"),
    )

    val all = listOf(Strategy("SPLIT DISORDER", "-s1 -d1 -r1+s -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s")) +
        listOf(2, 3, 4, 5).map { ttl ->
            // Как ALT4 на компьютере: поддельный ClientHello Google и разрезы. Там подделку портит неверный номер
            // пакета, на телефоне без root так нельзя — только малый TTL: до провайдера доходит, до сервера нет
            Strategy("ALT4 TTL$ttl", "-f-1 -t$ttl -n www.google.com -s1 -s3+s -s5+s -At,r,s -s1 -d1 -s5+s -r1+s")
        } +
        listOf(3, 4).map { ttl ->
            // Как ALT9: подделка имени сайта (www.google.com) вокруг настоящего, тоже на малом TTL
            Strategy("ALT9 TTL$ttl", "-f1+s -t$ttl -n www.google.com -d1+sm -s3+s -At,r,s -s1 -d1 -s5+s -r1+s")
        } +
        base

    /**
     * Полные аргументы byedpi: только на 127.0.0.1 — чтобы прокси не был виден другим устройствам в сети.
     * -T 3: через 3 с без ответа byedpi пробует запасную часть способа (после -At) — проверка ждёт дольше
     * ([Checks.TIMEOUT_MS]), чтобы её успеть увидеть.
     */
    fun args(strategy: Strategy, port: Int): Array<String> =
        (listOf("-i", "127.0.0.1", "-p", port.toString(), "-T", "3") + Udp.split(' ') + strategy.args.split(' '))
            .toTypedArray()
}
