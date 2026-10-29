package com.fiolkid.app.engine

/** byedpi — локальный SOCKS-прокси, который запутывает DPI провайдера (src/main/jni/byedpi). */
object ByeDpi {
    init {
        System.loadLibrary("byedpi")
    }

    /** Работает, пока не вызовут [stop]; возвращает код выхода (не 0 — стратегия не разобралась или порт занят). */
    external fun run(args: Array<String>): Int

    external fun stop()
}

/**
 * hev-socks5-tunnel: забирает пакеты из VPN-интерфейса телефона и отправляет соединения в byedpi как в SOCKS.
 * Имена и набор методов заданы в его hev-jni.c: при загрузке библиотека привязывает все четыре, и если
 * хоть одного нет в классе — загрузка падает.
 */
@Suppress("FunctionName")
object Tunnel {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    /** Счётчики трафика: пакеты и байты туда и обратно. */
    external fun TProxyGetStats(): LongArray
}
