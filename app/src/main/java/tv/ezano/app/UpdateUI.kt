package tv.ezano.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface UpdateStep {
    data object Offer : UpdateStep
    /** Android needs "Install unknown apps" for Ezano first; [manual] shows the settings path to follow by hand. */
    data class Permission(val manual: Boolean) : UpdateStep
    data object Downloading : UpdateStep
    /** The system installer was opened; Install reopens it if it was dismissed. */
    data class Ready(val file: File) : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

private const val FIRE_TV_PATH = "Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → Ezano TV → On"
private const val ANDROID_TV_PATH = "Other Android TV devices: Settings → Apps → Special app access → Install unknown apps → Ezano TV → Allowed"

private fun megabytes(bytes: Double) = "%.1f MB".format(bytes / (1024 * 1024))

/** Shows nothing until an update is found; then a Modal "Update available" with version, notes, and buttons "Update now" (primary, initially focused via FocusRequester + LaunchedEffect delay ~200ms, like other dialogs in the app), "Later" (snooze) and "Skip this version". Update now: if !canInstall -> explain "Allow Ezano to install updates" with a button that calls openInstallPermission (and re-checks canInstall when the activity resumes — use LocalLifecycleOwner / lifecycle events); show manual Fire TV instructions if that intent is unavailable ("Settings → My Fire TV → Developer options → Install unknown apps → Ezano TV → On"); then download with a LinearProgressIndicator + percentage, then install(). Errors show a short message and a Retry button. Back/Close = Later. */
@Composable fun UpdatePrompt(a: MainActivity) {
    val available by Updater.available.collectAsState()
    val info = available ?: return
    key(info.versionCode) { UpdateDialog(a, info) }
}

@Composable private fun UpdateDialog(a: MainActivity, info: UpdateInfo) {
    var step by remember { mutableStateOf<UpdateStep>(UpdateStep.Offer) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Set when the system "Install unknown apps" screen was opened, so returning from it is noticed.
    var awaitingPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val primary = remember { FocusRequester() }

    fun later() { job?.cancel(); Updater.snooze(a, info) }
    fun skip() { job?.cancel(); Updater.skip(a, info) }
    fun install(file: File) {
        step = if (Updater.install(a, file)) UpdateStep.Ready(file)
        else UpdateStep.Failed("This device has no app installer Ezano can open. Download Ezano-TV.apk from the GitHub releases page instead.")
    }
    fun download() {
        job?.cancel()
        progress = 0f
        step = UpdateStep.Downloading
        job = scope.launch {
            try {
                val file = Updater.download(a, info) { progress = it }
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step = UpdateStep.Failed((e as? UpdateError)?.message ?: "The download didn't finish. Check your internet connection and try again.")
            }
        }
    }
    fun start() { if (Updater.canInstall(a)) download() else step = UpdateStep.Permission(manual = false) }
    fun requestPermission() {
        when {
            Updater.canInstall(a) -> download()
            Updater.openInstallPermission(a) -> awaitingPermission = true
            else -> step = UpdateStep.Permission(manual = true)
        }
    }

    // Coming back from the system settings screen: continue straight to the download once allowed.
    DisposableEffect(a) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && step is UpdateStep.Permission) {
                if (Updater.canInstall(a)) download()
                else if (awaitingPermission) step = UpdateStep.Permission(manual = true)
                awaitingPermission = false
            }
        }
        a.lifecycle.addObserver(observer)
        onDispose { a.lifecycle.removeObserver(observer) }
    }

    // Never interrupt a video with the offer; it appears once playback ends.
    if (a.fullPlayer && step == UpdateStep.Offer) return

    val title = when (step) {
        UpdateStep.Offer -> "Update available"
        is UpdateStep.Permission -> "Allow Ezano to install updates"
        UpdateStep.Downloading -> "Downloading update"
        is UpdateStep.Ready -> "Ready to install"
        is UpdateStep.Failed -> "Update didn't finish"
    }
    Modal(title, onDismiss = { later() }) {
        LaunchedEffect(step) { delay(200); runCatching { primary.requestFocus() } }
        when (val current = step) {
            UpdateStep.Offer -> {
                Text("Ezano TV ${info.versionName} is ready to download. You have ${BuildConfig.VERSION_NAME}.", color = Color.White, fontSize = 15.sp)
                Text("${megabytes(info.size.toDouble())} · your playlists, favorites and settings are kept.", color = Muted, fontSize = 12.sp)
                if (info.notes.isNotBlank()) Text(info.notes, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EzButton("Update now", Icons.Outlined.SystemUpdateAlt, primary = true, modifier = Modifier.focusRequester(primary), onClick = { start() })
                    EzButton("Later", onClick = { later() })
                    EzButton("Skip this version", onClick = { skip() })
                }
            }
            is UpdateStep.Permission -> {
                Text("Android asks once before Ezano can install its own updates. Turn on Ezano TV in \"Install unknown apps\", then press Back to return here and the update will continue.", color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                if (current.manual) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("If the setting didn't open, change it yourself:", color = Muted, fontSize = 12.sp)
                    Text(FIRE_TV_PATH, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(ANDROID_TV_PATH, color = Muted, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EzButton(if (current.manual) "Check again" else "Open settings", Icons.Outlined.Settings, primary = true, modifier = Modifier.focusRequester(primary), onClick = { requestPermission() })
                    EzButton("Later", onClick = { later() })
                }
            }
            UpdateStep.Downloading -> {
                Text("Downloading Ezano TV ${info.versionName}…", color = Color.White, fontSize = 14.sp)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = LocalAccent.current)
                Text("${(progress * 100).toInt()}% · ${megabytes(progress.toDouble() * info.size)} of ${megabytes(info.size.toDouble())}", color = Muted, fontSize = 12.sp)
                EzButton("Cancel", modifier = Modifier.focusRequester(primary), onClick = { job?.cancel(); step = UpdateStep.Offer })
            }
            is UpdateStep.Ready -> {
                Text("Follow the installer to finish. Ezano TV then opens on version ${info.versionName} with your library intact. If you closed the installer, choose Install.", color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EzButton("Install", Icons.Outlined.SystemUpdateAlt, primary = true, modifier = Modifier.focusRequester(primary), onClick = { install(current.file) })
                    EzButton("Later", onClick = { later() })
                }
            }
            is UpdateStep.Failed -> {
                Text(current.message, color = Color(0xFFFF8592), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EzButton("Retry", Icons.Outlined.Refresh, primary = true, modifier = Modifier.focusRequester(primary), onClick = { start() })
                    EzButton("Later", onClick = { later() })
                }
            }
        }
    }
}

/** For Settings: a row "App updates" showing current version and a "Check now" button; a forced check shows the same prompt, or a message "Ezano TV is up to date" via a.message. */
@Composable fun UpdateSettingsRow(a: MainActivity) {
    val checking by Updater.checking.collectAsState()
    val available by Updater.available.collectAsState()
    val detail = "Version ${BuildConfig.VERSION_NAME}" + (available?.let { " · ${it.versionName} is available" } ?: " · Ezano checks for new versions automatically")
    SettingRow("App updates", detail, if (checking) "Checking…" else "Check now") {
        if (!checking) a.scope.launch {
            val info = Updater.refresh(a, force = true)
            if (info == null) a.message =
                if (Updater.lastCheckFailed) "Couldn't check for updates. Check your internet connection and try again."
                else "Ezano TV is up to date (version ${BuildConfig.VERSION_NAME})"
        }
    }
}
