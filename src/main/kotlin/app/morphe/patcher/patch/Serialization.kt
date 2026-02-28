package app.morphe.patcher.patch

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import java.io.OutputStream

private class PatchSerializer : KSerializer<Patch<*>> {
    override val descriptor = buildClassSerialDescriptor("Patch") {
        element<String?>("name")
        element<String?>("description")
        element<Boolean>("use")
        element<List<String>>("dependencies")
        element<Map<PackageName, Set<VersionName>?>?>("compatiblePackages")
        element("options", OptionSerializer.descriptor)
    }

    override fun deserialize(decoder: Decoder) = throw NotImplementedError("Deserialization is unsupported")

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Patch<*>) {
        encoder.encodeStructure(descriptor) {
            encodeNullableSerializableElement(
                descriptor,
                0,
                String.serializer(),
                value.name,
            )
            encodeNullableSerializableElement(
                descriptor,
                1,
                String.serializer(),
                value.description,
            )
            encodeBooleanElement(
                descriptor,
                2,
                value.use,
            )
            encodeSerializableElement(
                descriptor,
                3,
                ListSerializer(String.serializer()),
                value.dependencies.map { it.name ?: it.toString() },
            )
            encodeNullableSerializableElement(
                descriptor,
                4,
                MapSerializer(String.serializer(), SetSerializer(String.serializer()).nullable),
                value.compatiblePackages?.associate { (packageName, versions) -> packageName to versions },
            )
            encodeSerializableElement(
                descriptor,
                5,
                SetSerializer(OptionSerializer),
                value.options.values.toSet(),
            )
        }
    }

    private object OptionSerializer : KSerializer<Option<*>> {
        override val descriptor = buildClassSerialDescriptor("Option") {
            element<String>("key")
            element<String?>("title")
            element<String?>("description")
            element<Boolean>("required")
            // Type does not matter for serialization. Using String.
            element<String>("type")
            element<String?>("default")
            // Map value type does not matter for serialization. Using String.
            element<Map<String, String?>?>("values")
        }

        override fun deserialize(decoder: Decoder) = throw NotImplementedError("Deserialization is unsupported")

        @OptIn(ExperimentalSerializationApi::class)
        override fun serialize(encoder: Encoder, value: Option<*>) {
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, value.key)
                encodeNullableSerializableElement(descriptor, 1, String.serializer(), value.title)
                encodeNullableSerializableElement(descriptor, 2, String.serializer(), value.description)
                encodeBooleanElement(descriptor, 3, value.required)
                encodeSerializableElement(descriptor, 4, String.serializer(), value.type.toString())
                encodeNullableSerializableElement(descriptor, 5, serializer(value.type), value.default)
                encodeNullableSerializableElement(descriptor, 6, MapSerializer(String.serializer(), serializer(value.type)), value.values)
            }
        }
    }
}

private val patchPrettySerializer by lazy { Json { prettyPrint = true } }
private val patchSerializer by lazy { Json }

/**
 * Serialize this set of [Patch] to JSON and write it to the given [outputStream].
 *
 * @param outputStream The output stream to write the JSON to.
 * @param prettyPrint Whether to pretty print the JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
fun Set<Patch<*>>.serializeTo(
    outputStream: OutputStream,
    prettyPrint: Boolean = true,
) = if (prettyPrint) {
    patchPrettySerializer
} else {
    patchSerializer
}.encodeToStream(SetSerializer(PatchSerializer()), this, outputStream)
