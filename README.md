# Ezano TV 0.4.0

A native Android TV / Fire TV IPTV player with a black, content-first interface, remote navigation, movie/show shelves, and a cable-style live guide. Android 8 or later.

## Install and watch

[Download the signed APK](https://github.com/kingezano123/EzanoTV/releases/latest/download/Ezano-TV.apk). 0.4.0 uses a new signing key, so uninstall 0.3.x first. To keep your favorites and progress, back up in 0.3.x (Settings → Back up your library) and restore in 0.4.0. Until the repository signing secrets are configured, each release is signed with a one-off key and also needs a reinstall.

Open Playlists → Add playlist and enter your own Xtream Codes or M3U details. Credentials remain encrypted on the device. No commercial subscriptions, channels, or catalog are bundled.

## This update

- **Opens in seconds.** Library sections are encrypted with a software data key that the Android Keystore wraps once per launch, instead of one Keystore operation per 64 KB. Settings, playlists and Home open first; the catalog and guide load behind them. Saves rewrite only the changed section. The loading screen never asks you to close the app; the one-time upgrade from 0.3.x shows its progress.
- **Famous kids & family titles on Home.** A curated, release-date-checked list (`FeaturedTitles.kt`) of about 160 G/PG films and 70 kids shows is matched against your catalog, newest blockbusters first (Toy Story 5, Minions & Monsters, The Super Mario Galaxy Movie, Zootopia 2…). The best English copy of each title is used; locked, hidden, mature and foreign-language copies are skipped.
- **Free live captions.** Audio & subtitles → Live captions downloads a 40 MB English model once and writes captions from the program audio on the TV, on any channel, movie or show. They pause when the stream has its own subtitles. Non-English speech is filtered out by recognizer confidence instead of being shown as nonsense.
- Live streams reconnect automatically; http→https redirects are followed.

## Interface and library

- Black backgrounds, restrained white typography, small rectangular controls, and real provider artwork. The generated astronaut background and Home onboarding cards are removed.
- Continue Watching resumes movies and episodes, including episodes outside the main catalog. Mature, hidden, disabled-provider and PIN-protected content stays out of Home.
- Movies and Shows open with Recommended for you, New releases, and genre shelves. View all, provider categories and search still reach the complete collection. Genre and ranking depend on provider metadata; no external trending service is configured.
- Search fields require Select/click to open the keyboard. Returning focus or moving over a field does not open it.
- My stuff groups compact favorite-channel tiles, movie posters and show posters. Select a channel to watch immediately; long-press for its details.
- Show details initially focus Watch/Resume and load episodes in the background. Watch starts the first ordered episode; Resume selects an unfinished episode from saved history.
- One playback bar contains play/pause, 10-second back/forward, a seek timeline, and Audio & subtitles. More contains quality, picture fit, sleep, recording, multiview and external playback. Controls disappear automatically. Remote media buttons remain supported.
- English audio/captions and EN/ENI titles are preferred. UK/GB, US, AU, IE, NZ and English Canadian sources are recognized from labels/metadata. Artwork is never used to infer language. Other content stays accessible.

## Subtitles

Embedded HLS/DASH/container tracks and provider-supplied SRT/WebVTT tracks are detected automatically, with English preferred. Captions use white text with a dark shadow and no opaque box; embedded font styling is normalized while cue placement is preserved.

If you save your own OpenSubtitles API key through Audio & subtitles → Subtitles → Find or add subtitles, Ezano can also look up missing English captions in the background. Automatic external matches require the same movie title/year or show title/season/episode. Different video edits can still need a different release. Manual release selection, direct SRT/WebVTT links, local files and Subtitles off remain available. Settings → Automatic subtitles turns automatic selection off. Video starts independently of subtitle lookup; external service limits or failures do not interrupt it.

Speech captions are off initially. In playback, select Audio & subtitles → Speech captions → Download English model, then enable the speech fallback. Setup downloads about 40 MB and requires 200 MB of free storage; the installed model occupies about 71 MB. The [Vosk model documentation](https://alphacephei.com/vosk/models) estimates about 300 MB of runtime memory for small models, so this option can be demanding on older Fire TV devices. The model is verified against a pinned SHA-256 before installation. Once installed, recognition runs locally without microphone access or audio uploads. Embedded/provider captions take priority.

Speech captions use word timestamps, short lines and clearance above visible controls; they clear on seek or exit and remain stable while paused. Recognition follows the media clock, but it needs audio context and can lag by several seconds. Accents, background music, overlapping voices and device speed affect accuracy. It transcribes English speech; it does not translate Japanese anime. Streams that cannot supply decoded PCM audio retain normal playback without speech captions.

## Event search and performance

Live TV → Find event searches current or upcoming EPG titles/descriptions across all allowed channels. It requires provider XMLTV listings; it does not recognize events from the video.

Streaming imports retain the complete catalog. URLs/descriptions/artwork remain in compressed pages while resident labels handle search, English ordering and shelves. Lazy grids/rows and background artwork decoding keep large libraries off the UI thread. SQLite FTS4 indexes EPG entries in the background. Cached libraries open before guide refresh. Media3 uses adaptive playback and hardware decoder fallback. Actual start time depends on the provider, network, codecs and TV.

Favorites, groups, hiding, PIN protection, encrypted backups, reminders, compatible-provider catch-up and TS/clear TS-HLS recording remain available. DRM, universal Stalker support, local rolling-buffer timeshift and encrypted/fMP4/DASH recording are not implemented. Physical TV and actual-service checks remain necessary.

## Build

GitHub Actions (`.github/workflows/android.yml`) runs the unit tests and builds the release APK on every push. Pushing a `v*` tag publishes a GitHub release with `Ezano-TV.apk`, using the repository secrets `EZANO_KEYSTORE_BASE64` (base64 of the .jks, alias `ezano`) and `EZANO_STORE_PASSWORD`. Without those secrets, builds and releases are signed with a one-off key generated on the runner.

Locally: JDK 17+, Android SDK platform 35 and build tools 35.0.0, then `./gradlew assembleDebug testDebugUnitTest`. For a signed release set EZANO_KEYSTORE and EZANO_STORE_PASSWORD, then `./gradlew assembleRelease`. Keep the signing key: updates must be signed with it.

The buildable Android project is the attached Ezano-TV-source-0.3.1.zip. This repository is a release host; GitHub's autogenerated source archives contain its repository files. Signing secrets, SDK paths, caches and fixture media are excluded from the Android source archive.

See INSTALL.md, TESTING.md, VALIDATION.md and THIRD_PARTY.md.
