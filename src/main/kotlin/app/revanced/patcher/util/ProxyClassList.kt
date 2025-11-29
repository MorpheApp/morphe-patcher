package app.revanced.patcher.util

import com.android.tools.smali.dexlib2.iface.ClassDef

@Deprecated("Instead use PatchClasses", ReplaceWith("PatchClasses"))
class ProxyClassList internal constructor(classes: MutableList<ClassDef>) : MutableList<ClassDef> by classes
