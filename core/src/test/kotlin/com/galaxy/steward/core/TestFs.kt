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
