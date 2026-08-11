package dtm.serialization.mapper;

import dtm.serialization.BinaryObjectEncoder;
import dtm.serialization.BinaryObjectNode;
import dtm.serialization.Constants;
import dtm.serialization.StreamContent;
import dtm.serialization.StreamLazy;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.enums.SerializationType;
import dtm.serialization.exceptions.EncodeSerializationException;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BinaryObjectEncoderMapper extends BaseBinaryObjectSerializer implements BinaryObjectEncoder {

    private static final byte[] ROOT_NAME_BYTES = "root".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_NAME_BYTES = new byte[0];

    @Override
    public <T> List<byte[]> encodeToByteArrayList(Collection<T> objects) throws EncodeSerializationException {
        List<byte[]> serializationObjectsResult = new ArrayList<>(objects.size());
        for (T object : objects) {
            serializationObjectsResult.add(encodeToByteArray(object));
        }
        return serializationObjectsResult;
    }

    @Override
    public <T> byte[] encodeToByteArray(T object) throws EncodeSerializationException {
        if (object == null) throw new EncodeSerializationException("object is null");

        try {
            long payloadLength = measureValue(object, ROOT_NAME_BYTES, true);
            long totalLength = 2L + varLongLength(payloadLength) + payloadLength;
            if (totalLength > Integer.MAX_VALUE) {
                throw new EncodeSerializationException(
                        "Encoded value is too large for byte[]; use encode(value, OutputStream)"
                );
            }
            ByteArrayOutputStream destination = new ByteArrayOutputStream((int) Math.min(totalLength, 1_048_576L));
            encodeMeasured(object, destination, payloadLength);
            return destination.toByteArray();
        } catch (EncodeSerializationException e) {
            throw e;
        } catch (RuntimeException | IOException e) {
            throw new EncodeSerializationException("Failed to encode object", e);
        }
    }

    @Override
    public <T> void encode(T object, OutputStream destination) throws EncodeSerializationException {
        if (object == null) throw new EncodeSerializationException("object is null");
        if (destination == null) throw new EncodeSerializationException("destination is null");
        try {
            encodeMeasured(object, destination, measureValue(object, ROOT_NAME_BYTES, true));
        } catch (EncodeSerializationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new EncodeSerializationException("Failed to encode object", e);
        }
    }

    private void encodeMeasured(Object object, OutputStream destination, long payloadLength) throws IOException {
        StreamingOutput out = new StreamingOutput(destination);
        out.writeByte(Constants.VALIDATOR_BYTE);
        out.writeByte(Constants.VERSION_BYTE);
        out.writeVarLong(payloadLength);
        long payloadStart = out.position();
        writeValue(out, object, ROOT_NAME_BYTES, true);
        if (out.position() - payloadStart != payloadLength) {
            throw new EncodeSerializationException("Object changed while it was being encoded");
        }
    }

    private long measureValue(Object value, byte[] name, boolean allowLargeContent) {
        if (value == null) return headerSize(name);
        if (value instanceof StreamContent content) {
            if (!allowLargeContent && !(content instanceof StreamLazy)) {
                throw new EncodeSerializationException(
                        "StreamContent fields must be annotated with @LargeContent or use StreamLazy"
                );
            }
            return variableSize(name, content.length());
        }
        Class<?> type = value.getClass();
        if (type.isEnum()) return measureString(((Enum<?>) value).name(), name);
        if (value instanceof Byte) return fixedSize(name, 1);
        if (type == byte[].class) return variableSize(name, ((byte[]) value).length);
        if (value instanceof String s) return measureString(s, name);
        if (value instanceof Short) return fixedSize(name, 2);
        if (value instanceof Integer || value instanceof Float || value instanceof AtomicInteger) return fixedSize(name, 4);
        if (value instanceof Long || value instanceof Double || value instanceof AtomicLong) return fixedSize(name, 8);
        if (value instanceof Boolean || value instanceof AtomicBoolean) return fixedSize(name, 1);
        if (value instanceof BigInteger || value instanceof BigDecimal || value instanceof Character) {
            return measureString(value.toString(), name);
        }
        if (type.isArray()) return measureArray(value, name);
        if (value instanceof Collection<?> collection) return measureCollection(collection, name);
        if (value instanceof Map<?, ?> map) return measureMap(map, name);
        if (value instanceof BinaryObjectNode node) return measureNode(node, name);
        return measureObject(value, name);
    }

    private long measureString(String value, byte[] name) {
        return variableSize(name, value.getBytes(StandardCharsets.UTF_8).length);
    }

    private long measureObject(Object value, byte[] name) {
        long body = 0;
        for (FieldCacheProps props : resolveFields(value.getClass(), SerializationType.ENCODE)) {
            try {
                body = add(body, measureValue(props.field().get(value), props.elementNameBytes(), props.largeContent()));
            } catch (IllegalAccessException e) {
                throw new EncodeSerializationException("Failed to access field: " + props.field().getName(), e);
            }
        }
        return variableSize(name, body);
    }

    private long measureCollection(Collection<?> values, byte[] name) {
        long body = 0;
        for (Object value : values) body = add(body, measureValue(value, EMPTY_NAME_BYTES, false));
        return variableSize(name, body);
    }

    private long measureArray(Object values, byte[] name) {
        if (values instanceof byte[] bytes) return variableSize(name, bytes.length);
        long body = 0;
        for (int i = 0; i < Array.getLength(values); i++) {
            body = add(body, measureValue(Array.get(values, i), EMPTY_NAME_BYTES, false));
        }
        return variableSize(name, body);
    }

    private long measureMap(Map<?, ?> values, byte[] name) {
        long body = 0;
        int index = 0;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            byte[] key = Objects.toString(entry.getKey(), String.valueOf(index++)).getBytes(StandardCharsets.UTF_8);
            body = add(body, measureValue(entry.getValue(), key, false));
        }
        return variableSize(name, body);
    }

    private long measureNode(BinaryObjectNode node, byte[] name) {
        if (node.getObjectType() == ObjectType.LARGE_CONTENT) {
            return variableSize(name, node.getAsStreamContent().length());
        }
        if (node.getObjectType() == ObjectType.OBJECT || node.getObjectType() == ObjectType.LIST) {
            long body = 0;
            for (BinaryObjectNode child : node.getChildren()) {
                body = add(body, measureNode(child, child.getName().getBytes(StandardCharsets.UTF_8)));
            }
            return variableSize(name, body);
        }
        if (node.getObjectType() == ObjectType.NULL) return headerSize(name);
        int length = node.getAsBytes().length;
        return isVariable(node.getObjectType()) ? variableSize(name, length) : fixedSize(name, length);
    }

    private void writeValue(StreamingOutput out, Object value, byte[] name, boolean allowLargeContent) throws IOException {
        if (value == null) { writeStreamingHeader(out, ObjectType.NULL, name); return; }
        if (value instanceof StreamContent content) {
            if (!allowLargeContent && !(content instanceof StreamLazy)) {
                throw new EncodeSerializationException(
                        "StreamContent fields must be annotated with @LargeContent or use StreamLazy"
                );
            }
            writeStreamingHeader(out, ObjectType.LARGE_CONTENT, name);
            out.writeVarLong(content.length());
            try (InputStream source = content.openStream()) { out.copyExactly(source, content.length()); }
            return;
        }
        Class<?> type = value.getClass();
        if (type.isEnum()) { writeStreamingString(out, ((Enum<?>) value).name(), name); return; }
        if (value instanceof Byte b) { writeStreamingHeader(out, ObjectType.I8, name); out.writeByte(b); return; }
        if (type == byte[].class) { writeStreamingBytes(out, (byte[]) value, name); return; }
        if (value instanceof String s) { writeStreamingString(out, s, name); return; }
        if (value instanceof Short s) { writeStreamingHeader(out, ObjectType.I16, name); out.writeShort(s); return; }
        if (value instanceof Integer i) { writeStreamingHeader(out, ObjectType.I32, name); out.writeInt(i); return; }
        if (value instanceof Long l) { writeStreamingHeader(out, ObjectType.I64, name); out.writeLong(l); return; }
        if (value instanceof Boolean b) { writeStreamingHeader(out, ObjectType.BOOLEAN, name); out.writeByte(b ? 1 : 0); return; }
        if (value instanceof Float f) { writeStreamingHeader(out, ObjectType.FLOAT, name); out.writeInt(Float.floatToRawIntBits(f)); return; }
        if (value instanceof Double d) { writeStreamingHeader(out, ObjectType.DOUBLE, name); out.writeLong(Double.doubleToRawLongBits(d)); return; }
        if (value instanceof AtomicInteger i) { writeValue(out, i.get(), name, false); return; }
        if (value instanceof AtomicLong l) { writeValue(out, l.get(), name, false); return; }
        if (value instanceof AtomicBoolean b) { writeValue(out, b.get(), name, false); return; }
        if (value instanceof BigInteger || value instanceof BigDecimal || value instanceof Character) {
            writeStreamingString(out, value.toString(), name); return;
        }
        if (type.isArray()) { writeStreamingArray(out, value, name); return; }
        if (value instanceof Collection<?> collection) { writeStreamingCollection(out, collection, name); return; }
        if (value instanceof Map<?, ?> map) { writeStreamingMap(out, map, name); return; }
        if (value instanceof BinaryObjectNode node) { writeStreamingNode(out, node, name); return; }
        writeStreamingObject(out, value, name);
    }

    private void writeStreamingObject(StreamingOutput out, Object value, byte[] name) throws IOException {
        long size = measureObject(value, name);
        long body = bodySizeFromVariable(size, name);
        writeStreamingHeader(out, ObjectType.OBJECT, name); out.writeVarLong(body);
        for (FieldCacheProps props : resolveFields(value.getClass(), SerializationType.ENCODE)) {
            try {
                writeValue(out, props.field().get(value), props.elementNameBytes(), props.largeContent());
            } catch (IllegalAccessException e) {
                throw new EncodeSerializationException("Failed to access field: " + props.field().getName(), e);
            }
        }
    }

    private void writeStreamingCollection(StreamingOutput out, Collection<?> values, byte[] name) throws IOException {
        long total = measureCollection(values, name); long body = bodySizeFromVariable(total, name);
        writeStreamingHeader(out, ObjectType.LIST, name); out.writeVarLong(body);
        for (Object value : values) writeValue(out, value, EMPTY_NAME_BYTES, false);
    }

    private void writeStreamingArray(StreamingOutput out, Object values, byte[] name) throws IOException {
        if (values instanceof byte[] bytes) { writeStreamingBytes(out, bytes, name); return; }
        long total = measureArray(values, name); long body = bodySizeFromVariable(total, name);
        writeStreamingHeader(out, ObjectType.LIST, name); out.writeVarLong(body);
        for (int i = 0; i < Array.getLength(values); i++) writeValue(out, Array.get(values, i), EMPTY_NAME_BYTES, false);
    }

    private void writeStreamingMap(StreamingOutput out, Map<?, ?> values, byte[] name) throws IOException {
        long total = measureMap(values, name); long body = bodySizeFromVariable(total, name);
        writeStreamingHeader(out, ObjectType.OBJECT, name); out.writeVarLong(body);
        int index = 0;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            byte[] key = Objects.toString(entry.getKey(), String.valueOf(index++)).getBytes(StandardCharsets.UTF_8);
            writeValue(out, entry.getValue(), key, false);
        }
    }

    private void writeStreamingNode(StreamingOutput out, BinaryObjectNode node, byte[] name) throws IOException {
        ObjectType type = node.getObjectType(); writeStreamingHeader(out, type, name);
        if (type == ObjectType.NULL) return;
        if (type == ObjectType.LARGE_CONTENT) {
            StreamContent content = node.getAsStreamContent(); out.writeVarLong(content.length());
            try (InputStream source = content.openStream()) { out.copyExactly(source, content.length()); }
            return;
        }
        if (type == ObjectType.OBJECT || type == ObjectType.LIST) {
            long total = measureNode(node, name); long body = bodySizeFromVariable(total, name); out.writeVarLong(body);
            for (BinaryObjectNode child : node.getChildren()) {
                writeStreamingNode(out, child, child.getName().getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        byte[] bytes = node.getAsBytes();
        if (isVariable(type)) out.writeVarLong(bytes.length);
        out.write(bytes);
    }

    private void writeStreamingString(StreamingOutput out, String value, byte[] name) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeStreamingHeader(out, ObjectType.STRING, name); out.writeVarLong(bytes.length); out.write(bytes);
    }

    private void writeStreamingBytes(StreamingOutput out, byte[] value, byte[] name) throws IOException {
        writeStreamingHeader(out, ObjectType.BYTES, name); out.writeVarLong(value.length); out.write(value);
    }

    private void writeStreamingHeader(StreamingOutput out, ObjectType type, byte[] name) throws IOException {
        out.writeByte(type.id()); out.writeVarInt(name.length); out.write(name);
    }

    private long headerSize(byte[] name) { return add(1L + varIntLength(name.length), name.length); }
    private long fixedSize(byte[] name, long body) { return add(headerSize(name), body); }
    private long variableSize(byte[] name, long body) { return add(add(headerSize(name), varLongLength(body)), body); }
    private long bodySizeFromVariable(long total, byte[] name) {
        long withoutHeader = total - headerSize(name);
        for (int length = 1; length <= 10; length++) {
            long body = withoutHeader - length;
            if (body >= 0 && varLongLength(body) == length) return body;
        }
        throw new EncodeSerializationException("Invalid calculated body size");
    }
    private boolean isVariable(ObjectType type) {
        return type == ObjectType.STRING || type == ObjectType.OBJECT || type == ObjectType.LIST
                || type == ObjectType.BYTES || type == ObjectType.LARGE_CONTENT;
    }
    private long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException e) { throw new EncodeSerializationException("Encoded size exceeds long range", e); }
    }
    private static int varIntLength(int value) {
        int result = 1; while ((value & ~0x7F) != 0) { result++; value >>>= 7; } return result;
    }
    private static int varLongLength(long value) {
        if (value < 0) throw new EncodeSerializationException("Negative length: " + value);
        int result = 1; while ((value & ~0x7FL) != 0) { result++; value >>>= 7; } return result;
    }

    private void encode(BinaryOutput out, Object value, byte[] fieldNameBytes) {
        if (value == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        Class<?> type = value.getClass();

        if (type.isEnum()) {
            writeString(out, ((Enum<?>) value).name(), fieldNameBytes);
        } else if (value instanceof Byte b) {
            writeInt8(out, b, fieldNameBytes);
        } else if (type == byte[].class) {
            writeBytes(out, (byte[]) value, fieldNameBytes);
        } else if (value instanceof String s) {
            writeString(out, s, fieldNameBytes);
        } else if (type.isArray()) {
            writeArray(out, value, fieldNameBytes);
        } else if (value instanceof Short s) {
            writeInt16(out, s, fieldNameBytes);
        } else if (value instanceof Integer i) {
            writeInt32(out, i, fieldNameBytes);
        } else if (value instanceof Long l) {
            writeInt64(out, l, fieldNameBytes);
        } else if (value instanceof Boolean b) {
            writeBoolean(out, b, fieldNameBytes);
        } else if (value instanceof Float f) {
            writeFloat(out, f, fieldNameBytes);
        } else if (value instanceof Double d) {
            writeDouble(out, d, fieldNameBytes);
        } else if (value instanceof Collection<?> collection) {
            writeList(out, collection, fieldNameBytes);
        } else if (value instanceof BigInteger bigInteger) {
            writeString(out, bigInteger.toString(), fieldNameBytes);
        } else if (value instanceof BigDecimal bigDecimal) {
            writeString(out, bigDecimal.toString(), fieldNameBytes);
        } else if (value instanceof AtomicLong al) {
            writeInt64(out, al.get(), fieldNameBytes);
        } else if (value instanceof AtomicBoolean ab) {
            writeBoolean(out, ab.get(), fieldNameBytes);
        } else if (value instanceof AtomicInteger ai) {
            writeInt32(out, ai.get(), fieldNameBytes);
        } else if (value instanceof Character c) {
            writeString(out, String.valueOf(c), fieldNameBytes);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(out, map, fieldNameBytes);
        } else if (value instanceof BinaryObjectNode node) {
            writeBinaryObjectNode(out, node, fieldNameBytes);
        } else {
            writeObject(out, value, fieldNameBytes);
        }
    }

    private void writeNull(BinaryOutput out, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.NULL, fieldNameBytes);
    }

    private void writeString(BinaryOutput out, String value, byte[] fieldNameBytes) {
        if (value == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        writeHeader(out, ObjectType.STRING, fieldNameBytes);
        out.writeVarInt(valueBytes.length);
        out.write(valueBytes);
    }

    private void writeInt16(BinaryOutput out, short value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.I16, fieldNameBytes);
        out.writeShort(value);
    }

    private void writeInt8(BinaryOutput out, byte value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.I8, fieldNameBytes);
        out.writeByte(value);
    }

    private void writeInt32(BinaryOutput out, int value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.I32, fieldNameBytes);
        out.writeInt(value);
    }

    private void writeInt64(BinaryOutput out, long value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.I64, fieldNameBytes);
        out.writeLong(value);
    }

    private void writeBoolean(BinaryOutput out, boolean value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.BOOLEAN, fieldNameBytes);
        out.writeBoolean(value);
    }

    private void writeDouble(BinaryOutput out, double value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.DOUBLE, fieldNameBytes);
        out.writeDouble(value);
    }

    private void writeFloat(BinaryOutput out, float value, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.FLOAT, fieldNameBytes);
        out.writeFloat(value);
    }

    private void writeBytes(BinaryOutput out, byte[] value, byte[] fieldNameBytes) {
        if (value == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        writeHeader(out, ObjectType.BYTES, fieldNameBytes);
        out.writeVarInt(value.length);
        out.write(value);
    }

    private void writeObject(BinaryOutput out, Object object, byte[] fieldNameBytes) {
        if (object == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        writeHeader(out, ObjectType.OBJECT, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();

        List<FieldCacheProps> fields = resolveFields(object.getClass(), SerializationType.ENCODE);

        for (FieldCacheProps fieldCacheProps : fields) {
            encodeField(out, object, fieldCacheProps);
        }

        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void encodeField(BinaryOutput out, Object object, FieldCacheProps fieldCacheProps) {
        Field field = fieldCacheProps.field();
        Class<?> fieldType = fieldCacheProps.fieldType();
        byte[] fieldNameBytes = fieldCacheProps.elementNameBytes();

        try {
            if (fieldType == int.class) {
                writeInt32(out, field.getInt(object), fieldNameBytes);
                return;
            }
            if (fieldType == long.class) {
                writeInt64(out, field.getLong(object), fieldNameBytes);
                return;
            }
            if (fieldType == boolean.class) {
                writeBoolean(out, field.getBoolean(object), fieldNameBytes);
                return;
            }
            if (fieldType == double.class) {
                writeDouble(out, field.getDouble(object), fieldNameBytes);
                return;
            }
            if (fieldType == float.class) {
                writeFloat(out, field.getFloat(object), fieldNameBytes);
                return;
            }
            if (fieldType == short.class) {
                writeInt16(out, field.getShort(object), fieldNameBytes);
                return;
            }
            if (fieldType == byte.class) {
                writeInt8(out, field.getByte(object), fieldNameBytes);
                return;
            }
            if (fieldType == char.class) {
                writeString(out, String.valueOf(field.getChar(object)), fieldNameBytes);
                return;
            }

            Object value = field.get(object);
            if (value == null) {
                writeNull(out, fieldNameBytes);
                return;
            }

            if (fieldType == String.class) {
                writeString(out, (String) value, fieldNameBytes);
            } else if (fieldType == byte[].class) {
                writeBytes(out, (byte[]) value, fieldNameBytes);
            } else if (fieldType.isEnum()) {
                writeString(out, ((Enum<?>) value).name(), fieldNameBytes);
            } else if (fieldType.isArray()) {
                writeArray(out, value, fieldNameBytes);
            } else if (Collection.class.isAssignableFrom(fieldType)) {
                writeList(out, (Collection<?>) value, fieldNameBytes);
            } else if (Map.class.isAssignableFrom(fieldType)) {
                writeMap(out, (Map<?, ?>) value, fieldNameBytes);
            } else if (BinaryObjectNode.class.isAssignableFrom(fieldType)) {
                writeBinaryObjectNode(out, (BinaryObjectNode) value, fieldNameBytes);
            } else {
                encode(out, value, fieldNameBytes);
            }
        } catch (IllegalAccessException e) {
            throw new EncodeSerializationException("Failed to access field: " + field.getName(), e);
        }
    }

    private void writeArray(BinaryOutput out, Object array, byte[] fieldNameBytes) {
        if (array == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        if (array instanceof int[] values) {
            writeIntArray(out, values, fieldNameBytes);
            return;
        }
        if (array instanceof long[] values) {
            writeLongArray(out, values, fieldNameBytes);
            return;
        }
        if (array instanceof double[] values) {
            writeDoubleArray(out, values, fieldNameBytes);
            return;
        }
        if (array instanceof float[] values) {
            writeFloatArray(out, values, fieldNameBytes);
            return;
        }
        if (array instanceof boolean[] values) {
            writeBooleanArray(out, values, fieldNameBytes);
            return;
        }
        if (array instanceof short[] values) {
            writeShortArray(out, values, fieldNameBytes);
            return;
        }
        if (array instanceof byte[] values) {
            writeBytes(out, values, fieldNameBytes);
            return;
        }

        int length = Array.getLength(array);

        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();

        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);
            encode(out, element, EMPTY_NAME_BYTES);
        }

        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeIntArray(BinaryOutput out, int[] values, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();
        for (int i = 0; i < values.length; i++) {
            writeInt32(out, values[i], EMPTY_NAME_BYTES);
        }
        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeLongArray(BinaryOutput out, long[] values, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();
        for (int i = 0; i < values.length; i++) {
            writeInt64(out, values[i], EMPTY_NAME_BYTES);
        }
        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeDoubleArray(BinaryOutput out, double[] values, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();
        for (int i = 0; i < values.length; i++) {
            writeDouble(out, values[i], EMPTY_NAME_BYTES);
        }
        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeFloatArray(BinaryOutput out, float[] values, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();
        for (int i = 0; i < values.length; i++) {
            writeFloat(out, values[i], EMPTY_NAME_BYTES);
        }
        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeBooleanArray(BinaryOutput out, boolean[] values, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();
        for (int i = 0; i < values.length; i++) {
            writeBoolean(out, values[i], EMPTY_NAME_BYTES);
        }
        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeShortArray(BinaryOutput out, short[] values, byte[] fieldNameBytes) {
        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();
        for (int i = 0; i < values.length; i++) {
            writeInt16(out, values[i], EMPTY_NAME_BYTES);
        }
        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeList(BinaryOutput out, Collection<?> list, byte[] fieldNameBytes) {
        if (list == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        writeHeader(out, ObjectType.LIST, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();

        int i = 0;
        for (Object o : list) {
            encode(out, o, EMPTY_NAME_BYTES);
            i++;
        }

        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeMap(BinaryOutput out, Map<?, ?> map, byte[] fieldNameBytes) {
        if (map == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        writeHeader(out, ObjectType.OBJECT, fieldNameBytes);
        int payloadLengthPos = out.reserveVarInt();
        int payloadStart = out.position();

        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = Objects.toString(entry.getKey(), String.valueOf(i));
            encode(out, entry.getValue(), key.getBytes(StandardCharsets.UTF_8));
            i++;
        }

        out.writeVarIntAt(payloadLengthPos, out.position() - payloadStart);
    }

    private void writeBinaryObjectNode(BinaryOutput out, BinaryObjectNode node, byte[] fieldNameBytes) {
        if (node == null || node.getObjectType() == null) {
            writeNull(out, fieldNameBytes);
            return;
        }

        ObjectType objectType = node.getObjectType();
        byte[] dataBytes = node.getAsBytes();
        if (dataBytes == null) dataBytes = new byte[0];

        writeHeader(out, objectType, fieldNameBytes);

        switch (objectType) {
            case STRING, OBJECT, LIST, BYTES -> {
                out.writeVarInt(dataBytes.length);
                out.write(dataBytes);
            }
            case NULL -> {
            }
            case BOOLEAN, I8 -> writeFixedNodeBytes(out, dataBytes, 1, objectType);
            case I16 -> writeFixedNodeBytes(out, dataBytes, 2, objectType);
            case I32, FLOAT -> writeFixedNodeBytes(out, dataBytes, 4, objectType);
            case I64, DOUBLE -> writeFixedNodeBytes(out, dataBytes, 8, objectType);
            case LARGE_CONTENT -> throw new EncodeSerializationException(
                    "Large content nodes require encode(value, OutputStream)"
            );
        }
    }

    private void writeFixedNodeBytes(BinaryOutput out, byte[] dataBytes, int size, ObjectType objectType) {
        if (dataBytes.length != size) {
            throw new EncodeSerializationException(
                    "Invalid byte array length for " + objectType + ": expected " + size + ", got " + dataBytes.length
            );
        }
        out.write(dataBytes);
    }

    private void writeHeader(BinaryOutput out, ObjectType objectType, byte[] fieldNameBytes) {
        out.writeByte(objectType.id());
        out.writeVarInt(fieldNameBytes.length);
        out.write(fieldNameBytes);
    }

    private static int estimateInitialCapacity(Object object) {
        if (object instanceof byte[] bytes) {
            return bytes.length + 32;
        }
        if (object instanceof String string) {
            return Math.max(64, string.length() * 3 + 32);
        }
        if (object instanceof Collection<?> collection) {
            return Math.max(128, collection.size() * 32);
        }
        if (object instanceof Map<?, ?> map) {
            return Math.max(128, map.size() * 48);
        }
        return 512;
    }

    private static final class StreamingOutput {
        private static final int BUFFER_SIZE = 64 * 1024;
        private final OutputStream destination;
        private long position;

        private StreamingOutput(OutputStream destination) {
            this.destination = destination;
        }

        private long position() { return position; }

        private void writeByte(int value) throws IOException {
            destination.write(value);
            position++;
        }

        private void writeShort(int value) throws IOException {
            writeByte(value >>> 8); writeByte(value);
        }

        private void writeInt(int value) throws IOException {
            writeByte(value >>> 24); writeByte(value >>> 16); writeByte(value >>> 8); writeByte(value);
        }

        private void writeLong(long value) throws IOException {
            writeByte((int) (value >>> 56)); writeByte((int) (value >>> 48));
            writeByte((int) (value >>> 40)); writeByte((int) (value >>> 32));
            writeByte((int) (value >>> 24)); writeByte((int) (value >>> 16));
            writeByte((int) (value >>> 8)); writeByte((int) value);
        }

        private void writeVarInt(int value) throws IOException {
            if (value < 0) throw new EncodeSerializationException("Negative varint value: " + value);
            while ((value & ~0x7F) != 0) { writeByte((value & 0x7F) | 0x80); value >>>= 7; }
            writeByte(value);
        }

        private void writeVarLong(long value) throws IOException {
            if (value < 0) throw new EncodeSerializationException("Negative varlong value: " + value);
            while ((value & ~0x7FL) != 0) { writeByte(((int) value & 0x7F) | 0x80); value >>>= 7; }
            writeByte((int) value);
        }

        private void write(byte[] bytes) throws IOException {
            destination.write(bytes);
            position = Math.addExact(position, bytes.length);
        }

        private void copyExactly(InputStream source, long length) throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new EOFException("StreamContent ended early: expected " + length
                            + " bytes, got " + (length - remaining));
                }
                if (read == 0) continue;
                destination.write(buffer, 0, read);
                position = Math.addExact(position, read);
                remaining -= read;
            }
        }
    }

    private static final class BinaryOutput {
        private byte[] buffer;
        private int size;

        private BinaryOutput(int initialCapacity) {
            this.buffer = new byte[Math.max(32, initialCapacity)];
        }

        private int position() {
            return size;
        }

        private void writeByte(int value) {
            ensureCapacity(size + 1);
            buffer[size++] = (byte) value;
        }

        private void writeBoolean(boolean value) {
            writeByte(value ? 1 : 0);
        }

        private void writeShort(int value) {
            ensureCapacity(size + 2);
            buffer[size++] = (byte) (value >>> 8);
            buffer[size++] = (byte) value;
        }

        private void writeInt(int value) {
            ensureCapacity(size + 4);
            writeIntAt(size, value);
            size += 4;
        }

        private int reserveInt() {
            int pos = size;
            writeInt(0);
            return pos;
        }

        private void writeVarInt(int value) {
            if (value < 0) {
                throw new EncodeSerializationException("Negative varint value: " + value);
            }
            while ((value & ~0x7F) != 0) {
                writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            writeByte(value);
        }

        private int reserveVarInt() {
            return reserve(1);
        }

        private void writeVarIntAt(int pos, int value) {
            if (value < 0) {
                throw new EncodeSerializationException("Negative varint value: " + value);
            }
            int length = varIntLength(value);
            replaceReserved(pos, 1, length);
            writeVarIntBytesAt(pos, value);
        }

        private void writeLong(long value) {
            ensureCapacity(size + 8);
            writeLongAt(size, value);
            size += 8;
        }

        private int reserveLong() {
            int pos = size;
            writeLong(0L);
            return pos;
        }

        private void writeVarLong(long value) {
            if (value < 0) {
                throw new EncodeSerializationException("Negative varlong value: " + value);
            }
            while ((value & ~0x7FL) != 0L) {
                writeByte(((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
            writeByte((int) value);
        }

        private int reserveVarLong() {
            return reserve(5);
        }

        private void writeVarLongAt(int pos, long value) {
            if (value < 0) {
                throw new EncodeSerializationException("Negative varlong value: " + value);
            }
            int length = varLongLength(value);
            replaceReserved(pos, 5, length);
            writeVarLongBytesAt(pos, value);
        }

        private void writeFloat(float value) {
            writeInt(Float.floatToRawIntBits(value));
        }

        private void writeDouble(double value) {
            writeLong(Double.doubleToRawLongBits(value));
        }

        private void write(byte[] bytes) {
            if (bytes.length == 0) return;
            ensureCapacity(size + bytes.length);
            System.arraycopy(bytes, 0, buffer, size, bytes.length);
            size += bytes.length;
        }

        private void writeIntAt(int pos, int value) {
            buffer[pos] = (byte) (value >>> 24);
            buffer[pos + 1] = (byte) (value >>> 16);
            buffer[pos + 2] = (byte) (value >>> 8);
            buffer[pos + 3] = (byte) value;
        }

        private void writeLongAt(int pos, long value) {
            buffer[pos] = (byte) (value >>> 56);
            buffer[pos + 1] = (byte) (value >>> 48);
            buffer[pos + 2] = (byte) (value >>> 40);
            buffer[pos + 3] = (byte) (value >>> 32);
            buffer[pos + 4] = (byte) (value >>> 24);
            buffer[pos + 5] = (byte) (value >>> 16);
            buffer[pos + 6] = (byte) (value >>> 8);
            buffer[pos + 7] = (byte) value;
        }

        private int reserve(int length) {
            ensureCapacity(size + length);
            int pos = size;
            size += length;
            return pos;
        }

        private int varIntLength(int value) {
            int i = 0;
            while ((value & ~0x7F) != 0) {
                i++;
                value >>>= 7;
            }
            return i + 1;
        }

        private int varLongLength(long value) {
            int i = 0;
            while ((value & ~0x7FL) != 0L) {
                i++;
                value >>>= 7;
            }
            return i + 1;
        }

        private void writeVarIntBytesAt(int pos, int value) {
            while ((value & ~0x7F) != 0) {
                buffer[pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buffer[pos] = (byte) value;
        }

        private void writeVarLongBytesAt(int pos, long value) {
            while ((value & ~0x7FL) != 0L) {
                buffer[pos++] = (byte) (((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buffer[pos] = (byte) value;
        }

        private void replaceReserved(int pos, int reservedLength, int encodedLength) {
            int tailStart = pos + reservedLength;
            int newTailStart = pos + encodedLength;
            int tailLength = size - tailStart;
            int delta = encodedLength - reservedLength;

            if (delta > 0) {
                ensureCapacity(size + delta);
            }

            if (tailLength > 0 && newTailStart != tailStart) {
                System.arraycopy(buffer, tailStart, buffer, newTailStart, tailLength);
            }
            size += delta;
        }

        private byte[] toByteArray() {
            return Arrays.copyOf(buffer, size);
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity <= buffer.length) return;

            int newCapacity = buffer.length + (buffer.length >> 1);
            if (newCapacity < minCapacity) {
                newCapacity = minCapacity;
            }
            buffer = Arrays.copyOf(buffer, newCapacity);
        }
    }
}
