package com.galaxy.steward.core

import com.galaxy.steward.core.organize.FilingModel
import com.galaxy.steward.core.plan.ScanReport
import com.galaxy.steward.core.scan.TreeScanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Homes learned from the phone's own folders. */
class FilingModelTest {
    private fun layout(fs: TestFs) {
        for (game in listOf("Bionicle Heroes (USA)", "Ratchet and Clank (USA)", "Jak and Daxter (USA)", "Sly Cooper (USA)")) {
            fs.random("Documents/Gaming-Emulation/PS2/$game.iso", 4000, game.length)
        }
        fs.random("Documents/Gaming-Emulation/PS2/memcard.ps2", 400, 1)
        for (a in listOf("backup-2024.zip", "old-photos.zip", "fonts.7z", "site-export.tar.gz")) fs.random("Documents/Archives/$a", 900, a.length)
        for (r in listOf("chicken-curry-recipe", "lasagna-recipe", "sourdough-bread-recipe", "tomato-soup-recipe")) {
            fs.random("Documents/Recipes/$r.pdf", 700, r.length)
        }
        for (d in listOf("tv-manual", "fridge-warranty", "router-manual")) fs.random("Documents/Reference/$d.pdf", 800, d.length)
        repeat(6) { fs.random("Documents/Audio-DSP/Presets/viper-preset-$it.vdc", 300, it) }
        fs.random("Documents/Audio-DSP/impulse-hall.irs", 5000, 40)
        // Filed in the wrong place: PS2 images inside the audio folder.
        fs.random("Documents/Audio-DSP/Ratchet-Clank-Going-Commando/Ratchet Clank Going Commando (USA).iso", 4000, 50)
        fs.random("Documents/Audio-DSP/Ratchet-Clank-Going-Commando/Ratchet Clank Going Commando (USA).cue", 100, 51)
        // New downloads.
        fs.random("Download/Bionicle (Europe).iso", 4000, 60)
        fs.random("Download/pancake-recipe.pdf", 700, 61)
        fs.random("Download/zebra-migration.pdf", 700, 62)
        fs.ageDirectories()
    }

    private suspend fun scan(fs: TestFs, settings: StewardSettings = testSettings.copy(minDuplicateBytes = 1L shl 40)): ScanReport =
        Steward(fs.rootPath, settings, TestEnv, null).scan()

    @Test
    fun looseFilesGoWhereThingsLikeThemAlreadyAre() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val moves = scan(fs).organize.associateBy { it.source.removePrefix(fs.rootPath + "/") }
            val iso = moves.getValue("Download/Bionicle (Europe).iso")
            assertEquals(fs.path("Documents/Gaming-Emulation/PS2/Bionicle (Europe).iso"), iso.destination)
            assertTrue(iso.reason, iso.reason.startsWith("Learned: shares ") && ".iso" in iso.reason && "\"bionicle\"" in iso.reason)
            val pancake = moves.getValue("Download/pancake-recipe.pdf")
            assertEquals(fs.path("Documents/Recipes/pancake-recipe.pdf"), pancake.destination)
            // Nothing about zebras is filed yet: the type decides, as before.
            assertEquals(fs.path("Documents/Reference/zebra-migration.pdf"), moves.getValue("Download/zebra-migration.pdf").destination)
        }
    }

    @Test
    fun aFolderThatLooksOutOfPlaceIsSuggestedForItsHome() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val report = scan(fs)
            val move = report.organize.single { it.source == fs.path("Documents/Audio-DSP/Ratchet-Clank-Going-Commando") }
            assertEquals(fs.path("Documents/Gaming-Emulation/PS2/Ratchet-Clank-Going-Commando"), move.destination)
            assertTrue(!move.defaultSelected && move.reason.startsWith("Learned: "))
            // The presets fit where they are.
            assertTrue(report.organize.none { it.source.contains("Presets") })
            assertTrue(report.insights.any { it.title.endsWith("learned from your own folders") })
        }
    }

    @Test
    fun learningCanBeTurnedOffAndTheModelExplainsItself() = runTest {
        TestFs().use { fs ->
            layout(fs)
            val off = scan(fs, testSettings.copy(minDuplicateBytes = 1L shl 40, learnFromFolders = false))
            assertEquals(fs.path("Documents/Archives/Bionicle (Europe).iso"), off.organize.single { it.source.endsWith("Bionicle (Europe).iso") }.destination)
            assertTrue(off.organize.none { it.reason.startsWith("Learned") })

            val tree = TreeScanner(fs.rootPath, testSettings).scan { _, _, _, _ -> }
            val model = FilingModel.train(tree)
            val home = model.homeFor(tree.find("Download")!!.files.first { it.name == "Bionicle (Europe).iso" })!!
            assertEquals("Documents/Gaming-Emulation/PS2", home.folder)
            assertTrue(home.examples >= 4)
            assertTrue(home.score >= FilingModel.MIN_SCORE)
            assertNull(model.homeFor(tree.find("Download")!!.files.first { it.name == "zebra-migration.pdf" }))
            assertEquals(listOf("sourdough", "bread", "recipe"), FilingModel.tokens("Sourdough_bread-RECIPE-v2 (1)"))
        }
    }

    @Suppress("unused")
    private fun File.touch() = setLastModified(System.currentTimeMillis())
}
