package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** Opened file handle [id] with source-coupled optional size and modification-time metadata. */
public data class FileOpenResponse(
    public val id: Long,
    public val sizeBytes: Long?,
    public val modifiedAtUnixSeconds: Long?,
)

/** Contains one defensively copied bounded payload; an empty payload is a valid successful read. */
public data class FileReadResponse(public val data: HtspBinary)

/** Explicit successful empty acknowledgement returned for a protocol file-close request. */
public data object FileCloseResponse

/** Optional size and modification time for an open file handle; the pair is absent together when unavailable. */
public data class FileStatResponse(
    public val sizeBytes: Long?,
    public val modifiedAtUnixSeconds: Long?,
)

/** Contains the successful absolute non-negative file offset after a seek. */
public data class FileSeekResponse(public val offset: Long)

/** Finite file-seek origin vocabulary: start, current position, or end; a null request value omits the field. */
public enum class FileSeekWhence {
    SET,
    CURRENT,
    END,
}
/** Carries the exact protocol [file] selector without path normalization; diagnostics redact it. */
public data class FileOpenRequest(public val file: String) : HtspRequest<FileOpenResponse>(
    method = "fileOpen",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 8,
) {
    override fun toString(): String = "FileOpenRequest(file=<redacted>)"
}

/** Selects an open file [id], bounded byte [size], and optional signed [offset] for one read. */
public data class FileReadRequest(
    public val id: Long,
    public val size: Long,
    public val offset: Long? = null,
) : HtspRequest<FileReadResponse>(
    method = "fileRead",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 8,
) {
    init {
        requireU32("id", id)
        require(size in 0L..MAX_FILE_READ_SIZE_BYTES) {
                    "size must be between zero and 16 MiB"
                }
    }
}

/** Selects an open file [id] and optional recording position and play-count values; null omits each progress field. */
public data class FileCloseRequest(
    public val id: Long,
    public val playPositionSeconds: Long? = null,
    public val playCount: Long? = null,
) : HtspRequest<FileCloseResponse>(
    method = "fileClose",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = if (playPositionSeconds != null || playCount != null) 27 else 8,
) {
    init {
        requireU32("id", id)
        playPositionSeconds?.let { requireU32("playPositionSeconds", it) }
        playCount?.let { requireU32("playCount", it) }
    }
}

/** Selects an open protocol file handle by complete unsigned [id] for metadata retrieval. */
public data class FileStatRequest(public val id: Long) : HtspRequest<FileStatResponse>(
    method = "fileStat",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 8,
) {
    init {
        requireU32("id", id)
    }
}

/** Selects an open file [id], signed [offset], and optional finite [whence] origin. */
public data class FileSeekRequest(
    public val id: Long,
    public val offset: Long,
    public val whence: FileSeekWhence? = null,
) : HtspRequest<FileSeekResponse>(
    method = "fileSeek",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 8,
) {
    init {
        requireU32("id", id)
    }
}

/** Requests opening the exact supplied protocol file selector and decodes the returned handle; no path normalization is added. */
public suspend fun HtspConnection.fileOpen(
    file: String,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<FileOpenResponse> =
    execute(
        request = FileOpenRequest(
            file = file,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Reads a bounded byte range from an open protocol file handle through typed execution. */
public suspend fun HtspConnection.fileRead(
    id: Long,
    size: Long,
    offset: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<FileReadResponse> =
    execute(
        request = FileReadRequest(
            id = id,
            size = size,
            offset = offset,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests closure of an open protocol file handle, optionally recording playback progress, and decodes the acknowledgement. */
public suspend fun HtspConnection.fileClose(
    id: Long,
    playPositionSeconds: Long? = null,
    playCount: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<FileCloseResponse> =
    execute(
        request = FileCloseRequest(
            id = id,
            playPositionSeconds = playPositionSeconds,
            playCount = playCount,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Reads size and modification metadata for an open protocol file handle through typed execution. */
public suspend fun HtspConnection.fileStat(
    id: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<FileStatResponse> =
    execute(
        request = FileStatRequest(
            id = id,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a signed seek from the optional origin and decodes the server-reported absolute file offset. */
public suspend fun HtspConnection.fileSeek(
    id: Long,
    offset: Long,
    whence: FileSeekWhence? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<FileSeekResponse> =
    execute(
        request = FileSeekRequest(
            id = id,
            offset = offset,
            whence = whence,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
