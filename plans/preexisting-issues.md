# Issues to address

## msgpack-jackson3-specific

### 1. `isClosed()` always returns false — FIXED

**File:** `msgpack-jackson3/.../MessagePackGenerator.java` (close method)

Fixed by setting `_closed = true` directly in the `finally` block of `close()`, without
calling `super.close()` (which would unconditionally close the underlying stream via
`_closeInput()`, ignoring the `AUTO_CLOSE_TARGET` flag).

### 2. `MessagePackFactory.snapshot()` returns `this` — FIXED

**File:** `msgpack-jackson3/.../MessagePackFactory.java`

Fixed: `snapshot()` now delegates to `copy()`, and `rebuild()` is implemented via `MessagePackFactoryBuilder`.

### 3. Build: `msgpack-jackson3` fails to compile locally on Java < 17 — FIXED

`build.sbt` conditionally includes `msgpack-jackson3` in the root aggregate only when
running on Java 17+. Developers on older JDKs and CI on older JDK matrix entries
skip the module cleanly.

### 4. `MessagePackGenerator.streamWriteContext()` returns null — FIXED

**File:** `msgpack-jackson3/.../MessagePackGenerator.java`

Fixed by adding a `SimpleStreamWriteContext writeContext` field initialized to
`SimpleStreamWriteContext.createRootContext(null)`. `streamWriteContext()` returns
it; `writeStartArray/Object` push a child context; `endCurrentContainer` pops via
`clearAndGetParent()`; `writeName` calls `writeContext.writeName(name)`;
`_verifyValueWrite` calls `writeContext.writeValue()`. `currentValue()` and
`assignCurrentValue()` now delegate to the write context.

Also fixed in the same pass:
- `version()` now returns `PackageVersion.VERSION` (0.9.12) in generator, parser,
  and factory, replacing `Version.unknownVersion()`.

Note: `messageBufferOutputHolder` ThreadLocal was NOT fixed here — calling
`messageBufferOutputHolder.remove()` in `_releaseBuffers()` caused a 23% serialization
regression by defeating the ThreadLocal caching (each close allocates a new
`OutputStreamBufferOutput` on the next generator creation). Dropped in favour of
accepting the minor OutputStream retention, which is only observable when a thread
creates exactly one generator and never creates another.

### 5. `writeString(Reader, int)` len=-1 implementation allocates an extra copy

**File:** `msgpack-jackson3/.../MessagePackGenerator.java` (writeString(Reader, int))

The len=-1 path buffers into a `StringBuilder` then copies to a `char[]`. Using a
`CharArrayWriter` would avoid the intermediate allocation.

---

## msgpack-jackson pre-existing issues

These issues were identified during review of msgpack-jackson3 and confirmed to exist
identically in msgpack-jackson. They should be addressed in both modules together.

## 1. `MessagePackParser`: Same byte-array input skips unpacker reset

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackParser.java:130`

When `AUTO_CLOSE_SOURCE` is disabled and the same byte-array instance is parsed more
than once (e.g. reused buffer), the condition `messageUnpackerTuple.first() != src`
is false, so the unpacker is not reset. The second parse continues from where the first
left off instead of from the beginning.

## 2. `MessagePackParser`: ThreadLocal retains last byte-array payload per thread

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackParser.java:135`
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackParser.java` — FIXED

`messageUnpackerHolder` is never cleared on parser close. For byte-array inputs this
retains the entire last parsed payload for each thread in a pool indefinitely, which
can cause unbounded memory retention after large messages.

Fixed in msgpack-jackson3: on `close()`, if the cached source is a `byte[]`, it is
replaced with `null` in the ThreadLocal (keeping the unpacker alive for reuse but
releasing the byte-array reference). InputStream sources are left unchanged because
they are needed to detect same-stream reuse. Needs the same fix in msgpack-jackson.

## 3. `MessagePackGenerator`: `close()` does not set `isClosed()` to true

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` (close method)
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` — FIXED

The `close()` override never sets the closed flag, so `isClosed()` remains false.
Fixed in msgpack-jackson3 by setting `_closed = true` directly (calling `super.close()`
is not viable since it unconditionally closes the underlying stream, ignoring
`AUTO_CLOSE_TARGET`). Needs the same fix in msgpack-jackson.

## 4. `MessagePackGenerator`: `writeString(Reader, int)` crashes on length -1

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` (writeString(Reader, int))

`new char[len]` throws `NegativeArraySizeException` when `len` is -1, which is a
valid Jackson API usage meaning "unknown length, read until EOF".

## 5. `MessagePackGenerator`: `writeNumber(String)` tries `Double.parseDouble` before `BigInteger`

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java:699`

Integer strings outside the `long` range are serialized as floating-point values,
losing precision, even though MessagePack can encode big integers exactly. The order
should try integer parsing first.

## 6. `MessagePackGenerator`: Closing a container leaves stale `currentState`

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` (writeEndArray/writeEndObject)
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` — FIXED

After closing the root container, `currentState` was not reset to `IN_ROOT`. After
`flush()` clears `nodes`, any subsequent root-level value was treated as if inside the
old container. Fixed in msgpack-jackson3 by adding `currentState = IN_ROOT` in
`endCurrentContainer()`; needs the same fix in msgpack-jackson.

## 7. `MessagePackSerializedString`: Most interface methods are stubs

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackSerializedString.java:70`
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackSerializedString.java` — FIXED

Most `SerializableString` methods return 0 or do nothing. Any Jackson code path
that calls these methods (e.g. for length or byte-copy operations) will silently
produce incorrect results. Fixed in msgpack-jackson3 by implementing all append/write/put
methods using the existing `asUnquotedUTF8()` / `asQuotedUTF8()` helpers.
Needs the same fix in msgpack-jackson.

## 8. `MessagePackGenerator`: `getBytesIfAscii` writes to wrong index when offset > 0

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java:474`
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` — FIXED

`bytes[i] = (byte) c` should be `bytes[i - offset] = (byte) c`. When offset > 0, `i`
starts above 0 but `bytes` is length `len`, so it throws `ArrayIndexOutOfBoundsException`.
Fixed in msgpack-jackson3; needs the same fix in msgpack-jackson.

**Practical impact:** Low. `writeString(char[], offset, len)` is a low-level Jackson
streaming API used by Jackson's own internals and performance-sensitive custom serializers,
not by typical application code. Normal users call `writeString(String)` instead.

## 9. `MessagePackGenerator`: `writeByteArrayTextValue` ASCII path ignores offset

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java:512`
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` — FIXED

`addValueNode(new AsciiCharString(text))` stores the entire backing array instead of
the requested slice `[offset, offset+len)`. Callers such as `writeRawUTF8String` and
`writeUTF8String` with non-zero offsets will serialize garbage bytes.
Fixed in msgpack-jackson3 using `System.arraycopy`; needs the same fix in msgpack-jackson.

**Practical impact:** Low. `writeUTF8String(byte[], offset, len)` is a low-level API
called by Jackson's streaming infrastructure or custom serializers working with raw
byte buffers. Typical application code goes through ObjectMapper, which always passes
offset=0 for plain byte arrays.

## 10. `MessagePackGenerator`: ByteBuffer serialization ignores `position()`

**Files:**
- `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java:369`
- `msgpack-jackson3/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` — FIXED

`writePayload(bb.array(), bb.arrayOffset(), len)` ignores `bb.position()`. For any
ByteBuffer with a non-zero position (e.g. from `ByteBuffer.wrap(data, offset, len)` or
after reads), this serializes bytes starting at the wrong offset.
Fixed in msgpack-jackson3 using `bb.arrayOffset() + bb.position()`; needs the same fix in msgpack-jackson.

**Practical impact:** Moderate. This is the most realistic end-user scenario: a POJO
with a `ByteBuffer` field that was sliced or partially consumed will silently produce
corrupt serialized output. No exception is thrown.

