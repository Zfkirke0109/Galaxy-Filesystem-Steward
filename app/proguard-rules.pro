# WorkManager instantiates workers reflectively.
-keep class com.galaxy.steward.work.** extends androidx.work.ListenableWorker { <init>(...); }

# Shizuku starts the helper in its own process by class name and calls its constructor reflectively.
-keep class com.galaxy.steward.shizuku.StewardHelperService { <init>(...); }
