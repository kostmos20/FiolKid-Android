package com.fiolkid.app.engine

/**
 * Способы обхода («альты») для byedpi — аналог стратегий zapret в версии для компьютера. Перебираем по порядку,
 * пока YouTube и Discord не откроются. Перед каждым — отдельная группа для UDP (QUIC, голос Discord):
 * один поддельный пакет, дальше — TCP-группы стратегии.
 */
data class Strategy(val name: String, val args: String)

object Strategies {
    private const val Udp = "-Ku -a1 -An -Kt,h"

    val all = listOf(
        Strategy("SPLIT DISORDER", "-s1 -d1 -r1+s -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s"),
        Strategy("DISORDER TLSREC", "-d1 -r1+s -At,r,s -d1 -f-1 -t8"),
        Strategy("OOB", "-o1 -d1 -r1+s -At,r,s -o2 -s1+s -d3+s"),
        Strategy("FAKE TTL", "-f-1 -t6 -r1+s -At,r,s -d1+s -f-1 -t8"),
        Strategy("FAKE MD5", "-f-1 -S -r3+s -At,r,s -d1 -s1+s"),
        Strategy("DISOOB", "-q1 -r1+s -At,r,s -d1 -q3+s"),
        Strategy("SPLIT SNI", "-s1+s -d3+s -r1+s"),
        Strategy("FAKE SNI", "-f-1 -t8 -n www.google.com -r1+s -At,r,s -d1 -s1+s"),
    )

    /** Полные аргументы byedpi: только на 127.0.0.1 — чтобы прокси не был виден другим устройствам в сети. */
    fun args(strategy: Strategy, port: Int): Array<String> =
        (listOf("-i", "127.0.0.1", "-p", port.toString(), "-T", "5") + Udp.split(' ') + strategy.args.split(' '))
            .toTypedArray()
}
