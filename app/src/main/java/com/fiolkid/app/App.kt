package com.fiolkid.app

import android.app.Application
import com.fiolkid.app.engine.Engine
import com.fiolkid.app.engine.Options
import com.fiolkid.app.engine.TelegramProxy
import com.fiolkid.app.engine.TrafficMeter
import com.fiolkid.app.engine.Updater
import java.io.File

/**
 * Если программа упадёт, причина сохранится в файл и при следующем запуске окажется в «Журнале» —
 * чтобы тестер мог прислать скриншот.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Options.load(this)
        Engine.init(this)
        TrafficMeter.load(this)
        TelegramProxy.load(this)
        Engine.noteUnexpectedClose(updated = Updater.noteVersion(this, BuildConfig.VERSION_NAME))
        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) {
            Engine.note("Прошлый раз программа упала:")
            crash.readLines().take(12).forEach { Engine.note(it.trim()) }
            crash.delete()
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crash.writeText(error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
    }
}
