# Ezano TV feature mapping — 0.3.1

“Implemented” means a code/UI path exists. It does not imply every provider and Fire TV variant has been tested.

| Request | Where / status |
|---|---|
| Multiple playlists | Playlists; combined catalogs; enable, disable, edit, refresh, remove |
| Recording / DVR | Player Record; channel Options; direct TS / clear TS-HLS only |
| Scheduled recording | Program details or channel Options; OS alarms |
| Recurring recording | Daily / weekly, local wall time, DST aware |
| Multiview | Player → Multiview; 2–4 streams, one audible tile; hardware/provider dependent |
| Exiting playback | Back saves progress, stops/releases playback and returns to browsing; expanded mini-player and PiP removed |
| Catch-up | Past guide program → Watch from start; compatible provider required |
| Timeshift | Native player seekable live window; local rolling buffer remains future work |
| Favorites | Title/channel → Add to my list |
| Search | Dedicated channel, movie and show search; Home searches movies and shows together; complete catalog filtering |
| Find a live event | Live TV → Find event; local FTS4 search of EPG titles/descriptions across visible channels; On now / Today / Next 7 days; current matches open playback |
| Large playlist loading | Streamed M3U / Xtream import, compressed catalog pages, resident title labels, background parsing and atomic encrypted saves |
| Reopening | Bounded authenticated encryption frames, direct compressed-guide restoration, legacy migration, slow-load status and Retry/backup recovery |
| Family home | English/US preference, provider suitability labels, rating/popularity ranking, unknown and mature artwork excluded from automatic shelves |
| Android TV installation | Signed sideload APK, TV launcher icon/banner, D-pad navigation; Android 8+ |
| Downloader installation | Direct signed APK URL in INSTALL.md; code 8306366 still points to old 0.2.0 |
| Parental controls | Settings PIN; section/channel locks; automatic adult/XXX group guard when a PIN exists |
| Backup & restore | Encrypted export/restore; requires Android document picker; excludes video files |
| External player | Player → External player; installed compatible handler required |
| Auto frame rate | Settings; Media3 seamless frame-rate request; device dependent |
| Last channel | Settings → Resume last channel; respects PIN |
| Reminders | Program details; notification and optional foreground switch |
| Custom groups | Channel → Options → Custom groups |
| Move/copy groups | Choose copy/move among custom groups; provider groups remain intact |
| Hide channels/groups | Channel Options; Settings to restore |
| Manual order | Channel Options → Move earlier/later |
| EPG update interval | Settings; stale guide refreshed on open/resume |
| Historical EPG | Retention 0 / 3 / 7 days; subject to source data |
| Panels | Complete auto-hide of tools and transport controls; Up/Down reveals; configurable duration and opacity |
| Appearance | Black background, white typography, real provider artwork, Continue Watching, compact favorites and cable-style guide; theme accent and reduced motion |
| Channel preview | Live browser; opt-in muted preview; uses a connection |
| Program descriptions | Program detail dialog |
| Sleep timer | Player → Sleep; 15 / 30 / 60 / 90 min |
| Audio tracks | Audio & subtitles → Audio; stream-dependent |
| Subtitles/captions | Automatic English embedded/provider tracks; SRT/VTT file/link import; optional OpenSubtitles with your API key and quota |
| Generated captions | Optional offline English speech captions from decoded program audio; explicit model download/enable; no microphone, cloud upload or Japanese translation |
| Display modes | Player → Fit / Fill / Zoom |
| Number switching | Numeric key events while full-screen; follows current visible channel order |
| Login methods | Xtream, M3U URL; basic Stalker live support |
| VOD | Movies and Xtream series/episode browser |
| Advanced playback | One custom bar: play/pause, 10-second skips, seek, audio/subtitles; quality options and saved movie/episode resume |

## Recommended first checks on your Fire TV

1. Install over your current app, open it, force-close and reopen several times; verify playlists, favorites and progress.
2. Import your Xtream account and verify live, movies, episodes and guide times.
2. Play a 1080p and 4K title available from your provider; check audio and subtitles.
3. Try a short recording, stop it, and play the saved file.
4. Try two-screen multiview only if your account permits multiple connections.
5. Schedule a near-future recording, then test standby/background behavior.
6. Test catch-up on a channel marked as archive-capable by your provider.
