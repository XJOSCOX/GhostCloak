package org.ghostcloak.app.attachments

enum class PhotoFailureReason(val userMessage: String) {
    SOURCE_LIMIT("File too large. Choose a photo under 50 MiB."),
    BOUNDS("Photo resolution isn't supported."),
    READ("Couldn't read this photo. Choose it again or try another photo."),
    FORMAT("Unsupported image format. Choose a JPEG, PNG or supported HEIF photo."),
    ANIMATED("Animated images aren't supported. Choose a still photo."),
    DECODE("Couldn't safely decode this photo."),
    MEMORY("Not enough memory to prepare this photo. Try a smaller photo."),
    OUTPUT_LIMIT("Couldn't fit this photo within the 10 MiB output limit."),
    NORMALIZE("Couldn't safely prepare this photo.")
}
class PhotoFailure(val reason: PhotoFailureReason) : IllegalArgumentException(reason.name)

/** Closed vocabulary: no arbitrary diagnostics strings or exceptions can enter the logger. */
enum class PhotoEvent(val text: String) {
    START("PHOTO_PREP_START"), JPEG("PHOTO_SOURCE_FORMAT=JPEG"), PNG("PHOTO_SOURCE_FORMAT=PNG"),
    OTHER("PHOTO_SOURCE_FORMAT=OTHER"), UNKNOWN("PHOTO_SOURCE_FORMAT=UNKNOWN"),
    SIZE_OK("PHOTO_SOURCE_SIZE=UNDER_LIMIT"), SIZE_OVER("PHOTO_SOURCE_SIZE=OVER_LIMIT"),
    PIXELS_OK("PHOTO_PIXEL_LIMIT=OK"), PIXELS_OVER("PHOTO_PIXEL_LIMIT=OVER"),
    BOUNDS_OK("PHOTO_DECODE_BOUNDS=OK"), BOUNDS_FAILED("PHOTO_DECODE_BOUNDS=FAILED"),
    STILL("PHOTO_ANIMATION=NO"), ANIMATED("PHOTO_ANIMATION=YES"),
    HDR("PHOTO_HDR=HDR"), SDR("PHOTO_HDR=SDR"), HDR_UNKNOWN("PHOTO_HDR=UNKNOWN"),
    DECODE_OK("PHOTO_DECODE=OK"), DECODE_FAILED("PHOTO_DECODE=FAILED"),
    NORMALIZE_START("PHOTO_NORMALIZE=START"), NORMALIZE_OK("PHOTO_NORMALIZE=OK"),
    NORMALIZE_FAILED("PHOTO_NORMALIZE=FAILED"), OUTPUT_OK("PHOTO_OUTPUT_LIMIT=OK"),
    OUTPUT_OVER("PHOTO_OUTPUT_LIMIT=OVER"), OK("PHOTO_PREP_OK")
}
