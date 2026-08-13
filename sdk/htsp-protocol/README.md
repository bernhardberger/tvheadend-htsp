# HTSP protocol module

`:sdk:htsp-protocol` publishes the checkout-local JVM artifact
`at.bernhardberger.tvheadend:htsp-protocol:0.1.0-SNAPSHOT`. It owns the
provisional typed request, outcome, and server-message declarations in
`at.bernhardberger.tvheadend.htsp`, plus the public typed client transport and
probe seams, internal raw HTSP codec/service and lifecycle machinery, transport
facts, plus the narrow
`@HtspJsonApi` bridge. Every production declaration is in that one
package; this module does not declare `at.bernhardberger.tvheadend.client`.
The protocol-package raw playback ABI has been removed.

Ordinary `client-htsp` consumes this artifact only through public API; there is
no Gradle friend wiring. `HtspConnection`, its factory/options, opaque
generation, typed events/failures/outcomes, and generation-fenced lifecycle are
the ordinary transport boundary. `HtspService`, `HtspCodec`, raw per-message
mappers, and the catalog helper remain internal. The public 29-message finite
decoder is the explicit raw-map input boundary. Playback integration uses the
client-package typed `@PlaybackIntegrationApi` transport.

Generated parameter-based `HtspConnection` extensions are the only supported
public request path. Public request and response models remain data contracts,
but there is no public generic request primitive; missing methods belong in the
reviewed typed request catalog and generated surface.

The `api` request is an error-level opt-in bridge to TVHeadend's HTTP JSON API,
not an endpoint SDK or compatibility promise. It accepts an exact path and a
closed, ordered `HtspApiValue` tree, returns `HtspResult<ApiResponse>`, and has no
generic dispatch, endpoint registry, schema models, path normalization, or HTTP
fallback. Unknown endpoints and successful no-response callbacks both produce
`ApiResponse.NoPayload`.

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
