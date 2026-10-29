# Выпуск FiolKid для Android: сборка подписанного APK и выкладка на GitHub (kostmos20/FiolKid-Android, Releases).
# Программы на телефонах раз в 6 часов спрашивают GitHub, видят новую версию и предлагают «Обновить».
#
#   powershell -ExecutionPolicy Bypass -File Android\tools\publish-release.ps1 -NotesFile notes.txt
#
# Перед запуском: поднять versionName в app\build.gradle.kts. Нужны: инструменты Android в F:\Program\AndroidTools,
# ключ подписи F:\Program\FiolKid-tools\fiolkid-android.jks и gh (вход как владелец репозитория).
param([Parameter(Mandatory)][string]$NotesFile)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$repo = 'kostmos20/FiolKid-Android'

$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
$gh = if ($ghCommand) { $ghCommand.Source } else { 'F:\Program\FiolKid-tools\gh\bin\gh.exe' }
if (-not (Test-Path $gh)) { throw "Не нашёл GitHub CLI (gh): $gh" }
function gh { & $gh @args }

$tools = 'F:\Program\AndroidTools'
$env:JAVA_HOME = "$tools\jdk17"
$env:ANDROID_HOME = "$tools\sdk"
$env:GRADLE_USER_HOME = "$tools\gradle-home"
$env:ANDROID_USER_HOME = "$tools\android-home"

$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
if ($gradle -notmatch 'versionName = "([0-9.]+)"') { throw 'Не нашёл versionName в app\build.gradle.kts' }
$version = $Matches[1]
$tag = "v$version"

if (git -C $root status --porcelain) { throw 'Есть несохранённые изменения — сначала git commit' }
$released = $false
try { gh release view $tag --repo $repo *> $null; $released = ($LASTEXITCODE -eq 0) } catch { $released = $false }
if ($released) { throw "Выпуск $tag уже есть — подними versionName в app\build.gradle.kts" }

# Код — в тот же репозиторий, что и выпуски: выпуск соответствует коду на GitHub
git -C $root push
if ($LASTEXITCODE -ne 0) { throw 'git push не прошёл' }

Write-Host "Собираю FiolKid для Android $version…" -ForegroundColor Cyan
Push-Location $root
try {
    & .\gradlew.bat assembleRelease --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'Сборка не удалась' }
} finally { Pop-Location }

$release = Join-Path $root 'release'
New-Item -ItemType Directory -Force $release | Out-Null
$apk = Join-Path $release 'FiolKid.apk'
Copy-Item (Join-Path $root 'app\build\outputs\apk\release\app-release.apk') $apk -Force

$notes = [IO.File]::ReadAllText($NotesFile, [Text.Encoding]::UTF8).Trim()
$body = @"
$notes

### Как установить
Скачай **FiolKid.apk** (ниже, в разделе Assets) на телефон и открой его. Если телефон спросит — разреши установку из этого источника.
Уже стоит FiolKid — ничего качать не нужно: программа сама предложит «Обновить».

Прямая ссылка на последнюю версию: https://github.com/$repo/releases/latest/download/FiolKid.apk
"@
$bodyFile = Join-Path $env:TEMP "fiolkid-android-notes-$version.md"
[IO.File]::WriteAllText($bodyFile, $body, (New-Object Text.UTF8Encoding $false))

Write-Host "Выкладываю $tag на GitHub…" -ForegroundColor Cyan
gh release create $tag $apk --repo $repo --title "FiolKid для Android $version" --notes-file $bodyFile
if ($LASTEXITCODE -ne 0) { throw 'GitHub не принял выпуск' }
Write-Host "ВСЁ ГОТОВО: FiolKid для Android $version выложена." -ForegroundColor Green
