# Models use explicit JSON serialization. No reflection keep rules are required.
# Dependency consumer rules preserve AndroidX, Media3, Coil and OkHttp entry points.
# JNA resolves Vosk exports and structure fields by name.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn javax.swing.**
