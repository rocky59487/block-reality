/* ABI9 global finite arc control. Include through bsi_engine.h. */
#ifndef BSI_COROT_ARC_H
#define BSI_COROT_ARC_H
#ifndef BSI_ENGINE_H
#error "Include bsi_engine.h"
#endif
typedef struct bsi_corot_arc_request {
    uint32_t struct_size, reserved;
    bsi_world_stamp expected;
    bsi_analysis_token initialAnalysis;
    double radius, minRadius, lengthScale, loadScale;
    double relativeTolerance, forceTolerance, momentTolerance, linearTolerance, pathTolerance;
    uint32_t maxIterations, maxAttempts, maxBacktracks, linearIterations, restart;
    int32_t initialDirection;
} bsi_corot_arc_request;
typedef struct bsi_corot_arc_view {
    uint32_t struct_size, reserved;
    double radius, constraintResidual;
    const bsi_corot_view* analysis;
} bsi_corot_arc_view;
#endif
