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
import org.msgpack.core.buffer.MessageBufferOutput;

import java.io.IOException;
import java.io.OutputStream;

import static org.msgpack.core.Preconditions.checkNotNull;

public class JacksonBufferOutput implements MessageBufferOutput
{
    private OutputStream out;
    private IOContext ioContext;
    private MessageBuffer messageBuffer;
    private byte[] bytes;

    public JacksonBufferOutput(OutputStream out, IOContext ioContext)
    {
        this.out = checkNotNull(out, "output is null");
        this.ioContext = checkNotNull(ioContext, "ioContext is null");
    }

    @Override
    public MessageBuffer next(int minimumSize)
    {
        if (messageBuffer == null || messageBuffer.size() < minimumSize) {
            ioContext.releaseReadIOBuffer(bytes);
            bytes = ioContext.allocReadIOBuffer(minimumSize);
            messageBuffer = MessageBuffer.wrap(bytes);
        }
        return messageBuffer;
    }

    @Override
    public void writeBuffer(int length) throws IOException
    {
        write(messageBuffer.array(), messageBuffer.arrayOffset(), length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException
    {
        out.write(buffer, offset, length);
    }

    @Override
    public void add(byte[] buffer, int offset, int length) throws IOException
    {
        write(buffer, offset, length);
    }

    @Override
    public void close() throws IOException
    {
        try {
            out.close();
        }
        finally {
            ioContext.releaseReadIOBuffer(bytes);
            bytes = null;
            messageBuffer = null;
        }
    }

    @Override
    public void flush() throws IOException
    {
        out.flush();
    }

    /**
     * Reset Stream. This method doesn't close the old stream.
     *
     * @param out new stream
     * @param ioContext new IOContext
     */
    public void reset(OutputStream out, IOContext ioContext)
            throws IOException
    {
        if (bytes != null) {
            this.ioContext.releaseReadIOBuffer(bytes);
            bytes = null;
            this.messageBuffer = null;
        }
        this.out = out;
        this.ioContext = ioContext;
    }
}
