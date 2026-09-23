package com.galaxy.steward.shizuku;

// Runs inside a Shizuku user service as Android's shell user, which (unlike any normal app on Android 11+)
// can list and clean Android/data and Android/obb. Requests and results travel through pipes so neither is
// limited by the binder transaction size; the payloads are the line formats in core's AppDataWire.
interface IStewardHelper {
    // Called by the Shizuku server when the service is destroyed.
    void destroy() = 16777114;

    int uid() = 1;

    ParcelFileDescriptor scanAppData(in ParcelFileDescriptor request) = 2;

    ParcelFileDescriptor applyAppData(in ParcelFileDescriptor request) = 3;

    ParcelFileDescriptor rollbackAppData(String rootPath, in ParcelFileDescriptor entries) = 4;

    // Force-stops (when asked) and clears only the cache of one app. Returns "exit=<code>" or an error.
    String clearAppCache(String packageName, int userId, boolean forceStop, long timeoutMs) = 5;

    // Grants this app the usage-access app-op so it can read per-app storage statistics.
    boolean grantUsageAccess(String packageName) = 6;
}
