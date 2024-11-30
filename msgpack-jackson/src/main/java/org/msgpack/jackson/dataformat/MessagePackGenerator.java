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
import org.msgpack.core.annotations.Nullable;
import org.msgpack.core.buffer.MessageBufferOutput;
import org.msgpack.core.buffer.OutputStreamBufferOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MessagePackGenerator
        extends GeneratorBase
{
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private final MessagePacker messagePacker;
    private static final ThreadLocal<OutputStreamBufferOutput> messageBufferOutputHolder = new ThreadLocal<>();
    private final OutputStream output;
    private final MessagePack.PackerConfig packerConfig;

    private int currentParentElementIndex = -1;
    private final List<Element> elements;
    private boolean isElementsClosed = false;

    private static final boolean STRING_VALUE_FIELD_IS_CHARS;
    static {
        boolean stringValueFieldIsChars = false;
        try {
            Field stringValueField = String.class.getDeclaredField("value");
            stringValueFieldIsChars = stringValueField.getType() == char[].class;
        }
        catch (NoSuchFieldException ignored) {
        }
        STRING_VALUE_FIELD_IS_CHARS = stringValueFieldIsChars;
    }

    private static class AsciiCharString
    {
        public final byte[] bytes;

        public AsciiCharString(byte[] bytes)
        {
            this.bytes = bytes;
        }
    }

    private static final byte NON_CONTAINER = 0;
    private static final byte CONTAINER_OBJECT = 1;
    private static final byte CONTAINER_ARRAY = 2;

    private static final class Element
    {
        // Root containers have -1.
        final int parentIndex;
        final byte containerType;
        // Only for containers.
        int childCount;
        // Only for non-containers.
        @Nullable Object data;

        public Element(int parentIndex, byte containerType)
        {
            this.parentIndex = parentIndex;
            this.containerType = containerType;
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
        this.elements = new ArrayList<>();
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
        this.elements = new ArrayList<>();
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
        startCurrentContainer(CONTAINER_ARRAY);
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
        startCurrentContainer(CONTAINER_OBJECT);
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

    private void startCurrentContainer(byte containerType)
    {
        switch (containerType) {
            case CONTAINER_OBJECT:
                _writeContext = _writeContext.createChildObjectContext();
                break;
            case CONTAINER_ARRAY:
                _writeContext = _writeContext.createChildArrayContext();
                break;
            default:
                throw new AssertionError();
        }
        elements.add(new Element(currentParentElementIndex, containerType));
        currentParentElementIndex = elements.size() - 1;
    }

    private void endCurrentContainer()
    {
        Element parent = elements.get(currentParentElementIndex);
        parent.childCount = _writeContext.getEntryCount();
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
        if (v == null) {
            messagePacker.packNil();
        }
        else if (v instanceof Integer) {
            messagePacker.packInt((Integer) v);
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
        else if (v instanceof AsciiCharString) {
            byte[] bytes = ((AsciiCharString) v).bytes;
            messagePacker.packRawStringHeader(bytes.length);
            messagePacker.writePayload(bytes);
        }
        else if (v instanceof String) {
            messagePacker.packString((String) v);
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

    private void packObject(Element container)
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        messagePacker.packMapHeader(container.childCount);
    }

    private void packArray(Element container)
            throws IOException
    {
        MessagePacker messagePacker = getMessagePacker();
        messagePacker.packArrayHeader(container.childCount);
    }

    @Nullable
    private byte[] getBytesIfAscii(char[] chars, int offset, int len)
    {
        byte[] bytes = new byte[len];
        for (int i = offset; i < offset + len; i++) {
            char c = chars[i];
            if (c >= 0x80) {
                return null;
            }
            bytes[i] = (byte) c;
        }
        return bytes;
    }

    private boolean areAllAsciiBytes(byte[] bytes, int offset, int len)
    {
        for (int i = offset; i < offset + len; i++) {
            if ((bytes[i] & 0x80) != 0) {
                return false;
            }
        }
        return true;
    }

    private void addContainerElement(Object data)
    {
        Element element = new Element(currentParentElementIndex, NON_CONTAINER);
        element.data = data;
        elements.add(element);
    }

    private void addElementKey(Object key)
    {
        if (!_writeContext.inObject()) {
            throw new IllegalStateException();
        }
        addContainerElement(key);
    }

    private void addElementValue(Object value) throws IOException
    {
        if (_writeContext.inObject() || _writeContext.inArray()) {
            addContainerElement(value);
        }
        else {
            pack(value);
            flushMessagePacker();
        }
    }

    @Override
    public void writeFieldName(String name) throws IOException
    {
        if (STRING_VALUE_FIELD_IS_CHARS) {
            char[] chars = name.toCharArray();
            writeCharArrayTextKey(chars, 0, chars.length);
        }
        else {
            addElementKey(name);
        }
        _writeContext.writeFieldName(name);
    }

    @Override
    public void writeFieldName(SerializableString name) throws IOException
    {
        if (name instanceof MessagePackSerializedString) {
            addElementKey(((MessagePackSerializedString) name).getRawValue());
            _writeContext.writeFieldName(name.getValue());
        }
        else if (name instanceof SerializedString) {
            writeFieldName(name.getValue());
        }
        else {
            throw new IllegalArgumentException("Unsupported key: " + name);
        }
    }

    private void writeCharArrayTextKey(char[] text, int offset, int len)
    {
        byte[] bytes = getBytesIfAscii(text, offset, len);
        if (bytes != null) {
            addElementKey(new AsciiCharString(bytes));
            return;
        }
        addElementKey(new String(text, offset, len));
    }

    private void writeCharArrayTextValue(char[] text, int offset, int len) throws IOException
    {
        byte[] bytes = getBytesIfAscii(text, offset, len);
        if (bytes != null) {
            addElementValue(new AsciiCharString(bytes));
            return;
        }
        addElementValue(new String(text, offset, len));
    }

    private void writeByteArrayTextValue(byte[] text, int offset, int len) throws IOException
    {
        if (areAllAsciiBytes(text, offset, len)) {
            addElementValue(new AsciiCharString(text));
            return;
        }
        addElementValue(new String(text, offset, len, DEFAULT_CHARSET));
    }

    @Override
    public void writeString(String text)
            throws IOException
    {
        if (STRING_VALUE_FIELD_IS_CHARS) {
            char[] chars = text.toCharArray();
            writeCharArrayTextValue(chars, 0, chars.length);
        }
        else {
            addElementValue(text);
        }
        _writeContext.writeValue();
    }

    @Override
    public void writeString(char[] text, int offset, int len)
            throws IOException
    {
        writeCharArrayTextValue(text, offset, len);
        _writeContext.writeValue();
    }

    @Override
    public void writeRawUTF8String(byte[] text, int offset, int length)
            throws IOException
    {
        writeByteArrayTextValue(text, offset, length);
        _writeContext.writeValue();
    }

    @Override
    public void writeUTF8String(byte[] text, int offset, int length)
            throws IOException
    {
        writeByteArrayTextValue(text, offset, length);
        _writeContext.writeValue();
    }

    @Override
    public void writeRaw(String text)
            throws IOException
    {
        if (STRING_VALUE_FIELD_IS_CHARS) {
            char[] chars = text.toCharArray();
            writeCharArrayTextValue(chars, 0, chars.length);
        }
        else {
            addElementValue(text);
        }
        _writeContext.writeValue();
    }

    @Override
    public void writeRaw(String text, int offset, int len)
            throws IOException
    {
        // TODO: There is room to optimize this.
        char[] chars = text.toCharArray();
        writeCharArrayTextValue(chars, offset, len);
        _writeContext.writeValue();
    }

    @Override
    public void writeRaw(char[] text, int offset, int len)
            throws IOException
    {
        writeCharArrayTextValue(text, offset, len);
        _writeContext.writeValue();
    }

    @Override
    public void writeRaw(char c)
            throws IOException
    {
        writeCharArrayTextValue(new char[] { c }, 0, 1);
        _writeContext.writeValue();
    }

    @Override
    public void writeBinary(Base64Variant b64variant, byte[] data, int offset, int len)
            throws IOException
    {
        addElementValue(ByteBuffer.wrap(data, offset, len));
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(int v)
            throws IOException
    {
        addElementValue(v);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(long v)
            throws IOException
    {
        addElementValue(v);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(BigInteger v)
            throws IOException
    {
        addElementValue(v);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(double d)
            throws IOException
    {
        addElementValue(d);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(float f)
            throws IOException
    {
        addElementValue(f);
        _writeContext.writeValue();
    }

    @Override
    public void writeNumber(BigDecimal dec)
            throws IOException
    {
        addElementValue(dec);
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
            addElementValue(l);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        try {
            double d = Double.parseDouble(encodedValue);
            addElementValue(d);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        try {
            BigInteger bi = new BigInteger(encodedValue);
            addElementValue(bi);
            _writeContext.writeValue();
            return;
        }
        catch (NumberFormatException ignored) {
        }

        try {
            BigDecimal bc = new BigDecimal(encodedValue);
            addElementValue(bc);
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
        addElementValue(state);
        _writeContext.writeValue();
    }

    @Override
    public void writeNull()
            throws IOException
    {
        addElementValue(null);
        _writeContext.writeValue();
    }

    public void writeExtensionType(MessagePackExtensionType extensionType)
            throws IOException
    {
        addElementValue(extensionType);
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
            super.close();
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

        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            switch (element.containerType) {
                case NON_CONTAINER:
                    pack(element.data);
                    break;
                case CONTAINER_OBJECT:
                    packObject(element);
                    break;
                case CONTAINER_ARRAY:
                    packArray(element);
                    break;
                default:
                    throw new AssertionError();
            }
        }
        flushMessagePacker();
        elements.clear();
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
