package com.blockreality.core.finite;

/** A durable pointer into {@code objects/}: the SHA-256 of the complete envelope and its byte length. */
public record FiniteBlobRef(String sha256, long length) {

    public FiniteBlobRef {
        FiniteBasis.hash(sha256);
        if (length < FiniteCodec.ENVELOPE_HEADER_BYTES
                || length > FiniteStore.MAX_OBJECT_BYTES + FiniteCodec.ENVELOPE_HEADER_BYTES) throw invalid();
    }

    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid finite object reference"); }
}
