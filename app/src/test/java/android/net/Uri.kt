package android.net

/** Minimal local-URL shim for Readium locator unit tests; Android's mockable Uri.parse throws. */
class Uri private constructor(private val value: String) {
    val isAbsolute: Boolean get() = value.contains("://")
    val isRelative: Boolean get() = !isAbsolute
    val fragment: String? get() = value.substringAfter('#', "").takeIf { it.isNotEmpty() }
    fun buildUpon() = Builder(value)
    override fun toString() = value
    override fun equals(other: Any?) = other is Uri && value == other.value
    override fun hashCode() = value.hashCode()

    class Builder internal constructor(private var value: String) {
        fun fragment(fragment: String?): Builder = apply {
            value = value.substringBefore('#') + if (fragment.isNullOrEmpty()) "" else "#$fragment"
        }
        fun build() = Uri(value)
    }

    companion object {
        @JvmStatic fun parse(value: String?) = Uri(value.orEmpty())
    }
}
