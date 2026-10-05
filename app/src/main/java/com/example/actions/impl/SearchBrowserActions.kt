package com.example.actions.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class WebSearchAction(
    private val client: OkHttpClient,
    private val context: Context
) : Action {

    override val name: String = "web_search"
    override val description: String = "Performs multi-mode web intelligence searches: news, research, price, compare, or general search."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "query" to ParamDefinition("string", "Search query or question", required = true),
        "mode" to ParamDefinition(
            "string",
            "Search mode: 'search', 'news', 'research', 'price', or 'compare'",
            required = false,
            enumValues = listOf("search", "news", "research", "price", "compare")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult = withContext(Dispatchers.IO) {
        val query = (args["query"] as? String)?.trim() ?: return@withContext ActionResult("Query required.", isError = true)
        val mode = (args["mode"] as? String)?.lowercase() ?: "search"

        try {
            // DuckDuckGo Instant Answer API
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)

            val abstractText = json.optString("AbstractText", "")
            val heading = json.optString("Heading", query)
            val sourceUrl = json.optString("AbstractURL", "")

            if (abstractText.isNotEmpty()) {
                val spoken = when (mode) {
                    "news" -> "Latest intelligence on $heading: $abstractText"
                    "research" -> "Research findings indicate: $abstractText"
                    "price" -> "Market data for $heading: $abstractText"
                    "compare" -> "Comparative analysis: $abstractText"
                    else -> "$heading: $abstractText"
                }
                return@withContext ActionResult(
                    spokenResult = spoken,
                    cardData = mapOf("title" to heading, "snippet" to abstractText, "url" to sourceUrl, "mode" to mode)
                )
            }

            // Fallback: check RelatedTopics
            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null && relatedTopics.length() > 0) {
                val firstTopic = relatedTopics.optJSONObject(0)
                val text = firstTopic?.optString("Text", "") ?: ""
                if (text.isNotEmpty()) {
                    return@withContext ActionResult(
                        spokenResult = text,
                        cardData = mapOf("title" to query, "snippet" to text, "mode" to mode)
                    )
                }
            }

            // If direct abstract not found, provide synthesized query guidance & option to open
            val browserUrl = "https://html.duckduckgo.com/html/?q=$encoded"
            ActionResult(
                spokenResult = "Searched the web for '$query' in $mode mode. Displaying web results in the HUD, Sir.",
                cardData = mapOf("title" to query, "url" to browserUrl, "mode" to mode)
            )
        } catch (e: Exception) {
            ActionResult("Search index unreachable: ${e.message}", isError = true)
        }
    }
}

class BrowserControlAction(private val context: Context) : Action {

    override val name: String = "open_browser"
    override val description: String = "Navigates to a specific URL or web page in the default web browser."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "url" to ParamDefinition("string", "The URL or web address to load", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        var url = (args["url"] as? String)?.trim() ?: return ActionResult("URL required.", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ActionResult("Accessing $url, Sir.")
        } catch (e: Exception) {
            ActionResult("Could not open browser for $url: ${e.message}", isError = true)
        }
    }
}
