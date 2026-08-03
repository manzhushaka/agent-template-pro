package com.manzhushaka.agent.infrastructure.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.manzhushaka.agent.runtime.store.GraphCheckpointRecord;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** MySQL/in-memory durable truth with an optional Redis acceleration saver. */
public final class DurableGraphCheckpointSaver implements BaseCheckpointSaver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DurableGraphCheckpointSaver.class);

    private final RuntimeStore store;
    private final BaseCheckpointSaver acceleration;

    public DurableGraphCheckpointSaver(RuntimeStore store, BaseCheckpointSaver acceleration) {
        this.store = store;
        this.acceleration = acceleration;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        if (acceleration != null) {
            try {
                Collection<Checkpoint> accelerated = acceleration.list(config);
                if (!accelerated.isEmpty()) {
                    return List.copyOf(accelerated);
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Redis graph checkpoint read failed; falling back to durable RuntimeStore", exception);
            }
        }
        return records(config).stream().map(this::checkpoint).toList();
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        if (acceleration != null) {
            try {
                Optional<Checkpoint> accelerated = acceleration.get(config);
                if (accelerated.isPresent()) {
                    return accelerated;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Redis graph checkpoint read failed; falling back to durable RuntimeStore", exception);
            }
        }
        return records(config).stream()
                .filter(record -> config.checkPointId().isEmpty()
                        || config.checkPointId().orElseThrow().equals(record.checkpointId()))
                .findFirst()
                .map(this::checkpoint);
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = threadId(config);
        store.saveGraphCheckpoint(new GraphCheckpointRecord(
                threadId, checkpoint.getId(), checkpoint.getState(), checkpoint.getNodeId(),
                checkpoint.getNextNodeId(), Instant.now()
        ));
        RunnableConfig updated = RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
        if (acceleration != null) {
            try {
                acceleration.put(config, checkpoint);
            } catch (Exception exception) {
                LOGGER.warn("Redis graph checkpoint write failed; durable RuntimeStore write is retained", exception);
            }
        }
        return updated;
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String threadId = threadId(config);
        Collection<Checkpoint> checkpoints = records(config).stream().map(this::checkpoint).toList();
        store.deleteGraphCheckpoints(threadId);
        if (acceleration != null) {
            try {
                acceleration.release(config);
            } catch (Exception exception) {
                LOGGER.warn("Redis graph checkpoint release failed after durable release", exception);
            }
        }
        return new Tag(threadId, checkpoints);
    }

    private List<GraphCheckpointRecord> records(RunnableConfig config) {
        return store.graphCheckpoints(threadId(config));
    }

    private String threadId(RunnableConfig config) {
        return config.threadId().orElse(THREAD_ID_DEFAULT);
    }

    private Checkpoint checkpoint(GraphCheckpointRecord record) {
        return Checkpoint.builder()
                .id(record.checkpointId())
                .state(record.state())
                .nodeId(record.nodeId())
                .nextNodeId(record.nextNodeId())
                .build();
    }
}
