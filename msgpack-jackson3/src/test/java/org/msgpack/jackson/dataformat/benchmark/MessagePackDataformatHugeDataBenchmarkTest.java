//
// MessagePack for Java
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.msgpack.jackson.dataformat.benchmark;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.Test;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MessagePackDataformatHugeDataBenchmarkTest
{
    private static final int ELM_NUM = 1000000;
    private static final int COUNT = 6;
    private static final int WARMUP_COUNT = 4;
    private final ObjectMapper origObjectMapper = JsonMapper.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();
    private final ObjectMapper msgpackObjectMapper = MessagePackMapper.builder(new MessagePackFactory())
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();
    private static final List<Object> value;
    private static final byte[] packedByOriginal;
    private static final byte[] packedByMsgPack;

    static {
        value = new ArrayList<Object>();
        for (int i = 0; i < ELM_NUM; i++) {
            value.add((long) i);
        }
        for (int i = 0; i < ELM_NUM; i++) {
            value.add((double) i);
        }
        for (int i = 0; i < ELM_NUM; i++) {
            value.add(String.valueOf(i));
        }

        byte[] bytes = null;
        try {
            bytes = JsonMapper.builder().build().writeValueAsBytes(value);
        }
        catch (JacksonException e) {
            e.printStackTrace();
        }
        packedByOriginal = bytes;

        try {
            bytes = new MessagePackMapper(new MessagePackFactory()).writeValueAsBytes(value);
        }
        catch (JacksonException e) {
            e.printStackTrace();
        }
        packedByMsgPack = bytes;
    }

    @Test
    public void testBenchmark()
            throws Exception
    {
        Benchmarker benchmarker = new Benchmarker();

        File tempFileJackson = File.createTempFile("msgpack-jackson-", "-huge-jackson");
        tempFileJackson.deleteOnExit();
        final OutputStream outputStreamJackson = new FileOutputStream(tempFileJackson);

        File tempFileMsgpack = File.createTempFile("msgpack-jackson-", "-huge-msgpack");
        tempFileMsgpack.deleteOnExit();
        final OutputStream outputStreamMsgpack = new FileOutputStream(tempFileMsgpack);

        benchmarker.addBenchmark(new Benchmarker.Benchmarkable("serialize(huge) with JSON") {
            @Override
            public void run()
                    throws Exception
            {
                origObjectMapper.writeValue(outputStreamJackson, value);
            }
        });

        benchmarker.addBenchmark(new Benchmarker.Benchmarkable("serialize(huge) with MessagePack") {
            @Override
            public void run()
                    throws Exception
            {
                msgpackObjectMapper.writeValue(outputStreamMsgpack, value);
            }
        });

        benchmarker.addBenchmark(new Benchmarker.Benchmarkable("deserialize(huge) with JSON") {
            @Override
            public void run()
                    throws Exception
            {
                origObjectMapper.readValue(packedByOriginal, new TypeReference<List<Object>>() {});
            }
        });

        benchmarker.addBenchmark(new Benchmarker.Benchmarkable("deserialize(huge) with MessagePack") {
            @Override
            public void run()
                    throws Exception
            {
                msgpackObjectMapper.readValue(packedByMsgPack, new TypeReference<List<Object>>() {});
            }
        });

        try {
            benchmarker.run(COUNT, WARMUP_COUNT);
        }
        finally {
            outputStreamJackson.close();
            outputStreamMsgpack.close();
        }
    }
}
