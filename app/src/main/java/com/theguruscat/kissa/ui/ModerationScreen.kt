package com.theguruscat.kissa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theguruscat.kissa.data.Report
import com.theguruscat.kissa.data.StoriesRepository
import kotlinx.coroutines.launch

/**
 * Admins only: open reports, oldest first. Remove deletes what was reported (closing its reports), Dismiss
 * closes the report and keeps it, Ban stops the author posting, commenting, voting and reporting.
 */
@Composable
fun ModerationScreen(
    community: StoriesRepository,
    onStoryRemoved: (cardId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var reports by remember { mutableStateOf<List<Report>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped after each action, to reload the list.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        runCatching { community.openReports() }
            .onSuccess { reports = it; error = null }
            .onFailure { error = it.message }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { error = it.message }
            version++
        }
    }

    val list = reports
    LazyColumn(modifier.fillMaxSize()) {
        item {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
        when {
            list == null && error == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            list != null && list.isEmpty() -> item {
                Text("No open reports.", modifier = Modifier.padding(16.dp))
            }
        }
        items(list.orEmpty(), key = { it.id }) { report ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${if (report.isStory) "Story" else "Comment"} by u/${report.author ?: "?"}" +
                        (if (report.authorBanned) " (banned)" else "") + "  ·  " +
                        DateUtils.getRelativeTimeSpanString(report.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    report.content ?: "(already removed)",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Reported by u/${report.reporter}: ${report.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        act {
                            if (report.isStory) {
                                community.delete(report.targetId)
                                onStoryRemoved("k_${report.targetId}")
                            } else {
                                community.deleteComment(report.targetId)
                            }
                        }
                    }) { Text("Remove") }
                    TextButton(onClick = { act { community.dismissReport(report.id) } }) { Text("Dismiss") }
                    report.author?.let { author ->
                        TextButton(onClick = { act { community.setBanned(author, !report.authorBanned) } }) {
                            Text(if (report.authorBanned) "Unban" else "Ban")
                        }
                    }
                }
                TextButton(onClick = { uriHandler.openUri(report.link) }) { Text("Open the story ↗") }
            }
            HorizontalDivider()
        }
    }
}
