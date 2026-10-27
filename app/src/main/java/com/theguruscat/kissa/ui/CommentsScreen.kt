package com.theguruscat.kissa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.theguruscat.kissa.AccountState
import com.theguruscat.kissa.data.Comment
import com.theguruscat.kissa.data.RedditPost
import com.theguruscat.kissa.data.StoriesRepository
import kotlinx.coroutines.launch

/**
 * A Kissa story's comments, oldest first, with a box to add one. Tapping a name opens that profile; each
 * comment's menu offers Delete (your own, or any for admins) and Report. [onStoryRemoved] runs after the
 * story itself is deleted from here.
 */
@Composable
fun CommentsScreen(
    post: RedditPost,
    account: AccountState,
    community: StoriesRepository,
    onOpenProfile: (String) -> Unit,
    onStoryRemoved: (RedditPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Comment>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(post.id) {
        runCatching { community.comments(post.id) }
            .onSuccess { comments = it }
            .onFailure { error = it.message }
    }
    // Runs [block], showing its failure under the list.
    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { error = it.message } }
    }
    val canModerate = { author: String -> account.isAdmin || (account.username != null && account.username.equals(author, ignoreCase = true)) }

    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(post.title, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (post.author.isNotEmpty()) {
                            Text(
                                "u/${post.author}" + if (post.isHouse) "  $HOUSE_BADGE" else "",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { onOpenProfile(post.author) }.padding(vertical = 6.dp),
                            )
                        }
                        Text(
                            "  ·  ♥ ${compactCount(post.score)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ItemMenu(
                            canDelete = canModerate(post.author),
                            canReport = account.email != null && !account.isBanned,
                            onDelete = {
                                act {
                                    community.delete(post.id)
                                    onStoryRemoved(post)
                                }
                            },
                            onReport = { reporting = true to post.id },
                        )
                    }
                }
                HorizontalDivider()
            }
            val list = comments
            when {
                list == null && error == null -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
                list != null && list.isEmpty() -> item {
                    Text(
                        "No comments yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(list.orEmpty(), key = { it.id }) { comment ->
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "u/${comment.author}" + (if (comment.isHouse) "  $HOUSE_BADGE" else "") + "  ·  " +
                                DateUtils.getRelativeTimeSpanString(
                                comment.createdAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onOpenProfile(comment.author) },
                        )
                        Text(comment.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                    ItemMenu(
                        canDelete = canModerate(comment.author),
                        canReport = account.email != null && !account.isBanned,
                        onDelete = {
                            act {
                                community.deleteComment(comment.id)
                                comments = comments.orEmpty() - comment
                            }
                        },
                        onReport = { reporting = false to comment.id },
                    )
                }
            }
            item {
                (error ?: notice)?.let {
                    Text(
                        it,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        HorizontalDivider()
        if (account.canPost) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_COMMENT) },
                    placeholder = { Text("Add a comment") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        sending = true
                        error = null
                        scope.launch {
                            runCatching { community.addComment(post.id, draft.trim()) }
                                .onSuccess { comments = comments.orEmpty() + it; draft = "" }
                                .onFailure { error = it.message }
                            sending = false
                        }
                    },
                    enabled = draft.isNotBlank() && !sending,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post comment")
                }
            }
        } else {
            Text(
                when {
                    account.isBanned -> "This account can't comment."
                    account.email == null -> "Sign in (menu → Account) to comment."
                    else -> "Pick a username (menu → Account) to comment."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    reporting?.let { (isStory, id) ->
        ReportDialog(
            what = if (isStory) "story" else "comment",
            onDismiss = { reporting = null },
            onReport = { reason ->
                reporting = null
                act {
                    community.report(isStory, id, reason)
                    notice = "Thanks, the admins will take a look."
                }
            },
        )
    }
}

@Composable
private fun ItemMenu(canDelete: Boolean, canReport: Boolean, onDelete: () -> Unit, onReport: () -> Unit) {
    if (!canDelete && !canReport) return
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canDelete) DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; confirm = true })
            if (canReport) DropdownMenuItem(text = { Text("Report") }, onClick = { open = false; onReport() })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this?") },
            text = { Text("It's gone for everyone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}

/** Asks why a story or comment is being reported. */
@Composable
fun ReportDialog(what: String, onDismiss: () -> Unit, onReport: (String) -> Unit) {
    var reason by remember { mutableStateOf(REPORT_REASONS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this $what") },
        text = {
            Column {
                REPORT_REASONS.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { reason = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = { reason = option })
                        Text(option)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onReport(reason) }) { Text("Report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val REPORT_REASONS = listOf("Spam", "Harassment or hate", "Sexual content involving minors", "Violence or threats", "Something else")

// The relay's limit (relay/src/stories.js).
private const val MAX_COMMENT = 5_000
