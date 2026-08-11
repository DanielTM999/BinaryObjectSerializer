package dtm.serialization;

import dtm.serialization.enums.ObjectType;

/**
 * Immutable snapshot of the decoder position when a descriptor event occurred.
 * All offsets and byte counts are relative to the payload, excluding the
 * protocol header.
 */
public record DescriptorEvent(
        String descriptorName,
        ObjectType descriptorType,
        long descriptorOffset,
        long bytesProcessed,
        long totalBytes,
        double percentage
) {
}
