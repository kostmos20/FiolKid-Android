package com.fiolkid.app.engine

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * VPN-режим Android: весь трафик телефона идёт в наш интерфейс, оттуда hev-socks5-tunnel отдаёт соединения
 * в byedpi, а byedpi выпускает их в интернет, запутывая DPI. Само приложение в VPN не входит — иначе byedpi
 * отправлял бы свои же соединения по кругу.
 */
class FiolVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.fiolkid.app.START"
        const val ACTION_STOP = "com.fiolkid.app.STOP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var meter: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { lock.withLock { stop() } }
            else -> scope.launch { lock.withLock { safely { start() } } }
        }
        return START_NOT_STICKY
    }

    /** Ошибка при включении — в журнал и выключиться, а не уронить программу. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Engine.note("Сбой при включении: $e")
            runCatching { stop() }
        }
    }

    private fun start() {
        if (tun != null) return
        Engine.note("Включено")
        Engine.connecting()
        // Уведомление — сразу: пока подбирается способ, пользователь может свернуть программу
        val notification = StatusNotification.build(this, "FiolKid подключается…", "Подбираю способ обхода")
        ServiceCompat.startForeground(
            this, StatusNotification.ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        val picked = Engine.pickStrategy(this)
        if (picked == null) {
            Engine.note("Ни один способ обхода не запустился")
            Engine.off()
            stopSelf()
            return
        }
        val (strategy, looks) = picked
        Engine.note("Работаю со способом «${strategy.name}»")

        val builder = Builder()
            .setSession("FiolKid")
            .setMtu(8500)
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:f10:1::1", 128)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            Engine.note("VPN не включился: ${e.message}")
            null
        }
        if (descriptor == null) {
            Engine.stopDpi()
            Engine.off()
            stopSelf()
            return
        }
        tun = descriptor

        val config = File(filesDir, "tunnel.yml")
        config.writeText(
            """
            tunnel:
              mtu: 8500
            socks5:
              port: ${Engine.PORT}
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 81920
            """.trimIndent(),
        )
        if (!Tunnel.TProxyStartService(config.absolutePath, descriptor.fd)) {
            Engine.note("Не запустился перенаправитель трафика")
            stop()
            return
        }
        Engine.on(looks)
        watchTraffic()
    }

    /** Раз в 2 секунды — сколько прошло через обход: в уведомление и в журнал. */
    private fun watchTraffic() {
        TrafficMeter.begin(this)
        meter = scope.launch {
            while (isActive) {
                runCatching { TrafficMeter.update(this@FiolVpnService, Tunnel.TProxyGetStats()) }
                val traffic = TrafficMeter.traffic.value
                StatusNotification.update(
                    this@FiolVpnService, "FiolKid включён",
                    "Через обход: ${formatBytes(traffic.session)} · за всё время ${formatBytes(traffic.total)}",
                )
                delay(2000)
            }
        }
    }

    private fun stop() {
        meter?.cancel()
        meter = null
        if (tun != null) {
            runCatching { TrafficMeter.update(this, Tunnel.TProxyGetStats()) }
            runCatching { Tunnel.TProxyStopService() }
            tun?.close()
            tun = null
        }
        Engine.stopDpi()
        val traffic = TrafficMeter.traffic.value
        Engine.note("Выключено. Через обход прошло ${formatBytes(traffic.session)}")
        Engine.off()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // VPN выключили в настройках телефона или включили другой VPN
        scope.launch { lock.withLock { stop() } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
