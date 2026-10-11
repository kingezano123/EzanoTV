# Ezano TV 0.1.5 — Watch without waiting for the guide

Version code 6, package `tv.ezano.app`, signed with the existing release key for an in-place update.

The stale-guide startup/resume timer previously called the complete catalog importer. Failed guide downloads left its success timestamp stale, making subsequent launches and timed checks reload the catalog again. Startup, resume and timed checks now use a separate guide-only path.

- Catalog import completes after its encrypted save, reports **100% · Library saved. Ready to watch.**, and opens Live TV before the guide finishes.
- Guide downloads run independently, with a 45-second network deadline. Guide updates preserve channel identity, filters, selection and navigation.
- Fully parsed entries from truncated XML are retained. New listings merge into cached guide entries; missing entries do not erase cached future programs. Expired entries follow retention settings and removed channels are excluded.
- A failed/interrupted guide attempt is persistently blocked from automatic retries, including after restarting the app. **Settings → Retry TV guide** explicitly retries without importing the catalog.
- Guide failure is a small status message in Live TV, not a failed playlist connection or restart of import. An empty/failed guide response does not rewrite the saved library.

Install over the existing app. Open Live TV to use the saved library. Only refresh the playlist manually if channels/movies/shows are missing or you want a newer catalog.

## Verification

Build, signed APK, unit tests and lint are checked locally. Added retry-policy, partial XML and cached-guide merge unit tests. Added an Android guide-isolation regression covering catalog completion during a delayed failing guide, immediate saved-library reload, persistent suppression after recreation, manual retry and partial guide retention; existing instrumentation checks are adapted to asynchronous guide loading.

Android instrumentation could not be executed for this release: the hardware emulator failed with WHPX access denied and two software-emulation attempts remained offline. Earlier release device-test results must not be treated as verification of this build. Physical Fire TV/provider playback remains unverified.

Final local checks: **57 unit tests passed, 0 failed**; debug/release and test APK builds passed; Android lint passed. APK signature verified against the existing certificate.

APK SHA-256: `f9615b36ae5bf64d3633d2d7fc00ba427b7517dd13b5382fee048b6571f4fb40`.
