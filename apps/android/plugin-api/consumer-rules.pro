-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keep class io.github.whywood.superpi.plugin.api.** { *; }
-keep class io.github.whywood.superpi.design.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.compose.** { *; }

# AndroidX libraries plugin UIs commonly reach through Compose.
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }

-keep,includedescriptorclasses class io.github.whywood.superpi.plugin.api.**$$serializer { *; }
-keepclasseswithmembers class io.github.whywood.superpi.plugin.api.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
