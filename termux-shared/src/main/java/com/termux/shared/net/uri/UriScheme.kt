package com.termux.shared.net.uri

/**
 * The {@link android.net.Uri} schemes.
 *
 * https://www.iana.org/assignments/uri-schemes/uri-schemes.xhtml
 * https://en.wikipedia.org/wiki/List_of_URI_schemes
 */
object UriScheme {

    /** Android app resource. */
    const val SCHEME_ANDROID_RESOURCE = "android.resource"

    /** Android content provider. https://www.iana.org/assignments/uri-schemes/prov/content. */
    const val SCHEME_CONTENT = "content"

    /** Filesystem or android app asset. https://www.rfc-editor.org/rfc/rfc8089.html. */
    const val SCHEME_FILE = "file"

    /* Hypertext Transfer Protocol. */
    const val SCHEME_HTTP = "http"

    /* Hypertext Transfer Protocol Secure. */
    const val SCHEME_HTTPS = "https"

}
