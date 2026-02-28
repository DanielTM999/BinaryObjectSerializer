package dtm.serialization.mapper;

import dtm.serialization.BinaryObjectEncoder;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.enums.SerializationType;
import dtm.serialization.exceptions.EncodeSerializationException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BinaryObjectEncoderMapper extends BaseBinaryObjectSerializer implements BinaryObjectEncoder {

    @Override
    public <T> List<byte[]> encodeToByteArrayList(Collection<T> objects) throws EncodeSerializationException {
        List<byte[]> serializationObjectsResult = new ArrayList<>();
        for (T object : objects) {
            serializationObjectsResult.add(encodeToByteArray(object));
        }
        return serializationObjectsResult;
    }

    @Override
    public <T> byte[] encodeToByteArray(T object) throws EncodeSerializationException {
        try (
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos);

                ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
                DataOutputStream payloadOut = new DataOutputStream(payloadBaos);
        ) {
            if(object == null) throw new EncodeSerializationException("object is null");

            encode(payloadOut, object, "0");


            byte[] payload = payloadBaos.toByteArray();
            out.writeByte(0xAA);
            out.writeByte(0x01);
            out.writeLong(payload.length);
            out.write(payload);

            return baos.toByteArray();
        }catch (IOException e) {
            throw new EncodeSerializationException("Failed to encode simple type", e);
        }
    }

    private void encode(DataOutputStream out, Object value, String fieldName) throws IOException{
        if (value == null) {
            writeNull(out, fieldName);
            return;
        }

        Class<?> type = value.getClass();

        if(type.isEnum()){
            writeString(out, ((Enum<?>) value).name(), fieldName);
        } else if (value instanceof Byte aByte) {
            writeBytes(out, new byte[]{aByte}, fieldName);
        }else if (type == byte[].class) {
            writeBytes(out, (byte[]) value, fieldName);
        }else if (value instanceof String s) {
            writeString(out, s, fieldName);
        } else if (type.isArray()) {
            writeArray(out, value, fieldName);
        } else if (value instanceof Integer i) {
            writeInt(out, i, fieldName);
        } else if (value instanceof Long l) {
            writeLong(out, l, fieldName);
        } else if (value instanceof Boolean b) {
            writeBoolean(out, b, fieldName);
        } else if (value instanceof Float f) {
            writeDouble(out, f, fieldName);
        } else if (value instanceof Double d) {
            writeDouble(out, d, fieldName);
        }else if(value instanceof Collection<?> collection){
            writeList(out, collection, fieldName);
        }else if(value instanceof BigInteger bigInteger){
            writeString(out, bigInteger.toString(), fieldName);
        }else if(value instanceof BigDecimal bigDecimal){
            writeString(out, bigDecimal.toString(), fieldName);
        }else if (value instanceof AtomicLong al) {
            writeLong(out, al.get(), fieldName);
        } else if (value instanceof AtomicBoolean ab) {
            writeBoolean(out, ab.get(), fieldName);
        } else if (value instanceof AtomicInteger ai) {
            writeInt(out, ai.get(), fieldName);
        }else if (value instanceof Character c) {
            writeString(out, String.valueOf(c), fieldName);
        }

        else{
            writeObject(out, value, fieldName);
        }
    }

    private void writeNull(DataOutputStream out, String fieldName) throws EncodeSerializationException, IOException {
        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.NULL.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(0);
    }

    private void writeString(DataOutputStream out, String value, String fieldName) throws EncodeSerializationException, IOException {
        if(value == null) {
            writeNull(out, fieldName);
            return;
        }
        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.STRING.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(valueBytes.length);
        out.write(valueBytes);
    }

    private void writeInt(DataOutputStream out, int value, String fieldName) throws EncodeSerializationException, IOException{
        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.INT.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(4);
        out.writeInt(value);
    }

    private void writeLong(DataOutputStream out, long value, String fieldName) throws IOException {

        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.LONG.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(8);
        out.writeLong(value);
    }

    private void writeBoolean(DataOutputStream out, boolean value, String fieldName) throws IOException {
        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.BOOLEAN.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(1);
        out.writeBoolean(value);
    }

    private void writeDouble(DataOutputStream out, double value, String fieldName) throws IOException {

        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.DOUBLE.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(8);
        out.writeDouble(value);
    }

    private void writeBytes(DataOutputStream out, byte[] value, String fieldName) throws IOException {
        if (value == null) {
            writeNull(out, fieldName);
            return;
        }

        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.BYTE.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        out.writeInt(value.length);
        out.write(value);
    }

    private void writeObject(DataOutputStream out, Object object, String fieldName) throws IOException {
        if(object == null) {
            writeNull(out, fieldName);
            return;
        }
        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.OBJECT.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        List<FieldCacheProps> fields = resolveFields(object.getClass(), SerializationType.ENCODE);

        try( ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
             DataOutputStream payloadOut = new DataOutputStream(payloadBaos);
        ){
            for (FieldCacheProps fieldCacheProps : fields){
                Field field = fieldCacheProps.field();
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(object);
                } catch (IllegalAccessException e) {
                    throw new EncodeSerializationException("Failed to access field: " + field.getName(), e);
                }

                String fieldFieldName = fieldCacheProps.elementName();

                if (value == null) {
                    writeNull(payloadOut, fieldFieldName);
                    continue;
                }

                encode(payloadOut, value, fieldFieldName);
            }

            byte[] payload = payloadBaos.toByteArray();

            out.writeInt(payload.length);
            out.write(payload);
        }
    }

    private void writeArray(DataOutputStream out, Object array, String fieldName) throws IOException {
        if (array == null) {
            writeNull(out, fieldName);
            return;
        }

        int length = Array.getLength(array);

        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.LIST.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        try (
                ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
                DataOutputStream payloadOut = new DataOutputStream(payloadBaos)
        ) {
            for (int i = 0; i < length; i++) {
                Object element = java.lang.reflect.Array.get(array, i);
                encode(payloadOut, element, Integer.toString(i));
            }

            byte[] payload = payloadBaos.toByteArray();
            out.writeInt(payload.length);
            out.write(payload);
        }
    }

    private void writeList(DataOutputStream out, Collection<?> list, String fieldName) throws IOException {
        if(list == null) {
            writeNull(out, fieldName);
            return;
        }
        byte[] nameBytes = fieldName.getBytes(StandardCharsets.UTF_8);

        out.write(ObjectType.LIST.id());

        out.writeInt(nameBytes.length);
        out.write(nameBytes);

        try (
                ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                DataOutputStream headerOut = new DataOutputStream(headerBaos);
        ) {
            int i = 0;
            for (Object o : list) {
                encode(headerOut, o, Integer.toString(i));
                i++;
            }

            byte[] listData = headerBaos.toByteArray();

            out.writeInt(listData.length);
            out.write(listData);
        }

    }

}
