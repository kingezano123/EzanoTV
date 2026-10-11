# Ezano TV 0.1.6 (version code 7)

Large saved libraries could spend minutes unpacking the same compressed records for visibility, categories, searches, Home recommendations and lazy-layout keys. Browsing now uses resident title/category metadata built during import or restoration. Full descriptions, artwork URLs and playback credentials stay in compressed pages.

- Section search, favorites, provider counts, manual ordering and layout keys use the resident index without decompressing the catalog.
- Superseded searches cancel cooperatively; unfiltered movie/show views reuse their existing collection.
- Home ranks cached family eligibility and scores, then loads only its selected shelves. Existing suitability/language rules remain in force.
- Poster titles display immediately in a two-line slot; cold record decoding runs off the UI thread. Compressed pages hold 32 records instead of 128.
- Compact snapshots save their index and compressed pages directly. Restoring them avoids reconstructing every full record. Older libraries remain readable and migrate automatically after their usable snapshot is published.
- Guide failures continue to preserve saved channels, movies and series.

Install `app-release.apk` over the existing app to retain playlists. The first launch after upgrading an older library still needs to build its index once. Image availability and stream startup also depend on the provider/network.

Validation: 64 JVM tests cover 76,000-item indexed search, cancellation, recommendation equivalence, compressed snapshot round trips, legacy snapshots and guide/import regressions. The host benchmark compares the same queries and verifies no page decompression for indexed search. Timings are desktop JVM measurements, not Fire TV latency guarantees. Debug/release APKs, instrumentation sources, lint and signing are checked separately.

Device validation limitation: the local Android TV emulator cannot start because Windows WHPX partition setup returns 0x80070005. No device/instrumentation pass is claimed for this release.

Reference: OwnTV's remote-first, section-specific browsing and support for large libraries were reviewed at https://github.com/Thedurancode/owntv. No OwnTV implementation code was copied.

Final verification: 64 tests passed; release-final-016.log build/lint succeeded. Desktop JVM 76,000-item search median: indexed 12.21 ms, legacy 1072.63 ms (9 queries). APK v0.1.6/code7 SHA256 e84a8e03d8abe176d7b5cf93e7a5a10738ebe394c0c48f11cf632216d4c24197; signing certificate unchanged. Public APK HEAD returned 200.
