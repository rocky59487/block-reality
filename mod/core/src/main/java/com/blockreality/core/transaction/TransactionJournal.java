package com.blockreality.core.transaction;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import static com.blockreality.core.transaction.ConstructionTransaction.*;

/** The decision store must resolve a possibly successful write by reading the actual durable record. */
public interface TransactionJournal extends AutoCloseable {
    UUID domain();
    Optional<Entry> read(UUID id) throws IOException;
    /** Complete storage barriers before resolving an I/O error that may have followed replacement. */
    Optional<Entry> verify(UUID id) throws IOException;
    Optional<UUID> pending() throws IOException;
    void create(Entry entry) throws IOException;
    Entry decide(UUID id, Phase phase, Reason reason) throws IOException;
    @Override void close() throws IOException;
}
