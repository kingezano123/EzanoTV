# Install Ezano TV 0.3.1

[Direct APK](https://github.com/kingezano123/EzanoTV/releases/download/v0.3.1/Ezano-TV.apk) · [Release](https://github.com/kingezano123/EzanoTV/releases/tag/v0.3.1)

Enter the APK URL in Downloader. The permanent update URL is https://github.com/kingezano123/EzanoTV/releases/latest/download/Ezano-TV.apk . Code **8306366 downloads the older 0.2.0 release**; no new numeric code is registered for this update.

Requires Android 8+ on Android TV / Google TV or compatible Android-based Fire TV. Package tv.ezano.app; version code 11. **Install over the existing app** to retain playlists, favorites and watch progress. Uninstalling removes app data and app-owned recordings. The existing signing certificate is retained.

1. Download the APK and permit Downloader to install unknown apps when prompted.
2. Install Ezano TV and open it. Older encrypted libraries migrate automatically; the first migration can take longer than later launches.
3. New users: Playlists → Add playlist, then enter your service details. Add XMLTV for M3U if the playlist does not supply a guide.
4. Select a search field to type. Focus alone leaves the keyboard closed.
5. Back from playback saves progress and returns to browsing. Mini-player and PiP are removed.

Existing captions: Audio & subtitles → Subtitles → Automatic English. Embedded/provider English tracks take priority. Optional OpenSubtitles lookup needs your own API key.

Generated captions: **Audio & subtitles → Speech captions → Download English model**, then enable the speech fallback. Setup downloads about 40 MB and requires 200 MB of free storage. Recognition runs locally on decoded English program audio, without microphone access or audio uploads. It can lag by several seconds and make mistakes with music, accents or overlapping voices. It does not translate Japanese. Keep it off if your TV struggles; it is off initially and does not load during app startup.

Loading failures offer Retry or Restore encrypted backup while preserving the saved file. Avoid uninstalling or clearing storage to troubleshoot a library you want to keep.

Developer install: `adb install -r Ezano-TV-0.3.1.apk`.

APK SHA-256: `db59071573480d019c81f84a98803699ec4843b0ae06d7f551259e6e5eb41171`.

Full Android project: Ezano-TV-source-0.3.1.zip, attached to the release. Signing secrets, local SDK paths, caches and fixture media are excluded. See VALIDATION.md for checks and physical-device limitations.
