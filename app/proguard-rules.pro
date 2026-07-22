# ── Verse ProGuard Rules ──────────────────────────────────────────────────────

# ── WebView JavaScript Interface ──────────────────────────────────────────────
# Required: keep the JS bridge class and all public methods so WebView.evaluateJavascript works.
-keepclassmembers class com.example.WebViewHolder$PlayerBridge {
    public *;
}

# ── Moshi (JSON serialization) ────────────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ── Room Entities & DAO ───────────────────────────────────────────────────────
-keep class com.example.data.local.** { *; }

# ── Retrofit / OkHttp ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }

# ── Firebase / Google Play Services ──────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── Media3 / ExoPlayer ───────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Coil ──────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── Credential Manager / Google Identity ──────────────────────────────────────
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**

# ── Data model classes used by reflection (Moshi / Firebase) ──────────────────
-keep class com.example.data.model.** { *; }
-keep class com.example.data.remote.JammingRoom { *; }
-keep class com.example.data.remote.ChatMessage { *; }
-keep class com.example.data.network.YouTubeVideo { *; }
-keep class com.example.data.network.ITunesEntry { *; }
-keep class com.example.data.network.LRCLibResponse { *; }

# ── Preserve line numbers for Crashlytics ─────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
