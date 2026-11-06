-keepattributes Signature,*Annotation*
-keep class kotlinx.serialization.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepclassmembers,allowoptimization,allowshrinking class * {
    @kotlinx.serialization.SerialName <fields>;
}
