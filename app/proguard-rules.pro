# Add project specific ProGuard rules here.
# Keep OkHttp and Gson for runtime reflection
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.khalsatorrent.cs3builder.model.** { *; }
