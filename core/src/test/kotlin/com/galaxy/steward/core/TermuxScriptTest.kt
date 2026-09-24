package com.galaxy.steward.core

import com.galaxy.steward.core.termux.TermuxCleanSummary
import com.galaxy.steward.core.termux.TermuxException
import com.galaxy.steward.core.termux.TermuxGroup
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.core.termux.TermuxReport
import com.galaxy.steward.core.termux.TermuxScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the real termux-steward.sh with bash against a disposable fake Termux sandbox
 * (<fixture>/files/home, <fixture>/files/usr, <fixture>/cache), exactly as Termux would run it.
 */
class TermuxScriptTest {
    private lateinit var fixture: File
    private lateinit var outside: File
    private val home get() = File(fixture, "files/home")
    private val prefix get() = File(fixture, "files/usr")
    private val debian get() = File(prefix, "var/lib/proot-distro/installed-rootfs/debian")
    private val old = System.currentTimeMillis() - 30 * DAY_MS

    private fun write(f: File, bytes: Int, mtime: Long? = null): File {
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        if (mtime != null) f.setLastModified(mtime)
        return f
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.email=t@example.com", "-c", "user.name=t") + args)
            .directory(dir).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0)
    }

    private fun haveTool(name: String) = try {
        ProcessBuilder(name, "--version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
    } catch (_: Exception) {
        false
    }

    @Before
    fun setUp() {
        assumeTrue("bash and git are needed to run the Termux helper", haveTool("bash") && haveTool("git"))
        fixture = Files.createTempDirectory("termux-fixture").toFile().canonicalFile
        outside = Files.createTempDirectory("termux-outside").toFile().canonicalFile
        File(fixture, ".steward-termux-fixture").writeText("DISPOSABLE-FIXTURE\n")
        write(File(prefix, "var/cache/apt/archives/python_3.12_aarch64.deb"), 3000)
        write(File(prefix, "var/cache/apt/pkgcache.bin"), 100)
        write(File(prefix, "tmp/old-session.log"), 40, old)
        write(File(prefix, "tmp/current.sock-ish"), 10)
        write(File(fixture, "cache/termux-cache.bin"), 20)
        write(File(home, ".cache/pip/http/blob"), 5000)
        write(File(home, ".cache/huggingface/model.safetensors"), 7000)
        write(File(outside, "precious.txt"), 10)
        Files.createSymbolicLink(File(home, ".cache/uv").toPath(), outside.toPath()) // a cache path redirected elsewhere
        write(File(debian, "var/cache/apt/archives/curl.deb"), 2000)
        write(File(debian, "root/.cache/pip/wheel"), 300)
        write(File(debian, "tmp/old"), 50, old)
        write(File(home, "proj/src/main.c"), 30)
        write(File(home, "proj/build/out.o"), 400)
        write(File(home, "proj/node_modules/pkg/index.js"), 500)
        File(home, "proj/.gitignore").writeText("build/\nnode_modules/\n")
        // Caches this version added: downloaded distro images, npx, Yarn, Cargo Git, old Gradle daemon logs, trash, and
        // Python bytecode anywhere in the home (but never inside a distro or shared storage).
        write(File(prefix, "var/lib/proot-distro/dlcache/debian-aarch64.tar.xz"), 800)
        write(File(prefix, "var/log/apt/history.log"), 60, old)
        write(File(home, ".npm/_npx/abc/node_modules/x/index.js"), 70)
        write(File(home, ".cache/yarn/v6/pkg.tgz"), 80)
        write(File(home, ".cargo/git/checkouts/dep/src/lib.rs"), 90)
        write(File(home, ".gradle/daemon/8.9/daemon-1.out.log"), 110, old)
        write(File(home, ".gradle/daemon/8.9/daemon-2.out.log"), 120)
        write(File(home, ".local/share/Trash/files/old.txt"), 130)
        write(File(home, "scripts/__pycache__/tool.cpython-312.pyc"), 140)
        write(File(home, "scripts/tool.py"), 20)
        write(File(debian, "usr/lib/python3/__pycache__/os.cpython-312.pyc"), 150)
        write(File(debian, "root/.npm/_npx/def/index.js"), 160)
        git(File(home, "proj"), "init", "-q")
        git(File(home, "proj"), "add", ".gitignore", "src")
        git(File(home, "proj"), "commit", "-qm", "init")
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.deleteRecursively()
        if (::outside.isInitialized) outside.deleteRecursively()
    }

    private fun run(mode: String, targets: List<String> = emptyList(), homeDir: File = home, prefixDir: File = prefix): String {
        val out = File(fixture, "result-$mode.tsv")
        val pb = ProcessBuilder(listOf("bash") + TermuxScript.arguments(mode, out.path, targets)).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", homeDir.path)
            put("PREFIX", prefixDir.path)
            put("STEWARD_TERMUX_FIXTURE", "1")
        }
        val p = pb.start()
        val stdout = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        // The full report goes to the --out file; stdout only names it.
        return if (out.isFile && out.length() > 0) out.readText() else stdout
    }

    private fun audit(): TermuxReport = TermuxProtocol.parseAudit(run("audit"))

    @Test
    fun auditFindsCachesBuildOutputsAndDistros() {
        val report = audit()
        val byTarget = report.items.groupBy { it.targetId }
        assertEquals(3000L, byTarget.getValue("apt-archives").single().bytes)
        assertEquals(1, byTarget.getValue("termux-tmp").single().files) // only the week-old file
        assertEquals(5000L, byTarget.getValue("pip-cache").single().bytes)
        assertEquals("other-cache=${home.path}/.cache/huggingface", byTarget.getValue("other-cache").single().spec)
        assertFalse(byTarget.getValue("other-cache").single().defaultSelected)
        assertEquals(setOf("build=${home.path}/proj/build", "build=${home.path}/proj/node_modules"), byTarget.getValue("build").map { it.spec }.toSet())
        assertEquals(3, report.items.count { it.targetId == "proot-cache" }) // apt archives, pip and npx
        assertTrue(report.items.single { it.targetId == "proot-tmp" }.title.startsWith("Distro temp files: debian/"))
        assertEquals(listOf(debian.path), report.rootfs.map { it.path })
        assertTrue(report.unsafe.any { it.endsWith("/.cache/uv") }) // symlinked cache is refused, not followed
        assertTrue(report.items.none { it.targetId == "uv-cache" })
        assertTrue(report.items.none { it.path.contains("/src") })
        assertTrue(report.totalBytes > 0)
        assertTrue(report.items.any { it.group == TermuxGroup.DEV })

        assertEquals(800L, byTarget.getValue("proot-dlcache").single().bytes)
        assertEquals(60L, byTarget.getValue("termux-var-log").single().bytes)
        assertEquals(70L, byTarget.getValue("npx-cache").single().bytes)
        assertEquals(80L, byTarget.getValue("yarn-cache").single().bytes)
        assertEquals(90L, byTarget.getValue("cargo-git-checkouts").single().bytes)
        assertEquals(110L, byTarget.getValue("gradle-daemon-logs").single().bytes) // the week-old log only
        assertEquals(130L, byTarget.getValue("trash").single().bytes)
        assertEquals(140L, byTarget.getValue("pycache").single().bytes) // not the distro's bytecode
        assertTrue(byTarget.getValue("proot-cache").any { it.path.endsWith("/root/.npm/_npx") })
        assertTrue(byTarget.getValue("other-cache").none { it.path.endsWith("/yarn") }) // counted once, as its own target
    }

    @Test
    fun cleanRemovesOnlyValidatedTargets() {
        val specs = audit().items.map { it.spec } + listOf(
            "uv-cache", // symlinked to a folder outside Termux
            "other-cache=${outside.path}", // forged path outside ~/.cache
            "build=${home.path}/proj/src", // tracked sources
            "proot-cache=${debian.path}/etc", // not a package cache
            "no-such-target",
        )
        val summary: TermuxCleanSummary = TermuxProtocol.parseClean(run("clean", specs))

        assertFalse(File(prefix, "var/cache/apt/archives/python_3.12_aarch64.deb").exists())
        assertFalse(File(prefix, "tmp/old-session.log").exists())
        assertTrue(File(prefix, "tmp/current.sock-ish").exists())
        assertFalse(File(home, ".cache/pip/http/blob").exists())
        assertTrue(File(home, ".cache/pip").isDirectory) // the cache folder itself stays
        assertFalse(File(home, "proj/build").exists())
        assertFalse(File(home, "proj/node_modules").exists())
        assertTrue(File(home, "proj/src/main.c").exists())
        assertFalse(File(debian, "var/cache/apt/archives/curl.deb").exists())
        assertTrue(File(outside, "precious.txt").exists())
        assertFalse(File(prefix, "var/lib/proot-distro/dlcache/debian-aarch64.tar.xz").exists())
        assertFalse(File(home, ".gradle/daemon/8.9/daemon-1.out.log").exists())
        assertTrue(File(home, ".gradle/daemon/8.9/daemon-2.out.log").exists()) // a current log stays
        assertFalse(File(home, "scripts/__pycache__").exists())
        assertTrue(File(home, "scripts/tool.py").exists())
        assertTrue(File(debian, "usr/lib/python3/__pycache__/os.cpython-312.pyc").exists())
        assertFalse(File(home, ".local/share/Trash/files/old.txt").exists())

        val status = summary.results.associate { (it.targetId + "=" + it.path.substringAfterLast('/')) to it.status }
        assertEquals("SKIP_UNSAFE", status["uv-cache=uv"])
        assertEquals("SKIP_UNSAFE", status["other-cache=" + outside.name])
        assertEquals("SKIP_TRACKED", status["build=src"])
        assertEquals("SKIP_UNSAFE", status["proot-cache=etc"])
        assertEquals("SKIP_UNKNOWN", status["no-such-target="])
        assertTrue(summary.freed >= 3000 + 100 + 5000 + 7000 + 2000 + 400 + 500)
    }

    @Test
    fun largeFilesAndFoldersComeFromOneWalk() {
        // Real (non-sparse) data: the audit sizes everything of 50 MiB or more in a single du pass.
        val big = File(home, "models/llama.gguf")
        big.parentFile.mkdirs()
        big.outputStream().use { out ->
            val chunk = ByteArray(1 shl 20) { (it % 7).toByte() }
            repeat(51) { out.write(chunk) }
        }
        val report = audit()
        assertEquals(listOf(big.path), report.largeFiles.map { it.path })
        assertEquals(51L * 1024 * 1024, report.largeFiles.single().size)
        assertTrue(report.largeFiles.single().mtime > 0)
        assertTrue(report.usage.any { it.path == File(home, "models").path && it.bytes >= 51L * 1024 * 1024 })
        assertTrue(report.totalBytes >= 51L * 1024 * 1024)
    }

    @Test
    fun refusesToRunOutsideTermux() {
        val elsewhere = Files.createTempDirectory("not-termux").toFile()
        try {
            val e = runCatching { TermuxProtocol.parseAudit(run("audit", homeDir = elsewhere, prefixDir = elsewhere)) }.exceptionOrNull()
            assertTrue(e is TermuxException)
            assertTrue(File(prefix, "var/cache/apt/archives/python_3.12_aarch64.deb").exists())
        } finally {
            elsewhere.deleteRecursively()
        }
    }

    @Test
    fun truncatedOutputIsRejected() {
        val full = run("audit")
        val cut = full.lineSequence().takeWhile { !it.startsWith("E\t") }.joinToString("\n")
        assertTrue(runCatching { TermuxProtocol.parseAudit(cut) }.exceptionOrNull() is TermuxException)
    }
}
