/* ABI10 tensor material extension. Original embedded ABI7 requests stay fixed. */
#ifndef BSI_COROT_SHELL_H
#define BSI_COROT_SHELL_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif
typedef struct bsi_corot_shell_law {
  int32_t material; uint32_t layerFirst,layerCount,reserved;
  double E,nu,yield,isotropic,kinematic;
} bsi_corot_shell_law;
typedef struct bsi_corot_layer { double position,weight; } bsi_corot_layer;
typedef struct bsi_corot_tensor_point {
  double strain[6],plastic[6],accumulated,dissipation,stress[6],tangent[36],storedEnergy,workPotential;
  uint32_t loading,reserved;
} bsi_corot_tensor_point;
typedef struct bsi_corot_shell_profile {
  int32_t shell; uint32_t available,layerFirst,layerCount,pointFirst,pointCount;
  double E,nu,yield,isotropic,kinematic,storedEnergy,dissipatedEnergy,workPotential;
} bsi_corot_shell_profile;
typedef struct bsi_corot_shell_material_view {
  uint32_t struct_size,reserved;
  const bsi_corot_shell_profile* profiles; uint32_t nProfiles;
  const bsi_corot_layer* layers; uint32_t nLayers;
  const bsi_corot_tensor_point* points; uint32_t nPoints;
} bsi_corot_shell_material_view;
typedef struct bsi_corot_shell_input {
  const bsi_corot_shell_law* laws; uint32_t nLaws;
  const bsi_corot_layer* layers; uint32_t nLayers;
} bsi_corot_shell_input;
typedef struct bsi_corot_shell_request {
  uint32_t struct_size,reserved;
  bsi_corot_request analysis;
  bsi_corot_shell_input material;
} bsi_corot_shell_request;
typedef struct bsi_corot_shell_fracture_options {
  uint32_t struct_size,reserved;
  bsi_corot_fracture_options fracture;
  bsi_corot_shell_input material;
} bsi_corot_shell_fracture_options;
#endif
