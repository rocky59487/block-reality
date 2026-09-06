// Recovery packing rules shared by every host transport. No mechanics here.
#pragma once
#include "../bsi_engine.h"
#include <cmath>
#include <limits>

#if (defined(BSI_TEST_RECOVERY_NO_FACET_BLOCKS) || defined(BSI_TEST_RECOVERY_NONFINITE) || \
     defined(BSI_TEST_RECOVERY_OVERFLOW) || defined(BSI_TEST_RECOVERY_NARROW_DC) || \
     defined(BSI_TEST_RECOVERY_FORCE_MEMBERS) || defined(BSI_TEST_RECOVERY_STATION_ORDER) || \
     defined(BSI_TEST_RECOVERY_TRUNCATE) || defined(BSI_TEST_RECOVERY_SURFACE_ORDER)) && \
    !defined(BSI_HOST_TEST_MUTATIONS)
#error "BSI recovery mutation requires BSI_HOST_TEST_MUTATIONS; never ship"
#endif

namespace bsi { namespace recovery {
inline bool number(double v, bool f32 = false, bool missing = false) {
#ifndef BSI_TEST_RECOVERY_NONFINITE
    if (missing && std::isnan(v)) return true;
    if (!std::isfinite(v)) return false;
#else
    (void)missing;
#endif
#ifndef BSI_TEST_RECOVERY_OVERFLOW
    if (f32 && std::isfinite(v) && std::abs(v) > std::numeric_limits<float>::max()) return false;
#else
    (void)f32;
#endif
    return true;
}
inline float narrow(double v) {
    float f = static_cast<float>(v);
#ifdef BSI_TEST_RECOVERY_TRUNCATE
    if (std::isfinite(v) && std::abs(static_cast<double>(f)) > std::abs(v)) f = std::nextafter(f, 0.0f);
#endif
    return f;
}
template<size_t N> inline bool numbers(const double (&v)[N], bool f32 = false) {
    for (double x : v) if (!number(x, f32)) return false;
    return true;
}
inline bool valid(const bsi_station& s, bool f32) {
    return number(s.s, f32) && number(s.x, f32) && number(s.y, f32) && number(s.z, f32) &&
        numbers(s.sigma, f32) && number(s.tau, f32) && number(s.naY, f32, true) && number(s.naZ, f32, true);
}
inline bool valid(const bsi_member_result& m) {
    return number(m.lengthM) && numbers(m.endI) && numbers(m.endJ) && number(m.maxDC) && number(m.governingS);
}
inline bool valid(const bsi_facet_result& f) {
    for (const auto& c : f.corners) if (!numbers(c)) return false;
    return number(f.thicknessM) && numbers(f.ex) && numbers(f.ey) && numbers(f.n) &&
        numbers(f.N) && numbers(f.M) && numbers(f.Q) && number(f.dc);
}
inline bool valid(const bsi_surface& s, bool f32) {
    return number(s.s1, f32) && number(s.s2, f32) && number(s.theta, f32) && number(s.vm, f32);
}
}} // namespace bsi::recovery
