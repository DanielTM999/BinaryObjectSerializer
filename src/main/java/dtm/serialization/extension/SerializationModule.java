package dtm.serialization.extension;

public interface SerializationModule {
    boolean supports(Class<?> type);
    byte[] serializeType(Object type);
    Object deserializeType(Class<?> type, byte[] data);
}
