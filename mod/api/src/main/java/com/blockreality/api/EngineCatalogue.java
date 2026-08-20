package com.blockreality.api;

import java.util.List;

/**
 * What the engine says it can do, captured once at handshake.
 *
 * <p>The protocol number is checked <strong>fail-closed</strong>: a mismatch refuses the
 * binding outright rather than proceeding and hoping. That rule is inherited verbatim
 * from PFSF-CORE's three-stage ABI lock, which existed because the alternative — a
 * silent version skew between a Java manifest and a native contract — cost that project
 * real debugging time.
 */
public record EngineCatalogue(String engine, int protocol, List<String> materials,
                              List<String> sections, List<String> plates, int shmLayout) {

    /** The protocol version this build of the mod speaks. */
    public static final int SUPPORTED_PROTOCOL = 1;

    /** The shared-memory wire layout this build can decode. 0 means "JSON only". */
    public static final int SUPPORTED_SHM_LAYOUT = 1;

    public EngineCatalogue {
        materials = List.copyOf(materials);
        sections = List.copyOf(sections);
        plates = List.copyOf(plates);
    }

    /** Pre-shm signature, kept for callers and fixtures that predate the capability. */
    public EngineCatalogue(String engine, int protocol, List<String> materials,
                           List<String> sections, List<String> plates) {
        this(engine, protocol, materials, sections, plates, 0);
    }

    /**
     * Whether both ends speak the same shared-memory layout. A mismatch is not an
     * error: the JSON transport is the contract, shm is an optimisation, and an
     * engine advertising a layout this build does not know simply stays on JSON.
     */
    public boolean supportsShm() { return shmLayout == SUPPORTED_SHM_LAYOUT; }

    public boolean isCompatible() { return protocol == SUPPORTED_PROTOCOL; }

    public boolean hasMaterial(String id) { return materials.contains(id); }

    public boolean hasSection(String id) { return sections.contains(id); }

    /** A plate token names a shell facet, not a beam section; the two never overlap. */
    public boolean hasPlate(String id) { return plates.contains(id); }

    /** Either kind of token — what a block is allowed to declare. */
    public boolean hasElementToken(String id) { return hasSection(id) || hasPlate(id); }
}
