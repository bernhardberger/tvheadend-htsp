# HTSP protocol module

`:sdk:htsp-protocol` publishes the checkout-local JVM artifact
`at.bernhardberger.tvheadend:htsp-protocol:0.1.0-SNAPSHOT`. It owns the
provisional typed request, outcome, and server-message declarations in
`at.bernhardberger.tvheadend.htsp`, plus the existing raw HTSP codec, transport,
probe, lifecycle machinery, transport facts, and opt-in
`@PlaybackIntegrationApi` SPI. Every production declaration is in that one
package; this module does not declare `at.bernhardberger.tvheadend.client`.

This mutable `0.x` snapshot has exactly one declared external dependency:
`kotlinx-coroutines-core`, in addition to the implicit Kotlin and JDK runtimes.
It has no dependency on another SDK module, Android, Media3, playback/session,
native decoder, testing fixture, legacy application, or other third-party code.

The artifact is available only from the ignored checkout-local Maven repository.
It makes no support, completeness, stability, external-publication, release, or
distribution claim.

This GPLv3 SDK is an independently maintained descendant of
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not official
TVHeadend software and is not affiliated with, endorsed by, or sponsored by the
TVHeadend project.
