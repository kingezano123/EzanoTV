# Reproducing device checks

Use a dedicated Android TV emulator. The fixture data is synthetic; video is the Sintel open-film trailer. Do not use these fixture credentials with a real service.

1. Install Node.js and FFmpeg, and download `https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4` as `test-media/sintel-trailer.mp4`.
2. Create a TS fixture:

```sh
ffmpeg -i test-media/sintel-trailer.mp4 -t 25 -c:v libx264 -preset ultrafast -b:v 1200k -c:a aac -b:a 128k -f mpegts test-media/fixture.ts
node tools/fixture-server.cjs
```

3. Start an Android 11 TV emulator. It reaches the host at `10.0.2.2:8765`. The server binds only to host loopback.
4. Start the additional stress-test provider: `node tools/large-fixture.cjs` (loopback port 8766).
5. Start the persistence failure fixture: `node tools/persistence-fixture.cjs` (loopback port 8767).
6. Run `./gradlew testDebugUnitTest connectedDebugAndroidTest`.

For v0.1.5, also start `node tools/guide-isolation-fixture.cjs` on port 8768 before device tests. Catalog import now finishes before the background guide; assertions about guide data must wait for `guideBusy` to become false. Guide failures appear in `guideStatus`, independently of catalog import errors.

The test imports Xtream + XMLTV, plays and seeks a movie, plays live TS, records ten seconds, checks the output file, toggles favorites, loads a series episode and checks the PIN. It also drives D-pad navigation. Tests use app-owned emulator storage only. The Gradle device-test runner may uninstall the test app after completion.

Manual checks still needed: your provider, physical Fire TV remote, standby scheduling, external-player intents, backup document picker, AFR, extended recording, four-screen decoding, and provider-specific archive time zones. PiP is removed in 0.3.1.

## v0.1.2 regression coverage

- `ImportRegressionTest`: duplicate/null IDs, unavailable optional catalog sections, API URL normalization, invalid-login handling, duplicate/malformed XMLTV entries, and a 12,000-item streaming library round trip.
- `LargeImportTest`: chunked provider catalog (76,000 unique items plus duplicates), gzip XMLTV (64,000 unique programs plus duplicates), encrypted save/reload, and browsing after import.
- `SubtitleTest`: SRT/VTT validation, UTF-16 BOMs, oversize/HTML/ZIP rejection, API title encoding, separate credential handling, service quotas and HTTPS-only API download links.
- `SubtitlePlaybackTest`: a real Media3 playback session, subtitle attachment at 15 seconds, actual cue output, and preserved position.
- `CompactCatalogTest`: more than 60,000 movies followed by series, tail searches, deduplication, compressed storage and stable filtered/manual ordering.
- `HomeRecommendationsTest`: mature/unknown/foreign exclusion, mixed movies and shows, metadata ranking and neutral artwork for unclassified Home search results.
- `GuideLayoutTest`: clipping, gaps, overlapping programs and a shared time axis.
- `PlayerControlsTest`: native controls stay disabled; the single custom bar hides on inactivity; remote reveal does not accidentally toggle playback; 10-second skips and saved progress work.

The v0.1.0 APK crashes on the duplicate-ID fixture with `IllegalArgumentException: Key "large:live:1" was already used`. This is a reproduced defect; it does not prove the exact cause of a particular Fire TV crash without that device's logs.

OpenSubtitles API responses are mocked. No real IPTV credentials or OpenSubtitles API key are part of these tests. Physical Fire TV/provider verification remains necessary.

## v0.1.4 persistence regression coverage

- Successful imports await an atomic encrypted save before reporting ready. The app owns import jobs so activity recreation does not cancel them.
- Empty provider sections retain previously saved titles. Empty or failed guide responses preserve cached entries for channels without replacement programs.
- `PersistenceRegressionTest` reloads the saved catalog and guide immediately after import, without a manual flush; checks empty and failed refreshes; and injects a storage-write failure to ensure import does not falsely report success.
- `LargeImportTest` now also reloads without a manual flush: 76,000 titles and 64,000 guide entries.
- `ImportFlowTest` requires remote selection after the large import to reach Media3 `STATE_READY`. Its live fixture path was corrected; merely staying alive is not considered successful playback.

## v0.2.0 event and streamed-import coverage

EventSearchTermsTest and StreamingPlaylistTest cover literal FTS4 prefixes, punctuation, local midnight/DST, a reader-streamed M3U larger than 16 MB, playback headers, provider suitability fields, and XMLTV beyond the former 80,000-event cutoff. EventSearchTest runs the actual Android SQLite engine and a 100,000-program guide, including the event screen.

## v0.3.0 browsing, playback and subtitle coverage

RebrandTest covers English/UK metadata ordering, direct provider captions, strict movie/episode identity, persistent episode history and resident-label shelves. RebrandDeviceTest checks initial Watch focus, direct first-episode playback, Continue Watching and compact favorite channels. BrowsingTest checks real IME visibility: focus alone keeps it closed and an explicit Select opens it. SubtitlePlaybackTest includes provider English VTT automatically attached after video starts, without an external API key. The fixture server supplies this caption track for movie ID 21 at `/captions-en.vtt`.

For the 0.3.0 device suite, run BrowsingTest, PlayerControlsTest, SubtitlePlaybackTest, RebrandDeviceTest, DeviceTest, LargeImportTest and EventSearchTest with fixtures on ports 8765 and 8766. The complete historical suite also needs its other fixture servers described above.

Run `./gradlew testDebugUnitTest lintDebug`. With a TV emulator/device connected, run `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=tv.ezano.app.EventSearchTest`. The broader playback/import tests use the local fixtures described above. See VALIDATION.md for the tests and measurements performed for this release.

## v0.3.1 reopening and speech captions

The JVM suite now contains 83 tests. `ChunkedVaultTest` checks authentication, truncation, appended data, reordered/spliced frames and authentication on close. `SpeechCaptionTest` checks timestamped segmentation, low-confidence final words, stereo downmix/resampling phase and strict English language recognition.

Run `ColdRestartTest` only on a dedicated emulator. Pass `-e cold_restart true` to `adb shell am instrument`. Invoke `#seed` and `#verify` in separate force-stopped processes. Verification requires a different process ID, all 76,000 titles and 64,000 guide entries, the tail title, saved favorite/progress and the EZV3 header after migration. For a legacy migration check, seed using the 0.3.0 debug source, then install the 0.3.1 debug APK with `adb install -r`. `#corruptSavedVault` and `#recoveryPreservesDamagedDataAndCanRetry` must also run separately, in that order; they intentionally damage only the test library and confirm recovery does not overwrite it. Never run these against a user's library.

For `SpeechPlaybackTest`, download the official model archive from `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` and push it to `/sdcard/Android/media/tv.ezano.app/model.zip`. Download `https://raw.githubusercontent.com/alphacep/vosk-api/master/python/example/test.wav` to `test-media/asr-test.wav`, then create the fixture:

```sh
ffmpeg -stream_loop 15 -i test-media/asr-test.wav -c:a pcm_s16le test-media/speech-fixture.wav
node tools/speech-fixture.cjs
```

Port 8767 is shared with the persistence fixture, so stop one server before starting the other. The speech check installs the verified model, plays actual decoded audio, waits for recognized words, captures rendered captions, then checks seeking and player release. Without the prepared archive it is explicitly skipped. No microphone is used.

`ReleaseSmokeTest` is gated by `-e release_checks true`. Run `#seedLegacyFixture` after installing signed 0.3.0, with the extracted verified model in `/sdcard/Android/media/tv.ezano.app/model-prepared`; upgrade with the production signed APK, then run `#upgradedReleasePlaysSpeechAndBackReturnsToBrowsing` and `#releaseReopensSavedLibrary` in separate processes. The normal debug test APK excludes Kotlin classes supplied by its target, so it cannot instrument an R8-obfuscated release directly. For release checks, package this UI-only class in a separate non-minified test application with AndroidJUnitRunner targeting `tv.ezano.app`, JUnit, UIAutomator, AndroidX test runner and the Kotlin standard library bundled, signed with the same private release certificate. Keep that private signing material out of source archives.
