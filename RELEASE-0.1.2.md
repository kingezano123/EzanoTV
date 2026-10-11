# Ezano TV 0.1.2

Version code: 3. Signed with the same certificate as 0.1.0 and 0.1.1.

APK SHA-256: `493f60e246bdabdf90ba0d90c27966b9825bdac2375c1d7d15b7fb7b94022482`

APK size: 17,212,264 bytes.

## Changes

- Removed the shared 60,000-item Xtream cutoff. Catalog records are retained in compressed pages with a six-page decoded cache; index views avoid materializing the full catalog during filtering. Category/title searches run off the UI thread.
- Movies and Shows have separate search fields. Live TV searches channels. Home searches movies and shows together, with neutral artwork for unclassified results.
- Live TV now has a category sidebar, vertical channel names/logos, a shared time axis, duration-aligned guide cells, current-time marker, preview and program details.
- Fullscreen native controls and Ezano tools hide together. Up/Down restores them, including after Android consumes a key-down while leaving touch mode. Back hides controls before returning to browsing.
- Home recommendations mix family movies and series; require English/US markers and positive family suitability labels; reject mature, conflicting and unknown suitability. Ranking uses provider review/popularity metadata when supplied. This is not independent age verification.
- Mint/charcoal appearance, rounded cards, neutral missing-art placeholders and season-based episode browsing.

## Validation

- Release build and Android lint passed (zero lint errors).
- 39 unit tests passed.
- Six Android TV emulator checks passed across targeted runs: playback/recording/PIN smoke test; 76,000-item import and 64,000-program guide; legacy vault migration; fullscreen remote controls; actual subtitle cue rendering; Home and section search UI.
- The large import contains 68,000 movies followed by 6,000 series, plus 2,000 channels. It saves/reopens the encrypted library and finds the last movie and series through their search fields. Device app heap growth limit: 192 MB.
- Home, movie/show search, cable guide and clean fullscreen playback screenshots inspected. UI artwork fixtures are synthetic tests, not bundled provider content.

Install over the existing app, then choose **Playlists → Refresh all** to recover titles omitted by 0.1.1. Physical Fire TV, actual provider data and provider metadata quality remain unverified. The existing 80,000-program XMLTV bound still applies.
