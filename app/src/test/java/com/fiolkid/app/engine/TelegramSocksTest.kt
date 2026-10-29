package com.fiolkid.app.engine

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Прокси Telegram через SOCKS-порт, как на телефоне через byedpi: вместо byedpi — простейший SOCKS5-сервер,
 * который считает соединения. Telegram (тест) должен получить ответ, а соединения — пройти через «обход».
 */
class TelegramSocksTest {
    companion object {
        private val passed = AtomicInteger()
        private lateinit var socks: ServerSocket

        @BeforeClass
        @JvmStatic
        fun start() {
            socks = ServerSocket(0)
            thread(isDaemon = true) {
                while (!socks.isClosed) {
                    val client = runCatching { socks.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { relay(client) }
                }
            }
            TelegramProxy.stop()
            TelegramProxy.forgetRoutes()
            TelegramProxy.socksPort = socks.localPort
            TelegramProxy.start()
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            TelegramProxy.stop()
            TelegramProxy.socksPort = null
            TelegramProxy.forgetRoutes()
            socks.close()
        }

        /** SOCKS5 без пароля, только CONNECT на IPv4 или имя. */
        private fun relay(client: Socket) = client.use {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.readUnsignedByte()
            input.skipBytes(input.readUnsignedByte())
            output.write(byteArrayOf(5, 0))
            input.readUnsignedByte()
            input.readUnsignedByte()
            input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xFF).toString() }
                3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                else -> return@use
            }
            val port = input.readUnsignedShort()
            val remote = runCatching { Socket().apply { connect(InetSocketAddress(host, port), 6000) } }.getOrNull()
            if (remote == null) {
                output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                return@use
            }
            passed.incrementAndGet()
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            remote.use {
                val up = thread(isDaemon = true) { runCatching { client.getInputStream().copyTo(remote.getOutputStream()) }; runCatching { remote.shutdownOutput() } }
                runCatching { remote.getInputStream().copyTo(output) }
                up.join(1000)
            }
        }
    }

    @Test
    fun dc2ThroughSocks() {
        assertEquals("resPQ от DC2", 0x05162463, TelegramClient.askResPq(2))
        assertTrue("соединение шло через SOCKS", passed.get() > 0)
        assertTrue(TelegramProxy.routesReport().toString(), TelegramProxy.routesReport().any { "DC2: kws2, через обход" in it })
    }

    @Test
    fun dc5ThroughSocks() {
        assertEquals("resPQ от DC5", 0x05162463, TelegramClient.askResPq(5))
        assertTrue(TelegramProxy.routesReport().toString(), TelegramProxy.routesReport().any { it.startsWith("DC5: Cloudflare") && "через обход" in it })
    }
}
