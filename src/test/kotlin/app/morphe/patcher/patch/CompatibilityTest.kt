/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal object CompatibilityTest {

    @Test
    fun `legacy usage`() {
        val patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package"("1.0.0"),
            )
        }

        assertEquals(1, patch.compatiblePackages!!.size)
        assertEquals("compatible.package", patch.compatiblePackages!!.first().first)
    }

    @Test
    fun `legacy to Compatibility`() {
        var patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package"("1.0.0"),
            )
        }
        assertEquals(
            listOf(AppTarget(version = "1.0.0")),
            patch.compatibility!!.first().targets
        )

        patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package",
            )
        }
        assertEquals(
            listOf(AppTarget(version = null)),
            patch.compatibility!!.first().targets
        )

        var compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = null),
                AppTarget(version = "1.1.0"),
                AppTarget(version = "1.0.0")
            )
        )
        assertEquals(null, compatibility.legacy!!.second)

        compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = null, isExperimental = true),
                AppTarget(version = "1.1.0"),
                AppTarget(version = "1.0.0")
            )
        )
        assertEquals(setOf("1.1.0", "1.0.0"), compatibility.legacy!!.second)
    }

    @Test
    fun `legacy experimental only declaration`() {
        val compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.1.0", isExperimental = true),
                AppTarget(version = "1.0.0", isExperimental = true)
            )
        )

        // Experimental is included since no non-experimental declarations exist.
        assertEquals(setOf("1.1.0", "1.0.0"), compatibility.legacy!!.second)
    }


    @Test
    fun `legacy experimental declaration`() {
        val compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.1.0", isExperimental = true),
                AppTarget(version = "1.0.1"),
                AppTarget(version = "1.0.0")
            )
        )

        // Only non-experimental is included.
        assertEquals(setOf("1.0.1", "1.0.0"), compatibility.legacy!!.second)
    }

    @Test
    fun `empty versions`() {
        assertThrows<Exception> {
            Compatibility(
                name = "Example app",
                packageName = "compatible.package",
                targets = listOf()
            )
        }

        val compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = null, minSdk = 26)
            )
        )
        assertEquals(1, compatibility.targets.count())
        assertEquals(null, compatibility.targets.first().version)
        assertEquals(26, compatibility.targets.first().minSdk)
    }

    @Test
    fun `universal app`() {
        val compatibility = Compatibility(
            description = "Universal patch",
        )

        assertEquals(1, compatibility.targets.count())
        assertEquals(null, compatibility.targets.first().version)
        assertEquals(null, compatibility.packageName)
        assertEquals(null, compatibility.name)

        assertThrows<Exception> {
            Compatibility(
                packageName = "app.example",
                name = "",
                targets = listOf(AppTarget(version = "1.0.0"))
            )
        }
    }

    @Test
    fun `duplicate versions`() {
        assertThrows<Exception> {
            Compatibility(
                name = "Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = "1.0.0"),
                    AppTarget(version = "1.0.0")
                )
            )
        }

        assertThrows<Exception> {
            Compatibility(
                name = "Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = "1.0.0", isExperimental = true),
                    AppTarget(version = "1.0.0", isExperimental = false)
                )
            )
        }

        assertThrows<Exception> {
            Compatibility(
                name = "Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = null, isExperimental = true),
                    AppTarget(version = null, isExperimental = false)
                )
            )
        }
    }

    @Test
    fun `including excluding`() {
        val version_1 = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.0.0")
            )
        )

        val version1_2 = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "2.0.0"),
                AppTarget(version = "1.0.0")
            )
        )

        val version1_2_any = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = null),
                AppTarget(version = "2.0.0"),
                AppTarget(version = "1.0.0")
            )
        )

        val version_any = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
        )

        assertNotEquals(version_1, version1_2)
        assertNotEquals(version1_2, version_any)

        assertEquals(
            version1_2,
            version_1.including(AppTarget(version = "2.0.0")),
        )

        assertEquals(
            version_1,
            version1_2.excluding("2.0.0"),
        )

        assertEquals(
            version1_2,
            version1_2.excluding("non-existent-version"),
        )

        assertEquals(
            version_any,
            version1_2.excluding("1.0.0", "2.0.0"),
        )

        assertEquals(
            version_any,
            version1_2_any.excluding("1.0.0", "2.0.0"),
        )

        assertEquals(
            version_1,
            version1_2_any.excluding(null, "2.0.0")
        )

        assertEquals(
            listOf(
                AppTarget(version = null),
                AppTarget(version = "2.0.0"),
                AppTarget(version = "1.5.0"),
                AppTarget(version = "1.0.0"),
            ),
            version1_2.including(
                AppTarget(version = "1.5.0"),
                AppTarget(version = null)
            ).targets
        )
    }

    @Test
    fun `AppTarget sorting`() {
        assertTrue(AppTarget("1.0.0") < AppTarget("1.0.1"))
        assertTrue(AppTarget("1.0.1") > AppTarget("1.0.0"))
        assertTrue(AppTarget("1.0.111") > AppTarget("1.0.11"))

        // Null handling
        assertTrue(AppTarget(null) > AppTarget("0"))
        assertTrue(AppTarget(null) > AppTarget("1.0"))
        assertEquals(0, AppTarget(null).compareTo(AppTarget(null)))

        // Single vs multi-segment
        assertTrue(AppTarget("1") < AppTarget("1.1"))
        assertTrue(AppTarget("2") > AppTarget("1.9.9"))

        // Uneven segment lengths
        assertTrue(AppTarget("1.2") < AppTarget("1.2.1"))
        assertEquals(0, AppTarget("1.2.0").compareTo(AppTarget("1.2")))
        assertTrue(AppTarget("1.2.3") < AppTarget("1.2.3.1"))
        assertTrue(AppTarget("1.2.3.10") > AppTarget("1.2.3.4"))

        // Larger numbers
        assertTrue(AppTarget("10.0.0") > AppTarget("2.999.999"))
        assertTrue(AppTarget("1.20") > AppTarget("1.3"))
        assertEquals(0, AppTarget("1.002").compareTo(AppTarget("1.2")))

        // Complex multi-part
        assertTrue(AppTarget("1.2.3.4") < AppTarget("1.2.3.4.1"))
        assertTrue(AppTarget("1.2.3.4.5") > AppTarget("1.2.3.4.4"))

        // Non‑semantic vs non‑semantic (alphabetical)
        assertTrue(AppTarget("beta") < AppTarget("gamma"))
        assertTrue(AppTarget("release") > AppTarget("alpha"))
        assertTrue(AppTarget("build-10") < AppTarget("build-2"))   // alphabetical, not numeric
        assertTrue(AppTarget("v1") < AppTarget("v2"))
        assertEquals(0, AppTarget("foo").compareTo(AppTarget("foo")))
    }

    @Test
    fun `sortedDescending sorts semantic versions correctly`() {
        val list = listOf(
            AppTarget("1.0.0"),
            AppTarget("1.2.0"),
            AppTarget("1.1.5"),
            AppTarget("2.0"),
            AppTarget("1")
        )

        val sorted = list.sortedDescending()

        assertEquals(
            listOf(
                AppTarget("2.0"),
                AppTarget("1.2.0"),
                AppTarget("1.1.5"),
                AppTarget("1.0.0"),
                AppTarget("1")
            ),
            sorted
        )
    }

    @Test
    fun `sortedDescending sorts non semantic versions alphabetically`() {
        val list = listOf(
            AppTarget("beta"),
            AppTarget("alpha"),
            AppTarget("gamma"),
            AppTarget("release")
        )

        val sorted = list.sortedDescending()

        assertEquals(
            listOf(
                AppTarget("release"),
                AppTarget("gamma"),
                AppTarget("beta"),
                AppTarget("alpha")
            ),
            sorted
        )
    }

    @Test
    fun `sortedDescending sorts mixed semantic and non semantic alphabetically`() {
        val list = listOf(
            AppTarget("1.0"),
            AppTarget("beta"),
            AppTarget("2.0"),
            AppTarget("alpha")
        )

        val sorted = list.sortedDescending()

        // Alphabetical because mixing semantic + non-semantic
        assertEquals(
            listOf(
                AppTarget("beta"),
                AppTarget("alpha"),
                AppTarget("2.0"),
                AppTarget("1.0")
            ),
            sorted
        )
    }

    @Test
    fun `sortedDescending puts null versions last`() {
        val list = listOf(
            AppTarget(null),
            AppTarget("1.0"),
            AppTarget("beta"),
            AppTarget("2.0"),
            AppTarget(null)
        )

        val sorted = list.sortedDescending()

        assertEquals(
            listOf(
                AppTarget(null),
                AppTarget(null),
                AppTarget("beta"),
                AppTarget("2.0"),
                AppTarget("1.0")
            ),
            sorted
        )
    }

    @Test
    fun `sortedDescending handles complex mixed list`() {
        val list = listOf(
            AppTarget("1.2.3"),
            AppTarget("1.10"),
            AppTarget("alpha"),
            AppTarget("1.2"),
            AppTarget("beta"),
            AppTarget(null),
            AppTarget("1.2.3.4"),
            AppTarget("rc1")
        )

        val sorted = list.sortedDescending()

        assertEquals(
            listOf(
                AppTarget(null),
                AppTarget("rc1"),
                AppTarget("beta"),
                AppTarget("alpha"),
                AppTarget("1.10"),
                AppTarget("1.2.3.4"),
                AppTarget("1.2.3"),
                AppTarget("1.2")
            ),
            sorted
        )
    }

    @Test
    fun `compatibility color string`() {
        val colorString = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.1.0", isExperimental = true),
                AppTarget(version = "1.0.0", isExperimental = true)
            ),
            appIconColor = "#FF0000"
        )

        val colorInt = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.1.0", isExperimental = true),
                AppTarget(version = "1.0.0", isExperimental = true)
            ),
            appIconColor = 0xFF0000
        )

        assertEquals(colorString.appIconColor,  colorInt.appIconColor)

        assertThrows<Exception> {
            Compatibility(
                name = "Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = "1.1.0", isExperimental = true),
                    AppTarget(version = "1.0.0", isExperimental = true)
                ),
                appIconColor = "#00FF0000"
            )
        }

        assertThrows<Exception> {
            Compatibility(
                name = "Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = "1.1.0", isExperimental = true),
                    AppTarget(version = "1.0.0", isExperimental = true)
                ),
                appIconColor = "#0000"
            )
        }
    }

    @Test
    fun `app version code`() {
        var compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            apkFileType = ApkFileType.APKM,
            targets = listOf(
                AppTarget(
                    version = "1.0.0", versionCodes = mapOf(
                        SupportedAbi.X86_64 to 100,
                        SupportedAbi.ARMEABI_V7A to 300,
                        SupportedAbi.ARM64_V8A to 400
                    )
                )
            )
        )

        var versionCodes = compatibility.targets.first().versionCodes!!

        assertEquals(3, versionCodes.count())
        assertEquals(100, versionCodes[SupportedAbi.X86_64])
        assertEquals(null, versionCodes[SupportedAbi.X86])
        assertEquals(300, versionCodes[SupportedAbi.ARMEABI_V7A])
        assertEquals(400, versionCodes[SupportedAbi.ARM64_V8A])

        // Universal APK
        compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            apkFileType = ApkFileType.APK,
            targets = listOf(
                AppTarget(version = "1.0.0", versionCode = 500)
            )
        )
        versionCodes = compatibility.targets.first().versionCodes!!

        assertEquals(4, versionCodes.count())
        assertTrue(versionCodes.all { it.value == 500 })


        // No version codes
        compatibility = Compatibility(
            name = "Example app",
            packageName = "compatible.package",
            apkFileType = ApkFileType.APK,
            targets = listOf(
                AppTarget(version = "1.0.0")
            )
        )

        assertEquals(null, compatibility.targets.first().versionCodes)


        assertThrows<IllegalArgumentException> {
            AppTarget(version = null, versionCodes = mapOf(SupportedAbi.ARM64_V8A to 123))
        }

        assertDoesNotThrow {
            AppTarget(version = null, versionCodes = mapOf())
        }
    }
}
