package com.fiolkid.app

import androidx.compose.ui.graphics.Color

/** Сервисы на главном экране — те же четыре карточки, что в версии для компьютера. */
enum class Service(
    val title: String,
    val description: String,
    /** Плитка работающего сервиса: res/drawable-nodpi/card_*.png */
    val plate: Int,
    val accent: Color,
) {
    YouTube("YouTube", "Видео и трансляции", R.drawable.card_youtube, Color(0xFFEF8278)),
    Discord("Discord", "Голос и сообщества", R.drawable.card_discord, Color(0xFFB4A3FF)),
    Telegram("Telegram", "Сообщения и звонки", R.drawable.card_telegram, Color(0xFF83CEFA)),
    Ai("VPN", "Нейросети и другие сайты", R.drawable.card_vpn, Color(0xFFF1D16D)),
}

/** Как выглядит карточка сервиса. Soon — в версии для телефона ещё не сделано. */
enum class Look { Off, Checking, Working, NotWorking, NeedsKey, Soon }

enum class Power { Off, Connecting, On }

/** Всё, что показывает главный экран. */
data class HomeState(
    val power: Power = Power.Off,
    val looks: Map<Service, Look> = Service.entries.associateWith { Look.Off },
) {
    companion object {
        /** Пока на телефоне работают только YouTube и Discord. */
        fun looks(youTube: Look, discord: Look) =
            mapOf(Service.YouTube to youTube, Service.Discord to discord, Service.Telegram to Look.Soon, Service.Ai to Look.Soon)
    }

    private val problems get() = looks.values.count { it == Look.NotWorking || it == Look.NeedsKey }
    private val checking get() = looks.values.any { it == Look.Checking }

    val headline: Pair<String, String>
        get() = when {
            power == Power.Off -> "Выключено" to "Нажми на кнопку, чтобы включить FiolKid."
            power == Power.Connecting -> "Подключаем…" to "Обычно это занимает несколько секунд."
            checking -> "Проверяю…" to "Сейчас всё проверю"
            problems == 0 -> "Всё работает" to "Можно пользоваться привычными сервисами."
            else -> "Почти всё работает" to "Если не наладится само — открой «Журнал» и пришли отчёт"
        }

    val powerLabel
        get() = when (power) {
            Power.Off -> "Включить"
            Power.Connecting -> "Подключаем…"
            Power.On -> "Выключить"
        }

    val summary
        get() = when {
            power == Power.Off -> "Всё в одном месте"
            power == Power.Connecting || checking -> "Проверяем доступ"
            problems == 0 -> "Все сервисы доступны"
            else -> "Не всё доступно"
        }
}
