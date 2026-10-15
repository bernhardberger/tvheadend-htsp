# Module tvheadend-htsp

A Kotlin/JVM client for Tvheadend's HTSP protocol. Requests are suspending
functions that return typed outcomes, server events arrive on flows, and
cancelling the calling coroutine cancels the call.

Start with the
[README](https://github.com/bernhardberger/tvheadend-htsp#readme) and the
[guides](https://github.com/bernhardberger/tvheadend-htsp/tree/main/docs/guides).
The exact rules for outcomes, cancellation and event streams are in
[API behavior](https://github.com/bernhardberger/tvheadend-htsp/blob/main/docs/public-api.md).

# Package at.bernhardberger.tvheadend.htsp.connection

Connecting, authenticating, the connection state, server events, and
disconnecting or closing the client. Create a client with
[createHtspConnection], then call [HtspConnection.connect].

# Package at.bernhardberger.tvheadend.htsp.requests

Typed requests for the client-to-server HTSP methods, each with a suspending
extension function on [at.bernhardberger.tvheadend.htsp.connection.HtspConnection]
that returns an [at.bernhardberger.tvheadend.htsp.connection.HtspResult].

# Package at.bernhardberger.tvheadend.htsp.messages

Typed server-to-client messages. Metadata messages implement
[HtspMetadataMessage] and arrive on the connection's shared event flow; each
live subscription delivers its own messages in order on its own flow.

# Package at.bernhardberger.tvheadend.htsp.model

The channel, tag, EPG event, DVR entry and recording-rule entities that
metadata messages and request replies share. A full update message carries the
complete entity and replaces the stored one.

# Package at.bernhardberger.tvheadend.htsp.wire

The binary payload value type [HtspBinary]. Framing and encoding are internal.

# Package at.bernhardberger.tvheadend.htsp.jsonapi

An opt-in bridge that sends Tvheadend JSON API calls as HTSP `api` requests over
the existing connection. Using it requires opting in to [HtspJsonApi].
