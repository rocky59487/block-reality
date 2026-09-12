/* ABI5 typed initial-stress analysis, SI units. Include bsi_engine.h.
 * No wire verb/capability is implied. This is an elastic Theory-II result,
 * not a co-rotational, plastic, damage or nonlinear fracture transaction. */
#ifndef BSI_PDELTA_H
#define BSI_PDELTA_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif

enum bsi_pdelta_state {
  BSI_PD_CONVERGED=0, BSI_PD_UNSTABLE=1, BSI_PD_NOT_CONVERGED=2, BSI_PD_UNSUPPORTED=3
};
enum bsi_pdelta_available {
  BSI_PD_SOLVED=1, BSI_PD_ELASTIC=2, BSI_PD_CAPACITY=4,
  BSI_PD_CAPACITY_COMPLETE=8, BSI_PD_FIBRES=16
};
enum bsi_pdelta_capacity_face {
  BSI_PD_FACE_NONE=0, BSI_PD_FACE_AXIAL_BENDING=1, BSI_PD_FACE_SHEAR=2,
  BSI_PD_FACE_TORSION=3, BSI_PD_FACE_TENSION_CUT=4, BSI_PD_FACE_CRUSH=5
};
enum bsi_pdelta_weight_disposition {
  BSI_PD_WEIGHT_ATTACHED=0, BSI_PD_WEIGHT_GROUND=1, BSI_PD_WEIGHT_UNREPRESENTED=2,
  BSI_PD_WEIGHT_INACTIVE=3, BSI_PD_WEIGHT_ROPE=4, BSI_PD_WEIGHT_RETIRED=5,
  BSI_PD_WEIGHT_NONE=6
};
typedef struct bsi_analysis_token { bsi_id128 context; uint64_t sequence; } bsi_analysis_token;
typedef struct bsi_pdelta_options {
  uint32_t struct_size;
  bsi_world_stamp expected;
  double loadFactor, gravity[3], tolerance;
  uint32_t budgetDof, numThreads, maxIterations;
  uint8_t selfWeight, route;             /* 0/1; route 0=direct, 1=frozen iteration */
} bsi_pdelta_options;

/* Unavailable numeric fields are zero and MUST NOT be used as safe values.
 * Peak forces use the engine's station cut convention: N compression +,
 * then Vy,Vz,T,My,Mz. Generalized endAction arrays are nodal actions, not
 * material shear. These conventions intentionally differ from bsi_member_result. */
typedef struct bsi_pdelta_peak {
  double value, fraction, forces[6];
  int32_t side;                         /* -1 left, +1 right; 0 if unavailable */
  uint32_t surface;
} bsi_pdelta_peak;
typedef struct bsi_pdelta_island {
  uint32_t status, freeDof, iterations, factors, threadsUsed;
  uint8_t residualAvailable, indicativeShell;
  double residual, pivotRatio;
  const char* reason;                   /* borrowed from the owning snapshot */
} bsi_pdelta_island;
typedef struct bsi_pdelta_node {
  int32_t island;
  uint32_t available;
  double position[3], displacement[6], reaction[6]; /* world axes */
  uint8_t fixed[6];
} bsi_pdelta_node;
typedef struct bsi_pdelta_member {
  int32_t island, nodes[2], material, section; /* material/section index the declared vocabulary */
  uint32_t available, sourceFirst, sourceCount;
  double length, strengthScale;
  bsi_member_geometry geometry;
  double displacement[12], endAction[12];    /* LOCAL, including released coordinates */
  bsi_pdelta_peak elastic, capacity;
  uint8_t mode, fibre, capacityFace, active;
} bsi_pdelta_member;
typedef struct bsi_pdelta_shell {
  int32_t island, nodes[4], material;
  uint32_t available, sourceFirst, sourceCount; /* SOLVED only; no shell strength claim */
  double thickness, displacement[24], endAction[24]; /* WORLD nodal generalized actions */
  uint8_t active;
} bsi_pdelta_shell;
typedef struct bsi_pdelta_artifact {
  uint64_t artifact;
  uint32_t memberFirst, memberCount, shellFirst, shellCount;
} bsi_pdelta_artifact;
typedef struct bsi_pdelta_source {
  bsi_block block;
  uint64_t artifact;                    /* 0 for unowned support/nonstructural/retired storage */
  uint32_t weightDisposition;           /* bsi_pdelta_weight_disposition */
} bsi_pdelta_source;
typedef struct bsi_pdelta_view {
  uint32_t struct_size;
  bsi_world_stamp basis;
  bsi_id128 artifactNamespace;
  uint64_t generation;
  bsi_analysis_token token;
  double loadFactor, gravity[3];
  uint8_t selfWeight;
  bsi_physical_properties physical;      /* current physical material, including unrepresented */
  bsi_physical_properties weightGroups[6]; /* dispositions 0..5; retired is separate history */
  const bsi_pdelta_island* islands; uint32_t nIslands;
  const bsi_pdelta_node* nodes; uint32_t nNodes;
  const bsi_pdelta_member* members; uint32_t nMembers;
  const bsi_pdelta_shell* shells; uint32_t nShells;
  const bsi_pdelta_source* sources; uint32_t nSources;
  const uint32_t* sourceIndices; uint32_t nSourceIndices; /* member/shell ranges index sources */
  const bsi_pdelta_artifact* artifacts; uint32_t nArtifacts;
  const int32_t* artifactMembers; uint32_t nArtifactMembers;
  const int32_t* artifactShells; uint32_t nArtifactShells;
  const bsi_load* loads; uint32_t nLoads; /* exact request-local loads before loadFactor */
} bsi_pdelta_view;
typedef struct bsi_pdelta_query {
  uint32_t struct_size;
  bsi_analysis_token token;
  int32_t member;
  double fraction;
  int32_t side;
} bsi_pdelta_query;
typedef struct bsi_pdelta_station {
  uint32_t available;
  double forces[6], sigma[4], shear, elastic;
  /* sigma: corners (+cz,+cy), (+cz,-cy), (-cz,+cy), (-cz,-cy), tension +.
   * These are corner fibres, not bsi_station's four face centres. */
  uint8_t mode, fibre;
} bsi_pdelta_station;

/* Prefix: options through route; query through side. Longer tails ignored.
 * solve publishes only on success, including explicitly unsolved islands.
 * View and nested pointers live until the next successful pdelta_solve or close;
 * other operations never free them. Readable history is not current authority.
 * station requires the latest token AND the current identity handle/generation;
 * edits, redeclarations (even equal stamps), foreign sessions and later successful
 * analyses refuse old tokens. Failures preserve the view, token and caller output.
 * No exceptions cross either C entry point. */
#endif
