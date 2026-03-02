package dtm.serialization.mapper;

import dtm.serialization.BinaryObjectDecoder;
import dtm.serialization.BinaryObjectNode;
import dtm.serialization.CollectionReference;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.enums.SerializationType;
import dtm.serialization.exceptions.DecodeSerializationException;
import dtm.serialization.mapper.models.DefaultBinaryObjectNode;

import java.io.*;
import java.lang.reflect.Array;
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

    @Override
    public BinaryObjectNode readAsTree(byte[] bytes) throws DecodeSerializationException {
        return readAsTree(new ByteArrayInputStream(bytes));
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
        DefaultBinaryObjectNode rootNode = new DefaultBinaryObjectNode(this::convertTo);
        try (DataInputStream metadataIn = new DataInputStream(stream)) {
            validSignedByte(metadataIn);
            validVersion(metadataIn);
            try(DataInputStream payloadIn = readPayload(metadataIn)){
                readNode(payloadIn, rootNode);

                if (payloadIn.available() > 0) {
                    throw new DecodeSerializationException(
                            "Invalid serialization: extra bytes remaining after root node (" +
                                    payloadIn.available() + " bytes)"
                    );
                }

                return rootNode;
            }
        } catch (IOException e) {
            throw new DecodeSerializationException("Failed to read stream", e);
        }
    }

    @Override
    public <T> T readAsObject(byte[] bytes, Class<T> ref) throws DecodeSerializationException {
        return readAsTree(bytes).getAsObject(ref);
    }

    @Override
    public <T> T readAsObject(File file, Class<T> ref) throws DecodeSerializationException {
        return readAsTree(file).getAsObject(ref);
    }

    @Override
    public <T> T readAsObject(InputStream stream, Class<T> ref) throws DecodeSerializationException {
        return  readAsTree(stream).getAsObject(ref);
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

    private void validSignedByte(DataInputStream in) throws IOException {
        byte validator = in.readByte();
        if (validator != (byte) 0xAA) {
            throw new DecodeSerializationException("Invalid protocol: missing validator byte 0xAA");
        }
    }

    private DataInputStream readPayload(DataInputStream in) throws IOException {
        long payloadSize;
        try {
            payloadSize = in.readLong();
        } catch (EOFException e) {
            throw new DecodeSerializationException("Protocol corrupted: cannot read payload size");
        }

        if (payloadSize < 0 || payloadSize > Integer.MAX_VALUE) {
            throw new DecodeSerializationException("Invalid payload size: " + payloadSize);
        }

        byte[] payload = new byte[(int) payloadSize];
        try {
            in.readFully(payload);
        } catch (EOFException e) {
            throw new DecodeSerializationException("Protocol corrupted: payload incomplete, expected " + payloadSize + " bytes");
        }

        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private void validVersion(DataInputStream in) throws IOException {
        byte version = in.readByte();
        if (version != (byte) 0x01) {
            throw new DecodeSerializationException("Unsupported protocol version: " + version);
        }
    }

    private void readNode(DataInputStream payloadIn, DefaultBinaryObjectNode rootNode) throws IOException {
        readNodeMetadata(payloadIn, rootNode);
        readNodeBody(payloadIn, rootNode);
    }

    private void readNodeMetadata(DataInputStream payloadIn, DefaultBinaryObjectNode rootNode) throws IOException {
        byte typeByte = payloadIn.readByte();
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

        int nameSize = payloadIn.readInt();
        if (nameSize < 0) throw new IOException("Invalid name size: " + nameSize);

        byte[] nameBytes = new byte[nameSize];
        payloadIn.readFully(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        rootNode.setName(name);
    }

    private void readNodeBody(DataInputStream payloadIn, DefaultBinaryObjectNode rootNode) throws IOException {
        int bodySize = payloadIn.readInt();
        if (bodySize < 0) throw new IOException("Invalid body size: " + bodySize);

        byte[] bodyBytes = new byte[bodySize];
        payloadIn.readFully(bodyBytes);

        switch (rootNode.getObjectType()) {
            case STRING, LONG, INT, BOOLEAN, DOUBLE, BYTE, NULL -> {
                rootNode.setBytesValue(bodyBytes);
            }

            case OBJECT, LIST -> {
                try (DataInputStream objectStream = new DataInputStream(new ByteArrayInputStream(bodyBytes))) {
                    while (objectStream.available() > 0) {
                        DefaultBinaryObjectNode child = new DefaultBinaryObjectNode(this::convertTo);
                        rootNode.setBytesValue(bodyBytes);
                        readNode(objectStream, child);
                        rootNode.addChild(child);
                    }
                }
            }

            default -> throw new DecodeSerializationException(
                    "Unsupported object type in body: " + rootNode.getObjectType()
            );
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


        if(clazz == Object.class){
            if(node.getObjectType() == ObjectType.NULL) return null;

            clazz = switch (node.getObjectType()){
                case LIST -> List.class;
                case STRING -> String.class;
                case BOOLEAN -> Boolean.class;
                case BYTE -> Byte.class;
                case INT -> Integer.class;
                case DOUBLE -> Double.class;
                case LONG -> Long.class;
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
            Field field = fieldCacheProp.field();
            String elementName = fieldCacheProp.elementName();
           try{
               setFieldType(instance, field, elementName, node);
           }catch (DecodeSerializationException e){
               throw new DecodeSerializationException(
                       String.format(
                               "Failed to set field '%s' of type '%s' in class '%s' from node '%s' of type '%s': %s",
                               elementName,
                               field.getType().getSimpleName(),
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
                               field.getType().getSimpleName(),
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
        }else if(node.getObjectType() == ObjectType.INT){
            objectMap.put(node.getName(), node.getAsInt());
        }else if(node.getObjectType() == ObjectType.LONG){
            objectMap.put(node.getName(), node.getAsLong());
        }else if(node.getObjectType() == ObjectType.DOUBLE){
            objectMap.put(node.getName(), node.getAsDouble());
        }else if(node.getObjectType() == ObjectType.BOOLEAN){
            objectMap.put(node.getName(), node.getAsBoolean());
        } else if (node.getObjectType() == ObjectType.BYTE) {
            objectMap.put(node.getName(), node.getAsBytes());
        }else if (node.getObjectType() == ObjectType.LIST){
            objectMap.put(node.getName(), node.getAsCollection(new CollectionReference<List<Map<String, Object>>>(){}));
        }

        return objectMap;
    }

    @SuppressWarnings("unchecked")
    private <T> T convertSimpleType(Class<T> clazz, BinaryObjectNode node) {

        if (clazz == String.class) {
            return (T) node.getAsString();
        }

        if (clazz == Boolean.class || clazz == boolean.class) {
            Boolean v = node.getAsBoolean();
            return (T) ((v != null) ? v : Boolean.FALSE);
        }

        if (clazz == Integer.class || clazz == int.class) {
            Integer v = node.getAsInt();
            return (T) ((v != null) ? v : Integer.valueOf(0));
        }

        if (clazz == Long.class || clazz == long.class) {
            Long v = node.getAsLong();
            return (T) ((v != null) ? v : Long.valueOf(0L));
        }

        if (clazz == Float.class || clazz == float.class) {
            return (T) Float.valueOf(node.getAsFloat());
        }

        if (clazz == Double.class || clazz == double.class) {
            return (T) Double.valueOf(node.getAsDouble());
        }

        if (clazz == Byte.class || clazz == byte.class) {
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

    private void setFieldType(Object instance, Field field, String elementName, BinaryObjectNode node) throws IllegalAccessException {
        Class<?> fieldType = field.getType();
        BinaryObjectNode childNode = node.getChild(elementName);
        field.setAccessible(true);
        if (childNode == null)  return;


        if(isSimpleType(fieldType) || classIs(fieldType, AtomicBoolean.class, AtomicInteger.class, AtomicLong.class, BigDecimal.class, BigInteger.class)){
            setSimpleFieldType(instance, field, elementName, childNode);
            return;
        }

        if (childNode.getObjectType() == ObjectType.NULL) return;

        if(Collection.class.isAssignableFrom(fieldType)){
            setCollectionFieldType(instance, field, elementName, childNode);
            return;
        }

        if(fieldType.isArray()){
            Object value = createArrayFromNode(field.getType(), elementName, childNode);
            field.set(instance, value);
            return;
        }

        Object innerObject = convertTo(fieldType, childNode);
        field.set(instance, innerObject);
    }

    private void setSimpleFieldType(Object instance, Field field, String elementName, BinaryObjectNode node) throws IllegalAccessException {
        field.setAccessible(true);
        field.set(instance, getValueOnSimpleObject(field.getType(), elementName, node));
    }

    private void setCollectionFieldType(Object instance, Field field, String elementName, BinaryObjectNode node) throws IllegalAccessException {
        ObjectType nodeType = node.getObjectType();

        if (nodeType != ObjectType.LIST) {
            throw new DecodeSerializationException(
                    String.format("Field '%s' expected a LIST but found %s in node '%s'", elementName, nodeType, node.getName())
            );
        }

        Class<?> genericType = Object.class;
        if (field.getGenericType() instanceof ParameterizedType parameterizedType) {
            if (parameterizedType.getActualTypeArguments()[0] instanceof Class<?> clazz) {
                genericType = clazz;
            }
        }

        Collection<Object> collection;
        if (List.class.isAssignableFrom(field.getType())) {
            collection = new ArrayList<>();
        } else if (Set.class.isAssignableFrom(field.getType())) {
            collection = new HashSet<>();
        } else {
            throw new DecodeSerializationException(
                    String.format("Unsupported Collection type '%s' for field '%s' in node '%s'. Only List or Set are supported.",
                            field.getType().getName(), elementName, node.getName())
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

        field.setAccessible(true);
        field.set(instance, collection);
    }


    private Object createArrayFromNode(Class<?> arrayType, String elementName, BinaryObjectNode node) {
        Class<?> componentType = arrayType.getComponentType();
        ObjectType nodeType = node.getObjectType();
        List<BinaryObjectNode> innerNodes = node.getChildren();
        int size = innerNodes.size();

        if (arrayType.equals(byte[].class)) {
            if (nodeType != ObjectType.BYTE) {
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
            return clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new DecodeSerializationException(
                    String.format(
                            "Class '%s' must have a public or protected no-args constructor.",
                            clazz.getName()
                    )
            );
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

    private Object getValueOnSimpleObject(Class<?> fieldType, String elementName, BinaryObjectNode node){
        ObjectType nodeType = node.getObjectType();

        if (nodeType == ObjectType.NULL) {
            if (fieldType.isPrimitive()) {
                if (fieldType == boolean.class) return false;
                if (fieldType == byte.class) return (byte) 0;
                if (fieldType == short.class) return (short) 0;
                if (fieldType == int.class) return 0;
                if (fieldType == long.class) return 0L;
                if (fieldType == float.class) return 0f;
                if (fieldType == double.class) return 0d;
                if (fieldType == char.class) return '\0';

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

        if (fieldType.equals(AtomicInteger.class) || fieldType.equals(Integer.class) || fieldType.equals(int.class)
                || fieldType.equals(Short.class) || fieldType.equals(short.class)) {
            if (nodeType != ObjectType.INT) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected INT but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            int value = node.getAsInt();
            if (fieldType.equals(AtomicInteger.class)) return new AtomicInteger(value);
            return value;
        }

        if (fieldType.equals(AtomicLong.class) || fieldType.equals(Long.class) || fieldType.equals(long.class)) {
            if (nodeType != ObjectType.LONG && nodeType != ObjectType.INT) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected LONG but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            long value = node.getAsLong();
            if (fieldType.equals(AtomicLong.class)) return new AtomicLong(value);
            return value;
        }

        if (fieldType.equals(Float.class) || fieldType.equals(float.class)) {
            if (nodeType != ObjectType.DOUBLE) {
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
            if (nodeType != ObjectType.STRING && nodeType != ObjectType.BYTE) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected STRING/BYTE for BigDecimal but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            return new BigDecimal(node.getAsString());
        }

        if (fieldType.equals(BigInteger.class)) {
            if (nodeType != ObjectType.STRING && nodeType != ObjectType.BYTE) {
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
            if (nodeType != ObjectType.BYTE) {
                throw new DecodeSerializationException(
                        String.format("Field '%s' expected BYTE but found %s in node '%s'", elementName, nodeType, node.getName())
                );
            }
            byte[] bytes = node.getAsBytes();
            return (bytes.length > 0) ? bytes[0] : 0;
        }

        if (fieldType.equals(byte[].class) || fieldType.equals(Byte[].class)) {
            if (nodeType != ObjectType.BYTE) {
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
}
