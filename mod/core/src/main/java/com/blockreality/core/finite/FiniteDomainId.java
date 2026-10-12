package com.blockreality.core.finite;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The durable identity of one finite world authority (design §3).
 *
 * <p>One dimension owns exactly one finite domain. {@code journalDomain} is the construction
 * journal that issued the domain; {@code bsiDomain} is the persisted BSI world stamp domain
 * declared to the engine; {@code artifactNamespace} scopes the native owner ids minted for
 * this world. None may be zero: a zero identity on the wire is the engine's "no identity"
 * sentinel, not a world.
 */
public record FiniteDomainId(UUID journalDomain, String dimension, UUID bsiDomain, UUID artifactNamespace) {

    static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public FiniteDomainId {
        nonzero(journalDomain);
        dimension(dimension);
        nonzero(bsiDomain);
        nonzero(artifactNamespace);
    }

    static void dimension(String value) {
        if (value == null || value.length() > 256 || !DIMENSION.matcher(value).matches()) throw invalid();
    }

    static void nonzero(UUID id) {
        if (id == null || id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) throw invalid();
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite domain identity"); }

    static void requireFinite(boolean ok) { if (!ok) throw invalid(); }
}
