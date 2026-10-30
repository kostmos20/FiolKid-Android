package com.fiolkid.app.engine

/**
 * Способы обхода («альты») для byedpi — аналог стратегий zapret в версии для компьютера. Перебираем по порядку,
 * пока YouTube и Discord не откроются. Перед каждым — отдельная группа для UDP (голос Discord) и цепочка
 * для видеосерверов YouTube ([Video]), дальше — TCP-группы стратегии.
 */
data class Strategy(val name: String, val args: String)

object Strategies {

    /**
     * Настоящий ClientHello Chrome к www.google.com (tls_clienthello_www_google_com.bin из zapret, как в ALT4 на
     * компьютере) — путь к файлу на телефоне. Пока не задан (тесты), варианты с ним пропускаются.
     */
    @Volatile
    var googleHello: String? = null

    /**
     * Видео YouTube идёт не с youtube.com, а с серверов googlevideo.com — часто они стоят прямо в сети провайдера,
     * и подделке до них нужен другой TTL, чем до youtube.com. Поэтому для них — своя цепочка, независимо от
     * выбранного способа. Не открылось за 3 с — byedpi сам берёт следующий вариант и запоминает удачный для этого
     * сервера. Названия — для журнала: номер группы byedpi = 1 + номер варианта (группа 0 — UDP).
     */
    fun videoVariants(): List<Pair<String, String>> {
        val hello = googleHello
        val real = { ttl: Int -> "настоящий ClientHello Google, TTL $ttl" to "-f-1 -t$ttl -l $hello -r1+s" }
        return listOfNotNull(
            hello?.let { real(3) },
            hello?.let { real(2) },
            hello?.let { real(4) },
            "как ALT9, TTL 3" to "-f1+s -t3 -n www.google.com -d1+sm -s3+s",
            hello?.let { real(5) },
            "как ALT9, TTL 2" to "-f1+s -t2 -n www.google.com -d1+sm -s3+s",
            "без подделки" to "-s1 -d1 -s5+s -r1+s",
        )
    }

    private fun video() = videoVariants().mapIndexed { index, (_, variant) ->
        (if (index == 0) "" else "-At,r,s ") + "-Kt -H :googlevideo.com " + variant
    }.joinToString(" ")

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
    fun args(strategy: Strategy, port: Int, cacheDump: String? = null): Array<String> =
        (listOf("-i", "127.0.0.1", "-p", port.toString(), "-T", "3") + listOfNotNull(cacheDump?.let { "-y" }, cacheDump) +
            "-Ku -a1 -An".split(' ') + video().split(' ') + "-An -Kt,h".split(' ') + strategy.args.split(' '))
            .toTypedArray()
}
