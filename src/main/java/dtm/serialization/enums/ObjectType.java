package dtm.serialization.enums;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public enum ObjectType {
    STRING((byte) 0x01),
    INT((byte) 0x02),
    LONG((byte) 0x03),
    BOOLEAN((byte) 0x04),
    DOUBLE((byte) 0x05),
    OBJECT((byte) 0x06),
    BYTE((byte) 0x07),
    LIST((byte) 0x08),
    NULL((byte) 0x09);


    private final byte id;

    ObjectType(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    private static final Map<Byte, ObjectType> BY_ID = new ConcurrentHashMap<>();

    static {
        for (ObjectType t : values()) {
            BY_ID.put(t.id, t);
        }
    }

    public static ObjectType fromId(byte id) {
        ObjectType type = BY_ID.get(id);
        if (type == null) {
           return null;
        }
        return type;
    }
}
