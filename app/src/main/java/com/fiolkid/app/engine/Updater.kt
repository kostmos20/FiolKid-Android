package com.fiolkid.app.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Новая версия на GitHub. */
data class Release(val version: String, val url: String, val notes: String)

/** Что показывать про обновления: строка состояния, найденная версия и идёт ли скачивание. */
data class UpdateState(val status: String = "", val available: Release? = null, val busy: Boolean = false)

/**
 * Автообновление, как в версии для компьютера: раз в 6 часов (и по кнопке) смотрим последний выпуск в
 * репозитории kostmos20/FiolKid-Android, скачиваем FiolKid.apk и отдаём установщику Android. Поверх ставится
 * только APK, подписанный тем же ключом, — это проверяем мы и потом ещё раз сам Android.
 */
object Updater {
    private const val REPO = "kostmos20/FiolKid-Android"
    private const val PREFS = "updates"
    private const val KEY_CHECKED = "checked"
    private const val PERIOD = 6 * 60 * 60 * 1000L

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private fun set(status: String, available: Release? = _state.value.available, busy: Boolean = false) {
        _state.value = UpdateState(status, available, busy)
    }

    /** Проверить, если давно не проверяли. */
    suspend fun checkIfDue(context: Context, current: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (_state.value.available == null && System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < PERIOD) return
        check(context, current)
    }

    suspend fun check(context: Context, current: String) = withContext(Dispatchers.IO) {
        if (_state.value.busy) return@withContext
        set("Проверяю…", busy = true)
        try {
            val json = get("https://api.github.com/repos/$REPO/releases/latest")
            val tag = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == "FiolKid.apk" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
            if (apk != null && newer(tag, current)) {
                val release = Release(tag, apk.getString("browser_download_url"), json.optString("body"))
                set("Вышла версия $tag", release)
            } else {
                set("Установлена последняя версия", null)
            }
        } catch (e: Exception) {
            set("Не удалось проверить: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Скачать и поставить найденную версию. Android покажет своё окно «Обновить приложение?». */
    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        val release = _state.value.available ?: return@withContext
        if (_state.value.busy) return@withContext
        // Один раз: Android должен разрешить FiolKid ставить приложения
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            set("Разреши FiolKid устанавливать приложения и нажми «Обновить» ещё раз")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@withContext
        }
        try {
            val file = File(context.cacheDir, "update.apk")
            download(release.url, file) { percent -> set("Скачиваю версию ${release.version}… $percent%", busy = true) }
            if (!sameSigner(context, file)) {
                file.delete()
                set("Скачанный файл подписан не нашим ключом — не ставлю")
                return@withContext
            }
            set("Устанавливаю…", busy = true)
            commit(context, file)
        } catch (e: Exception) {
            set("Не удалось обновить: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** [done] — новая версия встала; иначе её можно попробовать поставить ещё раз. */
    internal fun installFinished(message: String, done: Boolean) = set(message, if (done) null else _state.value.available)

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "FiolKid-Android")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        try {
            if (connection.responseCode == 404) throw IllegalStateException("выпусков пока нет")
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, file: File, progress: (Int) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "FiolKid-Android")
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            progress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** У скачанного APK тот же пакет и тот же ключ подписи, что у установленной программы. */
    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun fingerprints(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val ours = fingerprints(installed)
        return ours.isNotEmpty() && ours == fingerprints(archive)
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Ответ установщика Android: показать окно подтверждения или сообщить, чем кончилось. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installFinished("Обновлено", done = true)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.installFinished("Обновление отменено", done = false)
            else -> Updater.installFinished(
                "Не установилось: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка установщика"}",
                done = false,
            )
        }
    }
}
