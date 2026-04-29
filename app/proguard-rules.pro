########## TENSORFLOW LITE ##########
# Keep TensorFlow Lite API
-keep class org.tensorflow.lite.** { *; }

########## YOUR APP CLASSES ##########
# Replace with your final namespace
-keep class com.resqmotion.app.** { *; }

########## NATIVE METHODS ##########
-keepclasseswithmembernames class * {
    native <methods>;
}

########## OPTIONAL BUT RECOMMENDED ##########
# Keep metadata (needed for TFLite metadata loading)
-keepclassmembers class * {
    @org.tensorflow.lite.support.metadata.MetadataExtractor *;
}

# Keep line numbers (useful for crash logs)
-keepattributes SourceFile, LineNumberTable
