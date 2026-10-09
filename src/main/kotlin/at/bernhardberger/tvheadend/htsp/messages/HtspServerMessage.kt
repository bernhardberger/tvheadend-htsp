package at.bernhardberger.tvheadend.htsp.messages

/**
 * A typed asynchronous HTSP server message. It never represents an RPC reply.
 *
 * Minor releases may add subtypes; keep an `else` branch when matching.
 */
public sealed interface HtspServerMessage
