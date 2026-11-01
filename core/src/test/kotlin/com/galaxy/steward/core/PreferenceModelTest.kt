package com.galaxy.steward.core

import com.galaxy.steward.core.learn.DecisionLog
import com.galaxy.steward.core.learn.PreferenceModel
import com.galaxy.steward.core.plan.FolderMerge
import com.galaxy.steward.core.plan.JunkCategory
import com.galaxy.steward.core.plan.JunkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Ticks learned from your own decisions. */
class PreferenceModelTest {
    private val root = "/storage/emulated/0"
    private val now = 1_790_000_000_000L

    private fun junk(category: JunkCategory, rel: String, n: Int) =
        JunkItem("junk:${category.name}:$rel:$n", category, "$root/$rel", false, 5 * MIB, now - 40 * DAY_MS, "")

    @Test
    fun choicesYouKeepMakingBecomeTheDefaultAndUndoingOneCounts() {
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val log = DecisionLog(File(dir, "decisions.tsv"))
            // Six runs: installers MT Manager extracted are always cleaned (the rules leave them unticked), Samsung's
            // Wi-Fi logs are always kept (the rules tick them), and installers in Download are left alone.
            repeat(6) { run ->
                val apk = junk(JunkCategory.INSTALLED_APKS, "MT2/apks/app$run.apk", run)
                val keep = junk(JunkCategory.INSTALLED_APKS, "Download/mine$run.apk", run)
                val wifi = junk(JunkCategory.OLD_LOGS, "log/wifi/wifi$run.log", run)
                log.record("run$run", listOf(apk, keep, wifi), setOf(apk.id), root, now)
            }
            val model = PreferenceModel.train(log.load())
            assertEquals(18, model.decisions)
            val nextApk = junk(JunkCategory.INSTALLED_APKS, "MT2/apks/new.apk", 9)
            val nextWifi = junk(JunkCategory.OLD_LOGS, "log/wifi/new.log", 9)
            val nextMine = junk(JunkCategory.INSTALLED_APKS, "Download/new.apk", 9)
            assertEquals(true, model.choiceFor(nextApk, root, now)?.select)
            assertEquals(false, model.choiceFor(nextWifi, root, now)?.select)
            assertNull(model.choiceFor(nextMine, root, now)) // unticked by default, and you agree: nothing to change
            // Somewhere it has never seen a decision about: the rules decide.
            assertNull(model.choiceFor(junk(JunkCategory.INSTALLED_APKS, "Documents/new.apk", 9), root, now))
            // Merging folders is never pre-ticked.
            assertNull(model.choiceFor(FolderMerge("m", "$root/a", "$root/MT2/apks", 1, 1, 1, 0, 0, emptyList()), root, now))

            // Undoing every one of those runs turns the lesson around.
            repeat(6) { log.undone("run$it", now) }
            val after = PreferenceModel.train(log.load())
            assertTrue(after.probability(com.galaxy.steward.core.learn.PreferenceFeatures.of(nextApk, root, now)) < 0.5)
            assertNull(after.choiceFor(nextApk, root, now))

            log.clear()
            assertTrue(log.load().isEmpty())
            assertFalse(File(dir, "decisions.tsv").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun choicesInFoldersWithSpacesAreLearnedToo() {
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val log = DecisionLog(File(dir, "decisions.tsv"))
            repeat(6) { run ->
                log.record("run$run", listOf(junk(JunkCategory.OLD_LOGS, "Documents/My Logs/old $run.log", run)), emptySet(), root, now)
            }
            val model = PreferenceModel.train(log.load())
            // The rules tick old logs; six times you left these: now they start unticked.
            assertEquals(false, model.choiceFor(junk(JunkCategory.OLD_LOGS, "Documents/My Logs/new one.log", 9), root, now)?.select)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun termuxPicksAreLearnedLikeTheRest() {
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val home = "/data/data/com.termux/files/home"
            val prefix = "/data/data/com.termux/files/usr"
            fun item(target: String, path: String, def: Boolean) =
                com.galaxy.steward.core.termux.TermuxItem("$target=$path", target, target, com.galaxy.steward.core.termux.TermuxGroup.DEV, path, 50 * MIB, 10, def, "")
            val log = DecisionLog(File(dir, "decisions.tsv"))
            // Six clean-ups: the npm cache (ticked by the rules) always left, decompiled apps in ~/work always cleaned.
            repeat(6) { run ->
                val npm = item("npm-cache", "$home/.npm/_cacache", true)
                val decompiled = item("decompiled", "$home/work/app$run", false)
                val offered = listOf(npm, decompiled).map { com.galaxy.steward.core.learn.PreferenceFeatures.ofTermux(it, home, prefix) to (it === decompiled) }
                log.recordFeatures("t$run", offered, now)
            }
            val model = PreferenceModel.train(log.load())
            val features = { i: com.galaxy.steward.core.termux.TermuxItem -> com.galaxy.steward.core.learn.PreferenceFeatures.ofTermux(i, home, prefix) }
            assertEquals(false, model.choiceFor(features(item("npm-cache", "$home/.npm/_cacache", true)), true)?.select)
            assertEquals(true, model.choiceFor(features(item("decompiled", "$home/work/other", false)), false)?.select)
            // Decompiled apps somewhere else: no decisions about that place yet.
            assertNull(model.choiceFor(features(item("decompiled", "$home/re/other", false)), false))
            assertTrue(features(item("pip-cache", "$prefix/tmp/pip", true)).contains("top:\$PREFIX/tmp"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theLogKeepsOnlyTheLatestDecisions() {
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val log = DecisionLog(File(dir, "decisions.tsv"))
            val items = (0 until 1000).map { junk(JunkCategory.OLD_LOGS, "log/f$it.log", it) }
            repeat(26) { log.record("run$it", items, emptySet(), root, now) }
            val kept = log.load()
            assertTrue(kept.size in DecisionLog.MAX_LINES..DecisionLog.MAX_LINES + DecisionLog.MAX_LINES / 4)
            assertEquals("run25", kept.last().runId)
        } finally {
            dir.deleteRecursively()
        }
    }
}
