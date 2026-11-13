package com.dc.andagent.llama

internal object LlamaNative {
    init { System.loadLibrary("llama_jni") }
    external fun load(path: String, threads: Int): String
    external fun generate(prompt: String, maxTokens: Int, temperature: Float): String?
    external fun generatedTokens(): Int
    external fun cancel()
    external fun resetCancellation()
    external fun unload()
}
