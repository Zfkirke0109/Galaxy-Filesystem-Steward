package com.galaxy.steward.core

import com.galaxy.steward.core.model.FileKind
import com.galaxy.steward.core.model.Zone

/**
 * Hard safety rules ported from the Koa Termux steward (v18.x). Everything here is deliberately conservative:
 * credential-like files, project roots, Git trees, symlinks, app-owned storage and the steward's own quarantine
 * are never moved or removed, whatever mode or rule asks for it.
 */
object SafetyPolicy {
    const val STEWARD_DIR = ".StorageSteward"
    const val QUARANTINE_DIR = "Quarantine"

    /** Top-level folders Android itself creates; never offered for empty-folder cleanup. */
    val STANDARD_TOP_DIRS = setOf(
        "Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
        "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones",
    )
    /** Top-level folders the system writes into (Samsung's dumpstate logs); never removed even when empty. */
    val SYSTEM_TOP_DIRS = setOf("log")

    /** Top-level folders an empty-folder clean-up must keep. */
    fun isKeptTopDir(name: String): Boolean = name in STANDARD_TOP_DIRS || name in SYSTEM_TOP_DIRS

    val MEDIA_TOP_DIRS = setOf(
        "DCIM", "Pictures", "Movies", "Music", "Recordings", "Audiobooks", "Podcasts", "Ringtones", "Alarms", "Notifications",
    )
    val MANAGED_TOP_DIRS = setOf("Download", "Documents")
    const val MANAGED_MEDIA_SUBDIR = "Imported"

    private val PROJECT_MARKERS = setOf(
        ".git", "settings.gradle", "settings.gradle.kts", "gradlew", "gradlew.bat", "build.gradle", "build.gradle.kts",
        "pyproject.toml", "setup.py", "Cargo.toml", "go.mod", "pom.xml", "CMakeLists.txt", "Makefile", ".project",
        // Decompiled and other build trees: apktool output, Flutter, Ant, Meson, SwiftPM, PHP, Ruby, Deno.
        "apktool.yml", "pubspec.yaml", "build.xml", "meson.build", "Package.swift", "composer.json", "Gemfile", "deno.json",
    )

    /**
     * Folder names that only appear inside source or build trees. Below one of these, a folder name is part of
     * a package path (`com/acme/userConfig/userConfig`), so a repeated name is never a redundant wrapper.
     */
    private val CODE_TREE_DIR = Regex("""^(src|smali(_classes\d+)?|java|kotlin|sources|jni|node_modules|site-packages|vendor|third_party)$""")

    private val CREDENTIAL_EXT = setOf(
        "jks", "keystore", "p12", "pfx", "pem", "key", "crt", "cer", "der", "kdbx", "kdb", "ovpn",
        "mobileconfig", "mobileprovision", "gpg", "pgp", "asc", "ppk",
    )
    private val CREDENTIAL_EXACT = setOf(".env", "credentials", "secrets", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa", "wallet.dat")
    private val CREDENTIAL_PREFIX = listOf(".env.", "credentials.", "secrets.", "id_rsa.", "id_ed25519.", "id_ecdsa.", "id_dsa.")
    private val CREDENTIAL_WORDS = listOf(
        "credential", "secret", "recovery-code", "recovery_code", "recovery code", "private-key", "private_key",
        "private key", "license-key", "license_key", "backup-codes", "backup_codes", "backup codes", "wallet", "passwords",
    )

    private val GENERIC_WRAPPERS = setOf(
        "documents", "document", "download", "downloads", "organized", "files", "file", "misc", "miscellaneous",
        "other", "others", "automatic", "new folder", "untitled folder", "folder", "inbox", "received", "received files",
        "quick share", "nearby share", "bluetooth", "shared", "sharing", "media", "temp", "tmp",
    )

    private val IN_PROGRESS_EXT = setOf("crdownload", "part", "partial", "download", "tmp", "temp", "opdownload")
    private val MARKER_NAMES = setOf(".nomedia", ".gitkeep", ".keep", ".placeholder")
    private val COPY_MARKER = Regex("""(?i)(\s?\(\d+\)$|[\s_-]+copy(\s?\(?\d+\)?)?$|^copy of\s|\s-\s?copy$)""")

    fun isCredentialName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in CREDENTIAL_EXACT) return true
        if (CREDENTIAL_PREFIX.any { lower.startsWith(it) }) return true
        if (FileKind.extensionOf(lower) in CREDENTIAL_EXT) return true
        return CREDENTIAL_WORDS.any { lower.contains(it) }
    }

    fun isProjectMarker(name: String): Boolean = name in PROJECT_MARKERS

    /**
     * Project-root detection from a directory listing, as in the Termux planner: explicit build/VCS markers, or
     * package.json / AndroidManifest.xml next to source folders.
     */
    fun isProjectRoot(childNames: Collection<String>): Boolean {
        if (childNames.any { it in PROJECT_MARKERS }) return true
        if ("package.json" in childNames && ("src" in childNames || "node_modules" in childNames)) return true
        if ("AndroidManifest.xml" in childNames && ("src" in childNames || "res" in childNames || childNames.any { it.startsWith("smali") })) return true
        return false
    }

    fun isCodeTreeDir(name: String): Boolean = CODE_TREE_DIR.matches(name)

    private val SMALI_DIR = Regex("""^smali(_classes\d+)?$""")

    /**
     * Folder names that hold development work. Below the top level (`Download/Projects`, `Documents/src`) the whole
     * folder is treated like a project: never moved, bucketed or deduplicated, though it can serve as the kept copy.
     */
    private val DEV_CONTAINERS = setOf(
        "projects", "workspace", "workspaces", "repos", "repositories", "git", "github", "gitlab",
        "src", "source", "sources", "code", "decompiled", "jadx", "apktool", "smali",
    )

    fun isDevContainerName(name: String): Boolean = name.trim().lowercase() in DEV_CONTAINERS

    /**
     * Decompiled apps from a directory listing: apktool output (smali, smali_classesN), jadx output (sources next to
     * resources), or an unpacked APK (classes.dex next to AndroidManifest.xml). Their folder names are Java packages.
     */
    fun isDecompiledAppRoot(childNames: Collection<String>): Boolean {
        if (childNames.any { SMALI_DIR.matches(it) }) return true
        if ("sources" in childNames && "resources" in childNames) return true
        return "classes.dex" in childNames && "AndroidManifest.xml" in childNames
    }

    /** A subtree this code-heavy is source code, whatever it is called (at least 50 code files and half of all files). */
    fun isCodeDominated(codeFiles: Int, totalFiles: Int): Boolean = codeFiles >= 50 && codeFiles * 2 >= totalFiles

    /** Names containing record separators would corrupt TSV journals, so the steward never touches them. */
    fun isUnsafeName(name: String): Boolean = name.any { it == '\t' || it == '\n' || it == '\r' || it == '\u0000' }

    fun isGenericWrapperName(name: String): Boolean = name.trim().lowercase() in GENERIC_WRAPPERS

    /** Wrappers that are redundant *inside Documents* (Documents/Documents, Documents/Download, ...). */
    fun isDocumentsWrapperName(name: String): Boolean =
        name.trim().lowercase() in setOf("documents", "document", "download", "downloads", "organized", "new folder", "untitled folder")

    fun isInProgressDownload(name: String): Boolean = FileKind.extensionOf(name) in IN_PROGRESS_EXT

    fun isMarkerFile(name: String): Boolean = name.lowercase() in MARKER_NAMES

    fun stemOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /** "IMG_1234 (1).jpg", "report - Copy.pdf", "Copy of notes.txt" - copies a human or app made by accident. */
    fun hasCopyMarker(name: String): Boolean = COPY_MARKER.containsMatchIn(stemOf(name))

    /** Zone of a top-level folder directly under the storage root. */
    fun topLevelZone(name: String): Zone = when {
        name == "Android" -> Zone.APP_OWNED
        name == STEWARD_DIR -> Zone.STEWARD
        name in MEDIA_TOP_DIRS -> Zone.MEDIA_LIBRARY
        name in MANAGED_TOP_DIRS -> Zone.USER_MANAGED
        else -> Zone.OTHER_SHARED
    }
}
