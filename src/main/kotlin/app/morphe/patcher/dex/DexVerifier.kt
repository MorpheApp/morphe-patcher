/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Debugging utility — validates DEX files and APKs using official Android SDK tools
 * and cross-DEX class hierarchy checks via dexlib2.
 * Not intended for production use.
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.MethodHandleType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import java.util.logging.Logger

interface DexVerifier {
    fun verifyDexFile(dexFile: File)
    fun verifyDexDirectory(dexDir: File)
    fun verifyApkFile(apkFile: File)
}

object NoOpDexVerifier : DexVerifier {
    override fun verifyDexFile(dexFile: File) {
        require(dexFile.isFile) { "DEX file does not exist" }
    }

    override fun verifyDexDirectory(dexDir: File) {
        require(dexDir.isDirectory) { "DEX directory does not exist" }
        dexDir.listFiles()?.forEach { verifyDexFile(it) }
    }

    override fun verifyApkFile(apkFile: File) {
        require(apkFile.isFile) { "APK file does not exist" }
    }
}

/**
 * Validates DEX files and APKs by shelling out to official Android SDK tools
 * and by performing cross-DEX class hierarchy verification using dexlib2.
 *
 * This is a **debugging-only** utility. The SDK-tool methods require the Android SDK
 * build-tools to be installed. The [verifyClassHierarchy] method has no external
 * dependencies beyond dexlib2 (already on the classpath).
 *
 * Usage:
 * ```
 * val verifier = DexVerifier(File("/path/to/android-sdk"))
 * verifier.verifyDex(File("classes.dex"))           // dexdump + d8
 * verifier.verifyApk(File("patched.apk"))           // aapt2 + apksigner + zipalign
 * verifier.verifyClassHierarchy(dexDir)             // cross-DEX hierarchy checks
 * verifier.verifyAll(dexDir, File("patched.apk"))   // everything
 * ```
 *
 * @param androidSdkPath Root of the Android SDK (i.e. the directory containing `build-tools/`).
 * @param buildToolsVersion Specific build-tools version to use, or `null` to auto-detect the latest.
 */
class SdkDexVerifier(
    androidSdkPath: File,
    buildToolsVersion: String? = null,
) : DexVerifier {
    private val logger = Logger.getLogger(this::class.java.name)

    private val buildToolsDir: File = run {
        val btRoot = androidSdkPath.resolve("build-tools")
        require(btRoot.isDirectory) {
            "Android SDK build-tools directory not found: $btRoot"
        }
        if (buildToolsVersion != null) {
            btRoot.resolve(buildToolsVersion).also {
                require(it.isDirectory) { "Build-tools version directory not found: $it" }
            }
        } else {
            // Pick the newest version directory (lexicographic sort works for semver with same-length segments).
            btRoot.listFiles { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?: error("No build-tools versions found in $btRoot")
        }
    }

    private fun resolveTool(name: String): File {
        val candidates = listOf(name, "$name.exe", "$name.bat")
        return candidates.map { buildToolsDir.resolve(it) }
            .firstOrNull { it.exists() }
            ?: error("$name not found in $buildToolsDir (tried: ${candidates.joinToString()})")
    }

    private val dexdump = resolveTool("dexdump")
    private val d8 = resolveTool("d8")
    private val aapt2 = resolveTool("aapt2")
    private val apksigner = resolveTool("apksigner")
    private val zipalign = resolveTool("zipalign")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Validate a single DEX file with both `dexdump` and `d8`.
     *
     * @throws DexVerificationException if any check fails.
     */
    override fun verifyDexFile(dexFile: File) {
        require(dexFile.isFile) { "DEX file does not exist: $dexFile" }
        verifyWithDexdump(dexFile)
        verifyWithD8(dexFile)
    }

    /**
     * Validate every `.dex` file in [directory] (non-recursive).
     *
     * @throws DexVerificationException on the first failing file.
     */
    override fun verifyDexDirectory(directory: File) {
        require(directory.isDirectory) { "Not a directory: $directory" }
        val dexFiles = directory.listFiles { f -> f.isFile && f.extension == "dex" }
            ?.sorted()
            ?: emptyList()
        require(dexFiles.isNotEmpty()) { "No .dex files found in $directory" }
        logger.info { "Verifying ${dexFiles.size} DEX files with dexdump/d8" }
        for (dex in dexFiles) {
            verifyDexFile(dex)
        }
        verifyClassHierarchy(directory)
    }

    /**
     * Validate an APK with `aapt2 dump`, `apksigner verify`, and `zipalign -c`.
     *
     * @throws DexVerificationException if any check fails.
     */
    override fun verifyApkFile(apkFile: File) {
        require(apkFile.isFile) { "APK file does not exist: $apkFile" }
        logger.info { "Verifying APK" }
        verifyWithAapt2(apkFile)
        verifyWithApksigner(apkFile)
        verifyWithZipalign(apkFile)
    }

    // -------------------------------------------------------------------------
    // Cross-DEX class hierarchy verification (dexlib2-based)
    // -------------------------------------------------------------------------

    /**
     * Performs cross-DEX class hierarchy verification on all `.dex` files in [directory].
     *
     * This catches errors that per-file tools (`dexdump`, `d8`) cannot detect, including:
     * - **Duplicate class definitions** across DEX files (undefined behavior in ART).
     * - **Hollowed-out class_defs** (STRIP_FAST artifacts with zeroed data offsets that still
     *   appear as empty shells — if ART picks these over the real definitions, you get
     *   `IncompatibleClassChangeError` or `NoSuchMethodError` at runtime).
     * - **Broken superclass chains** (a class references a superclass that doesn't exist in
     *   any DEX file).
     * - **Broken interface declarations** (a class declares it implements an interface that
     *   doesn't exist or isn't actually an interface).
     * - **invoke-interface on non-interfaces** (an `invoke-interface` instruction targets a
     *   method whose defining class is not an interface).
     *
     * @throws DexVerificationException with a detailed report if any issues are found.
     */
    private fun verifyClassHierarchy(directory: File) {
        require(directory.isDirectory) { "Not a directory: $directory" }
        val dexFiles = directory.listFiles { f -> f.isFile && f.extension == "dex" }
            ?.sorted()
            ?: emptyList()
        require(dexFiles.isNotEmpty()) { "No .dex files found in $directory" }

        logger.info("Running cross-DEX class hierarchy verification on ${dexFiles.size} files")

        // 1. Parse all DEX files and build a unified class map.
        //    Track which file each class came from for diagnostics.
        val classMap = HashMap<String, ClassDef>(65536)          // type -> ClassDef
        val classSourceMap = HashMap<String, String>(65536)      // type -> source filename
        val errors = mutableListOf<String>()

        for (file in dexFiles) {
            val dex = DexBackedDexFile.fromInputStream(null, file.inputStream().buffered())
            for (classDef in dex.classes) {
                val type = classDef.type
                val existing = classMap[type]
                if (existing != null) {
                    // Check if this is a hollowed-out duplicate (STRIP_FAST artifact).
                    val existingHollowed = isHollowedClass(existing)
                    val newHollowed = isHollowedClass(classDef)
                    if (existingHollowed && !newHollowed) {
                        // The existing entry is a hollow shell — replace with the real one.
                        classMap[type] = classDef
                        val hollowFile = classSourceMap[type]
                        errors.add(
                            "[DUPLICATE/HOLLOW] Class $type has a hollowed-out definition in " +
                                "$hollowFile and a real definition in ${file.name}. " +
                                "ART may load the wrong one depending on DEX ordering."
                        )
                        classSourceMap[type] = file.name
                    } else if (!existingHollowed && newHollowed) {
                        errors.add(
                            "[DUPLICATE/HOLLOW] Class $type has a real definition in " +
                                "${classSourceMap[type]} and a hollowed-out definition in ${file.name}. " +
                                "ART may load the wrong one depending on DEX ordering."
                        )
                    } else {
                        errors.add(
                            "[DUPLICATE] Class $type is defined in both " +
                                "${classSourceMap[type]} and ${file.name}. " +
                                "Duplicate class_defs within one APK have undefined behavior in ART."
                        )
                    }
                } else {
                    classMap[type] = classDef
                    classSourceMap[type] = file.name
                }
            }
        }

        // 2. Check superclass chains.
        for ((type, classDef) in classMap) {
            val superclass = classDef.superclass ?: continue // java.lang.Object has no super
            if (superclass !in classMap && !isFrameworkType(superclass)) {
                errors.add(
                    "[MISSING_SUPER] Class $type (in ${classSourceMap[type]}) declares " +
                        "superclass $superclass which is not defined in any DEX file " +
                        "(and is not a known framework type)."
                )
            }
        }

        // 3. Check interface declarations.
        for ((type, classDef) in classMap) {
            for (iface in classDef.interfaces) {
                val ifaceType = "L${iface.removePrefix("L")}" // normalize just in case
                val ifaceDef = classMap[ifaceType]
                if (ifaceDef == null) {
                    if (!isFrameworkType(ifaceType)) {
                        errors.add(
                            "[MISSING_IFACE] Class $type (in ${classSourceMap[type]}) declares " +
                                "interface $ifaceType which is not defined in any DEX file."
                        )
                    }
                    continue
                }
                if (!AccessFlags.INTERFACE.isSet(ifaceDef.accessFlags)) {
                    errors.add(
                        "[NOT_INTERFACE] Class $type (in ${classSourceMap[type]}) declares it " +
                            "implements $ifaceType, but $ifaceType (in ${classSourceMap[ifaceType]}) " +
                            "is not an interface (access flags: 0x${ifaceDef.accessFlags.toString(16)})."
                    )
                }
            }
        }

        // 4. Validate all instruction references: method invokes, field accesses,
        //    type references, method handles, and polymorphic/custom invokes.
        //
        //    Pre-build lookup indices for methods and fields.
        val methodIndex = HashMap<String, HashSet<String>>(classMap.size * 2)
        val fieldIndex = HashMap<String, HashSet<String>>(classMap.size * 2)
        for ((type, cd) in classMap) {
            val mSigs = HashSet<String>()
            for (m in cd.methods) mSigs.add(methodSignature(m))
            methodIndex[type] = mSigs

            val fSigs = HashSet<String>()
            for (f in cd.fields) fSigs.add(fieldSignature(f))
            fieldIndex[type] = fSigs
        }

        for ((type, classDef) in classMap) {
            for (method in classDef.methods) {
                val impl = method.implementation ?: continue
                for (insn in impl.instructions) {
                    if (insn !is ReferenceInstruction) continue
                    val callerDesc = "$type->${method.name} (${classSourceMap[type]})"

                    // Primary reference (present on all ReferenceInstructions).
                    validateReference(
                        insn.reference, insn.opcode, callerDesc, type,
                        classMap, classSourceMap, methodIndex, fieldIndex, errors
                    )

                    // Secondary reference (invoke-polymorphic has a METHOD_PROTO as reference2).
                    if (insn is DualReferenceInstruction) {
                        validateReference(
                            insn.reference2, insn.opcode, callerDesc, type,
                            classMap, classSourceMap, methodIndex, fieldIndex, errors
                        )
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            val report = buildString {
                appendLine("Cross-DEX class hierarchy verification found ${errors.size} issue(s):")
                appendLine()
                errors.forEachIndexed { i, error ->
                    appendLine("  ${i + 1}. $error")
                }
            }
            logger.severe(report)
            throw DexVerificationException(report)
        }

        logger.info { "Cross-DEX class hierarchy verification: OK (${classMap.size} classes across ${dexFiles.size} files)" }
    }

    // -------------------------------------------------------------------------
    // Individual tool wrappers
    // -------------------------------------------------------------------------

    /**
     * `dexdump -d <file>` — full disassembly validates header, checksum,
     * class_defs, and instruction encoding.
     */
    private fun verifyWithDexdump(dexFile: File) {
        logger.fine { "Running dexdump on ${dexFile.name}" }
        exec(
            listOf(dexdump.absolutePath, "-d", dexFile.absolutePath),
            toolName = "dexdump",
            targetName = dexFile.name,
        )
        logger.fine { "dexdump: ${dexFile.name} OK" }
    }

    /**
     * `d8 --file-per-class --output <tmpDir> <file>` — re-processes the DEX
     * through Google's compiler front-end, catching deeper structural issues.
     */
    private fun verifyWithD8(dexFile: File) {
        logger.fine { "Running d8 on ${dexFile.name}" }
        val tmpDir = createTempDir("d8-verify-")
        try {
            exec(
                listOf(
                    d8.absolutePath,
                    "--file-per-class",
                    "--output", tmpDir.absolutePath,
                    dexFile.absolutePath,
                ),
                toolName = "d8",
                targetName = dexFile.name,
            )
            logger.fine { "d8: ${dexFile.name} OK" }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * `aapt2 dump badging <apk>` — validates manifest and resource table.
     */
    private fun verifyWithAapt2(apkFile: File) {
        logger.fine { "Running aapt2 dump on ${apkFile.name}" }
        exec(
            listOf(aapt2.absolutePath, "dump", "badging", apkFile.absolutePath),
            toolName = "aapt2",
            targetName = apkFile.name,
        )
        logger.fine { "aapt2: ${apkFile.name} OK" }
    }

    /**
     * `apksigner verify --verbose <apk>` — validates APK signature schemes and ZIP integrity.
     */
    private fun verifyWithApksigner(apkFile: File) {
        logger.fine { "Running apksigner verify on ${apkFile.name}" }
        exec(
            listOf(apksigner.absolutePath, "verify", "--verbose", apkFile.absolutePath),
            toolName = "apksigner",
            targetName = apkFile.name,
        )
        logger.fine { "apksigner: ${apkFile.name} OK" }
    }

    /**
     * `zipalign -c -v 4 <apk>` — checks 4-byte alignment.
     */
    private fun verifyWithZipalign(apkFile: File) {
        logger.fine { "Running zipalign check on ${apkFile.name}" }
        exec(
            listOf(zipalign.absolutePath, "-c", "-v", "4", apkFile.absolutePath),
            toolName = "zipalign",
            targetName = apkFile.name,
        )
        logger.fine { "zipalign: ${apkFile.name} OK" }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Runs [command], capturing stdout and stderr. Throws [DexVerificationException]
     * if the exit code is non-zero.
     */
    private fun exec(command: List<String>, toolName: String, targetName: String) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            // Truncate very long output (dexdump -d can be huge on success, but on failure it's short).
            val truncated = if (output.length > 4096) {
                output.take(2048) + "\n\n... [truncated ${output.length - 4096} chars] ...\n\n" + output.takeLast(2048)
            } else {
                output
            }
            throw DexVerificationException(
                "$toolName failed on $targetName (exit code $exitCode):\n$truncated"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(prefix: String): File =
        kotlin.io.createTempDir(prefix)

    companion object {
        /**
         * Well-known framework type prefixes that won't be present in the APK's DEX files.
         * We skip verification for these since they're provided by the Android framework at runtime.
         */
        private val FRAMEWORK_PREFIXES = arrayOf(
            "Ljava/",
            "Ljavax/",
            "Ldalvik/",
            "Landroid/",
            "Landroidx/",
            "Lcom/android/",
            "Lcom/google/android/",
            "Lsun/",
            "Llibcore/",
            "Lorg/json/",
            "Lorg/xml/",
            "Lorg/w3c/",
            "Lorg/xmlpull/",
            "Lorg/apache/",
            "Ljunit/",
            "Lkotlin/",
            "Lkotlinx/",

            // OEM frameworks
            "Lcom/miui/",
            "Lcom/huawei/",
            "Lcom/samsung/",

            // okhttp references these crypto frameworks even if not available, which can cause false alarms.
            "Lorg/conscrypt/",
            "Lorg/bouncycastle/",
            "Lorg/openjsse/",
        )

        /**
         * Returns `true` if [type] is a well-known Android framework or standard library type
         * that would not be defined in the APK's DEX files, or if it is an array type
         * (array types are synthetic runtime types that inherit from java.lang.Object).
         */
        private fun isFrameworkType(type: String): Boolean =
            type.startsWith("[") || FRAMEWORK_PREFIXES.any { type.startsWith(it) }

        // -----------------------------------------------------------------
        // Reference validation dispatcher
        // -----------------------------------------------------------------

        /**
         * Validates a single [Reference] extracted from an instruction.
         * Dispatches to the appropriate check based on the reference's runtime type.
         */
        private fun validateReference(
            ref: Reference,
            opcode: Opcode,
            callerDesc: String,
            callerType: String,
            classMap: Map<String, ClassDef>,
            classSourceMap: Map<String, String>,
            methodIndex: Map<String, Set<String>>,
            fieldIndex: Map<String, Set<String>>,
            errors: MutableList<String>,
        ) {
            when (ref) {
                is MethodReference -> validateMethodRef(ref, opcode, callerDesc, callerType, classMap, classSourceMap, methodIndex, errors)
                is FieldReference -> validateFieldRef(ref, opcode, callerDesc, classMap, classSourceMap, fieldIndex, errors)
                is TypeReference -> validateTypeRef(ref, opcode, callerDesc, classMap, errors)
                is MethodHandleReference -> validateMethodHandleRef(ref, opcode, callerDesc, callerType, classMap, classSourceMap, methodIndex, fieldIndex, errors)
                // MethodProtoReference (const-method-type, invoke-polymorphic reference2):
                //   Contains only parameter types and a return type — no class or member name.
                //   Nothing to cross-validate against the class pool.
                // CallSiteReference (invoke-custom): the bootstrap method handle is typically
                //   a framework method (e.g. LambdaMetafactory). We validate the embedded
                //   MethodHandleReference if present.
                is com.android.tools.smali.dexlib2.iface.reference.CallSiteReference -> {
                    validateMethodHandleRef(
                        ref.methodHandle, opcode, callerDesc, callerType,
                        classMap, classSourceMap, methodIndex, fieldIndex, errors
                    )
                }
                // StringReference, MethodProtoReference, etc. — no class/member to validate.
            }
        }

        // -----------------------------------------------------------------
        // Method reference validation
        // -----------------------------------------------------------------

        private val INVOKE_INTERFACE_OPCODES = setOf(
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
        )

        private val INVOKE_INSTANCE_OPCODES = setOf(
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
        )

        private fun validateMethodRef(
            ref: MethodReference,
            opcode: Opcode,
            callerDesc: String,
            callerType: String,
            classMap: Map<String, ClassDef>,
            classSourceMap: Map<String, String>,
            methodIndex: Map<String, Set<String>>,
            errors: MutableList<String>,
        ) {
            val definingClass = ref.definingClass
            if (isFrameworkType(definingClass)) return

            val targetClassDef = classMap[definingClass]
            if (targetClassDef == null) {
                errors.add(
                    "[INVOKE_MISSING_CLASS] In $callerDesc: ${opcode.name} targets " +
                        "${definingClass}->${ref.name}, but $definingClass is not " +
                        "defined in any DEX file."
                )
                return
            }

            // invoke-interface: defining class must be an interface.
            if (opcode in INVOKE_INTERFACE_OPCODES) {
                if (!AccessFlags.INTERFACE.isSet(targetClassDef.accessFlags)) {
                    errors.add(
                        "[INVOKE_IFACE_NOT_IFACE] In $callerDesc: invoke-interface targets " +
                            "${definingClass}->${ref.name}, but $definingClass " +
                            "(in ${classSourceMap[definingClass]}) is not an interface " +
                            "(access flags: 0x${targetClassDef.accessFlags.toString(16)})."
                    )
                }
            }

            // For instance method invocations, the defining class of the method reference
            // must be a supertype of (or the same as) the caller's class. Otherwise, the
            // 'this' argument would fail ART's assignability check at runtime.
            if (opcode in INVOKE_INSTANCE_OPCODES &&
                definingClass != callerType &&
                !isFrameworkType(callerType)
            ) {
                val isAssignable = isSupertype(definingClass, callerType, classMap)
                if (!isAssignable) {
                    errors.add(
                        "[INVOKE_BAD_RECEIVER] In $callerDesc: ${opcode.name} targets " +
                            "${definingClass}->${ref.name}, but $definingClass is not " +
                            "a supertype of $callerType. The 'this' argument would fail " +
                            "ART's assignability check at runtime."
                    )
                }
            }

            // Check method existence.
            val refSig = methodRefSignature(ref)
            val found = findMethodInHierarchy(definingClass, refSig, classMap, methodIndex)

            if (!found) {
                errors.add(
                    "[INVOKE_MISSING_METHOD] In $callerDesc: ${opcode.name} targets " +
                        "${definingClass}->${ref.name}(${ref.parameterTypes.joinToString(",")})${ref.returnType}, " +
                        "but no such method exists on $definingClass or any of its supertypes."
                )
            }
        }

        // -----------------------------------------------------------------
        // Field reference validation
        // -----------------------------------------------------------------

        private fun validateFieldRef(
            ref: FieldReference,
            opcode: Opcode,
            callerDesc: String,
            classMap: Map<String, ClassDef>,
            classSourceMap: Map<String, String>,
            fieldIndex: Map<String, Set<String>>,
            errors: MutableList<String>,
        ) {
            val definingClass = ref.definingClass
            if (isFrameworkType(definingClass)) return

            if (definingClass !in classMap) {
                errors.add(
                    "[FIELD_MISSING_CLASS] In $callerDesc: ${opcode.name} targets " +
                        "${definingClass}->${ref.name}:${ref.type}, but $definingClass is not " +
                        "defined in any DEX file."
                )
                return
            }

            val refSig = fieldRefSignature(ref)

            // Fields resolve up the hierarchy (both instance and static).
            val found = findFieldInHierarchy(definingClass, refSig, classMap, fieldIndex)

            if (!found) {
                errors.add(
                    "[FIELD_MISSING] In $callerDesc: ${opcode.name} targets " +
                        "${definingClass}->${ref.name}:${ref.type}, " +
                        "but no such field exists on $definingClass or any of its supertypes."
                )
            }
        }

        // -----------------------------------------------------------------
        // Type reference validation
        // -----------------------------------------------------------------

        private fun validateTypeRef(
            ref: TypeReference,
            opcode: Opcode,
            callerDesc: String,
            classMap: Map<String, ClassDef>,
            errors: MutableList<String>,
        ) {
            var typeDesc = ref.type

            // Array types: peel off leading '[' to get the element type.
            while (typeDesc.startsWith("[")) typeDesc = typeDesc.substring(1)

            // Primitive types (Z, B, C, S, I, J, F, D, V) are always valid.
            if (typeDesc.length == 1) return

            if (isFrameworkType(typeDesc)) return

            if (typeDesc !in classMap) {
                errors.add(
                    "[TYPE_MISSING] In $callerDesc: ${opcode.name} references type " +
                        "${ref.type}, but $typeDesc is not defined in any DEX file."
                )
            }
        }

        // -----------------------------------------------------------------
        // MethodHandle reference validation
        // -----------------------------------------------------------------

        private fun validateMethodHandleRef(
            ref: MethodHandleReference,
            opcode: Opcode,
            callerDesc: String,
            callerType: String,
            classMap: Map<String, ClassDef>,
            classSourceMap: Map<String, String>,
            methodIndex: Map<String, Set<String>>,
            fieldIndex: Map<String, Set<String>>,
            errors: MutableList<String>,
        ) {
            val member = ref.memberReference
            when (ref.methodHandleType) {
                MethodHandleType.INVOKE_STATIC,
                MethodHandleType.INVOKE_INSTANCE,
                MethodHandleType.INVOKE_CONSTRUCTOR,
                MethodHandleType.INVOKE_DIRECT,
                MethodHandleType.INVOKE_INTERFACE -> {
                    if (member is MethodReference) {
                        // Treat invoke-instance/invoke-interface handles as virtual dispatch,
                        // others as direct.
                        val pseudoOpcode = when (ref.methodHandleType) {
                            MethodHandleType.INVOKE_INTERFACE -> Opcode.INVOKE_INTERFACE
                            MethodHandleType.INVOKE_INSTANCE -> Opcode.INVOKE_VIRTUAL
                            else -> Opcode.INVOKE_STATIC // direct/static/constructor → no hierarchy walk
                        }
                        validateMethodRef(member, pseudoOpcode, callerDesc, callerType, classMap, classSourceMap, methodIndex, errors)
                    }
                }
                MethodHandleType.STATIC_PUT,
                MethodHandleType.STATIC_GET,
                MethodHandleType.INSTANCE_PUT,
                MethodHandleType.INSTANCE_GET -> {
                    if (member is FieldReference) {
                        val pseudoOpcode = when (ref.methodHandleType) {
                            MethodHandleType.STATIC_GET -> Opcode.SGET
                            MethodHandleType.STATIC_PUT -> Opcode.SPUT
                            MethodHandleType.INSTANCE_GET -> Opcode.IGET
                            else -> Opcode.IPUT
                        }
                        validateFieldRef(member, pseudoOpcode, callerDesc, classMap, classSourceMap, fieldIndex, errors)
                    }
                }
            }
        }

        /**
         * Creates a signature string for a declared [Method] for use in lookup.
         * Format: "name|param1,param2,...|returnType"
         */
        private fun methodSignature(method: Method): String = buildString {
            append(method.name)
            append('|')
            method.parameterTypes.joinTo(this, ",")
            append('|')
            append(method.returnType)
        }

        /**
         * Creates a signature string from a [MethodReference] to match against [methodSignature].
         */
        private fun methodRefSignature(ref: MethodReference): String = buildString {
            append(ref.name)
            append('|')
            ref.parameterTypes.joinTo(this, ",")
            append('|')
            append(ref.returnType)
        }

        /**
         * Creates a signature string for a declared [Field] for use in lookup.
         * Format: "name|type"
         */
        private fun fieldSignature(field: Field): String = "${field.name}|${field.type}"

        /**
         * Creates a signature string from a [FieldReference] to match against [fieldSignature].
         */
        private fun fieldRefSignature(ref: FieldReference): String = "${ref.name}|${ref.type}"

        /**
         * Walks the class hierarchy (superclasses and interfaces) starting from [startType],
         * looking for a method matching [signature]. Returns `true` if found.
         * Stops at framework types (which we can't inspect).
         */
        private fun findMethodInHierarchy(
            startType: String,
            signature: String,
            classMap: Map<String, ClassDef>,
            methodIndex: Map<String, Set<String>>,
        ): Boolean {
            val visited = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.add(startType)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue

                // Check if the method is declared on this class.
                if (methodIndex[current]?.contains(signature) == true) return true

                // If this is a framework type we can't inspect, assume the method exists.
                if (current !in classMap && isFrameworkType(current)) return true

                val classDef = classMap[current] ?: continue

                // Walk superclass.
                classDef.superclass?.let { sup ->
                    if (sup !in visited) queue.add(sup)
                }

                // Walk interfaces.
                for (iface in classDef.interfaces) {
                    if (iface !in visited) queue.add(iface)
                }
            }

            return false
        }

        /**
         * Returns `true` if [candidateSupertype] is in the supertype hierarchy of [subtype]
         * (i.e., [subtype] is assignable to [candidateSupertype]). Walks superclasses and
         * interfaces. Returns `true` for framework types that we can't inspect (conservative).
         */
        private fun isSupertype(
            candidateSupertype: String,
            subtype: String,
            classMap: Map<String, ClassDef>,
        ): Boolean {
            val visited = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.add(subtype)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue

                if (current == candidateSupertype) return true

                // If we reach a framework type, assume the hierarchy is valid.
                if (current !in classMap && isFrameworkType(current)) return true

                val classDef = classMap[current] ?: continue

                classDef.superclass?.let { sup ->
                    if (sup !in visited) queue.add(sup)
                }
                for (iface in classDef.interfaces) {
                    if (iface !in visited) queue.add(iface)
                }
            }

            return false
        }

        /**
         * Walks the class hierarchy (superclasses and interfaces) starting from [startType],
         * looking for a field matching [signature]. Returns `true` if found.
         * Stops at framework types (which we can't inspect).
         */
        private fun findFieldInHierarchy(
            startType: String,
            signature: String,
            classMap: Map<String, ClassDef>,
            fieldIndex: Map<String, Set<String>>,
        ): Boolean {
            val visited = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.add(startType)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) continue

                if (fieldIndex[current]?.contains(signature) == true) return true
                if (current !in classMap && isFrameworkType(current)) return true

                val classDef = classMap[current] ?: continue
                classDef.superclass?.let { sup ->
                    if (sup !in visited) queue.add(sup)
                }
                for (iface in classDef.interfaces) {
                    if (iface !in visited) queue.add(iface)
                }
            }

            return false
        }

        /**
         * Heuristic: a "hollowed-out" class is a STRIP_FAST artifact where the class_def
         * exists but all data-referencing fields were zeroed. This manifests as a class with
         * no methods, no fields, and no interfaces — which is extremely unusual for a real class
         * (even marker interfaces usually extend something meaningful).
         *
         * We also check: if the class has a non-trivial superclass but zero methods/fields/interfaces,
         * it's almost certainly hollowed.
         */
        private fun isHollowedClass(classDef: ClassDef): Boolean {
            if (classDef.methods.none() &&
                classDef.fields.none() &&
                classDef.interfaces.isEmpty() &&
                classDef.superclass != null &&
                classDef.superclass != "Ljava/lang/Object;"
            ) {
                return true
            }
            // A class with a superclass other than Object, no interfaces, AND no methods
            // is suspicious but might be legitimate (e.g. a class that only inherits).
            // Be conservative: also flag classes with Object super but originally had interfaces.
            if (classDef.methods.none() &&
                classDef.fields.none() &&
                classDef.interfaces.isEmpty() &&
                classDef.annotations.none()
            ) {
                // Truly empty — could be a hollowed shell or a trivial marker.
                // Only flag if it's not java.lang.Object itself.
                return classDef.type != "Ljava/lang/Object;"
            }
            return false
        }

        /**
         * Kotlin-friendly extension: checks if an [Iterable] has no elements
         * without materializing a collection.
         */
        private fun <T> Iterable<T>.none(): Boolean = !iterator().hasNext()
    }
}

/**
 * Thrown when an Android SDK verification tool reports a failure.
 */
class DexVerificationException(message: String) : RuntimeException(message)
