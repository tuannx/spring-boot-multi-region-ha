package com.multiregion.platform.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes reads to the regional reader and writes to the currently active
 * writer. A verified promotion changes only future connection acquisition;
 * transactions that already own a connection finish on that connection.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSource.class);

    public static final String WRITER = "writer";
    public static final String READER = "reader";
    public static final String PROMOTED_WRITER = "promoted-writer";

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    private final AtomicReference<String> activeWriter = new AtomicReference<>(WRITER);

    public static void routeTo(String target) {
        contextHolder.set(target);
    }

    public static void clearRoute() {
        contextHolder.remove();
    }

    public void activatePromotedWriter() {
        activeWriter.set(PROMOTED_WRITER);
        log.warn("Future write connections will be routed to the promoted writer pool");
    }

    public boolean isPromotedWriterActive() {
        return PROMOTED_WRITER.equals(activeWriter.get());
    }

    public void restoreInitialWriter() {
        activeWriter.set(WRITER);
        log.info("Future write connections will use the initial writer pool");
    }

    public boolean isInitialWriterActive() {
        return WRITER.equals(activeWriter.get());
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String explicit = contextHolder.get();
        if (explicit != null) {
            String resolved = resolveWriter(explicit);
            log.debug("Routing connection to explicit {} pool (resolved to {})", explicit, resolved);
            return resolved;
        }
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        String key = readOnly ? READER : activeWriter.get();
        log.debug("Routing connection using transaction readOnly={} to {} pool", readOnly, key);
        return key;
    }

    private String resolveWriter(String requested) {
        return WRITER.equals(requested) ? activeWriter.get() : requested;
    }
}
