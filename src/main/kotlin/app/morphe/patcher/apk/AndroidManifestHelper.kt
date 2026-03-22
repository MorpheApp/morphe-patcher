/*
 *  Copyright (C) 2022 github.com/REAndroid
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// https://github.com/REAndroid/APKEditor/blob/eafe39f9fc81cd5eed8bf3ef72defe1bfa75cf2a/src/main/java/com/reandroid/apkeditor/common/AndroidManifestHelper.java
package app.morphe.patcher.apk

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkUtil
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.reandroid.utils.HexUtil
import com.reandroid.utils.collection.CollectionUtil
import java.util.function.Predicate

internal object AndroidManifestHelper {
    fun listSplitRequired(parentElement: ResXmlElement): MutableList<ResXmlElement> {
        return CollectionUtil.toList(parentElement.getElements(Predicate { element: ResXmlElement ->
            if (!element.equalsName(AndroidManifest.TAG_meta_data)) {
                return@Predicate false
            }
            val nameAttribute = CollectionUtil.getFirst(
                element
                    .getAttributes(NAME_FILTER)
            )
            if (nameAttribute == null) {
                return@Predicate false
            }
            val value = nameAttribute.getValueAsString() ?: return@Predicate false
            if (value == "com.android.dynamic.apk.fused.modules") {
                val attribute = element.searchAttributeByResourceId(AndroidManifest.ID_value)
                if (attribute != null) {
                    val attributeValue = attribute.getValueAsString()
                    if (attributeValue != null && attributeValue == ApkUtil.DEF_MODULE_NAME) {
                        return@Predicate true
                    }
                }
            }
            value.startsWith("com.android.vending.")
                    || value.startsWith("com.android.stamp.")
        }))
    }

    fun removeAttributeFromManifestByName(
        androidManifestBlock: AndroidManifestBlock,
        resourceName: String?, logger: APKLogger?
    ) {
        val manifestElement = androidManifestBlock.manifestElement
        if (manifestElement == null) {
            logger?.logMessage("WARN: AndroidManifest don't have <manifest>")
            return
        }
        val removed = manifestElement.removeAttributesWithName(resourceName)
        if (removed) {
            logger?.logMessage("Removed-attribute : $resourceName")
        }
    }

    fun removeAttributeFromManifestById(
        androidManifestBlock: AndroidManifestBlock,
        resourceId: Int, logger: APKLogger?
    ) {
        val manifestElement = androidManifestBlock.manifestElement
        if (manifestElement == null) {
            logger?.logMessage("WARN: AndroidManifest don't have <manifest>")
            return
        }
        val removed = manifestElement.removeAttributesWithId(resourceId)
        if (removed) {
            logger?.logMessage("Removed-attribute : " + HexUtil.toHex8("@0x", resourceId))
        }
    }


    fun removeAttributeFromManifestAndApplication(
        androidManifestBlock: AndroidManifestBlock,
        resourceId: Int, logger: APKLogger?, nameForLogging: String?
    ) {
        if (resourceId == 0) {
            return
        }
        val manifestElement = androidManifestBlock.manifestElement
        if (manifestElement == null) {
            logger?.logMessage("WARN: AndroidManifest don't have <manifest>")
            return
        }
        var removed = manifestElement.removeAttributesWithId(resourceId)
        val applicationElement = manifestElement.getElement(
            AndroidManifest.TAG_application
        )

        if (removed) {
            logger?.logMessage(
                ("Attributes on <manifest> removed: "
                        + HexUtil.toHex8("0x", resourceId) + " (" + nameForLogging + ")")
            )
        }
        if (applicationElement == null) {
            return
        }
        removed = applicationElement.removeAttributesWithId(resourceId)
        if (removed) {
            logger?.logMessage(
                ("Attributes on <application> removed: "
                        + HexUtil.toHex8("0x", resourceId) + " (" + nameForLogging + ")")
            )
        }
    }

    fun isNameResourceId(attribute: ResXmlAttribute): Boolean {
        val resourceId = attribute.getNameId()
        return resourceId == AndroidManifest.ID_name
    }

    val NAME_FILTER: Predicate<ResXmlAttribute?> = Predicate { attribute: ResXmlAttribute? ->
        if (!isNameResourceId(attribute!!)) {
            return@Predicate false
        }
        attribute.valueType == ValueType.STRING
    }
}