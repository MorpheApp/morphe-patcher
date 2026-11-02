/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.AaptXmlStringDecoder
import com.reandroid.arsc.coder.xml.XmlCoder

/**
 * The settings of the process-wide [XmlCoder] that resources are decoded and encoded with.
 *
 * The coder is shared by every patching run of the process, so a run installs its setting only
 * while it decodes or encodes, and leaves the one it found when it is done.
 */
internal object XmlCoderSettings {
    /**
     * The setting values are decoded with, the one the coder starts out with in a new process. The logger of
     * the current setting is kept, as a host may have set one.
     */
    fun decoding(): CoderSetting = CoderSetting().also { it.logger = XmlCoder.getInstance().setting.logger }

    /** The setting values are encoded with, which reads strings as aapt does. */
    fun encoding(): CoderSetting = CoderSetting().also { it.stringDecoder = AaptXmlStringDecoder() }

    /** Runs [block] with [setting] installed on the coder, and installs the previous setting again after it. */
    inline fun <T> withSetting(setting: CoderSetting, block: () -> T): T {
        val coder = XmlCoder.getInstance()
        val previous = coder.setting
        coder.setting = setting
        try {
            return block()
        } finally {
            coder.setting = previous
        }
    }
}
