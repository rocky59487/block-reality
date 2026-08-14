package com.blockreality.api.spi;

/**
 * The one extension point third-party physics and effect mods get.
 *
 * <p>The division of labour is fixed (DEMO_V0 §2, D-014):
 *
 * <ul>
 *   <li>{@code CRUSHING} produces purely visual debris — no damage, no persistent
 *       entities, nothing recoverable, nothing written to the save. That is exactly the
 *       shape of work an external effect mod does well, so it is lent out.
 *   <li>{@code FRACTURE} produces a persistent rigid member with damage history, a
 *       lifecycle and a mass balance. That is gameplay, and it is not outsourced.
 * </ul>
 *
 * <p>Three properties are guaranteed by the caller and must be relied on:
 *
 * <ol>
 *   <li>It is called <em>after</em> the world has already changed.
 *   <li>Its return value is void, so it cannot influence any decision.
 *   <li>Exceptions are caught. A handler that throws is logged, disabled for the rest of
 *       the session, and replaced by the built-in effect. A third-party mod is untrusted
 *       input and must never be able to take the solver down with it.
 * </ol>
 */
@FunctionalInterface
public interface IFractureEffectHandler {

    /** Called on the server thread once the world change is complete. */
    void onCrushing(CrushingDescription description);

    /** Identifier used in logs and in the "handler disabled" message. */
    default String handlerId() { return getClass().getName(); }
}
