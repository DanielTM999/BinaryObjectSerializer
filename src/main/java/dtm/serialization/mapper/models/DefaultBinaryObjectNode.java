package dtm.serialization.mapper.models;

import dtm.serialization.BinaryObjectEncoder;
import dtm.serialization.BinaryObjectNode;
import dtm.serialization.CollectionReference;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.exceptions.DecodeSerializationException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class DefaultBinaryObjectNode implements BinaryObjectNode {

    private ObjectType objectType;
    private String name;
    private byte[] bytesValue;
    private final List<BinaryObjectNode> children;
    private final BiFunction<Object, DefaultBinaryObjectNode, Object> convertAction;

    public DefaultBinaryObjectNode(
            BiFunction<Object, DefaultBinaryObjectNode, Object> convertAction
    ){
        this.children = new ArrayList<>();
        this.convertAction = convertAction;
    }


    @Override
    public ObjectType getObjectType() {
        return objectType;
    }

    @Override
    public String getName() {
        return name;
    }


    @Override
    public List<BinaryObjectNode> getChildren() {
        return children == null ? Collections.emptyList() : children;
    }

    @Override
    public BinaryObjectNode getChild(String key) {
        if (children == null) return null;

        for (BinaryObjectNode child : children) {
            if (child.getName().equals(key)) {
                return child;
            }
        }
        return null;
    }

    @Override
    public BinaryObjectNode getChild(int index) {
        if (index < 0 || index >= children.size()) return null;
        return children.get(index);
    }

    @Override
    public String getAsString() {
        if (objectType != ObjectType.STRING) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not STRING, but %s", name, objectType)
            );
        }
        return bytesValue != null ? new String(bytesValue, StandardCharsets.UTF_8) : "";
    }

    @Override
    public Long getAsLong() {
        if (objectType != ObjectType.LONG && objectType != ObjectType.INT) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not LONG/INT, but %s", name, objectType)
            );
        }
        if (bytesValue == null || bytesValue.length != 8) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for LONG at node '%s': %d", name, bytesValue == null ? 0 : bytesValue.length)
            );
        }
        return ByteBuffer.wrap(bytesValue).getLong();
    }

    @Override
    public Integer getAsInt() {
        if (objectType != ObjectType.INT) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not INT, but %s", name, objectType)
            );
        }
        if (bytesValue == null || bytesValue.length != 4) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for INT at node '%s': %d", name, bytesValue == null ? 0 : bytesValue.length)
            );
        }
        return ByteBuffer.wrap(bytesValue).getInt();
    }

    @Override
    public Boolean getAsBoolean() {
        if (objectType != ObjectType.BOOLEAN) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not BOOLEAN, but %s", name, objectType)
            );
        }
        if (bytesValue == null || bytesValue.length != 1) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for BOOLEAN at node '%s': %d", name, bytesValue == null ? 0 : bytesValue.length)
            );
        }
        return bytesValue[0] != 0;
    }

    @Override
    public byte[] getAsBytes() {
        return bytesValue;
    }

    @Override
    public float getAsFloat() {
        if (objectType != ObjectType.DOUBLE) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not FLOAT, but %s", name, objectType)
            );
        }
        if (bytesValue == null || bytesValue.length != 4) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for FLOAT at node '%s': %d", name, bytesValue == null ? 0 : bytesValue.length)
            );
        }
        return ByteBuffer.wrap(bytesValue).getFloat();
    }

    @Override
    public double getAsDouble() {
        if (objectType != ObjectType.DOUBLE) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not DOUBLE, but %s", name, objectType)
            );
        }
        if (bytesValue == null || bytesValue.length != 8) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for FLOAT at node '%s': %d", name, bytesValue == null ? 0 : bytesValue.length)
            );
        }
        return ByteBuffer.wrap(bytesValue).getDouble();
    }

    @Override
    public <T> T getAsObject(Class<T> clazz) {
        try{
            return clazz.cast(convertAction.apply(clazz, this));
        }catch (Exception e){
            throw new DecodeSerializationException(e);
        }
    }

    @Override
    public <T extends Collection<?>> T getAsCollection(CollectionReference<T> ref) {
        try{
            return (T)convertAction.apply(ref, this);
        }catch (Exception e){
            throw new DecodeSerializationException(e);
        }
    }

    @Override
    public Map<String, Object> getAsMap() {
        Map<String, Object> objectMap = new ConcurrentHashMap<>();

        for(BinaryObjectNode node : children){
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
        }

        return objectMap;
    }

    @Override
    public Map<String, byte[]> getAsByteMap() {
        Map<String, byte[]> objectMap = new ConcurrentHashMap<>();

        for(BinaryObjectNode node : children){
            objectMap.put(node.getName(), node.getAsBytes());
        }
        return objectMap;
    }

    @Override
    public String toString() {
        return toString(0);
    }

    public void setObjectType(ObjectType objectType) {
        this.objectType = objectType;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setBytesValue(byte[] bytesValue) {
        this.bytesValue = bytesValue;
    }

    public void addChild(BinaryObjectNode  child) {
        this.children.add(child);
    }


    private String toString(int indent) {
        StringBuilder sb = new StringBuilder();
        String prefix = "  ".repeat(indent);

        sb.append(prefix)
                .append(name != null ? name : "null")
                .append(" : ")
                .append(objectType);

        // Tenta mostrar valor legível
        if (bytesValue != null && !bytesValueIsContainer()) {
            sb.append(" = ");
            try {
                switch (objectType) {
                    case STRING -> sb.append(getAsString());
                    case INT -> sb.append(getAsInt());
                    case LONG -> sb.append(getAsLong());
                    case BOOLEAN -> sb.append(getAsBoolean());
                    case DOUBLE -> sb.append(ByteBuffer.wrap(bytesValue).getFloat());
                    case NULL -> sb.append("null");
                    default -> sb.append(Arrays.toString(bytesValue));
                }
            } catch (DecodeSerializationException e) {
                sb.append(Arrays.toString(bytesValue));
            }
        }

        if (!children.isEmpty()) {
            sb.append(" {\n");
            for (BinaryObjectNode child : children) {
                if (child instanceof DefaultBinaryObjectNode dChild) {
                    sb.append(dChild.toString(indent + 1)).append("\n");
                } else {
                    sb.append("  ".repeat(indent + 1)).append(child.toString()).append("\n");
                }
            }
            sb.append(prefix).append("}");
        }

        return sb.toString();
    }

    private boolean bytesValueIsContainer() {
        return objectType == ObjectType.OBJECT || objectType == ObjectType.LIST;
    }

}
