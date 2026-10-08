package com.multiregion.ingest.port;

import com.multiregion.ingest.domain.MtgMessage;

/**
 * Producer-side port: put one MTG message onto the ingest stream. In the
 * Deere pattern this is what MTG edge producers call; the Ingest Service
 * itself only consumes. Provided so the lab can demo the full path
 * (producer -> Kinesis -> Ingest -> SQS -> Processor) from one endpoint.
 */
public interface StreamProducerPort {

    void put(MtgMessage message);
}
