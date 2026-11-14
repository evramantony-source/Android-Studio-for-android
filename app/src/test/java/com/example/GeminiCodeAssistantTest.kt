package com.example

import com.example.ai.ChatMessage
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiCodeAssistant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun parseSsePayload_preservesFunctionCallAndThoughtSignature() {
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "functionCall": {
                          "name": "read_file",
                          "args": {"path": "AndroidManifest.xml"}
                        },
                        "thoughtSignature": "sig-xyz-123"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = GeminiCodeAssistant.parseSsePayload(json)
        val assembled = mutableListOf<JSONObject>()
        parsed.rawParts.forEach { GeminiCodeAssistant.appendRawPart(assembled, it) }

        assertEquals(1, assembled.size)
        assertEquals("sig-xyz-123", assembled[0].getString("thoughtSignature"))
        assertEquals("read_file", assembled[0].getJSONObject("functionCall").getString("name"))
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
    fun buildRequestPayload_includesHistoryToolsAndLatestPrompt() {
        val assistant = GeminiCodeAssistant()
        val contents = assistant.buildInitialContents(
            history = listOf(
                ChatMessage(id = "u1", isUser = true, text = "Hello"),
                ChatMessage(id = "a1", isUser = false, text = "Hi there"),
            ),
            latestPromptWithContext = "Add a reset button",
        )
        val payload = assistant.buildRequestPayload(contents)
        assertTrue(payload.contains("\"role\":\"user\""))
        assertTrue(payload.contains("\"role\":\"model\""))
        assertTrue(payload.contains("Add a reset button"))
        assertTrue(payload.contains("\"read_file\""))
        assertTrue(payload.contains("\"write_file\""))
        assertTrue(payload.contains("\"edit_file\""))
        assertTrue(payload.contains("\"build_apk\""))
    }
}
