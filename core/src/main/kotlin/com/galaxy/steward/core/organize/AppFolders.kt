package com.galaxy.steward.core.organize

/**
 * Top-level folders that installed apps create and write to by path: Telegram, Poweramp, ZArchiver and so on. Moving
 * one only makes its app create it again, empty, and lose track of what it saved. A folder belongs to an app when its
 * name is the app's label, the first word of a longer label, or a distinctive word of the app's package name.
 */
class AppFolderIndex(apps: Map<String, String>) {
    private val owners = HashMap<String, String>()

    init {
        for ((pkg, label) in apps) {
            val keys = buildList {
                add(key(label))
                label.trim().split(' ').firstOrNull()?.let(::key)?.takeIf { it.length >= 5 && it !in GENERIC }?.let(::add)
                pkg.split('.').map(::key).filter { it.length >= 4 && it !in GENERIC }.forEach(::add)
            }
            for (k in keys) if (k.length >= 3 && k !in GENERIC) owners.putIfAbsent(k, label.ifBlank { pkg })
        }
    }

    /** The label of the installed app [folder] belongs to, or null. */
    fun owner(folder: String): String? = owners[key(folder)]

    private fun key(s: String) = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private companion object {
        /** Words too common in labels and package names to say which app a folder belongs to. */
        val GENERIC = setOf(
            "com", "org", "net", "android", "google", "samsung", "galaxy", "apps", "app", "mobile", "phone", "client",
            "lite", "free", "plus", "beta", "main", "music", "video", "videos", "photo", "photos", "files", "file",
            "manager", "player", "camera", "gallery", "download", "downloads", "documents", "pictures", "movies",
            "the", "my", "pro", "dev", "debug", "release", "launcher", "browser", "notes", "tools", "tool",
            "backup", "backups", "media", "image", "images", "audio", "books", "games", "game", "studio",
        )
    }
}
