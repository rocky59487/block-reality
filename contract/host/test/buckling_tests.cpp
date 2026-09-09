// Contract oracle: a fixed truth table, independent of engine mechanics.
#include "../bsi_reply.hpp"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <vector>
static int checks = 0, failures = 0;
static void check(const std::string& id, bool ok) {
    ++checks; if (!ok) ++failures;
    std::printf("%s: %s\n", id.c_str(), ok ? "PASS" : "FAIL");
}
// Indices: computed, no-positive, not-eligible, scale, failed.
static const int truth[5][5] = {{0,0,2,3,4},{0,1,2,3,4},{2,2,2,2,4},{3,3,2,3,4},{4,4,4,4,4}};
static const uint8_t states[] = {0,1,2,3,5};
static const char* names[] = {"computed","no-positive-eigenvalue","not-eligible","not-eligible-scale","solver-failed"};
static double factor(uint8_t s, int id = 0) { return s == 0 ? .5 + id : std::nan(""); }
static bsi::ReplyBuilder base(unsigned islands) {
    bsi::ReplyBuilder w(0,0,0); const double z[3] = {};
    w.blocks(nullptr,0); w.equilibrium(z,z,0); w.quality(0,0,1,0,0); w.diag(0,0,0,islands,0,0);
    return w;
}
static std::vector<uint8_t> records(const bsi::ReplyBuilder& w) {
    for (const auto& s : w.sections()) if (s.name == "buckling")
        return {w.payload().begin() + s.offset, w.payload().begin() + s.offset + s.bytes};
    return {};
}
static void invalid(const std::string& id, bsi::ReplyBuilder w, uint8_t mode) {
    std::string why;
    check(id, !w.finalizeSolve(why,mode) && !why.empty() && w.payload().empty() && w.sections().empty());
}
int main() {
    for (int a=0;a<5;++a) for (int b=0;b<5;++b) for (int c=0;c<5;++c) {
        int row[] = {a,b,c}; std::vector<uint8_t> first;
        for (int reverse=0;reverse<2;++reverse) {
            auto w=base(3);
            for (int k=0;k<3;++k) { int id=reverse ? 2-k : k; w.buckling(id,states[row[id]],1,factor(states[row[id]],id)); }
            std::string why, label="aggregate-"+std::to_string(a)+std::to_string(b)+std::to_string(c)+"-"+std::to_string(reverse);
            check(label,w.finalizeSolve(why,1) && w.bucklingState(1)==names[truth[truth[a][b]][c]]);
            const auto raw=records(w);
            if (!reverse) first=raw;
            else check(label+"-canonical",raw==first);
        }
    }
    for (uint8_t mode : {0,1,2}) {
        auto w=base(0); std::string why;
        check("empty-"+std::to_string(mode), w.finalizeSolve(why,mode) && w.bucklingState(mode)==(mode==0 ? "disabled-by-request" : "not-eligible"));
    }
    for (uint8_t mode : {1,2}) {
        auto w=base(1); w.buckling(0,0,mode,.5); std::string why;
        check("kind-valid-"+std::to_string(mode),w.finalizeSolve(why,mode));
    }
    auto disabled=base(2); disabled.buckling(1,4,0,std::nan("")); disabled.buckling(0,4,0,std::nan("")); std::string why;
    check("disabled-complete",disabled.finalizeSolve(why,0) && disabled.bucklingState(0)=="disabled-by-request");
    // Native record bytes for a pre-existing valid none response remain unchanged.
    auto raw=records(disabled); bool exact=raw.size()==32;
    for (int id=0;id<2 && exact;++id) {
        int32_t got; double f; std::memcpy(&got,raw.data()+16*id,4); std::memcpy(&f,raw.data()+16*id+8,8);
        exact=got==id && raw[16*id+4]==4 && raw[16*id+5]==0 && raw[16*id+6]==0 && raw[16*id+7]==0 && std::isnan(f);
    }
    check("disabled-layout",exact);
    invalid("missing",base(1),1);
    for (int id : {-1,0,2}) {
        auto w=base(2); w.buckling(0,1,1,std::nan("")); w.buckling(id,1,1,std::nan(""));
        invalid("collection-"+std::to_string(id),w,1);
    }
    auto extra=base(0); extra.buckling(0,4,0,std::nan("")); invalid("extra",extra,0);
    for (uint8_t kind : {0,2,255}) {
        auto w=base(1); w.buckling(0,1,kind,std::nan("")); invalid("kind-"+std::to_string(kind),w,1);
    }
    for (uint8_t state : {6,255}) {
        auto w=base(1); w.buckling(0,state,1,std::nan("")); invalid("state-"+std::to_string(state),w,1);
    }
    for (double f : {0.,-1.,std::nan(""),std::numeric_limits<double>::infinity(),-std::numeric_limits<double>::infinity()}) {
        auto w=base(1); w.buckling(0,0,1,f); invalid("computed-factor-"+std::to_string(checks),w,1);
    }
    for (uint8_t state : {1,2,3,4,5}) for (double f : {0.,.5,std::numeric_limits<double>::infinity()}) {
        auto w=base(1); w.buckling(0,state,state==4?0:1,f); invalid("inactive-factor-"+std::to_string(state)+"-"+std::to_string(checks),w,state==4?0:1);
    }
    auto activeDisabled=base(1); activeDisabled.buckling(0,4,1,std::nan("")); invalid("active-disabled",activeDisabled,1);
    auto noneActive=base(1); noneActive.buckling(0,1,0,std::nan("")); invalid("none-active",noneActive,0);
    invalid("invalid-request-mode",base(0),255);
    // A later failed finalization must discard bytes from an earlier success.
    auto repeated=base(1); repeated.buckling(0,1,1,std::nan(""));
    check("repeat-valid",repeated.finalizeSolve(why,1)); repeated.buckling(0,1,1,std::nan("")); invalid("repeat-atomic",repeated,1);
    std::printf("BUCKLING-HOST %s checks=%d failures=%d\n",failures ? "FAIL" : "PASS",checks,failures);
    return failures ? 1 : 0;
}
