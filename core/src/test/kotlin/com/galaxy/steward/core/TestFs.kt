package com.galaxy.steward.core

import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Tiny DSL for building throwaway storage trees in tests. */
class TestFs : AutoCloseable {
    val root: File = Files.createTempDirectory("steward-test").toFile().canonicalFile
    val rootPath: String get() = root.path
    val stateDir: File = Files.createTempDirectory("steward-state").toFile()

    private val oldTime = System.currentTimeMillis() - 60 * DAY_MS

    fun file(rel: String, content: ByteArray, mtime: Long = oldTime): File {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeBytes(content)
        f.setLastModified(mtime)
        return f
    }

    fun text(rel: String, content: String, mtime: Long = oldTime) = file(rel, content.toByteArray(), mtime)

    fun random(rel: String, size: Int, seed: Int, mtime: Long = oldTime) = file(rel, Random(seed).nextBytes(size), mtime)

    fun dir(rel: String, mtime: Long = oldTime): File = File(root, rel).also {
        it.mkdirs()
        it.setLastModified(mtime)
    }

    fun path(rel: String) = File(root, rel).path

    fun exists(rel: String) = File(root, rel).exists()

    /** Directory mtimes change as children are created; age them all at the end of setup. */
    fun ageDirectories() {
        root.walkBottomUp().filter { it.isDirectory }.forEach { it.setLastModified(oldTime) }
    }

    override fun close() {
        root.deleteRecursively()
        stateDir.deleteRecursively()
    }
}

object TestEnv : DeviceEnvironment {
    override val deviceLabel: String = "Test-Phone"
}

val testSettings = StewardSettings(minDuplicateBytes = 1, minDuplicateFolderBytes = 1, hashWorkers = 2)

/**
 * A tool a test needs (bash, git). On CI (CI=true, as GitHub Actions sets it) a missing tool fails the build: a runner
 * image without git would otherwise skip every test of the Termux script, which deletes in Termux's home, and stay green.
 * Elsewhere the test is skipped.
 */
fun requireTool(why: String, available: Boolean) {
    if (System.getenv("CI") == "true") org.junit.Assert.assertTrue("$why (missing on CI)", available) else org.junit.Assume.assumeTrue(why, available)
}

/**
 * Runs [pb] with its output going to a file, and stops it after [seconds]: reading its output first and waiting after,
 * a hung script blocked the build until the CI job's own timeout. Returns whether it finished, and what it printed.
 */
fun runBounded(pb: ProcessBuilder, seconds: Long): Pair<Boolean, String> {
    val log = java.io.File.createTempFile("steward-proc", ".out")
    try {
        val p = pb.redirectErrorStream(true).redirectOutput(log).start()
        val done = p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) p.destroyForcibly().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        return done to log.readText()
    } finally {
        log.delete()
    }
}
