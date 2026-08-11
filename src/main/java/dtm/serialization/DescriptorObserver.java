package dtm.serialization;

import java.util.concurrent.ExecutorService;

/**
 * Receives asynchronous notifications while a binary payload is decoded.
 */
public interface DescriptorObserver {

    default void onDescriptorStarted(DescriptorEvent event) {
    }

    default void onDescriptorFinished(DescriptorEvent event) {
    }

    default void onProgress(DescriptorEvent event) {
    }

    /**
     * Executor used to deliver notifications. Returning {@code null} selects
     * {@link java.util.concurrent.ForkJoinPool#commonPool()}.
     */
    default ExecutorService getExecutorService() {
        return null;
    }

    /**
     * Whether a user-supplied executor should be shut down after all callbacks
     * from this decode operation have completed. The common pool is never shut down.
     */
    default boolean shouldAutoCloseExecutorService() {
        return true;
    }
}
