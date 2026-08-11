package dtm.serialization;

import dtm.serialization.exceptions.EncodeSerializationException;

import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

public interface BinaryObjectEncoder {
    <T> byte[] encodeToByteArray(T object) throws EncodeSerializationException;
    <T> void encode(T object, OutputStream destination) throws EncodeSerializationException;
    <T> List<byte[]> encodeToByteArrayList(Collection<T> objects) throws EncodeSerializationException;


}
