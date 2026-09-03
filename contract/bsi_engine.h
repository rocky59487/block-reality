/* bsi_engine.h -- BSI v1 engine adapter ABI (contract artifact; mirrored by hash in
 * block-reality and tectonic2). See BSI.md Part C. C99 / C++17 compatible.
 *
 * An engine implements ONE vtable. The shared host library (bsi-host) owns
 * transport, framing, schema validation, canonical ordering, vocabulary
 * validation/typing, the zero-copy arena and result packing. The engine
 * receives typed arrays and writes typed structs through bsi_writer_*; it never
 * touches bytes on the wire.
 */
#ifndef BSI_ENGINE_H
#define BSI_ENGINE_H
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BSI_MAJOR 1
#define BSI_ENGINE_ABI 1u

#if defined(_WIN32) && !defined(BSI_STATIC)
#  ifdef BSI_ENGINE_BUILD
#    define BSI_EXPORT __declspec(dllexport)
#  else
#    define BSI_EXPORT __declspec(dllimport)
#  endif
#elif defined(__GNUC__) && defined(BSI_ENGINE_BUILD)
/* Part G 2026-09-03 item 13: an engine library built with hidden visibility (the
 * shipping shape: nothing but the contract's own symbols leaves the .so) would
 * otherwise hide bsi_engine_entry itself, and a version script cannot un-hide
 * what the compiler marked hidden. Same branch bsi_capi.h already carries. */
#  define BSI_EXPORT __attribute__((visibility("default")))
#else
#  define BSI_EXPORT
#endif

/* ---- return codes (host maps them to wire error tokens, BSI.md B.7) -------- */
enum bsi_status {
  BSI_OK                = 0,
  BSI_E_PROTOCOL        = 1,   /* PROTOCOL_ERROR       */
  BSI_E_UNSUPPORTED     = 2,   /* UNSUPPORTED          */
  BSI_E_UNSUPPORTED_ATTR= 3,   /* UNSUPPORTED_ATTR     */
  BSI_E_VOCAB           = 4,   /* VOCAB_INVALID        */
  BSI_E_NO_WORLD        = 5,   /* NO_WORLD             */
  BSI_E_EMPTY_WORLD     = 6,   /* EMPTY_WORLD          */
  BSI_E_EXTRACT         = 7,   /* EXTRACT_FAILED       */
  BSI_E_LOAD_TARGET     = 8,   /* LOAD_TARGET          */
  BSI_E_LOAD_UNSUPPORTED= 9,   /* LOAD_UNSUPPORTED     */
  BSI_E_SOLVE           = 10,  /* SOLVE_FAILED         */
  BSI_E_OOM             = 11,  /* OUT_OF_MEMORY        */
  BSI_E_CANCELLED       = 12,  /* CANCELLED            */
  BSI_E_BUDGET          = 13,  /* BUDGET_EXCEEDED      */
  BSI_E_INTERNAL        = 14   /* INTERNAL             */
};

/* ---- enumerations (values are wire values; never renumber, append only) ---- */
enum bsi_role   { BSI_ROLE_NONSTRUCTURAL = 0, BSI_ROLE_MEMBER = 1, BSI_ROLE_PANEL = 2, BSI_ROLE_SUPPORT = 3, BSI_ROLE_MONOLITH = 4 };
enum bsi_model  { BSI_MODEL_ISOTROPIC = 0, BSI_MODEL_ORTHOTROPIC = 1, BSI_MODEL_COMPOSITE_RC = 2, BSI_MODEL_ROPE = 3, BSI_MODEL_VENDOR = 255 };
enum bsi_support_kind { BSI_SUPPORT_FIXALL = 0, BSI_SUPPORT_TRANSLATION_ONLY = 1 };
enum bsi_sect_kind { BSI_SECT_RECT = 0, BSI_SECT_CIRCLE = 1, BSI_SECT_H = 2, BSI_SECT_BOX = 3, BSI_SECT_PIPE = 4, BSI_SECT_RCRECT = 5, BSI_SECT_CUSTOM = 6 };
enum bsi_mode   { BSI_MODE_NONE = 0, BSI_MODE_AXIAL = 1, BSI_MODE_BENDING = 2, BSI_MODE_SHEAR = 3, BSI_MODE_TORSION = 4, BSI_MODE_COMBINED = 5, BSI_MODE_SHELL = 6 };
enum bsi_fibre  { BSI_FIBRE_NONE = 0, BSI_FIBRE_CRUSH = 1, BSI_FIBRE_TENSION = 2, BSI_FIBRE_SHEAR = 3, BSI_FIBRE_BENDING = 4, BSI_FIBRE_TORSION = 5, BSI_FIBRE_SHELL_VM = 6 };
enum bsi_owner  { BSI_OWNER_NONE = 0, BSI_OWNER_MEMBER = 1, BSI_OWNER_FACET = 2, BSI_OWNER_UNASSIGNED = 3 };
enum bsi_buckling_mode  { BSI_BUCK_NONE = 0, BSI_BUCK_EIGEN = 1, BSI_BUCK_SCREEN = 2 };
enum bsi_buckling_state { BSI_BSTATE_COMPUTED = 0, BSI_BSTATE_NO_POSITIVE = 1, BSI_BSTATE_NOT_ELIGIBLE = 2, BSI_BSTATE_NOT_ELIGIBLE_SCALE = 3, BSI_BSTATE_DISABLED = 4, BSI_BSTATE_SOLVER_FAILED = 5 };
enum bsi_tier    { BSI_TIER_COMMIT = 0, BSI_TIER_DISPLAY = 1 };
enum bsi_storage { BSI_STORAGE_F64 = 0, BSI_STORAGE_F32 = 1 };
enum bsi_edit_op { BSI_EDIT_ADD = 0, BSI_EDIT_REMOVE = 1, BSI_EDIT_UPDATE = 2 };

/* ---- wire records (packed; byte layouts are the contract, BSI.md B.4/B.5) -- */
#pragma pack(push, 1)
typedef struct bsi_block {          /* 40 B */
  int32_t x, y, z;
  int32_t mat;                      /* vocabulary material id */
  int32_t sect;                     /* section id or -1 (material default) */
  uint8_t axis;                     /* 0=x 1=y 2=z (declared by placement) */
  uint8_t joint;                    /* 0=rigid 1=pinned */
  uint8_t axisRot;                  /* 0..3 quarter turns */
  uint8_t attr;                     /* 0 none, 1 has an attrs record */
  double  fill;                     /* (0,1] */
  double  strength;                 /* [0,1] curing */
} bsi_block;

typedef struct bsi_attr {           /* 16 B (Part G 2026-09-02 item 12: key is u16; the
                                       original u32 key + reserved[3] summed to 20 B) */
  uint32_t blockIndex;              /* index into canonical block order */
  uint16_t key;                     /* index into vocab.attrKeys */
  uint8_t  type;                    /* 0 f64, 1 i64, 2 u64 bitmask */
  uint8_t  reserved;
  uint8_t  value[8];
} bsi_attr;

typedef struct bsi_edit {           /* 41 B */
  uint8_t   op;                     /* bsi_edit_op */
  bsi_block block;
} bsi_edit;

typedef struct bsi_load {           /* 64 B */
  int32_t  x, y, z;
  uint32_t flags;                   /* must be 0 */
  double   f[3];                    /* N, world axes */
  double   m[3];                    /* N*m, must be 0 in v1 */
} bsi_load;

typedef struct bsi_block_result {   /* 24 B */
  double   dc;
  int32_t  island;
  int32_t  owner;                   /* member/facet id or -1 */
  uint8_t  mode;                    /* bsi_mode */
  uint8_t  ownerKind;               /* bsi_owner */
  uint8_t  flags;                   /* bit0 overloaded, bit1 indicative, bit2 bucklingCritical */
  uint8_t  reserved;
  uint32_t reason;                  /* unassigned reason enum, 0 = none */
} bsi_block_result;

typedef struct bsi_station {        /* 88 B */
  double s;                         /* [0,1] along the member */
  double x, y, z;                   /* world position, m */
  double sigma[4];                  /* TOP_Y, BOT_Y, PLUS_Z, MINUS_Z (Pa, tension +) */
  double tau;                       /* Pa */
  double naY, naZ;                  /* m from centroid; NaN = none */
} bsi_station;

typedef struct bsi_member_result {  /* 160 B; blocks/stations are passed separately */
  int32_t  id, island;
  uint32_t blockFirst, blockCount;  /* filled by the writer */
  uint32_t stationFirst, stationCount;
  int32_t  material, section;
  double   lengthM;
  double   endI[6], endJ[6];        /* section forces, local axes, N tension + */
  double   maxDC, governingS;
  uint8_t  mode, governingFibre, flags;
  uint8_t  reserved[5];
} bsi_member_result;

typedef struct bsi_facet_result {   /* 280 B */
  int32_t  id, island;
  uint32_t blockFirst, blockCount;
  int32_t  material;
  uint32_t reserved0;
  double   thicknessM;
  double   corners[4][3];
  double   ex[3], ey[3], n[3];      /* ex x ey = n */
  double   N[3], M[3], Q[2];        /* Nxx Nyy Nxy (N/m), Mxx Myy Mxy (N*m/m), Qx Qy (N/m) */
  double   dc;
  uint8_t  flags, governingFibre;   /* flags bit0 overloaded, bit1 governing on top */
  uint8_t  reserved[6];
} bsi_facet_result;

typedef struct bsi_surface { double s1, s2, theta, vm; } bsi_surface;   /* per corner per face */
#pragma pack(pop)

/* ---- typed vocabulary handed to the engine (host-validated) ---------------- */
typedef struct bsi_material {
  const char* name; uint8_t role; uint8_t model; uint8_t supportKind; uint8_t temporary;
  double E[3], G[3], nu[3], rho;          /* isotropic uses [0] only */
  double Ec, rhoC;                        /* composite_rc */
  double sigmaAllowC, sigmaAllowT, tauAllowS, sigmaAllow;
  double fc, ft, tauS;
  double shellThickness;
  int32_t defaultSection;
  uint8_t tensionOnly, eulerBernoulli, hasCapacity, reserved;
  const char* vendorModel;                /* "x-vendor:name" or NULL */
  const char* vendorJson;                 /* raw JSON of x-* keys or NULL */
} bsi_material;

typedef struct bsi_section {
  const char* name; uint8_t kind; uint8_t reserved[7];
  double p[4];                            /* kind-specific, m */
  double A, Iy, Iz, J, cy, cz, Asy, Asz, Zy, Zz, principalAngle;   /* custom kind */
} bsi_section;

typedef struct bsi_vocab {
  uint32_t version;
  const bsi_material* materials; uint32_t nMaterials;
  const bsi_section*  sections;  uint32_t nSections;
  const char* const*  attrKeys;  uint32_t nAttrKeys;
} bsi_vocab;

typedef struct bsi_solve_options {
  uint8_t  selfWeight;
  double   gravity[3];
  uint8_t  bucklingMode;                  /* bsi_buckling_mode */
  double   bucklingK;
  uint32_t bucklingBudgetDof;
  uint8_t  tier;                          /* bsi_tier */
  double   targetRel;
  uint8_t  storage;                       /* bsi_storage */
  uint8_t  warmStart;
  uint32_t maxTimeMs;
  uint32_t numThreads;                    /* 0 = engine default */
  uint32_t includeMask;                   /* bit0 members, bit1 stations, bit2 shells, bit3 attrsEcho */
} bsi_solve_options;

/* ---- host services & result writer ---------------------------------------- */
typedef struct bsi_host   bsi_host;      /* opaque; logging, alloc, cancellation flag */
typedef struct bsi_engine bsi_engine;    /* opaque; engine-owned */
typedef struct bsi_writer bsi_writer;    /* opaque; writes straight into the arena */

BSI_EXPORT int  bsi_host_cancelled(const bsi_host*);                     /* poll during long solves */
BSI_EXPORT void bsi_host_log(const bsi_host*, int level, const char* msg);

BSI_EXPORT int bsi_writer_blocks(bsi_writer*, const bsi_block_result* r, uint32_t n);          /* n == declared block count, canonical order */
BSI_EXPORT int bsi_writer_member(bsi_writer*, const bsi_member_result* m,
                                 const int32_t* blocksXyz, uint32_t nBlocks,
                                 const bsi_station* st, uint32_t nStations);
BSI_EXPORT int bsi_writer_facet(bsi_writer*, const bsi_facet_result* f,
                                const int32_t* blocksXyz, uint32_t nBlocks,
                                const bsi_surface top[4], const bsi_surface bottom[4]);
BSI_EXPORT int bsi_writer_unassigned(bsi_writer*, const char* why, int32_t island,
                                     const int32_t* blocksXyz, uint32_t nBlocks);
BSI_EXPORT int bsi_writer_warning(bsi_writer*, const char* code, uint32_t count);
BSI_EXPORT int bsi_writer_equilibrium(bsi_writer*, const double applied[3], const double reaction[3], double residual);
BSI_EXPORT int bsi_writer_quality(bsi_writer*, double achievedRel, int32_t iterations,
                                  uint8_t tierHonoured, uint8_t warmStartUsed, uint8_t timedOut);
BSI_EXPORT int bsi_writer_buckling(bsi_writer*, int32_t island, uint8_t state, uint8_t kind, double factor);
BSI_EXPORT int bsi_writer_edit_class(bsi_writer*, char cls, const char* downgradedOrNull);
BSI_EXPORT int bsi_writer_diag(bsi_writer*, uint32_t nodes, uint32_t members, uint32_t facets,
                               uint32_t islands, uint32_t singularIslands, uint32_t refusedBlocks);
BSI_EXPORT int bsi_writer_error(bsi_writer*, const char* code, const char* message, const int32_t* atXyzOrNull);

/* ---- the engine vtable (append-only; host reads up to abi_version) -------- */
typedef struct bsi_engine_vtable {
  uint32_t abi_version;                                        /* = BSI_ENGINE_ABI */
  const char* (*name)(void);
  const char* (*version)(void);
  const char* (*build_sha)(void);
  uint32_t    (*capabilities)(const char* const** out);
  bsi_engine* (*open)(const bsi_host* host);
  void        (*close)(bsi_engine*);
  int (*vocab)(bsi_engine*, const bsi_vocab* v);
  int (*world_declare)(bsi_engine*, const bsi_block* blocks, uint32_t n,
                       const bsi_attr* attrs, uint32_t nAttrs, bsi_writer* w);
  int (*world_edit)(bsi_engine*, const bsi_edit* edits, uint32_t n, bsi_writer* w);   /* may be NULL */
  int (*solve)(bsi_engine*, const bsi_solve_options* o, const bsi_load* loads, uint32_t n, bsi_writer* w);
  int (*cancel)(bsi_engine*);                                                          /* may be NULL */
} bsi_engine_vtable;

/* The single exported symbol an engine must provide. Returns NULL when
 * host_abi_version is not supported (host then refuses to load). */
BSI_EXPORT const bsi_engine_vtable* bsi_engine_entry(uint32_t host_abi_version);

#ifdef __cplusplus
}
#endif
#endif /* BSI_ENGINE_H */
