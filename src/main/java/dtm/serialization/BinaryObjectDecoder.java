package dtm.serialization;

import dtm.serialization.exceptions.DecodeSerializationException;

import java.io.File;
import java.io.InputStream;
import java.util.Collection;

public interface BinaryObjectDecoder {
    BinaryObjectNode readAsTree(byte[] bytes) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(File file) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(InputStream stream) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(byte[] bytes, DescriptorObserver observer) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(File file, DescriptorObserver observer) throws DecodeSerializationException;
    BinaryObjectNode readAsTree(InputStream stream, DescriptorObserver observer) throws DecodeSerializationException;
    BinaryObjectNode readAsTreeWithOptions(byte[] bytes, DecodeOptions options) throws DecodeSerializationException;
    BinaryObjectNode readAsTreeWithOptions(File file, DecodeOptions options) throws DecodeSerializationException;
    BinaryObjectNode readAsTreeWithOptions(InputStream stream, DecodeOptions options) throws DecodeSerializationException;

    <T> T readAsObject(byte[] bytes, Class<T> ref) throws DecodeSerializationException;
    <T> T readAsObject(File file, Class<T> ref) throws DecodeSerializationException;
    <T> T readAsObject(InputStream stream, Class<T> ref) throws DecodeSerializationException;
    <T> T readAsObject(byte[] bytes, Class<T> ref, DescriptorObserver observer) throws DecodeSerializationException;
    <T> T readAsObject(File file, Class<T> ref, DescriptorObserver observer) throws DecodeSerializationException;
    <T> T readAsObject(InputStream stream, Class<T> ref, DescriptorObserver observer) throws DecodeSerializationException;
    <T> T readAsObjectWithOptions(byte[] bytes, Class<T> ref, DecodeOptions options) throws DecodeSerializationException;
    <T> T readAsObjectWithOptions(File file, Class<T> ref, DecodeOptions options) throws DecodeSerializationException;
    <T> T readAsObjectWithOptions(InputStream stream, Class<T> ref, DecodeOptions options) throws DecodeSerializationException;

    <T extends Collection<?>> T readAsCollection(byte[] bytes, CollectionReference<T> ref) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollection(File file, CollectionReference<T> ref) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollection(InputStream stream, CollectionReference<T> ref) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollection(byte[] bytes, CollectionReference<T> ref, DescriptorObserver observer) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollection(File file, CollectionReference<T> ref, DescriptorObserver observer) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollection(InputStream stream, CollectionReference<T> ref, DescriptorObserver observer) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollectionWithOptions(byte[] bytes, CollectionReference<T> ref, DecodeOptions options) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollectionWithOptions(File file, CollectionReference<T> ref, DecodeOptions options) throws DecodeSerializationException;
    <T extends Collection<?>> T readAsCollectionWithOptions(InputStream stream, CollectionReference<T> ref, DecodeOptions options) throws DecodeSerializationException;

}
