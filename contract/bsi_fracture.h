/* Included by bsi_engine.h after the common typed records. ABI3 additions. */
#ifndef BSI_FRACTURE_H
#define BSI_FRACTURE_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h, not bsi_fracture.h directly"
#endif

typedef struct bsi_id128 { uint64_t hi, lo; } bsi_id128;
typedef struct bsi_world_stamp { bsi_id128 domain; int64_t revision; } bsi_world_stamp;
typedef struct bsi_trial_token { bsi_id128 context; uint64_t sequence; } bsi_trial_token;
#pragma pack(push, 1)
typedef struct bsi_artifact_owner { int32_t x, y, z; int64_t artifact; } bsi_artifact_owner;
#pragma pack(pop)
/* Physical records have natural 8-byte alignment, including array stride.
 * Copy packed wire bytes into typed storage; do not cast an arbitrary payload. */
typedef struct bsi_physical_properties {
  double mass, center[3], inertia[6]; /* SI; COM tensor xx,yy,zz,xy,xz,yz */
} bsi_physical_properties;
typedef struct bsi_fracture_cell {
  bsi_block source;
  int32_t panelNormal;              /* -1 non-panel; otherwise 0=x,1=y,2=z */
  uint32_t group;                   /* 0 remaining, 1 broken, 2+ fragment index */
  int64_t artifact;
  uint32_t flags;                   /* bit0 unrepresented (must remain) */
  uint32_t reserved;                /* zero; physical starts at offset 64 */
  bsi_physical_properties physical;
} bsi_fracture_cell;
typedef struct bsi_fracture_fragment {
  bsi_physical_properties physical;
  uint32_t parentFirst, parentCount;
  uint32_t flags;                   /* bit0 releases, bit1 tension-only, bit2 couplings */
  uint32_t reserved;                /* zero; 96-byte stride */
} bsi_fracture_fragment;
typedef struct bsi_fracture_event {
  uint32_t cellFirst, cellCount;     /* range in eventCells: indices into source cells */
  double utilization;
  uint8_t face;                     /* capacity face; not a material/crush classifier */
  uint8_t reserved[7];
} bsi_fracture_event;

typedef struct bsi_world_identity {
  uint32_t struct_size;
  bsi_world_stamp stamp;
  bsi_id128 artifactNamespace;
  const bsi_artifact_owner* owners;
  uint32_t nOwners;
} bsi_world_identity;
typedef struct bsi_identified_edit {
  uint32_t struct_size;
  bsi_world_stamp expected;
  const bsi_artifact_owner* owners;  /* complete after-world ownership */
  uint32_t nOwners;
} bsi_identified_edit;
typedef struct bsi_fracture_options {
  uint32_t struct_size;
  bsi_world_stamp expected;
  bsi_id128 request;
  double gravity[3];
  uint32_t budget, numThreads;
  uint8_t tier;                     /* must be BSI_TIER_COMMIT */
} bsi_fracture_options;
typedef struct bsi_fracture_finish {
  uint32_t struct_size;
  bsi_trial_token token;
  bsi_id128 request;
  bsi_world_stamp expected;
  int64_t resultRevision;
  uint8_t action;                   /* 0 discard, 1 commit */
} bsi_fracture_finish;
enum bsi_fracture_finish_status {
  BSI_FRACTURE_COMMITTED=0, BSI_FRACTURE_REPLAYED=1, BSI_FRACTURE_DISCARDED=2
};
/* Borrowed immutable spans, valid until successful world change, replacement,
 * discard or close. Copy before retaining beyond the call. No pointer on wire. */
typedef struct bsi_fracture_view {
  uint32_t struct_size;
  bsi_trial_token token;
  bsi_id128 request, artifactNamespace;
  bsi_world_stamp before, after;
  bsi_physical_properties beforeMass, remainingMass, brokenMass;
  const bsi_fracture_cell* cells; uint32_t nCells;
  const bsi_fracture_fragment* fragments; uint32_t nFragments;
  const int64_t* parents; uint32_t nParents;
  const bsi_fracture_event* events; uint32_t nEvents;
  const uint32_t* eventCells; uint32_t nEventCells;
  const int32_t* mechanismXyz; uint32_t nMechanismCells;
  uint32_t steps;
  uint8_t flags;                    /* bit0 exhausted, bit1 mechanism rollback */
} bsi_fracture_view;
#endif
