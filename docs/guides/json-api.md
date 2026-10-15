# The JSON API bridge

TVHeadend has a second interface besides HTSP, a JSON API that its web UI uses.
It reaches server features that have no typed HTSP request. HTSP can carry such
a call: one HTSP method, `api`, runs a JSON API path on the server and sends the
result back over the connection you already have. The `jsonapi` package wraps
that method. This guide covers when to use it, how to make a call and read the reply, and what the
bridge does not promise.

It assumes a live connection; see the [connecting guide](connecting.md). The
snippets omit imports; the `api` function and the value types are in
`at.bernhardberger.tvheadend.htsp.jsonapi`. The complete file is
[`JsonApiGuide.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/JsonApiGuide.kt),
compiled against the staged artifact in CI.

## When to use it

Use the typed surface first. Channels, EPG, recordings, rules and subscriptions
all have typed requests and messages, and those are covered by the library's
[compatibility rules](../versioning.md). The bridge is for what they leave out.

The bridge gives up three things the typed surface offers:

- **A stable shape.** The library knows nothing about the endpoints. Their
  paths, arguments and replies belong to the server, and a different TVHeadend
  release can change them. HTSP does not negotiate them the way it negotiates
  its own protocol version.
- **Compatibility promises.** The `@HtspJsonApi` bridge is outside the
  compatibility rules in [versioning](../versioning.md), so it may change in a
  minor release.
- **Typed results.** A reply is a tree of generic values that you read by key.

For that reason every declaration is marked `@HtspJsonApi`, an opt-in
annotation at error level. Code that uses the bridge must say so with
`@OptIn(HtspJsonApi::class)` or carry the annotation itself, which keeps the
dependency visible in your code.

## Make a call

`api` is an extension on `HtspConnection`. It behaves like every other request:
it returns an `HtspResult`, honors `timeoutMs` and `expectedGeneration`, and
propagates cancellation. There is no separate connection and no HTTP request,
so it uses the account you authenticated with.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/JsonApiGuide.kt#server-info -->
```kotlin
@OptIn(HtspJsonApi::class)
suspend fun serverInfoName(connection: HtspConnection, live: HtspLiveConnection): String? =
    when (val result = connection.api("serverinfo", expectedGeneration = live.generation)) {
        is HtspResult.Ok -> when (val response = result.value) {
            is ApiResponse.Payload ->
                ((response.value as? HtspApiObject)?.get("name") as? HtspApiString)?.value
            ApiResponse.NoPayload -> null
        }
        is HtspFailure -> null
    }
```

- `path` is the endpoint name that follows `/api/` in the HTTP form of the same
  call, such as `serverinfo`. The library sends it exactly as given, without
  normalizing or validating it.
- `args` is an optional `HtspApiObject` with the call's arguments. Left null, it
  is not sent, and the server hands the endpoint an empty object.
- What an endpoint accepts and returns is defined by the server, not by this
  library. Check it against the server version you target; `serverFacts` on the
  live connection has the `serverVersion` and the `apiVersion` the server
  reported.

### Build arguments and read replies

Arguments and replies use one closed set of values:

| Type | Holds |
|---|---|
| `HtspApiString` | a string, read with `value` |
| `HtspApiLong` | a signed 64-bit integer |
| `HtspApiBoolean` | a boolean |
| `HtspApiBinary` | bytes, copied in and out |
| `HtspApiUuid` | exactly 16 bytes; any other length throws `IllegalArgumentException` |
| `HtspApiObject` | string keys to values, in insertion order |
| `HtspApiList` | values in order |

`htspApiObject("key" to HtspApiString("value"))` and `htspApiList(...)` build
the containers, which nest freely. They copy their input, so changing an array
afterwards does not change the value. `HtspApiObject` offers `get(name)`, which
returns null for a missing key, plus `keys` and `size`. `HtspApiList` offers
`get(index)` and `size`.

There is no floating-point type and no null. A reply that contains a value
outside this set fails as `MalformedReply`. A successful reply carries an
object or a list, never a bare scalar.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/JsonApiGuide.kt#path-list -->
```kotlin
@OptIn(HtspJsonApi::class)
suspend fun apiPaths(connection: HtspConnection, live: HtspLiveConnection): List<String>? {
    val result = connection.api("pathlist", expectedGeneration = live.generation)
    val payload = (result as? HtspResult.Ok)?.value as? ApiResponse.Payload ?: return null
    val list = payload.value as? HtspApiList ?: return null
    return List(list.size) { index -> (list[index] as? HtspApiString)?.value ?: return null }
}
```

The `pathlist` endpoint of the pinned server lists the paths the server
registered, which is a quick way to see what a given server offers.

## What comes back

`api` returns `HtspResult<ApiResponse>`:

| Result | Meaning |
|---|---|
| `Ok(Payload)` | the call produced an object or a list |
| `Ok(NoPayload)` | the reply had no payload; see below |
| `AccessDenied` | the server denied the account access to this path |
| `NotSupported` | the server does not know the `api` method |
| `ServerError` | the server refused the call; `serverMessage` holds its text, if any |
| `Timeout`, `TransportUnavailable` | as for any request |
| `MalformedReply` | the reply held a value outside the set above, or fields the bridge does not expect |

`NoPayload` is the one to read carefully. The server sends the same empty reply
for a call that legitimately returns nothing, for a path it does not know, and
for an object the endpoint could not find. So `NoPayload` does not prove that
the call did anything, and the bridge cannot tell you which case it was. If you
need to know whether a change took effect, read the state back.

An endpoint can also report its own error inside a payload. A reply whose object
has an `error` key still arrives as `Ok(Payload)`, because the bridge sees data,
not a failure. Look in the payload before you treat a call as successful. The
pinned server answers a failure that is neither a permission problem nor a
missing object with a generic error text, not the backend's own, so a
`ServerError` from this call says little about the cause.

The usual rules about uncertain outcomes apply. A `Timeout` or `MalformedReply`
does not show that a call changed nothing, and JSON API calls can change the
server, so do not repeat a modifying call blindly. See the
[failure table](connecting.md#run-requests).

## What the library keeps out of logs

`toString` on these types is partly redacted. A string prints as a placeholder,
an object or list prints only its size, a binary value prints its size, a UUID
prints a placeholder, and an `ApiRequest` prints no path. Numbers and booleans
print as they are. Reading `value` or `get` gives you the real content, so
decide what you log.

## Where to go next

- [Connecting, requests, and the connection lifecycle](connecting.md) for
  request outcomes and the generation fence.
- [Errors and troubleshooting](troubleshooting.md) when a call fails.
- [Versioning and compatibility](../versioning.md) for what the opt-in bridge
  leaves uncovered.
