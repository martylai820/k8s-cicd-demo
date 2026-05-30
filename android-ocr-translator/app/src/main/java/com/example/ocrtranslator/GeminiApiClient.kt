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

/**
 * Client for the Google Gemini multimodal API.
 * Sends a screenshot bitmap and receives OCR + translation text.
 */
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

    /**
     * Result type returned by [ocrAndTranslate].
     */
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Sends [bitmap] to Gemini for OCR and translation into [targetLanguage].
     * Must be called from a coroutine; network I/O runs on [Dispatchers.IO].
     */
    suspend fun ocrAndTranslate(
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

            val requestBody = requestJson
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            Log.d(TAG, "Sending request to Gemini API for language: $targetLanguage")

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

            val translatedText = parseSuccessResponse(responseBody)
            if (translatedText.isNullOrBlank()) {
                return@withContext Result.Error("No text found in the API response.")
            }

            Log.d(TAG, "Translation successful, text length: ${translatedText.length}")
            Result.Success(translatedText)

        } catch (e: IOException) {
            Log.e(TAG, "Network error", e)
            Result.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            Result.Error("Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Compresses [bitmap] to JPEG and encodes it as Base64.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Builds the JSON request body for the Gemini multimodal API.
     */
    private fun buildRequestJson(base64Image: String, targetLanguage: String): String {
        val prompt = buildString {
            append("Please perform OCR on this image to extract all visible text, ")
            append("then translate the extracted text to $targetLanguage. ")
            append("Format your response as follows:\n")
            append("--- Original Text ---\n")
            append("[the extracted original text here]\n\n")
            append("--- $targetLanguage Translation ---\n")
            append("[the translated text here]\n\n")
            append("If there is no text in the image, respond with: 'No text detected in the image.' ")
            append("Keep the formatting clean and readable.")
        }

        val inlineData = JsonObject().apply {
            addProperty("mime_type", "image/jpeg")
            addProperty("data", base64Image)
        }

        val imagePart = JsonObject().apply {
            add("inline_data", inlineData)
        }

        val textPart = JsonObject().apply {
            addProperty("text", prompt)
        }

        val partsArray = com.google.gson.JsonArray().apply {
            add(imagePart)
            add(textPart)
        }

        val content = JsonObject().apply {
            add("parts", partsArray)
        }

        val contentsArray = com.google.gson.JsonArray().apply {
            add(content)
        }

        // Safety settings to minimize refusals on benign screenshot content
        val safetySettings = com.google.gson.JsonArray().apply {
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
            ).forEach { category ->
                add(JsonObject().apply {
                    addProperty("category", category)
                    addProperty("threshold", "BLOCK_ONLY_HIGH")
                })
            }
        }

        val generationConfig = JsonObject().apply {
            addProperty("temperature", 0.1)
            addProperty("maxOutputTokens", 2048)
        }

        val root = JsonObject().apply {
            add("contents", contentsArray)
            add("safetySettings", safetySettings)
            add("generationConfig", generationConfig)
        }

        return gson.toJson(root)
    }

    /**
     * Extracts the translated text from a successful Gemini API response JSON.
     * Returns null if the expected structure is missing.
     */
    private fun parseSuccessResponse(responseBody: String): String? {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            val candidates = root.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) return null

            val firstCandidate = candidates[0].asJsonObject
            val content = firstCandidate.getAsJsonObject("content") ?: return null
            val parts = content.getAsJsonArray("parts") ?: return null
            if (parts.size() == 0) return null

            parts[0].asJsonObject.get("text")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse success response", e)
            null
        }
    }

    /**
     * Extracts an error message from a failed Gemini API response JSON.
     */
    private fun parseErrorMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) return "Unknown error"
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            val error = root.getAsJsonObject("error")
            error?.get("message")?.asString ?: responseBody.take(200)
        } catch (e: Exception) {
            responseBody.take(200)
        }
    }
}
