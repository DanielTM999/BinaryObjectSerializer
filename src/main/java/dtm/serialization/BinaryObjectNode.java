package dtm.serialization;

import dtm.serialization.enums.ObjectType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface BinaryObjectNode {

    ObjectType getObjectType();
    String getName();

    List<BinaryObjectNode> getChildren();

    BinaryObjectNode getChild(String key);
    BinaryObjectNode getChild(int index);

    String getAsString();
    Long getAsLong();
    Integer getAsInt();
    Boolean getAsBoolean();
    byte[] getAsBytes();
    float getAsFloat();
    double getAsDouble();
    <T> T getAsObject(Class<T> clazz);
    <T extends Collection<?>> T getAsCollection(CollectionReference<T> ref);
    Map<String, Object> getAsMap();
    Map<String, byte[]> getAsByteMap();
}
