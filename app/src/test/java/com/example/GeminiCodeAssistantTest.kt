package com.example

import com.example.ai.ChatMessage
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiCodeAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiCodeAssistantTest {

    @Test
    fun usesGemini4HydrogenModel() {
        assertEquals("gemini-4-hydrogen", GEMINI_MODEL_ID)
    }

    @Test
    fun parseSsePayload_skipsThoughtPartsAndExtractsText() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {"text": "internal thinking...", "thought": true},
                      {"text": "Here is the updated code:\n"}
                    ]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val parsed = GeminiCodeAssistant.parseSsePayload(json)
        assertNull(parsed.errorMessage)
        assertEquals("STOP", parsed.finishReason)
        assertEquals("Here is the updated code:\n", parsed.text)
    }

    @Test
    fun extractCodeBlocks_parsesPathHintAndStripsHeader() {
        val markdown = """
            Updated MainActivity:
            ```java
            // file: src/com/example/counter/MainActivity.java
            package com.example.counter;
            public class MainActivity {}
            ```
        """.trimIndent()

        val blocks = GeminiCodeAssistant.extractCodeBlocks(markdown)
        assertEquals(1, blocks.size)
        assertEquals("java", blocks[0].language)
        assertEquals("src/com/example/counter/MainActivity.java", blocks[0].suggestedPath)
        assertEquals("package com.example.counter;\npublic class MainActivity {}", blocks[0].code)
    }

    @Test
    fun buildRequestPayload_includesHistoryAndLatestPrompt() {
        val assistant = GeminiCodeAssistant()
        val payload = assistant.buildRequestPayload(
            history = listOf(
                ChatMessage(id = "u1", isUser = true, text = "Hello"),
                ChatMessage(id = "a1", isUser = false, text = "Hi there"),
            ),
            latestPromptWithContext = "Add a reset button",
        )
        assertEquals(true, payload.contains("\"role\":\"user\""))
        assertEquals(true, payload.contains("\"role\":\"model\""))
        assertEquals(true, payload.contains("Add a reset button"))
    }
}
