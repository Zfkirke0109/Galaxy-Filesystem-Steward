package com.galaxy.steward.core.organize

import com.galaxy.steward.core.model.FileKind

enum class RuleTarget { FILES, FOLDERS, BOTH }

/**
 * A semantic filing rule: whole-word keywords (a trailing `*` matches a word prefix) and/or file extensions,
 * mapped to a destination relative to the storage root. `{device}` expands to the device label.
 */
data class KeywordRule(
    val name: String,
    val keywords: List<String>,
    val destination: String,
    val extensions: Set<String> = emptySet(),
    val target: RuleTarget = RuleTarget.BOTH,
) {
    private val normalizedKeywords = keywords.mapNotNull { Text.keyword(it) }

    fun matchesFile(name: String): Boolean {
        if (target == RuleTarget.FOLDERS) return false
        if (extensions.isNotEmpty() && FileKind.extensionOf(name) !in extensions) return false
        if (normalizedKeywords.isEmpty()) return extensions.isNotEmpty()
        return Text.matchesAny(Text.normalize(name), normalizedKeywords)
    }

    fun matchesFolder(name: String): Boolean {
        if (target == RuleTarget.FILES || normalizedKeywords.isEmpty()) return false
        return Text.matchesAny(Text.normalize(name), normalizedKeywords)
    }

    fun resolvedDestination(deviceLabel: String): String = destination.replace("{device}", deviceLabel).trim('/')

    /** Tab-separated persistence format used by the settings store. */
    fun encode(): String = listOf(
        name, keywords.joinToString(","), extensions.joinToString(","), destination, target.name,
    ).joinToString("\t")

    companion object {
        fun decode(line: String): KeywordRule? {
            val p = line.split('\t')
            if (p.size < 4 || p[0].isBlank() || p[3].isBlank()) return null
            return KeywordRule(
                name = p[0],
                keywords = p[1].split(',').map { it.trim() }.filter { it.isNotEmpty() },
                extensions = p[2].split(',').map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotEmpty() }.toSet(),
                destination = p[3].trim().trim('/'),
                target = p.getOrNull(4)?.let { runCatching { RuleTarget.valueOf(it) }.getOrNull() } ?: RuleTarget.BOTH,
            )
        }
    }
}

object Text {
    private val nonWord = Regex("[^a-z0-9]+")

    /** Lower-case words separated by single spaces, padded so " word " matches whole words. */
    fun normalize(s: String): String = " " + s.lowercase().replace(nonWord, " ").trim() + " "

    /** A keyword pattern: " words " for whole-word matches, " words" (no trailing space) for `words*` prefixes. */
    fun keyword(raw: String): String? {
        val prefix = raw.trim().endsWith("*")
        val core = normalize(raw.trim().removeSuffix("*")).trim()
        if (core.isEmpty()) return null
        return if (prefix) " $core" else " $core "
    }

    fun matchesAny(normalizedName: String, patterns: List<String>): Boolean = patterns.any { normalizedName.contains(it) }
}

/** Built-in semantic taxonomy, generalized from the Koa v18.1 topology planner. Order matters: first match wins. */
object BuiltInRules {
    private val DOCS = setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md", "html", "htm", "mht", "mhtml", "pages")
    private val SHEETS = setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers")
    private val IMAGES = FileKind.IMAGE_EXT
    private val DOC_LIKE = DOCS + SHEETS + setOf("json", "xml", "log", "zip", "gz")
    private val PAPERS = DOCS + SHEETS + IMAGES

    val rules: List<KeywordRule> = listOf(
        KeywordRule(
            "Diagnostics & logs",
            listOf("logcat", "dumpstate", "bugreport", "bug report", "diagnostic*", "leakcanary", "tombstone*", "anr", "crash log*", "stacktrace", "trace"),
            "Documents/Reports/Diagnostics",
            extensions = DOC_LIKE + setOf("trace", "hprof", "pb", "tar", "7z", "txt"),
        ),
        KeywordRule(
            "Device firmware",
            listOf("firmware", "odin", "boot img", "vendor boot", "recovery img", "init boot", "magisk patched*", "multi cert*", "user low ship*", "home csc*"),
            "Documents/Android-Device/{device}/Firmware",
        ),
        KeywordRule(
            "Personal finance",
            listOf("bank", "statement*", "invoice*", "receipt*", "payslip*", "paystub*", "w 2", "1099", "tax", "taxes", "financial", "credit union", "billing", "payroll"),
            "Documents/Personal/Finance",
            extensions = PAPERS,
        ),
        KeywordRule(
            "Travel",
            listOf("itinerary", "boarding pass*", "e ticket*", "eticket*", "reservation*", "booking*", "flight*", "hotel*", "travel"),
            "Documents/Personal/Travel",
            extensions = PAPERS,
        ),
        KeywordRule(
            "Health",
            listOf("medical", "prescription*", "lab result*", "vaccin*", "insurance card", "health"),
            "Documents/Personal/Health",
            extensions = PAPERS,
        ),
        KeywordRule(
            "Career",
            listOf("resume", "cv", "cover letter", "offer letter", "curriculum vitae"),
            "Documents/Personal/Career",
            extensions = DOCS,
        ),
        KeywordRule(
            "Security & reversing",
            listOf("apktool", "jadx", "frida", "ghidra", "smali", "revengi", "npatch", "forensic*", "reverse engineering", "jni hook*", "hooking"),
            "Documents/Security-Reversing",
        ),
        KeywordRule(
            "AI models",
            listOf("gguf", "llama*", "mistral", "gemma", "qwen*", "whisper", "ggml", "layla", "ai model*", "lora"),
            "Documents/AI-Models",
        ),
        KeywordRule("AI model files", emptyList(), "Documents/AI-Models", extensions = setOf("gguf", "ggml", "safetensors", "onnx", "tflite", "ckpt", "pth", "mlmodel")),
        KeywordRule(
            "Audio DSP",
            listOf("jamesdsp", "viper*", "wavelet", "autoeq", "convolver", "impulse response*", "dsp"),
            "Documents/Audio-DSP",
        ),
        KeywordRule("DSP presets", emptyList(), "Documents/Audio-DSP/Presets", extensions = setOf("irs", "vdc", "eel", "vdp")),
        KeywordRule(
            "Gaming & emulation",
            listOf("rom", "roms", "bios", "ps1", "psx", "ps2", "psp", "pcsx*", "aethersx*", "nethersx*", "retroarch", "dolphin", "citra", "yuzu", "ppsspp", "emulator*", "savestate*"),
            "Documents/Gaming-Emulation",
        ),
        KeywordRule(
            "Game ROMs",
            emptyList(),
            "Documents/Gaming-Emulation/ROMs",
            extensions = setOf("nes", "sfc", "smc", "gb", "gbc", "gba", "nds", "3ds", "cia", "n64", "z64", "v64", "chd", "cso", "pbp", "rvz", "wbfs", "xci", "nsp", "gcm"),
        ),
        KeywordRule(
            "Android development",
            listOf("termux", "shizuku", "magisk", "lsposed", "xposed", "gradle", "kotlin", "adb", "fastboot", "platform tools", "android mod*", "twrp", "kernelsu", "apatch"),
            "Documents/Development/Android",
        ),
        KeywordRule(
            "Backups & exports",
            listOf("backup*", "export*", "titanium", "swift backup", "neo backup", "takeout"),
            "Documents/Backups",
        ),
        KeywordRule("Backup files", emptyList(), "Documents/Backups", extensions = setOf("bak", "ab", "tibkp", "layladata", "nbackup")),
        KeywordRule(
            "Research",
            listOf("research", "benchmark*", "specification*", "whitepaper*", "paper", "thesis", "datasheet*"),
            "Documents/Research",
            extensions = DOCS + SHEETS,
        ),
        KeywordRule("Reports", listOf("report*", "summary", "summaries"), "Documents/Reports", extensions = DOCS + SHEETS),
    )

    /** Folder names that *are* a category: their contents merge straight into the canonical home. */
    val categoryFolders: Map<String, String> = mapOf(
        "apk" to "Documents/Software/APKs", "apks" to "Documents/Software/APKs",
        "archive" to "Documents/Archives", "archives" to "Documents/Archives", "zips" to "Documents/Archives",
        "script" to "Documents/Development/Scripts", "scripts" to "Documents/Development/Scripts",
        "build artifacts" to "Documents/Development/Build-Artifacts", "builds" to "Documents/Development/Build-Artifacts",
        "logs" to "Documents/Reports/Diagnostics", "log" to "Documents/Reports/Diagnostics",
        "logs evidence" to "Documents/Reports/Diagnostics", "diagnostics" to "Documents/Reports/Diagnostics",
        "research" to "Documents/Research", "reports" to "Documents/Reports",
        "backup" to "Documents/Backups", "backups" to "Documents/Backups",
        "model" to "Documents/AI-Models", "models" to "Documents/AI-Models",
        "ebooks" to "Documents/Books", "books" to "Documents/Books", "fonts" to "Documents/Fonts",
        "roms" to "Documents/Gaming-Emulation/ROMs",
        "screenshots" to "Pictures/Screenshots", "wallpapers" to "Pictures/Wallpapers",
        "finance" to "Documents/Personal/Finance", "financial" to "Documents/Personal/Finance",
    )

    fun categoryFolderDestination(name: String): String? = categoryFolders[Text.normalize(name).trim()]

    /** Extension-based homes used when no keyword rule matched. */
    fun extensionDestination(ext: String): Pair<String, String>? = when (ext) {
        in FileKind.APK_EXT -> "Documents/Software/APKs" to "App installer"
        in setOf("exe", "msi", "dmg", "appimage", "pkg") -> "Documents/Software/Installers" to "Desktop installer"
        in setOf("aab", "aar", "jar", "war", "so", "dex", "img", "bin", "deb", "rpm", "ipa", "patch", "diff", "hex") ->
            "Documents/Development/Build-Artifacts" to "Build artifact"
        in FileKind.ARCHIVE_EXT, "iso" -> "Documents/Archives" to "Archive"
        in FileKind.CODE_EXT -> "Documents/Development/Scripts" to "Script or source"
        in DOCS -> "Documents/Reference" to "Document"
        in SHEETS -> "Documents/Spreadsheets" to "Spreadsheet"
        in setOf("ppt", "pptx", "odp") -> "Documents/Presentations" to "Presentation"
        in setOf("json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "log", "db", "sqlite") -> "Documents/Data" to "Data file"
        in setOf("epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "djvu") -> "Documents/Books" to "E-book"
        in setOf("vcf", "ics") -> "Documents/Personal/Contacts-Calendar" to "Contacts or calendar"
        in setOf("ttf", "otf", "woff", "woff2") -> "Documents/Fonts" to "Font"
        in setOf("psd", "ai", "xcf", "kra", "sketch", "fig", "afdesign", "afphoto", "procreate") -> "Documents/Design" to "Design file"
        in setOf("stl", "obj", "3mf", "gcode", "step", "stp", "blend", "fbx") -> "Documents/3D-Models" to "3D model"
        in setOf("srt", "ass", "ssa", "vtt", "sub") -> "Movies/Subtitles" to "Subtitles"
        "torrent" -> "Documents/Torrents" to "Torrent file"
        else -> null
    }
}
