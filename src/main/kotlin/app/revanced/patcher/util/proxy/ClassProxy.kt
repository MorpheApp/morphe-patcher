package app.revanced.patcher.util.proxy

import app.revanced.patcher.util.PatchClasses
import com.android.tools.smali.dexlib2.iface.ClassDef

@Deprecated("Instead use BytecodePatchContext class lookup methods")
class ClassProxy internal constructor(
    val immutableClass: ClassDef,
    patchClasses: PatchClasses
) {
    @Deprecated("Instead use BytecodePatchContext class lookup methods")
    val mutableClass by lazy {
        patchClasses.mutableClassBy(immutableClass)
    }
}
