# WorkManager instantiates workers reflectively.
-keep class com.galaxy.steward.work.** extends androidx.work.ListenableWorker { <init>(...); }
