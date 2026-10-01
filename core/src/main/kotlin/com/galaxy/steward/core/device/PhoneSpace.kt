package com.galaxy.steward.core.device

import com.galaxy.steward.core.GIB
import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppAreaUsage
import com.galaxy.steward.core.humanBytes

/**
 * Android's live count of this user's shared storage (ExternalStorageStats): all of it, its photos, videos and audio,
 * and what apps keep in their own folders there (Android/data and media).
 */
data class SharedCount(val total: Long, val images: Long, val video: Long, val audio: Long, val apps: Long) {
    /** What Android's storage categories call "Other": all of shared storage but photos, videos and audio. */
    val other: Long get() = (total - images - video - audio).coerceAtLeast(0)
}

/** One app's folders in shared storage, Android/data, obb and media together, as the app folder scan measured them. */
data class AppFolderTotal(val packageName: String, val label: String?, val bytes: Long, val byArea: Map<AppArea, Long>, val installed: Boolean) {
    val name: String get() = label?.takeIf { it != packageName }?.let { "$it ($packageName)" } ?: packageName
}

/**
 * Where a phone's storage goes, from every count there is: Android's categories (diskstats, which it refreshes about
 * once a day), its live count of shared storage, the app folders the Shizuku scan measured, and the scan of your files.
 * It exists for one question the 10-01 phone raised: Android said "Other 90 GiB" and no scan here saw anything near it.
 * "Other" is shared storage that isn't photos, videos or audio, so it holds every app's Android/data and obb as well
 * as your files: this says how much of each, and which apps.
 */
data class PhoneSpace(
    val disk: DiskStats? = null,
    val shared: SharedCount? = null,
    /** Largest first; empty when the app folders haven't been scanned through Shizuku. */
    val appFolders: List<AppFolderTotal> = emptyList(),
    /** Your own files in shared storage, from the last scan. */
    val scanned: Long? = null,
) {
    /** What the app folder scan measured in all, or Android's own count of them when there was no scan. */
    val appFolderBytes: Long? get() = appFolders.takeIf { it.isNotEmpty() }?.sumOf { it.bytes } ?: shared?.apps

    /** Plain lines, largest app folders up to [apps]: for the screen, the run log and the storage report. */
    fun lines(apps: Int = 5): List<String> = buildList {
        disk?.let { d ->
            add(
                "Android counts ${d.used.humanBytes()} used of ${d.total.humanBytes()}, ${d.free.humanBytes()} free" +
                    if (d.measured) {
                        "; its categories (refreshed about once a day): " +
                            d.parts.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value.humanBytes()}" }
                    } else {
                        ""
                    },
            )
        }
        val s = shared
        if (s != null) {
            val folders = appFolderBytes ?: 0L
            val rest = (s.other - folders).coerceAtLeast(0)
            add(
                "Shared storage now: ${s.total.humanBytes()}: photos ${s.images.humanBytes()}, videos ${s.video.humanBytes()}, " +
                    "audio ${s.audio.humanBytes()}, app folders ${folders.humanBytes()}, everything else ${rest.humanBytes()}" +
                    (scanned?.let { " (the last scan saw ${it.humanBytes()} of your files, media included)" } ?: ""),
            )
            val said = disk?.parts?.get("Other")
            add(
                "\"Other\" is shared storage that isn't photos, videos or audio: ${s.other.humanBytes()} now, of which app folders " +
                    "(Android/data, obb and media) ${folders.humanBytes()} and everything else ${rest.humanBytes()}." +
                    if (said != null && kotlin.math.abs(said - s.other) > maxOf(2 * GIB, s.other / 10)) {
                        " Android's ${said.humanBytes()} is from its last count; it is ${s.other.humanBytes()} now."
                    } else {
                        ""
                    },
            )
        }
        if (appFolders.isNotEmpty()) {
            if (apps > 0) add(
                "Largest app folders: " + appFolders.take(apps).joinToString { f ->
                    val areas = f.byArea.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                    val split = if (areas.size > 1) " (" + areas.joinToString { "${it.key.dir} ${it.value.humanBytes()}" } + ")" else ""
                    "${f.name}${if (f.installed) "" else " [removed]"} ${f.bytes.humanBytes()}$split"
                },
            )
        } else if ((s?.apps ?: 0) > GIB) {
            add("Scan the app folders through Shizuku on the Apps tab to see which apps these are.")
        }
    }

    companion object {
        /** Per app, Android/data, obb and media added up, largest first. */
        fun appFolders(usage: List<AppAreaUsage>, labels: Map<String, String>): List<AppFolderTotal> =
            usage.groupBy { it.packageName }.map { (pkg, list) ->
                AppFolderTotal(
                    pkg,
                    labels[pkg],
                    list.sumOf { it.bytes },
                    list.groupBy { it.area }.mapValues { (_, l) -> l.sumOf { it.bytes } },
                    list.any { it.installed },
                )
            }.sortedByDescending { it.bytes }
    }
}
