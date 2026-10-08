package com.multiregion.ingest.kinesis;

import com.multiregion.ingest.application.IngestRoutingService;
import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.port.StreamConsumerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kinesis consumer adapter (Ingest Service inbound). Polls every open shard
 * with GetRecords and hands each record to {@link IngestRoutingService}.
 * Wire format is one flat JSON envelope per record:
 * {@code {"messageId":"...","messageType":"MTG_ORDER","sourceRegion":"us-east-1","payload":"..."}}.
 * The record partition key becomes the ordering/idempotency partition key.
 * The envelope codec is hand-rolled for these four flat string fields on
 * purpose: no JSON library version coupling in the adapter.
 * Checkpointing is in-memory (TRIM_HORIZON on restart) which is correct
 * for the local lab; a production deployment swaps in a KCL/DynamoDB
 * checkpoint behind this same port without touching the domain.
 */
public class KinesisStreamConsumer implements StreamConsumerPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KinesisStreamConsumer.class);

    private final KinesisClient client;
    private final IngestRoutingService routingService;
    private final String streamName;
    private final ShardIteratorType position;
    private final long pollIntervalMs;
    private final int maxRecordsPerPoll;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    public KinesisStreamConsumer(KinesisClient client,
                                 IngestRoutingService routingService,
                                 String streamName,
                                 ShardIteratorType position,
                                 long pollIntervalMs,
                                 int maxRecordsPerPoll) {
        this.client = client;
        this.routingService = routingService;
        this.streamName = streamName;
        this.position = position;
        this.pollIntervalMs = pollIntervalMs;
        this.maxRecordsPerPoll = maxRecordsPerPoll;
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofVirtual().name("kinesis-ingest-consumer").start(this::pollLoop);
        log.info("Kinesis ingest consumer started: stream={} position={}", streamName, position);
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        log.info("Kinesis ingest consumer stopped: stream={}", streamName);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        List<String> shardIds = client.describeStream(
                        DescribeStreamRequest.builder().streamName(streamName).build())
                .streamDescription().shards().stream().map(shard -> shard.shardId()).toList();
        Map<String, String> iteratorByShard = new HashMap<>();
        for (String shardId : shardIds) {
            iteratorByShard.put(shardId, newIterator(shardId));
        }
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                for (String shardId : List.copyOf(iteratorByShard.keySet())) {
                    String iterator = iteratorByShard.get(shardId);
                    if (iterator == null) {
                        continue;
                    }
                    GetRecordsResponse response = client.getRecords(GetRecordsRequest.builder()
                            .shardIterator(iterator).limit(maxRecordsPerPoll).build());
                    for (Record record : response.records()) {
                        routingService.onMessage(toMessage(record));
                    }
                    String next = response.nextShardIterator();
                    if (next == null) {
                        iteratorByShard.remove(shardId);
                        log.info("Kinesis shard closed: stream={} shard={}", streamName, shardId);
                    } else {
                        iteratorByShard.put(shardId, next);
                    }
                }
                Thread.sleep(pollIntervalMs);
            } catch (ProvisionedThroughputExceededException e) {
                backoff();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Kinesis poll failed, will retry: stream={} error={}", streamName, e.toString());
                backoff();
            }
        }
    }

    private void backoff() {
        try {
            Thread.sleep(Math.max(pollIntervalMs, 1000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String newIterator(String shardId) {
        return client.getShardIterator(GetShardIteratorRequest.builder()
                        .streamName(streamName).shardId(shardId).shardIteratorType(position).build())
                .shardIterator();
    }

    static MtgMessage toMessage(Record record) {
        String json = record.data().asString(StandardCharsets.UTF_8);
        Map<String, String> fields = FlatJson.parseObject(json);
        return new MtgMessage(
                required(fields, "messageId"),
                required(fields, "messageType"),
                required(fields, "sourceRegion"),
                record.partitionKey(),
                required(fields, "payload"),
                Instant.now());
    }

    static String encode(MtgMessage message) {
        return FlatJson.writeObject(Map.of(
                "messageId", message.messageId(),
                "messageType", message.messageType(),
                "sourceRegion", message.sourceRegion(),
                "payload", message.payload()));
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("MTG envelope missing field: " + name);
        }
        return value;
    }

    @Override
    public void close() {
        stop();
        client.close();
    }

    /** Minimal codec for one flat JSON object with string values only. */
    static final class FlatJson {

        private FlatJson() {
        }

        static String writeObject(Map<String, String> fields) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(escape(entry.getKey())).append("\":\"")
                        .append(escape(entry.getValue())).append('"');
            }
            return out.append('}').toString();
        }

        static Map<String, String> parseObject(String json) {
            Map<String, String> fields = new HashMap<>();
            int[] pos = {0};
            skipWhitespace(json, pos);
            expect(json, pos, '{');
            skipWhitespace(json, pos);
            if (peek(json, pos) == '}') {
                return fields;
            }
            while (true) {
                skipWhitespace(json, pos);
                String key = parseString(json, pos);
                skipWhitespace(json, pos);
                expect(json, pos, ':');
                skipWhitespace(json, pos);
                String value = parseString(json, pos);
                fields.put(key, value);
                skipWhitespace(json, pos);
                char next = peek(json, pos);
                if (next == ',') {
                    pos[0]++;
                } else if (next == '}') {
                    return fields;
                } else {
                    throw new IllegalArgumentException("Invalid MTG envelope JSON at offset " + pos[0]);
                }
            }
        }

        private static String parseString(String json, int[] pos) {
            expect(json, pos, '"');
            StringBuilder value = new StringBuilder();
            while (pos[0] < json.length()) {
                char c = json.charAt(pos[0]++);
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\' && pos[0] < json.length()) {
                    char escaped = json.charAt(pos[0]++);
                    switch (escaped) {
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> {
                            value.append((char) Integer.parseInt(
                                    json.substring(pos[0], pos[0] + 4), 16));
                            pos[0] += 4;
                        }
                        default -> value.append(escaped);
                    }
                } else {
                    value.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in MTG envelope");
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }

        private static void skipWhitespace(String json, int[] pos) {
            while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) {
                pos[0]++;
            }
        }

        private static char peek(String json, int[] pos) {
            if (pos[0] >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of MTG envelope");
            }
            return json.charAt(pos[0]);
        }

        private static void expect(String json, int[] pos, char expected) {
            if (peek(json, pos) != expected) {
                throw new IllegalArgumentException(
                        "Expected '" + expected + "' in MTG envelope at offset " + pos[0]);
            }
            pos[0]++;
        }
    }
}
