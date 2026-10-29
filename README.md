# FiolKid для Android

Версия FiolKid для телефонов: одна кнопка «Включить» — и YouTube с Discord снова работают.
Тот же зимний интерфейс, что и в [версии для компьютера](https://github.com/kostmos20/FiolKid).

> Пока это тестовая версия. Работают YouTube и Discord; Telegram и нейросети — скоро.

## Как установить

1. Скачай **[FiolKid.apk](https://github.com/kostmos20/FiolKid-Android/releases/latest/download/FiolKid.apk)** на телефон.
2. Открой файл. Если телефон спросит — разреши установку из этого источника.
3. Открой FiolKid и нажми «Включить». Android спросит разрешение на VPN-подключение — разреши.

Нужен Android 7 или новее. Root не нужен.

## Как обновлять

Ничего качать не нужно: раз в 6 часов программа сама проверяет, не вышла ли новая версия, и пишет внизу главного
экрана «Вышла версия … — обновить». Нажми, и Android предложит обновить приложение. Проверить сразу можно в
«Настройках» → «Проверить обновления».

## Если что-то не работает

Открой вкладку «Журнал», сделай скриншот и пришли его тому, кто дал тебе программу.

---

## Как устроено

Приложение поднимает системный VPN Android (`VpnService`) и само раскладывает трафик:

| Что | Как |
|---|---|
| YouTube, Discord | обход DPI на телефоне: [byedpi](https://github.com/hufrea/byedpi) |
| Трафик телефона → byedpi | [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) |
| Telegram | скоро: WebSocket-прокси, как tg-ws-proxy в версии для ПК |
| ИИ-сайты и списки | скоро: AmneziaWG по ключу `vpn://` или подписка |

Kotlin + Jetpack Compose. Исходники byedpi и hev-socks5-tunnel (обе под MIT) лежат как есть в `app/src/main/jni`.

## Сборка и выпуск

Нужны JDK 17, Android SDK и NDK 27 (без интерфейса Android Studio). Скриншоты экранов без телефона:
`gradlew recordPaparazziDebug` → `app/src/test/snapshots/images`.

Выпуск: поднять `versionName` в `app/build.gradle.kts` и запустить
`tools\publish-release.ps1 -NotesFile заметки.txt` — соберёт подписанный APK и выложит его в Releases.
