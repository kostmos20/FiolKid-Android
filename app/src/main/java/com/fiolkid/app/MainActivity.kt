package com.fiolkid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fiolkid.app.engine.Engine
import com.fiolkid.app.engine.FiolVpnService
import com.fiolkid.app.engine.Updater
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Android один раз спрашивает «Разрешить FiolKid создать VPN-подключение?»
    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) send(FiolVpnService.ACTION_START)
        else Engine.note("VPN не разрешён — без него обход не работает")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by Engine.state.collectAsState()
            val log by Engine.log.collectAsState()
            val update by Updater.state.collectAsState()
            FiolKidApp(
                state = state, version = BuildConfig.VERSION_NAME, onPower = { power(state.power) }, log = log,
                update = UpdateUi(
                    update,
                    onCheck = { lifecycleScope.launch { Updater.check(this@MainActivity, BuildConfig.VERSION_NAME) } },
                    onInstall = { lifecycleScope.launch { Updater.install(this@MainActivity) } },
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Раз в 6 часов: не вышла ли новая версия
        lifecycleScope.launch { Updater.checkIfDue(this@MainActivity, BuildConfig.VERSION_NAME) }
    }

    private fun power(current: Power) {
        if (current != Power.Off) {
            send(FiolVpnService.ACTION_STOP)
            return
        }
        // Android 13+: разрешение на уведомление «FiolKid включён» (без него обход работает, просто не видно)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        val ask = VpnService.prepare(this)
        if (ask != null) askVpn.launch(ask) else send(FiolVpnService.ACTION_START)
    }

    private fun send(action: String) {
        startService(Intent(this, FiolVpnService::class.java).setAction(action))
    }
}
