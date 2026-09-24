package com.galaxy.steward.core

import com.galaxy.steward.core.termux.TermuxException
import com.galaxy.steward.core.termux.TermuxProtocol
import com.galaxy.steward.core.termux.TermuxScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * What 1.2.8 added to termux-steward.sh, run for real against a disposable fake Termux: dates in the size map, when
 * packages were installed and last run, programs from npm, pip and cargo, Git repositories, leftovers, identical files,
 * folder sketches, and moving a project out of shared storage and back.
 */
class TermuxInventoryTest {
    private lateinit var fixture: File
    private val home get() = File(fixture, "files/home")
    private val prefix get() = File(fixture, "files/usr")
    private val shared get() = File(fixture, "shared")
    private val debian get() = File(prefix, "var/lib/proot-distro/containers/debian/rootfs")
    private val mib = 1 shl 20

    private fun write(f: File, bytes: Int, seed: Int = 0, mtime: Long? = null): File {
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes) { ((it * 31 + seed) % 251).toByte() })
        mtime?.let { f.setLastModified(it) }
        return f
    }

    private fun text(f: File, content: String): File {
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    private fun tool(name: String, body: String) {
        val f = text(File(prefix, "bin/$name"), "#!/bin/bash\n$body\n")
        f.setExecutable(true)
    }

    private fun works(vararg command: String) = try {
        ProcessBuilder(*command).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
    } catch (_: Exception) {
        false
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "init.defaultBranch=main") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        assertEquals(out, 0, p.waitFor())
    }

    @Before
    fun setUp() {
        assumeTrue("bash is needed to run the Termux helper", works("bash", "--version"))
        fixture = Files.createTempDirectory("termux-inventory").toFile().canonicalFile
        File(fixture, ".steward-termux-fixture").writeText("DISPOSABLE-FIXTURE\n")
        home.mkdirs()
        shared.mkdirs()
        File(prefix, "var/lib/dpkg/info").mkdirs()
        text(File(debian, "etc/os-release"), "debian")
        text(File(debian, "usr/lib/os-release"), "debian")
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.deleteRecursively()
    }

    private fun run(mode: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap()): String {
        val out = File(fixture, "result-$mode.tsv")
        out.delete()
        val pb = ProcessBuilder(listOf("bash") + TermuxScript.arguments(mode, out.path, args)).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.path)
            put("PREFIX", prefix.path)
            put("PATH", "${prefix.path}/bin:${System.getenv("PATH")}")
            put("STEWARD_TERMUX_FIXTURE", "1")
            put("STEWARD_FIXTURE_SHARED", shared.path)
            put("STEWARD_FIXTURE_MIN_KIB", "1024")
            putAll(env)
        }
        val p = pb.start()
        val stdout = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(180, TimeUnit.SECONDS))
        return if (out.isFile && out.length() > 0) out.readText() else stdout
    }

    @Test
    fun auditFindsLeftoversCopiesAndDates() {
        val then = 1_700_000_000_000L
        // Two decompiles of one app, and one inside a Git project (the work itself: never offered).
        for (dir in listOf("mx_jadx", "mx_jadx_bad")) {
            text(File(home, "$dir/resources/AndroidManifest.xml"), "<manifest/>")
            repeat(30) { write(File(home, "$dir/sources/p/C$it.java"), 40_000, it) }
        }
        text(File(home, "mx_apktool/apktool.yml"), "version: 2.9")
        write(File(home, "mx_apktool/smali/A.smali"), 2000)
        File(home, "work/re/.git").mkdirs()
        text(File(home, "work/re/decomp/apktool.yml"), "version: 2.9")
        // An installer in the home, one too deep, and npm run in the home itself.
        val layla = write(File(home, "layla.apk"), 3 * mib / 2, mtime = then)
        write(File(home, "a/b/deep.apk"), 3 * mib / 2, 5)
        text(File(home, "node_modules/left-pad/index.js"), "module.exports = 1")
        // Identical big files (and a hard link, which is no copy at all).
        val big = write(File(home, "downloads/model.bin"), 3 * mib / 2, 7)
        write(File(home, "models/model-copy.bin"), 3 * mib / 2, 7)
        Files.createLink(File(home, "downloads/model-link.bin").toPath(), big.toPath())
        // An NDK built for x86-64 PCs inside the distribution, and one built for ARM in the home.
        write(File(debian, "opt/android-sdk/ndk/27.2/toolchains/llvm/prebuilt/linux-x86_64/lib/liblldb.so"), 2 * mib)
        write(File(home, "android-ndk-r27/toolchains/llvm/prebuilt/linux-aarch64/bin/clang"), 2 * mib)
        // proot's hard-link store: one copy a live Git pack points at, one nothing points at.
        write(File(debian, ".l2s/.l2s.pack_DEF0001.0001"), 2 * mib, 1)
        File(debian, "root/repo/.git/objects/pack").mkdirs()
        Files.createSymbolicLink(File(debian, "root/repo/.git/objects/pack/pack-1.pack").toPath(), File("/.l2s/.l2s.pack_DEF0001").toPath())
        write(File(debian, ".l2s/.l2s.tmp_pack_ABC0001.0001"), 2 * mib, 2)
        // Claude Code run through a launcher script that names the version it runs.
        write(File(home, ".local/share/claude/versions/2.1.278"), 3 * mib / 2, 3, mtime = then)
        write(File(home, ".local/share/claude/versions/2.1.280"), 3 * mib / 2, 4, mtime = then + 86_400_000)
        text(File(home, ".local/bin/claude"), "#!/bin/sh\nexec grun ~/.local/share/claude/versions/2.1.280 \"$@\"\n")

        val report = TermuxProtocol.parseAudit(run("audit", env = mapOf("STEWARD_FIXTURE_ARCH" to "aarch64")))
        val items = report.items.groupBy { it.targetId }.mapValues { (_, list) -> list.map { it.path }.toSet() }
        assertEquals(setOf(File(home, "mx_jadx").path, File(home, "mx_jadx_bad").path, File(home, "mx_apktool").path), items["decompiled"])
        assertEquals(setOf(layla.path), items["home-apk"])
        assertEquals(setOf(File(home, "node_modules").path), items["home-node-modules"])
        assertEquals(setOf(File(debian, "opt/android-sdk/ndk/27.2").path), items["foreign-ndk"])
        assertEquals(setOf(File(debian, ".l2s/.l2s.tmp_pack_ABC0001.0001").path), items["l2s-orphan"])
        assertEquals(3L * mib / 2, report.items.single { it.targetId == "claude-versions" }.bytes)

        val copies = report.duplicates.single { big.path in it.paths }
        assertEquals(listOf(big.path, File(home, "models/model-copy.bin").path), copies.paths)
        assertEquals(3L * mib / 2, copies.extraBytes)

        assertEquals(then, report.entry(layla.path)!!.mtime)
        val sketched = report.sketches.map { it.path }.toSet()
        assertTrue(sketched.toString(), File(home, "mx_jadx").path in sketched && File(home, "mx_jadx_bad").path in sketched)
        val a = report.sketches.first { it.path == File(home, "mx_jadx").path }
        val b = report.sketches.first { it.path == File(home, "mx_jadx_bad").path }
        assertEquals(1.0, com.galaxy.steward.core.dedupe.FolderSketch.similarity(a.hashes, b.hashes), 0.0)

        // On a phone without an ARM CPU (or with box64 in the distribution) the NDK is in use.
        assertNull(TermuxProtocol.parseAudit(run("audit", env = mapOf("STEWARD_FIXTURE_ARCH" to "x86_64"))).items.firstOrNull { it.targetId == "foreign-ndk" })

        // A launcher naming the older version keeps both.
        text(File(home, ".local/bin/claude"), "#!/bin/sh\nexec grun ~/.local/share/claude/versions/2.1.278 \"$@\"\n")
        assertNull(TermuxProtocol.parseAudit(run("audit")).items.firstOrNull { it.targetId == "claude-versions" })
    }

    @Test
    fun theBrowserKnowsWhichFoldersPackagesOwnAndTotalsFollowDeletions() {
        // chromium's files, a folder you put in $PREFIX yourself, and dpkg's list of what chromium installed.
        write(File(prefix, "lib/chromium/chrome"), 3 * mib / 2)
        write(File(prefix, "lib/chromium/locales/en.pak"), 3 * mib / 2, 1)
        write(File(prefix, "opt/mytool/tool.bin"), 3 * mib / 2, 2)
        text(
            File(prefix, "var/lib/dpkg/info/chromium.list"),
            listOf("lib", "lib/chromium", "lib/chromium/chrome", "lib/chromium/locales", "lib/chromium/locales/en.pak")
                .joinToString("\n", postfix = "\n") { "${prefix.path}/$it" },
        )
        text(File(prefix, "var/lib/dpkg/info/libc.list"), "${prefix.path}/lib\n${prefix.path}/lib/libc.so\n")
        write(File(home, "big/data.bin"), 3 * mib, 3)
        val report = TermuxProtocol.parseAudit(run("audit"))
        fun lock(f: File) = com.galaxy.steward.core.termux.TermuxLocks.reason(f.path, report)
        assertEquals("Installed by chromium: uninstall it under Packages", lock(File(prefix, "lib/chromium")))
        assertEquals("Installed by chromium: uninstall it under Packages", lock(File(prefix, "lib/chromium/chrome")))
        assertEquals("Files of 2 packages: uninstall them under Packages", lock(File(prefix, "lib")))
        assertNull(lock(File(prefix, "opt/mytool")))

        // What the app shows after deleting ~/big: the folder is gone and every total above it shrinks.
        val before = report.totalBytes
        val homeBefore = report.usage.first { it.path == home.path }.bytes
        val after = report.afterDeleting(mapOf(File(home, "big").path to 3L * mib))
        assertEquals(before - 3L * mib, after.totalBytes)
        assertEquals(homeBefore - 3L * mib, after.usage.first { it.path == home.path }.bytes)
        assertNull(after.entry(File(home, "big/data.bin").path))
    }

    @Test
    fun keystoresAnAppShipsDontLockItsDecompiledFolderButYoursDo() {
        text(File(home, "mx_jadx/resources/AndroidManifest.xml"), "<manifest/>")
        write(File(home, "mx_jadx/sources/p/A.java"), 2000)
        write(File(home, "mx_jadx/resources/assets/bundled-client.p12"), 300) // came out of the APK
        text(File(home, "mine_apktool/apktool.yml"), "version: 2.9")
        write(File(home, "mine_apktool/smali/A.smali"), 2000)
        write(File(home, "mine_apktool/release.jks"), 300) // yours, next to the decompiled app
        val items = TermuxProtocol.parseAudit(run("audit")).items.filter { it.targetId == "decompiled" }.map { it.path }
        assertEquals(listOf(File(home, "mx_jadx").path), items)
        val results = TermuxProtocol.parseClean(run("delete", listOf(File(home, "mx_jadx").path, File(home, "mine_apktool").path))).results
        assertEquals(listOf("CLEARED", "SKIP_KEYS"), results.map { it.status })
        assertEquals("holds release.jks", results.last().note)
    }

    @Test
    fun olderClaudeCodeVersionsAreOfferedForReviewWhenNothingSaysWhichRuns() {
        write(File(home, ".local/share/claude/versions/2.1.278"), 3 * mib / 2, 3).setLastModified(1_700_000_000_000L)
        write(File(home, ".local/share/claude/versions/2.1.280"), 3 * mib / 2, 4).setLastModified(1_700_086_400_000L)
        val items = TermuxProtocol.parseAudit(run("audit")).items.filter { it.targetId.startsWith("claude-versions") }
        assertEquals(listOf("claude-versions-unsure"), items.map { it.targetId })
        assertFalse(items.single().defaultSelected)
        assertEquals(3L * mib / 2, items.single().bytes)
        val done = TermuxProtocol.parseClean(run("clean", listOf("claude-versions-unsure"))).results.single()
        assertEquals("CLEARED", done.status)
        assertFalse(File(home, ".local/share/claude/versions/2.1.278").exists())
        assertTrue(File(home, ".local/share/claude/versions/2.1.280").exists())
    }

    @Test
    fun leftoversAreCheckedAgainBeforeTheyGo() {
        text(File(home, "mx_jadx_bad/resources/AndroidManifest.xml"), "<manifest/>")
        write(File(home, "mx_jadx_bad/sources/A.java"), 1000)
        File(home, "work/re/.git").mkdirs()
        text(File(home, "work/re/decomp/apktool.yml"), "version: 2.9")
        write(File(home, "layla.apk"), 2000)
        write(File(debian, "opt/ndk/27.2/toolchains/llvm/prebuilt/linux-x86_64/lib/liblldb.so"), 3000)
        write(File(debian, ".l2s/.l2s.tmp_pack_ABC0001.0001"), 3000)
        write(File(debian, ".l2s/.l2s.pack_DEF0001.0001"), 3000)
        File(debian, "root/repo").mkdirs()
        Files.createSymbolicLink(File(debian, "root/repo/pack-1.pack").toPath(), File("/.l2s/.l2s.pack_DEF0001").toPath())
        write(File(home, "notes.txt"), 10)

        val specs = listOf(
            "decompiled=${File(home, "mx_jadx_bad").path}",
            "decompiled=${File(home, "work/re/decomp").path}",
            "decompiled=${File(home, "notes.txt").path}",
            "home-apk=${File(home, "layla.apk").path}",
            "home-apk=${File(home, "notes.txt").path}",
            "foreign-ndk=${File(debian, "opt/ndk/27.2").path}",
            "l2s-orphan=${File(debian, ".l2s/.l2s.tmp_pack_ABC0001.0001").path}",
            "l2s-orphan=${File(debian, ".l2s/.l2s.pack_DEF0001.0001").path}",
        )
        val results = TermuxProtocol.parseClean(run("clean", specs, mapOf("STEWARD_FIXTURE_ARCH" to "aarch64"))).results
        assertEquals(
            listOf("CLEARED", "SKIP_UNSAFE", "SKIP_UNSAFE", "CLEARED", "SKIP_UNSAFE", "CLEARED", "CLEARED", "SKIP_UNSAFE"),
            results.map { it.status },
        )
        assertEquals("a file in the distribution still points at it", results.last().note)
        assertFalse(File(home, "mx_jadx_bad").exists())
        assertTrue(File(home, "work/re/decomp/apktool.yml").exists())
        assertTrue(File(home, "notes.txt").exists())
        assertFalse(File(debian, "opt/ndk/27.2").exists())
        assertTrue(File(debian, ".l2s/.l2s.pack_DEF0001.0001").exists())
    }

    private fun fakeDpkg() {
        val db = text(
            File(prefix, "var/lib/dpkg/fake-status.tsv"),
            listOf(
                "git\t40000\tinstall ok installed\t\toptional\t2.46\t\t\tVersion control",
                "python\t100000\tinstall ok installed\t\toptional\t3.12\t\t\tPython",
                "bash\t5000\tinstall ok installed\tyes\trequired\t5.2\t\t\tShell",
            ).joinToString("\n", postfix = "\n"),
        )
        tool("dpkg-query", "tr '\t' '\\037' < \"${db.path}\"")
        tool("apt-mark", "printf 'git\\npython\\n'")
        val sitePackages = File(prefix, "lib/python3.12/site-packages")
        text(File(prefix, "var/lib/dpkg/info/git.list"), "${prefix.path}/bin/git\n${prefix.path}/bin/git-upload-pack\n${prefix.path}/share/doc/git\n")
            .setLastModified(1_690_000_000_000L)
        text(File(prefix, "var/lib/dpkg/info/python.list"), "${prefix.path}/bin/python3\n${File(sitePackages, "pip-24.0.dist-info").path}\n")
        text(File(prefix, "var/lib/dpkg/info/bash.list"), "${prefix.path}/bin/bash\n")
        // pip itself came with the python package: dpkg's, not a program pip installed.
        text(File(sitePackages, "pip-24.0.dist-info/METADATA"), "Name: pip\nVersion: 24.0\n")
        text(File(sitePackages, "pip-24.0.dist-info/RECORD"), "pip/__init__.py,sha256=x,100\n")
        text(File(sitePackages, "requests-2.32.0.dist-info/METADATA"), "Metadata-Version: 2.1\nName: requests\nVersion: 2.32.0\n")
        text(
            File(sitePackages, "requests-2.32.0.dist-info/RECORD"),
            "requests/__init__.py,sha256=a,5000\nrequests/api.py,sha256=b,7000\n../../../bin/normalizer,sha256=c,300\n" +
                "requests-2.32.0.dist-info/RECORD,,\n",
        )
        // A global npm package with a command, and npm itself.
        text(File(prefix, "lib/node_modules/@mmmbuto/codex-cli-termux/package.json"), "{\n  \"name\": \"@mmmbuto/codex-cli-termux\",\n  \"version\": \"0.39.0\"\n}\n")
        write(File(prefix, "lib/node_modules/@mmmbuto/codex-cli-termux/bin/codex.js"), 4000)
        Files.createSymbolicLink(File(prefix, "bin/codex").toPath(), File("../lib/node_modules/@mmmbuto/codex-cli-termux/bin/codex.js").toPath())
        text(File(prefix, "lib/node_modules/npm/package.json"), "{\"name\": \"npm\", \"version\": \"10.8.0\"}")
        // cargo install ripgrep.
        text(File(home, ".cargo/.crates.toml"), "[v1]\n\"ripgrep 14.1.0 (registry+https://github.com/rust-lang/crates.io-index)\" = [\"rg\"]\n")
        write(File(home, ".cargo/bin/rg"), 1000)
        // Shell history in all three formats.
        text(File(home, ".bash_history"), "#1700000000\ngit status\n#1700000500\nsudo git log | less\nls -la\n")
        text(File(home, ".zsh_history"), ": 1700001000:0;python3 -m http.server\n")
        text(File(home, ".local/share/fish/fish_history"), "- cmd: FOO=1 python3 x.py\n  when: 1700002000\n- cmd: rg TODO\n  when: 1700003000\n")
    }

    @Test
    fun packagesAndProgramsKnowWhenTheyWereInstalledAndLastRun() {
        fakeDpkg()
        val output = run("packages")
        val packages = TermuxProtocol.parsePackages(output).associateBy { it.name }
        val git = packages.getValue("git")
        assertEquals(1_690_000_000_000L, git.installed)
        assertEquals(1_700_000_500_000L, git.lastUsed)
        assertEquals(2, git.uses)
        assertEquals(listOf("git", "git-upload-pack"), git.commands)
        assertEquals(1_700_002_000_000L, packages.getValue("python").lastUsed)
        assertEquals(2, packages.getValue("python").uses)
        assertEquals(0L, packages.getValue("bash").lastUsed)

        val programs = TermuxProtocol.parsePrograms(output).associateBy { it.key }
        assertEquals(setOf("npm:@mmmbuto/codex-cli-termux", "npm:npm", "pip:requests", "cargo:ripgrep"), programs.keys)
        val codex = programs.getValue("npm:@mmmbuto/codex-cli-termux")
        assertEquals("0.39.0", codex.version)
        assertEquals(listOf("codex"), codex.commands)
        assertFalse(codex.protected)
        assertTrue(programs.getValue("npm:npm").protected)
        val requests = programs.getValue("pip:requests")
        assertEquals(12_300L, requests.bytes)
        assertEquals(listOf("normalizer"), requests.commands)
        val rg = programs.getValue("cargo:ripgrep")
        assertEquals(1000L, rg.bytes)
        assertEquals(1_700_003_000_000L, rg.lastUsed)
    }

    @Test
    fun programsGoThroughTheirOwnPackageManager() {
        fakeDpkg()
        tool("npm", "[ \"\$1\" = rm ] && [ \"\$2\" = -g ] && rm -rf \"\$PREFIX/lib/node_modules/\$4\"")
        tool(
            "python3",
            "if [ \"\$1 \$2 \$3\" = '-m pip uninstall' ]; then rm -rf \"\$PREFIX\"/lib/python3.12/site-packages/\"\${@: -1}\"-*.dist-info; fi",
        )
        val npm = TermuxProtocol.parseClean(run("prog-remove", listOf("npm", "@mmmbuto/codex-cli-termux", "npm"))).results
        assertEquals(listOf("REMOVED", "SKIP_PROTECTED"), npm.map { it.status })
        assertFalse(File(prefix, "lib/node_modules/@mmmbuto/codex-cli-termux").exists())
        assertTrue(File(prefix, "lib/node_modules/npm/package.json").exists())

        val pip = TermuxProtocol.parseClean(run("prog-remove", listOf("pip", "requests", "pip"))).results
        assertEquals(listOf("REMOVED", "SKIP_PROTECTED"), pip.map { it.status })
        assertEquals(12_300L, pip.first().freed)
        assertTrue(File(prefix, "lib/python3.12/site-packages/pip-24.0.dist-info").exists())

        try {
            TermuxProtocol.parseClean(run("prog-remove", listOf("npm", "../../etc")))
            throw AssertionError("a forged name must be refused")
        } catch (e: TermuxException) {
            assertEquals("not an npm package name: ../../etc", e.message)
        }
    }

    @Test
    fun repositoriesSayWhenTheyWereUsedAndPackLosslessly() {
        assumeTrue("git is needed", works("git", "--version"))
        val app = File(home, "code/app").apply { mkdirs() }
        git(app, "init", "-q")
        repeat(150) { text(File(app, "src/F$it.kt"), "val x$it = \"${"y".repeat(it * 20)}\"\n") }
        git(app, "add", "-A")
        git(app, "commit", "-q", "-m", "first")
        git(app, "remote", "add", "origin", "https://me:ghp_secret@github.com/me/app.git")
        val lib = File(shared, "Download/Projects/lib").apply { mkdirs() }
        git(lib, "init", "-q")
        text(File(lib, "README.md"), "lib")
        git(lib, "add", "-A")
        git(lib, "commit", "-q", "-m", "first")

        val repos = TermuxProtocol.parseRepos(run("repos")).associateBy { it.path }
        val r = repos.getValue(app.path)
        assertEquals("https://github.com/me/app.git", r.remote)
        assertEquals("github.com/me/app", r.remoteLabel)
        assertEquals("main", r.branch)
        assertEquals(false, r.dirty)
        assertNull(r.unpushed) // no upstream yet
        assertTrue(r.lastCommit > 0 && r.looseBytes > 0)
        assertTrue(r.bytes > r.gitBytes)
        val s = repos.getValue(lib.path)
        assertEquals(-1L, s.bytes) // in shared storage: the scan knows its size
        assertNull(s.dirty)

        val gc = TermuxProtocol.parseClean(run("git-gc", listOf(app.path, File(home, "code").path))).results
        assertEquals(listOf("CLEARED", "SKIP_UNSAFE"), gc.map { it.status })
        assertTrue(gc.first().freed > 0)
        val after = TermuxProtocol.parseRepos(run("repos")).first { it.path == app.path }
        assertEquals(0L, after.looseBytes)
        git(app, "fsck", "--no-progress")
    }

    @Test
    fun aProjectMovesIntoTermuxOnlyAsAVerifiedCopyAndBackAgain() {
        val project = File(shared, "Download/Projects/tool")
        repeat(40) { write(File(project, "src/dir${it % 4}/F$it.txt"), 500 + it, it) }
        File(project, "empty").mkdirs()
        val moved = TermuxProtocol.parseClean(run("relocate", listOf(project.path))).results.single()
        assertEquals("MOVED", moved.status)
        val inTermux = File(home, "projects/tool")
        assertEquals(inTermux.path, moved.note)
        assertEquals(0L, moved.freed) // shared storage and Termux share one partition: moving frees nothing
        assertFalse(project.exists())
        assertEquals(40, inTermux.walkTopDown().count { it.isFile })
        assertEquals(510L, File(inTermux, "src/dir2/F10.txt").length())
        assertTrue(File(inTermux, "empty").isDirectory)
        assertFalse(File(home, "projects/.steward-partial-tool").exists())

        write(File(shared, "Download/Projects/tool/again.txt"), 10)
        assertEquals("SKIP_EXISTS", TermuxProtocol.parseClean(run("relocate", listOf(File(shared, "Download/Projects/tool").path))).results.single().status)
        File(shared, "Download/Projects/tool").deleteRecursively()

        val back = TermuxProtocol.parseClean(run("relocate-back", listOf(inTermux.path, project.path))).results.single()
        assertEquals("MOVED", back.status)
        assertEquals(40, project.walkTopDown().count { it.isFile })
        assertFalse(inTermux.exists())

        for (bad in listOf(File(shared, "Download"), File(shared, "Android/data/x"), File(home, "projects"))) {
            bad.mkdirs()
            assertEquals(bad.path, "SKIP_UNSAFE", TermuxProtocol.parseClean(run("relocate", listOf(bad.path))).results.single().status)
        }
    }
}
