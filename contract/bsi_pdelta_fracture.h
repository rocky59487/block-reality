/* ABI6 typed static whole-member fracture. Include bsi_engine.h. */
#ifndef BSI_PDELTA_FRACTURE_H
#define BSI_PDELTA_FRACTURE_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif

typedef struct bsi_pdelta_fracture_options {
  uint32_t struct_size;
  bsi_pdelta_options analysis;
  bsi_id128 request;
  bsi_analysis_token initialAnalysis; /* zero unless useInitial=1 */
  uint32_t budget;                    /* 1..4096 whole-member cuts */
  uint8_t tier, useInitial;           /* COMMIT only; useInitial is 0/1 */
} bsi_pdelta_fracture_options;

enum bsi_pdelta_fracture_end {
  BSI_PDF_WITHIN_CAPACITY=0, BSI_PDF_UNQUALIFIED=1,
  BSI_PDF_BUDGET=2, BSI_PDF_UNSTABLE_CUT_ROLLED_BACK=3
};
enum bsi_pdelta_fracture_qualification {
  BSI_PDF_INCOMPLETE_CAPACITY=1, BSI_PDF_SHELL_STRENGTH_UNAVAILABLE=2,
  BSI_PDF_UNREPRESENTED_MATERIAL=4
};
typedef struct bsi_pdelta_fracture_decision {
  uint32_t microStep;
  int32_t member;                     /* historical analysis index */
  uint32_t cellFirst, cellCount;      /* range in decisionCells */
  uint32_t islandFirst, islandCount;  /* same analysis diagnostics */
  bsi_pdelta_peak capacity;
  uint8_t face, reserved[7];
} bsi_pdelta_fracture_decision;
typedef struct bsi_pdelta_fracture_load {
  bsi_load input;                     /* unscaled, original order and duplicates */
  uint32_t cell, group;               /* physical.cells index and matching 0/1/2+ group */
} bsi_pdelta_fracture_load;
typedef struct bsi_pdelta_fracture_view {
  uint32_t struct_size;
  bsi_pdelta_fracture_options options;
  const bsi_fracture_view* physical;   /* the SAME candidate and finish token */
  uint32_t end, qualification, flags; /* flags bit0 reusedInitial; otherwise zero */
  int32_t pending;                    /* -1, or last decisions index; not an accepted break */
  const bsi_pdelta_fracture_decision* decisions; uint32_t nDecisions;
  const uint32_t* decisionCells; uint32_t nDecisionCells; /* physical.cells indices */
  const bsi_pdelta_island* islands; uint32_t nIslands;
  uint32_t rollbackIslandFirst, rollbackIslandCount;
  const char* rollbackReason;
  const bsi_pdelta_fracture_load* loads; uint32_t nLoads;
} bsi_pdelta_fracture_view;

/* Prefix through useInitial, nested analysis through route. New prepare catches
 * all exceptions. Failure preserves the previous candidate and caller output.
 * Same complete request returns the same pending candidate. A request id cannot
 * cross linear/P-Delta kinds or change its options, loads or initial token.
 * View lifetime equals bsi_fracture_view. Finish via the existing fracture_finish;
 * no separate commit authority. Whole-member static material only: no local
 * damage history, deformed release velocity or force handoff to rigid dynamics. */
#endif
