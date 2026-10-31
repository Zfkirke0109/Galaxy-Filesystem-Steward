package com.galaxy.steward.core

import com.galaxy.steward.core.organize.BuiltInRules
import com.galaxy.steward.core.organize.KeywordRule
import com.galaxy.steward.core.organize.RuleTarget
import com.galaxy.steward.core.organize.Text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Filing-rule keywords: whole words, a trailing `*` for a word prefix, in any case, accent or script. */
class KeywordRuleTest {
    private fun rule(vararg keywords: String, ext: Set<String> = setOf("pdf"), target: RuleTarget = RuleTarget.BOTH) =
        KeywordRule("Test", keywords.toList(), "Documents/Test", ext, target)

    private fun KeywordRule.matches(vararg names: String) = names.forEach { assertTrue("$keywords should match $it", matchesFile(it)) }
    private fun KeywordRule.missesAll(vararg names: String) = names.forEach { assertFalse("$keywords should not match $it", matchesFile(it)) }

    @Test
    fun ordinaryWordsMatchWholeWordsOnly() {
        rule("hotel").matches("hotel.pdf", "Hotel-Booking.PDF", "my_hotel_2024.pdf", "Lisbon (hotel).pdf")
        rule("hotel").missesAll("hotelier.pdf", "motel.pdf", "hotel.jpg")
    }

    @Test
    fun aTrailingStarMatchesAWordPrefix() {
        rule("statement*").matches("Statements_2024.pdf", "statement.pdf", "STATEMENT-jan.pdf", "bank statement2024.pdf")
        rule("statement*").missesAll("bankstatement.pdf", "misstatement.pdf")
        rule("boarding pass*").matches("Boarding_Passes-Lisbon.pdf", "boarding pass 2.pdf")
        rule("boarding pass*").missesAll("boarding.pdf", "passes boarding.pdf")
    }

    @Test
    fun spacesStarsAndCaseAroundAKeywordDontChangeIt() {
        listOf(" tax* ", "tax**", "TAX*", "Tax *").forEach { assertEquals(it, " tax", Text.keyword(it)) }
        listOf("tax", " TAX ", "Tax.").forEach { assertEquals(it, " tax ", Text.keyword(it)) }
        rule("iPhone Backup").matches("IPHONE_backup_2023.pdf", "iphone-BACKUP.pdf")
    }

    /** A `*` only means "any ending" at the end; elsewhere it separates words like any other punctuation. */
    @Test
    fun leadingAndInnerStarsAreNotWildcards() {
        rule("*tax").matches("tax.pdf", "my tax.pdf")
        rule("*tax").missesAll("syntax.pdf", "taxes.pdf")
        rule("e*ticket").matches("e-ticket.pdf", "E ticket.pdf")
        rule("e*ticket").missesAll("eticket.pdf", "ticket.pdf")
    }

    @Test
    fun accentsFoldAway() {
        rule("résumé").matches("Resume 2024.pdf", "RÉSUMÉ.pdf", "résumé.pdf")
        rule("resume").matches("Résumé.pdf")
        rule("café*").matches("Cafeteria menu.pdf", "Café receipts.pdf")
        // The built-in career rule now knows an accented résumé.
        val career = BuiltInRules.rules.first { it.name == "Career" }
        assertTrue(career.matchesFile("Résumé - Zach.pdf"))
    }

    @Test
    fun lettersOfOtherScriptsAreWords() {
        rule("налог*").matches("Налоговая декларация 2024.pdf", "налог.pdf")
        rule("налог*").missesAll("отчет.pdf", "report.pdf")
        rule("φορολογία").matches("Φορολογία 2023.pdf")
        rule("φορολογία").missesAll("report.pdf")
    }

    /** Chinese and Japanese names don't put spaces between words, so their keywords match anywhere in a name. */
    @Test
    fun unspacedScriptsMatchAnywhereInTheName() {
        rule("請求書").matches("2024年11月請求書.pdf", "請求書.pdf")
        rule("請求書").missesAll("領収書.pdf", "invoice.pdf")
    }

    /**
     * A keyword with no letter or digit left once normalized ("***", "-", or before this fix anything not in a-z0-9,
     * like "налог") used to vanish, and a rule with nothing but such keywords then matched every file of its types:
     * every PDF in storage filed under one rule.
     */
    @Test
    fun keywordsWithoutLettersOrDigitsMatchNothing() {
        listOf("", " ", "*", "***", "-*", "_", "!!!", "* *").forEach { assertNull("'$it'", Text.keyword(it)) }
        rule("***").missesAll("report.pdf", "anything.pdf")
        rule("-", "* *").missesAll("report.pdf")
        // A usable keyword next to an unusable one still works.
        rule("***", "tax").matches("tax.pdf")
        rule("***", "tax").missesAll("report.pdf")
        // As saved in settings.
        val saved = KeywordRule.decode("Bad\t***\tpdf\tDocuments/Bad\tBOTH")!!
        assertFalse(saved.matchesFile("report.pdf"))
        assertEquals(listOf("***"), KeywordRule.unusableKeywords(listOf("***", "tax", "налог")))
    }

    @Test
    fun extensionOnlyRulesStillMatchEveryFileOfTheirTypes() {
        val books = KeywordRule("Books", emptyList(), "Documents/Books", setOf("epub"))
        assertTrue(books.matchesFile("Anything at all.EPUB"))
        assertFalse(books.matchesFile("notes.pdf"))
        assertFalse(books.matchesFolder("epub"))
    }

    @Test
    fun targetsKeepFilesAndFoldersApart() {
        val files = rule("tax*", target = RuleTarget.FILES)
        val folders = rule("tax*", ext = emptySet(), target = RuleTarget.FOLDERS)
        val both = rule("tax*", ext = emptySet())
        assertTrue(files.matchesFile("taxes.pdf"))
        assertFalse(files.matchesFolder("Taxes"))
        assertFalse(folders.matchesFile("taxes.pdf"))
        assertTrue(folders.matchesFolder("Taxes 2024"))
        assertTrue(both.matchesFile("taxes.txt"))
        assertTrue(both.matchesFolder("Taxes"))
        // Only a folder's own name counts, not where it is: "2024" inside "Taxes" is not a tax folder.
        assertFalse(folders.matchesFolder("2024"))
    }

    @Test
    fun unicodeRulesRoundTripThroughSettings() {
        val r = KeywordRule("Налоги", listOf("налог*", "請求書"), "Documents/Налоги", setOf("pdf"), RuleTarget.FILES)
        assertEquals(r, KeywordRule.decode(r.encode()))
    }

    /** Overlapping rules: yours come before the built-in ones, and among either the first that matches decides. */
    @Test
    fun theFirstMatchingRuleDecides() = runTest {
        TestFs().use { fs ->
            fs.text("Download/invoice hotel lisbon.pdf", "pdf")
            fs.text("Download/Taxes/2024/receipt hotel.pdf", "pdf")
            fs.ageDirectories()
            suspend fun destinations(settings: StewardSettings) =
                Steward(fs.rootPath, settings, TestEnv, File(fs.stateDir, "cache.tsv")).scan().organize.associate { it.source to it.destination }

            // Built in: "Personal finance" (invoice*) comes before "Travel" (hotel*).
            val builtIn = destinations(testSettings)
            assertEquals(fs.path("Documents/Personal/Finance/invoice hotel lisbon.pdf"), builtIn[fs.path("Download/invoice hotel lisbon.pdf")])

            val work = KeywordRule("Work trips", listOf("hotel"), "Documents/Work/Trips", setOf("pdf"))
            val yours = destinations(testSettings.copy(customRules = listOf(work)))
            assertEquals(fs.path("Documents/Work/Trips/invoice hotel lisbon.pdf"), yours[fs.path("Download/invoice hotel lisbon.pdf")])
        }
    }
    /** Keywords that only lost part of themselves to normalization used to match far too much. */
    @Test
    fun partlyLatinKeywordsKeepTheirLetters() {
        rule("Über*").missesAll("Berlin trip.pdf", "Bericht.pdf")
        rule("Über*").matches("Überweisung 2024.pdf", "uberweisung.pdf")
        rule("счёт 2024").missesAll("report 2024.pdf")
        rule("счёт 2024").matches("Счет 2024.pdf", "счёт-2024.pdf")
        rule("İnvoice").matches("invoice.pdf", "INVOICE 3.pdf")
        // No CamelCase splitting: "BoardingPass" is one word.
        rule("boarding pass*").missesAll("BoardingPass.pdf")
        rule("tax*").missesAll("surtax.pdf")
    }

    @Test
    fun aRuleIsStoredOnOneLine() {
        val r = KeywordRule("Bank", listOf("bank\nstatement", "tab\there"), "Documents/Bank", setOf("pdf"))
        val line = r.encode()
        assertFalse(line.contains('\n'))
        val back = KeywordRule.decode(line)!!
        assertEquals(listOf("bank statement", "tab here"), back.keywords)
        assertEquals("Documents/Bank", back.destination)
        assertEquals(setOf("pdf"), back.extensions)
    }
}
