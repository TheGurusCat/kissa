package com.theguruscat.kissa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theguruscat.kissa.data.Notification
import com.theguruscat.kissa.data.RedditPost
import com.theguruscat.kissa.data.StoriesRepository
import kotlinx.coroutines.launch

/**
 * Likes and comments on your stories, newest first; unread ones have a dot. Opening the screen marks them read
 * ([onSeen] gets the newest ID); tapping one opens that story's comments.
 */
@Composable
fun NotificationsScreen(
    community: StoriesRepository,
    onSeen: (newestId: Long) -> Unit,
    onOpenStory: (RedditPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Notification>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { community.notifications().first }
            .onSuccess { loaded ->
                list = loaded
                loaded.firstOrNull()?.let { onSeen(it.id) }
            }
            .onFailure { error = it.message }
    }
    val loaded = list
    when {
        error != null -> Message(error.orEmpty(), modifier, isError = true)
        loaded == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        loaded.isEmpty() -> Message("When someone likes or comments on your stories, it shows up here.", modifier)
        else -> LazyColumn(modifier.fillMaxSize()) {
            items(loaded, key = { it.id }) { item ->
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            runCatching { community.story(item.storyId) }
                                .onSuccess(onOpenStory)
                                .onFailure { error = it.message }
                        }
                    },
                    leadingContent = {
                        Icon(
                            if (item.isComment) Icons.AutoMirrored.Filled.Comment else Icons.Filled.Favorite,
                            contentDescription = if (item.isComment) "Comment" else "Like",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    overlineContent = {
                        Text(DateUtils.getRelativeTimeSpanString(item.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                    },
                    headlineContent = {
                        Text(
                            if (item.isComment) "u/${item.actor} commented on \"${item.storyTitle}\""
                            else "u/${item.actor} liked \"${item.storyTitle}\"",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = item.comment?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = if (item.read) null else {
                        { Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier, isError: Boolean = false) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
