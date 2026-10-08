# Kinesis Ingest Layer (Deere Pattern)

Optional front layer for MTG messages, modeled on the pattern John Deere
uses in production: **Kinesis first for ingest, SQS behind it for work
distribution**. Kinesis gives a durable, ordered, replayable entry point;
SQS gives per-queue scaling, visibility timeouts and DLQs for processing.

```text
MTG producer ──PutRecord──▶ Kinesis stream (mtg-ingest, partitionKey = ordering key)
                                 │  GetRecords (poll, shard iterator)
                                 ▼
                        Ingest Service (separate deployable, service.role=ingest)
                        - validate MTG envelope (messageId/messageType/sourceRegion/payload)
                        - deterministic routing: messageType -> logical queue
                        - target region = sourceRegion (home-region semantics)
                                 │  SendMessage (messageId kept as attribute /
                                 │  FIFO deduplication id)
                                 ▼
                        SQS queues (orders-us-east-1, billing-eu-west-1, ...)
                                 │
                                 ▼
                        Message Processor Service (service.role=processor)
                        existing queue listeners + lease/takeover HA logic
```

The Ingest Service does **no business processing**. It only routes.
The Message Processor Service does **no stream handling**. It only consumes
queues. They meet at exactly one contract: the SQS queue name
(`ingest.destination.sqs.queueNamePattern`, default `%s-%s` =
logical-region) and the `messageId` attribute.

## Turning it on (config choice, not a code change)

The layer is **off by default**; existing deployments are untouched
(producers keep the legacy direct path to the processor queues).

| Setting | Default | Meaning |
|---|---|---|
| `ingest.enabled` | `false` | Master switch for the whole ingest layer |
| `ingest.mode` | `direct` | `direct` bypasses Kinesis; `kinesis` puts Kinesis in front of SQS |
| `ingest.streamName` | `mtg-ingest` | Kinesis stream name |
| `ingest.streamRegion` | app region | Stream region override |
| `ingest.endpointOverride` | _(empty)_ | LocalStack/Floci endpoint, e.g. `http://localstack:4566`; empty = real AWS |
| `ingest.destination.type` | `logging` | `logging` (no AWS) or `sqs` |
| `ingest.destination.sqs.queueNamePattern` | `%s-%s` | Physical queue = pattern(logical, region) |
| `ingest.destination.sqs.fifo` | `false` | FIFO queues: group = partitionKey, dedup = messageId |
| `ingest.routing.defaultQueue` | `orders` | Fallback queue for unknown messageType |
| `ingest.routing.rules` | MTG_ORDER→orders, MTG_PAYMENT→billing | Deterministic messageType routing table |
| `service.role` | `combined` | `combined` = both services in one process; `ingest` / `processor` split them |

Environment variables (Spring relaxed binding): `INGEST_ENABLED=true`,
`INGEST_MODE=kinesis`, `SERVICE_ROLE=ingest`, etc.

## Running the split locally

```bash
# Full stack + Kinesis layer (LocalStack provides Kinesis + SQS)
docker compose --profile kinesis up -d

# Ingest Service status (evidence: mode, stream, routing table)
curl -s http://localhost:8082/ingest/status | jq

# Put one MTG message on Kinesis; watch it land on SQS via the Ingest Service
curl -s -X POST http://localhost:8082/ingest/mtg \
  -H 'Content-Type: application/json' \
  -d '{"messageId":"m-1","messageType":"MTG_ORDER","sourceRegion":"us-east-1","partitionKey":"m-1","payload":"{\"orderId\":42}"}'

# Direct mode (no Kinesis hop) for comparison: same endpoint, mode=direct
# routes straight to the destination queue and returns the RouteDecision.
```

LocalStack init (`docker/localstack/init-aws.sh`) creates stream
`mtg-ingest` (1 shard) and queues `orders-us-east-1`, `orders-eu-west-1`,
`billing-us-east-1`, `billing-eu-west-1`. On AWS/Floci, create the same
names or adjust `queueNamePattern`.

## Failure semantics

- **Kinesis down / throttled**: consumer backs off and retries the same
  shard iterator; records are not lost (stream retention), and
  `ProvisionedThroughputExceededException` never drops a message.
- **SQS down**: publish failure propagates and the record is re-polled on
  the next loop after restart from `TRIM_HORIZON` (in-memory checkpoint;
  swap in KCL/DynamoDB checkpointing behind `StreamConsumerPort` for
  production exactly-once positioning).
- **Unknown messageType**: routed to `defaultQueue` with
  `matchedRule=default` in the log line, never silently dropped.
- **Role split**: `service.role=ingest` starts zero processor listeners
  (enforced in `QueueListenerConfiguration`/`QueueCoordinationScheduler`);
  `service.role=processor` starts zero Kinesis consumers (enforced in
  `IngestConfiguration`). Same image, independent scaling and blast radius.

## Code map

```text
app/src/main/java/com/multiregion/ingest/
  domain/      MtgMessage, RouteDecision, MessageRouter (pure, deterministic)
  port/        QueuePublisherPort, StreamConsumerPort, StreamProducerPort
  application/ IngestRoutingService (route -> publish, nothing else)
  kinesis/     KinesisStreamConsumer, KinesisStreamProducer (AWS SDK v2)
  sqs/         SqsQueuePublisher (AWS SDK v2)
  logging/     LoggingQueuePublisher (dev/test sink)
  config/      IngestProperties, ServiceRoleProperties, IngestConfiguration
  web/         IngestResource (POST /ingest/mtg, GET /ingest/status)
```

Hexagonal rules are enforced by `IngestArchitectureTest` (domain pure,
ports domain-only, application domain+ports only, core never depends on
adapters or on the processor's `queue` package).
