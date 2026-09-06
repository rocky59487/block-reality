// BSI_RETRY: same host gate against stub or real adapter, counted at the vtable.
#include "../bsi_host.h"
#include "../bsi_frame.hpp"
#include "../bsi_json.hpp"
#include "../../bsi_capi.h"
#include <array>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>
extern "C" uint64_t bsi_retry_test_count(uint32_t);
extern "C" uint32_t bsi_retry_test_supports_edit(void);
extern "C" void bsi_retry_test_throw_vocab(void);
using Bytes = std::vector<uint8_t>;
static int checks = 0, failures = 0;
static void check(const char* id, const char* what, bool ok) {
    ++checks; if (!ok) ++failures;
    std::printf("%s %s -> %s\n", id, what, ok ? "PASS" : "FAIL");
}
static std::array<uint64_t,4> counts() {
    return {bsi_retry_test_count(0), bsi_retry_test_count(1), bsi_retry_test_count(2), bsi_retry_test_count(3)};
}
static Bytes request(const char* method, const std::string& body, const Bytes& payload = {}, const char* id = "r1") {
    return bsi::frame::encode(0, "{\"bsi\":1,\"kind\":\"request\",\"id\":\"" + std::string(id) +
        "\",\"method\":\"" + method + "\",\"revision\":7,\"body\":" + body + "}", payload.data(), payload.size());
}
template<class T> static Bytes raw(const std::vector<T>& records) {
    const auto* p = reinterpret_cast<const uint8_t*>(records.data());
    return Bytes(p, p + records.size() * sizeof(T));
}
static bool status(const Bytes& bytes, const char* value = "ok") {
    bsi::frame::View f; bsi::json::Value h;
    if (!bsi::frame::decode(bytes.data(), bytes.size(), f) || !bsi::json::parse(f.headerStr(), h)) return false;
    const auto* v = h.find("status"); const auto* rev = h.find("revision");
    return v && v->str == value && rev && rev->i64 == 7;
}
struct Handle {
    void* p = bsi_capi_open("{\"probe\":true,\"assumeCaps\":[\"bsi.core\",\"bsi.world.edit\"],\"numThreads\":4}");
    ~Handle() { bsi_capi_close(p); }
};
static Bytes direct(void* h, const Bytes& req) {
    Bytes out(4 * 1024 * 1024);
    size_t len=0, need=0;
    int rc=bsi_capi_call(h, req.data(), req.size(), out.data(), out.size(), &len, &need);
    check("RETRY-07", "direct oracle completes without a retry", rc==BSI_CAPI_OK && len==need && len<=out.size());
    if(rc!=BSI_CAPI_OK || len>out.size()) return {};
    out.resize(len); return out;
}
static void rejected(void* h, const Bytes& req) {
    uint8_t sentinel=0xa5;
    size_t len=999, need=999;
    auto before=counts();
    int rc=bsi_capi_call(h, req.data(), req.size(), &sentinel, 0, &len, &need);
    check("RETRY-03", "different pending request refused without output or dispatch",
        rc==BSI_CAPI_PROTOCOL && len==0 && need==0 && sentinel==0xa5 && counts()==before);
}
static void retry(void* h, const Bytes& req, const Bytes& oracle, int counter, bool unsupported=false) {
    const auto before=counts();
    size_t len=999, need=999;
    int rc=bsi_capi_call(h, req.data(), req.size(), nullptr, 0, &len, &need);
    check("RETRY-01", "zero capacity returns fixed complete size", rc==BSI_CAPI_NEED_BIGGER && len==0 && need==oracle.size());
    const auto dispatched=counts();
    auto expected=before; if(counter>=0) ++expected[static_cast<size_t>(counter)];
    check("RETRY-01", "initial probe executes the vtable verb exactly once", dispatched==expected);
    for(size_t cap : {size_t(1), oracle.empty()?size_t(0):oracle.size()-1}) {
        Bytes out(oracle.size()+16,0xa5), sentinel=out;
        len=need=999;
        rc=bsi_capi_call(h, req.data(), req.size(), out.data(), cap, &len, &need);
        check("RETRY-01", "short buffer preserves every output byte and reply size",
            rc==BSI_CAPI_NEED_BIGGER && len==0 && need==oracle.size() && out==sentinel);
        check("RETRY-01", "short-buffer retry never dispatches", counts()==dispatched);
    }
    // Null output probes even with a nominally sufficient capacity.
    rc=bsi_capi_call(h, req.data(), req.size(), nullptr, oracle.size(), nullptr, nullptr);
    check("RETRY-01", "null output and optional length pointers retain pending reply", rc==BSI_CAPI_NEED_BIGGER && counts()==dispatched);
    auto changed=req; changed[2]^=2; rejected(h,changed); // same id, changed flags
    changed=req; changed.back()^=1; rejected(h,changed);  // payload/body changed, same id
    rejected(h,request("bsi.solve","{}",{},"different"));
    auto sameId=request("bsi.solve","{\"selfWeight\":false}"); rejected(h,sameId);
    std::array<int,8> codes{};
    std::vector<std::thread> threads;
    auto other=request("bsi.world.edit","{\"edits\":0}",{},"concurrent");
    for(size_t k=0;k<codes.size();++k) threads.emplace_back([&,k] {
        size_t l=1,n=1;
        codes[k]=bsi_capi_call(h,other.data(),other.size(),nullptr,0,&l,&n);
        if(l!=0 || n!=0) codes[k]=-1;
    });
    for(auto& t:threads)t.join();
    bool all=true; for(int c:codes)all=all && c==BSI_CAPI_PROTOCOL;
    check("RETRY-04", "concurrent different requests cannot overtake pending reply", all && counts()==dispatched);
    Bytes out(oracle.size()+16,0xa5);
    rc=bsi_capi_call(h,req.data(),req.size(),out.data(),oracle.size(),&len,&need);
    bool tail=true; for(size_t i=oracle.size();i<out.size();++i)tail=tail && out[i]==0xa5;
    if(len<=out.size())out.resize(len);
    check("RETRY-02", "exact-sized delivery equals independent direct reply including revision",
        rc==BSI_CAPI_OK && len==oracle.size() && need==oracle.size() && out==oracle && (unsupported || status(out)) && tail);
    check("RETRY-01", "delivery still has exactly one execution",counts()==expected);
    check("RETRY-02", "successful delivery clears last error",bsi_capi_last_error(h)==nullptr);
}
static void writeFile(const std::string& dir,const char* name,const Bytes& bytes) {
    if(dir.empty())return;
    std::ofstream out(dir+"/"+name,std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    if(!out) { std::fprintf(stderr,"cannot write fixture\n"); ++failures; }
}
int main(int argc,char** argv) {
    if(argc<2) { std::fprintf(stderr,"usage: retry_tests C4-cantilever-selfweight.json [dump-directory]\n"); return 2; }
    std::ifstream file(argv[1]); std::string text((std::istreambuf_iterator<char>(file)),{});
    bsi::json::Value fixture;
    if(!bsi::json::parse(text,fixture) || !fixture.find("vocab") || !fixture.find("blocks"))return 2;
    std::string vocab; bsi::json::serialize(*fixture.find("vocab"),vocab);
    std::vector<bsi_block> blocks;
    for(const auto& row:fixture.find("blocks")->arr) {
        bsi_block b{}; b.x=static_cast<int32_t>(row.arr[0].i64); b.y=static_cast<int32_t>(row.arr[1].i64); b.z=static_cast<int32_t>(row.arr[2].i64);
        b.mat=row.arr[3].str=="steel"?0:1; b.sect=-1; b.fill=1; b.strength=1; blocks.push_back(b);
    }
    auto hello=request("bsi.hello","{\"bsi\":1,\"client\":\"retry-gate\",\"contractSha256\":\""+std::string(bsi::contractSha256())+"\"}");
    auto v=request("bsi.vocab.declare",vocab);
    auto world=request("bsi.world.declare","{\"blocks\":"+std::to_string(blocks.size())+"}",raw(blocks));
    auto solve=request("bsi.solve","{\"selfWeight\":true}");
    bsi_edit ed{}; ed.op=BSI_EDIT_REMOVE; ed.block=blocks.back();
    auto edit=request("bsi.world.edit","{\"edits\":1}",raw(std::vector<bsi_edit>{ed}));
    Handle a,b;
    check("RETRY-06","both independent handles opened",a.p && b.p);
    bool supportsEdit=bsi_retry_test_supports_edit()!=0;
    std::printf("edit arm: %s\n",supportsEdit?"executed":"UNSUPPORTED (native slot absent)");
    for(const auto& pair:std::vector<std::pair<Bytes,int>>{{hello,-1},{v,0},{world,1},{solve,3},{edit,2}}) {
        auto oracle=direct(a.p,pair.first);
        bool unsupported=pair.second==2 && !supportsEdit;
        if(unsupported) {
            bsi::frame::View f; bsi::json::Value j;
            bool decoded=bsi::frame::decode(oracle.data(),oracle.size(),f) && bsi::json::parse(f.headerStr(),j);
            check("RETRY-02","absent native edit remains explicitly unsupported",
                decoded && j.find("code") && j.find("code")->str=="UNSUPPORTED");
        } else if(pair.second==2) check("RETRY-02","stub edit succeeds",status(oracle));
        retry(b.p,pair.first,oracle,unsupported?-1:pair.second,unsupported);
    }
    auto ra=direct(a.p,solve),rb=direct(b.p,solve);
    check("RETRY-02","edit retry produces the same world on next solve",status(ra) && ra==rb);
    auto before=counts(); direct(b.p,solve);
    auto expected=before; ++expected[3];
    check("RETRY-06","same bytes after successful delivery are a new call",counts()==expected);
    uint8_t sentinel=0xa5; size_t len=99,need=99;
    before=counts();
    int rc=bsi_capi_call(b.p,nullptr,0,&sentinel,1,&len,&need);
    check("RETRY-06","null request refused with zero lengths",rc==BSI_CAPI_PROTOCOL && !len && !need && sentinel==0xa5 && counts()==before);
    rc=bsi_capi_call(nullptr,hello.data(),hello.size(),&sentinel,1,&len,&need);
    check("RETRY-06","null handle refused with zero lengths",rc==BSI_CAPI_INVALID && !len && !need);
    auto malformed=hello; malformed[0]='?'; len=need=99;
    rc=bsi_capi_call(b.p,malformed.data(),malformed.size(),&sentinel,1,&len,&need);
    check("RETRY-06","bad magic does not dispatch",rc==BSI_CAPI_PROTOCOL && !len && !need && counts()==before);
    len=need=99;
    rc=bsi_capi_call(b.p,hello.data(),size_t(BSI_CAPI_MAX_FRAME_BYTES)+1,&sentinel,1,&len,&need);
    check("RETRY-06","oversized request refused before reading advertised bytes",rc==BSI_CAPI_PROTOCOL && !len && !need && counts()==before);
    {
        Handle abandoned; bsi_capi_call(abandoned.p,hello.data(),hello.size(),nullptr,0,&len,&need);
        Handle independent; auto fresh=direct(independent.p,hello);
        check("RETRY-04","another handle works while a reply is pending",status(fresh));
    }
    Handle after; check("RETRY-04","close with pending reply does not affect a new session",status(direct(after.p,hello)));

    Handle broken;
    check("RETRY-06","exception fixture completes handshake",status(direct(broken.p,hello)));
    before=counts(); bsi_retry_test_throw_vocab(); len=need=99;
    rc=bsi_capi_call(broken.p,v.data(),v.size(),nullptr,0,&len,&need);
    expected=before; ++expected[0];
    check("RETRY-06","uncertain dispatch failure invalidates handle without leaking an exception",
        rc==BSI_CAPI_INVALID && !len && !need && counts()==expected && bsi_capi_last_error(broken.p)!=nullptr);
    rc=bsi_capi_call(broken.p,v.data(),v.size(),nullptr,0,&len,&need);
    check("RETRY-06","failed handle never re-executes the uncertain request",
        rc==BSI_CAPI_INVALID && !len && !need && counts()==expected);

    // 500 disconnected copies of the frozen C4 fixture, separated along z.
    std::vector<bsi_block> large;
    for(int island=0;island<500;++island)for(auto block:blocks) { block.z+=3*island;large.push_back(block); }
    auto largeWorld=request("bsi.world.declare","{\"blocks\":"+std::to_string(large.size())+"}",raw(large));
    Handle largeA,largeB;
    const Bytes setup[]={hello,v,largeWorld};
    for(const auto& req:setup) {
        check("RETRY-05","large-world direct setup succeeds",status(direct(largeA.p,req)));
        check("RETRY-05","large-world retry setup succeeds",status(direct(largeB.p,req)));
    }
    auto oracle=direct(largeA.p,solve);
    check("RETRY-05","real solve fixture exceeds Java default 64 KiB",status(oracle) && oracle.size()>65536);
    retry(largeB.p,solve,oracle,3);
    std::string dump=argc>2?argv[2]:"";
    writeFile(dump,"hello.frame",hello); writeFile(dump,"vocab.frame",v); writeFile(dump,"world.frame",largeWorld);
    writeFile(dump,"solve.frame",solve); writeFile(dump,"oracle.frame",oracle);
    std::printf("BSI-RETRY %s (checks=%d failures=%d)\n",failures?"FAIL":"ALL PASS",checks,failures);
    return failures?1:0;
}
