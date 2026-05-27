package org.msgpack.jackson.dataformat.benchmark;

import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Compares writeUTF8String throughput for RawUtf8String (current) vs new String(bytes, UTF_8).
 * Run this benchmark twice: once with the current implementation, once after replacing
 * writeByteArrayTextValue to use new String(text, offset, len, StandardCharsets.UTF_8).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class WriteUTF8StringBenchmark
{
    private static final int WRITES_PER_INVOCATION = 500;

    private final MessagePackFactory factory = new MessagePackFactory();

    // ASCII-only: compact-string fast path applies for new String(bytes, UTF_8)
    private final byte[] asciiBytes =
            "Hello, World! This is a typical ASCII field value.".getBytes(StandardCharsets.UTF_8);

    // Non-ASCII: multi-byte UTF-8, compact-string fast path does NOT apply
    private final byte[] nonAsciiBytes =
            "東京は日本の首都です。This mixes CJK and ASCII.".getBytes(StandardCharsets.UTF_8);

    @Benchmark
    public int writeUTF8StringAscii() throws Exception
    {
        NopOutputStream out = new NopOutputStream();
        try (JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartArray();
            for (int i = 0; i < WRITES_PER_INVOCATION; i++) {
                gen.writeUTF8String(asciiBytes, 0, asciiBytes.length);
            }
            gen.writeEndArray();
        }
        return out.size();
    }

    @Benchmark
    public int writeUTF8StringNonAscii() throws Exception
    {
        NopOutputStream out = new NopOutputStream();
        try (JsonGenerator gen = factory.createGenerator(ObjectWriteContext.empty(), out)) {
            gen.writeStartArray();
            for (int i = 0; i < WRITES_PER_INVOCATION; i++) {
                gen.writeUTF8String(nonAsciiBytes, 0, nonAsciiBytes.length);
            }
            gen.writeEndArray();
        }
        return out.size();
    }
}
