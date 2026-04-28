package dtm.serialization.mapper.models;

import dtm.serialization.BinaryObjectNode;
import dtm.serialization.CollectionReference;
import dtm.serialization.enums.ObjectType;
import dtm.serialization.exceptions.DecodeSerializationException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

public class DefaultBinaryObjectNode implements BinaryObjectNode {

    private ObjectType objectType;
    private String name;
    private byte[] bytesValue;
    private byte[] sourceBytes;
    private int sourceOffset;
    private int sourceLength;
    private final List<BinaryObjectNode> children;
    private Map<String, BinaryObjectNode> childrenByName;
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
        return children;
    }

    @Override
    public BinaryObjectNode getChild(String key) {
        if (key == null || children.isEmpty()) return null;

        if (children.size() > 4) {
            if (childrenByName == null) {
                Map<String, BinaryObjectNode> index = new HashMap<>(children.size() * 2);
                for (BinaryObjectNode child : children) {
                    index.put(child.getName(), child);
                }
                childrenByName = index;
            }
            return childrenByName.get(key);
        }

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
        int length = valueLength();
        return length > 0 ? new String(valueSource(), valueOffset(), length, StandardCharsets.UTF_8) : "";
    }

    @Override
    public Long getAsLong() {
        if (objectType != ObjectType.I64 && objectType != ObjectType.I32
                && objectType != ObjectType.I16 && objectType != ObjectType.I8) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not LONG/INT, but %s", name, objectType)
            );
        }

        int length = valueLength();
        if (objectType != ObjectType.I64) {
            return (long) getAsInt();
        }

        if (length != 8) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for LONG at node '%s': %d", name, length)
            );
        }
        return readLongValue();
    }

    @Override
    public Integer getAsInt() {
        if (objectType != ObjectType.I32 && objectType != ObjectType.I16 && objectType != ObjectType.I8) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not INT, but %s", name, objectType)
            );
        }
        int length = valueLength();
        if (objectType == ObjectType.I8 && length == 1) {
            return (int) byteAt(0);
        }
        if (objectType == ObjectType.I16 && length == 2) {
            return (int) readShortValue();
        }
        if (length != 4) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for INT at node '%s': %d", name, length)
            );
        }
        return readIntValue();
    }

    @Override
    public Boolean getAsBoolean() {
        if (objectType != ObjectType.BOOLEAN) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not BOOLEAN, but %s", name, objectType)
            );
        }
        int length = valueLength();
        if (length != 1) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for BOOLEAN at node '%s': %d", name, length)
            );
        }
        return byteAt(0) != 0;
    }

    @Override
    public byte[] getAsBytes() {
        return materializedBytes();
    }

    @Override
    public float getAsFloat() {
        if (objectType != ObjectType.FLOAT) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not FLOAT, but %s", name, objectType)
            );
        }
        int length = valueLength();
        if (length != 4) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for FLOAT at node '%s': %d", name, length)
            );
        }
        return Float.intBitsToFloat(readIntValue());
    }

    @Override
    public double getAsDouble() {
        if (objectType != ObjectType.DOUBLE) {
            throw new DecodeSerializationException(
                    String.format("Node '%s' is not DOUBLE, but %s", name, objectType)
            );
        }
        int length = valueLength();
        if (length != 8) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte array length for DOUBLE at node '%s': %d", name, length)
            );
        }
        return Double.longBitsToDouble(readLongValue());
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
        Map<String, Object> objectMap = new HashMap<>();

        for(BinaryObjectNode node : children){
            if(node.getObjectType() == ObjectType.OBJECT){
                objectMap.put(node.getName(), node.getAsMap());
            }else if(node.getObjectType() == ObjectType.STRING){
                objectMap.put(node.getName(), node.getAsString());
            }else if(node.getObjectType() == ObjectType.I8){
                objectMap.put(node.getName(), (byte) node.getAsInt().intValue());
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
                objectMap.put(node.getName(), node.getAsCollection(new CollectionReference<List<Object>>(){}));
            }
        }

        return objectMap;
    }

    @Override
    public Map<String, byte[]> getAsByteMap() {
        Map<String, byte[]> objectMap = new HashMap<>();

        for(BinaryObjectNode node : children){
            objectMap.put(node.getName(), node.getAsBytes());
        }
        return objectMap;
    }

    @Override
    public Map<String, BinaryObjectNode> getAsBinaryObjectNodeMap() {
        Map<String, BinaryObjectNode> objectMap = new HashMap<>();
        for(BinaryObjectNode node : children){
            objectMap.put(node.getName(), node);
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
        this.bytesValue = (bytesValue != null) ? bytesValue : new byte[0];
        this.sourceBytes = null;
        this.sourceOffset = 0;
        this.sourceLength = 0;
    }

    public void setBytesValue(byte[] sourceBytes, int sourceOffset, int sourceLength) {
        this.bytesValue = null;
        this.sourceBytes = sourceBytes;
        this.sourceOffset = sourceOffset;
        this.sourceLength = sourceLength;
    }

    public void addChild(BinaryObjectNode  child) {
        this.children.add(child);
        if (childrenByName != null) {
            childrenByName.put(child.getName(), child);
        }
    }

    public void addAllChilds(Collection<BinaryObjectNode> childs) {
        this.children.addAll(childs);
        this.childrenByName = null;
    }


    private String toString(int indent) {
        StringBuilder sb = new StringBuilder();
        String prefix = "  ".repeat(indent);

        sb.append(prefix)
                .append(name != null ? name : "null")
                .append(" : ")
                .append(objectType);

        // Tenta mostrar valor legível
        if (valueLength() > 0 && !bytesValueIsContainer()) {
            sb.append(" = ");
            try {
                switch (objectType) {
                    case STRING -> sb.append(getAsString());
                    case I32 -> sb.append(getAsInt());
                    case I64 -> sb.append(getAsLong());
                    case BOOLEAN -> sb.append(getAsBoolean());
                    case FLOAT -> sb.append(getAsFloat());
                    case DOUBLE -> sb.append(getAsDouble());
                    case NULL -> sb.append("null");
                    default -> sb.append(Arrays.toString(materializedBytes()));
                }
            } catch (DecodeSerializationException e) {
                sb.append(Arrays.toString(materializedBytes()));
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

    private byte[] valueSource() {
        return bytesValue != null ? bytesValue : sourceBytes;
    }

    private int valueOffset() {
        return bytesValue != null ? 0 : sourceOffset;
    }

    private int valueLength() {
        if (bytesValue != null) return bytesValue.length;
        if (sourceBytes != null) return sourceLength;
        return 0;
    }

    private byte byteAt(int index) {
        if (index < 0 || index >= valueLength()) {
            throw new DecodeSerializationException(
                    String.format("Invalid byte access at node '%s': index %d, length %d", name, index, valueLength())
            );
        }
        return valueSource()[valueOffset() + index];
    }

    private int readIntValue() {
        return ((byteAt(0) & 0xFF) << 24)
                | ((byteAt(1) & 0xFF) << 16)
                | ((byteAt(2) & 0xFF) << 8)
                | (byteAt(3) & 0xFF);
    }

    private short readShortValue() {
        return (short) (((byteAt(0) & 0xFF) << 8)
                | (byteAt(1) & 0xFF));
    }

    private long readLongValue() {
        return ((long) (byteAt(0) & 0xFF) << 56)
                | ((long) (byteAt(1) & 0xFF) << 48)
                | ((long) (byteAt(2) & 0xFF) << 40)
                | ((long) (byteAt(3) & 0xFF) << 32)
                | ((long) (byteAt(4) & 0xFF) << 24)
                | ((long) (byteAt(5) & 0xFF) << 16)
                | ((long) (byteAt(6) & 0xFF) << 8)
                | (long) (byteAt(7) & 0xFF);
    }

    private byte[] materializedBytes() {
        if (bytesValue == null) {
            bytesValue = sourceBytes == null
                    ? new byte[0]
                    : Arrays.copyOfRange(sourceBytes, sourceOffset, sourceOffset + sourceLength);
            sourceBytes = null;
            sourceOffset = 0;
            sourceLength = 0;
        }
        return bytesValue;
    }

    private boolean bytesValueIsContainer() {
        return objectType == ObjectType.OBJECT || objectType == ObjectType.LIST;
    }

}
