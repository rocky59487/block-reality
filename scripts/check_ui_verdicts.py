#!/usr/bin/env python3
"""Execute the two frozen verdict-presentation mutations with isolated compiled classes."""
from check_game_runtime import run_mutations

FILE = "mod/core/src/main/java/com/blockreality/core/render/BucklingReadout.java"
ARMS = {
    "HIDE_LOCAL": (FILE, "mod", ":core", "if (critical) lines.add", "if (critical && state.hasFactor()) lines.add",
                   "com.blockreality.core.render.BucklingReadoutTest.refusedWorldKeepsTheSuppliedLocalCriticalWarning"),
    "RECOMPUTE_FLAG": (FILE, "mod", ":core", "if (critical) lines.add", "if (worldFactor <= 1) lines.add",
                       "com.blockreality.core.render.BucklingReadoutTest.neverDerivesCriticalFromTheDisplayedFactor"),
}

if __name__ == "__main__":
    run_mutations(ARMS, __doc__)
