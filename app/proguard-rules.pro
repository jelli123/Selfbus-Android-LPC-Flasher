# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.hoho.android.usbserial.** { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
