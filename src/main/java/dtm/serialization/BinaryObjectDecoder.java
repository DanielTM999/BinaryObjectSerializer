package dtm.serialization;

import dtm.serialization.exceptions.DecodeSerializationException;

import java.io.File;
import java.io.InputStream;

public interface BinaryObjectDecoder {
    BinaryObjectNode readAsTree(byte[] bytes) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(File file) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(InputStream stream) throws DecodeSerializationException;

    <T> T readAsObject(byte[] bytes, Class<T> ref) throws DecodeSerializationException;
    <T> T readAsObject(File file, Class<T> ref) throws DecodeSerializationException;
    <T> T readAsObject(InputStream stream, Class<T> ref) throws DecodeSerializationException;

}
