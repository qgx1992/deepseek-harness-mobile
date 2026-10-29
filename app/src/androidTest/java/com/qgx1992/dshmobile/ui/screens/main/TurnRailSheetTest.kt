package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The turn rail's reach past what the transcript has loaded.
 *
 * The rail is the one list that can name a turn whose events have not paged in, so these pin the two
 * things a reader depends on: a turn they never loaded still lists, and picking it reports that turn
 * rather than the row it happens to sit on.
 */
class TurnRailSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val empty get() = context.getString(R.string.chat_turn_rail_empty)
    private val noPrompt get() = context.getString(R.string.chat_turn_rail_no_prompt)
    private val turnTwo get() = context.getString(R.string.trajectory_turn, 2)
    private val turnNine get() = context.getString(R.string.trajectory_turn, 9)

    private var picked: TurnEntry? = null

    private fun sheet(entries: List<TurnEntry>) {
        compose.setContent {
            DshTheme {
                TurnRailSheet(entries = entries, onPick = { picked = it }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    private fun entry(turn: Int, seq: Long, prompt: String = "prompt $turn", loaded: Boolean = true) =
        TurnEntry(turn = turn, seq = seq, prompt = prompt, response = "reply $turn", loaded = loaded)

    @Test
    fun aTurnTheWindowNeverLoadedStillListsAndCanBePicked() {
        sheet(listOf(entry(2, seq = 10), entry(9, seq = 40, loaded = false)))

        compose.onNodeWithText(turnNine).assertIsDisplayed()
        compose.onNodeWithText("prompt 9").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(9, picked?.turn)
            assertEquals(40L, picked?.seq)
            assertEquals(false, picked?.loaded)
        }
    }

    @Test
    fun everyTurnOfTheSessionIsListed() {
        sheet(listOf(entry(2, seq = 10), entry(9, seq = 40, loaded = false)))

        compose.onNodeWithText(turnTwo).assertIsDisplayed()
        compose.onNodeWithText(turnNine).assertIsDisplayed()
        compose.onNodeWithText("reply 9").assertIsDisplayed()
    }

    @Test
    fun aTurnOpenedWithoutPromptTextSaysSoInsteadOfShowingABlankRow() {
        sheet(listOf(entry(2, seq = 10, prompt = "")))

        compose.onNodeWithText(noPrompt).assertIsDisplayed()
    }

    @Test
    fun noTurnsAtAllSaysSo() {
        sheet(emptyList())

        compose.onNodeWithText(empty).assertIsDisplayed()
    }
}
