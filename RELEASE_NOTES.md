Ezano TV 0.4.0 opens in seconds, puts famous kids movies on Home, and adds free live captions.

**Download Ezano-TV.apk below** · Android 8+ · package `tv.ezano.app` · version code 12.

> **One-time reinstall:** this release is signed with a new key, because the key that signed 0.3.x is not available. Android cannot install it over 0.3.1. To keep your favorites and progress: in 0.3.1 open **Settings → Back up your library**, then uninstall Ezano, install this APK and use **Settings → Restore a backup**. Or simply re-add your playlist under **Playlists**. Future updates install over 0.4.0 normally.

### Fast startup
- The saved library is no longer decrypted through the TV's secure hardware in thousands of small steps. Only one small key goes through it per launch. The rest is decrypted in software, which on Fire TV turns minutes into a few seconds.
- The library is split into small parts. Your settings, playlists and Home open first, and channels, movies, shows and the guide load behind them. Favoriting a title or saving watch progress now writes a few kilobytes instead of re-saving the entire catalog.
- Home appears instantly with last time's shelves while everything else opens.
- The loading screen never asks you to close the app. The first launch after updating from 0.3.x converts the old library once and shows a percentage; later launches are fast.
- The guide search index is reused when the guide hasn't changed, and is built after startup instead of during it.

### Home: famous kids & family titles
- Home now leads with the biggest English kids and family films your provider carries, newest first: Toy Story 5, Minions & Monsters (Minions 3), The Super Mario Galaxy Movie, Hoppers, Moana (2026), Zootopia 2, Lilo & Stitch, KPop Demon Hunters, A Minecraft Movie and more.
- Rows: Popular kids movies, Kids & family shows (Bluey, Peppa Pig, PAW Patrol, Gabby's Dollhouse…), Family classics and More family movies & shows. Only G/PG titles and kids TV are in the curated list. Foreign-language copies, locked, hidden and mature titles never appear, and the best English copy of each title is chosen.
- Titles display cleanly, without provider tags like "EN -", "[UK]", "4K" or "(2026)".

### Live captions (free, on-device)
- **Audio & subtitles → Live captions** (or **Settings → Live captions**): one button downloads a 40 MB English speech model once and turns captions on. They work on any channel, movie or show, appear about a second after speech, and need no account or internet after setup. No audio leaves the TV.
- Captions start with the video, pause automatically when the stream has real subtitles, and stay on across channel changes without reloading.
- When the audio isn't English, low-confidence recognition is hidden instead of showing nonsense words.

### Playback
- Live channels reconnect automatically after a dropped connection, and jump back to live if they fall behind.
- Streams that redirect from http to https now play.
- Smaller download: only ARM (Fire TV / Android TV) native libraries are included.

Built and unit-tested on GitHub Actions. Startup time on a physical Fire TV still needs confirming on your device.
