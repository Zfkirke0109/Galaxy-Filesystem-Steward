package com.galaxy.steward

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.galaxy.steward.apps.AppStorageRow
import com.galaxy.steward.ui.screens.ClearDataDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Clearing app data can't be undone, so the dialog names the apps and only confirms after "I understand". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ClearDataDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun app(pkg: String, label: String, mib: Long) =
        AppStorageRow(pkg, label, system = false, appBytes = 0, dataBytes = mib * 1024 * 1024, cacheBytes = 0, protected = false)

    @Test
    fun confirmsOnlyAfterIUnderstand() {
        var confirmed = 0
        val apps = listOf(app("a.one", "One", 10), app("a.two", "Two", 300), app("a.three", "Three", 20), app("a.four", "Four", 5), app("a.five", "Five", 1))
        compose.setContent { ClearDataDialog(apps, onConfirm = { confirmed++ }, onDismiss = {}) }

        compose.onNodeWithText("Clear all data of 5 apps?").assertExists()
        compose.onNodeWithText("Two, Three, One and 2 more").assertExists() // largest first
        compose.onNodeWithText("• Frees about 336 MiB").assertExists()
        compose.onNodeWithText("Clear data").assertIsNotEnabled().performClick()
        assertEquals(0, confirmed)

        compose.onNodeWithText("I understand this can't be undone").performClick()
        compose.onNodeWithText("Clear data").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
}
