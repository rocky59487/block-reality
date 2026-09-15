package com.blockreality.core.render;

import com.blockreality.api.BucklingState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared HUD/command wording. Every warning is supplied by the engine, never derived from a factor. */
public final class BucklingReadout {
    private BucklingReadout() { }
    public enum Tone { CRITICAL, UNEVALUATED, DETAIL }
    public record Line(String key, String fallback, List<String> arguments, Tone tone) {
        public Line { arguments = List.copyOf(arguments); }
    }

    public static List<Line> lines(BucklingState state, double worldFactor, boolean critical) {
        List<Line> lines = new ArrayList<>(2);
        if (critical) lines.add(new Line("br.buckling.local_critical",
                "BUCKLING: linear onset reached in a structure", List.of(), Tone.CRITICAL));
        if (state.hasFactor()) {
            lines.add(new Line("br.buckling.world_factor", "World buckling λ_cr %s (linear onset)",
                    List.of(String.format(Locale.ROOT, "%.3f", worldFactor)), Tone.DETAIL));
        } else {
            String text = switch (state) {
                case NO_POSITIVE_EIGENVALUE -> "No positive buckling eigenvalue for this load";
                case NOT_ELIGIBLE -> "World buckling incomplete: an ineligible structure";
                case NOT_ELIGIBLE_SCALE -> "World buckling incomplete: engine DOF budget";
                case SOLVER_FAILED -> "World buckling computation failed";
                case DISABLED_BY_REQUEST -> "Buckling not requested";
                case DISABLED_BY_SCALE -> "Buckling not evaluated (structure size)";
                default -> "Buckling: the reply did not say";
            };
            lines.add(new Line(state.translationKey(), text, List.of(), Tone.UNEVALUATED));
        }
        return List.copyOf(lines);
    }
}
