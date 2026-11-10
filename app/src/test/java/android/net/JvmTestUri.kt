package android.net

import android.os.Parcel

/**
 * Concrete subclass of [android.net.Uri] compiled ONLY in the local JVM unit test source set (`src/test`).
 * Allocated via `Unsafe.allocateInstance` when `Uri.fromFile(file)` returns `null` under
 * `unitTests.isReturnDefaultValues = true`.
 */
class JvmTestUri private constructor() : Uri() {
    @JvmField
    var filePath: String = ""

    override fun getPath(): String = filePath
    override fun toString(): String = if (filePath.isNotEmpty()) "file://$filePath" else "file://test"
    override fun isHierarchical(): Boolean = true
    override fun isOpaque(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun isAbsolute(): Boolean = true
    override fun getScheme(): String = "file"
    override fun getEncodedSchemeSpecificPart(): String = "//$filePath"
    override fun getSchemeSpecificPart(): String = "//$filePath"
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getEncodedPath(): String = filePath
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getLastPathSegment(): String? = filePath.substringAfterLast('/')
    override fun buildUpon(): Builder = Builder()
    override fun compareTo(other: Uri?): Int = toString().compareTo(other?.toString().orEmpty())
    override fun writeToParcel(out: Parcel, flags: Int) {}
    override fun describeContents(): Int = 0
}
