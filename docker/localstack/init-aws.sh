#!/bin/bash
# LocalStack init (profile kinesis): create the MTG ingest stream and the
# SQS queues the Ingest Service routes to. Names must match
# ingest.destination.sqs.queueNamePattern "%s-%s" (logical-region).
set -e
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
ENDPOINT=http://localhost:4566

awslocal --endpoint-url=$ENDPOINT kinesis create-stream \
  --stream-name mtg-ingest --shard-count 1 --region us-east-1 || true

for q in orders-us-east-1 orders-eu-west-1 billing-us-east-1 billing-eu-west-1; do
  awslocal --endpoint-url=$ENDPOINT sqs create-queue --queue-name "$q" --region us-east-1 || true
done

echo "Kinesis stream + SQS queues ready."
awslocal --endpoint-url=$ENDPOINT kinesis list-streams --region us-east-1
awslocal --endpoint-url=$ENDPOINT sqs list-queues --region us-east-1
