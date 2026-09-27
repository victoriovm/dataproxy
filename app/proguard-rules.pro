# Add project specific ProGuard rules here.
# Keep app entry points
-keep class com.dataproxy.MainActivity { *; }
-keep class com.dataproxy.service.ProxyService { *; }
-keep class com.dataproxy.DataProxyApplication { *; }

# Keep coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Shizuku is called purely through reflection (graceful when not installed),
# so keep the API surface names intact in release builds.
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
