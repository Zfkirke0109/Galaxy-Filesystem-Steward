package com.galaxy.steward.core

import com.galaxy.steward.core.appdata.AppArea
import com.galaxy.steward.core.appdata.AppDataExecutor
import com.galaxy.steward.core.appdata.AppJunkItem
import com.galaxy.steward.core.appdata.AppJunkKind
import com.galaxy.steward.core.appdata.AppTarget
import com.galaxy.steward.core.exec.JournalBroken
import com.galaxy.steward.core.exec.JournalEntry
import com.galaxy.steward.core.exec.JournalSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** An app-folder run whose journal can't be written (the phone is full) stops, with what it just moved put back. */
class AppFolderJournalTest {
    @Test
    fun anAppFolderRunStopsWhenItsJournalCantBeWritten() = runTest {
        TestFs().use { fs ->
            val first = fs.random("Android/obb/com.game/main.1.com.game.obb", 4000, 31)
            val second = fs.random("Android/obb/com.game/main.2.com.game.obb", 4000, 32)
            val full = object : JournalSink {
                override fun meta(key: String, value: String) = Unit
                override fun entry(entry: JournalEntry) = throw IOException("No space left on device")
            }
            val items = listOf(first, second).mapIndexed { i, f ->
                val target = AppTarget(f.path, false, f.length(), f.lastModified())
                AppJunkItem("app:obb:$i", AppJunkKind.OBSOLETE_OBB, "com.game", AppArea.OBB, listOf(target), f.length(), 1, Long.MAX_VALUE, "test")
            }

            val error = runCatching { AppDataExecutor(fs.rootPath, full, "run-1", null, emptySet()).execute("App folders", items) }.exceptionOrNull()

            assertTrue("$error", error is JournalBroken)
            // The first went back where it was; the second was never touched.
            assertTrue(first.exists() && second.exists())
        }
    }
}
