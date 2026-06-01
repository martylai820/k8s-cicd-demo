package com.example.ocrtranslator

data class TextBlock(
    val original: String,
    val translation: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

sealed class OcrResult {
    data class Success(val blocks: List<TextBlock>) : OcrResult()
    data class Failure(val message: String, val cause: Throwable? = null) : OcrResult()
}
