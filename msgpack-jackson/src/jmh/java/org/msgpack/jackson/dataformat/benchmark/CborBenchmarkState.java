package org.msgpack.jackson.dataformat.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.msgpack.jackson.dataformat.benchmark.model.MediaItem;
import org.msgpack.jackson.dataformat.benchmark.model.MediaItems;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class CborBenchmarkState
{
    public final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    public final byte[] cborBytes;

    public CborBenchmarkState()
    {
        try {
            MediaItem item = MediaItems.stdMediaItem();
            cborBytes = cborMapper.writeValueAsBytes(item);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
