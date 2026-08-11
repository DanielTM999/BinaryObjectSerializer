package dtm.serialization;

import dtm.serialization.annotations.LargeContent;
import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EncodeToStreamTest {

    @Test
    void producesTheSameFrameAsEncodeToByteArray() throws Exception {
        Message source = new Message();
        source.name = "stream";
        source.payload = new byte[]{1, 2, 3, 4};
        BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();

        byte[] expected = encoder.encodeToByteArray(source);
        byte[] actual;
        try (InputStream encoded = encoder.encodeToStream(source)) {
            actual = encoded.readAllBytes();
        }

        assertArrayEquals(expected, actual);
    }

    @Test
    void streamsLargeContentDirectlyIntoTheDecoder() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024 + 31];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 29);
        Upload source = new Upload();
        source.headers = "application/octet-stream";
        source.payload = StreamContent.of(payload.length, () -> new ByteArrayInputStream(payload));

        BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
        BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();
        try (InputStream encoded = encoder.encodeToStream(source);
             BinaryObjectNode tree = decoder.readAsTreeWithOptions(
                     encoded, DecodeOptions.DEFAULT.withDeserializeOnDemand(true));
             InputStream body = tree.getChild("payload").openStream()) {
            assertEquals("application/octet-stream", tree.getChild("headers").getAsString());
            assertEquals(payload.length, tree.getChild("payload").getBodyLength());
            assertArrayEquals(payload, body.readAllBytes());
        }
    }

    @Test
    void startsEncodingOnlyWhenTheReturnedStreamIsRead() throws Exception {
        AtomicBoolean opened = new AtomicBoolean();
        Upload source = new Upload();
        source.headers = "lazy";
        source.payload = StreamContent.of(3, () -> {
            opened.set(true);
            return new ByteArrayInputStream(new byte[]{7, 8, 9});
        });

        InputStream encoded = new BinaryObjectEncoderMapper().encodeToStream(source);
        assertFalse(opened.get());

        assertTrue(encoded.read() >= 0);
        encoded.readAllBytes();
        assertTrue(opened.get());
        encoded.close();
    }

    @Test
    void reportsProducerFailuresWhileTheStreamIsConsumed() throws Exception {
        Upload source = new Upload();
        source.headers = "broken";
        source.payload = StreamContent.of(10, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}));

        try (InputStream encoded = new BinaryObjectEncoderMapper().encodeToStream(source)) {
            IOException error = assertThrows(IOException.class, encoded::readAllBytes);
            assertInstanceOf(EOFException.class, error.getCause());
        }
    }

    public static class Message {
        public String name;
        public byte[] payload;
    }

    public static class Upload {
        public String headers;
        @LargeContent
        public StreamContent payload;
    }
}
