/* ABI7 finite mechanics, owning material history and shared fracture/motion. */
#ifndef BSI_COROT_H
#define BSI_COROT_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif

enum bsi_corot_available { BSI_CO_SOLVED=1, BSI_CO_CHECKPOINT=2, BSI_CO_MATERIAL=4 };
enum bsi_corot_state { BSI_CO_CONVERGED=0, BSI_CO_MECHANISM=1, BSI_CO_NOT_CONVERGED=2, BSI_CO_UNSUPPORTED=3, BSI_CO_INACTIVE=4 };
enum bsi_corot_material_kind { BSI_CO_PLASTIC=0, BSI_CO_DAMAGE=1, BSI_CO_COMPRESSION_PLASTICITY=2 };
typedef struct bsi_corot_law {
  uint32_t kind, reserved;
  double E, tension, compression, kinematic, isotropic, fractureEnergyT, fractureEnergyC, compressionPlasticity;
} bsi_corot_law;
typedef struct bsi_corot_material { int32_t material; uint32_t reserved; bsi_corot_law primary, concrete; } bsi_corot_material;
typedef struct bsi_corot_options {
  uint32_t struct_size, flags; /* bit0 selfWeight, bit1 followerLocal, bit2 retainLoadPath, bit3 transferTopology, bit4 useMetis */
  bsi_world_stamp expected;
  double loadFactor, gravity[3], relativeTolerance, forceTolerance, momentTolerance;
  double initialStep, minStep, maxStep, linearTolerance, pathTolerance;
  uint32_t budgetDof, numThreads, maxIterations, maxAttempts, maxBacktracks, linearIterations, restart;
  uint32_t pointBudget, fiberCells, radialCells, angularCells;
} bsi_corot_options;
typedef struct bsi_corot_prescribed { int32_t node[4]; double value[6]; } bsi_corot_prescribed;
/* Stable ordered endpoint keys, not transient extraction indices.
 * kind 0=node wrench, 1=member point force, 2=member line wrench, 3=UDL,
 * 4=shell pressure. Unused targets/components must be zero. */
typedef struct bsi_corot_load {
  uint32_t kind, localFrame;
  int32_t target[4][4];
  double begin, end, force[3], couple[3], pressure;
} bsi_corot_load;
typedef struct bsi_corot_request {
  uint32_t struct_size;
  bsi_corot_options options;
  const bsi_corot_material* materials; uint32_t nMaterials;
  const bsi_load* cellLoads; uint32_t nCellLoads;
  const bsi_corot_prescribed* prescribed; uint32_t nPrescribed;
  const bsi_corot_load* loads; uint32_t nLoads;
} bsi_corot_request;
typedef struct bsi_corot_island {
  uint32_t status, freeDof, flags; /* resumed=1, transferred=2, checkpoint=4 */
  uint32_t iterations, linearIterations, acceptedSteps, rejectedSteps, backtracks, factors, historyChecks;
  double forceResidual, momentResidual, historyError, historyLoadFactor, storedEnergy, dissipatedEnergy;
  const char* reason;
} bsi_corot_island;
typedef struct bsi_corot_node {
  int32_t key[4], island; uint32_t available;
  double reference[3], position[3], orientation[4], reaction[6], applied[6]; /* quaternion w,x,y,z */
  uint8_t fixed[6], reserved[2];
} bsi_corot_node;
typedef struct bsi_corot_member {
  int32_t island, nodes[2], material, section, profile;
  uint32_t available, sourceFirst, sourceCount, active;
  double length, storedEnergy, frame[4], localDisplacement[12], localAction[12], action[12], external[12];
} bsi_corot_member;
typedef struct bsi_corot_shell {
  int32_t island, nodes[4], material;
  uint32_t available, sourceFirst, sourceCount, active;
  double thickness, area, storedEnergy, frame[4], localDisplacement[24], localAction[24], action[24], external[24];
  double sections[5][8]; /* centroid then Gauss: membrane[3], bending[3], shear[2] */
} bsi_corot_shell;
typedef struct bsi_corot_fiber { double y,z,area; uint32_t constituent, reserved; bsi_corot_law law; } bsi_corot_fiber;
typedef struct bsi_corot_station { double station,weight,section[3]; } bsi_corot_station; /* N tension+, My, Mz */
typedef struct bsi_corot_point {
  double strain,plasticStrain,accumulatedPlastic,plasticDissipation,maximumT,maximumC,damageDissipation;
  double stress,tangent,storedEnergy,dissipatedEnergy,workPotential,damageT,damageC;
  int32_t loading; uint32_t reserved;
} bsi_corot_point;
typedef struct bsi_corot_profile {
  int32_t member; uint32_t available, fiberFirst,fiberCount,stationFirst,stationCount,pointFirst,pointCount;
  uint32_t physicalSection,reserved; double area,Iy,Iz,storedEnergy,dissipatedEnergy,workPotential;
} bsi_corot_profile;
/* Material table is also used by retired members. Ranges index this table only. */
typedef struct bsi_corot_material_view {
  const bsi_corot_profile* profiles; uint32_t nProfiles;
  const bsi_corot_fiber* fibers; uint32_t nFibers;
  const bsi_corot_station* stations; uint32_t nStations;
  const bsi_corot_point* points; uint32_t nPoints;
} bsi_corot_material_view;
typedef struct bsi_corot_joint {
  uint32_t kind,available; /* 0=node chart, 1=member release, 2=link wrench, 3=coupling slave, 4=coupling master */
  int32_t index,node,end,master;
  double position[3],orientation[4],relativeOrientation[4],coordinate[6],action[6];
} bsi_corot_joint;
typedef struct bsi_corot_view {
  uint32_t struct_size,fullyConverged;
  bsi_world_stamp basis; bsi_id128 artifactNamespace; uint64_t generation;
  bsi_analysis_token token;
  bsi_corot_options options;
  bsi_physical_properties physical,weightGroups[6];
  const bsi_corot_island* islands; uint32_t nIslands;
  const bsi_corot_node* nodes; uint32_t nNodes;
  const bsi_corot_member* members; uint32_t nMembers;
  const bsi_corot_shell* shells; uint32_t nShells;
  const bsi_corot_joint* joints; uint32_t nJoints;
  const bsi_pdelta_source* sources; uint32_t nSources;
  const uint32_t* sourceIndices; uint32_t nSourceIndices;
  const bsi_pdelta_artifact* artifacts; uint32_t nArtifacts;
  const int32_t* artifactMembers; uint32_t nArtifactMembers;
  const int32_t* artifactShells; uint32_t nArtifactShells;
  bsi_corot_material_view material;
} bsi_corot_view;
typedef struct bsi_corot_failure_rule {
  int32_t material; uint32_t constituent;
  double accumulatedPlastic,damageT,damageC,minimumAreaFraction;
} bsi_corot_failure_rule;
typedef struct bsi_corot_fracture_options {
  uint32_t struct_size;
  bsi_corot_request analysis;
  bsi_id128 request;
  bsi_analysis_token initialAnalysis;
  const bsi_corot_failure_rule* rules; uint32_t nRules;
  uint32_t budget, useInitial;
} bsi_corot_fracture_options;
typedef struct bsi_corot_phase_failure {
  uint32_t constituent,flags,mode; /* configured=1, failed=2 */
  int32_t station,fiber;
  double coordinate,value,limit,utilization,failedArea,totalArea,fraction,requiredFraction;
} bsi_corot_phase_failure;
typedef struct bsi_corot_decision {
  uint32_t microStep,cellFirst,cellCount,phaseFirst,phaseCount;
  int32_t member,station;
  double coordinate,utilization;
} bsi_corot_decision;
typedef struct bsi_corot_failure {
  int32_t member,station; uint32_t flags,phaseFirst,phaseCount,reserved; /* available=1, complete=2, failed=4 */
  double coordinate,utilization;
} bsi_corot_failure;
typedef struct bsi_corot_retired_member {
  int32_t member,island,profile,nodeKeys[2][4];
  uint32_t archive,cellFirst,cellCount,reserved;
  double referenceNodes[2][3],position[2][3],orientation[2][4];
  double referenceMaterial[4],referenceSection[10],referenceVector[3]; /* E,G,rho,sigmaAllow; A,Iy,Iz,J,cy,cz,Asy,Asz,Zy,Zz */
  bsi_corot_member mechanics;
  bsi_corot_joint releases[2]; /* available=0 when absent */
} bsi_corot_retired_member;
typedef struct bsi_corot_retired_shell {
  int32_t shell,island,nodeKeys[4][4]; uint32_t archive,cellFirst,cellCount,reserved;
  double referenceNodes[4][3],position[4][3],orientation[4][4],referenceMaterial[4];
  bsi_corot_shell mechanics;
} bsi_corot_retired_shell;
typedef struct bsi_corot_archive {
  bsi_world_stamp before,after; bsi_id128 artifactNamespace;
  uint64_t beforeGeneration,afterGeneration;
  uint32_t flags,memberFirst,memberCount,reserved; /* hasBefore=1, sourcesEquilibrated=2, completeMechanicalFields=4 */
  uint32_t shellFirst,shellCount,removedFirst,removedCount,installedFirst,installedCount;
  uint64_t historyPoints;
  double energy[8]; /* before/retained/retired/installed stored, then same dissipated */
  bsi_physical_properties beforeMass,retainedMass,afterMass,removedMass,installedMass;
} bsi_corot_archive;
typedef struct bsi_corot_fracture_view {
  uint32_t struct_size,end,flags; /* hasPending=1, reusedInitial=2, unrepresented=4, shellMaterialUnavailable=8 */
  int32_t pending;
  const bsi_fracture_view* physical;
  const bsi_corot_view* remaining;
  const bsi_corot_decision* decisions; uint32_t nDecisions;
  const bsi_corot_phase_failure* phases; uint32_t nPhases;
  const bsi_corot_failure* failures; uint32_t nFailures; /* final world member order */
  const uint32_t* decisionCells; uint32_t nDecisionCells;
  const bsi_pdelta_fracture_load* loads; uint32_t nLoads;
  const bsi_corot_archive* archives; uint32_t nArchives;
  const bsi_corot_retired_member* retiredMembers; uint32_t nRetiredMembers;
  const bsi_corot_retired_shell* retiredShells; uint32_t nRetiredShells;
  const bsi_artifact_owner* retiredCells; uint32_t nRetiredCells;
  const bsi_artifact_owner* archiveOwners; uint32_t nArchiveOwners;
  bsi_corot_material_view retiredMaterial;
} bsi_corot_fracture_view;
typedef struct bsi_corot_velocity { int32_t node[4]; double linear[3],angular[3]; } bsi_corot_velocity;
typedef struct bsi_corot_body {
  uint64_t id,revision; uint32_t fragment,velocityFirst,velocityCount,reserved;
  double time; bsi_motion_surface surface;
} bsi_corot_body;
typedef struct bsi_corot_motion_declare {
  uint32_t struct_size;
  bsi_world_stamp scene;
  bsi_trial_token fracture;
  const bsi_corot_body* bodies; uint32_t nBodies;
  const bsi_corot_velocity* velocities; uint32_t nVelocities;
  const bsi_motion_box* terrain; uint32_t nTerrain;
  double chordTolerance,relativeTolerance,absoluteTolerance;
  uint32_t maxBodies,maxCells,maxColliders,maxVertices,maxRefinements,maxSamples;
} bsi_corot_motion_declare;
typedef struct bsi_corot_body_energy { uint64_t id; double kinetic,rigidKinetic,internalKinetic,estimatedError; uint64_t samples; } bsi_corot_body_energy;
typedef struct bsi_corot_motion_view {
  uint32_t struct_size;
  const bsi_motion_geometry_view* geometry;
  const bsi_motion_state* states; uint32_t nStates;
  const bsi_corot_body_energy* energies; uint32_t nEnergies;
  const bsi_corot_fracture_view* source; /* material energy and history remain owned; no invented release speed */
} bsi_corot_motion_view;
/* View pointers remain readable until the next successful operation in their
 * family or close; fracture views use the shared fracture candidate lifetime.
 * Longer input tails are ignored. Prefixes end at the last named field.
 * corot_solve publishes a prepared world only after storage succeeds. Use
 * SOLVED for mechanics; CHECKPOINT alone is retained unavailable history.
 * Fracture uses the existing fracture_finish authority; motion requires that
 * exact finite receipt to have committed. Continue with the original motion_step. */
#endif
