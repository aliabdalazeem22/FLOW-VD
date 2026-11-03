# No reflection-based serialization is used: state is stored as plain JSON.
# Keep the entry points that the Android framework instantiates.
-keep class com.example.flowdl.MainActivity { *; }
-keep class com.example.flowdl.App { *; }
-keep class com.example.flowdl.service.DownloadService { *; }
