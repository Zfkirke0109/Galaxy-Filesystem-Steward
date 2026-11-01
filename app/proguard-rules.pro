# WorkManager instantiates workers reflectively.
-keep class com.galaxy.steward.work.** extends androidx.work.ListenableWorker { <init>(...); }

# Shizuku starts the helper in its own process by class name and calls its constructor reflectively.
-keep class com.galaxy.steward.shizuku.StewardHelperService { <init>(...); }

# The source is public, so renaming classes hides nothing. Real names and line numbers keep crash traces in a phone's
# logcat readable without a mapping file, and error messages that show an exception's class name stay meaningful.
# Unused code is still removed.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
