package com.galaxy.steward.core

import com.galaxy.steward.core.termux.TermuxProtocol
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
 * Package removal, whole distributions and chosen folders, run by the real termux-steward.sh against a disposable fake
 * Termux. apt-get, dpkg-query, apt-mark and proot-distro are small stand-ins working on a fixture database, so nothing
 * here can reach the machine's own package manager.
 */
class TermuxManageTest {
    private lateinit var fixture: File
    private val home get() = File(fixture, "files/home")
    private val prefix get() = File(fixture, "files/usr")
    private val distros get() = File(prefix, "var/lib/proot-distro/containers")
    private val db get() = File(prefix, "var/lib/dpkg/fake-status.tsv")

    private fun write(f: File, bytes: Int): File {
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        return f
    }

    private fun tool(name: String, body: String) {
        val f = File(prefix, "bin/$name")
        f.parentFile.mkdirs()
        f.writeText("#!/bin/bash\n$body\n")
        f.setExecutable(true)
    }

    private fun haveBash() = try {
        ProcessBuilder("bash", "--version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
    } catch (_: Exception) {
        false
    }

    @Before
    fun setUp() {
        assumeTrue("bash is needed to run the Termux helper", haveBash())
        fixture = Files.createTempDirectory("termux-manage").toFile().canonicalFile
        File(fixture, ".steward-termux-fixture").writeText("DISPOSABLE-FIXTURE\n")
        home.mkdirs()
        // name, KiB, status, essential, priority, version, depends, pre-depends, summary
        db.parentFile.mkdirs()
        db.writeText(
            listOf(
                "bash\t5000\tinstall ok installed\tyes\trequired\t5.2\t\t\tGNU Bourne Again shell",
                "coreutils\t3000\tinstall ok installed\t\toptional\t9.4\t\t\tBasic file utilities",
                "chromium\t800000\tinstall ok installed\t\toptional\t120\tlibx11, nss, chromium-data\t\tWeb browser",
                "chromium-data\t50000\tinstall ok installed\t\toptional\t120\t\t\tChromium resources",
                "chromium-widevine\t2000\tinstall ok installed\t\toptional\t1\tchromium\t\tDRM plugin",
                "firefox\t600000\tinstall ok installed\t\toptional\t130\tlibx11, nss\t\tAnother web browser",
                "libx11\t3000\tinstall ok installed\t\toptional\t1.8\t\t\tX11 client library",
                "nss\t2000\tinstall ok installed\t\toptional\t3.9\t\t\tNetwork security",
                "python\t100000\tinstall ok installed\t\toptional\t3.12\t\t\tPython",
                "needs-bash\t10\tinstall ok installed\t\toptional\t1\tcoreutils\t\tDepends on a protected package",
                "old\t10\tdeinstall ok config-files\t\toptional\t1\t\t\tRemoved, config left",
            ).joinToString("\n", postfix = "\n"),
        )
        File(prefix, "var/lib/dpkg/fake-manual").writeText("bash\nchromium\nchromium-widevine\nfirefox\npython\nneeds-bash\n")
        tool(
            "dpkg-query",
            """
            db="${'$'}PREFIX/var/lib/dpkg/fake-status.tsv"
            name="${'$'}{@: -1}"
            if [ "${'$'}name" = "${'$'}{name#-}" ] && [ "${'$'}#" -ge 3 ]; then
              awk -F'\t' -v p="${'$'}name" '${'$'}1 == p { print ${'$'}3; f = 1 } END { exit !f }' "${'$'}db"
            else
              tr '	' '' < "${'$'}db"
            fi
            """.trimIndent(),
        )
        tool("apt-mark", "cat \"\$PREFIX/var/lib/dpkg/fake-manual\"")
        // apt's behaviour, simplified: removing a package removes everything installed that depends on it, and
        // --autoremove also takes automatically installed packages nothing depends on any more.
        tool(
            "apt-get",
            """
            db="${'$'}PREFIX/var/lib/dpkg/fake-status.tsv"; manual="${'$'}PREFIX/var/lib/dpkg/fake-manual"
            sim=0; auto=0; names=()
            for a in "${'$'}@"; do case "${'$'}a" in -s) sim=1 ;; -y|-q|remove) ;; --autoremove) auto=1 ;; *) names+=("${'$'}a") ;; esac; done
            installed() { awk -F'\t' '${'$'}3 ~ / installed${'$'}/ { print ${'$'}1 }' "${'$'}db"; }
            deps() { awk -F'\t' -v p="${'$'}1" '${'$'}1 == p { print ${'$'}7 }' "${'$'}db" | tr ',' '\n' | sed 's/(.*//; s/ //g' | grep -v '^${'$'}'; }
            declare -A gone=()
            for n in "${'$'}{names[@]}"; do
              installed | grep -qx "${'$'}n" || { echo "E: Unable to locate package ${'$'}n" >&2; exit 100; }
              gone[${'$'}n]=1
            done
            changed=1
            while [ ${'$'}changed = 1 ]; do
              changed=0
              for q in ${'$'}(installed); do
                [ -n "${'$'}{gone[${'$'}q]:-}" ] && continue
                for d in ${'$'}(deps "${'$'}q"); do [ -n "${'$'}{gone[${'$'}d]:-}" ] && { gone[${'$'}q]=1; changed=1; break; }; done
              done
              if [ ${'$'}auto = 1 ]; then
                for q in ${'$'}(installed); do
                  [ -n "${'$'}{gone[${'$'}q]:-}" ] && continue
                  grep -qx "${'$'}q" "${'$'}manual" && continue
                  needed=0
                  for r in ${'$'}(installed); do
                    [ -n "${'$'}{gone[${'$'}r]:-}" ] && continue
                    deps "${'$'}r" | grep -qx "${'$'}q" && { needed=1; break; }
                  done
                  [ ${'$'}needed = 0 ] && { gone[${'$'}q]=1; changed=1; }
                done
              fi
            done
            if [ ${'$'}sim = 1 ]; then for q in "${'$'}{!gone[@]}"; do echo "Remv ${'$'}q [1]"; done; exit 0; fi
            for q in "${'$'}{!gone[@]}"; do awk -F'\t' -v p="${'$'}q" '${'$'}1 != p' "${'$'}db" > "${'$'}db.tmp" && mv "${'$'}db.tmp" "${'$'}db"; done
            """.trimIndent(),
        )
        tool("proot-distro", "[ \"\$1\" = remove ] && rm -rf \"\$PREFIX/var/lib/proot-distro/containers/\$2\"")
        // Two distributions, and files packages own.
        for (d in listOf("debian", "proroot")) {
            write(File(distros, "$d/rootfs/etc/os-release"), 10)
            write(File(distros, "$d/rootfs/usr/lib/libc.so"), 3000)
        }
        write(File(distros, "debian/rootfs/opt/android-sdk/ndk/linux-x86_64/liblldb.so"), 4000)
        File(prefix, "var/lib/dpkg/info").mkdirs()
        write(File(prefix, "lib/chromium/chrome"), 5000)
        File(prefix, "var/lib/dpkg/info/chromium.list").writeText("${prefix.path}/lib/chromium\n${prefix.path}/lib/chromium/chrome\n")
        write(File(prefix, "opt/flutter/bin/flutter"), 6000) // put there by hand: no package owns it
        write(File(home, "mx_jadx_bad/out/classes.txt"), 7000)
        write(File(home, "mx_jadx_bad/out/res/raw/cacert.pem"), 100) // a certificate inside a decompiled app is not your key
        write(File(home, ".termux/termux.properties"), 20)
        write(File(home, "keys/release.jks"), 30)
        write(File(home, "project/notes.txt"), 40)
        File(home, "storage").mkdirs()
        Files.createSymbolicLink(File(home, "storage/shared").toPath(), fixture.toPath())
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.deleteRecursively()
    }

    private fun run(mode: String, args: List<String> = emptyList()): String {
        val out = File(fixture, "result-$mode.tsv")
        val pb = ProcessBuilder(listOf("bash") + TermuxScript.arguments(mode, out.path, args)).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", home.path)
            put("PREFIX", prefix.path)
            put("STEWARD_TERMUX_FIXTURE", "1")
        }
        val p = pb.start()
        val stdout = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        return if (out.isFile && out.length() > 0) out.readText() else stdout
    }

    @Test
    fun packagesComeWithSizesOriginAndProtection() {
        val packages = TermuxProtocol.parsePackages(run("packages")).associateBy { it.name }
        assertEquals(setOf("bash", "coreutils", "chromium", "chromium-data", "chromium-widevine", "firefox", "libx11", "nss", "python", "needs-bash"), packages.keys)
        assertEquals(800_000L * 1024, packages.getValue("chromium").bytes)
        assertTrue(packages.getValue("chromium").manual)
        assertFalse(packages.getValue("libx11").manual) // pulled in as a dependency
        assertTrue(packages.getValue("bash").protected) // essential
        assertTrue(packages.getValue("coreutils").protected) // Termux and the steward need it
        assertEquals(setOf("libx11", "nss", "chromium-data"), packages.getValue("chromium").depends)
        assertEquals("Web browser", packages.getValue("chromium").summary)
    }

    @Test
    fun planShowsEverythingAptWouldRemoveAndWhy() {
        val plan = TermuxProtocol.parsePlan(run("pkg-plan", listOf("chromium")))
        val kinds = plan.packages.associate { it.name to it.kind }
        assertEquals("requested", kinds["chromium"])
        assertEquals("dependent", kinds["chromium-widevine"]) // needs chromium, so it goes too
        assertEquals("orphan", kinds["chromium-data"]) // nothing needs it once chromium is gone (only with autoremove)
        assertFalse("firefox still needs these", "libx11" in kinds || "nss" in kinds)
        assertFalse(plan.blocked)
        assertTrue(TermuxProtocol.parsePlan(run("pkg-plan", listOf("coreutils"))).blocked) // takes needs-bash and coreutils
        assertEquals(listOf("apt refused: E: Unable to locate package nosuch"), TermuxProtocol.parsePlan(run("pkg-plan", listOf("nosuch"))).warnings)
    }

    @Test
    fun removalGoesThroughAptAndNeverTakesProtectedPackages() {
        val refused = TermuxProtocol.parseClean(run("pkg-remove", listOf("needs-bash", "coreutils")))
        assertEquals(setOf("SKIP_PROTECTED"), refused.results.map { it.status }.toSet())
        assertTrue(db.readText().contains("coreutils\t"))
        assertTrue(db.readText().contains("needs-bash\t"))

        val removed = TermuxProtocol.parseClean(run("pkg-remove", listOf("--autoremove", "chromium")))
        assertEquals(setOf("chromium", "chromium-widevine", "chromium-data"), removed.results.filter { it.status == "REMOVED" }.map { it.targetId }.toSet())
        assertEquals((800_000L + 2000 + 50_000) * 1024, removed.freed)
        assertFalse(db.readText().contains("chromium"))
        assertTrue(db.readText().contains("firefox\t"))
        assertTrue(db.readText().contains("libx11\t"))
    }

    @Test
    fun aWholeDistributionCanGo() {
        val proroot = File(distros, "proroot/rootfs").path
        val result = TermuxProtocol.parseClean(run("distro-remove", listOf(proroot))).results.single()
        assertEquals("CLEARED", result.status)
        assertTrue(result.freed > 0)
        assertFalse(File(distros, "proroot").exists())
        assertTrue(File(distros, "debian/rootfs/usr/lib/libc.so").exists())
        val forged = TermuxProtocol.parseClean(run("distro-remove", listOf(home.path))).results.single()
        assertEquals("SKIP_UNSAFE", forged.status)
        assertTrue(home.isDirectory)
    }

    @Test
    fun chosenFoldersAreDeletedOnlyWhereThatIsSafe() {
        val debian = File(distros, "debian/rootfs")
        val paths = listOf(
            File(home, "mx_jadx_bad"), // yours
            File(prefix, "opt/flutter"), // no package owns it
            File(debian, "opt/android-sdk"), // add-on inside a distribution
            File(debian, "usr/lib"), // the distribution's own system
            File(prefix, "lib/chromium"), // a package's files
            File(home, ".termux"), // Termux's settings
            File(home, "keys"), // holds a key
            File(home, "storage/shared"), // a link to shared storage
            File(prefix, "var/lib/proot-distro/containers"), // every distribution at once
            home, // the home itself
        ).map { it.path }
        val results = TermuxProtocol.parseClean(run("delete", paths)).results.associateBy { it.path.ifEmpty { "?" } }
        fun status(f: File) = results[f.path]?.status
        assertEquals("CLEARED", status(File(home, "mx_jadx_bad")))
        assertEquals("CLEARED", status(File(prefix, "opt/flutter")))
        assertEquals("CLEARED", status(File(debian, "opt/android-sdk")))
        assertEquals("SKIP_UNSAFE", status(File(debian, "usr/lib")))
        assertEquals("SKIP_PACKAGE", status(File(prefix, "lib/chromium")))
        assertEquals("chromium", results.getValue(File(prefix, "lib/chromium").path).note)
        assertEquals("SKIP_UNSAFE", status(File(home, ".termux")))
        assertEquals("SKIP_KEYS", status(File(home, "keys")))
        assertEquals("holds release.jks", results.getValue(File(home, "keys").path).note)
        assertEquals("SKIP_UNSAFE", status(File(home, "storage/shared")))
        assertEquals("SKIP_UNSAFE", status(File(prefix, "var/lib/proot-distro/containers")))
        assertEquals("SKIP_UNSAFE", status(home))

        assertFalse(File(home, "mx_jadx_bad").exists())
        assertFalse(File(debian, "opt/android-sdk").exists())
        assertTrue(File(debian, "usr/lib/libc.so").exists())
        assertTrue(File(prefix, "lib/chromium/chrome").exists())
        assertTrue(File(home, ".termux/termux.properties").exists())
        assertTrue(File(home, "keys/release.jks").exists())
        assertTrue(File(home, "project/notes.txt").exists())
        assertTrue(File(distros, "proroot/rootfs/etc/os-release").exists())
    }

    @Test
    fun theBrowserLocksWhatTheScriptWouldRefuse() {
        val report = TermuxProtocol.parseAudit(run("audit"))
        val debian = File(distros, "debian/rootfs").path
        fun lock(path: String) = com.galaxy.steward.core.termux.TermuxLocks.reason(path, report)
        assertEquals(null, lock(File(home, "mx_jadx_bad").path))
        assertEquals(null, lock("$debian/opt/android-sdk"))
        assertEquals(null, lock(File(prefix, "opt/flutter").path)) // dpkg decides, inside Termux
        assertEquals("A Linux distribution: remove it on the Termux screen", lock(debian))
        assertEquals("Part of the distribution's system", lock("$debian/usr/lib"))
        assertEquals("Package files: uninstall packages instead", lock(File(prefix, "lib").path))
        assertEquals("Links to shared storage", lock(File(home, "storage/shared").path))
        assertEquals("Termux or your keys need it", lock(File(home, ".termux").path))
        assertEquals("Termux itself", lock(home.path))
    }

    @Test
    fun auditMapsEverythingOfAMegabyteAndMore() {
        write(File(home, "models/small.bin"), 1000)
        val big = File(home, "models/model.bin")
        big.parentFile.mkdirs()
        big.outputStream().use { out -> repeat(2) { out.write(ByteArray(1 shl 20) { (it % 13).toByte() }) } }
        val report = TermuxProtocol.parseAudit(run("audit"))
        val entries = report.entries.associateBy { it.path }
        assertEquals(false, entries.getValue(big.path).isDirectory)
        assertTrue(entries.getValue(big.path).bytes >= 2L shl 20)
        assertTrue(entries.getValue(File(home, "models").path).isDirectory)
        assertFalse(File(home, "models/small.bin").path in entries)
        assertEquals(listOf(big.path), report.children(File(home, "models").path).map { it.path })
    }
}
