/* ABI4 additions. Source geometry and explicit host-owned kinematics, all SI. */
#ifndef BSI_MOTION_H
#define BSI_MOTION_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h, not bsi_motion.h directly"
#endif

typedef struct bsi_motion_surface {
  double friction, restitution, rollingResistance, spinningResistance;
} bsi_motion_surface;
typedef struct bsi_motion_source {
  uint64_t id;                     /* caller-owned positive signed-i64 body id */
  bsi_world_stamp birthWorld;
  bsi_id128 artifactNamespace, fractureRequest;
  uint32_t group, flags;           /* original fracture group>=2; flags must be 0 */
  uint32_t cellFirst, cellCount;    /* exact partition of declare.cells */
  bsi_physical_properties physical;
  bsi_motion_surface surface;
} bsi_motion_source;
typedef struct bsi_motion_box {
  uint64_t id;
  double size[3], position[3], orientation[4]; /* Hamilton wxyz, box-local->world */
  bsi_motion_surface surface;
} bsi_motion_box;
typedef struct bsi_motion_state {
  uint64_t id, revision;
  double time, position[3], orientation[4], linearMomentum[3], angularMomentum[3];
} bsi_motion_state;
typedef struct bsi_motion_force { uint64_t id; double force[3], torque[3]; } bsi_motion_force;
typedef struct bsi_motion_body_geometry {
  uint64_t id;
  bsi_physical_properties physical; /* reference source COM and body-frame tensor */
  double surfaceError;
  uint32_t pieceFirst, pieceCount;
} bsi_motion_body_geometry;
typedef struct bsi_motion_piece {
  uint64_t body, part;
  int32_t source[3]; uint32_t sourcePart;
  uint32_t vertexFirst, vertexCount, triangleFirst, triangleCount;
} bsi_motion_piece;
typedef struct bsi_motion_vertex { double xyz[3]; } bsi_motion_vertex;
typedef struct bsi_motion_triangle { uint32_t vertex[3]; } bsi_motion_triangle;

/* Binary wire report: floating point results never enter response headers. */
typedef struct bsi_motion_report {
  double elapsed, maxPenetration, positionCorrection, numericalEnergyRemoved;
  uint64_t substeps, trials, contactSolves, contactPoints, projectionSweeps;
  uint64_t wakeTrials, integratedBodies, equilibriumSolves;
  uint8_t fullFallback, reserved[7];
} bsi_motion_report;

typedef struct bsi_motion_declare {
  uint32_t struct_size;
  bsi_world_stamp scene;
  const bsi_motion_source* bodies; uint32_t nBodies;
  const bsi_fracture_cell* cells; uint32_t nCells;
  const bsi_motion_box* terrain; uint32_t nTerrain;
  double chordTolerance;
  uint32_t maxBodies, maxCells, maxColliders, maxVertices;
} bsi_motion_declare;
typedef struct bsi_motion_step {
  uint32_t struct_size;
  bsi_world_stamp scene;
  bsi_id128 request;
  const bsi_motion_state* states; uint32_t nStates;
  const bsi_motion_force* forces; uint32_t nForces;
  double dt, gravity[3], maxStep;
  uint32_t maxSubsteps, maxTrials, maxPairs, maxPoints, maxSurfaceTests, maxSweeps;
  uint8_t enableSleep;             /* 0 or 1; sleep cache is never durable state */
} bsi_motion_step;
/* Read-only borrowed spans. Copy before successful replacement or close.
 * A rejected call preserves the previous view. Triangles index the full vertex
 * span. Vertices are body-COM-relative; meshes never define physical mass. */
typedef struct bsi_motion_geometry_view {
  uint32_t struct_size;
  bsi_world_stamp scene;
  const bsi_motion_body_geometry* bodies; uint32_t nBodies;
  const bsi_motion_piece* pieces; uint32_t nPieces;
  const bsi_motion_vertex* vertices; uint32_t nVertices;
  const bsi_motion_triangle* triangles; uint32_t nTriangles;
} bsi_motion_geometry_view;
typedef struct bsi_motion_step_view {
  uint32_t struct_size;
  bsi_world_stamp scene;
  bsi_id128 request;
  const bsi_motion_state* states; uint32_t nStates;
  const uint64_t* sleeping; uint32_t nSleeping;
  const uint64_t* woken; uint32_t nWoken;
  double elapsed, maxPenetration, positionCorrection, numericalEnergyRemoved;
  uint64_t substeps, trials, contactSolves, contactPoints, projectionSweeps;
  uint64_t wakeTrials, integratedBodies, equilibriumSolves;
  uint8_t fullFallback;
} bsi_motion_step_view;
#endif
