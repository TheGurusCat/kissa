package com.theguruscat.kissa.data

import org.json.JSONObject

/**
 * Kissa's own stories through the relay (see relay/src/stories.js). Anyone can read them; posting, editing
 * and deleting need an author account.
 */
class StoriesRepository(private val account: AccountRepository) {

    /** Up to [limit] stories published before [before] (epoch ms), newest first. */
    suspend fun fetch(before: Long? = null, limit: Int = 50): List<RedditPost> {
        val query = "?limit=$limit" + (before?.let { "&before=$it" } ?: "")
        val stories = account.call("GET", "/stories$query", null).getJSONArray("stories")
        return List(stories.length()) { RedditPost.fromJson(stories.getJSONObject(it)) }
    }

    /** Publishes a new story, or edits [id] (a card ID like "k_abc123defg") when given. */
    suspend fun save(id: String?, title: String, body: String): RedditPost {
        val json = JSONObject().put("title", title).put("body", body)
        val result = if (id == null) account.call("POST", "/stories", json)
        else account.call("PUT", "/stories/${id.removePrefix(ID_PREFIX)}", json)
        return RedditPost.fromJson(result.getJSONObject("story"))
    }

    suspend fun delete(id: String) {
        account.call("DELETE", "/stories/${id.removePrefix(ID_PREFIX)}", null)
    }

    private companion object {
        const val ID_PREFIX = "k_"
    }
}
