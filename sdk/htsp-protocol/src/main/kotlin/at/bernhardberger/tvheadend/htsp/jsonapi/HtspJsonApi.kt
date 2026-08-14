package at.bernhardberger.tvheadend.htsp.jsonapi

/**
 * Provisional bridge from HTSP to TVHeadend's separately versioned HTTP JSON API.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This bridge reaches TVHeadend's HTTP JSON API, whose endpoint schemas and compatibility are not negotiated by HTSP and may vary across server releases.",
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
public annotation class HtspJsonApi
