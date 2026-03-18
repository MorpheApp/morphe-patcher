package app.morphe.patcher.resource

@Suppress("unused")
enum class CpuArchitecture(val arch: String) {
    ARMEABI_V7A("armeabi-v7a"),
    ARM64_V8A("arm64-v8a"),
    X86("x86"),
    X86_64("x86_64"),
    ARMEABI("armeabi"),
    MIPS("mips"),
    MIPS64("mips64");

    companion object {
        fun valueOfOrNull(name: String?): CpuArchitecture? {
            if (name == null) return null
            return entries.firstOrNull { it.arch == name }
                ?: runCatching { valueOf(name) }.getOrNull()
        }
    }
}
