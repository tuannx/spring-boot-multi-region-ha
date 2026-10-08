package com.multiregion.ingest.sqs;

import com.multiregion.ingest.config.IngestProperties;
import com.multiregion.ingest.domain.MtgMessage;
import com.multiregion.ingest.domain.RouteDecision;
import com.multiregion.ingest.port.QueuePublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQS destination adapter: the queue layer the Message Processor Service
 * consumes from. Queue URL is resolved once per physical queue and cached;
 * messageId rides as an attribute and (on FIFO queues) as the
 * deduplication id, so processor-side idempotency keys survive the Kinesis
 * hop unchanged.
 */
public class SqsQueuePublisher implements QueuePublisherPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqsQueuePublisher.class);

    private final SqsClient client;
    private final IngestProperties.Sqs sqs;
    private final Map<String, String> queueUrlByName = new ConcurrentHashMap<>();

    public SqsQueuePublisher(SqsClient client, IngestProperties.Sqs sqs) {
        this.client = client;
        this.sqs = sqs;
    }

    @Override
    public void publish(MtgMessage message, RouteDecision decision) {
        String queueName = sqs.queueName(decision.logicalQueue(), decision.targetRegion());
        String queueUrl = queueUrlByName.computeIfAbsent(queueName, this::resolveQueueUrl);
        SendMessageRequest.Builder request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(message.payload())
                .messageAttributes(Map.of(
                        "messageId", attr(message.messageId()),
                        "messageType", attr(message.messageType()),
                        "sourceRegion", attr(message.sourceRegion()),
                        "matchedRule", attr(decision.matchedRule())));
        if (sqs.fifo()) {
            request.messageGroupId(message.partitionKey())
                    .messageDeduplicationId(message.messageId());
        }
        client.sendMessage(request.build());
        log.info("Ingest publish (sqs): messageId={} queue={} queueUrl={}",
                message.messageId(), queueName, queueUrl);
    }

    private String resolveQueueUrl(String queueName) {
        return client.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
    }

    private static MessageAttributeValue attr(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    @Override
    public void close() {
        client.close();
    }
}
