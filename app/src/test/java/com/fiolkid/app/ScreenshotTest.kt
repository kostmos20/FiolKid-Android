package com.fiolkid.app

import app.cash.paparazzi.DeviceConfig
import com.fiolkid.app.engine.Release
import com.fiolkid.app.engine.UpdateState
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/** Снимки главного экрана без телефона: gradlew recordPaparazziDebug → app/src/test/snapshots/images. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:Theme.Material.NoActionBar")

    private val now = LocalDateTime.of(2026, 10, 29, 12, 0, 0)

    @Test
    fun off() = paparazzi.snapshot { FiolKidApp(HomeState(), "0.1.0", {}, time = 3f, now = now) }

    @Test
    fun on() = paparazzi.snapshot {
        FiolKidApp(HomeState(Power.On, HomeState.looks(Look.Working, Look.Working)), "0.1.0", {}, time = 3f, now = now)
    }

    @Test
    fun mixed() = paparazzi.snapshot {
        val looks = mapOf(Service.YouTube to Look.Working, Service.Discord to Look.Working, Service.Telegram to Look.Checking, Service.Ai to Look.NeedsKey)
        FiolKidApp(HomeState(Power.On, looks), "0.1.0", {}, time = 3f, now = now)
    }

    private val newVersion = UpdateUi(UpdateState("Вышла версия 0.3.0", Release("0.3.0", "", "")), {}, {})

    @Test
    fun updateLink() = paparazzi.snapshot { FiolKidApp(HomeState(), "0.2.0", {}, time = 3f, now = now, update = newVersion) }

    @Test
    fun settings() = paparazzi.snapshot {
        FiolKidApp(HomeState(), "0.2.0", {}, time = 3f, now = now, initialTab = Tab.Settings, update = newVersion)
    }
}
