package com.fiolkid.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import com.fiolkid.app.Look
import com.fiolkid.app.Service
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
 * в byedpi, а byedpi выпускает их в интернет, запутывая DPI. С подпиской между ними — sing-box ([Vpn]):
 * Telegram, YouTube, нейросети и заблокированные сайты он отправляет через серверы VPN. Само приложение в VPN не входит — иначе byedpi
 * отправлял бы свои же соединения по кругу.
 *
 * Все действия (включить, выключить, проверить, замерить, сменить способ) идут через одну очередь, чтобы
 * не мешать друг другу.
 */
class FiolVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.fiolkid.app.START"
        const val ACTION_STOP = "com.fiolkid.app.STOP"
        const val ACTION_CHECK = "com.fiolkid.app.CHECK"
        const val ACTION_BENCHMARK = "com.fiolkid.app.BENCHMARK"
        const val ACTION_APPLY = "com.fiolkid.app.APPLY"
        const val ACTION_RESTART_TELEGRAM = "com.fiolkid.app.RESTART_TELEGRAM"
        const val ACTION_RELOAD_VPN = "com.fiolkid.app.RELOAD_VPN"
        /** Сменился выбор сервера: перезапустить только sing-box. */
        const val ACTION_RESTART_SINGBOX = "com.fiolkid.app.RESTART_SINGBOX"
        const val ACTION_MEASURE_SERVERS = "com.fiolkid.app.MEASURE_SERVERS"
        const val EXTRA_STRATEGY = "strategy"

        /** Проверка, пока включено, — раз в 5 минут; после двух неудач подряд подбираем способ заново. */
        private const val WATCH_PERIOD = 5 * 60 * 1000L

        fun send(context: Context, action: String, strategy: String? = null) {
            context.startService(Intent(context, FiolVpnService::class.java).setAction(action).putExtra(EXTRA_STRATEGY, strategy))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var meter: Job? = null
    private var watchdog: Job? = null
    private var failures = 0
    private var vpnFailures = 0
    /** Сколько адресов открылось, когда способ выбирали: хуже не стало — подбирать заново незачем. */
    private var pickedPassed = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val strategy = intent?.getStringExtra(EXTRA_STRATEGY)
        // Замер серверов долгий — не в общей очереди, чтобы не задерживать проверки и переключения
        if (intent?.action == ACTION_MEASURE_SERVERS) {
            measureServers()
            return START_NOT_STICKY
        }
        scope.launch {
            lock.withLock {
                safely {
                    when (intent?.action) {
                        ACTION_STOP -> stop()
                        ACTION_CHECK -> recheck(byUser = true)
                        ACTION_BENCHMARK -> benchmark()
                        ACTION_APPLY -> apply(strategy)
                        ACTION_RESTART_TELEGRAM -> restartTelegram()
                        ACTION_RELOAD_VPN -> reloadSubscription()
                        ACTION_RESTART_SINGBOX -> restartSingBox()
                        // Без действия запускает сама система: «Постоянная VPN» в настройках телефона
                        else -> start()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Ошибка — в журнал и выключиться, а не уронить программу. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Engine.note("Сбой: $e")
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
        // Прокси Telegram — сразу, не дожидаясь подбора способа: иначе Telegram минуту висит на «Соединение»
        startTelegram()
        Engine.step("подбор способа")
        val picked = Engine.pickStrategy()
        if (picked == null) {
            Engine.note("Ни один способ обхода не запустился")
            return stop()
        }
        val (strategy, result) = picked
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
        Engine.step("включение VPN")
        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            Engine.note("VPN не включился: ${e.message}")
            null
        }
        if (descriptor == null) return stop()
        tun = descriptor

        Engine.step("запуск VPN по подписке")
        startSubscription()
        Engine.step("запуск перенаправителя трафика")
        if (!startTunnel()) {
            Engine.note("Не запустился перенаправитель трафика")
            stop()
            return
        }
        Engine.note("VPN включён")
        Engine.step("работа")
        pickedPassed = result.passed
        // С подпиской YouTube идёт через VPN: проверка выше была через byedpi, перепроверяем как есть
        val first = if (Vpn.running) Engine.check() else result
        Engine.on(first.looks + extraLooks())
        discordFallback(first)
        // Как на компьютере: выбор «самый быстрый» — серверы меряем (в фоне, уже подключившись) и берём лучшие
        if (Vpn.measureDue) measureServers()
        if (result.allPassed) Engine.note("Все проверки прошли")
        failures = 0
        watchTraffic()
        watch()
    }

    /**
     * hev-socks5-tunnel: пакеты из VPN-интерфейса — соединениями в SOCKS. С подпиской — в sing-box (он раскладывает
     * между VPN и byedpi), без неё — прямо в byedpi.
     */
    private fun startTunnel(): Boolean {
        val descriptor = tun ?: return false
        val config = File(filesDir, "tunnel.yml")
        config.writeText(
            """
            tunnel:
              mtu: 8500
            socks5:
              port: ${if (Vpn.running) Vpn.PORT else Engine.PORT}
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 81920
            """.trimIndent(),
        )
        return Tunnel.TProxyStartService(config.absolutePath, descriptor.fd)
    }

    /** sing-box по подписке, если она есть; не запустился — работаем как без неё. */
    private fun startSubscription() {
        TelegramProxy.vpnPort = null
        if (!Vpn.hasLink) return
        // Список серверов — сохранённый: обновляется потом, в проверке (иначе телефон ждал бы без интернета)
        val ok = Vpn.start(Engine.PORT, networkDns())
        if (ok) TelegramProxy.vpnPort = Vpn.PORT
        else Engine.note("VPN по подписке не запустился — Telegram и YouTube пока через обход")
    }

    /** Новый выбор сервера (или запасной режим Discord): перезапускаем sing-box на том же порту, hev не трогаем. */
    private fun restartSingBox() {
        if (tun == null || !Vpn.canConnect) return
        if (!Vpn.running) return reloadSubscription()
        if (!Vpn.start(Engine.PORT, networkDns())) {
            // Не поднялся — работаем без VPN, пока не починится
            reloadSubscription()
            return
        }
        Engine.on(Engine.check().looks + extraLooks())
    }

    /** Замер серверов в фоне; если включено — потом переподключаемся к лучшим. */
    private fun measureServers() {
        scope.launch {
            if (!Vpn.measure()) return@launch
            lock.withLock { safely { if (Vpn.running) restartSingBox() } }
        }
    }

    /**
     * Запасной режим, как на компьютере: Discord через обход не открылся, а VPN есть — пускаем Discord через VPN
     * (до выключения FiolKid).
     */
    private fun discordFallback(result: CheckResult) {
        if (!Vpn.running || Vpn.discordViaVpn || result.problems[Service.Discord].isNullOrEmpty() || result.passed == 0) return
        Engine.note("Discord через обход не открылся — пускаю его через VPN (запасной режим)")
        Vpn.discordViaVpn = true
        restartSingBox()
    }

    /** DNS сети телефона (Wi-Fi или мобильной) — через него sing-box узнаёт адреса серверов подписки. */
    private fun networkDns(): String? = runCatching {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return null
        connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            .firstOrNull { it is java.net.Inet4Address }?.hostAddress
    }.getOrNull()

    /**
     * Подписку добавили или убрали, пока FiolKid включён: перезапускаем sing-box и перенаправитель
     * трафика, не выключая VPN (на пару секунд соединения прервутся).
     */
    private fun reloadSubscription() {
        if (tun == null) return
        runCatching { TrafficMeter.update(this, Tunnel.TProxyGetStats()) }
        TrafficMeter.restarted()
        runCatching { Tunnel.TProxyStopService() }
        Vpn.stop()
        startSubscription()
        if (!startTunnel()) {
            Engine.note("Не запустился перенаправитель трафика")
            return stop()
        }
        Engine.on(Engine.check().looks + extraLooks())
    }

    /**
     * Серверы подписки перестали отвечать (VPN и YouTube через него не открылись две проверки подряд, а что-то
     * другое открылось — значит, сеть есть): замеряем серверы заново и переходим на рабочие, не дожидаясь срока.
     */
    private fun vpnWatch(result: CheckResult, extra: Map<Service, Look>) {
        val bad = Vpn.running && Vpn.state.value.auto &&
            (extra[Service.Ai] == Look.NotWorking || !result.problems[Service.YouTube].isNullOrEmpty())
        if (!bad || result.passed == 0) {
            if (!bad) vpnFailures = 0
            return
        }
        if (++vpnFailures < 2) return
        vpnFailures = 0
        Engine.note("Серверы подписки не отвечают две проверки подряд — замеряю их заново")
        measureServers()
    }

    /** Карточки, которые не зависят от способа обхода: Telegram (наш прокси) и VPN. */
    private fun extraLooks() = mapOf(Service.Telegram to Engine.telegramLook(), Service.Ai to Engine.vpnLook())

    /** Раз в 2 секунды — сколько прошло через обход: в уведомление. */
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

    private fun watch() {
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCH_PERIOD)
                lock.withLock { safely { recheck(byUser = false) } }
            }
        }
    }

    /** Проверка через текущий способ; две неудачи подряд — подбираем способ заново, VPN не выключая. */
    private fun recheck(byUser: Boolean) {
        if (tun == null) {
            if (byUser) Engine.note("Проверка — после включения: сейчас FiolKid выключен")
            return
        }
        if (byUser) Engine.note("Проверяю по просьбе")
        // sing-box мог упасть — тогда поднимаем его заново вместе с перенаправителем; раз в 6 часов — новый список серверов
        if (Vpn.canConnect && !Vpn.running) {
            Engine.note("VPN по подписке не работает — перезапускаю")
            reloadSubscription()
            if (tun == null) return
        } else if (!byUser && Vpn.refreshIfDue()) {
            reloadSubscription()
            if (tun == null) return
        }
        val result = Engine.check()
        val extra = extraLooks()
        Engine.on(result.looks + extra)
        discordFallback(result)
        vpnWatch(result, extra)
        if (result.allPassed) {
            failures = 0
            Engine.note("Все проверки прошли")
            return
        }
        failures++
        Engine.note("Проверка не прошла ($failures из 2)")
        if (failures < 2) return
        failures = 0
        // Не открылось вообще ничего — скорее всего, нет сети или телефон спит и не пускает в сеть: способ ни при чём
        if (result.passed == 0) {
            Engine.note("Не открылось ничего — похоже, нет сети или телефон спит; способ не меняю")
            return
        }
        // Перебор способов идёт прямо на рабочем соединении и рвёт его — только если стало хуже, чем при выборе
        if (result.passed >= pickedPassed) {
            Engine.note("Открылось столько же, сколько при выборе способа (${result.passed} из ${result.total}) — способ не меняю")
            return
        }
        Engine.note("Подбираю способ заново")
        Engine.connecting()
        Engine.step("подбор способа заново")
        val picked = Engine.pickStrategy() ?: return stop()
        Engine.step("работа")
        pickedPassed = picked.second.passed
        Engine.note("Работаю со способом «${picked.first.name}»")
        Engine.on((if (Vpn.running) Engine.check().looks else picked.second.looks) + extraLooks())
    }

    /** Прокси Telegram работает, пока включён FiolKid; первое подключение Telegram сразу зажигает карточку. */
    private fun startTelegram() {
        TelegramProxy.socksPort = Engine.PORT
        TelegramProxy.onFirstClient = { Engine.update(Service.Telegram, Look.Working) }
        try {
            TelegramProxy.start()
        } catch (e: Exception) {
            Engine.note("Прокси Telegram не запустился: ${e.message}")
        }
    }

    private fun restartTelegram() {
        if (tun == null) return
        Engine.note("Перезапускаю прокси Telegram по просьбе")
        TelegramProxy.stop()
        startTelegram()
        Engine.update(Service.Telegram, Engine.telegramLook())
    }

    private fun benchmark() {
        val on = tun != null
        val picked = Engine.benchmark { number, count -> Engine.measuring(number, count) }
        if (!on) {
            // Замер без VPN: byedpi был нужен только для него
            Engine.stopDpi()
            Engine.off()
            stopSelf()
            return
        }
        if (picked == null) return stop()
        pickedPassed = picked.second.passed
        Engine.note("Работаю со способом «${picked.first.name}»")
        Engine.on((if (Vpn.running) Engine.check().looks else picked.second.looks) + extraLooks())
    }

    private fun apply(name: String?) {
        val strategy = Strategies.all.firstOrNull { it.name == name } ?: return
        if (tun == null) return
        val looks = Engine.state.value.looks
        Engine.connecting()
        val result = Engine.apply(strategy) ?: return stop()
        pickedPassed = result.passed
        val now = if (Vpn.running) Engine.check().looks else result.looks
        Engine.on(now + (Service.Telegram to looks.getValue(Service.Telegram)) + (Service.Ai to looks.getValue(Service.Ai)))
    }

    private fun stop() {
        meter?.cancel()
        meter = null
        watchdog?.cancel()
        watchdog = null
        TelegramProxy.stop()
        TelegramProxy.vpnPort = null
        if (tun != null) {
            runCatching { TrafficMeter.update(this, Tunnel.TProxyGetStats()) }
            runCatching { Tunnel.TProxyStopService() }
            tun?.close()
            tun = null
            Vpn.stop()
            Vpn.discordViaVpn = false
            Engine.note("Выключено. Через обход прошло ${formatBytes(TrafficMeter.traffic.value.session)}")
        }
        Engine.stopDpi(noteVideo = true)
        Engine.off()
        Engine.step(null)
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
