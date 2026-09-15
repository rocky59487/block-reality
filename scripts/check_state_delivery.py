#!/usr/bin/env python3
"""Require each frozen source/order/dimension mutation to compile and fail its named assertion."""
from check_game_runtime import run_mutations

FILE = "mod/core/src/main/java/com/blockreality/core/AnalysisDeliveryClock.java"
TEST = "com.blockreality.core.AnalysisDeliveryClockTest."
ARMS = {
    "SOURCE_IGNORED": (FILE, "mod", ":core", "if (!sameSource && !bootstrap) return false;", "if (false) return false;",
                       TEST + "rejectsAnotherSourceUntilBootstrap"),
    "SEQUENCE_IGNORED": (FILE, "mod", ":core", "sequence <= this.sequence || ", "",
                         TEST + "rejectsLateAndDuplicateUpdates"),
    "DIMENSION_IGNORED": (FILE, "mod", ":core", "if (!activeDimension.equals(dimension)) return false;", "if (false) return false;",
                          TEST + "rejectsOtherDimensionsWithoutChangingState"),
}

if __name__ == "__main__":
    run_mutations(ARMS, __doc__)
