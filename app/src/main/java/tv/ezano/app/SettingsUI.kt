package tv.ezano.app

import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun SettingsScreen(a: MainActivity,onProviders: ()->Unit) {
    val s by a.store.libraryState();var pin by remember {mutableStateOf(false)};var backup by remember {mutableStateOf<String?>(null)};var hidden by remember {mutableStateOf(false)}
    val hiddenChannels by uiListState(uiKey(s.channels),s.hidden) { s.channels.searchCatalog {it.id in s.hidden} }
    fun prefs(change: (Prefs)->Prefs) {a.store.update {it.copy(prefs=change(it.prefs))}}
    LazyColumn(Modifier.padding(horizontal=28.dp),contentPadding=PaddingValues(bottom=35.dp)) {
        item {Heading("Make yourself at home.","Playback, your library, and the finer details")}
        item {SettingRow("Your playlists","Manage Xtream Codes, M3U and Stalker sources","Manage",onProviders)}
        item {SettingRow("Resume last channel","Open your last live channel when Ezano starts",if(s.prefs.resumeLast) "On" else "Off"){prefs {it.copy(resumeLast=!it.resumeLast)}}}
        item {SettingRow("Auto frame rate","Request seamless refresh-rate matching where the TV supports it",if(s.prefs.afr) "On" else "Off"){prefs {it.copy(afr=!it.afr)};a.message="Applies to the next stream you open"}}
        item {SettingRow("Live channel previews","Play a muted preview while browsing; uses a provider connection",if(s.prefs.preview) "On" else "Off"){prefs {it.copy(preview=!it.preview)}}}
        item {SettingRow("Player panel duration","How long playback controls stay visible","${s.prefs.panelSeconds} seconds"){prefs {it.copy(panelSeconds=when(it.panelSeconds){5->7;7->12;else->5})}}}
        item {SettingRow("Panel transparency","Set the opacity of player panels","${(s.prefs.panelOpacity*100).toInt()}%"){prefs {it.copy(panelOpacity=when {it.panelOpacity>.95f->.65f;it.panelOpacity<.7f->.85f;else->1f})}}}
        item {SettingRow("Accent color","Your focus highlight and primary controls",if(s.prefs.accent=="white") "White" else "Classic red"){prefs {it.copy(accent=if(it.accent=="white") "red" else "white")}}}
        item {SettingRow("Reduce motion","Disable focus scaling animations",if(s.prefs.reduceMotion) "On" else "Off"){prefs {it.copy(reduceMotion=!it.reduceMotion)}}}
        item {SettingRow("Automatic subtitles","Prefer English captions from streams and providers; use your saved OpenSubtitles key for matching titles",if(s.prefs.autoSubtitles) "On" else "Off"){prefs {it.copy(autoSubtitles=!it.autoSubtitles)}}}
        item {SettingRow("Caption background","Show subtitles and live captions on a black box instead of a clear background",if(s.prefs.subtitleBackground) "Black" else "Clear"){prefs {it.copy(subtitleBackground=!it.subtitleBackground)}}}
        item {var setup by remember {mutableStateOf(false)};SettingRow("Live captions","Free English captions written from the program audio on this TV when a stream has no subtitles",if(a.speechEnabled() && SpeechModel.installed(a)) "On" else "Off"){setup=true};if(setup)SpeechSetup(a){setup=false}}
        item {SettingRow("Guide refresh interval","Refresh while Ezano is open and when the saved guide is stale","${s.prefs.guideHours} hours"){prefs {it.copy(guideHours=when(it.guideHours){6->12;12->24;else->6})}}}
        item {SettingRow("Keep previous guide entries","Retain past programs for catch-up browsing","${s.prefs.retentionDays} days"){prefs {it.copy(retentionDays=when(it.retentionDays){0->3;3->7;else->0})}}}
        item {SettingRow("Refresh library","Save the latest channels, movies and shows; update the guide separately","Refresh now"){a.scope.launch {if(a.store.refresh()) a.message="100% · Library saved. Ready to watch."}}}
        item {SettingRow("Retry TV guide","Fetch guide listings without reloading your channels, movies or shows","Retry guide"){if(!a.store.guideBusy.value) a.scope.launch {a.store.refreshGuide()} else a.message="The guide is updating. Your saved channels are ready to watch."}}
        item {SettingRow("Hidden content","Restore hidden groups and individual channels","Manage"){hidden=true}}
        item {SettingRow("Parental PIN","Protect channels, sections, and settings",if(s.prefs.pinHash.isBlank()) "Set PIN" else "Change PIN"){a.admin {pin=true}}}
        if(s.prefs.pinHash.isNotBlank()) {
            items(listOf("Live TV","Movies","Series")) {section->SettingRow("Protect $section","Require your PIN to open this section",if(section in s.prefs.lockedSections) "Locked" else "Unlocked"){a.admin {prefs {it.copy(lockedSections=if(section in it.lockedSections) it.lockedSections-section else it.lockedSections+section)}}}}
            item {SettingRow("Disable parental PIN","Remove the PIN and content locks","Disable"){a.admin {prefs {it.copy(pinHash="",pinSalt="",lockedSections=emptySet(),pinFailures=0,pinBlockedUntil=0)}}}}
        }
        item {SettingRow("Scheduled alarm access","${if(Scheduler.exact(a)) "Precise scheduling is available" else "Timing is currently approximate"}; the device must stay powered","Open settings"){try {if(Build.VERSION.SDK_INT>=31) a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${a.packageName}"))) else a.message="Exact scheduling is available on this device"}catch(_:Exception){a.message="This device manages alarm access automatically"}}}
        item {SettingRow("Notification permission","Show program reminders and recording status","Enable"){a.permissions();if(Build.VERSION.SDK_INT<33) a.message="Notifications are managed by your Fire TV system settings"}}
        item {SettingRow("Back up your library","Passphrase-encrypted playlists, preferences, favorites and schedules","Export"){a.admin {backup="export"}}}
        item {SettingRow("Restore a backup","Replace settings and playlists; recording video files are separate","Restore"){a.admin {backup="restore"}}}
        item {Spacer(Modifier.height(22.dp));Text("EZANO TV  /  ${BuildConfig.VERSION_NAME}",color=LocalAccent.current,fontSize=12.sp);Text("Built for your big screen. Streaming quality follows your source, network, HDMI setup and hardware decoder. Refresh-rate matching varies by Fire TV model.",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=8.dp));Text("Content artwork comes from your provider. Demo films: Blender Foundation, CC BY 3.0. No bundled commercial playlists.",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=8.dp))}
    }
    if(pin) {var value by remember {mutableStateOf("")};var again by remember {mutableStateOf("")};var error by remember {mutableStateOf("")};Modal("Set your parental PIN",{pin=false}) {Field("4–8 digit PIN",value,{value=it.filter(Char::isDigit).take(8)},true);Field("Confirm PIN",again,{again=it.filter(Char::isDigit).take(8)},true);if(error.isNotBlank())Text(error,color=Color(0xFFFF8592),fontSize=12.sp);EzButton("Save PIN",primary=true,onClick={if(value.length<4 || value!=again) error="Use at least 4 digits and matching entries" else a.scope.launch {a.store.setPin(value);pin=false;a.message="Parental PIN saved"}})}}
    backup?.let {mode->var pass by remember {mutableStateOf("")};var confirm by remember {mutableStateOf("")};var error by remember {mutableStateOf("")};Modal(if(mode=="export") "Create encrypted backup" else "Restore encrypted backup",{backup=null}) {Text(if(mode=="export") "Choose a passphrase you can remember. It protects the provider credentials in your backup." else "This replaces your current library and cancels current recording jobs. You need the backup's passphrase.",color=Muted,fontSize=12.sp);Field("Backup passphrase (8+ characters)",pass,{pass=it},true);if(mode=="export")Field("Confirm passphrase",confirm,{confirm=it},true);if(error.isNotBlank())Text(error,color=Color(0xFFFF8592),fontSize=12.sp);EzButton(if(mode=="export") "Choose save location" else "Choose backup & restore",primary=true,onClick={if(pass.length<8 || (mode=="export" && confirm!=pass))error="Use at least 8 characters and matching passphrases" else{a.backup(pass,mode=="restore");backup=null}})}}
    if(hidden) Modal("Hidden channels & groups",{hidden=false}) {if(s.hidden.isEmpty() && s.hiddenGroups.isEmpty())Text("Nothing is hidden.",color=Muted);s.hiddenGroups.forEach {g->EzButton("Show group: $g",onClick={a.store.update {it.copy(hiddenGroups=it.hiddenGroups-g)}})};hiddenChannels.forEach {c->EzButton("Show ${c.name}",onClick={a.store.update {it.copy(hidden=it.hidden-c.id)}})}}
}
@Composable fun RecordingsScreen(a: MainActivity) {
    val s by a.store.state.collectAsState();var delete by remember {mutableStateOf<Recording?>(null)}
    LazyColumn(Modifier.padding(horizontal=28.dp),contentPadding=PaddingValues(bottom=35.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        item {Heading("On your time.","Your recordings, scheduled programs and reminders")}
        if(s.recordings.isEmpty()) item {Text("Open a live channel or a program in the guide to record, set a schedule, or create a reminder.",color=Muted,fontSize=13.sp)}
        items(s.recordings.sortedByDescending {it.start},key={it.id}) {r->Column {Text(r.title,color=Color.White,fontSize=17.sp);Text("${r.channel.name} · ${java.time.Instant.ofEpochMilli(r.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · ${timeLabel(r.start)} – ${timeLabel(r.end)} · ${r.repeat}",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=5.dp));Row(Modifier.padding(top=9.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {Tag(if(r.reminder) "REMINDER · ${r.status.uppercase()}" else r.status.uppercase());if(r.path.isNotBlank() && File(r.path).exists() && File(r.path).length()>0 && r.status!="recording") EzButton("Watch",onClick={a.guarded(r.channel){a.startPlayback(r.channel.copy(id="recording:${r.id}",url=Uri.fromFile(File(r.path)).toString(),kind=Kind.MOVIE,name=r.title,provider="local"))}});if(r.status in listOf("scheduled","recording"))EzButton(if(r.status=="recording") "Stop" else "Cancel",onClick={Scheduler.cancel(a,r);a.store.update {it.copy(recordings=it.recordings.map {v->if(v.id==r.id) v.copy(status="cancelled") else v})}});if(r.status!="recording")EzButton("Delete",onClick={delete=r})};if(r.error.isNotBlank())Text(r.error,color=Color(0xFFFFCA89),fontSize=11.sp,modifier=Modifier.padding(top=7.dp))}}
        item {Text("Recordings are saved in this app's external Movies folder. Uninstalling Ezano removes app-owned recordings. Export important files before uninstalling.",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=18.dp))}
    }
    delete?.let {r->Modal("Delete ${r.title}?",{delete=null}) {Text("This removes the saved recording file and its entry. It cannot be undone.",color=Muted,fontSize=12.sp);EzButton("Delete recording",onClick={Scheduler.cancel(a,r);if(r.path.isNotBlank()) {val file=File(r.path);val allowed=listOfNotNull(a.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),File(a.filesDir,"recordings"));if(allowed.any {file.canonicalPath.startsWith(it.canonicalPath+File.separator)})file.delete()};a.store.update {it.copy(recordings=it.recordings.filterNot {v->v.id==r.id})};delete=null})}}
}
