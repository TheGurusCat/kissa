package com.theguruscat.kissa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theguruscat.kissa.data.RedditPost
import com.theguruscat.kissa.data.StoriesRepository
import com.theguruscat.kissa.data.UserProfile

/** Someone's page: when they joined and every story they've posted, newest first; tap one for its comments. */
@Composable
fun ProfileScreen(
    username: String,
    community: StoriesRepository,
    onOpenStory: (RedditPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(username) {
        runCatching { community.profile(username) }
            .onSuccess { profile = it }
            .onFailure { error = it.message }
    }
    val loaded = profile
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("u/${loaded?.username ?: username}", style = MaterialTheme.typography.headlineSmall)
                if (loaded?.isHouse == true) {
                    Text(
                        "$HOUSE_BADGE · one of Kissa's own accounts",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (loaded != null) {
                    val stories = loaded.stories.size
                    val score = loaded.stories.sumOf { it.score }
                    Text(
                        "Joined " + DateUtils.getRelativeTimeSpanString(loaded.joinedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS) +
                            "  ·  $stories ${if (stories == 1) "story" else "stories"}  ·  ▲ $score",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider()
        }
        when {
            loaded == null && error == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            loaded != null && loaded.stories.isEmpty() -> item {
                Text(
                    "No stories yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(loaded?.stories.orEmpty(), key = { it.id }) { story ->
            ListItem(
                modifier = Modifier.clickable { onOpenStory(story) },
                overlineContent = {
                    Text(
                        DateUtils.getRelativeTimeSpanString(story.publishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            .toString() + "  ·  ▲ ${story.score}  ·  ${story.numComments} comments",
                    )
                },
                headlineContent = { Text(story.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(story.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            )
            HorizontalDivider()
        }
    }
}
