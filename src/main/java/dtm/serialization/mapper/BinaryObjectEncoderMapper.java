package dtm.serialization.mapper;

import dtm.serialization.BinaryObjectEncoder;
import dtm.serialization.BinaryObjectNode;
import dtm.serialization.Constants;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.enums.SerializationType;
import dtm.serialization.exceptions.EncodeSerializationException;

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
            BinaryOutput out = new BinaryOutput(estimateInitialCapacity(object));
            out.writeByte(Constants.VALIDATOR_BYTE);
            out.writeByte(Constants.VERSION_BYTE);

            int payloadLengthPos = out.reserveVarLong();
            int payloadStart = out.position();
            encode(out, object, ROOT_NAME_BYTES);
            out.writeVarLongAt(payloadLengthPos, out.position() - payloadStart);

            return out.toByteArray();
        } catch (EncodeSerializationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EncodeSerializationException("Failed to encode object", e);
        }
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
