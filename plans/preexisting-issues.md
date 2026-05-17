# Issues to address

## msgpack-jackson3-specific

### 1. `isClosed()` always returns false

**File:** `msgpack-jackson3/.../MessagePackGenerator.java` (close method)

`close()` cannot call `super.close()` because `GeneratorBase.close()` in Jackson 3
closes the underlying output stream as a side effect, breaking tests that disable
`AUTO_CLOSE_TARGET`. As a result `isClosed()` always returns false and callers can
continue writing into a closed generator without getting the standard exception.

### 2. `MessagePackFactory.snapshot()` returns `this` — FIXED

**File:** `msgpack-jackson3/.../MessagePackFactory.java`

Fixed: `snapshot()` now delegates to `copy()`, and `rebuild()` is implemented via `MessagePackFactoryBuilder`.

### 3. Build: `msgpack-jackson3` fails to compile locally on Java < 17

`msgpack-jackson3` is in the root aggregate unconditionally. The CI works around
this with a bash version check, but a developer running `./sbt test` locally on
Java 8 or 11 gets a hard compilation failure. A cleaner build-level solution
(conditional aggregate, toolchain support, or a separate profile) is needed.

### 4. `MessagePackGenerator.streamWriteContext()` returns null

**File:** `msgpack-jackson3/.../MessagePackGenerator.java` (streamWriteContext method)

`streamWriteContext()` returns `null`, bypassing Jackson's standard write context
management. This can cause NPEs in Jackson code paths that use the context for path
tracking in error messages or certain serialization features. Fixing it properly
requires integrating with Jackson 3's `TokenStreamContext` / `_streamWriteContext`
managed by `GeneratorBase`, which needs investigation.

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

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackParser.java:135`

`messageUnpackerHolder` is never cleared on parser close. For byte-array inputs this
retains the entire last parsed payload for each thread in a pool indefinitely, which
can cause unbounded memory retention after large messages.

## 3. `MessagePackGenerator`: `close()` does not call `super.close()`

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` (close method)

The `close()` override never delegates to `GeneratorBase.close()`, so `isClosed()`
remains false after close. Callers can continue writing into a closed generator
instead of getting the standard closed-generator exception.

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

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackGenerator.java` (writeEndArray/writeEndObject)

After `flush()` clears `nodes`, any subsequent root-level value written with the
same generator is treated as if inside the old container, which can corrupt output.

## 7. `MessagePackSerializedString`: Most interface methods are stubs

**File:** `msgpack-jackson/src/main/java/org/msgpack/jackson/dataformat/MessagePackSerializedString.java:70`

Most `SerializableString` methods return 0 or do nothing. Any Jackson code path
that calls these methods (e.g. for length or byte-copy operations) will silently
produce incorrect results.

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

