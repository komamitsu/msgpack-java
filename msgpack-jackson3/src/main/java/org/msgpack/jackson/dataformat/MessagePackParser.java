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

import tools.jackson.core.Base64Variant;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.Version;
import tools.jackson.core.base.ParserBase;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.json.DupDetector;
import org.msgpack.core.ExtensionTypeHeader;
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.buffer.MessageBufferInput;
import org.msgpack.value.ValueType;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

public class MessagePackParser
        extends ParserBase
{
    private static final ThreadLocal<Tuple<Object, MessageUnpacker>> messageUnpackerHolder = new ThreadLocal<>();
    private final MessageUnpacker messageUnpacker;

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private MessagePackReadContext streamReadContext;

    private long tokenPosition;
    private long currentPosition;
    private final IOContext ioContext;
    private ExtensionTypeCustomDeserializers extTypeCustomDesers;
    private final boolean ownsThreadLocalUnpacker;

    private enum Type
    {
        INT, LONG, DOUBLE, STRING, BYTES, BOOL, BIG_INT, EXT, NULL
    }
    private Type type;
    private boolean booleanValue;
    private byte[] bytesValue;
    private String stringValue;
    private MessagePackExtensionType extensionTypeValue;

    MessagePackParser(ObjectReadContext readCtxt,
            IOContext ioCtxt,
            int streamReadFeatures,
            MessageBufferInput input,
            Object src,
            boolean reuseResourceInParser)
            throws IOException
    {
        super(readCtxt, ioCtxt, streamReadFeatures);

        ioContext = ioCtxt;
        DupDetector dups = StreamReadFeature.STRICT_DUPLICATE_DETECTION.enabledIn(streamReadFeatures)
                ? DupDetector.rootDetector(this) : null;
        streamReadContext = MessagePackReadContext.createRootContext(dups);
        if (!reuseResourceInParser) {
            messageUnpacker = MessagePack.newDefaultUnpacker(input);
            ownsThreadLocalUnpacker = false;
            return;
        }

        Tuple<Object, MessageUnpacker> messageUnpackerTuple = messageUnpackerHolder.get();
        if (messageUnpackerTuple == null) {
            messageUnpacker = MessagePack.newDefaultUnpacker(input);
        }
        else {
            // Considering to reuse InputStream with StreamReadFeature.AUTO_CLOSE_SOURCE,
            // MessagePackParser needs to use the MessageUnpacker that has the same InputStream
            // since it has buffer which has loaded the InputStream data ahead.
            // However, it needs to call MessageUnpacker#reset when the source is different from the previous one.
            if (StreamReadFeature.AUTO_CLOSE_SOURCE.enabledIn(streamReadFeatures) || messageUnpackerTuple.first() != src || src instanceof byte[]) {
                messageUnpackerTuple.second().reset(input);
            }
            messageUnpacker = messageUnpackerTuple.second();
        }
        messageUnpackerHolder.set(new Tuple<>(src, messageUnpacker));
        ownsThreadLocalUnpacker = true;
    }

    public void setExtensionTypeCustomDeserializers(ExtensionTypeCustomDeserializers extTypeCustomDesers)
    {
        this.extTypeCustomDesers = extTypeCustomDesers;
    }

    @Override
    public Version version()
    {
        return PackageVersion.VERSION;
    }

    private String unpackString(MessageUnpacker messageUnpacker) throws IOException
    {
        return messageUnpacker.unpackString();
    }

    @Override
    public JsonToken nextToken() throws JacksonException
    {
        try {
            return _nextToken();
        }
        catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    private JsonToken _nextToken() throws IOException
    {
        _numTypesValid = NR_UNKNOWN;
        tokenPosition = messageUnpacker.getTotalReadBytes();

        boolean isObjectValueSet = streamReadContext.inObject() && _currToken != JsonToken.PROPERTY_NAME;
        if (isObjectValueSet) {
            if (!streamReadContext.expectMoreValues()) {
                streamReadContext = streamReadContext.getParent();
                return _updateToken(JsonToken.END_OBJECT);
            }
        }
        else if (streamReadContext.inArray()) {
            if (!streamReadContext.expectMoreValues()) {
                streamReadContext = streamReadContext.getParent();
                return _updateToken(JsonToken.END_ARRAY);
            }
        }

        if (!messageUnpacker.hasNext()) {
            if (streamReadContext.inRoot()) {
                return null;
            }
            throw new UnexpectedEndOfInputException(this, null, null);
        }

        MessageFormat format = messageUnpacker.getNextFormat();
        ValueType valueType = format.getValueType();

        JsonToken nextToken;
        switch (valueType) {
            case STRING:
                type = Type.STRING;
                stringValue = unpackString(messageUnpacker);
                _streamReadConstraints.validateStringLength(stringValue.length());
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(stringValue);
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_STRING;
                }
                break;
            case INTEGER:
                Object v;
                switch (format) {
                    case UINT64:
                        BigInteger bi = messageUnpacker.unpackBigInteger();
                        if (0 <= bi.compareTo(LONG_MIN) && bi.compareTo(LONG_MAX) <= 0) {
                            type = Type.LONG;
                            _numberLong = bi.longValue();
                            _numTypesValid = NR_LONG;
                            v = _numberLong;
                        }
                        else {
                            type = Type.BIG_INT;
                            _numberBigInt = bi;
                            _numTypesValid = NR_BIGINT;
                            v = _numberBigInt;
                        }
                        break;
                    default:
                        long l = messageUnpacker.unpackLong();
                        if (Integer.MIN_VALUE <= l && l <= Integer.MAX_VALUE) {
                            type = Type.INT;
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            v = _numberInt;
                        }
                        else {
                            type = Type.LONG;
                            _numberLong = l;
                            _numTypesValid = NR_LONG;
                            v = _numberLong;
                        }
                        break;
                }

                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(String.valueOf(v));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_NUMBER_INT;
                }
                break;
            case NIL:
                type = Type.NULL;
                messageUnpacker.unpackNil();
                nextToken = JsonToken.VALUE_NULL;
                break;
            case BOOLEAN:
                boolean b = messageUnpacker.unpackBoolean();
                type = Type.BOOL;
                booleanValue = b;
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(Boolean.toString(b));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = b ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
                break;
            case FLOAT:
                type = Type.DOUBLE;
                _numberDouble = messageUnpacker.unpackDouble();
                _numTypesValid = NR_DOUBLE;
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(String.valueOf(_numberDouble));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_NUMBER_FLOAT;
                }
                break;
            case BINARY:
                type = Type.BYTES;
                int len = messageUnpacker.unpackBinaryHeader();
                _streamReadConstraints.validateStringLength(len);
                bytesValue = messageUnpacker.readPayload(len);
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(new String(bytesValue, MessagePack.UTF8));
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_EMBEDDED_OBJECT;
                }
                break;
            case ARRAY:
                nextToken = JsonToken.START_ARRAY;
                streamReadContext = streamReadContext.createChildArrayContext(messageUnpacker.unpackArrayHeader());
                _streamReadConstraints.validateNestingDepth(streamReadContext.getNestingDepth());
                break;
            case MAP:
                nextToken = JsonToken.START_OBJECT;
                streamReadContext = streamReadContext.createChildObjectContext(messageUnpacker.unpackMapHeader());
                _streamReadConstraints.validateNestingDepth(streamReadContext.getNestingDepth());
                break;
            case EXTENSION:
                type = Type.EXT;
                ExtensionTypeHeader header = messageUnpacker.unpackExtensionTypeHeader();
                _streamReadConstraints.validateStringLength(header.getLength());
                extensionTypeValue = new MessagePackExtensionType(header.getType(), messageUnpacker.readPayload(header.getLength()));
                if (isObjectValueSet) {
                    streamReadContext.setCurrentName(deserializedExtensionTypeValue().toString());
                    nextToken = JsonToken.PROPERTY_NAME;
                }
                else {
                    nextToken = JsonToken.VALUE_EMBEDDED_OBJECT;
                }
                break;
            default:
                nextToken = _reportError("Unexpected MessagePack format type: " + valueType);
        }
        currentPosition = messageUnpacker.getTotalReadBytes();

        _updateToken(nextToken);

        return nextToken;
    }

    @Override
    protected void _handleEOF()
    {
    }

    @Override
    public String getString()
    {
        switch (type) {
            case STRING:
                return stringValue;
            case BYTES:
                return new String(bytesValue, MessagePack.UTF8);
            case INT:
                return String.valueOf(_numberInt);
            case LONG:
                return String.valueOf(_numberLong);
            case DOUBLE:
                return String.valueOf(_numberDouble);
            case BOOL:
                return Boolean.toString(booleanValue);
            case BIG_INT:
                return String.valueOf(_numberBigInt);
            case EXT:
                try {
                    return deserializedExtensionTypeValue().toString();
                }
                catch (IOException e) {
                    throw _wrapIOFailure(e);
                }
            case NULL:
                return "null";
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public char[] getStringCharacters()
    {
        return getString().toCharArray();
    }

    @Override
    public boolean hasStringCharacters()
    {
        return false;
    }

    @Override
    public int getStringLength()
    {
        return getString().length();
    }

    @Override
    public int getStringOffset()
    {
        return 0;
    }

    @Override
    public byte[] getBinaryValue(Base64Variant b64variant)
    {
        switch (type) {
            case BYTES:
                return bytesValue;
            case STRING:
                return stringValue.getBytes(MessagePack.UTF8);
            case EXT:
                return extensionTypeValue.getData();
            case INT:
            case LONG:
            case DOUBLE:
            case BOOL:
            case BIG_INT:
            case NULL:
                return _reportError("Current token (" + _currToken + ") not of binary type");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    // getNumberValue(), getFloatValue(), getDoubleValue() are inherited from ParserBase.

    @Override
    public int getIntValue() throws JacksonException
    {
        if ((_numTypesValid & NR_INT) != 0) {
            return _numberInt;
        }
        if (_numTypesValid == NR_UNKNOWN) {
            _parseNumericValue(NR_INT);
        }
        if ((_numTypesValid & NR_INT) == 0) {
            // Conversion from LONG, DOUBLE, or BIGINT — need NaN/range checks.
            if ((_numTypesValid & NR_DOUBLE) != 0) {
                if (!Double.isFinite(_numberDouble)) {
                    return _reportError("Cannot convert non-finite double (" + _numberDouble + ") to `int`");
                }
                if (_numberDouble < Integer.MIN_VALUE || _numberDouble > Integer.MAX_VALUE) {
                    return _reportError("Numeric value (" + _numberDouble + ") out of range for `int`");
                }
            }
            convertNumberToInt();
        }
        return _numberInt;
    }

    @Override
    public long getLongValue() throws JacksonException
    {
        if ((_numTypesValid & NR_LONG) != 0) {
            return _numberLong;
        }
        if (_numTypesValid == NR_UNKNOWN) {
            _parseNumericValue(NR_LONG);
        }
        if ((_numTypesValid & NR_LONG) == 0) {
            if ((_numTypesValid & NR_DOUBLE) != 0) {
                if (!Double.isFinite(_numberDouble)) {
                    return _reportError("Cannot convert non-finite double (" + _numberDouble + ") to `long`");
                }
                if (_numberDouble < Long.MIN_VALUE || _numberDouble > Long.MAX_VALUE) {
                    return _reportError("Numeric value (" + _numberDouble + ") out of range for `long`");
                }
            }
            convertNumberToLong();
        }
        return _numberLong;
    }

    @Override
    public BigInteger getBigIntegerValue() throws JacksonException
    {
        if ((_numTypesValid & NR_BIGINT) != 0) {
            return _numberBigInt;
        }
        if (_numTypesValid == NR_UNKNOWN) {
            _parseNumericValue(NR_BIGINT);
        }
        if ((_numTypesValid & NR_BIGINT) == 0) {
            if ((_numTypesValid & NR_DOUBLE) != 0) {
                if (!Double.isFinite(_numberDouble)) {
                    return _reportError("Cannot convert non-finite double (" + _numberDouble + ") to BigInteger");
                }
                // truncates fractional part
                _numberBigInt = BigDecimal.valueOf(_numberDouble).toBigInteger();
                _numTypesValid |= NR_BIGINT;
                return _numberBigInt;
            }
            convertNumberToBigInteger();
        }
        return _numberBigInt;
    }

    @Override
    public BigDecimal getDecimalValue() throws JacksonException
    {
        if ((_numTypesValid & NR_BIGDECIMAL) != 0) {
            return _numberBigDecimal;
        }
        if (_numTypesValid == NR_UNKNOWN) {
            _parseNumericValue(NR_BIGDECIMAL);
        }
        if ((_numTypesValid & NR_BIGDECIMAL) == 0) {
            if ((_numTypesValid & NR_DOUBLE) != 0) {
                if (!Double.isFinite(_numberDouble)) {
                    return _reportError("Cannot convert non-finite double (" + _numberDouble + ") to BigDecimal");
                }
                // bypass ParserBase's text-based conversion to avoid parsing "NaN"/"Infinity"
                _numberBigDecimal = BigDecimal.valueOf(_numberDouble);
                _numTypesValid |= NR_BIGDECIMAL;
                return _numberBigDecimal;
            }
            convertNumberToBigDecimal();
        }
        return _numberBigDecimal;
    }

    private Object deserializedExtensionTypeValue()
            throws IOException
    {
        if (extTypeCustomDesers != null) {
            ExtensionTypeCustomDeserializers.Deser deser = extTypeCustomDesers.getDeser(extensionTypeValue.getType());
            if (deser != null) {
                return deser.deserialize(extensionTypeValue.getData());
            }
        }
        return extensionTypeValue;
    }

    @Override
    public Object getEmbeddedObject()
    {
        switch (type) {
            case BYTES:
                return bytesValue;
            case EXT:
                try {
                    return deserializedExtensionTypeValue();
                }
                catch (IOException e) {
                    throw _wrapIOFailure(e);
                }
            case INT:
            case LONG:
            case DOUBLE:
            case BOOL:
            case BIG_INT:
            case STRING:
            case NULL:
                return _reportError("Current token (" + _currToken + ") not of embeddable type");
            default:
                return _reportError("Unexpected MessagePack value type: " + type);
        }
    }

    @Override
    public NumberType getNumberType()
    {
        // Check _currToken directly (like CBORParser) to avoid _parseNumericValue()
        // being called for non-numeric tokens.
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            if ((_numTypesValid & NR_INT) != 0) {
                return NumberType.INT;
            }
            if ((_numTypesValid & NR_LONG) != 0) {
                return NumberType.LONG;
            }
            return NumberType.BIG_INTEGER;
        }
        if (_currToken == JsonToken.VALUE_NUMBER_FLOAT) {
            return NumberType.DOUBLE;
        }
        return null;
    }

    @Override
    protected void _parseNumericValue(int expType) throws JacksonException
    {
        // No lazy decoding — numbers are fully parsed eagerly in _nextToken().
        // If we get here the current token is not numeric.
        if (_currToken != JsonToken.VALUE_NUMBER_INT && _currToken != JsonToken.VALUE_NUMBER_FLOAT) {
            _reportError("Current token (" + _currToken + ") not numeric, cannot use numeric value accessors");
        }
    }

    @Override
    protected int _parseIntValue() throws JacksonException
    {
        _parseNumericValue(NR_INT);
        return 0; // unreachable
    }

    @Override
    protected void _closeInput() throws IOException
    {
        if (StreamReadFeature.AUTO_CLOSE_SOURCE.enabledIn(_streamReadFeatures)) {
            messageUnpacker.close();
        }
    }

    @Override
    protected void _releaseBuffers()
    {
        super._releaseBuffers();
    }

    @Override
    public void close()
    {
        try {
            _closeInput();
        }
        catch (IOException e) {
            throw _wrapIOFailure(e);
        }
        finally {
            _closed = true;
            if (ownsThreadLocalUnpacker) {
                Tuple<Object, MessageUnpacker> tuple = messageUnpackerHolder.get();
                if (tuple != null && tuple.first() instanceof byte[]) {
                    messageUnpackerHolder.set(new Tuple<>(null, tuple.second()));
                }
            }
        }
    }

    @Override
    public TokenStreamContext streamReadContext()
    {
        return streamReadContext;
    }

    @Override
    public TokenStreamLocation currentTokenLocation()
    {
        // columnNr repurposed as byte offset; truncates for inputs > 2 GB
        return new TokenStreamLocation(ioContext.contentReference(), tokenPosition, -1, (int) tokenPosition);
    }

    @Override
    public TokenStreamLocation currentLocation()
    {
        // columnNr repurposed as byte offset; truncates for inputs > 2 GB
        return new TokenStreamLocation(ioContext.contentReference(), currentPosition, -1, (int) currentPosition);
    }

    @Override
    public String currentName()
    {
        // Simple, but need to look for START_OBJECT/ARRAY's "off-by-one" thing:
        if (_currToken == JsonToken.START_OBJECT || _currToken == JsonToken.START_ARRAY) {
            MessagePackReadContext parent = streamReadContext.getParent();
            return parent.currentName();
        }
        return streamReadContext.currentName();
    }

    @Override
    public Object streamReadInputSource()
    {
        return ioContext.contentReference().getRawContent();
    }

    @Override
    public Object currentValue()
    {
        return streamReadContext.currentValue();
    }

    @Override
    public void assignCurrentValue(Object v)
    {
        streamReadContext.assignCurrentValue(v);
    }

    @Override
    public boolean isNaN()
    {
        if (type == Type.DOUBLE) {
            return !Double.isFinite(_numberDouble);
        }
        return false;
    }

    public boolean isCurrentFieldId()
    {
        return this.type == Type.INT || this.type == Type.LONG;
    }
}
