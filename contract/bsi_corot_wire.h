#ifndef BSI_COROT_WIRE_H
#define BSI_COROT_WIRE_H
/* Pointer-free diagnostic record. All other corot records use the named C
 * record's x64 little-endian layout; padding and reserved fields are zero. */
typedef struct bsi_corot_island_record {
  uint32_t status,freeDof,flags,iterations,linearIterations,acceptedSteps,rejectedSteps,backtracks,factors,historyChecks;
  double forceResidual,momentResidual,historyError,historyLoadFactor,storedEnergy,dissipatedEnergy;
  uint32_t reasonFirst,reasonCount;
} bsi_corot_island_record;
#endif
