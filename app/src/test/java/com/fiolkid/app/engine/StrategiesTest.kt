package com.fiolkid.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аргументы byedpi: цепочка для видеосерверов с настоящим ClientHello Google и запись итогов в файл. */
class StrategiesTest {
    @Test
    fun videoChainWithGoogleHello() {
        Strategies.googleHello = "/data/files/hello.bin"
        try {
            val args = Strategies.args(Strategies.all.first(), 10808, "/data/files/dpi-cache.txt").joinToString(" ")
            assertTrue(args, args.contains("-y /data/files/dpi-cache.txt -Ku -a1 -An -Kt -H :googlevideo.com -f-1 -t3 -l /data/files/hello.bin"))
            assertEquals(7, Strategies.videoVariants().size)
            // Каждый вариант для видео — только для googlevideo.com, после него — обычные группы способа
            assertEquals(7, Regex("-H :googlevideo.com").findAll(args).count())
            assertTrue(args, args.contains("-An -Kt,h -s1 -d1"))
        } finally {
            Strategies.googleHello = null
        }
        assertEquals(3, Strategies.videoVariants().size)
    }
}
