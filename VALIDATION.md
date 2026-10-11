# Ezano TV 0.3.1 validation

Validated October 1, 2026, locally on Windows with a dedicated Android TV emulator.

## Build and identity

- Gradle 8.11.1 / AGP 8.9.1 / Kotlin 2.1.20, SDK 35, Media3 1.8.1.
- **83 JVM tests passed**, no failures or errors. Android lint: 0 errors, 33 advisory warnings.
- R8 and resource shrinking enabled. Android 8+; package tv.ezano.app; version **0.3.1 / code 11**.
- APK: 43961816 bytes. Native offline speech engines for ARM and x86 are included; the English model downloads separately. Speech is disabled initially and model loading is excluded from startup.
- apksigner verified the existing certificate and v2 signature. Certificate SHA-256: `3beee9b145381faf84f202ac26755c4d489ee2a1ce847d449cca7d1cc22b9e73`.
- Final APK SHA-256: `db59071573480d019c81f84a98803699ec4843b0ae06d7f551259e6e5eb41171`.
- Final manifest has no RECORD_AUDIO permission or Android PiP support.

## Loading and persistence

The user reported a Firestick Plus remaining on the reopening spinner for over two minutes across versions. The supplied video showed the spinner, but physical-device logs were unavailable. The exact hardware failure has **not** been reproduced or proven resolved on that Firestick.

New saved libraries avoid whole-file authenticated-decryption buffering and guide reconstruction at launch. EZV3 storage uses authenticated 64 KB frames, file identity and sequence-bound authentication, plus an authenticated end marker. Older EZV2 saves migrate through encrypted staging; raw legacy JSON saves remain supported. Compressed guide pages and validated channel indices restore directly.

A cold-process fixture contained 76,000 titles, 64,000 guide entries, favorites/progress and high-entropy metadata. The Android 11 TV emulator had **192 MB of managed heap**. The 0.3.0 saved read took 22.7 seconds; after migration an EZV3 read measured **2.9 seconds**. These are fixture saved-read times, not a guarantee of full launch time on physical hardware. Different process IDs confirmed cold reopening, followed by tail-content, favorite and progress assertions. The latest build passed reopening again.

An intentionally damaged vault was rejected. Retry / Restore encrypted backup / Close appeared. Updates and saves could not overwrite the damaged file. Restoring the preserved fixture and Retry recovered the complete catalog, guide, favorites and progress. Slow reads show status; saved reads have a 90-second deadline; memory/read failures preserve the file and show recovery.

PersistenceRegressionTest passed two checks for durable imports, empty/failed guide refresh preservation and injected disk-write failure/retry. LargeImportTest passed the complete streamed 76,000-title / 64,000-program import, encrypted reload and tail searches.

## Playback, browsing and captions

The core emulator run passed all 11 tests across DeviceTest, BrowsingTest, EventSearchTest, PlayerControlsTest, SubtitlePlaybackTest, RebrandDeviceTest, LegacyLibraryTest and SpeechPlaybackTest. Latest focused reruns covered speech and reopening. The final caption-spacing change was followed by playback/subtitle checks, a signed build and release smoke checks.

Coverage includes actual Media3 movie/live playback, seek/resume, recording/output inspection, favorites, episodes/PIN, remote navigation, Select-only keyboard activation, initial Watch focus, Continue Watching, compact favorite channels, and real SQLite event searches through 100,000 programs.

Back saves progress, releases playback and returns to browsing without mini-player/PiP. Matching Back key-up is consumed to avoid accidentally exiting the app. Show details close when playback begins.

SubtitlePlaybackTest verified actual provider English VTT cues and manually imported subtitles that preserved playback position. Regional English language tags are recognized. Provider download failures fall through to alternatives and optional configured OpenSubtitles. Automatic English resumes discovery after manual Off. White shadowed captions have clearance above visible playback controls.

SpeechPlaybackTest verified the pinned English model archive, played decoded program PCM, observed recognized words, captured rendered captions and checked seeking/reset/release. Recognition uses a bounded background queue, one model allocation at a time, word timestamps and short segments. Existing subtitle tracks take priority. No room microphone or cloud audio upload is used.

The **production signed 0.3.0 → 0.3.1 upgrade** retained a saved legacy encrypted library, favorite, resume position and prepared speech model. A UI-only harness signed with the same certificate bundled its own Kotlin runtime, avoiding production names altered by R8. Actual speech captions rendered, Back returned to browsing, and a separate process reopened the library with Resume and the favorite intact. Screenshots were inspected. An initial short-fixture assertion failed because the app marked its final 30 seconds complete; a longer fixture passed both checks.

## Practical limits

Physical Fire TV hardware, real IPTV services and a live OpenSubtitles key were unavailable. Verify repeated reopening on the affected Firestick. Real 4K codecs, physical remotes, standby scheduling, extended recording, frame-rate matching, provider catch-up, four-screen decoding and provider time zones still need hardware/service checks.

Embedded/provider subtitles are preferred. External subtitle releases can differ in timing; no manual delay control is shipped. Speech recognition can lag by several seconds and make errors from accents, music, overlapping voices or limited device resources. The [official Vosk model list](https://alphacephei.com/vosk/models) estimates around 300 MB runtime memory for small models. This optional feature transcribes English; it does not translate Japanese. Unsupported decoded-audio formats retain normal playback.

The attached source archive contains the build wrapper, app source, tests, fixture scripts and licenses. It excludes signing keys, passwords, local SDK paths, caches and fixture media. GitHub's autogenerated source ZIP contains only the release-host repository; use Ezano-TV-source-0.3.1.zip.
