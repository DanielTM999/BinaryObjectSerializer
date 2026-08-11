package dtm.serialization;

import dtm.serialization.enums.ObjectType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface BinaryObjectNode extends AutoCloseable {

    ObjectType getObjectType();
    String getName();

    long getBodyLength();

    List<BinaryObjectNode> getChildren();

    BinaryObjectNode getChild(String key);
    BinaryObjectNode getChild(int index);

    String getAsString();
    Long getAsLong();
    Integer getAsInt();
    Boolean getAsBoolean();
    byte[] getAsBytes();

    InputStream openStream() throws IOException;

    StreamContent getAsStreamContent();
    float getAsFloat();
    double getAsDouble();
    <T> T getAsObject(Class<T> clazz);
    <T extends Collection<?>> T getAsCollection(CollectionReference<T> ref);
    Map<String, Object> getAsMap();
    Map<String, byte[]> getAsByteMap();
    Map<String, BinaryObjectNode> getAsBinaryObjectNodeMap();

    @Override
    void close() throws IOException;
}
