package com.blockreality.core.finite;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The full stamp a finite result, checkpoint or history commit refers to (design §3.1).
 *
 * <p>{@code worldRevision} counts visible structural/source changes and agrees with the
 * identified world stamp; {@code historySequence} orders finite history commits (solves,
 * arcs, material commits) within and across revisions by the host's persistent order.
 * Neither is interchangeable with the CT metadata order or with any native token.
 *
 * <p>{@code sessionGeneration} is the in-memory revocation domain: it survives in a live
 * {@code FiniteResult} but is never written to disk, because a native token minted by one
 * session can never authorize work in another.
 */
public record FiniteBasis(FiniteDomainId domain, long worldRevision, long historySequence,
                          String sourceHash, String vocabHash, String ownersHash, long sessionGeneration) {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public FiniteBasis {
        Objects.requireNonNull(domain);
        hash(sourceHash); hash(vocabHash); hash(ownersHash);
        if (worldRevision < 0 || historySequence < 0 || sessionGeneration < 0) throw invalid();
    }

    static void hash(String value) {
        if (value == null || !SHA.matcher(value).matches()) throw invalid();
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite basis"); }
}
