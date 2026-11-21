# apksig 与 ARSCLib 大量使用反射与内部实现，保留必要符号
-keep class com.android.apksig.** { *; }
-keep class com.reandroid.** { *; }

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# 保留行号，便于排查打包异常
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
