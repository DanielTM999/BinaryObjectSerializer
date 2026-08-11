package dtm.serialization;

import dtm.serialization.annotations.LargeContent;
import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class OnDemandTreeTest {

    @TempDir
    Path tempDir;

    @Test
    void defersByteArrayMaterializationUntilTheNodeIsRead() throws Exception {
        byte[] payload = new byte[257];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 47 + 3);
        Document source = new Document();
        source.headers = new Headers("POST", "/upload");
        source.payload = payload;

        byte[] frame = new BinaryObjectEncoderMapper().encodeToByteArray(source);
        BinaryObjectNode tree = new BinaryObjectDecoderMapper().readAsTreeWithOptions(
                frame,
                DecodeOptions.DEFAULT.withDeserializeOnDemand(true)
        );

        BinaryObjectNode payloadNode = tree.getChild("payload");
        int payloadOffset = indexOf(frame, payload);
        assertTrue(payloadOffset >= 0);
        frame[payloadOffset] = 99;

        byte[] expected = Arrays.copyOf(payload, payload.length);
        expected[0] = 99;
        assertArrayEquals(expected, payloadNode.getAsBytes());
        assertEquals("POST", tree.getChild("headers").getChild("method").getAsString());
    }

    @Test
    void consumesOneFrameAndLeavesTheCallerStreamOpen() throws Exception {
        Document source = new Document();
        source.headers = new Headers("PUT", "/objects/1");
        source.payload = new byte[180_000];
        Arrays.fill(source.payload, (byte) 7);

        BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
        byte[] firstFrame = encoder.encodeToByteArray(source);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(firstFrame);
        encoder.encode("second", frames);

        TrackingInputStream input = new TrackingInputStream(frames.toByteArray());
        BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();
        BinaryObjectNode tree = decoder.readAsTreeWithOptions(
                input,
                DecodeOptions.DEFAULT.withDeserializeOnDemand(true)
        );

        assertFalse(input.closed);
        assertTrue(input.available() > 0);
        assertTrue(input.position() < firstFrame.length, "the binary body must still be unread");
        assertEquals("/objects/1", tree.getChild("headers").getChild("path").getAsString());
        assertEquals(dtm.serialization.enums.ObjectType.BYTES, tree.getChild("payload").getObjectType());
        assertEquals(180_000, tree.getChild("payload").getBodyLength());
        assertTrue(input.position() < firstFrame.length, "reading body metadata must not consume its bytes");
        try (InputStream payload = tree.getChild("payload").openStream()) {
            assertEquals(180_000, payload.readAllBytes().length);
        }
        assertEquals("second", decoder.readAsObject(input, String.class));
        assertEquals(0, input.available());
    }

    @Test
    void closingTheTreeDrainsAnUnreadBodyAndAlignsTheNextFrame() throws Exception {
        Document source = new Document();
        source.headers = new Headers("POST", "/discard");
        source.payload = new byte[190_000];

        BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        encoder.encode(source, frames);
        encoder.encode("next", frames);

        ByteArrayInputStream input = new ByteArrayInputStream(frames.toByteArray());
        BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();
        BinaryObjectNode tree = decoder.readAsTreeWithOptions(
                input, DecodeOptions.DEFAULT.withDeserializeOnDemand(true)
        );

        tree.close();

        assertEquals("next", decoder.readAsObject(input, String.class));
    }

    @Test
    void keepsLibraryOwnedFileInputOpenUntilTheDeferredBodyFinishes() throws Exception {
        Document source = new Document();
        source.headers = new Headers("GET", "/file");
        source.payload = new byte[170_000];
        Arrays.fill(source.payload, (byte) 21);
        Path frame = tempDir.resolve("on-demand.bin");
        Files.write(frame, new BinaryObjectEncoderMapper().encodeToByteArray(source));

        BinaryObjectNode tree = new BinaryObjectDecoderMapper().readAsTreeWithOptions(
                frame.toFile(), DecodeOptions.DEFAULT.withDeserializeOnDemand(true)
        );

        try (tree; InputStream body = tree.getChild("payload").openStream()) {
            assertArrayEquals(source.payload, body.readAllBytes());
        }
    }

    @Test
    void exposesLargeTreeContentAsAnInMemoryStreamViewOnDemand() throws Exception {
        byte[] payload = new byte[220_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        LargeDocument source = new LargeDocument();
        source.content = StreamContent.of(payload.length, () -> new ByteArrayInputStream(payload));

        byte[] frame = new BinaryObjectEncoderMapper().encodeToByteArray(source);
        BinaryObjectNode tree = new BinaryObjectDecoderMapper().readAsTreeWithOptions(
                new ByteArrayInputStream(frame),
                DecodeOptions.DEFAULT.withDeserializeOnDemand(true)
        );

        StreamContent content = tree.getChild("content").getAsStreamContent();
        assertFalse(content instanceof StreamLazy);
        try (InputStream body = content.openStream()) {
            assertArrayEquals(payload, body.readAllBytes());
        }
        assertThrows(IOException.class, content::openStream);
        content.close();
    }

    @Test
    void optionBuildersPreserveOnDemandMode() {
        DecodeOptions options = DecodeOptions.DEFAULT
                .withDeserializeOnDemand(true)
                .withObserver(new DescriptorObserver() { })
                .withLargeContentResolver(context -> null);

        assertTrue(options.deserializeOnDemand());
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public static class Document {
        public Headers headers;
        public byte[] payload;
    }

    public static class Headers {
        public String method;
        public String path;

        public Headers() {
        }

        private Headers(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }

    public static class LargeDocument {
        @LargeContent
        public StreamContent content;
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        private int position() {
            return pos;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            if (length > 64 * 1024) fail("read request exceeded 64 KiB: " + length);
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
