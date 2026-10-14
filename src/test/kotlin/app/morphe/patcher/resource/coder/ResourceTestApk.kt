/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.value.ResConfig
import com.reandroid.xml.XMLFactory
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A small APK with the kinds of resources apps ship, built in code.
 */
internal object ResourceTestApk {
    fun build(dir: File): File {
        val packageName = "com.test.app"
        // The coder under test installs an aapt-style string decoder on this singleton, which
        // would otherwise leak into the fixture of the next test.
        XmlCoder.getInstance().setting = CoderSetting()

        val table = TableBlock()
        table.addFramework(AndroidFrameworks.getLatest().tableBlock)
        val pkg = table.newPackage(0x7f, packageName)

        fun declare(type: String, vararg names: String) = names.forEach { pkg.getOrCreate("", type, it) }
        declare("attr", "myAttr")
        declare("string", "app_name", "hello", "keep", "styled", "escaped")
        declare("color", "accent", "selector", "shared_a", "shared_b")
        declare("style", "S")
        declare("array", "arr")
        declare("layout", "main")
        declare("drawable", "icon", "sparse_a", "sparse_b", "sparse_c")
        declare("id", "existing_view")

        fun values(qualifiers: String, type: String, xml: String) {
            val typeBlock = pkg.getOrCreateTypeBlock(qualifiers, type)
            XmlCoder.getInstance().VALUES_XML.encode(XMLFactory.newPullParser("<resources>$xml</resources>"), typeBlock)
        }
        values("", "attr", """<attr name="myAttr" format="reference|color"/>""")
        values(
            "", "string",
            """<string name="app_name">Test</string>
               <string name="hello">Hello</string>
               <string name="keep">Keep</string>
               <string name="styled">A <b>bold</b> word</string>
               <string name="escaped">It's "quoted"</string>"""
        )
        values("-de", "string", """<string name="hello">Hallo</string><string name="keep">Behalten</string>""")
        values("", "color", """<color name="accent">#ffff0000</color>""")
        values("", "style", """<style name="S"><item name="myAttr">@color/accent</item></style>""")
        values("", "array", """<string-array name="arr"><item>one</item><item>two</item></string-array>""")
        pkg.getOrCreate("", "id", "existing_view").setValueAsBoolean(false)
        pkg.getOrCreate("", "layout", "main").setValueAsString("res/layout/main.xml")
        pkg.getOrCreate("", "drawable", "icon").setValueAsString("res/drawable/icon.png")
        pkg.getOrCreate("-xxhdpi", "drawable", "icon").setValueAsString("res/drawable-xxhdpi/icon.png")
        // A sparse configuration, as apps built with aapt2 --enable-sparse-encoding ship them.
        pkg.getOrCreate("-hdpi", "drawable", "sparse_a").setValueAsString("res/drawable-hdpi/sparse_a.png")
        pkg.getOrCreate("-hdpi", "drawable", "sparse_b").setValueAsString("res/drawable-hdpi/sparse_b.png")
        pkg.getOrCreate("-hdpi", "drawable", "sparse_c").setValueAsString("res/drawable-hdpi/sparse_c.png")
        pkg.getOrCreateSpecTypePair("drawable").getTypeBlock(ResConfig.parse("-hdpi"))!!.headerBlock.isSparse = true
        pkg.getOrCreate("", "color", "selector").setValueAsString("res/color/selector.xml")
        // aapt shares one file between resources with the same content, under an obfuscated name.
        pkg.getOrCreate("", "color", "shared_a").setValueAsString("res/color/x.xml")
        pkg.getOrCreate("", "color", "shared_b").setValueAsString("res/color/x.xml")
        table.refresh()

        fun binaryXml(xml: String) = ResXmlDocument().apply {
            setPackageBlock(pkg)
            parse(XMLFactory.newPullParser(xml))
            refreshFull()
        }.bytes

        val layout = binaryXml(
            """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                   android:id="@id/existing_view" android:text="@string/hello"/>"""
        )
        val selector = binaryXml(
            """<selector xmlns:android="http://schemas.android.com/apk/res/android">
                   <item android:color="@color/accent"/>
               </selector>"""
        )

        val manifest = AndroidManifestBlock().apply {
            setPackageName(packageName)
            setVersionCode(1)
            setVersionName("1.0")
            setMinSdkVersion(26)
            setTargetSdkVersion(34)
            setCompileSdkVersion(34)
            setPlatformBuildVersionCode(34)
            getOrCreateApplicationElement()
            refreshFull()
        }

        table.clearFrameworks()
        table.refresh()

        val apk = dir.resolve("input.apk")
        ZFile.openReadWrite(apk).use { zip ->
            zip.add("resources.arsc", ByteArrayInputStream(table.bytes))
            zip.add("AndroidManifest.xml", ByteArrayInputStream(manifest.bytes))
            zip.add("res/layout/main.xml", ByteArrayInputStream(layout))
            zip.add("res/color/selector.xml", ByteArrayInputStream(selector))
            zip.add("res/color/x.xml", ByteArrayInputStream(selector))
            zip.add("res/drawable/icon.png", ByteArrayInputStream("PNG default".toByteArray()))
            zip.add("res/drawable-xxhdpi/icon.png", ByteArrayInputStream("PNG xxhdpi".toByteArray()))
            for (name in listOf("sparse_a", "sparse_b", "sparse_c")) {
                zip.add("res/drawable-hdpi/$name.png", ByteArrayInputStream("PNG $name".toByteArray()))
            }
            zip.add("assets/data.bin", ByteArrayInputStream(ByteArray(1024) { it.toByte() }))
        }
        return apk
    }
}
