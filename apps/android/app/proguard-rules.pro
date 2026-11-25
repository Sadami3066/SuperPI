-keep class io.github.whywood.superpi.plugin.api.** { *; }
-keep class io.github.whywood.superpi.design.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# Plugin ABI: plugins reuse the host's Kotlin/coroutines/Compose at runtime.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }

-keep class androidx.compose.runtime.ComposeVersion { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Wireless-debug shell daemon and virtual-screen helper are launched via app_process reflection.
-keep class io.github.whywood.superpi.adb.ShellDaemon { *; }
-keep class io.github.whywood.superpi.adb.ShellDaemon$* { *; }
-keep class io.github.whywood.superpi.adb.ShellAuth { *; }
-keep class io.github.whywood.superpi.vscreen.VirtualScreenHelperMain { *; }
-keep class io.github.whywood.superpi.vscreen.VirtualScreenHelperMain$* { *; }

# WorkManager workers and Room prebuilt database are instantiated via reflection.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# kotlinx.serialization generated serializers and companions inside SuperPI and bundled SDK.
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class io.github.whywood.superpi.**$$serializer { *; }
-keepclassmembers class io.github.whywood.superpi.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.whywood.superpi.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    void write$Self*(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
