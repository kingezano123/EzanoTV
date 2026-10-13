Ezano TV 0.5.1 fixes remote navigation on Home and in the playlist form, and makes reopening and returning from videos seamless.

**Download Ezano-TV.apk below** · Android 8+ · package `tv.ezano.app` · version code 14 · signed with the original Ezano key.

> Installs over **0.5.0** and **0.3.x** and keeps your library. Only if you still have **0.4.0** (one-off key) installed: uninstall it once first.

### Fixed
- **Down from the top bar now reaches Home.** In 0.5.0 the full-screen Home sat underneath the floating bar, so the remote's Down press found nothing and stayed on "Home". Down from any tab or icon now goes to Home's main button: Play on the billboard, or Add a playlist on a new install.
- **New install:** "Add a playlist" is already selected, so one press of OK starts setup. The playlist window and editor also open with their first button selected.
- **Playlist form with the remote:** moving down the form no longer pops up the keyboard on every field. Press OK on a field to type, Back to close the keyboard, then Down to continue to Connect & import.
- **Back from a video** returns to the same place: same page, row, search and filters, with the selection where you left it. The browsing screens stay ready underneath playback instead of being rebuilt.
- **After importing a playlist** you land on Home with its new content selected, instead of an empty Live TV page for movie-only playlists.
- The top bar turns solid as soon as Home scrolls, and selecting the billboard scrolls back to the top, so nothing overlaps the logo.
- "Subtitles off" now also turns live captions off until you turn them back on.
- Live channels that keep dropping no longer reconnect forever; after a few tries you get the error panel.
- Focused buttons are unmistakable: solid white with a red outline.
- Movies, Shows and Home rows are remembered per library, so switching tabs is instant even with 150,000+ titles.
- Safer saving: a backup restored right after launch is no longer undone, and failed saves back off instead of retrying forever.

### Verified on an Android TV emulator (Android 11, 1080p) with the remote only
All 12 device checks pass: fresh install → Add a playlist → typing the playlist with the remote → import → Home → rows → Movies → Shows → Live TV → details → playback → Audio & subtitles → caption background → Back, plus playback, recording, player controls and details checks.

Measured on the emulator:
- Cold start to Home: about 1.7 s (2.8 s with a 76,000-title, 64,000-programme library).
- Reopen from the background: about 0.8 s.
- First remote response: under 0.2 s.

Physical Fire TV timing may differ.
