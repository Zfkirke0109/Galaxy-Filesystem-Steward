package com.galaxy.steward.core.model

/** Coarse content category used for storage breakdowns and folder-content inference. */
enum class FileKind(val label: String) {
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    APK("Apps & APKs"),
    CODE("Code & scripts"),
    OTHER("Other");

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp", "svg", "dng", "raw", "tif", "tiff")
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "3g2", "ts", "wmv", "flv", "mpg", "mpeg")
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "3ga", "mid", "midi", "alac", "aiff", "dsf")
        val DOCUMENT_EXT = setOf(
            "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "html", "htm", "mht", "mhtml",
            "xls", "xlsx", "ods", "csv", "tsv", "ppt", "pptx", "odp", "epub", "mobi", "azw", "azw3",
            "fb2", "cbz", "cbr", "djvu", "json", "xml", "yaml", "yml", "toml", "ini", "log", "vcf", "ics",
        )
        val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "zst", "bz2", "lz4", "lzma", "cab", "md5")
        val APK_EXT = setOf("apk", "apks", "apkm", "xapk")
        val CODE_EXT = setOf(
            "sh", "bash", "zsh", "fish", "py", "rb", "pl", "js", "ts", "kt", "kts", "java", "c", "cc", "cpp", "h",
            "rs", "go", "lua", "ps1", "bat", "cmd", "gradle", "patch", "diff", "smali", "class", "dex", "swift", "m", "mm",
            "cs", "php", "scala", "dart", "proto", "aidl", "hpp", "cxx", "jsx", "tsx", "vue", "mjs", "cjs",
        )

        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
        }

        fun of(name: String): FileKind = when (extensionOf(name)) {
            in IMAGE_EXT -> IMAGE
            in VIDEO_EXT -> VIDEO
            in AUDIO_EXT -> AUDIO
            in APK_EXT -> APK
            in ARCHIVE_EXT -> ARCHIVE
            in CODE_EXT -> CODE
            in DOCUMENT_EXT -> DOCUMENT
            else -> OTHER
        }
    }
}
