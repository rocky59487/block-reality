// Geometry validation only: no recovery formulas or section inference.
#pragma once
#include "bsi_recovery.hpp"
#if (defined(BSI_TEST_GEOMETRY_PAIR) || defined(BSI_TEST_GEOMETRY_FINITE)) && !defined(BSI_HOST_TEST_MUTATIONS)
#error "BSI geometry mutation requires BSI_HOST_TEST_MUTATIONS; never ship"
#endif
namespace bsi { namespace geometry {
static_assert(sizeof(bsi_member_geometry)==168, "memberGeometry 168 B");
inline double dot(const double a[3], const double b[3]) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
inline bool valid(const bsi_member_geometry& g) {
#ifndef BSI_TEST_GEOMETRY_FINITE
    if (!recovery::numbers(g.origin) || !recovery::numbers(g.ex) || !recovery::numbers(g.ey) ||
        !recovery::numbers(g.ez) || !recovery::numbers(g.faceY) || !recovery::numbers(g.faceZ)) return false;
#endif
    if (g.id<0 || g.reserved || !(g.faceY[0]>0) || !(g.faceZ[2]>0) ||
        g.faceY[1]!=-g.faceY[0] || g.faceZ[3]!=-g.faceZ[2] ||
        g.faceY[2]!=0 || g.faceY[3]!=0 || g.faceZ[0]!=0 || g.faceZ[1]!=0) return false;
    const double* axes[]={g.ex,g.ey,g.ez};
    for (int i=0;i<3;++i) for (int j=i;j<3;++j)
        if (std::abs(dot(axes[i],axes[j])-(i==j?1.:0.))>1e-9) return false;
    for (int k=0;k<3;++k) {
        const int i=(k+1)%3,j=(k+2)%3;
        if (std::abs(g.ex[i]*g.ey[j]-g.ex[j]*g.ey[i]-g.ez[k])>1e-9) return false;
    }
    return true;
}
}} // namespace bsi::geometry
