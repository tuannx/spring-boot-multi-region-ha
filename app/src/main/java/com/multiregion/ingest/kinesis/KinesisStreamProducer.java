package com.multiregion.ingest.kinesis;

import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.port.StreamProducerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;

import java.nio.charset.StandardCharsets;

/** Producer adapter: MTG edge -> Kinesis stream (PutRecord). */
public class KinesisStreamProducer implements StreamProducerPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KinesisStreamProducer.class);

    private final KinesisClient client;
    private final String streamName;

    public KinesisStreamProducer(KinesisClient client, String streamName) {
        this.client = client;
        this.streamName = streamName;
    }

    @Override
    public void put(MtgMessage message) {
        String json = KinesisStreamConsumer.encode(message);
        PutRecordResponse response = client.putRecord(PutRecordRequest.builder()
                .streamName(streamName)
                .partitionKey(message.partitionKey())
                .data(SdkBytes.fromByteArray(json.getBytes(StandardCharsets.UTF_8)))
                .build());
        log.info("Kinesis put: stream={} shard={} sequence={} messageId={}",
                streamName, response.shardId(), response.sequenceNumber(), message.messageId());
    }

    @Override
    public void close() {
        client.close();
    }
}
