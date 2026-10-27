package com.richkobe.piikandroid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saving files to the public Downloads folder, and the debug log bundle. */
object Diagnostics {
    fun logDir(context: Context) = File(context.filesDir, "piik/logs").apply { mkdirs() }

    /** Saves [bytes] to Downloads (no storage permission needed on Android 10+). */
    fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * Everything useful for debugging hosting, as one text file in Downloads.
     * 返回**完整保存路径**（/storage/emulated/0/Download/<文件名>），toast 直接展示，
     * 用户能去文件管理器按路径找到（2026-10-27 用户要求，原来只给文件名）。
     */
    fun saveDebugLog(context: Context): String? {
        val sb = StringBuilder()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        sb.append("Piik for Android debug log\n")
        sb.append("time: ").append(Date()).append('\n')
        sb.append("app: ").append(version).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("hosting: ").append(HostState.phase).append(' ').append(HostState.pageUrl ?: "").append('\n')
        sb.append("lan: ").append(runCatching { NetInfo.lanAddresses(context) }.getOrNull()).append('\n')
        sb.append("dns: ").append(runCatching { NetInfo.dnsServers(context) }.getOrNull()).append('\n')
        sb.append("webview: ").append(runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()).append('\n')

        sb.append("\n===== engine output =====\n").append(HostState.logText()).append('\n')

        // Piik's own diagnostics (--debug), newest last, capped.
        val files = logDir(context).walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        for (f in files) {
            sb.append("\n===== ").append(f.name).append(" =====\n")
            val text = runCatching { f.readText() }.getOrDefault("")
            sb.append(if (text.length > 1_500_000) text.takeLast(1_500_000) else text).append('\n')
        }

        // This app's own logcat (capture, encoder, WebView console).
        sb.append("\n===== logcat =====\n")
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "4000", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).start()
            sb.append(p.inputStream.bufferedReader().readText())
        }.onFailure { sb.append("logcat unavailable: ").append(it.message) }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "piik-debug-$stamp.txt"
        val uri = saveToDownloads(context, name, "text/plain", sb.toString().toByteArray()) ?: return null
        return downloadsPath(context, uri) ?: "/storage/emulated/0/Download/$name"
    }

    /** MediaStore 行的真实文件系统路径（_data 列）；查不到返回 null 由调用方兜底。 */
    private fun downloadsPath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
