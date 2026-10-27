package com.theguruscat.kissa.data

import org.json.JSONObject

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
    /** When the article was published, in epoch milliseconds; 0 for Reddit posts and unknown dates. */
    val publishedAt: Long = 0L,
    /** Written for Kissa and posted through the relay (the Stories tab), not from Reddit or a news site. */
    val isOriginal: Boolean = false,
) {
    val url: String get() = if (isNews || isOriginal) permalink else "https://www.reddit.com$permalink"

    /** Where the card came from, as shown above it: "r/tifu", "BBC News" or "Kissa". */
    val sourceLabel: String get() = if (isNews || isOriginal) subreddit else "r/$subreddit"

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

    /** Everything that marks this card as already read: its ID and, when there is one, its [titleKey]. */
    val seenKeys: List<String> get() = listOfNotNull(id, titleKey)

    /** A news article published more than [NEWS_MAX_AGE_MS] ago; articles without a date never go stale. */
    fun isStaleNews(now: Long = System.currentTimeMillis()) =
        isNews && publishedAt > 0L && now - publishedAt > NEWS_MAX_AGE_MS

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
        )
    }
}
