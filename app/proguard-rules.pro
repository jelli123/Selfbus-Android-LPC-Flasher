# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.hoho.android.usbserial.** { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }

# SLF4J 2.0 service provider (ServiceLoader) for capturing calimero logs
-keep class com.selfbus.lpcflasher.serial.knx.CalimeroSlf4jProvider { *; }
-keep class org.slf4j.** { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
# calimero KNX stack uses reflection in a few places
-keep class tuwien.auto.calimero.** { *; }
-dontwarn tuwien.auto.calimero.**
