/* ABI8 opaque finite history checkpoints. Include through bsi_engine.h. */
#ifndef BSI_COROT_CHECKPOINT_H
#define BSI_COROT_CHECKPOINT_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif
typedef struct bsi_corot_checkpoint_options {
    uint32_t struct_size, format; /* 1 = finite state; 2 also preserves arc direction */
    bsi_world_stamp expected;
    uint32_t byteBudget, allocationBudget, elementBudget, reserved;
} bsi_corot_checkpoint_options;
typedef struct bsi_corot_checkpoint_view {
    uint32_t struct_size, format;
    bsi_world_stamp basis;
    const uint8_t* data;
    uint32_t bytes;
} bsi_corot_checkpoint_view;
#endif
