# Credits

The interface uses **Manrope**, Copyright 2018 The Manrope Project Authors, under the SIL Open Font License 1.1. The unmodified variable font is bundled in `app/src/main/res/font/manrope.ttf`; its complete copyright and license are bundled inside the APK at `assets/licenses/manrope-ofl.txt`. It is loaded locally without a font service or network request. Source: https://github.com/google/fonts/tree/main/ofl/manrope . SHA-256: `3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6`.

The interface and launcher banner use a black background and native vector shapes. Home artwork comes from the user's provider catalog. The former generated astronaut background has been removed from the APK and source.

The demo collection uses Blender Foundation open films and an official trailer. The bundled thumbnails are frames extracted from those films, scaled for the catalog. They are not commercial-provider artwork.

- **Big Buck Bunny**, © 2008 Blender Foundation, https://www.bigbuckbunny.org / https://peach.blender.org — Creative Commons Attribution 3.0. Demo playback uses Mux's public HLS test stream: https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8.
- **Sintel**, © 2010 Blender Foundation, https://www.sintel.org / https://durian.blender.org — Creative Commons Attribution 3.0. Full film and trailer from https://download.blender.org/durian/.
- **Tears of Steel**, © 2012 Blender Foundation, https://www.tearsofsteel.org / https://mango.blender.org — Creative Commons Attribution 3.0. Film from https://download.blender.org/demo/movies/ToS/.

License: https://creativecommons.org/licenses/by/3.0/ . Ezano is not affiliated with Blender, Mux, Netflix, Disney, Amazon, or any IPTV provider.

Libraries: AndroidX / Jetpack Compose / Media3 (Apache-2.0), Kotlin and coroutines (Apache-2.0), OkHttp (Apache-2.0), Coil (Apache-2.0). Test-only libraries include JUnit 4 (EPL-1.0), JSON-java (public domain), kXML2 (BSD), and MockWebServer (Apache-2.0). Dependencies are resolved from their original Maven repositories.

Optional speech captions use [Vosk Android 0.3.75](https://github.com/alphacep/vosk-api), Copyright Alpha Cephei Inc., under Apache License 2.0. Its license is bundled as `assets/licenses/vosk-apache-2.0.txt`. Native access uses [JNA 5.18.1](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE) under its Apache License 2.0 option; notices and the selected license are bundled as `assets/licenses/jna-license.txt` and `assets/licenses/jna-apache-2.0.txt`.

The optional `vosk-model-small-en-us-0.15` English model is downloaded from the [official Vosk model list](https://alphacephei.com/vosk/models), which lists Apache License 2.0. It is not bundled in the APK or source archive. Download SHA-256: `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`. Model archive: 41,205,931 bytes. Speech-test audio is downloaded separately from Vosk's official example and is excluded from distribution.
