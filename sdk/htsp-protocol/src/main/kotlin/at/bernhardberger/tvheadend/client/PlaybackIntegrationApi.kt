package at.bernhardberger.tvheadend.client

/**
 * Cross-module implementation boundary used only by the Media3 playback SDK.
 * Frontend consumers must use [TvheadendClient] and the supported runtime facades.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for the TVHeadend Media3 playback integration.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.TYPEALIAS,
)
public annotation class PlaybackIntegrationApi
