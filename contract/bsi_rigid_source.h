/* ABI11 additions. Portable finite rigid source packets: opaque, native-owned
 * sealed material/geometry sources exported from a committed corot fracture
 * token and re-declared into one common rigid scene without any live token or
 * identified world. Packets are integrity-sealed (CRC64), not signatures; the
 * durable store and the engine's structural/physical re-validation protect
 * provenance. */
#ifndef BSI_RIGID_SOURCE_H
#define BSI_RIGID_SOURCE_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h, not bsi_rigid_source.h directly"
#endif

/* bsi.corot.rigid.source.export -- capture each named fragment of the
 * committed corot fracture into its own sealed packet. Bodies and velocity
 * ranges carry the same meaning as bsi_corot_motion_declare. The integration
 * options are recorded in the packet and re-applied verbatim at scene
 * declaration so a fresh process reproduces the same projection. */
typedef struct bsi_rigid_source_export {
  uint32_t struct_size, format;          /* format = 1 */
  bsi_world_stamp expected;              /* live identified world stamp */
  bsi_trial_token fracture;              /* exact committed corot fracture token */
  const bsi_corot_body* bodies; uint32_t nBodies;
  const bsi_corot_velocity* velocities; uint32_t nVelocities;
  double relativeTolerance, absoluteTolerance;
  uint32_t maxRefinements, maxSamples;
  uint32_t byteBudget, allocationBudget, elementBudget;
} bsi_rigid_source_export;
/* Read-only borrowed bytes; copy before the next call. data is a sequence of
 * u32 little-endian length + sealed packet in ascending body id order. */
typedef struct bsi_rigid_source_batch {
  uint32_t struct_size, format;
  bsi_world_stamp basis;                 /* fracture before-world stamp */
  bsi_trial_token fracture;
  const uint8_t* data; uint32_t bytes;
  uint32_t nSources;
} bsi_rigid_source_batch;

/* bsi.rigid.scene.declare -- declare one common rigid scene from sealed
 * packets. ids[i] must equal the body id recorded inside the i-th packet;
 * input order is normalized by the engine so reordering the wire records does
 * not change the scene. No live token or identified world is required. */
typedef struct bsi_rigid_scene_declare {
  uint32_t struct_size;
  bsi_world_stamp scene;
  const uint64_t* ids; uint32_t nIds;               /* positive signed-i64, unique */
  const uint8_t* sources; uint32_t sourceBytes;     /* u32-length-prefixed packets */
  const bsi_motion_box* terrain; uint32_t nTerrain;
  double chordTolerance;
  uint32_t maxBodies, maxCells, maxColliders, maxVertices;
  uint32_t byteBudget, allocationBudget, elementBudget;
} bsi_rigid_scene_declare;
/* Borrowed until the next call. states/energies are each packet's birth
 * projection, mirroring corot.rigid.declare. */
typedef struct bsi_rigid_scene_view {
  uint32_t struct_size;
  const bsi_motion_geometry_view* geometry;
  const bsi_motion_state* states; uint32_t nStates;
  const bsi_corot_body_energy* energies; uint32_t nEnergies;
} bsi_rigid_scene_view;
#endif
