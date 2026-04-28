package dtm.serialization.mapper;

import dtm.serialization.BinaryObjectDecoder;
import dtm.serialization.BinaryObjectNode;
import dtm.serialization.CollectionReference;
import dtm.serialization.Constants;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.enums.SerializationType;
import dtm.serialization.exceptions.DecodeSerializationException;
import dtm.serialization.exceptions.SerializationException;
import dtm.serialization.mapper.models.DefaultBinaryObjectNode;

import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BinaryObjectDecoderMapper extends BinaryObjectEncoderMapper implements BinaryObjectDecoder {

    private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    @Override
    public BinaryObjectNode readAsTree(byte[] bytes) throws DecodeSerializationException {
        if (bytes == null) {
            throw new DecodeSerializationException("bytes is null");
        }

        try {
            DefaultBinaryObjectNode rootNode = new DefaultBinaryObjectNode(this::convertTo);
            BinaryInput input = new BinaryInput(bytes);

            validSignedByte(input);
            validVersion(input);

            long payloadSize = input.readPayloadLength();
            if (payloadSize < 0 || payloadSize > Integer.MAX_VALUE) {
                throw new DecodeSerializationException("Invalid payload size: " + payloadSize);
            }

            int payloadEnd = input.position() + (int) payloadSize;
            if (payloadEnd > bytes.length) {
                throw new DecodeSerializationException(
                        "Protocol corrupted: payload incomplete, expected " + payloadSize + " bytes"
                );
            }

            readNode(input, rootNode, payloadEnd);

            if (input.position() < payloadEnd) {
                throw new DecodeSerializationException(
                        "Invalid serialization: extra bytes remaining after root node (" +
                                (payloadEnd - input.position()) + " bytes)"
                );
            }

            return rootNode;
        } catch (DecodeSerializationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecodeSerializationException("Failed to read bytes", e);
        }
    }

    @Override
    public BinaryObjectNode readAsTree(File file) throws DecodeSerializationException {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return readAsTree(bytes);
        } catch (IOException e) {
            throw new DecodeSerializationException("Failed to read file", e);
        }
    }

    @Override
    public BinaryObjectNode readAsTree(InputStream stream) throws DecodeSerializationException {
        try {
            return readAsTree(stream.readAllBytes());
        } catch (IOException e) {
            throw new DecodeSerializationException("Failed to read stream", e);
        }
    }

    @Override
    public <T> T readAsObject(byte[] bytes, Class<T> ref) throws DecodeSerializationException {
        if (bytes == null) {
            throw new DecodeSerializationException("bytes is null");
        }

        try {
            BinaryInput input = new BinaryInput(bytes);
            validSignedByte(input);
            validVersion(input);

            long payloadSize = input.readPayloadLength();
            if (payloadSize < 0 || payloadSize > Integer.MAX_VALUE) {
                throw new DecodeSerializationException("Invalid payload size: " + payloadSize);
            }

            int payloadEnd = input.position() + (int) payloadSize;
            if (payloadEnd > bytes.length) {
                throw new DecodeSerializationException(
                        "Protocol corrupted: payload incomplete, expected " + payloadSize + " bytes"
                );
            }

            Object value = readValue(input, ref, ref, payloadEnd);
            if (input.position() < payloadEnd) {
                throw new DecodeSerializationException(
                        "Invalid serialization: extra bytes remaining after root node (" +
                                (payloadEnd - input.position()) + " bytes)"
                );
            }

            return ref.cast(value);
        } catch (DecodeSerializationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecodeSerializationException("Failed to read bytes", e);
        }
    }

    @Override
    public <T> T readAsObject(File file, Class<T> ref) throws DecodeSerializationException {
        try {
            return readAsObject(Files.readAllBytes(file.toPath()), ref);
        } catch (IOException e) {
            throw new DecodeSerializationException("Failed to read file", e);
        }
    }

    @Override
    public <T> T readAsObject(InputStream stream, Class<T> ref) throws DecodeSerializationException {
        try {
            return readAsObject(stream.readAllBytes(), ref);
        } catch (IOException e) {
            throw new DecodeSerializationException("Failed to read stream", e);
        }
    }


    @Override
    public <T extends Collection<?>> T readAsCollection(byte[] bytes, CollectionReference<T> ref) throws DecodeSerializationException {
        return readAsTree(bytes).getAsCollection(ref);
    }

    @Override
    public <T extends Collection<?>> T readAsCollection(File file, CollectionReference<T> ref) throws DecodeSerializationException {
        return readAsTree(file).getAsCollection(ref);
    }

    @Override
    public <T extends Collection<?>> T readAsCollection(InputStream stream, CollectionReference<T> ref) throws DecodeSerializationException {
        return  readAsTree(stream).getAsCollection(ref);
    }

    private void validSignedByte(BinaryInput in) {
        byte validator = in.readByte();
        if (validator != ((byte) Constants.VALIDATOR_BYTE)) {
            throw new DecodeSerializationException("Invalid protocol: missing validator byte 0xAA");
        }
    }

    private void validVersion(BinaryInput in) {
        byte version = in.readByte();
        if (version != Constants.VERSION_BYTE && version != Constants.LEGACY_VERSION_BYTE) {
            throw new DecodeSerializationException("Unsupported protocol version: " + version);
        }
        in.useCompactLengths(version == Constants.VERSION_BYTE);
    }

    private void readNode(BinaryInput input, DefaultBinaryObjectNode rootNode, int payloadLimit) {
        readNodeMetadata(input, rootNode);
        readNodeBody(input, rootNode, payloadLimit);
    }

    private void readNodeMetadata(BinaryInput input, DefaultBinaryObjectNode rootNode) {
        byte typeByte = input.readByte();
        ObjectType objectType = ObjectType.fromId(typeByte);

        if (objectType == null) {
            StringBuilder typesList = new StringBuilder();
            for (ObjectType t : ObjectType.values()) {
                if (!typesList.isEmpty()) typesList.append(", ");
                typesList.append(String.format("0x%02X[%s]", t.id(), t.name()));
            }

            throw new DecodeSerializationException(
                    String.format(
                            "Unknown object type: byte=0x%02X (%d). Valid types: %s",
                            typeByte,
                            typeByte,
                            typesList
                    )
            );
        }

        rootNode.setObjectType(objectType);

        int nameSize = input.readLength();
        if (nameSize < 0) throw new DecodeSerializationException("Invalid name size: " + nameSize);

        rootNode.setName(input.readString(nameSize));
    }

    private void readNodeBody(BinaryInput input, DefaultBinaryObjectNode rootNode, int payloadLimit) {
        int bodySize = getBodySize(input, rootNode.getObjectType());
        if (bodySize < 0) throw new SerializationException("Invalid body size: " + bodySize);

        int bodyStart = input.position();
        int bodyEnd = bodyStart + bodySize;
        if (bodyEnd < bodyStart || bodyEnd > payloadLimit) {
            throw new DecodeSerializationException(
                    "Protocol corrupted: node body incomplete, expected " + bodySize + " bytes"
            );
        }

        switch (rootNode.getObjectType()) {
            case STRING, I64, I32, I16, I8, BOOLEAN, DOUBLE, FLOAT, BYTES, NULL -> {
                rootNode.setBytesValue(input.bytes(), bodyStart, bodySize);
                input.skip(bodySize);
            }

            case OBJECT, LIST -> {
                rootNode.setBytesValue(input.bytes(), bodyStart, bodySize);
                while (input.position() < bodyEnd) {
                    DefaultBinaryObjectNode child = new DefaultBinaryObjectNode(this::convertTo);
                    readNode(input, child, bodyEnd);
                    rootNode.addChild(child);
                }
            }

            default -> throw new DecodeSerializationException(
                    "Unsupported object type in body: " + rootNode.getObjectType()
            );
        }

        if (input.position() != bodyEnd) {
            throw new DecodeSerializationException(
                    "Invalid serialization: node body size mismatch for '" + rootNode.getName() + "'"
            );
        }
    }

    private Object readValue(BinaryInput input, Class<?> targetType, Type genericType, int payloadLimit) {
        NodeHeader header = readHeader(input, payloadLimit);
        return readValueBody(input, header, targetType, genericType);
    }

    private NodeHeader readHeader(BinaryInput input, int payloadLimit) {
        byte typeByte = input.readByte();
        ObjectType objectType = ObjectType.fromId(typeByte);
        if (objectType == null) {
            throw new DecodeSerializationException(
                    String.format("Unknown object type: byte=0x%02X (%d)", typeByte, typeByte)
            );
        }

        int nameSize = input.readLength();
        if (nameSize < 0) throw new DecodeSerializationException("Invalid name size: " + nameSize);
        String name = input.readString(nameSize);

        int bodySize = getBodySize(input, objectType);
        if (bodySize < 0) throw new SerializationException("Invalid body size: " + bodySize);

        int bodyStart = input.position();
        int bodyEnd = bodyStart + bodySize;
        if (bodyEnd < bodyStart || bodyEnd > payloadLimit) {
            throw new DecodeSerializationException(
                    "Protocol corrupted: node body incomplete, expected " + bodySize + " bytes"
            );
        }

        return new NodeHeader(objectType, name, bodyStart, bodyEnd);
    }

    private Object readValueBody(BinaryInput input, NodeHeader header, Class<?> targetType, Type genericType) {
        if (header.objectType() == ObjectType.NULL) {
            return primitiveDefault(targetType);
        }

        if (targetType == Object.class) {
            return readDynamicBody(input, header);
        }

        if (targetType == byte[].class) {
            return readBytesBody(input, header);
        }

        if (isSimpleType(targetType)
                || classIs(targetType, AtomicBoolean.class, AtomicInteger.class, AtomicLong.class, BigDecimal.class, BigInteger.class)) {
            return readSimpleBody(input, header, targetType);
        }

        if (targetType.isArray()) {
            return readArrayBody(input, header, targetType);
        }

        if (Collection.class.isAssignableFrom(targetType)) {
            return readCollectionBody(input, header, targetType, genericType);
        }

        if (Map.class.isAssignableFrom(targetType)) {
            return readMapBody(input, header, genericType);
        }

        return readObjectBody(input, header, targetType);
    }

    private Object readObjectBody(BinaryInput input, NodeHeader header, Class<?> targetType) {
        expectType(header, ObjectType.OBJECT, targetType.getSimpleName());

        Object instance = createInstanceOf(targetType);
        Map<String, FieldCacheProps> fields = resolveFieldMap(targetType, SerializationType.DECODE);

        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            FieldCacheProps fieldCacheProps = fields.get(childHeader.name());

            if (fieldCacheProps == null) {
                input.skip(childHeader.bodySize());
                continue;
            }

            Object value = readValueBody(input, childHeader, fieldCacheProps.fieldType(), fieldCacheProps.genericType());
            try {
                setDecodedField(instance, fieldCacheProps.field(), fieldCacheProps.fieldType(), value);
            } catch (IllegalAccessException e) {
                throw new DecodeSerializationException(
                        "Failed to set field: " + fieldCacheProps.field().getName(),
                        e
                );
            }
        }

        return instance;
    }

    private Object readCollectionBody(BinaryInput input, NodeHeader header, Class<?> collectionType, Type genericType) {
        expectType(header, ObjectType.LIST, collectionType.getSimpleName());

        Type elementType = Object.class;
        if (genericType instanceof ParameterizedType parameterizedType) {
            elementType = parameterizedType.getActualTypeArguments()[0];
        }

        Class<?> elementClass = resolveRawClassOrObject(elementType);
        Collection<Object> collection = List.class.isAssignableFrom(collectionType)
                ? new ArrayList<>()
                : new HashSet<>();

        while (input.position() < header.bodyEnd()) {
            collection.add(readValue(input, elementClass, elementType, header.bodyEnd()));
        }

        return collection;
    }

    private Object readArrayBody(BinaryInput input, NodeHeader header, Class<?> arrayType) {
        if (arrayType == byte[].class) {
            return readBytesBody(input, header);
        }

        expectType(header, ObjectType.LIST, arrayType.getSimpleName());

        if (arrayType == int[].class) return readIntArrayBody(input, header);
        if (arrayType == long[].class) return readLongArrayBody(input, header);
        if (arrayType == double[].class) return readDoubleArrayBody(input, header);
        if (arrayType == float[].class) return readFloatArrayBody(input, header);
        if (arrayType == boolean[].class) return readBooleanArrayBody(input, header);
        if (arrayType == short[].class) return readShortArrayBody(input, header);

        Class<?> componentType = arrayType.getComponentType();
        List<Object> values = new ArrayList<>();
        while (input.position() < header.bodyEnd()) {
            values.add(readValue(input, componentType, componentType, header.bodyEnd()));
        }

        Object array = Array.newInstance(componentType, values.size());
        for (int i = 0; i < values.size(); i++) {
            Array.set(array, i, values.get(i));
        }
        return array;
    }

    private int[] readIntArrayBody(BinaryInput input, NodeHeader header) {
        int[] values = new int[8];
        int size = 0;
        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = readIntBody(input, childHeader);
        }
        return Arrays.copyOf(values, size);
    }

    private long[] readLongArrayBody(BinaryInput input, NodeHeader header) {
        long[] values = new long[8];
        int size = 0;
        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = readLongBody(input, childHeader);
        }
        return Arrays.copyOf(values, size);
    }

    private double[] readDoubleArrayBody(BinaryInput input, NodeHeader header) {
        double[] values = new double[8];
        int size = 0;
        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = readDoubleBody(input, childHeader);
        }
        return Arrays.copyOf(values, size);
    }

    private float[] readFloatArrayBody(BinaryInput input, NodeHeader header) {
        float[] values = new float[8];
        int size = 0;
        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = readFloatBody(input, childHeader);
        }
        return Arrays.copyOf(values, size);
    }

    private boolean[] readBooleanArrayBody(BinaryInput input, NodeHeader header) {
        boolean[] values = new boolean[8];
        int size = 0;
        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = readBooleanBody(input, childHeader);
        }
        return Arrays.copyOf(values, size);
    }

    private short[] readShortArrayBody(BinaryInput input, NodeHeader header) {
        short[] values = new short[8];
        int size = 0;
        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = (short) readIntBody(input, childHeader);
        }
        return Arrays.copyOf(values, size);
    }

    private Map<String, Object> readMapBody(BinaryInput input, NodeHeader header, Type genericType) {
        expectType(header, ObjectType.OBJECT, "Map");

        Type valueType = Object.class;
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type[] args = parameterizedType.getActualTypeArguments();
            if (args.length > 1) valueType = args[1];
        }

        Class<?> valueClass = resolveRawClassOrObject(valueType);
        Map<String, Object> map = new HashMap<>();

        while (input.position() < header.bodyEnd()) {
            NodeHeader childHeader = readHeader(input, header.bodyEnd());
            Object value = readValueBody(input, childHeader, valueClass, valueType);
            map.put(childHeader.name(), value);
        }

        return map;
    }

    private Object readDynamicBody(BinaryInput input, NodeHeader header) {
        return switch (header.objectType()) {
            case STRING -> readStringBody(input, header);
            case I8 -> (byte) readIntBody(input, header);
            case I16 -> (short) readIntBody(input, header);
            case I32 -> readIntBody(input, header);
            case I64 -> readLongBody(input, header);
            case FLOAT -> readFloatBody(input, header);
            case DOUBLE -> readDoubleBody(input, header);
            case BOOLEAN -> readBooleanBody(input, header);
            case BYTES -> readBytesBody(input, header);
            case LIST -> {
                List<Object> list = new ArrayList<>();
                while (input.position() < header.bodyEnd()) {
                    list.add(readValue(input, Object.class, Object.class, header.bodyEnd()));
                }
                yield list;
            }
            case OBJECT -> readMapBody(input, header, Object.class);
            case NULL -> null;
        };
    }

    private Object readSimpleBody(BinaryInput input, NodeHeader header, Class<?> targetType) {
        if (targetType.isEnum()) {
            String enumName = readStringBody(input, header);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object enumValue = Enum.valueOf((Class<Enum>) targetType, enumName);
            return enumValue;
        }

        if (targetType == String.class) {
            return readStringBody(input, header);
        }

        if (targetType == Boolean.class || targetType == boolean.class || targetType == AtomicBoolean.class) {
            boolean value = readBooleanBody(input, header);
            return targetType == AtomicBoolean.class ? new AtomicBoolean(value) : value;
        }

        if (targetType == Integer.class || targetType == int.class || targetType == AtomicInteger.class) {
            int value = readIntBody(input, header);
            return targetType == AtomicInteger.class ? new AtomicInteger(value) : value;
        }

        if (targetType == Short.class || targetType == short.class) {
            return (short) readIntBody(input, header);
        }

        if (targetType == Long.class || targetType == long.class || targetType == AtomicLong.class) {
            long value = readLongBody(input, header);
            return targetType == AtomicLong.class ? new AtomicLong(value) : value;
        }

        if (targetType == Float.class || targetType == float.class) {
            return readFloatBody(input, header);
        }

        if (targetType == Double.class || targetType == double.class) {
            return readDoubleBody(input, header);
        }

        if (targetType == Byte.class || targetType == byte.class) {
            if (header.objectType() == ObjectType.BYTES) {
                byte[] bytes = readBytesBody(input, header);
                return bytes.length > 0 ? bytes[0] : (byte) 0;
            }
            return (byte) readIntBody(input, header);
        }

        if (targetType == Character.class || targetType == char.class) {
            String value = readStringBody(input, header);
            return value.isEmpty() ? '\0' : value.charAt(0);
        }

        if (targetType == BigDecimal.class) {
            return new BigDecimal(readStringBody(input, header));
        }

        if (targetType == BigInteger.class) {
            return new BigInteger(readStringBody(input, header));
        }

        throw new DecodeSerializationException("Unsupported simple type: " + targetType.getName());
    }

    private String readStringBody(BinaryInput input, NodeHeader header) {
        expectType(header, ObjectType.STRING, "String");
        String value = new String(input.bytes(), input.position(), header.bodySize(), StandardCharsets.UTF_8);
        input.skip(header.bodySize());
        return value;
    }

    private boolean readBooleanBody(BinaryInput input, NodeHeader header) {
        expectType(header, ObjectType.BOOLEAN, "boolean");
        if (header.bodySize() != 1) throw invalidBodySize(header, 1);
        return input.readByte() != 0;
    }

    private int readIntBody(BinaryInput input, NodeHeader header) {
        return switch (header.objectType()) {
            case I8 -> {
                if (header.bodySize() != 1) throw invalidBodySize(header, 1);
                yield input.readByte();
            }
            case I16 -> {
                if (header.bodySize() != 2) throw invalidBodySize(header, 2);
                yield input.readShort();
            }
            case I32 -> {
                if (header.bodySize() != 4) throw invalidBodySize(header, 4);
                yield input.readInt();
            }
            default -> throw new DecodeSerializationException(
                    String.format("Node '%s' expected INT but found %s", header.name(), header.objectType())
            );
        };
    }

    private long readLongBody(BinaryInput input, NodeHeader header) {
        if (header.objectType() == ObjectType.I64) {
            if (header.bodySize() != 8) throw invalidBodySize(header, 8);
            return input.readLong();
        }
        return readIntBody(input, header);
    }

    private float readFloatBody(BinaryInput input, NodeHeader header) {
        expectType(header, ObjectType.FLOAT, "float");
        if (header.bodySize() != 4) throw invalidBodySize(header, 4);
        return Float.intBitsToFloat(input.readInt());
    }

    private double readDoubleBody(BinaryInput input, NodeHeader header) {
        expectType(header, ObjectType.DOUBLE, "double");
        if (header.bodySize() != 8) throw invalidBodySize(header, 8);
        return Double.longBitsToDouble(input.readLong());
    }

    private byte[] readBytesBody(BinaryInput input, NodeHeader header) {
        expectType(header, ObjectType.BYTES, "byte[]");
        byte[] bytes = Arrays.copyOfRange(input.bytes(), input.position(), header.bodyEnd());
        input.skip(header.bodySize());
        return bytes;
    }

    private void setDecodedField(Object instance, Field field, Class<?> fieldType, Object value) throws IllegalAccessException {
        if (fieldType == int.class) {
            field.setInt(instance, ((Number) value).intValue());
        } else if (fieldType == long.class) {
            field.setLong(instance, ((Number) value).longValue());
        } else if (fieldType == boolean.class) {
            field.setBoolean(instance, (Boolean) value);
        } else if (fieldType == double.class) {
            field.setDouble(instance, ((Number) value).doubleValue());
        } else if (fieldType == float.class) {
            field.setFloat(instance, ((Number) value).floatValue());
        } else if (fieldType == short.class) {
            field.setShort(instance, ((Number) value).shortValue());
        } else if (fieldType == byte.class) {
            field.setByte(instance, ((Number) value).byteValue());
        } else if (fieldType == char.class) {
            field.setChar(instance, (Character) value);
        } else {
            field.set(instance, value);
        }
    }

    private Object primitiveDefault(Class<?> targetType) {
        if (targetType == boolean.class) return false;
        if (targetType == byte.class) return (byte) 0;
        if (targetType == short.class) return (short) 0;
        if (targetType == int.class) return 0;
        if (targetType == long.class) return 0L;
        if (targetType == float.class) return 0f;
        if (targetType == double.class) return 0d;
        if (targetType == char.class) return '\0';
        return null;
    }

    private Class<?> resolveRawClassOrObject(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        return Object.class;
    }

    private void expectType(NodeHeader header, ObjectType expected, String targetName) {
        if (header.objectType() != expected) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' expected %s for %s but found %s",
                            header.name(), expected, targetName, header.objectType())
            );
        }
    }

    private DecodeSerializationException invalidBodySize(NodeHeader header, int expected) {
        return new DecodeSerializationException(
                String.format("Invalid byte array length for %s at node '%s': expected %d, got %d",
                        header.objectType(), header.name(), expected, header.bodySize())
        );
    }

    private record NodeHeader(ObjectType objectType, String name, int bodyStart, int bodyEnd) {
        private int bodySize() {
            return bodyEnd - bodyStart;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T convertTo(Object obj, BinaryObjectNode node){
        if (obj instanceof Class<?>) {
            return (T)convertTo((Class<T>)obj, node);
        }else if(obj instanceof CollectionReference<?> ref){
            return (T) convertToCollection(ref, node);
        }

        throw new DecodeSerializationException(
                "Unsupported type for conversion: " + obj.getClass().getName()
        );
    }

    private Object convertTo(Class<?> clazz, BinaryObjectNode node){


        if(clazz.equals(Object.class)){
            if(node.getObjectType() == ObjectType.NULL) return null;

            clazz = switch (node.getObjectType()){
                case LIST -> List.class;
                case STRING -> String.class;
                case BOOLEAN -> Boolean.class;
                case I8 -> Byte.class;
                case I16 -> Short.class;
                case BYTES -> byte[].class;
                case I32 -> Integer.class;
                case FLOAT -> Float.class;
                case DOUBLE -> Double.class;
                case I64 -> Long.class;
                default -> Map.class;
            };
        }

        if(isSimpleType(clazz)){
            return convertSimpleType(clazz, node);
        }

        if(clazz.isArray()){
            return clazz.cast(createArrayFromNode(clazz, "root", node));
        }

        if(Collection.class.isAssignableFrom(clazz)){
            throw new DecodeSerializationException(
                    String.format(
                            "Cannot deserialize node '%s' of type %s to collection %s. Use a final generic wrapper 'CollectionReference<%s>' instead.",
                            node.getName(),
                            node.getObjectType(),
                            clazz.getSimpleName(),
                            clazz.getSimpleName()
                    )
            );
        }

        if (Map.class.isAssignableFrom(clazz)) {
            return clazz.cast(convertToMap(node));
        }

        Object instance = createInstanceOf(clazz);
        List<FieldCacheProps> fieldCacheProps = resolveFields(clazz, SerializationType.DECODE);

        for (FieldCacheProps fieldCacheProp : fieldCacheProps) {
            String elementName = fieldCacheProp.elementName();
           try{
               setFieldType(instance, fieldCacheProp, node);
           }catch (DecodeSerializationException e){
               throw new DecodeSerializationException(
                       String.format(
                               "Failed to set field '%s' of type '%s' in class '%s' from node '%s' of type '%s': %s",
                               elementName,
                               fieldCacheProp.fieldType().getSimpleName(),
                               clazz.getSimpleName(),
                               node.getName(),
                               node.getObjectType(),
                               e.getMessage()
                       ),
                       e
               );
           }
           catch (Exception e){
               throw new DecodeSerializationException(
                       String.format(
                               "Unexpected error while setting field '%s' of type '%s' in class '%s' from node '%s'",
                               elementName,
                               fieldCacheProp.fieldType().getSimpleName(),
                               clazz.getSimpleName(),
                               node.getName()
                       ),
                       e
               );
           }
        }

        return instance;
    }

    @SuppressWarnings("unchecked")
    private <E> Collection<E> convertToCollection(CollectionReference<?> ref, BinaryObjectNode node) {
        if (node.getObjectType() == ObjectType.NULL) return null;

        Type type = ref.getType();

        if (!(type instanceof ParameterizedType pt)) {
            throw new DecodeSerializationException(
                    "CollectionReference must provide a generic type parameter, e.g., CollectionReference<List<String>>"
            );
        }

        Type elementType = pt.getActualTypeArguments()[0];

        Collection<E> collection;

        Class<?> rawClass = (Class<?>) pt.getRawType();
        if (List.class.isAssignableFrom(rawClass)) {
            collection = new ArrayList<>();
        } else if (Set.class.isAssignableFrom(rawClass)) {
            collection = new HashSet<>();
        } else {
            throw new DecodeSerializationException(
                    String.format("Unsupported collection type '%s'. Only List or Set are supported", rawClass.getName())
            );
        }

        for (BinaryObjectNode childNode : node.getChildren()) {
            E value;


            Class<?> clazz = resolveRawClass(elementType);
            if (isSimpleType(clazz)) {
                value = (E) getValueOnSimpleObject(clazz, "", childNode);
            } else {
                value = (E) convertTo(clazz, childNode);
                if(clazz == Object.class && value instanceof Map<?,?> m){
                    if (!m.isEmpty()) {
                        Object firstValue = m.values().iterator().next();
                        if (firstValue instanceof Map) {
                            value = (E) new ConcurrentHashMap<>((Map<?, ?>) firstValue);
                        } else {
                            value = (E) firstValue;
                        }
                    } else {
                        value = (E) new ConcurrentHashMap<>();
                    }
                }
            }
            collection.add(value);
        }

        return collection;
    }

    private Map<String, Object> convertToMap(BinaryObjectNode node){
        Map<String, Object> objectMap = new ConcurrentHashMap<>();

        if(node.getObjectType() == ObjectType.OBJECT){
            objectMap.put(node.getName(), node.getAsMap());
        }else if(node.getObjectType() == ObjectType.STRING){
            objectMap.put(node.getName(), node.getAsString());
        }else if(node.getObjectType() == ObjectType.I8){
            byte[] bytes = node.getAsBytes();
            objectMap.put(node.getName(), bytes.length > 0 ? bytes[0] : (byte) 0);
        }else if(node.getObjectType() == ObjectType.I16){
            objectMap.put(node.getName(), (short) node.getAsInt().intValue());
        }else if(node.getObjectType() == ObjectType.I32){
            objectMap.put(node.getName(), node.getAsInt());
        }else if(node.getObjectType() == ObjectType.I64){
            objectMap.put(node.getName(), node.getAsLong());
        }else if(node.getObjectType() == ObjectType.FLOAT){
            objectMap.put(node.getName(), node.getAsFloat());
        }else if(node.getObjectType() == ObjectType.DOUBLE){
            objectMap.put(node.getName(), node.getAsDouble());
        }else if(node.getObjectType() == ObjectType.BOOLEAN){
            objectMap.put(node.getName(), node.getAsBoolean());
        } else if (node.getObjectType() == ObjectType.BYTES) {
            objectMap.put(node.getName(), node.getAsBytes());
        }else if (node.getObjectType() == ObjectType.LIST){
            objectMap.put(node.getName(), node.getAsCollection(new CollectionReference<List<Map<String, Object>>>(){}));
        }

        return objectMap;
    }

    @SuppressWarnings("unchecked")
    private <T> T convertSimpleType(Class<T> clazz, BinaryObjectNode node) {

        if (clazz.equals(String.class)) {
            return (T) node.getAsString();
        }

        if (clazz.equals(Boolean.class) || clazz.equals(boolean.class)) {
            Boolean v = node.getAsBoolean();
            return (T) ((v != null) ? v : Boolean.FALSE);
        }

        if (clazz.equals(Integer.class) || clazz.equals(int.class)) {
            Integer v = node.getAsInt();
            return (T) ((v != null) ? v : Integer.valueOf(0));
        }

        if (clazz.equals(Short.class) || clazz.equals(short.class)) {
            Integer v = node.getAsInt();
            return (T) ((v != null) ? Short.valueOf(v.shortValue()) : Short.valueOf((short) 0));
        }

        if (clazz.equals(Long.class) || clazz.equals(long.class)) {
            Long v = node.getAsLong();
            return (T) ((v != null) ? v : Long.valueOf(0L));
        }

        if (clazz.equals(Float.class) || clazz.equals(float.class)) {
            return (T) Float.valueOf(node.getAsFloat());
        }

        if (clazz.equals(Double.class) || clazz.equals(double.class)) {
            return (T) Double.valueOf(node.getAsDouble());
        }

        if (clazz.equals(Byte.class) || clazz.equals(byte.class)) {
            byte[] bytes = node.getAsBytes();
            if (bytes == null || bytes.length != 1) {
                throw new DecodeSerializationException(
                        "Expected single byte but found " +
                                (bytes == null ? "null" : bytes.length + " bytes")
                );
            }
            return (T) Byte.valueOf(bytes[0]);
        }

        throw new DecodeSerializationException(
                "Unsupported simple type: " + clazz.getName()
        );
    }

    private void setFieldType(Object instance, FieldCacheProps fieldCacheProps, BinaryObjectNode node) throws IllegalAccessException {
        Field field = fieldCacheProps.field();
        Class<?> fieldType = fieldCacheProps.fieldType();
        String elementName = fieldCacheProps.elementName();
        BinaryObjectNode childNode = node.getChild(elementName);
        if (childNode == null)  return;


        if(isSimpleType(fieldType) || classIs(fieldType, AtomicBoolean.class, AtomicInteger.class, AtomicLong.class, BigDecimal.class, BigInteger.class)){
            setSimpleFieldType(instance, field, fieldType, elementName, childNode);
            return;
        }

        if (childNode.getObjectType() == ObjectType.NULL) return;

        if(Collection.class.isAssignableFrom(fieldType)){
            setCollectionFieldType(instance, fieldCacheProps, childNode);
            return;
        }

        if(fieldType.isArray()){
            Object value = createArrayFromNode(fieldType, elementName, childNode);
            field.set(instance, value);
            return;
        }

        Object innerObject = convertTo(fieldType, childNode);
        field.set(instance, innerObject);
    }

    private void setSimpleFieldType(Object instance, Field field, Class<?> fieldType, String elementName, BinaryObjectNode node) throws IllegalAccessException {
        Object value = getValueOnSimpleObject(fieldType, elementName, node);

        if (fieldType == int.class) {
            field.setInt(instance, ((Number) value).intValue());
        } else if (fieldType == long.class) {
            field.setLong(instance, ((Number) value).longValue());
        } else if (fieldType == boolean.class) {
            field.setBoolean(instance, (Boolean) value);
        } else if (fieldType == double.class) {
            field.setDouble(instance, ((Number) value).doubleValue());
        } else if (fieldType == float.class) {
            field.setFloat(instance, ((Number) value).floatValue());
        } else if (fieldType == short.class) {
            field.setShort(instance, ((Number) value).shortValue());
        } else if (fieldType == byte.class) {
            field.setByte(instance, ((Number) value).byteValue());
        } else if (fieldType == char.class) {
            field.setChar(instance, (Character) value);
        } else {
            field.set(instance, value);
        }
    }

    private void setCollectionFieldType(Object instance, FieldCacheProps fieldCacheProps, BinaryObjectNode node) throws IllegalAccessException {
        Field field = fieldCacheProps.field();
        Class<?> fieldType = fieldCacheProps.fieldType();
        String elementName = fieldCacheProps.elementName();
        ObjectType nodeType = node.getObjectType();

        if (nodeType != ObjectType.LIST) {
            throw new DecodeSerializationException(
                    String.format("Field '%s' expected a LIST but found %s in node '%s'", elementName, nodeType, node.getName())
            );
        }

        Class<?> genericType = Object.class;
        if (fieldCacheProps.genericType() instanceof ParameterizedType parameterizedType) {
            if (parameterizedType.getActualTypeArguments()[0] instanceof Class<?> clazz) {
                genericType = clazz;
            }
        }

        Collection<Object> collection;
        if (List.class.isAssignableFrom(fieldType)) {
            collection = new ArrayList<>(node.getChildren().size());
        } else if (Set.class.isAssignableFrom(fieldType)) {
            collection = new HashSet<>(Math.max(16, node.getChildren().size() * 2));
        } else {
            throw new DecodeSerializationException(
                    String.format("Unsupported Collection type '%s' for field '%s' in node '%s'. Only List or Set are supported.",
                            fieldType.getName(), elementName, node.getName())
            );
        }

        for (BinaryObjectNode childNode : node.getChildren()) {
            Object value;

            if (isSimpleType(genericType)) {
                value = getValueOnSimpleObject(genericType, elementName, childNode);
            } else {
                value = convertTo(genericType, childNode);
            }
            collection.add(value);
        }

        field.set(instance, collection);
    }


    private Object createArrayFromNode(Class<?> arrayType, String elementName, BinaryObjectNode node) {
        Class<?> componentType = arrayType.getComponentType();
        ObjectType nodeType = node.getObjectType();
        List<BinaryObjectNode> innerNodes = node.getChildren();
        int size = innerNodes.size();

        if (arrayType.equals(byte[].class)) {
            if (nodeType != ObjectType.BYTES) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BYTE array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return node.getAsBytes();
        }

        if (arrayType.equals(int[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected INT array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            int[] arr = new int[size];
            for (int i = 0; i < size; i++) arr[i] = innerNodes.get(i).getAsInt();
            return arr;
        }

        if (arrayType.equals(long[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected LONG array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            long[] arr = new long[size];
            for (int i = 0; i < size; i++) arr[i] = innerNodes.get(i).getAsLong();
            return arr;
        }

        if (arrayType.equals(float[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected FLOAT array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            float[] arr = new float[size];
            for (int i = 0; i < size; i++) arr[i] = innerNodes.get(i).getAsFloat();
            return arr;
        }

        if (arrayType.equals(double[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected DOUBLE array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            double[] arr = new double[size];
            for (int i = 0; i < size; i++) arr[i] = innerNodes.get(i).getAsDouble();
            return arr;
        }

        if (arrayType.equals(boolean[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BOOLEAN array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            boolean[] arr = new boolean[size];
            for (int i = 0; i < size; i++) arr[i] = innerNodes.get(i).getAsBoolean();
            return arr;
        }

        if (arrayType.equals(String[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            String[] arr = new String[size];
            for (int i = 0; i < size; i++) arr[i] = innerNodes.get(i).getAsString();
            return arr;
        }

        if (arrayType.equals(BigInteger[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BigInteger array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            BigInteger[] arr = new BigInteger[size];
            for (int i = 0; i < size; i++) arr[i] = new BigInteger(innerNodes.get(i).getAsString());
            return arr;
        }

        if (arrayType.equals(BigDecimal[].class)) {
            if (nodeType != ObjectType.LIST) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BigDecimal array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            BigDecimal[] arr = new BigDecimal[size];
            for (int i = 0; i < size; i++) arr[i] = new BigDecimal(innerNodes.get(i).getAsString());
            return arr;
        }

        if (arrayType.isArray()) {
            Object arr = Array.newInstance(componentType, size);
            for (int i = 0; i < size; i++) {
                Object value = convertTo(componentType, innerNodes.get(i));
                Array.set(arr, i, value);
            }
            return arr;
        }

        throw new DecodeSerializationException(
                String.format("Unsupported array type '%s' for field '%s' in node '%s'", arrayType.getName(), elementName, node.getName())
        );
    }

    private <T> T createInstanceOf(Class<T> clazz){
        try {
            Constructor<?> constructor = CONSTRUCTOR_CACHE.computeIfAbsent(clazz, this::resolveConstructor);
            return clazz.cast(constructor.newInstance());
        } catch (DecodeSerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new DecodeSerializationException(
                    String.format(
                            "Failed to instantiate class '%s': %s",
                            clazz.getName(),
                            e.getMessage()
                    ), e
            );
        }
    }

    private Constructor<?> resolveConstructor(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new DecodeSerializationException(
                    String.format(
                            "Class '%s' must have a public or protected no-args constructor.",
                            clazz.getName()
                    ),
                    e
            );
        }
    }

    private Object getValueOnSimpleObject(Class<?> fieldType, String elementName, BinaryObjectNode node){
        ObjectType nodeType = node.getObjectType();

        if (nodeType == ObjectType.NULL) {
            if (fieldType.isPrimitive()) {
                if (fieldType.equals(boolean.class)) return false;
                if (fieldType.equals(byte.class)) return (byte) 0;
                if (fieldType.equals(short.class)) return (short) 0;
                if (fieldType.equals(int.class)) return 0;
                if (fieldType.equals(long.class)) return 0L;
                if (fieldType.equals(float.class)) return 0f;
                if (fieldType.equals(double.class)) return 0d;
                if (fieldType.equals(char.class)) return '\0';

                throw new DecodeSerializationException(
                        String.format("Field '%s' has primitive type '%s' but node is NULL", elementName, fieldType.getName())
                );
            }
            return null;
        }

        if(fieldType.isEnum()){
            if (nodeType != ObjectType.STRING) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING for enum '%s' but found %s in node '%s'",
                                elementName, fieldType.getSimpleName(), nodeType, node.getName())
                );
            }

            String enumName = node.getAsString();
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object enumValue = Enum.valueOf((Class<Enum>) fieldType, enumName);
                return enumValue;
            } catch (IllegalArgumentException e) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' could not map value '%s' to enum '%s' in node '%s'",
                                elementName, enumName, fieldType.getSimpleName(), node.getName()), e
                );
            }
        }

        if (fieldType.equals(AtomicBoolean.class) || fieldType.equals(Boolean.class) || fieldType.equals(boolean.class)) {
            if (nodeType != ObjectType.BOOLEAN) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BOOLEAN but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return (fieldType.equals(AtomicBoolean.class)) ? new AtomicBoolean(node.getAsBoolean()) : node.getAsBoolean();
        }

        if (fieldType.equals(AtomicInteger.class) || fieldType.equals(Integer.class) || fieldType.equals(int.class)) {
            if (nodeType != ObjectType.I32 && nodeType != ObjectType.I16 && nodeType != ObjectType.I8) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected INT but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            int value = node.getAsInt();
            if (fieldType.equals(AtomicInteger.class)) return new AtomicInteger(value);
            return value;
        }

        if (fieldType.equals(Short.class) || fieldType.equals(short.class)) {
            if (nodeType != ObjectType.I16 && nodeType != ObjectType.I32 && nodeType != ObjectType.I8) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected SHORT but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return (short) node.getAsInt().intValue();
        }

        if (fieldType.equals(AtomicLong.class) || fieldType.equals(Long.class) || fieldType.equals(long.class)) {
            if (nodeType != ObjectType.I64 && nodeType != ObjectType.I32 && nodeType != ObjectType.I16 && nodeType != ObjectType.I8) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected LONG but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            long value = node.getAsLong();
            if (fieldType.equals(AtomicLong.class)) return new AtomicLong(value);
            return value;
        }

        if (fieldType.equals(Float.class) || fieldType.equals(float.class)) {
            if (nodeType != ObjectType.FLOAT) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected FLOAT but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return node.getAsFloat();
        }

        if (fieldType.equals(Double.class) || fieldType.equals(double.class)) {
            if (nodeType != ObjectType.DOUBLE) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected FLOAT (for double) but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return node.getAsDouble();
        }

        if (fieldType.equals(BigDecimal.class)) {
            if (nodeType != ObjectType.STRING && nodeType != ObjectType.BYTES) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING/BYTE for BigDecimal but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return new BigDecimal(node.getAsString());
        }

        if (fieldType.equals(BigInteger.class)) {
            if (nodeType != ObjectType.STRING && nodeType != ObjectType.BYTES) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING/BYTE for BigInteger but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return new BigInteger(node.getAsString());
        }

        if (fieldType.equals(String.class)) {
            if (nodeType != ObjectType.STRING) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return node.getAsString();
        }

        if (fieldType.equals(byte.class) || fieldType.equals(Byte.class)) {
            if (nodeType != ObjectType.I8 && nodeType != ObjectType.BYTES) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BYTE but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            if (nodeType == ObjectType.I8) {
                return (byte) node.getAsInt().intValue();
            }
            byte[] bytes = node.getAsBytes();
            return (bytes.length > 0) ? bytes[0] : 0;
        }

        if (fieldType.equals(byte[].class) || fieldType.equals(Byte[].class)) {
            if (nodeType != ObjectType.BYTES) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BYTE array but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return node.getAsBytes();
        }

        if (fieldType.equals(Character.class) || fieldType.equals(char.class)) {
            if (nodeType != ObjectType.STRING) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING for Character but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            String str = node.getAsString();
            return (str != null && !str.isEmpty()) ? str.charAt(0) : '\0';
        }

        throw new DecodeSerializationException(
                String.format("Unsupported simple field type '%s' for field '%s' in node '%s'", fieldType.getName(), elementName, node.getName())
        );
    }

    private Class<?> resolveRawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        throw new DecodeSerializationException(
                "Unsupported Type: " + type
        );
    }

    private int getBodySize(BinaryInput input, ObjectType objectType) {
        return switch (objectType) {
            case STRING, OBJECT, LIST, BYTES -> input.readLength();
            case NULL -> 0;
            case BOOLEAN, I8 -> 1;
            case I16 -> 2;
            case I32, FLOAT -> 4;
            case I64, DOUBLE -> 8;
            default -> Constants.INVALID_SIZE;
        };
    }

    private static final class BinaryInput {
        private final byte[] bytes;
        private int position;
        private boolean compactLengths;

        private BinaryInput(byte[] bytes) {
            this.bytes = bytes;
        }

        private byte[] bytes() {
            return bytes;
        }

        private int position() {
            return position;
        }

        private void useCompactLengths(boolean compactLengths) {
            this.compactLengths = compactLengths;
        }

        private int readLength() {
            return compactLengths ? readVarInt() : readInt();
        }

        private long readPayloadLength() {
            return compactLengths ? readVarLong() : readLong();
        }

        private byte readByte() {
            require(1);
            return bytes[position++];
        }

        private int readInt() {
            require(4);
            int value = ((bytes[position] & 0xFF) << 24)
                    | ((bytes[position + 1] & 0xFF) << 16)
                    | ((bytes[position + 2] & 0xFF) << 8)
                    | (bytes[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        private short readShort() {
            require(2);
            short value = (short) (((bytes[position] & 0xFF) << 8)
                    | (bytes[position + 1] & 0xFF));
            position += 2;
            return value;
        }

        private long readLong() {
            require(8);
            long value = ((long) (bytes[position] & 0xFF) << 56)
                    | ((long) (bytes[position + 1] & 0xFF) << 48)
                    | ((long) (bytes[position + 2] & 0xFF) << 40)
                    | ((long) (bytes[position + 3] & 0xFF) << 32)
                    | ((long) (bytes[position + 4] & 0xFF) << 24)
                    | ((long) (bytes[position + 5] & 0xFF) << 16)
                    | ((long) (bytes[position + 6] & 0xFF) << 8)
                    | (long) (bytes[position + 7] & 0xFF);
            position += 8;
            return value;
        }

        private int readVarInt() {
            int value = 0;
            int shift = 0;
            for (int i = 0; i < 5; i++) {
                byte b = readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new DecodeSerializationException("Invalid varint length");
        }

        private long readVarLong() {
            long value = 0;
            int shift = 0;
            for (int i = 0; i < 10; i++) {
                byte b = readByte();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new DecodeSerializationException("Invalid varlong length");
        }

        private String readString(int length) {
            if (length < 0) {
                throw new DecodeSerializationException("Invalid string length: " + length);
            }
            require(length);
            String value = new String(bytes, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        private void skip(int length) {
            if (length < 0) {
                throw new DecodeSerializationException("Invalid skip length: " + length);
            }
            require(length);
            position += length;
        }

        private void require(int length) {
            if (position + length < position || position + length > bytes.length) {
                throw new DecodeSerializationException("Unexpected end of input");
            }
        }
    }

}
