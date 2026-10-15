package at.bernhardberger.tvheadend.htsp.messages

/**
 * A typed asynchronous HTSP metadata message delivered on the global transport
 * event flow. It never represents a subscription stream message.
 *
 * Minor releases may add subtypes; keep an `else` branch when matching.
 */
public sealed interface HtspMetadataMessage : HtspServerMessage
