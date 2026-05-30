package com.example.ocrtranslator

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TextBlock(
    val original: String,
    val translation: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

class GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private const val JPEG_QUALITY = 85
        private const val TIMEOUT_SECONDS = 60L
    }

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Success(val blocks: List<TextBlock>) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    suspend fun ocrTranslatePositional(
        bitmap: Bitmap,
        apiKey: String,
        targetLanguage: String
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.Error("API key is not configured.")
            }

            val base64Image = bitmapToBase64(bitmap)
            val requestJson = buildRequestJson(base64Image, targetLanguage)
            val url = "$BASE_URL?key=$apiKey"

            val requestBody = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending positional OCR request for language: $targetLanguage")

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(responseBody)
                Log.e(TAG, "API error ${response.code}: $errorMsg")
                return@withContext Result.Error("API error (${response.code}): $errorMsg")
            }

            if (responseBody.isNullOrBlank()) {
                return@withContext Result.Error("Empty response from API.")
            }

            val blocks = parsePositionalResponse(responseBody)
            Log.d(TAG, "Parsed ${blocks.size} text blocks from response")
            Result.Success(blocks)

        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
            Result.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            Result.Error("Unexpected error: ${e.message}", e)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildRequestJson(base64Image: String, targetLanguage: String): String {
        val prompt = """
            You are an OCR and translation assistant. Analyze this screenshot image.
            Find ALL visible text blocks on screen and translate EACH ONE into $targetLanguage.
            You MUST output the translation field in $targetLanguage — do not use any other language.

            Return ONLY a valid JSON array — no markdown, no code fences, no extra text:
            [{"original":"text here","translation":"translated text","x":0.10,"y":0.05,"w":0.50,"h":0.04}]

            Field definitions (all fractions of image dimensions, range 0.0–1.0):
              x = left edge of the text block
              y = top edge of the text block
              w = width of the text block
              h = height of the text block

            Group text on the same line/sentence into a single block.
            If no text is visible, return an empty array: []
        """.trimIndent()

        val inlineData = JsonObject().apply {
            addProperty("mime_type", "image/jpeg")
            addProperty("data", base64Image)
        }
        val imagePart = JsonObject().apply { add("inline_data", inlineData) }
        val textPart = JsonObject().apply { addProperty("text", prompt) }

        val parts = com.google.gson.JsonArray().apply { add(imagePart); add(textPart) }
        val content = JsonObject().apply { add("parts", parts) }
        val contents = com.google.gson.JsonArray().apply { add(content) }

        val safetySettings = com.google.gson.JsonArray().apply {
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { cat ->
                add(JsonObject().apply {
                    addProperty("category", cat)
                    addProperty("threshold", "BLOCK_ONLY_HIGH")
                })
            }
        }

        val generationConfig = JsonObject().apply {
            addProperty("temperature", 0.1)
            addProperty("maxOutputTokens", 4096)
        }

        return gson.toJson(JsonObject().apply {
            add("contents", contents)
            add("safetySettings", safetySettings)
            add("generationConfig", generationConfig)
        })
    }

    private fun parsePositionalResponse(responseBody: String): List<TextBlock> {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            val candidates = root.getAsJsonArray("candidates") ?: return emptyList()
            if (candidates.size() == 0) return emptyList()

            val rawText = candidates[0].asJsonObject
                .getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.get(0)?.asJsonObject
                ?.get("text")?.asString ?: return emptyList()

            // Strip markdown code fences Gemini sometimes adds despite instructions
            val cleaned = rawText.trim()
                .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("\\s*```\\s*$"), "")
                .trim()

            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()

            JsonParser.parseString(cleaned.substring(start, end + 1)).asJsonArray
                .mapNotNull { element ->
                    try {
                        val obj = element.asJsonObject
                        TextBlock(
                            original = obj.get("original")?.asString ?: return@mapNotNull null,
                            translation = obj.get("translation")?.asString ?: return@mapNotNull null,
                            x = obj.get("x")?.asFloat ?: return@mapNotNull null,
                            y = obj.get("y")?.asFloat ?: return@mapNotNull null,
                            w = obj.get("w")?.asFloat ?: return@mapNotNull null,
                            h = obj.get("h")?.asFloat ?: return@mapNotNull null
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed block: $element", e)
                        null
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse positional response", e)
            emptyList()
        }
    }

    private fun parseErrorMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) return "Unknown error"
        return try {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonObject("error")
                ?.get("message")?.asString ?: responseBody.take(200)
        } catch (e: Exception) {
            responseBody.take(200)
        }
    }
}
