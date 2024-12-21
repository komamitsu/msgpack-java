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
package org.msgpack.jackson.dataformat;

import com.fasterxml.jackson.core.io.IOContext;
import org.msgpack.core.buffer.MessageBuffer;
import org.msgpack.core.buffer.MessageBufferInput;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.msgpack.core.Preconditions.checkNotNull;

/**
 * {@link MessageBufferInput} adapter for {@link InputStream}
 */
public class JacksonBufferInput
        implements MessageBufferInput
{
    private final InputStream in;
    private final byte[] buffer;
    private final IOContext ioContext;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    public JacksonBufferInput(InputStream in, IOContext ioContext)
    {
        this(in, ioContext, 8192);
    }

    public JacksonBufferInput(InputStream in, IOContext ioContext, int bufferSize)
    {
        this.in = checkNotNull(in, "input is null");
        this.buffer = ioContext.allocReadIOBuffer(bufferSize);
        this.ioContext = ioContext;
    }

    @Override
    public MessageBuffer next()
            throws IOException
    {
        int readLen = in.read(buffer);
        if (readLen == -1) {
            return null;
        }
        return MessageBuffer.wrap(buffer, 0, readLen);
    }

    @Override
    public void close()
            throws IOException
    {
        try {
            in.close();
        }
        finally {
            if (!isClosed.get()) {
                ioContext.releaseReadIOBuffer(buffer);
            }
            isClosed.set(true);
        }
    }
}
