# Ezano TV 0.1.4

Version code: 5. Package: `tv.ezano.app`. Signed with the existing Ezano release certificate so this APK can update the installed app in place.

## Library recovery fixes

- Import completion now waits for encrypted atomic saves of the catalog and guide. Save failures return an error instead of announcing that the import is ready; background saves retry.
- Imports are owned by the application instead of the current activity.
- Empty provider sections keep existing channels, movies and shows. An empty or failed EPG refresh keeps cached guide entries; channels without replacement entries retain their guide within the retention window.
- Opening a large saved catalog displays a loading message, rather than claiming that there are zero channels or no saved titles.
- The playlist dialog includes an explicit **Open saved library** action.

## Install

Install `app-release.apk` over the existing installation. Open Playlists and choose **Refresh all** once. Wait for saving to complete; the Live TV guide opens automatically. A provider guide outage is reported separately and does not prevent watching saved channels.

## Verification scope

Android 11 TV emulator, 192 MB Java heap, synthetic Xtream providers: 76,000 catalog items, 64,000 guide entries, immediate encrypted reload without manual flushing, forced app restart, remote selection reaching Media3 playback, and preservation after empty or failed refreshes. Unit tests and Android lint also run locally. Real provider and physical Fire TV behavior are not verified by these synthetic tests.

APK SHA-256: `d759f9d785b1f8bbc6a65e2368ad867fba9b7293a415b74c2ec153d075589ed7`.
