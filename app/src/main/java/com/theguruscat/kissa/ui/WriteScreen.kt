package com.theguruscat.kissa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theguruscat.kissa.AccountState
import com.theguruscat.kissa.WritingState
import com.theguruscat.kissa.data.RedditPost

/**
 * Write a story for the Stories tab, and edit or delete the ones already published. Needs an account with a
 * username; until then it points to the Account screen. Publishing is immediate; readers get new stories the
 * next time their Stories tab fetches.
 */
@Composable
fun WriteScreen(
    account: AccountState,
    writing: WritingState,
    onOpenAccount: () -> Unit,
    onLoad: () -> Unit,
    onSave: (id: String?, title: String, body: String, onSaved: () -> Unit) -> Unit,
    onDelete: (RedditPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!account.canPost) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Write for Kissa", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    account.isBanned -> "This account can't post stories."
                    account.email == null -> "Anyone can post stories here. Sign in first; it takes a few seconds."
                    else -> "Pick a username first. Your stories show it as u/name."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!account.isBanned) {
                Button(onClick = onOpenAccount) { Text(if (account.email == null) "Sign in" else "Pick a username") }
            }
        }
        return
    }
    LaunchedEffect(account.username) { onLoad() }
    // The story being edited; null while writing a new one.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<RedditPost?>(null) }
    val clear = { editingId = null; title = ""; body = "" }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (editingId == null) "New story" else "Edit story", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(MAX_TITLE) },
            label = { Text("Title") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it.take(MAX_BODY) },
            label = { Text("Story") },
            supportingText = { Text("A blank line starts a new paragraph.") },
            minLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(editingId, title, body, clear) },
                enabled = title.isNotBlank() && body.isNotBlank() && !writing.isSaving,
            ) {
                if (writing.isSaving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (editingId == null) "Publish" else "Save changes")
                }
            }
            if (editingId != null || title.isNotEmpty() || body.isNotEmpty()) {
                TextButton(onClick = clear, enabled = !writing.isSaving) {
                    Text(if (editingId == null) "Clear" else "Cancel")
                }
            }
        }
        writing.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Published", style = MaterialTheme.typography.titleMedium)
        when {
            writing.isLoading && writing.stories.isEmpty() -> CircularProgressIndicator(Modifier.size(24.dp))
            writing.stories.isEmpty() -> Text(
                "Nothing yet. Your stories appear in everyone's Stories tab, newest first, as u/${account.username}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        writing.stories.forEach { story ->
            ListItem(
                overlineContent = {
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            story.publishedAt,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString() + "  ·  ${story.readMinutes} min read",
                    )
                },
                headlineContent = { Text(story.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = { editingId = story.id; title = story.title; body = story.body },
                            enabled = !writing.isSaving,
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { confirmDelete = story }, enabled = !writing.isSaving) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        }
    }

    confirmDelete?.let { story ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this story?") },
            text = { Text("\"${story.title}\" leaves the Stories tab for everyone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(story)
                    if (editingId == story.id) clear()
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

// The relay's limits (relay/src/stories.js).
private const val MAX_TITLE = 300
private const val MAX_BODY = 40_000
