package com.theguruscat.kissa.data

import org.json.JSONObject
import java.security.MessageDigest

/**
 * One card: a Reddit text post, a news article when [isNews] is set, or one of Kissa's own stories when
 * [isOriginal] is set. For news, [subreddit] holds the feed's name (see [NEWS_FEEDS]); for news and Kissa
 * stories, [permalink] is the full URL.
 */
data class RedditPost(
    val id: String,
    val subreddit: String,
    val title: String,
    val body: String,
    val author: String,
    val score: Int,
    val numComments: Int,
    val permalink: String,
    val isNsfw: Boolean,
    val isNews: Boolean = false,
    /** When the post or article was published, in epoch milliseconds; 0 when unknown. */
    val publishedAt: Long = 0L,
    /** Written for Kissa and posted through the relay (the Stories tab), not from Reddit or a news site. */
    val isOriginal: Boolean = false,
    /** A Kissa story posted by one of Kissa's own (house) accounts rather than a reader; shown with a badge. */
    val isHouse: Boolean = false,
) {
    val url: String get() = if (isNews || isOriginal) permalink else "https://www.reddit.com$permalink"

    /** Where the card came from, as shown above it: "r/tifu", "BBC News", or "u/name" for a Kissa story. */
    val sourceLabel: String get() = when {
        isOriginal -> if (author.isNotEmpty()) "u/$author" else subreddit
        isNews -> subreddit
        else -> "r/$subreddit"
    }

    /** A Reddit post: neither news nor a Kissa story. */
    val isReddit: Boolean get() = !isNews && !isOriginal

    /** From a subreddit of short posts (see [QUICK_READ_SUBREDDITS]) rather than a story subreddit. */
    val isQuickRead: Boolean by lazy { isReddit && isQuickReadSubreddit(subreddit) }

    /**
     * Identifies the same story under another ID: a Reddit crosspost, or one article in two feeds (BBC News
     * and BBC World often share stories). Google News adds " - Outlet" to titles, so that's left out. Null
     * for titles too short to tell apart, such as "Update".
     */
    val titleKey: String? by lazy {
        val text = if (isNews) title.substringBeforeLast(" - ") else title
        text.lowercase().filter { it.isLetterOrDigit() }.takeIf { it.length >= 15 }?.let { "t:$it" }
    }

    /**
     * Identifies the same Reddit story posted again under another title, often to several subreddits at
     * once ("AITA for…" in r/AmItheAsshole, "AITAH for…" in r/AITAH): a hash of the start of the body, so
     * edits added at the end don't change it. Null for news, Kissa stories and posts too short to tell apart.
     */
    val bodyKey: String? by lazy {
        if (!isReddit) return@lazy null
        val text = body.lowercase().filter { it.isLetterOrDigit() }
        if (text.length < BODY_KEY_CHARS) return@lazy null
        val digest = MessageDigest.getInstance("SHA-1").digest(text.take(BODY_KEY_CHARS).encodeToByteArray())
        "b:" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Everything that marks this card as already read: its ID and, when there are ones, its [titleKey] and [bodyKey]. */
    val seenKeys: List<String> get() = listOfNotNull(id, titleKey, bodyKey)

    /**
     * A news article published more than [NEWS_MAX_AGE_MS] ago (articles without a date never go stale), or a
     * Reddit post more than [REDDIT_MAX_AGE_MS] ago. Reddit cards saved before posts carried a date count as
     * stale, so an old offline deck clears once.
     */
    fun isStale(now: Long = System.currentTimeMillis()) = when {
        isNews -> publishedAt > 0L && now - publishedAt > NEWS_MAX_AGE_MS
        isReddit -> now - publishedAt > REDDIT_MAX_AGE_MS
        else -> false
    }

    /** Estimated reading time at about 230 words per minute; computed once, since cards redraw often. */
    val readMinutes: Int by lazy {
        val words = "$title $body".split(WHITESPACE).count { it.isNotEmpty() }
        maxOf(1, (words + 115) / 230)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("subreddit", subreddit)
        .put("title", title)
        .put("body", body)
        .put("author", author)
        .put("score", score)
        .put("numComments", numComments)
        .put("permalink", permalink)
        .put("isNsfw", isNsfw)
        .put("isNews", isNews)
        .put("publishedAt", publishedAt)
        .put("isOriginal", isOriginal)
        .put("isHouse", isHouse)

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun fromJson(json: JSONObject) = RedditPost(
            id = json.getString("id"),
            subreddit = json.getString("subreddit"),
            title = json.getString("title"),
            body = json.optString("body"),
            author = json.optString("author"),
            score = json.optInt("score"),
            numComments = json.optInt("numComments"),
            permalink = json.getString("permalink"),
            isNsfw = json.optBoolean("isNsfw"),
            isNews = json.optBoolean("isNews"),
            publishedAt = json.optLong("publishedAt"),
            isOriginal = json.optBoolean("isOriginal"),
            isHouse = json.optBoolean("isHouse"),
        )
    }
}

// Letters and digits from the start of a body that [RedditPost.bodyKey] hashes; about 40 words.
private const val BODY_KEY_CHARS = 200
