package dtm.serialization;

import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DescriptorObserverTest {

    public static class ObservedValue {
        public String name;
        public int count;

        public ObservedValue() {
        }

        public ObservedValue(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    @Test
    void deliversOrderedEventsOutsideDecoderThread() throws Exception {
        byte[] bytes = new BinaryObjectEncoderMapper().encodeToByteArray(new ObservedValue("test", 7));
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "descriptor-observer-test");
            thread.setDaemon(true);
            return thread;
        });
        List<String> sequence = Collections.synchronizedList(new ArrayList<>());
        List<DescriptorEvent> progress = Collections.synchronizedList(new ArrayList<>());
        List<String> callbackThreads = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(1);
        String decoderThread = Thread.currentThread().getName();

        DescriptorObserver observer = new DescriptorObserver() {
            @Override
            public void onDescriptorStarted(DescriptorEvent event) {
                callbackThreads.add(Thread.currentThread().getName());
                sequence.add("start:" + event.descriptorOffset());
            }

            @Override
            public void onProgress(DescriptorEvent event) {
                callbackThreads.add(Thread.currentThread().getName());
                progress.add(event);
                sequence.add("progress:" + event.descriptorOffset());
            }

            @Override
            public void onDescriptorFinished(DescriptorEvent event) {
                callbackThreads.add(Thread.currentThread().getName());
                sequence.add("finish:" + event.descriptorOffset());
                if (event.percentage() == 100.0) completed.countDown();
            }

            @Override
            public ExecutorService getExecutorService() {
                return executor;
            }

            @Override
            public boolean shouldAutoCloseExecutorService() {
                return false;
            }
        };

        ObservedValue decoded = new BinaryObjectDecoderMapper().readAsObject(bytes, ObservedValue.class, observer);

        assertEquals("test", decoded.name);
        assertEquals(7, decoded.count);
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertFalse(callbackThreads.isEmpty());
        assertTrue(callbackThreads.stream().noneMatch(decoderThread::equals));
        assertFalse(progress.isEmpty());
        assertEquals(0.0, progress.get(0).percentage());
        assertEquals(100.0, progress.get(progress.size() - 1).percentage());
        assertTrue(progress.stream().allMatch(event -> event.bytesProcessed() >= 0
                && event.bytesProcessed() <= event.totalBytes()));
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i).percentage() > progress.get(i - 1).percentage());
        }
        assertTrue(sequence.get(0).startsWith("start:"));
        assertTrue(sequence.get(sequence.size() - 1).startsWith("finish:"));
        assertFalse(executor.isShutdown());

        executor.shutdownNow();
    }

    @Test
    void closesUserExecutorOnlyAfterQueuedCallbacksFinish() throws Exception {
        byte[] bytes = new BinaryObjectEncoderMapper().encodeToByteArray(new ObservedValue("close", 1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch finalEvent = new CountDownLatch(1);

        DescriptorObserver observer = new DescriptorObserver() {
            @Override
            public void onDescriptorFinished(DescriptorEvent event) {
                if (event.percentage() == 100.0) finalEvent.countDown();
            }

            @Override
            public ExecutorService getExecutorService() {
                return executor;
            }
        };

        new BinaryObjectDecoderMapper().readAsTree(bytes, observer);

        assertTrue(finalEvent.await(5, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.isShutdown());
    }

    @Test
    void commonPoolIsNeverClosedAndObserverFailureDoesNotFailDecode() throws Exception {
        byte[] bytes = new BinaryObjectEncoderMapper().encodeToByteArray(new ObservedValue("safe", 2));
        CountDownLatch completed = new CountDownLatch(1);
        DescriptorObserver observer = new DescriptorObserver() {
            @Override
            public void onDescriptorStarted(DescriptorEvent event) {
                throw new IllegalStateException("observer failure");
            }

            @Override
            public void onDescriptorFinished(DescriptorEvent event) {
                if (event.percentage() == 100.0) completed.countDown();
            }
        };

        ObservedValue value = new BinaryObjectDecoderMapper().readAsObject(bytes, ObservedValue.class, observer);

        assertEquals("safe", value.name);
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertFalse(ForkJoinPool.commonPool().isShutdown());
    }

    @Test
    void supportsObserverOverloadsForFilesStreamsAndCollections() throws Exception {
        BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
        BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();
        byte[] objectBytes = encoder.encodeToByteArray(new ObservedValue("sources", 3));
        File file = File.createTempFile("binary-object-observer", ".bin");
        try {
            Files.write(file.toPath(), objectBytes);
            assertEquals("sources", decoder.readAsObject(file, ObservedValue.class, null).name);
            assertEquals("sources", decoder.readAsObject(
                    new ByteArrayInputStream(objectBytes), ObservedValue.class, null).name);

            List<String> expected = List.of("a", "b");
            byte[] collectionBytes = encoder.encodeToByteArray(expected);
            List<String> decoded = decoder.readAsCollection(
                    collectionBytes,
                    new CollectionReference<List<String>>() {},
                    null
            );
            assertEquals(expected, decoded);
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    void inlineExecutorStillDoesNotInvokeObserverOnDecoderThread() throws Exception {
        byte[] bytes = new BinaryObjectEncoderMapper().encodeToByteArray("inline");
        CountDownLatch completed = new CountDownLatch(1);
        List<Thread> callbackThreads = Collections.synchronizedList(new ArrayList<>());
        ExecutorService inlineExecutor = new InlineExecutorService();
        Thread decoderThread = Thread.currentThread();

        DescriptorObserver observer = new DescriptorObserver() {
            @Override
            public void onDescriptorFinished(DescriptorEvent event) {
                callbackThreads.add(Thread.currentThread());
                completed.countDown();
            }

            @Override
            public ExecutorService getExecutorService() {
                return inlineExecutor;
            }

            @Override
            public boolean shouldAutoCloseExecutorService() {
                return false;
            }
        };

        assertEquals("inline", new BinaryObjectDecoderMapper().readAsObject(bytes, String.class, observer));
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertTrue(callbackThreads.stream().noneMatch(decoderThread::equals));
    }

    @Test
    void malformedPayloadStillClosesOwnedExecutor() throws Exception {
        byte[] valid = new BinaryObjectEncoderMapper().encodeToByteArray(new ObservedValue("broken", 9));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DescriptorObserver observer = new DescriptorObserver() {
            @Override
            public ExecutorService getExecutorService() {
                return executor;
            }
        };

        assertThrows(
                dtm.serialization.exceptions.DecodeSerializationException.class,
                () -> new BinaryObjectDecoderMapper().readAsTree(truncated, observer)
        );
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectedCallbacksDoNotAffectDecoding() {
        byte[] bytes = new BinaryObjectEncoderMapper().encodeToByteArray("rejected");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        DescriptorObserver observer = new DescriptorObserver() {
            @Override
            public ExecutorService getExecutorService() {
                return executor;
            }
        };

        assertEquals("rejected", new BinaryObjectDecoderMapper().readAsObject(bytes, String.class, observer));
    }

    private static final class InlineExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
