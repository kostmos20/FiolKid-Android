package com.fiolkid.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.fiolkid.app.engine.Engine
import com.fiolkid.app.engine.FiolVpnService
import com.fiolkid.app.engine.Options
import com.fiolkid.app.engine.Report
import com.fiolkid.app.engine.StrategyMode
import com.fiolkid.app.engine.TelegramProxy
import android.content.ActivityNotFoundException
import android.net.Uri
import com.fiolkid.app.engine.TrafficMeter
import com.fiolkid.app.engine.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Android один раз спрашивает «Разрешить FiolKid создать VPN-подключение?»
    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) FiolVpnService.send(this, FiolVpnService.ACTION_START)
        else Engine.note("VPN не разрешён — без него обход не работает")
    }

    private val toast = MutableStateFlow<String?>(null)
    private var toastJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = AppActions(
            check = {
                if (Engine.state.value.power == Power.Off) showToast("Сначала включи FiolKid на главной")
                else {
                    showToast("Проверяю…")
                    FiolVpnService.send(this, FiolVpnService.ACTION_CHECK)
                }
            },
            copyReport = ::copyReport,
            shareReport = ::shareReport,
            setMode = { mode -> Options.update { it.copy(mode = mode) } },
            pickStrategy = ::pickStrategy,
            benchmark = { FiolVpnService.send(this, FiolVpnService.ACTION_BENCHMARK) },
            setNewYear = { on -> Options.update { it.copy(newYear = on) } },
            setAutostart = { on -> Options.update { it.copy(autostart = on) } },
            checkUpdate = { lifecycleScope.launch { Updater.check(this@MainActivity, BuildConfig.VERSION_NAME) } },
            installUpdate = { lifecycleScope.launch { Updater.install(this@MainActivity) } },
            connectTelegram = ::connectTelegram,
            restartTelegram = {
                showToast("Перезапускаю прокси Telegram…")
                FiolVpnService.send(this, FiolVpnService.ACTION_RESTART_TELEGRAM)
            },
        )
        setContent {
            val state by Engine.state.collectAsState()
            val data = AppData(
                log = Engine.log.collectAsState().value,
                update = Updater.state.collectAsState().value,
                options = Options.state.collectAsState().value,
                results = Engine.results.collectAsState().value,
                current = Engine.current.collectAsState().value,
                traffic = TrafficMeter.traffic.collectAsState().value,
                lastCheck = Engine.lastCheck.collectAsState().value,
                telegram = TelegramProxy.state.collectAsState().value,
                toast = toast.collectAsState().value,
            )
            FiolKidApp(state = state, version = BuildConfig.VERSION_NAME, onPower = { power(state.power) }, data = data, actions = actions)
        }
        Updater.justUpdated?.let {
            showToast("FiolKid обновлён до $it", 4000)
            Updater.forgetJustUpdated()
        }
    }

    override fun onResume() {
        super.onResume()
        // Раз в 6 часов: не вышла ли новая версия
        lifecycleScope.launch { Updater.checkIfDue(this@MainActivity, BuildConfig.VERSION_NAME) }
    }

    private fun power(current: Power) {
        if (current != Power.Off) {
            FiolVpnService.send(this, FiolVpnService.ACTION_STOP)
            return
        }
        // Android 13+: разрешение на уведомление «FiolKid включён» (без него обход работает, просто не видно)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val ask = VpnService.prepare(this)
        if (ask != null) askVpn.launch(ask) else FiolVpnService.send(this, FiolVpnService.ACTION_START)
    }

    /** Выбор способа в списке — это ручной режим; если включено, переключаемся сразу. */
    private fun pickStrategy(name: String) {
        Options.update { it.copy(mode = StrategyMode.Manual, manual = name) }
        if (Engine.state.value.power == Power.On) {
            showToast("Переключаюсь на «$name»…")
            FiolVpnService.send(this, FiolVpnService.ACTION_APPLY, name)
        }
    }

    /** Открыть в Telegram ссылку на наш прокси — он сам предложит его добавить. */
    private fun connectTelegram() {
        if (Engine.state.value.power == Power.Off) showToast("Сначала включи FiolKid — прокси работает вместе с ним")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TelegramProxy.link)))
        } catch (_: ActivityNotFoundException) {
            showToast("Telegram не установлен")
        }
    }

    private fun copyReport() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FiolKid", Report.build(this)))
        showToast("Отчёт скопирован — вставь его в сообщение")
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Report.build(this))
        startActivity(Intent.createChooser(send, "Отправить отчёт"))
    }

    private fun showToast(text: String, millis: Long = 2500) {
        toastJob?.cancel()
        toast.value = text
        toastJob = lifecycleScope.launch {
            delay(millis)
            toast.value = null
        }
    }
}
