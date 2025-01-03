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

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.base.GeneratorBase;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.json.JsonWriteContext;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.buffer.MessageBufferOutput;
import org.msgpack.core.buffer.OutputStreamBufferOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MessagePackGenerator
        extends GeneratorBase
{
    private final MessagePacker messagePacker;
    private static final ThreadLocal<OutputStreamBufferOutput> messageBufferOutputHolder = new ThreadLocal<>();
    private final OutputStream output;
    private final MessagePack.PackerConfig packerConfig;

    private int currentParentElementIndex = -1;
    private final List<Node> nodes;
    private boolean isElementsClosed = false;

    private abstract static class Node
    {
        // Root containers have -1.
        final int parentIndex;

        public Node(int parentIndex)
        {
            this.parentIndex = parentIndex;
        }
    }

    private abstract static class NodeContainer extends Node
    {
        // Only for containers.
        int childCount;

        public NodeContainer(int parentIndex)
        {
            super(parentIndex);
        }
    }

    private static class NodeArray extends NodeContainer
    {
        public NodeArray(int parentIndex)
        {
            super(parentIndex);
        }
    }

    private static class NodeObject extends NodeContainer
    {
        public NodeObject(int parentIndex)
        {
            super(parentIndex);
        }
    }

    private static class NodeEntryInArray extends Node
    {
        final Object value;

        public NodeEntryInArray(int parentIndex, Object value)
        {
            super(parentIndex);
            this.value = value;
        }
    }

    private static class NodeEntryInObject extends Node
    {
        final Object key;
        // Lazily initialized.
        Object value;

        public NodeEntryInObject(int parentIndex, Object key)
        {
            super(parentIndex);
            this.key = key;
        }
    }

    // This is an internal constructor for nested serialization.
    private MessagePackGenerator(
            int features,
            ObjectCodec codec,
            OutputStream out,
            MessagePack.PackerConfig packerConfig)
    {
        super(features, codec);
        this.output = out;
        this.messagePacker = packerConfig.newPacker(out);
        this.packerConfig = packerConfig;
        this.nodes = new ArrayList<>();
    }

    public MessagePackGenerator(
            int features,
            ObjectCodec codec,
            OutputStream out,
            MessagePack.PackerConfig packerConfig,
            boolean reuseResourceInGenerator)
            throws IOException
    {
        super(features, codec);
        this.output = out;
        this.messagePacker = packerConfig.newPacker(getMessageBufferOutputForOutputStream(out, reuseResourceInGenerator));
        this.packerConfig = packerConfig;
        this.nodes = new ArrayList<>();
    }

    private MessageBufferOutput getMessageBufferOutputForOutputStream(
            OutputStream out,
            boolean reuseResourceInGenerator)
            throws IOException
    {
        OutputStreamBufferOutput messageBufferOutput;
        if (reuseResourceInGenerator) {
            messageBufferOutput = messageBufferOutputHolder.get();
            if (messageBufferOutput == null) {
                messageBufferOutput = new OutputStreamBufferOutput(out);
                messageBufferOutputHolder.set(messageBufferOutput);
            }
            else {
                messageBufferOutput.reset(out);
            }
        }
        else {
            messageBufferOutput = new OutputStreamBufferOutput(out);
        }
        return messageBufferOutput;
    }

    @Override
    public void writeStartArray()
    {
        _writeContext = _writeContext.createChildArrayContext();
        nodes.add(new NodeArray(currentParentElementIndex));
        currentParentElementIndex = nodes.size() - 1;
    }

    @Override
    public void writeEndArray()
            throws IOException
    {
        if (!_writeContext.inArray()) {
            _reportError("Current context not an array but " + _writeContext.getTypeDesc());
        }
        endCurrentContainer();
    }

    @Override
    public void writeStartObject()
    {
        _writeContext = _writeContext.createChildObjectContext();
        nodes.add(new NodeObject(currentParentElementIndex));
        currentParentElementIndex = nodes.size() - 1;
    }

    @Override
    public void writeEndObject()
            throws IOException
    {
        if (!_writeContext.inObject()) {
            _reportError("Current context not an object but " + _writeContext.getTypeDesc());
        }
        endCurrentContainer();
    }

    private void endCurrentContainer()
    {
        Node parent = nodes.get(currentParentElementIndex);
        assert parent instanceof NodeContainer;
        NodeContainer parentContainer = (NodeContainer) parent;
        parentContainer.childCount = _writeContext.getEntryCount();
        if (currentParentElementIndex == 0) {
            isElementsClosed = true;
        }
        currentParentElementIndex = parent.parentIndex;
        _writeContext = _writeContext.getParent();
        _writeContext.writeValue();
    }

    private void pack(Object v)
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        if (v instanceof String) {
            messagePacker.packString((String) v);
        }
        else if (v instanceof Integer) {
            messagePacker.packInt((Integer) v);
        }
        else if (v == null) {
            messagePacker.packNil();
        }
        else if (v instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) v;
            int len = bb.remaining();
            if (bb.hasArray()) {
                messagePacker.packBinaryHeader(len);
                messagePacker.writePayload(bb.array(), bb.arrayOffset(), len);
            }
            else {
                byte[] data = new byte[len];
                bb.get(data);
                messagePacker.packBinaryHeader(len);
                messagePacker.addPayload(data);
            }
        }
        else if (v instanceof Float) {
            messagePacker.packFloat((Float) v);
        }
        else if (v instanceof Long) {
            messagePacker.packLong((Long) v);
        }
        else if (v instanceof Double) {
            messagePacker.packDouble((Double) v);
        }
        else if (v instanceof BigInteger) {
            messagePacker.packBigInteger((BigInteger) v);
        }
        else if (v instanceof BigDecimal) {
            packBigDecimal((BigDecimal) v);
        }
        else if (v instanceof Boolean) {
            messagePacker.packBoolean((Boolean) v);
        }
        else if (v instanceof MessagePackExtensionType) {
            MessagePackExtensionType extensionType = (MessagePackExtensionType) v;
            byte[] extData = extensionType.getData();
            messagePacker.packExtensionTypeHeader(extensionType.getType(), extData.length);
            messagePacker.writePayload(extData);
        }
        else {
            messagePacker.flush();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MessagePackGenerator messagePackGenerator = new MessagePackGenerator(getFeatureMask(), getCodec(), outputStream, packerConfig);
            getCodec().writeValue(messagePackGenerator, v);
            output.write(outputStream.toByteArray());
        }
    }

    private void packBigDecimal(BigDecimal decimal)
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        boolean failedToPackAsBI = false;
        try {
            //Check to see if this BigDecimal can be converted to BigInteger
            BigInteger integer = decimal.toBigIntegerExact();
            messagePacker.packBigInteger(integer);
        }
        catch (ArithmeticException e) {
            failedToPackAsBI = true;
        }
        catch (IllegalArgumentException e) {
            failedToPackAsBI = true;
        }

        if (failedToPackAsBI) {
            double doubleValue = decimal.doubleValue();
            //Check to make sure this BigDecimal can be represented as a double
            if (!decimal.stripTrailingZeros().toEngineeringString().equals(
                    BigDecimal.valueOf(doubleValue).stripTrailingZeros().toEngineeringString())) {
                throw new IllegalArgumentException("MessagePack cannot serialize a BigDecimal that can't be represented as double. " + decimal);
            }
            messagePacker.packDouble(doubleValue);
        }
    }

    private void packObject(NodeObject container)
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        messagePacker.packMapHeader(container.childCount);
    }

    private void packArray(NodeArray container)
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        messagePacker.packArrayHeader(container.childCount);
    }

    private void addKeyToStackTop(Object key)
    {
        if (!_writeContext.inObject()) {
            throw new IllegalStateException();
        }
        Node node = new NodeEntryInObject(currentParentElementIndex, key);
        nodes.add(node);
    }

    private void addValueToStackTop(Object value) throws IOException
    {
        if (_writeContext.inObject()) {
            Node node = nodes.get(nodes.size() - 1);
            assert node instanceof NodeEntryInObject;
            NodeEntryInObject nodeEntryInObject = (NodeEntryInObject) node;
            nodeEntryInObject.value = value;
        }
        else if (_writeContext.inArray()) {
            Node node = new NodeEntryInArray(currentParentElementIndex, value);
            nodes.add(node);
        }
        else {
            pack(value);
            flushMessagePacker();
        }
    }

    @Override
    public void writeFieldName(String name) throws IOException
    {
        addKeyToStackTop(name);
        _writeContext.writeFieldName(name);
    }

    @Override
    public void writeFieldName(SerializableString name) throws IOException
    {
        if (name instanceof MessagePackSerializedString) {
            addKeyToStackTop(((MessagePackSerializedString) name).getRawValue());
            _writeContext.writeFieldName(name.getValue());
        }
        else if (name instanceof SerializedString) {
            writeFieldName(name.getValue());
        }
        else {
            throw new IllegalArgumentException("Unsupported key: " + name);
        }
    }

    @Override
    public void writeString(String text)
            throws IOException
    {
        addValueToStackTop(text);
        _writeContext.writeValue();
    }

    @Override
    public void writeString(char[] text, int offset, int len)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeRawUTF8String(byte[] text, int offset, int length)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeUTF8String(byte[] text, int offset, int length)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeRaw(String text)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeRaw(String text, int offset, int len)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeRaw(char[] text, int offset, int len)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeRaw(char c)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeBinary(Base64Variant b64variant, byte[] data, int offset, int len)
            throws IOException
    {
        if (true) {
            throw new RuntimeException();
        }
    }

    @Override
    public void writeNumber(int v)
            throws IOException
    {
        addValueToStackTop(v);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(long v)
            throws IOException
    {
        addValueToStackTop(v);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(BigInteger v)
            throws IOException
    {
        addValueToStackTop(v);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(double d)
            throws IOException
    {
        addValueToStackTop(d);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(float f)
            throws IOException
    {
        addValueToStackTop(f);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(BigDecimal dec)
            throws IOException
    {
        addValueToStackTop(dec);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(String encodedValue)
            throws IOException, UnsupportedOperationException
    {
        // There is a room to improve this API's performance while the implementation is robust.
        // If users can use other MessagePackGenerator#writeNumber APIs that accept
        // proper numeric types not String, it's better to use the other APIs instead.
        try {
            long l = Long.parseLong(encodedValue);
            addValueToStackTop(l);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        try {
            double d = Double.parseDouble(encodedValue);
            addValueToStackTop(d);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        try {
            BigInteger bi = new BigInteger(encodedValue);
            addValueToStackTop(bi);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        try {
            BigDecimal bc = new BigDecimal(encodedValue);
            addValueToStackTop(bc);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        throw new NumberFormatException(encodedValue);
    }

    @Override
    public void writeBoolean(boolean state)
            throws IOException
    {
        addValueToStackTop(state);
        _writeContext.writeValue();
    }

    @Override
    public void writeNull()
            throws IOException
    {
        addValueToStackTop(null);
        _writeContext.writeValue();
    }

    public void writeExtensionType(MessagePackExtensionType extensionType)
            throws IOException
    {
        addValueToStackTop(extensionType);
        _writeContext.writeValue();
    }

    @Override
    public void close()
            throws IOException
    {
        try {
            flush();
        }
        finally {
            if (isEnabled(Feature.AUTO_CLOSE_TARGET)) {
                MessagePacker messagePacker = getMessagePacker();
                messagePacker.close();
            }
        }
    }

    @Override
    public void flush()
            throws IOException
    {
        if (!isElementsClosed) {
            // The whole elements are not closed yet.
            return;
        }

        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof NodeObject) {
                packObject((NodeObject) node);
            }
            else if (node instanceof NodeEntryInObject) {
                NodeEntryInObject nodeEntry = (NodeEntryInObject) node;
                pack(nodeEntry.key);
                pack(nodeEntry.value);
            }
            else if (node instanceof NodeArray) {
                packArray((NodeArray) node);
            }
            else if (node instanceof NodeEntryInArray) {
                pack(((NodeEntryInArray) node).value);
            }
            else {
                throw new AssertionError();
            }
        }
        flushMessagePacker();
        nodes.clear();
        isElementsClosed = false;
    }

    private void flushMessagePacker()
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        messagePacker.flush();
    }

    @Override
    protected void _releaseBuffers()
    {
        try {
            messagePacker.close();
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to close MessagePacker", e);
        }
    }

    @Override
    protected void _verifyValueWrite(String typeMsg)
            throws IOException
    {
        int status = _writeContext.writeValue();
        if (status == JsonWriteContext.STATUS_EXPECT_NAME) {
            _reportError("Can not " + typeMsg + ", expecting field name");
        }
    }

    private MessagePacker getMessagePacker()
    {
        return messagePacker;
    }
}
