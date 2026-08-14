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
public record EngineCatalogue(String engine, int protocol, List<String> materials, List<String> sections) {

    /** The protocol version this build of the mod speaks. */
    public static final int SUPPORTED_PROTOCOL = 1;

    public EngineCatalogue {
        materials = List.copyOf(materials);
        sections = List.copyOf(sections);
    }

    public boolean isCompatible() { return protocol == SUPPORTED_PROTOCOL; }

    public boolean hasMaterial(String id) { return materials.contains(id); }

    public boolean hasSection(String id) { return sections.contains(id); }
}
