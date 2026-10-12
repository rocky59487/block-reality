package com.blockreality.core.finite;

import com.blockreality.core.bsi.BsiFracture;
import com.blockreality.core.bsi.BsiResponse;
import com.blockreality.core.bsi.BsiVocabulary;
import com.blockreality.core.engine.InProcessEngine;

/**
 * The narrow engine lane {@link FiniteSession} drives. The production implementation adapts
 * {@link InProcessEngine}; tests script the same four operations, so the ordering, refusal and
 * identity logic is verified against a native-shaped peer without a native library.
 */
public interface FiniteLane {

    /** One serialised finite request; null when the engine is off. */
    BsiResponse request(String headerJson, byte[] payload);

    /** Install the validated vocabulary. */
    boolean adoptVocabulary(BsiVocabulary vocabulary);

    /** The identified world declaration over the same wire as the fracture flow. */
    boolean declareFiniteWorld(BsiFracture.World world);

    /** The engine handshake completed and the lane is usable. */
    boolean ready();

    /** The production lane over a real {@link InProcessEngine}. */
    static FiniteLane inProcess(InProcessEngine engine) {
        return new FiniteLane() {
            @Override public BsiResponse request(String headerJson, byte[] payload) {
                return engine.finiteRequest(headerJson, payload);
            }
            @Override public boolean adoptVocabulary(BsiVocabulary vocabulary) {
                return engine.adoptVocabulary(vocabulary);
            }
            @Override public boolean declareFiniteWorld(BsiFracture.World world) {
                return engine.declareFiniteWorld(world);
            }
            @Override public boolean ready() {
                return engine.status() == InProcessEngine.Status.READY;
            }
        };
    }
}
