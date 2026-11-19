package com.galaxy.steward.core

import com.galaxy.steward.core.device.DiskStats
import com.galaxy.steward.core.device.KnownApps
import com.galaxy.steward.core.device.PrivateData
import com.galaxy.steward.core.device.ShellSpace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeepSpaceTest {
    private lateinit var dir: File
    private val tmp get() = File(dir, "local-tmp")
    private val reports get() = File(dir, "bugreports")
    private val roots get() = listOf(tmp.path, reports.path)
    private val old = System.currentTimeMillis() - 30 * DAY_MS

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("deep-space").toFile().canonicalFile
        tmp.mkdirs()
        reports.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(f: File, bytes: Int, mtime: Long = old): File {
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun shellPlacesListWhatAdbLeftAndKeepTheToolsOtherAppsStart() {
        file(File(tmp, "app-debug.apk"), 5000)
        file(File(tmp, "shizuku_starter"), 300)
        file(File(tmp, "frida-server-16"), 900)
        file(File(tmp, "pushing-now.bin"), 100, System.currentTimeMillis())
        file(File(tmp, "dump/a.bin"), 2000)
        file(File(tmp, "dump/b/c.bin"), 1000)
        // A folder's own time changes when something is added to it: pushed a month ago, like its files.
        File(tmp, "dump").setLastModified(old)
        file(File(reports, "bugreport-2026-09-20.zip"), 7000)
        Files.createSymbolicLink(File(tmp, "link").toPath(), File(dir, "elsewhere").toPath())

        val lines = ArrayList<String>()
        ShellSpace.handle("list", { lines += it }, roots)
        val (entries, unreadable) = ShellSpace.readList(lines.asSequence())
        assertTrue(unreadable.isEmpty())
        val byName = entries.associateBy { it.name }
        assertEquals(setOf("app-debug.apk", "shizuku_starter", "frida-server-16", "pushing-now.bin", "dump", "bugreport-2026-09-20.zip"), byName.keys)
        assertEquals(3000L, byName.getValue("dump").bytes)
        assertEquals("Bug report", byName.getValue("bugreport-2026-09-20.zip").kind)
        assertEquals("Installer pushed with adb", byName.getValue("app-debug.apk").kind)
        assertNull(byName.getValue("app-debug.apk").lock)
        assertNotNull(byName.getValue("shizuku_starter").lock)
        assertNotNull(byName.getValue("frida-server-16").lock)
        assertTrue(byName.getValue("pushing-now.bin").lock!!.contains("10 minutes"))

        val out = ArrayList<String>()
        val request = listOf(
            "remove",
            File(tmp, "app-debug.apk").path,
            File(tmp, "shizuku_starter").path,
            File(tmp, "dump").path,
            File(tmp, "dump/a.bin").path,
            "${tmp.path}/../elsewhere",
            File(reports, "bugreport-2026-09-20.zip").path,
        ).joinToString("\n")
        ShellSpace.handle(request, { out += it }, roots)
        val results = ShellSpace.readRemoval(out.asSequence())
        assertEquals(listOf("REMOVED", "SKIP_LOCKED", "REMOVED", "SKIP_UNSAFE", "SKIP_UNSAFE", "REMOVED"), results.map { it.status })
        assertEquals(3000L, results[2].bytes)
        assertFalse(File(tmp, "dump").exists())
        assertTrue(File(tmp, "shizuku_starter").exists())
    }

    @Test
    fun androidsOwnBreakdownIsRead() {
        val text = """
            Latency: 2ms [512B Data Write]
            Data-Free: 41943040K / 234881024K total = 17% free
            Cache-Free: 41943040K / 234881024K total = 17% free
            System-Free: 0K / 12582912K total = 0% free
            File-based Encryption: true
            App Size: 21474836480
            App Data Size: 64424509440
            App Cache Size: 3221225472
            Photos Size: 1073741824
            Videos Size: 536870912
            Audio Size: 104857600
            Downloads Size: 209715200
            System Size: 17179869184
            Other Size: 42949672960
            Package Names: ["com.layla","tech.ula"]
            App Sizes: [1,2]
        """.trimIndent()
        val stats = DiskStats.parse(text)!!
        assertEquals(234881024L * 1024, stats.total)
        assertEquals(41943040L * 1024, stats.free)
        assertEquals(64424509440L, stats.parts.getValue("App data"))
        assertEquals(42949672960L, stats.parts.getValue("Other"))
        assertEquals(DiskStats.LABELS.values.toList(), stats.parts.keys.toList())
        assertNull(DiskStats.parse("Can't find service: diskstats"))
    }

    @Test
    fun privateDataOfADebugBuildIsReadSafelyAndUserLandsFilesystemsAreNamed() {
        val du = """
            4	./lib
            8	./shared_prefs
            262144	./files/1
            1048576	./files/2
            51200	./files/support
            40960	./files/debian-rootfs.tar.gz
            12	./files/small.txt
            20480	./cache
            1331200	./files
            1351680	.
        """.trimIndent()
        val entries = PrivateData.parseDu("tech.ula.devstudio", du.lineSequence())
        val rels = entries.map { it.rel }
        assertFalse("lib" in rels)
        assertFalse("." in rels)
        assertFalse("files/small.txt" in rels)
        assertEquals("files", rels.first())
        val labels = entries.associate { it.rel to it.label }
        assertTrue(labels.getValue("files/2")!!.startsWith("A Linux filesystem"))
        assertTrue(labels.getValue("files/support")!!.contains("needs them"))
        assertTrue(labels.getValue("files/debian-rootfs.tar.gz")!!.contains("can go"))
        assertEquals("Cache: the app rebuilds it", labels["cache"])

        assertTrue(PrivateData.validRel("files/2"))
        listOf("", "/data/x", "../x", "files/../../x", "-rf", "lib", "lib/libx.so", "files//x", "a\nb").forEach { assertFalse(it, PrivateData.validRel(it)) }
        assertEquals("Layla", KnownApps.hint("com.layla")!!.name)
        assertEquals("UserLAnd", KnownApps.hint("tech.ula.aicli")!!.name)
        assertNull(KnownApps.hint("com.example"))
    }
}
