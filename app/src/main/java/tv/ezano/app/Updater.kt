package tv.ezano.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A failure with a message written for the person holding the remote; other exceptions get a generic message. */
class UpdateError(message: String) : IOException(message)

/** One release as described by the `update.json` asset that CI publishes with every GitHub release. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val size: Long, val notes: String)

/**
 * In-app updates for the sideloaded APK: a tiny JSON feed on the latest GitHub release, a verified
 * download into the app cache, and the system package installer. Nothing here runs on the main thread
 * except the intent launches; the startup check is throttled and never throws.
 */
object Updater {
    const val FEED = "https://github.com/kingezano123/EzanoTV/releases/latest/download/update.json"
    internal const val MAX_APK = 200L * 1024 * 1024
    internal const val MAX_FEED = 64 * 1024
    internal const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    internal const val SNOOZE = 24 * 60 * 60 * 1000L
    private const val MIME = "application/vnd.android.package-archive"
    private const val PREFS = "updates"
    private val versionNamePattern = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,39}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    /** The update to offer, or null. [UpdatePrompt] shows it; snooze/skip clear it. */
    val available = MutableStateFlow<UpdateInfo?>(null)
    /** True while a check is running, for the Settings row. */
    val checking = MutableStateFlow(false)
    /** The most recent network check failed (offline, server error, bad feed). */
    @Volatile var lastCheckFailed = false
        private set
    @Volatile private var cleaned = false
    private val downloadLock = Mutex()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // GitHub's release links redirect to objects.githubusercontent.com; never allow a downgrade to http.
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val downloadClient by lazy { client.newBuilder().callTimeout(20, TimeUnit.MINUTES).build() }

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Debug builds may override the feed with SharedPreferences("updates").getString("feed") — used by device tests. */
    fun feed(context: Context): String =
        if (BuildConfig.DEBUG) prefs(context).getString("feed", null)?.trim()?.takeIf { it.isNotEmpty() } ?: FEED else FEED

    fun parse(json: String): UpdateInfo = parse(json, allowHttp = BuildConfig.DEBUG)

    internal fun parse(json: String, allowHttp: Boolean): UpdateInfo {
        val o = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("Update feed is not valid JSON") }
        val codeValue = o.opt("versionCode")
        require(codeValue is Number && codeValue.toDouble() == codeValue.toLong().toDouble() && codeValue.toLong() in 1..Int.MAX_VALUE) { "Invalid versionCode" }
        val versionName = o.optString("versionName", "").trim()
        require(versionNamePattern.matches(versionName)) { "Invalid versionName" }
        val apk = o.optString("apk", "").trim()
        val uri = try { URI(apk) } catch (e: Exception) { throw IllegalArgumentException("Invalid apk URL") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || (allowHttp && scheme == "http")) { "The apk URL must use https" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid apk URL" }
        val sha = o.optString("sha256", "").trim()
        require(sha256Pattern.matches(sha)) { "Invalid sha256" }
        val sizeValue = o.opt("size")
        require(sizeValue is Number && sizeValue.toDouble() == sizeValue.toLong().toDouble() && sizeValue.toLong() in 1..MAX_APK) { "Invalid size" }
        val notes = if (o.isNull("notes")) "" else o.optString("notes", "").trim().take(2000)
        return UpdateInfo(codeValue.toInt(), versionName, apk, sha.lowercase(), sizeValue.toLong(), notes)
    }

    internal fun toJson(info: UpdateInfo): String = JSONObject()
        .put("versionCode", info.versionCode).put("versionName", info.versionName).put("apk", info.apk)
        .put("sha256", info.sha256).put("size", info.size).put("notes", info.notes).toString()

    internal fun isNewer(info: UpdateInfo, installedCode: Int) = info.versionCode > installedCode

    /**
     * Whether to show [info] now. "Later" hides that version (and anything older) until [snoozeUntil];
     * "Skip" hides it for good, but a newer release is offered again. [force] (Settings → Check now) ignores both.
     */
    internal fun shouldOffer(info: UpdateInfo, installedCode: Int, now: Long, snoozeUntil: Long, skippedCode: Int, snoozedCode: Int = Int.MAX_VALUE, force: Boolean = false): Boolean {
        if (!isNewer(info, installedCode)) return false
        if (force) return true
        if (info.versionCode <= skippedCode) return false
        // A clock moved backwards by more than a snooze period must not hide updates for ever.
        val snoozed = now < snoozeUntil && snoozeUntil - now <= SNOOZE && info.versionCode <= snoozedCode
        return !snoozed
    }

    /** At most one network check per [CHECK_INTERVAL]; a changed feed or a clock moved backwards checks again. */
    internal fun isDue(now: Long, lastCheck: Long, force: Boolean, feedChanged: Boolean = false) =
        force || feedChanged || lastCheck <= 0 || now < lastCheck || now - lastCheck >= CHECK_INTERVAL

    /** Numeric comparison of version names ("0.10.0" > "0.9.3"); non-numeric parts are ignored. */
    internal fun compareVersionNames(a: String, b: String): Int {
        fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { "0" }; val q = y.getOrElse(i) { "0" }
            val c = if (p.length != q.length) p.length.compareTo(q.length) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }

    internal fun apkName(info: UpdateInfo) = "Ezano-TV-${info.versionName}.apk"

    /** Network check, at most once per 6 h unless force=true; remembers skipped versions and "remind me later" (24 h snooze). Returns an update only if versionCode > BuildConfig.VERSION_CODE and not skipped/snoozed (force ignores snooze/skip). Never throws for network errors (returns null). Runs on Dispatchers.IO. */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val installed = BuildConfig.VERSION_CODE
        val feed = feed(app)
        val info: UpdateInfo? = if (!isDue(now, p.getLong("lastCheck", 0), force, p.getString("lastFeed", FEED) != feed)) {
            lastCheckFailed = false
            cached(p)
        } else try {
            val fetched = fetch(feed)
            lastCheckFailed = false
            p.edit().putLong("lastCheck", now).putString("lastFeed", feed)
                .putString("last", if (isNewer(fetched, installed)) toJson(fetched) else null).apply()
            fetched
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeedStatus) {
            // The server answered (for example 404 before the first release with a feed): don't ask again for 6 h.
            lastCheckFailed = true
            p.edit().putLong("lastCheck", now).putString("lastFeed", feed).apply()
            if (force) null else cached(p)
        } catch (e: Exception) {
            lastCheckFailed = true
            if (force) null else cached(p)
        }
        info?.takeIf { shouldOffer(it, installed, now, p.getLong("snoozeUntil", 0), p.getInt("skippedCode", 0), p.getInt("snoozedCode", Int.MAX_VALUE), force) }
    }

    /** Runs [check] and publishes the result to [available] for [UpdatePrompt]. Also tidies old downloads once per launch. */
    suspend fun refresh(context: Context, force: Boolean = false): UpdateInfo? {
        val app = context.applicationContext
        checking.value = true
        try {
            if (!cleaned) { cleaned = true; withContext(Dispatchers.IO) { cleanup(app) } }
            val info = check(app, force)
            if (info != null || force) available.value = info
            return info
        } finally {
            checking.value = false
        }
    }

    private class FeedStatus(code: Int) : IOException("Update feed unavailable (HTTP $code)")

    private fun cached(p: SharedPreferences): UpdateInfo? =
        p.getString("last", null)?.let { runCatching { parse(it) }.getOrNull() }

    private fun fetch(url: String): UpdateInfo {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").header("Accept", "application/json, application/octet-stream")
            .header("User-Agent", "EzanoTV/${BuildConfig.VERSION_NAME} (Android)").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FeedStatus(response.code)
            val body = response.body ?: throw IOException("Empty update feed")
            if (body.contentLength() > MAX_FEED) throw IOException("Update feed too large")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_FEED) throw IOException("Update feed too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return parse(String(bytes, Charsets.UTF_8))
        }
    }

    fun snooze(context: Context, info: UpdateInfo) {
        prefs(context).edit().putLong("snoozeUntil", System.currentTimeMillis() + SNOOZE).putInt("snoozedCode", info.versionCode).apply()
        available.compareAndSet(info, null)
    }

    fun skip(context: Context, info: UpdateInfo) {
        val p = prefs(context)
        p.edit().putInt("skippedCode", maxOf(info.versionCode, p.getInt("skippedCode", 0))).apply()
        available.compareAndSet(info, null)
    }

    /** Downloads to cacheDir/updates/Ezano-TV-<versionName>.apk (resumable not required), reports progress 0..1, verifies size and SHA-256, deletes partial/mismatched files, returns the file. Use OkHttp with redirects followed (GitHub release downloads redirect to objects.githubusercontent.com), sensible timeouts, a 200 MB cap. Cancellation-safe. [progress] is called on a background thread. */
    suspend fun download(context: Context, info: UpdateInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val dir = File(context.applicationContext.cacheDir, "updates")
            val file = downloadTo(downloadClient, info, dir, progress)
            try {
                verifyPackage(context.applicationContext, file, info)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            file
        }
    }

    /** Pure-JVM part of [download] (unit tested with MockWebServer). */
    internal suspend fun downloadTo(http: OkHttpClient, info: UpdateInfo, dir: File, progress: (Float) -> Unit): File = coroutineScope {
        if (info.size !in 1..MAX_APK) throw UpdateError("The update has an unexpected size. Try again later.")
        if (!dir.isDirectory && !dir.mkdirs()) throw UpdateError("Could not save the update. Check free storage.")
        val target = File(dir, apkName(info))
        if (target.isFile && target.length() == info.size && sha256(target) == info.sha256) {
            progress(1f)
            return@coroutineScope target
        }
        target.delete()
        val needed = info.size + 16L * 1024 * 1024
        if (dir.usableSpace in 1 until needed) throw UpdateError("Free at least ${needed / (1024 * 1024)} MB of storage to download the update.")
        val partial = File(dir, apkName(info) + ".part")
        val call = http.newCall(Request.Builder().url(info.apk).header("User-Agent", "EzanoTV/${BuildConfig.VERSION_NAME} (Android)").build())
        // Cancelling the coroutine aborts the blocking socket read straight away.
        val watchdog = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            call.execute().use { response ->
                if (!response.isSuccessful) throw UpdateError("Download failed (HTTP ${response.code}). Try again later.")
                val body = response.body ?: throw UpdateError("Download failed. Try again later.")
                val length = body.contentLength()
                if (length >= 0 && length != info.size) throw UpdateError("The update has an unexpected size. Try again later.")
                body.byteStream().use { input ->
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var shown = -1
                        progress(0f)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > info.size || total > MAX_APK) throw UpdateError("The update has an unexpected size. Try again later.")
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            val percent = (total * 100 / info.size).toInt()
                            if (percent != shown) { shown = percent; progress(total.toFloat() / info.size) }
                        }
                        if (total != info.size) throw UpdateError("The download was incomplete. Try again.")
                    }
                }
            }
            ensureActive()
            if (hex(digest.digest()) != info.sha256) throw UpdateError("The update failed verification. Try again.")
            if (!partial.renameTo(target)) throw UpdateError("Could not save the update. Check free storage.")
            progress(1f)
            target
        } catch (e: Throwable) {
            partial.delete()
            target.delete()
            // A cancelled call surfaces as IOException("Canceled"); report it as the cancellation it is.
            if (!isActive && e !is CancellationException) throw CancellationException("Update download cancelled").apply { initCause(e) }
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Catches a wrong or differently signed APK before the installer shows a vague "App not installed". */
    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File, info: UpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw UpdateError("The downloaded update is not a valid app. Try again.")
        if (archive.packageName != context.packageName) throw UpdateError("The downloaded file is not an Ezano TV update.")
        if (versionCode(archive) != info.versionCode.toLong()) throw UpdateError("The downloaded update does not match the release. Try again later.")
        if (Build.VERSION.SDK_INT >= 28) {
            val installed = runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            val mine = signers(installed)
            val theirs = signers(archive)
            // Some builds don't report an archive's certificates; only a definite mismatch is an error.
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty())
                throw UpdateError("This update is signed with a different key, so Android can't install it over this version. Download it from GitHub instead.")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun signers(info: PackageInfo?): Set<String> {
        if (info == null || Build.VERSION.SDK_INT < 28) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        val certificates = (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory) ?: return emptySet()
        return certificates.mapNotNull { it?.toCharsString() }.toSet()
    }

    fun canInstall(context: Context): Boolean =
        try { context.packageManager.canRequestPackageInstalls() } catch (e: Exception) { false }

    /** Opens the per-app "Install unknown apps" setting (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES with package: URI); returns false if no activity handles it (some Fire OS builds) so the UI can show manual instructions. */
    fun openInstallPermission(activity: Activity): Boolean {
        val specific = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        val general = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        return listOf(specific, general).any { intent ->
            try { activity.startActivity(intent); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
        }
    }

    /** Launches the system installer for the verified APK via FileProvider content URI (authority "${context.packageName}.files"), ACTION_VIEW + "application/vnd.android.package-archive" + FLAG_GRANT_READ_URI_PERMISSION + FLAG_ACTIVITY_NEW_TASK; fall back to ACTION_INSTALL_PACKAGE if needed. Returns false if nothing can install. */
    fun install(activity: Activity, apk: File): Boolean {
        if (!apk.isFile) return false
        val uri = try { FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk) } catch (e: IllegalArgumentException) { return false }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME).addFlags(flags)
        @Suppress("DEPRECATION")
        val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, MIME).addFlags(flags)
        return listOf(view, legacy).any { intent ->
            try { activity.startActivity(intent); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
        }
    }

    /** Deletes cached update APKs older than the installed version (call on startup, cheap). */
    fun cleanup(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "updates")
        val files = dir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            val version = name.removePrefix("Ezano-TV-").removeSuffix(".apk")
            val stale = name.endsWith(".part") || !name.startsWith("Ezano-TV-") || !name.endsWith(".apk") ||
                compareVersionNames(version, BuildConfig.VERSION_NAME) <= 0
            if (stale) file.delete()
        }
    }
}
