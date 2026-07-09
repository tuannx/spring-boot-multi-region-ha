package com.multiregion.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes between write (primary) and read (replica) data sources
 * based on the @Transactional(readOnly = true) hint.
 * <p>
 * Writes → writer pool (connected to writer instance via AWS Wrapper)
 * Reads  → reader pool (connected to reader instance via AWS Wrapper)
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger logger = LoggerFactory.getLogger(RoutingDataSource.class);

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    public static void routeTo(String target) {
        contextHolder.set(target);
    }

    public static void clearRoute() {
        contextHolder.remove();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        // First check ThreadLocal (explicit routing)
        String explicit = contextHolder.get();
        if (explicit != null) {
            System.err.println("ROUTING_DEBUG: explicit=" + explicit);
            return explicit;
        }
        // Fallback to @Transactional(readOnly=true) hint
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String key = readOnly ? "reader" : "writer";
        System.err.println("ROUTING_DEBUG: readOnly=" + readOnly + " key=" + key);
        return key;
    }
}
