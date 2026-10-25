# panama-msgpack

MessagePack for Java 25, built on `java.lang.foreign`. It has no dependencies
and does not use `sun.misc.Unsafe`.

Every read and write goes through a `MemorySegment` with big-endian unaligned
value layouts. That segment is either a heap array or one you pass in, so a
frame in off-heap or memory-mapped storage can be decoded in place. The library
calls no restricted method, so it needs no `--enable-native-access` grant.

It was written to replace msgpack-java, whose `MessageBuffer` calls the
`sun.misc.Unsafe` memory-access methods that JEP 498 warns about and that a
future JDK will remove.

## Dependency

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://botwithus.github.io/maven") }
}

dependencies {
    implementation("com.botwithus:msgpack:0.1.0")
}
```

It is a named module: `requires com.botwithus.msgpack;`.

## API

| Type | Role |
|---|---|
| `MsgPack` | Encodes and decodes a whole value in one call: `encode(MsgValue)`, `decode(byte[] or MemorySegment, MsgDecodeRules)`, `decodeMap(...)`. |
| `MsgPacker` | Streaming writer into a growable heap buffer. It provides `pack*` for every type, raw headers and payloads, and `packValue(MsgValue)`. It always picks the smallest encoding. |
| `MsgUnpacker` | Streaming reader over a `byte[]` or a `MemorySegment`. It provides `nextType()`, `unpack*` for every type and header, `readPayload` / `readPayloadSegment` (zero-copy), `skipValue()` and `unpackValue(maxDepth)`. |
| `MsgValue` | Immutable sealed value model: `MsgNil`, `MsgBoolean`, `MsgInteger` (the full `-2^63 .. 2^64-1` range), `MsgFloat`, `MsgString`, `MsgBinary`, `MsgArray`, `MsgMap`, `MsgExtension`. Optional accessors such as `stringValue()` and `longValue()` read a value of another type as absent. |
| `MsgDecodeRules` | Configurable strictness: the nesting limit, whether trailing bytes are an error, and whether the root must be a map. |
| `MsgTimestamps` | Reads the spec's timestamp extension (type `-1`) when you ask for it. |
| `MsgPackException` | The single exception thrown for bad input. |

```java
// Whole values
byte[] bytes = MsgPack.encode(MsgMap.builder().put("id", MsgValue.of(7)).build());
MsgMap map = MsgPack.decodeMap(bytes, MsgDecodeRules.strict());
long id = map.get("id").map(v -> v.longValue().orElse(-1L)).orElse(-1L);

// Streaming, into your own object model
MsgUnpacker in = new MsgUnpacker(bytes);
int entries = in.unpackMapHeader();
String key = in.unpackString();
long value = in.unpackLong();

byte[] out = new MsgPacker().packMapHeader(1).packString("id").packLong(7).toByteArray();
```

## Guarantees

- **Lengths are checked before anything is allocated.** A string, binary or
  extension length, or an array or map count, larger than the bytes left is
  refused before any buffer is created. A five-byte frame claiming two billion
  elements costs nothing.
- **`MsgPackException` is the only exception bad input produces.** That covers a
  truncated frame, an oversize length, a type mismatch, nesting past the limit,
  invalid UTF-8 where a `String` was asked for, an integer outside the requested
  Java type, the never-used byte `0xc1`, and a broken `MsgDecodeRules` rule. Any
  other exception from a read is a bug, and the fuzz suites assert exactly this.
- **There is a depth limit.** `unpackValue` checks the limit before descending, so
  deep input is refused rather than overflowing the stack (`MsgDecodeRules.strict()`
  allows 128). `skipValue` keeps a count instead of recursing and has no limit to hit.
- **No native access is needed.** Only heap segments and segments you supply are
  used, with no downcalls. The test suite runs under `--illegal-native-access=deny`.
- **Every encoding is accepted, and the smallest is always written.** The unpacker
  reads non-minimal forms: a small value in int32, a short string in str32, and
  every bin, ext and fixext form. The packer writes the bytes msgpack-java 0.9
  writes for the same calls.
- **Decoded values pack back to the same bytes.** Strings keep their raw bytes,
  floats keep their width, and maps keep their entries in wire order, duplicates
  included.

## Performance

These numbers are indicative only. They come from `./gradlew bench`, which runs
a warm-up and then 1M timed operations per row. It is not JMH, and the numbers
are from one developer machine.

| Operation | msgpack-java 0.9.8 | panama-msgpack |
|---|---:|---:|
| Encode a 99-byte RPC-style request | 1.24M ops/s | 6.27M ops/s |
| Decode a 2 KB list reply into `Map<String, Object>` (streaming) | 57k ops/s | 112k ops/s |
| Decode the same reply into a value tree | 241k ops/s | 186k ops/s |

## Build

```
./gradlew build   # compile (-Xlint:all -Werror), test, javadoc (doclint, warnings fail)
./gradlew bench   # the throughput comparison above
```

msgpack-java is a test dependency only, used as the oracle. The tests compare
the packer byte for byte and the unpacker value for value against it, and fuzz
truncation, corruption and noise. A failing fuzz case prints its seed; replay it
with `-Pmsgpack.seed=<n>`.

## Releasing

Push a `vX.Y.Z` tag. `.github/workflows/publish.yml` then builds, refuses to
overwrite a version that is already published, writes the artifact into
[BotWithUs/maven](https://github.com/BotWithUs/maven), and creates a GitHub
release.

**One-time setup for a repository admin:** add a repository secret named
`MAVEN_DEPLOY_KEY`. It holds the private half of a write-enabled deploy key that
is registered on `BotWithUs/maven` and scoped to that repository alone. Until the
secret exists, the publish workflow fails at its checkout of `BotWithUs/maven`
and publishes nothing.

## License

GNU General Public License, version 3. See [LICENSE](LICENSE).
