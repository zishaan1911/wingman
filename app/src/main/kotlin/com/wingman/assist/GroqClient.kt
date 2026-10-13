package com.wingman.assist

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for Groq's OpenAI-compatible Chat Completions endpoint.
 * Handles both plain-text calls (messaging) and image calls (swipe vision).
 *
 * Key handling: for a quick personal build the key comes from BuildConfig
 * (set via local.properties). The cleaner pattern is to point ENDPOINT at your
 * own tiny proxy that holds the key, so it never ships in the APK — this class
 * is written so you only change ENDPOINT + drop the Authorization header.
 *
 * NOTE: model IDs change on Groq's side. Verify current IDs in the Groq console;
 * these are sensible defaults at time of writing. Swiping needs a *vision* model.
 */
class GroqClient(private val apiKey: String) {

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        // Text model for messaging/strategy. Must be one your Groq account can access
        // (check https://console.groq.com/ → Models). gpt-oss-120b is a solid default.
        const val TEXT_MODEL = "openai/gpt-oss-120b"

        // Vision model for swiping / photo audit / photo-based openers. Leave BLANK if your
        // account has no image-capable model — those features then disable gracefully. Set it
        // to a multimodal model id (e.g. a llama-4 vision model) once you have access.
        const val VISION_MODEL = ""

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** True if a vision model is configured (image features can run). */
    fun hasVision(): Boolean = VISION_MODEL.isNotBlank()

    /** Plain text completion. Returns the assistant message content. */
    fun complete(
        systemPrompt: String,
        userPrompt: String,
        model: String = TEXT_MODEL,
        temperature: Double = 0.8,
        maxTokens: Int = 300,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        return call(model, messages, temperature, maxTokens)
    }

    /**
     * Vision completion: a text instruction + one or more images (as JPEG bytes).
     * Images are sent inline as base64 data URLs (OpenAI vision content format).
     */
    fun completeWithImages(
        systemPrompt: String,
        userPrompt: String,
        images: List<ByteArray>,
        model: String = VISION_MODEL,
        temperature: Double = 0.4,
        maxTokens: Int = 300,
    ): String {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", userPrompt)
        )
        for (img in images) {
            val b64 = android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP)
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", content))
        return call(model, messages, temperature, maxTokens)
    }

    private fun call(model: String, messages: JSONArray, temperature: Double, maxTokens: Int): String {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
        // gpt-oss models reason before answering; keep it minimal so the short answer isn't
        // starved of tokens (reasoning counts toward max_tokens).
        if (model.contains("gpt-oss")) payload.put("reasoning_effort", "low")
        val body = payload.toString().toRequestBody(JSON)

        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Groq ${resp.code}: $raw")
            }
            return JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }
}
