package com.uber.rss.execution;

import com.uber.m3.tally.Counter;
import com.uber.m3.tally.Gauge;
import com.uber.rss.common.AppShufflePartitionId;
import com.uber.rss.common.FilePathAndLength;
import com.uber.rss.metrics.M3Stats;
import com.uber.rss.storage.ShuffleOutputStream;
import com.uber.rss.storage.ShuffleStorage;
import com.uber.rss.util.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/***
 * This class wraps logic to write for a single shuffle output file.
 */
public class ShufflePartitionWriter {
    private static final Logger logger =
            LoggerFactory.getLogger(ShufflePartitionWriter.class);
    
    private static final AtomicInteger numConcurrentWriteFilesAtomicInteger = new AtomicInteger();
    private static final Gauge numConcurrentWriteFiles = M3Stats.getDefaultScope().gauge("numConcurrentWriteFiles");
    private static final Counter numWriteFileBytes = M3Stats.getDefaultScope().counter("numWriteFileBytes");

    // TODO optimize how to use timer, M3 timer causes performance issue, need to figure out another way
    // private static final Timer flushLatency = M3Stats.getDefaultScope().timer("flushLatency");
    // private static final Timer fsyncLatency = M3Stats.getDefaultScope().timer("fsyncLatency");
    
    private final AppShufflePartitionId shufflePartitionId;
    private final String filePathBase;
    private final int fileStartIndex;
    private final String compressionCodec;
    private final ShuffleStorage storage;
    private final boolean fsync;
    
    private final ShuffleOutputStream[] outputStreams;
    private boolean closed = true;

    // dirty means having unflushed data
    private boolean isDirty = false;

    private final ConcurrentHashMap<String, Long> streamPersistedBytesSnapshots = new ConcurrentHashMap<>();

    public ShufflePartitionWriter(
            AppShufflePartitionId shufflePartitionId,
            String filePathBase,
            int fileStartIndex,
            String compressionCodec,
            ShuffleStorage storage,
            boolean fsync,
            int numSplits) {
        this.shufflePartitionId = shufflePartitionId;
        this.filePathBase = filePathBase;
        this.fileStartIndex = fileStartIndex;
        this.compressionCodec = compressionCodec;
        this.storage = storage;
        this.fsync = fsync;
        this.outputStreams = new ShuffleOutputStream[numSplits];
    }

    public AppShufflePartitionId getShufflePartitionId() {
        return shufflePartitionId;
    }

    public String getFilePathBase() {
        return filePathBase;
    }

    /***
     * Writes data to storage. This method will release the ByteBuf object in the argument.
     * @param taskAttemptId task attempt id, used to route the data to proper split.
     * @param bytes
     * @return
     */
    public synchronized int writeData(long taskAttemptId, ByteBuf bytes) {
        if (bytes == null) {
            return 0;
        }
        
        try {
            if (closed) {
                open();
            }

            int outputStreamIndex = (int)(taskAttemptId % outputStreams.length);
            ShuffleOutputStream outputStream = outputStreams[outputStreamIndex];

            int writtenBytes = bytes.readableBytes();
            byte[] byteArray = ByteBufUtils.readBytes(bytes);

            isDirty = true;
            outputStream.write(byteArray);

            numWriteFileBytes.inc(writtenBytes);
            return writtenBytes;
        } finally {
            bytes.release();
        }
    }
    
    public synchronized void flush() {
        if (!isDirty) {
            return;
        }

        if (closed) {
            open();
        }
        
        for (ShuffleOutputStream shuffleOutputStream: outputStreams) {
            logger.debug("Flushing shuffle file: " + shuffleOutputStream + ", fsync: " + fsync);
            shuffleOutputStream.flush();

            streamPersistedBytesSnapshots.put(shuffleOutputStream.getLocation(), shuffleOutputStream.getWrittenBytes());

            if (fsync) {
                shuffleOutputStream.fsync();
            }
        }

        isDirty = false;
    }

    public synchronized void close() {
        if (!closed) {
            logger.info(String.format("Closing stream file: %s", filePathBase));

            flush();

            for (ShuffleOutputStream shuffleOutputStream: outputStreams) {
                logger.debug(String.format("Closing shuffle file: %s", shuffleOutputStream));
                shuffleOutputStream.close();
                streamPersistedBytesSnapshots.put(shuffleOutputStream.getLocation(), shuffleOutputStream.getWrittenBytes());
            }
            closed = true;
            int numConcurrentWriteFilesValue = numConcurrentWriteFilesAtomicInteger.addAndGet(-outputStreams.length);
            numConcurrentWriteFiles.update(numConcurrentWriteFilesValue);

            isDirty = false;
        } else {
            logger.warn(String.format("Shuffle file already closed: %s, do not need to close it again", filePathBase));
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized long getPersistedBytes() {
        long result = 0;
        for (Long value: streamPersistedBytesSnapshots.values()) {
            result += value;
        }
        return result;
    }

    /**
     * Get persisted bytes for each stream and return a snapshot of last flush
     * @return list of files and their length
     */
    public List<FilePathAndLength> getPersistedBytesSnapshot() {
        List<FilePathAndLength> result = new ArrayList<>();
        for (ConcurrentHashMap.Entry<String, Long> entry: streamPersistedBytesSnapshots.entrySet()) {
            result.add(new FilePathAndLength(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /***
     * Get all file locations.
     * @return
     */
    public List<String> getFileLocations() {
        List<String> result = new ArrayList<>();
        for (ShuffleOutputStream entry: outputStreams) {
            result.add(entry.getLocation());
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShufflePartitionWriter that = (ShufflePartitionWriter) o;
        return fileStartIndex == that.fileStartIndex &&
            Objects.equals(shufflePartitionId, that.shufflePartitionId) &&
            Objects.equals(filePathBase, that.filePathBase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shufflePartitionId, filePathBase, fileStartIndex);
    }

    @Override
    public String toString() {
        return "ShufflePartitionWriter{" +
                "shufflePartitionId=" + shufflePartitionId +
                ", filePathBase='" + filePathBase + '\'' +
                ", fileStartIndex='" + fileStartIndex + '\'' +
                ", closed=" + closed +
                '}';
    }
    
    private void open() {
        String parentPath = Paths.get(filePathBase).getParent().toString();
        storage.createDirectories(parentPath);
        for (int i = 0; i < outputStreams.length; i++) {
            int fileIndex = i + fileStartIndex;
            String actualFile = filePathBase + "." + fileIndex;
            logger.info("Opening shuffle file: " + actualFile);
            outputStreams[i] = storage.createWriterStream(actualFile, compressionCodec);
        }
        closed = false;
        int numConcurrentFilesValue = numConcurrentWriteFilesAtomicInteger.addAndGet(outputStreams.length);
        numConcurrentWriteFiles.update(numConcurrentFilesValue);
    }
}
