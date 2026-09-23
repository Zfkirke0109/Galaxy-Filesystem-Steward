package com.galaxy.steward.core

import com.galaxy.steward.core.exec.MediaRescan
import com.galaxy.steward.core.organize.BuiltInRules
import com.galaxy.steward.core.organize.KeywordRule
import com.galaxy.steward.core.organize.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAndRulesTest {
    @Test
    fun credentialNamesAreProtected() {
        listOf(".env", ".env.local", "id_rsa", "id_ed25519.pub", "release.keystore", "upload.jks", "vault.kdbx",
            "my-secret-notes.txt", "google-recovery-codes.txt", "wallet.dat", "server.pem", "home.ovpn").forEach {
            assertTrue(it, SafetyPolicy.isCredentialName(it))
        }
        listOf("photo.jpg", "report.pdf", "keyboard.apk", "monkey.png").forEach {
            assertFalse(it, SafetyPolicy.isCredentialName(it))
        }
    }

    @Test
    fun copyMarkersAreRecognised() {
        listOf("IMG_1234 (1).jpg", "report - Copy.pdf", "notes_copy.txt", "Copy of plan.docx", "song(2).mp3").forEach {
            assertTrue(it, SafetyPolicy.hasCopyMarker(it))
        }
        listOf("IMG_1234.jpg", "IMG-20240101-WA0001.jpg", "copycat.png").forEach {
            assertFalse(it, SafetyPolicy.hasCopyMarker(it))
        }
    }

    @Test
    fun projectRootsNeedMarkers() {
        assertTrue(SafetyPolicy.isProjectRoot(listOf("src", ".git")))
        assertTrue(SafetyPolicy.isProjectRoot(listOf("settings.gradle.kts", "app")))
        assertTrue(SafetyPolicy.isProjectRoot(listOf("package.json", "src")))
        assertFalse(SafetyPolicy.isProjectRoot(listOf("package.json", "readme.md")))
        assertFalse(SafetyPolicy.isProjectRoot(listOf("photos", "notes.txt")))
    }

    @Test
    fun keywordsMatchWholeWordsAndPrefixes() {
        val rule = KeywordRule("Travel", listOf("boarding pass*", "hotel"), "Documents/Travel", extensions = setOf("pdf"))
        assertTrue(rule.matchesFile("Boarding_Passes-Lisbon.pdf"))
        assertTrue(rule.matchesFile("hotel-booking.pdf"))
        assertFalse(rule.matchesFile("hotelier.pdf"))
        assertFalse(rule.matchesFile("hotel.jpg"))
        assertFalse(rule.matchesFolder("x"))
    }

    @Test
    fun extensionOnlyRulesMatchFilesNotFolders() {
        val rule = BuiltInRules.rules.first { it.name == "Game ROMs" }
        assertTrue(rule.matchesFile("Pokemon Emerald.gba"))
        assertFalse(rule.matchesFolder("gba"))
    }

    @Test
    fun rulesRoundTripThroughEncoding() {
        val rule = KeywordRule("Taxes", listOf("tax*", "w 2"), "Documents/Personal/Taxes", setOf("pdf", "png"), RuleTarget.FILES)
        assertEquals(rule, KeywordRule.decode(rule.encode()))
        assertNull(KeywordRule.decode("broken"))
    }

    @Test
    fun deviceFirmwareUsesDeviceLabel() {
        val rule = BuiltInRules.rules.first { it.name == "Device firmware" }
        assertTrue(rule.matchesFile("AP_S918BXXS3AWF7_MQB_REV00_user_low_ship_MULTI_CERT_meta_OS13.tar.md5"))
        assertEquals("Documents/Android-Device/Samsung-SM-S918B/Firmware", rule.resolvedDestination("Samsung-SM-S918B"))
    }

    @Test
    fun categoryFoldersResolve() {
        assertNotNull(BuiltInRules.categoryFolderDestination("APKs"))
        assertEquals("Documents/Reports/Diagnostics", BuiltInRules.categoryFolderDestination("Logs-Evidence"))
        assertNull(BuiltInRules.categoryFolderDestination("Holiday 2024"))
    }

    @Test
    fun humanBytesFormatting() {
        assertEquals("512 B", 512L.humanBytes())
        assertEquals("1.0 KiB", 1024L.humanBytes())
        assertEquals("1.5 MiB", (MIB + MIB / 2).humanBytes())
        assertEquals("2.0 GiB", (2 * GIB).humanBytes())
    }

    @Test
    fun mediaRescanCollapsesToAffectedFolders() {
        val root = "/storage/emulated/0"
        val dirs = setOf("$root/Documents/Archives/Presets", "$root/Documents/Archives/Presets/Full", "$root/Download/Trip")
        val changed = listOf(
            "$root/Download/Trip/a.jpg", // moved away: its folder is rescanned
            "$root/Download/Trip/b.jpg",
            "$root/Documents/Archives/Presets", // folder moved here: rescanned as a whole ...
            "$root/Documents/Archives/Presets/Full/x.vdc", // ... so its contents need no call of their own
            "$root/notes.txt", // top-level file: the file itself, never the whole volume
            "$root/.StorageSteward/Quarantine/run/Download/c.tmp", // quarantine is never indexed
            "/elsewhere/file",
        )
        val targets = MediaRescan.targets(root, changed) { it in dirs }
        assertEquals(listOf("$root/Documents/Archives/Presets", "$root/Download/Trip", "$root/notes.txt"), targets)
    }
}
