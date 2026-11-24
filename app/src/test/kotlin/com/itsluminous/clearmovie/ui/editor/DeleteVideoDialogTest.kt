package com.itsluminous.clearmovie.ui.editor

import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.clearmovie.R
import com.itsluminous.clearmovie.ui.theme.ClearMovieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Delete this video?" (cm-delete-project): plain words, Cancel and Delete, and only Delete deletes. */
@RunWith(RobolectricTestRunner::class)
class DeleteVideoDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = context.getString(id)

    private fun show(): MutableList<String> {
        val calls = mutableListOf<String>()
        compose.setContent { ClearMovieTheme { DeleteVideoDialog(onDelete = { calls += "delete" }, onDismiss = { calls += "cancel" }) } }
        return calls
    }

    @Test
    fun `it says the gallery copy stays and the video can't be brought back, with Cancel and Delete`() {
        show()
        compose.onNodeWithTag(DELETE_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_delete_body)).assertIsDisplayed()
        assertThat(s(R.string.editor_delete_title)).isEqualTo("Delete this video from My videos?")
        assertThat(s(R.string.editor_delete_body)).contains("saved to your gallery from it stay there")
        assertThat(s(R.string.editor_delete_body)).contains("can't be brought back")
        compose.onNodeWithTag(DELETE_CANCEL_TAG).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(DELETE_CONFIRM_TAG).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(s(R.string.action_cancel)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_delete)).assertIsDisplayed()
    }

    @Test
    fun `Cancel deletes nothing`() {
        val calls = show()
        compose.onNodeWithTag(DELETE_CANCEL_TAG).performClick()
        assertThat(calls).containsExactly("cancel")
    }

    @Test
    fun `Delete deletes`() {
        val calls = show()
        compose.onNodeWithTag(DELETE_CONFIRM_TAG).performClick()
        assertThat(calls).containsExactly("delete")
    }

    @Test
    @Config(qualifiers = "hi")
    fun `in Hindi it speaks Hindi`() {
        show()
        compose.onNodeWithText("यह वीडियो मेरे वीडियो से हटाएँ?").assertIsDisplayed()
        compose.onNodeWithText("हटाएँ").assertIsDisplayed()
    }

    @Test
    fun `while the video is being saved it says to wait, with OK`() {
        var closed = false
        compose.setContent { ClearMovieTheme { DeleteBlockedDialog(onDismiss = { closed = true }) } }
        compose.onNodeWithTag(DELETE_BUSY_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.editor_delete_busy_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_ok)).performClick()
        assertThat(closed).isTrue()
    }
}
