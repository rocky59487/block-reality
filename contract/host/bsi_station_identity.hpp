// Identity validation only. Physics and governing choice belong to the engine.
#pragma once
#include "../bsi_engine.h"
#include <cmath>
#include <vector>
#if (defined(BSI_TEST_IDENTITY_PAIR) || defined(BSI_TEST_IDENTITY_ORDER) || defined(BSI_TEST_IDENTITY_VALIDATE) || defined(BSI_TEST_IDENTITY_GOVERNING) || defined(BSI_TEST_IDENTITY_LAYOUT)) && !defined(BSI_HOST_TEST_MUTATIONS)
#error "BSI identity mutation requires BSI_HOST_TEST_MUTATIONS; never ship"
#endif
namespace bsi { namespace identity {
static_assert(sizeof(bsi_station_identity)==16,"stationIdentity 16 B");
inline bool valid(const std::vector<bsi_member_result>& members,const std::vector<bsi_station>& stations,
                  const std::vector<bsi_station_identity>& ids) {
#ifndef BSI_TEST_IDENTITY_PAIR
    if(ids.size()!=stations.size()) return false;
#endif
    // Keep memory-safe even when the count check is deliberately escaped.
    if(ids.size()<stations.size()) return false;
    size_t cursor=0;int32_t previous=-1;
    for(const auto& m:members) {
#ifndef BSI_TEST_IDENTITY_PAIR
        if(m.id<=previous || m.stationFirst!=cursor || uint64_t(m.stationFirst)+m.stationCount>stations.size()) return false;
#endif
        if(uint64_t(m.stationFirst)+m.stationCount>stations.size()) return false;
        unsigned governing=0;
        for(uint32_t k=0;k<m.stationCount;++k) {
            const size_t j=size_t(m.stationFirst)+k;const auto& p=ids[j];
#ifndef BSI_TEST_IDENTITY_VALIDATE
            if(!std::isfinite(p.s) || p.s<0 || p.s>1 || (p.side!=-1 && p.side!=1) || (p.flags&~1u) || p.reserved || p.reserved2) return false;
#endif
#ifndef BSI_TEST_IDENTITY_PAIR
            if(p.s!=stations[j].s) return false;
#endif
#ifndef BSI_TEST_IDENTITY_ORDER
            if(k && (p.s<ids[j-1].s || (p.s==ids[j-1].s && (ids[j-1].side!=-1 || p.side!=1)))) return false;
#endif
            if(p.flags&1u) {
                ++governing;
#ifndef BSI_TEST_IDENTITY_GOVERNING
                if(p.s!=m.governingS || governing>1) return false;
#endif
            }
        }
        cursor+=m.stationCount;previous=m.id;
    }
#ifndef BSI_TEST_IDENTITY_PAIR
    if(cursor!=stations.size()) return false;
#endif
    (void)previous;return true;
}
}} // namespace bsi::identity
