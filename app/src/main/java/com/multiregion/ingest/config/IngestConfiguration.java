package com.multiregion.ingest.config;

import com.multiregion.ingest.application.IngestRoutingService;
import com.multiregion.ingest.domain.MessageRouter;
import com.multiregion.ingest.kinesis.KinesisStreamConsumer;
import com.multiregion.ingest.kinesis.KinesisStreamProducer;
import com.multiregion.ingest.logging.LoggingQueuePublisher;
import com.multiregion.ingest.port.QueuePublisherPort;
import com.multiregion.ingest.port.StreamConsumerPort;
import com.multiregion.ingest.port.StreamProducerPort;
import com.multiregion.ingest.sqs.SqsQueuePublisher;
import com.multiregion.platform.config.MultiRegionConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * Wiring for the optional Kinesis ingest layer. Everything here is gated by
 * {@code ingest.enabled=true} plus {@code service.role} so the default
 * combined deployment (no Kinesis) creates none of these beans. The Ingest
 * Service and the Message Processor Service are the same image; the role
 * decides which half starts.
 *
 * Role contract (service.role):
 * combined (default) = ingest + processor in one process;
 * ingest = Kinesis -> SQS routing only, no processor listeners;
 * processor = queue listeners only, no Kinesis consumer.
 */
@Configuration
public class IngestConfiguration {

    static final String INGEST_ENABLED =
            "'${ingest.enabled:false}' == 'true' and '${service.role:combined}' != 'processor'";
    static final String KINESIS_FRONT =
            "'${ingest.enabled:false}' == 'true' and '${ingest.mode:direct}' == 'kinesis'"
                    + " and '${service.role:combined}' != 'processor'";

    @Bean
    @ConditionalOnExpression(INGEST_ENABLED)
    public MessageRouter messageRouter(IngestProperties properties) {
        return new MessageRouter(properties.routing().defaultQueue(), properties.routing().rules());
    }

    @Bean
    @ConditionalOnExpression(INGEST_ENABLED)
    public IngestRoutingService ingestRoutingService(MessageRouter router, QueuePublisherPort publisher) {
        return new IngestRoutingService(router, publisher);
    }

    @Bean
    @ConditionalOnExpression(INGEST_ENABLED + " and '${ingest.destination.type:logging}' == 'sqs'")
    public QueuePublisherPort sqsQueuePublisher(IngestProperties properties) {
        String endpoint = firstNonBlank(properties.destination().sqs().endpointOverride(),
                properties.endpointOverride());
        String region = firstNonBlank(properties.streamRegion(), "us-east-1");
        SqsClient client = buildSqsClient(region, endpoint);
        return new SqsQueuePublisher(client, properties.destination().sqs());
    }

    @Bean
    @ConditionalOnExpression(INGEST_ENABLED)
    @ConditionalOnMissingBean(QueuePublisherPort.class)
    public QueuePublisherPort loggingQueuePublisher() {
        return new LoggingQueuePublisher();
    }

    @Bean
    @ConditionalOnExpression(KINESIS_FRONT)
    public KinesisClient kinesisClient(IngestProperties properties, MultiRegionConfig config) {
        String region = firstNonBlank(properties.streamRegion(), config.awsRegion());
        return buildKinesisClient(region, properties.endpointOverride());
    }

    @Bean
    @ConditionalOnExpression(KINESIS_FRONT)
    public StreamConsumerPort kinesisStreamConsumer(
            KinesisClient kinesisClient, IngestRoutingService routingService, IngestProperties properties) {
        ShardIteratorType position = "LATEST".equalsIgnoreCase(properties.position())
                ? ShardIteratorType.LATEST : ShardIteratorType.TRIM_HORIZON;
        KinesisStreamConsumer consumer = new KinesisStreamConsumer(kinesisClient, routingService,
                properties.streamName(), position, properties.shardPollIntervalMs(),
                properties.maxRecordsPerPoll());
        consumer.start();
        return consumer;
    }

    @Bean
    @ConditionalOnExpression(KINESIS_FRONT)
    public StreamProducerPort kinesisStreamProducer(KinesisClient kinesisClient, IngestProperties properties) {
        return new KinesisStreamProducer(kinesisClient, properties.streamName());
    }

    static KinesisClient buildKinesisClient(String region, String endpointOverride) {
        var builder = KinesisClient.builder().region(Region.of(region));
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    static SqsClient buildSqsClient(String region, String endpointOverride) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
