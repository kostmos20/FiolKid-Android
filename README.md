# FiolKid для Android

Версия FiolKid для телефонов: одна кнопка «Включить» — и YouTube, Discord и Telegram снова работают.
С VPN-подпиской через VPN идут ещё звонки Telegram, нейросети и заблокированные сайты.
Тот же зимний интерфейс, что и в [версии для компьютера](https://github.com/kostmos20/FiolKid).

> Пока это тестовая версия.

## Как установить

1. Скачай **[FiolKid.apk](https://github.com/kostmos20/FiolKid-Android/releases/latest/download/FiolKid.apk)** на телефон.
2. Открой файл. Если телефон спросит — разреши установку из этого источника.
3. Открой FiolKid и нажми «Включить». Android спросит разрешение на VPN-подключение — разреши.

Нужен Android 7 или новее. Root не нужен.

> Google Play Защита может написать, что приложение заблокировано или «вредоносное». Это ложная тревога: так она
> реагирует на программы не из Google Play, которые создают VPN (ByeByeDPI и похожие — так же). Нажми
> «Подробнее» → «Всё равно установить».

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
| Telegram | свой WebSocket-прокси, как tg-ws-proxy в версии для ПК |
| VPN по подписке: Telegram со звонками, YouTube, нейросети, заблокированные сайты | [sing-box](https://github.com/SagerNet/sing-box) 1.14.3, тот же, что на ПК: hev-socks5-tunnel отдаёт трафик ему, он раскладывает по спискам между серверами подписки и byedpi |

sing-box (GPL-3.0) — готовая сборка с GitHub: при сборке Gradle сам скачивает её, сверяет SHA-256 и кладёт в
приложение как `libsingbox.so` (в git не хранится, кэш — `Android/.deps`). Списки адресов — `app/src/main/assets/vpn`,
те же, что в версии для ПК.

Kotlin + Jetpack Compose. Исходники byedpi и hev-socks5-tunnel (обе под MIT) лежат как есть в `app/src/main/jni`.

## Сборка и выпуск

Нужны JDK 17, Android SDK и NDK 27 (без интерфейса Android Studio). Скриншоты экранов без телефона:
`gradlew recordPaparazziDebug` → `app/src/test/snapshots/images`.

Выпуск: поднять `versionName` в `app/build.gradle.kts` и запустить
`tools\publish-release.ps1 -NotesFile заметки.txt` — соберёт подписанный APK и выложит его в Releases.
