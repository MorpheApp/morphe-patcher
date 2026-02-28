package app.morphe.patcher.patch

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertIs

class SerializationTest {
    private val testPatch = bytecodePatch("Test patch") {
        compatibleWith("com.example.package"("1.0.0"))
        compatibleWith("com.example.package2")

        dependsOn(bytecodePatch(), bytecodePatch())

        stringOption("key1", null, null, "title1", "description1")
        booleanOption("key2", true, null, "title2", "description2")
        floatsOption("key3", listOf(1.0f), mapOf("list" to listOf(1f)), "title3", "description3")
    }

    private var patches = setOf(testPatch)

    @Test
    fun `serializes and deserializes`() {
        val serializedJson = ByteArrayOutputStream().apply { patches.serializeTo(this) }.toString()
        val deserializedJson = Json.parseToJsonElement(serializedJson)

        // Test patch serialization.

        assertIs<JsonArray>(deserializedJson)

        val deserializedPatch = deserializedJson[0].jsonObject

        assert(deserializedPatch["name"]!!.jsonPrimitive.content == "Test patch")

        assert(deserializedPatch["compatiblePackages"]!!.jsonObject.size == 2) {
            "The patch should be compatible with two packages."
        }

        assert(deserializedPatch["dependencies"]!!.jsonArray.size == 2) {
            "Even though the dependencies are named the same, they are different objects."
        }

        // Test option serialization.

        val options = deserializedPatch["options"]!!.jsonArray

        assert(options.size == 3) { "The patch should have three options." }

        assert(options[0].jsonObject["title"]!!.jsonPrimitive.content == "title1")
        assert(options[0].jsonObject["default"]!!.jsonPrimitive.contentOrNull == null)
        assert(options[1].jsonObject["default"]!!.jsonPrimitive.boolean)
        assert(options[2].jsonObject["values"]!!.jsonObject["list"]!!.jsonArray[0].jsonPrimitive.float == 1f)
    }
}