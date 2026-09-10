// BSI record validation and world reduction, independent of any engine solver.
#pragma once
#include "../bsi_engine.h"
#include <cmath>
#if (defined(BSI_TEST_BUCKLING_AGG) || defined(BSI_TEST_BUCKLING_IDS) || defined(BSI_TEST_BUCKLING_KIND) || defined(BSI_TEST_BUCKLING_FACTOR) || defined(BSI_TEST_BUCKLING_ORDER) || defined(BSI_TEST_BUCKLING_CRITICAL)) && !defined(BSI_HOST_TEST_MUTATIONS)
#error "BSI buckling mutation requires BSI_HOST_TEST_MUTATIONS; never ship"
#endif
namespace bsi { namespace buckling {
inline int rank(uint8_t state) {
    // Wire ordinals are immutable; they are not severity order.
    static constexpr int ranks[] = {1,0,3,2,-1,4};
    return state < 6 ? ranks[state] : 5;
}
inline bool valid(uint8_t state, uint8_t kind, double factor, uint8_t requested) {
    if (state > BSI_BSTATE_SOLVER_FAILED || requested > BSI_BUCK_SCREEN) return false;
#ifndef BSI_TEST_BUCKLING_KIND
    if (kind != requested || ((requested == BSI_BUCK_NONE) != (state == BSI_BSTATE_DISABLED))) return false;
#else
    (void)kind;
#endif
#ifndef BSI_TEST_BUCKLING_FACTOR
    if (state == BSI_BSTATE_COMPUTED) return std::isfinite(factor) && factor > 0;
    return std::isnan(factor);
#else
    (void)factor; return true;
#endif
}
inline bool critical(uint8_t state, double factor) {
    if (state != BSI_BSTATE_COMPUTED || !std::isfinite(factor) || factor <= 0) return false;
#ifdef BSI_TEST_BUCKLING_CRITICAL
    return factor <= 1.0;
#else
    return factor < 1.0;
#endif
}
} }
