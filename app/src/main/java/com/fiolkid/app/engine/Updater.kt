package com.fiolkid.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
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
            val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "FiolKid.apk")
            download(release.url, file) { percent -> set("Скачиваю версию ${release.version}… $percent%", busy = true) }
            if (!sameSigner(context, file)) {
                file.delete()
                set("Скачанный файл подписан не нашим ключом — не ставлю")
                return@withContext
            }
            openInstaller(context, file)
            set("Открыл установку — нажми «Обновить». Если Google предупредит: «Подробнее» → «Всё равно установить»")
        } catch (e: Exception) {
            set("Не удалось обновить: ${e.message ?: e.javaClass.simpleName}")
        }
    }

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

    /**
     * Обычное окно установки Android — то же, что при открытии APK из файлов. Сессия установки
     * (PackageInstaller) на Xiaomi после предупреждения Play Защиты молча отменялась.
     */
    private fun openInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
