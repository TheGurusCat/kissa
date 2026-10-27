package com.theguruscat.kissa.data

import org.json.JSONArray
import org.json.JSONObject

data class Comment(val id: String, val author: String, val body: String, val createdAt: Long)

data class UserProfile(val username: String, val joinedAt: Long, val stories: List<RedditPost>)

/** An open report, as the Moderation screen shows it. [targetId] is a story or comment ID without "k_". */
data class Report(
    val id: Long,
    val kind: String,
    val targetId: String,
    val storyId: String,
    val reason: String,
    val reporter: String,
    val content: String?,
    val author: String?,
    val authorBanned: Boolean,
    val createdAt: Long,
    val link: String,
) {
    val isStory: Boolean get() = kind == "story"
}

/**
 * Kissa's own stories through the relay (see relay/src/stories.js): reading, writing, upvotes, comments,
 * profiles, reports and, for admins, moderation. Story IDs are card IDs like "k_abc123defg".
 */
class StoriesRepository(private val account: AccountRepository) {

    /** Up to [limit] stories published before [before] (epoch ms), newest first. */
    suspend fun fetch(before: Long? = null, limit: Int = 50): List<RedditPost> {
        val query = "?limit=$limit" + (before?.let { "&before=$it" } ?: "")
        return posts(account.call("GET", "/stories$query", null).getJSONArray("stories"))
    }

    /** Publishes a new story, or edits [id] when given. */
    suspend fun save(id: String?, title: String, body: String): RedditPost {
        val json = JSONObject().put("title", title).put("body", body)
        val result = if (id == null) account.call("POST", "/stories", json)
        else account.call("PUT", "/stories/${bare(id)}", json)
        return RedditPost.fromJson(result.getJSONObject("story"))
    }

    suspend fun delete(id: String) {
        account.call("DELETE", "/stories/${bare(id)}", null)
    }

    /** Upvotes [id], or takes the vote back; returns the new score. */
    suspend fun vote(id: String, up: Boolean): Int =
        account.call(if (up) "POST" else "DELETE", "/stories/${bare(id)}/vote", null).optInt("score")

    suspend fun comments(storyId: String): List<Comment> {
        val array = account.call("GET", "/stories/${bare(storyId)}/comments", null).getJSONArray("comments")
        return List(array.length()) { comment(array.getJSONObject(it)) }
    }

    suspend fun addComment(storyId: String, body: String): Comment =
        comment(account.call("POST", "/stories/${bare(storyId)}/comments", JSONObject().put("body", body)).getJSONObject("comment"))

    suspend fun deleteComment(id: String) {
        account.call("DELETE", "/comments/$id", null)
    }

    suspend fun profile(username: String): UserProfile {
        val json = account.call("GET", "/u/$username", null)
        return UserProfile(json.getString("username"), json.optLong("joinedAt"), posts(json.getJSONArray("stories")))
    }

    /** Reports a story (a card ID) or a comment to the admins. */
    suspend fun report(isStory: Boolean, id: String, reason: String) {
        val json = JSONObject().put("kind", if (isStory) "story" else "comment").put("id", bare(id)).put("reason", reason)
        account.call("POST", "/reports", json)
    }

    suspend fun openReports(): List<Report> {
        val array = account.call("GET", "/admin/reports", null).getJSONArray("reports")
        return List(array.length()) { i ->
            val json = array.getJSONObject(i)
            Report(
                id = json.getLong("id"),
                kind = json.getString("kind"),
                targetId = json.getString("targetId"),
                storyId = json.getString("storyId"),
                reason = json.optString("reason"),
                reporter = json.optString("reporter"),
                content = json.optString("content").takeUnless { json.isNull("content") },
                author = json.optString("author").takeUnless { json.isNull("author") },
                authorBanned = json.optBoolean("authorBanned"),
                createdAt = json.optLong("createdAt"),
                link = json.optString("link"),
            )
        }
    }

    suspend fun dismissReport(id: Long) {
        account.call("POST", "/admin/reports/$id/dismiss", null)
    }

    suspend fun setBanned(username: String, banned: Boolean) {
        account.call("POST", "/admin/users/$username/ban", JSONObject().put("banned", banned))
    }

    private fun posts(array: JSONArray) = List(array.length()) { RedditPost.fromJson(array.getJSONObject(it)) }

    private fun comment(json: JSONObject) =
        Comment(json.getString("id"), json.optString("author"), json.getString("body"), json.optLong("createdAt"))

    private fun bare(id: String) = id.removePrefix("k_")
}
