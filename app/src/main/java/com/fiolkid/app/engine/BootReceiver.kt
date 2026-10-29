package com.fiolkid.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * «Запускать вместе с телефоном»: после включения телефона сразу включаем обход — если пользователь так
 * выбрал и VPN уже разрешён (спросить разрешение без открытой программы Android не даёт).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Options.state.value.autostart || VpnService.prepare(context) != null) return
        Engine.note("Телефон включён — запускаю обход")
        ContextCompat.startForegroundService(context, Intent(context, FiolVpnService::class.java).setAction(FiolVpnService.ACTION_START))
    }
}
