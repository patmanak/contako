package com.patmanak.contako.data.android.provider

/** Compare each representation with its own expected bytes. The stable reader
 * prefers display bytes; a later display read MUST still match that observation.
 * An absent display allows only the existing exact inline fallback, never an old
 * thumbnail alongside a different full-size image.
 */
internal fun matchesAndroidPhotoReadback(
    observed: ByteArray,
    thumbnail: ByteArray?,
    display: ByteArray?,
    expectedDisplay: ByteArray,
    expectedThumbnail: ByteArray,
    requireDisplay: Boolean,
): Boolean {
    if (expectedDisplay.isEmpty() || expectedThumbnail.isEmpty() ||
        thumbnail == null || !thumbnail.contentEquals(expectedThumbnail)) return false
    return if (display == null) {
        !requireDisplay && observed.contentEquals(expectedThumbnail)
    } else {
        display.contentEquals(expectedDisplay) && observed.contentEquals(display)
    }
}
