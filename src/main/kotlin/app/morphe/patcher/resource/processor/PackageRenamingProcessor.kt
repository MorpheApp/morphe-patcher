/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.inOrderTraverse
import app.morphe.patcher.util.Document
import com.reandroid.json.JSONObject
import org.w3c.dom.Attr
import java.io.File
import java.util.logging.Logger
import kotlin.collections.component1
import kotlin.collections.component2

internal class PackageRenamingProcessor(
    internal val get: (String, String) -> File,
    internal val publicXmlManager: PublicXmlManager,
    internal val packageDirectories: Map<String, File>,
    internal val originalPackageName: String,
    internal val newPackageName: String
) {
    private val logger = Logger.getLogger(PackageRenamingProcessor::class.java.name)

    private val regex = Regex("^[@?]$originalPackageName:.*")

    fun process() {
        if (originalPackageName == newPackageName) {
            return
        }

        logger.info("Post-processing package name change")

        publicXmlManager.getPublicNode().setAttribute("package", newPackageName)

        get("package.json", originalPackageName).apply {
            val packageJson = JSONObject(this)
            packageJson.put("package_name", newPackageName)
            packageJson.write(this)
        }

        // We should only need to fix references to the original package in other package resource bundles.
        packageDirectories.filter { it.key != originalPackageName }.forEach { (resPackageName, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                dir.listFiles { it.extension == "xml" && it.name != "strings.xml" }?.forEach { file ->
                    Document(get("res/${dir.name}/${file.name}", resPackageName)).use { doc ->
                        doc.inOrderTraverse { element ->
                            for (i in 0 until element.attributes.length) {
                                val attr = element.attributes.item(i) as Attr
                                val attrMatch = regex.matchEntire(attr.value)
                                if (attrMatch != null) {
                                    attr.value = attr.value.replace(originalPackageName, newPackageName)
                                }
                            }

                            val textContentMatch = regex.matchEntire(element.textContent)
                            if (textContentMatch != null) {
                                element.textContent = element.textContent.replace(originalPackageName, newPackageName)
                            }
                        }
                    }
                }
            }
        }
    }
}