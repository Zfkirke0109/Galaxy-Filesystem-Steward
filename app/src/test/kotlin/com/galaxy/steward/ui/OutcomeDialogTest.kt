package com.galaxy.steward.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.core.MIB
import com.galaxy.steward.core.exec.ExecutionSummary
import com.galaxy.steward.ui.components.OutcomeDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A run that left its duplicates and clutter in the quarantine (the 11-19 Autopilot: "freed 0 B", 2.2 GiB there) offers
 * to free that at once, after saying that Undo can't bring it back. The figure is what the quarantine holds for the run,
 * as the dialog's own "quarantined" line says: a pack run's zips stay where they are and are not part of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OutcomeDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun summary(quarantined: Long, packed: Long = 0) = ExecutionSummary(
        runId = "run-1", completedItemIds = emptySet(), partialItemIds = emptySet(), moved = 59, deduped = 41, quarantined = 11,
        removedDirs = 13, skipped = 0, failed = 0, bytesFreed = 0, bytesQuarantined = quarantined, bytesMoved = 0,
        messages = emptyList(), changedPaths = emptyList(), dedupedQuarantined = 41, packed = if (packed > 0) 1 else 0, bytesPacked = packed,
    )

    @Test
    fun aRunThatLeftItsBytesInTheQuarantineOffersToFreeThemAfterAsking() {
        val freed = ArrayList<String>()
        compose.setContent {
            OutcomeDialog(Outcome.Applied("Autopilot", summary(300 * MIB, packed = 100 * MIB)), onUndo = null, onDismiss = {}, onFreeNow = { freed += it })
        }
        compose.onNodeWithText("Free 300 MiB now").performClick()
        assertEquals(emptyList<String>(), freed)
        compose.onNodeWithText("Undo can't bring it back", substring = true).assertExists()
        compose.onNodeWithText("files you picked and what a recycle bin held have no other copy", substring = true).assertExists()
        compose.onNodeWithText("Keep for now").performClick()
        compose.onNodeWithText("Free 300 MiB now").performClick()
        compose.onNodeWithText("Delete 300 MiB").performClick()
        assertEquals(listOf("run-1"), freed)
    }

    @Test
    fun nothingInTheQuarantineMeansNoOffer() {
        compose.setContent { OutcomeDialog(Outcome.Applied("Autopilot", summary(0)), onUndo = null, onDismiss = {}, onFreeNow = {}) }
        compose.onNodeWithText("Free", substring = true).assertDoesNotExist()
    }
}
