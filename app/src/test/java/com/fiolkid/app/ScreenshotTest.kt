package com.fiolkid.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.fiolkid.app.engine.OptionsState
import com.fiolkid.app.engine.Release
import com.fiolkid.app.engine.StrategyMode
import com.fiolkid.app.engine.StrategyResult
import com.fiolkid.app.engine.Traffic
import com.fiolkid.app.engine.UpdateState
import com.fiolkid.app.engine.ServerRow
import com.fiolkid.app.engine.ServerScore
import com.fiolkid.app.engine.VpnState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/** Снимки экранов без телефона: gradlew recordPaparazziDebug → app/src/test/snapshots/images. */
/** Подписка с замеренными серверами — как на компьютере после «Замерить серверы». */
internal val vpnSample = VpnState(
    hasLink = true, linkOk = true, linkText = "Подписка «🔥 Quattro VPN»: серверов 5, до 03.12.2026",
    servers = listOf(
        ServerRow("a", "🇳🇱 Нидерланды", "vless", ServerScore("a", 84, 37.5, "NL", null)),
        ServerRow("b", "🇩🇪 Германия", "vless", ServerScore("b", 97, 29.1, "DE", null)),
        ServerRow("c", "🇫🇮 Финляндия", "hysteria2", ServerScore("c", 112, 18.4, "FI", null)),
        ServerRow("d", "🇷🇺 Москва", "vless", ServerScore("d", 31, 61.0, "RU", null)),
        ServerRow("e", "🇵🇱 Польша", "trojan", ServerScore("e", null, null, null, "не отвечает")),
    ),
    chosen = listOf("🇳🇱 Нидерланды", "🇩🇪 Германия", "🇫🇮 Финляндия"),
    running = true, profile = "подписка «🔥 Quattro VPN»",
)

class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:Theme.Material.NoActionBar")

    private val now = LocalDateTime.of(2026, 10, 29, 12, 0, 0)
    private val working = HomeState(Power.On, HomeState.looks(Look.Working, Look.Working, Look.Working))

    internal val sample = AppData(
        log = listOf(
            "12:11:33  Программа запущена 29.10.2026",
            "12:11:33  Включено",
            "12:11:34  Способ «SPLIT DISORDER»: открылось 5 из 5, 0,6 с",
            "12:11:34  Работаю со способом «SPLIT DISORDER»",
            "12:11:34  Все проверки прошли",
            "12:16:34  Не прошло: Discord: cdn.discordapp.com: нет ответа за 10 с",
            "12:16:34  Проверка не прошла (1 из 2)",
            "12:21:34  Все проверки прошли",
        ),
        results = mapOf(
            "SPLIT DISORDER" to StrategyResult(5, 5, 0.6),
            "DISORDER TLSREC" to StrategyResult(5, 5, 0.9),
            "OOB" to StrategyResult(2, 5, 10.2),
            "FAKE TTL" to StrategyResult(0, 5, 10.1),
        ),
        current = "SPLIT DISORDER",
        telegram = com.fiolkid.app.engine.TelegramState(running = true, active = 6, total = 41, connectedOnce = true),
        traffic = Traffic(152L * 1024 * 1024, 1288L * 1024 * 1024),
        lastCheck = now,
    )

    @Test
    fun off() = paparazzi.snapshot { FiolKidApp(HomeState(), "0.6.0", {}, time = 3f, now = now) }

    @Test
    fun on() = paparazzi.snapshot { FiolKidApp(working, "0.6.0", {}, time = 3f, now = now) }

    @Test
    fun mixed() = paparazzi.snapshot {
        val looks = HomeState.looks(Look.Working, Look.Working, Look.NeedsConnect)
        FiolKidApp(HomeState(Power.On, looks), "0.6.0", {}, time = 3f, now = now)
    }

    /** С подпиской: все четыре карточки, VPN тоже работает. */
    @Test
    fun vpn() = paparazzi.snapshot {
        val looks = HomeState.looks(Service.entries.associateWith { Look.Working })
        FiolKidApp(HomeState(Power.On, looks), "0.7.0", {}, time = 3f, now = now)
    }

    /** Панели 01–03 (ключ, подписка, подключение VPN) крупно — с подпиской и замером серверов. */
    @Test
    fun vpnPanels() = paparazzi.snapshot {
        androidx.compose.runtime.CompositionLocalProvider(LocalUnit provides 393f / 500f) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF1B1D27)).padding(16.u),
            ) {
                val data = sample.copy(vpn = vpnSample)
                SubscriptionPanel(data, AppActions())
                VpnPanel(HomeState(Power.On), data, AppActions())
            }
        }
    }

    /** Без ключа и подписки. */
    @Test
    fun vpnPanelsEmpty() = paparazzi.snapshot {
        androidx.compose.runtime.CompositionLocalProvider(LocalUnit provides 393f / 500f) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF1B1D27)).padding(16.u),
            ) {
                KeyPanel(sample, AppActions())
                SubscriptionPanel(sample, AppActions())
                VpnPanel(HomeState(Power.On), sample, AppActions())
            }
        }
    }

    @Test
    fun updateLink() = paparazzi.snapshot {
        FiolKidApp(HomeState(), "0.6.0", {}, time = 3f, now = now, data = AppData(update = UpdateState("Вышла версия 0.4.0", Release("0.6.0", "", ""))))
    }

    @Test
    fun noNewYear() = paparazzi.snapshot {
        FiolKidApp(working, "0.6.0", {}, time = 3f, now = now, data = AppData(options = OptionsState(newYear = false)))
    }

    @Test
    fun log() = paparazzi.snapshot { FiolKidApp(working, "0.6.0", {}, time = 3f, now = now, initialTab = Tab.Log, data = sample) }

    @Test
    fun settings() = paparazzi.snapshot {
        FiolKidApp(working, "0.6.0", {}, time = 3f, now = now, initialTab = Tab.Settings, data = sample)
    }

    @Test
    fun settingsManual() = paparazzi.snapshot {
        val data = sample.copy(options = OptionsState(mode = StrategyMode.Manual, manual = "DISORDER TLSREC"), toast = "Отчёт скопирован — вставь его в сообщение")
        FiolKidApp(working, "0.6.0", {}, time = 3f, now = now, initialTab = Tab.Settings, data = data)
    }
}

/** Вся страница настроек целиком (высокий экран) — с найденным обновлением и тем, что в нём нового. */
class TallScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 5200), theme = "android:Theme.Material.NoActionBar")

    @Test
    fun settingsFull() = paparazzi.snapshot {
        val notes = "После обновления программа пишет «FiolKid обновлён до …», а здесь видно, что нового в версии." +
            "\n\n### Как установить\n…"
        val base = ScreenshotTest().sample
        val data = base.copy(update = UpdateState("Вышла версия 0.3.2", Release("0.3.2", "", notes)))
        FiolKidApp(HomeState(Power.On, HomeState.looks(Look.Working, Look.Working, Look.Working)), "0.3.1", {}, time = 3f, initialTab = Tab.Settings, data = data)
    }

    /** Настройки с добавленной подпиской. */
    @Test
    fun settingsVpn() = paparazzi.snapshot {
        val data = ScreenshotTest().sample.copy(vpn = vpnSample)
        val looks = HomeState.looks(Service.entries.associateWith { Look.Working })
        FiolKidApp(HomeState(Power.On, looks), "0.7.0", {}, time = 3f, initialTab = Tab.Settings, data = data)
    }
}
