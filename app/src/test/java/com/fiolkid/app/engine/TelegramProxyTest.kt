package com.fiolkid.app.engine

import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Проверка прокси Telegram на компьютере, по-настоящему, через интернет: тест ведёт себя как приложение Telegram —
 * подключается к прокси с dd-секретом, отправляет первый запрос MTProto (req_pq_multi) и ждёт от сервера
 * Telegram ответ resPQ. Проходит — значит, рукопожатие, шифрование, деление на пакеты и WebSocket верны.
 */
class TelegramProxyTest {
    companion object {
        private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }

        @BeforeClass
        @JvmStatic
        fun start() {
            TelegramProxy.useSecret(secret)
            TelegramProxy.start()
        }

        @AfterClass
        @JvmStatic
        fun stop() = TelegramProxy.stop()
    }

    private fun ctr(key: ByteArray, iv: ByteArray) =
        Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    /** Отправить req_pq_multi в [dc] через прокси и вернуть конструктор ответа. */
    private fun askResPq(dc: Int): Int {
        Socket("127.0.0.1", TelegramProxy.PORT).use { socket ->
            socket.soTimeout = 20000
            val random = SecureRandom()
            // Начало обфусцированного потока, как у клиента Telegram: протокол intermediate, номер DC
            val init = ByteArray(64)
            do random.nextBytes(init) while (init[0] == 0xEF.toByte() || init.copyOfRange(4, 8).all { it == 0.toByte() })
            for (i in 56 until 60) init[i] = 0xEE.toByte()
            init[60] = dc.toByte()
            init[61] = 0
            val enc = ctr(sha256(init.copyOfRange(8, 40) + secret), init.copyOfRange(40, 56))
            val encrypted = enc.update(init)
            val reversed = init.copyOfRange(8, 56).reversedArray()
            val dec = ctr(sha256(reversed.copyOfRange(0, 32) + secret), reversed.copyOfRange(32, 48))
            val out = socket.getOutputStream()
            out.write(init.copyOfRange(0, 56) + encrypted.copyOfRange(56, 64))

            // Незашифрованное сообщение MTProto: auth_key_id = 0, message_id, длина, req_pq_multi#be7e8ef1 + nonce
            val body = java.nio.ByteBuffer.allocate(20 + 20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            body.putLong(0)
            body.putLong((System.currentTimeMillis() / 1000) shl 32)
            body.putInt(20)
            body.putInt(0xbe7e8ef1.toInt())
            body.put(ByteArray(16).also(random::nextBytes))
            val payload = body.array()
            val packet = java.nio.ByteBuffer.allocate(4 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(payload.size).put(payload).array()
            out.write(enc.update(packet))
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            val header = dec.update(ByteArray(4).also(input::readFully))
            val length = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val answer = dec.update(ByteArray(length).also(input::readFully))
            assertArrayEquals("auth_key_id ответа", ByteArray(8), answer.copyOfRange(0, 8))
            return java.nio.ByteBuffer.wrap(answer, 20, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        }
    }

    @Test
    fun dc2ThroughWebSocket() = assertEquals("resPQ от DC2", 0x05162463, askResPq(2))

    @Test
    fun dc4ThroughWebSocket() = assertEquals("resPQ от DC4", 0x05162463, askResPq(4))

    @Test
    fun dc1ThroughCloudflare() = assertEquals("resPQ от DC1", 0x05162463, askResPq(1))

    @Test
    fun dc3ThroughCloudflare() = assertEquals("resPQ от DC3", 0x05162463, askResPq(3))

    @Test
    fun dc5ThroughCloudflare() = assertEquals("resPQ от DC5", 0x05162463, askResPq(5))

    @Test
    fun probe() = assertEquals(null, TelegramProxy.probe())
}
