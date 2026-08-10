# HTSP protocol module

`:sdk:htsp-protocol` publishes the checkout-local JVM artifact
`at.bernhardberger.tvheadend:htsp-protocol:0.1.0-SNAPSHOT`. It owns the
provisional typed request, outcome, and server-message declarations in
`at.bernhardberger.tvheadend.htsp`, plus the public typed client transport and
probe seams, internal raw HTSP codec/service and lifecycle machinery, transport
facts, and opt-in
`@PlaybackIntegrationApi` SPI. Every production declaration is in that one
package; this module does not declare `at.bernhardberger.tvheadend.client`.

Ordinary `client-htsp` consumes this artifact only through public API; there is
no Gradle friend wiring. `HtspClientTransport`, its factory/options, opaque
generation, typed events/failures/outcomes, and generation-fenced lifecycle are
the ordinary transport boundary. `HtspService`, `HtspCodec`, raw per-message
mappers, and the catalog helper remain internal. The public 26-message finite
decoder is the explicit raw-map input boundary, while pre-existing raw playback
events remain available only through `@PlaybackIntegrationApi` opt-in.

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
