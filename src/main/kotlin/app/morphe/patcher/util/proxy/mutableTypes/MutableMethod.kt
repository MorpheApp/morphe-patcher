/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.util.proxy.mutableTypes

import app.morphe.patcher.util.proxy.mutableTypes.MutableAnnotation.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethodParameter.Companion.toMutable
import com.android.tools.smali.dexlib2.HiddenApiRestriction
import com.android.tools.smali.dexlib2.base.reference.BaseMethodReference
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation

class MutableMethod(method: Method) :
    BaseMethodReference(),
    Method {
    private var definingClass = method.definingClass
    private var name = method.name
    private var accessFlags = method.accessFlags
    private var returnType = method.returnType

    // Create own mutable MethodImplementation (due to not being able to change members like register count)
    private val implementationDelegate = lazy { method.implementation?.let { Implementation(it) } }
    private val _implementation by implementationDelegate
    private val _annotations by lazy { method.annotations.map { annotation -> annotation.toMutable() }.toMutableSet() }
    private val _parameters by lazy { method.parameters.map { parameter -> parameter.toMutable() }.toMutableList() }
    private val _parameterTypes by lazy { method.parameterTypes.toMutableList() }
    private val _hiddenApiRestrictions by lazy { method.hiddenApiRestrictions }

    /**
     * The method this method was created from.
     */
    internal val sourceMethod: Method = method

    /**
     * If the mutable implementation was created. Until then, the instructions
     * are the same as the instructions of [sourceMethod].
     */
    internal val isImplementationCreated: Boolean
        get() = implementationDelegate.isInitialized()

    /**
     * The [ChangeStamps] stamp of the last change of the instructions, or 0 if the instructions
     * are the same as the instructions of [sourceMethod].
     */
    internal var instructionsChangeStamp = 0L
        private set

    /**
     * The mutable classes this method was added to, which are told when the instructions change.
     * A class this method was removed from is kept, which only makes it check its methods again.
     */
    private val owners = ArrayList<MutableClass>(1)

    internal fun addOwner(owner: MutableClass) {
        if (owners.none { it === owner }) owners += owner
    }

    private fun instructionsChanged() {
        val stamp = ChangeStamps.next()
        instructionsChangeStamp = stamp
        owners.forEach { owner -> owner.methodsChanged(stamp) }
    }

    /**
     * A mutable implementation that tells this method when its instructions change.
     * All changes of the instructions are done with the methods overridden here.
     */
    private inner class Implementation(implementation: MethodImplementation) :
        MutableMethodImplementation(implementation) {
        override fun addInstruction(index: Int, instruction: BuilderInstruction) {
            super.addInstruction(index, instruction)
            instructionsChanged()
        }

        override fun addInstruction(instruction: BuilderInstruction) {
            super.addInstruction(instruction)
            instructionsChanged()
        }

        override fun replaceInstruction(index: Int, replacementInstruction: BuilderInstruction) {
            super.replaceInstruction(index, replacementInstruction)
            instructionsChanged()
        }

        override fun removeInstruction(index: Int) {
            super.removeInstruction(index)
            instructionsChanged()
        }

        override fun swapInstructions(index1: Int, index2: Int) {
            super.swapInstructions(index1, index2)
            instructionsChanged()
        }
    }

    fun setDefiningClass(definingClass: String) {
        this.definingClass = definingClass
    }

    fun setName(name: String) {
        this.name = name
    }

    fun setAccessFlags(accessFlags: Int) {
        this.accessFlags = accessFlags
    }

    fun setReturnType(returnType: String) {
        this.returnType = returnType
    }

    override fun getDefiningClass(): String = definingClass

    override fun getName(): String = name

    override fun getParameterTypes(): MutableList<CharSequence> = _parameterTypes

    override fun getReturnType(): String = returnType

    override fun getAnnotations(): MutableSet<MutableAnnotation> = _annotations

    override fun getAccessFlags(): Int = accessFlags

    override fun getHiddenApiRestrictions(): MutableSet<HiddenApiRestriction> = _hiddenApiRestrictions

    override fun getParameters(): MutableList<MutableMethodParameter> = _parameters

    override fun getImplementation(): MutableMethodImplementation? = _implementation

    companion object {
        /**
         * Creates a new mutable copy of this method.
         *
         * **Important**
         * The created mutable method is not automatically added to any classes, and if the
         * method is not added to a class it will not be saved to the final target apk.
         *
         * For many use cases, it's more desirable to use
         * [app.morphe.patcher.patch.BytecodePatchContext.mutableClassDefBy]
         * and find/modify the mutable methods from it.
         */
        fun Method.toMutable(): MutableMethod = MutableMethod(this)
    }
}
