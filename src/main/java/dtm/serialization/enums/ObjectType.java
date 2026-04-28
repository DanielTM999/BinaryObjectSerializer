package dtm.serialization.enums;

public enum ObjectType {
    STRING((byte) 0x01),
    I8((byte) 0x02),
    I16((byte) 0x03),
    I32((byte) 0x04),
    I64((byte) 0x05),
    BOOLEAN((byte) 0x06),
    DOUBLE((byte) 0x07),
    FLOAT((byte) 0x08),
    OBJECT((byte) 0x09),
    BYTES((byte) 0x10),
    LIST((byte) 0x11),
    NULL((byte) 0x12);


    private final byte id;

    ObjectType(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    public static ObjectType fromId(byte id) {
        return switch (id) {
            case 0x01 -> STRING;
            case 0x02 -> I8;
            case 0x03 -> I16;
            case 0x04 -> I32;
            case 0x05 -> I64;
            case 0x06 -> BOOLEAN;
            case 0x07 -> DOUBLE;
            case 0x08 -> FLOAT;
            case 0x09 -> OBJECT;
            case 0x10 -> BYTES;
            case 0x11 -> LIST;
            case 0x12 -> NULL;
            default -> null;
        };
    }
}
