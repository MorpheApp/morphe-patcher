/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.patch

import kotlin.test.Test
import kotlin.test.assertEquals

internal class MostCommonCompatibleVersionsTest {
    private val patches =
        arrayOf(
            newPatch("some.package", setOf("a")) { stringOption("string", "value") },
            newPatch("some.package", setOf("a", "b"), default = false),
            newPatch("some.package", setOf("a", "b", "c"), default = false),
            newPatch("some.other.package", setOf("b"), default = false),
            newPatch("some.other.package", setOf("b", "c")) { booleanOption("bool", true) },
            newPatch("some.other.package", setOf("b", "c", "d")),
            newPatch("some.other.other.package") { intsOption("intArray", listOf(1, 2, 3)) },
            newPatch("some.other.other.package", setOf("a")),
            newPatch("some.other.other.package", setOf("b")),
            newPatch("some.other.other.other.package", default = false),
            newPatch("some.other.other.other.package", default = false),
        ).toSet()

    @Test
    fun `empty because package is incompatible with any version`() {
        assertEqualsVersions(
            expected = mapOf("some.package" to linkedMapOf()),
            patches = setOf(newPatch("some.package", emptySet())),
            compatiblePackageNames = setOf("some.package"),
        )
    }

    @Test
    fun `empty list of versions because package is unconstrained to any version`() {
        assertEqualsVersions(
            expected = mapOf("some.package" to linkedMapOf()),
            patches = setOf(newPatch("some.package")),
            compatiblePackageNames = setOf("some.package"),
            countUnusedPatches = true,
        )
    }

    @Test
    fun `empty because no known package was supplied`() {
        assertEqualsVersions(
            expected = emptyMap(),
            patches,
            compatiblePackageNames = setOf("unknown.package"),
        )
    }

    @Test
    fun `common versions correctly ordered for each package`() {
        fun assertEqualsExpected(compatiblePackageNames: Set<String>?) =
            assertEqualsVersions(
                expected =
                    mapOf(
                        "some.package" to linkedMapOf("a" to 3, "b" to 2, "c" to 1),
                        "some.other.package" to linkedMapOf("b" to 3, "c" to 2, "d" to 1),
                        "some.other.other.package" to linkedMapOf("a" to 1, "b" to 1),
                        "some.other.other.other.package" to linkedMapOf(),
                    ),
                patches,
                compatiblePackageNames,
                countUnusedPatches = true,
            )

        assertEqualsExpected(
            compatiblePackageNames =
                setOf(
                    "some.package",
                    "some.other.package",
                    "some.other.other.package",
                    "some.other.other.other.package",
                ),
        )

        assertEqualsExpected(
            compatiblePackageNames = null,
        )
    }

    @Test
    fun `common versions correctly ordered for each package without counting unused patches`() {
        assertEqualsVersions(
            expected =
                mapOf(
                    "some.package" to linkedMapOf("a" to 1),
                    "some.other.package" to linkedMapOf("b" to 2, "c" to 2, "d" to 1),
                    "some.other.other.package" to linkedMapOf("a" to 1, "b" to 1),
                ),
            patches,
            compatiblePackageNames =
                setOf(
                    "some.package",
                    "some.other.package",
                    "some.other.other.package",
                    "some.other.other.other.package",
                ),
            countUnusedPatches = false,
        )
    }

    @Test
    fun `return 'a' because it is the most common version`() {
        val patches =
            arrayOf("a", "a", "c", "d", "a", "b", "c", "d", "a", "b", "c", "d")
                .map { version -> newPatch("some.package", setOf(version)) }
                .toSet()

        assertEqualsVersion("a", patches, "some.package")
    }

    @Test
    fun `return null because no patches were supplied`() {
        assertEqualsVersion(null, emptySet<BytecodePatch>(), "some.package")
    }

    @Test
    fun `return null because no patch is compatible with the supplied package name`() {
        val patches = setOf(newPatch("some.package", setOf("a")))

        assertEqualsVersion(null, patches, "other.package")
    }

    @Test
    fun `return null because no compatible package is constrained to a version`() {
        val patches =
            setOf(
                newPatch("other.package"),
                newPatch("other.package"),
            )

        assertEqualsVersion(null, patches, "other.package")
    }

    private fun assertEqualsVersions(
        expected: PackageNameMap,
        patches: Set<Patch<*>>,
        compatiblePackageNames: Set<String>?,
        countUnusedPatches: Boolean = false,
    ) = assertEquals(
        expected,
        patches.mostCommonCompatibleVersions(compatiblePackageNames, countUnusedPatches),
    )

    private fun assertEqualsVersion(
        expected: String?,
        patches: Set<Patch<*>>,
        compatiblePackageName: String,
    ) {
        assertEquals(
            expected,
            patches.mostCommonCompatibleVersions(setOf(compatiblePackageName))
                .entries.firstOrNull()?.value?.keys?.firstOrNull(),
        )
    }

    private fun newPatch(
        packageName: String,
        versions: Set<String>? = null,
        default: Boolean = true,
        options: PatchBuilder<*>.() -> Unit = {},
    ) = bytecodePatch(
        name = "test",
        default = default,
    ) {
        if (versions == null) {
            compatibleWith(packageName)
        } else {
            compatibleWith(
                if (versions.isEmpty()) {
                    packageName()
                } else {
                    packageName(*versions.toTypedArray())
                }
            )
        }

        options()
    }
}