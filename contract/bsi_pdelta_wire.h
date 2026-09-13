/* Pointer-free binary records used by the shared P-Delta wire. */
#ifndef BSI_PDELTA_WIRE_H
#define BSI_PDELTA_WIRE_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif
typedef struct bsi_pdelta_options_record {
  double loadFactor, gravity[3], tolerance;
  uint32_t budgetDof, numThreads, maxIterations;
  uint8_t selfWeight, route, reserved[2];
} bsi_pdelta_options_record;
typedef struct bsi_pdelta_sample_record {
  int32_t member, side;
  double fraction;
} bsi_pdelta_sample_record;
typedef struct bsi_pdelta_island_record {
  uint32_t status, freeDof, iterations, factors, threadsUsed;
  uint8_t residualAvailable, indicativeShell;
  uint16_t reserved;
  double residual, pivotRatio;
  uint32_t reasonFirst, reasonCount; /* UTF-8 bytes in pdeltaText, without terminator */
} bsi_pdelta_island_record;
/* Other pointer-free records use the native field offsets with all padding
 * serialized as zero: node136, member560, shell440, source56, artifact24,
 * peak72, station112, fractureDecision104, fractureLoad72. LE/SI/f64. */
#endif
