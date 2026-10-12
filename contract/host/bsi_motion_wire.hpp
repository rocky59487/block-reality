// Motion wire ownership and structural validation. All physics stays in ABI4.
#pragma once
#include "bsi_fracture_wire.hpp"

namespace bsi { namespace motion_wire {
using fracture_wire::same;
using fracture_wire::nonzero;
using fracture_wire::Key;
constexpr size_t kPayloadLimit = fracture_wire::kPayloadLimit;
inline bool positive(uint64_t n) { return n && n <= uint64_t(INT64_MAX); }
template<size_t N> bool finite(const double (&v)[N]) {
    for (double x : v) if (!std::isfinite(x)) return false;
    return true;
}
inline bool nonnegative(double x) { return std::isfinite(x) && x >= 0; }
inline bool rotation(const double (&q)[4]) {
    return finite(q) && std::abs(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]-1) <= 1e-8;
}
template<class T> void read(const uint8_t* payload, size_t& offset, uint32_t count, std::vector<T>& rows) {
    rows.resize(count);
    if (count) std::memcpy(rows.data(), payload + offset, size_t(count) * sizeof(T));
    offset += size_t(count) * sizeof(T);
}
struct Declaration {
    bsi_motion_declare input{};
    std::vector<bsi_motion_source> bodies;
    std::vector<bsi_fracture_cell> cells;
    std::vector<bsi_motion_box> terrain;
};
inline uint32_t count(const json::Value& b, const char* name) { return uint32_t(b.find(name)->i64); }
inline bool declaration(const json::Value& body, const uint8_t* payload, size_t n, Declaration& d) {
    auto& o = d.input; o.struct_size = sizeof o; o.scene = fracture_wire::stamp(*body.find("scene"));
    o.nBodies = count(body,"bodies"); o.nCells = count(body,"cells"); o.nTerrain = count(body,"terrain");
    o.chordTolerance = body.find("chordTolerance")->num;
    o.maxBodies = count(body,"maxBodies"); o.maxCells = count(body,"maxCells");
    o.maxColliders = count(body,"maxColliders"); o.maxVertices = count(body,"maxVertices");
    const uint64_t bytes = uint64_t(o.nBodies)*192 + uint64_t(o.nCells)*144 + uint64_t(o.nTerrain)*120;
    if (bytes != n || n > kPayloadLimit || !nonzero(o.scene.domain) ||
        o.nBodies > o.maxBodies || o.nCells > o.maxCells || o.nTerrain > o.maxColliders) return false;
    size_t offset = 0;
    read(payload,offset,o.nBodies,d.bodies); read(payload,offset,o.nCells,d.cells); read(payload,offset,o.nTerrain,d.terrain);
    std::sort(d.bodies.begin(),d.bodies.end(),[](const bsi_motion_source& a,const bsi_motion_source& b){return a.id<b.id;});
    std::vector<bool> used(o.nCells,false);
    for (size_t i=0;i<d.bodies.size();++i) {
        const auto& b=d.bodies[i];
        if (!positive(b.id) || (i && d.bodies[i-1].id==b.id) || !b.cellCount ||
            b.cellFirst>o.nCells || b.cellCount>o.nCells-b.cellFirst) return false;
        for (uint32_t j=b.cellFirst;j<b.cellFirst+b.cellCount;++j) {
            if (used[j]) return false;
            used[j]=true;
        }
    }
    if (std::find(used.begin(),used.end(),false)!=used.end()) return false;
    o.bodies=d.bodies.data(); o.cells=d.cells.data(); o.terrain=d.terrain.data(); return true;
}
struct Step {
    bsi_motion_step input{};
    std::vector<bsi_motion_state> states;
    std::vector<bsi_motion_force> forces;
    std::vector<bsi_motion_coverage_box> coverage;
    bool query=false;
};
inline bool step(const json::Value& body,const uint8_t* payload,size_t n,Step& d) {
    auto& o=d.input; o.struct_size=sizeof o; o.scene=fracture_wire::stamp(*body.find("scene"));
    o.request=fracture_wire::id(*body.find("requestId")); o.nStates=count(body,"states"); o.nForces=count(body,"forces");
    const auto* cov=body.find("coverage");const auto* query=body.find("coverageQuery");
    const uint32_t nc=cov?count(body,"coverage"):0;d.query=query&&query->b;
    if (cov&&d.query) return false;  // declare and query are mutually exclusive
    if (uint64_t(o.nStates)*128+uint64_t(o.nForces)*56+uint64_t(nc)*48!=n || n>kPayloadLimit || o.nForces>o.nStates ||
        !nonzero(o.scene.domain) || !nonzero(o.request)) return false;
    size_t offset=0; read(payload,offset,o.nStates,d.states); read(payload,offset,o.nForces,d.forces); read(payload,offset,nc,d.coverage);
    std::sort(d.states.begin(),d.states.end(),[](const bsi_motion_state& a,const bsi_motion_state& b){return a.id<b.id;});
    o.states=d.states.data(); o.forces=d.forces.data(); o.dt=body.find("dt")->num; o.maxStep=body.find("maxStep")->num;
    for (int i=0;i<3;++i) o.gravity[i]=body.find("gravity")->arr[size_t(i)].num;
    o.maxSubsteps=count(body,"maxSubsteps"); o.maxTrials=count(body,"maxTrials");
    o.maxPairs=count(body,"maxPairs"); o.maxPoints=count(body,"maxPoints");
    o.maxSurfaceTests=count(body,"maxSurfaceTests"); o.maxSweeps=count(body,"maxSweeps");
    o.enableSleep=uint8_t(body.find("enableSleep")->b);
    if (cov||d.query){o.coverage=d.coverage.data();o.nCoverage=nc;o.coverageFlags=(cov?2u:0u)|(d.query?1u:0u);}
    return true;
}
// ABI11 bsi.rigid.scene.declare: ids + length-prefixed sealed packets + terrain.
struct SceneDeclare {
    bsi_rigid_scene_declare input{};
    std::vector<uint64_t> ids;
    std::vector<uint8_t> sources;
    std::vector<bsi_motion_box> terrain;
};
inline bool sceneDeclare(const json::Value& body,const uint8_t* payload,size_t n,SceneDeclare& d) {
    auto& o=d.input;o.struct_size=sizeof(o);o.scene=fracture_wire::stamp(*body.find("scene"));
    o.nIds=count(body,"sources");o.sourceBytes=count(body,"sourceBytes");o.nTerrain=count(body,"terrain");
    const uint64_t bytes=uint64_t(o.nIds)*8+o.sourceBytes+uint64_t(o.nTerrain)*sizeof(bsi_motion_box);
    if (bytes!=n||n>kPayloadLimit||!nonzero(o.scene.domain)) return false;
    size_t offset=0;read(payload,offset,o.nIds,d.ids);
    d.sources.assign(payload+offset,payload+offset+o.sourceBytes);offset+=o.sourceBytes;
    read(payload,offset,o.nTerrain,d.terrain);
    o.ids=d.ids.data();o.sources=d.sources.data();o.terrain=d.terrain.data();
    o.chordTolerance=body.find("chordTolerance")->num;
    o.maxBodies=count(body,"maxBodies");o.maxCells=count(body,"maxCells");
    o.maxColliders=count(body,"maxColliders");o.maxVertices=count(body,"maxVertices");
    o.byteBudget=count(body,"byteBudget");o.allocationBudget=count(body,"allocationBudget");o.elementBudget=count(body,"elementBudget");
    return true;
}
struct Span { const char* name; const void* data; uint64_t count, stride; };
template<size_t N> bool pack(const Span (&spans)[N],std::vector<SectionInfo>& table,std::vector<uint8_t>& payload) {
    uint64_t bytes=0;
    for (const auto& s:spans) {
        const uint64_t length=s.count*s.stride;
        if ((s.count&&!s.data)||length>kPayloadLimit-bytes) return false;
        table.push_back({s.name,bytes,length,s.count}); bytes+=length;
    }
    const uint16_t endian=1;
    if (*reinterpret_cast<const uint8_t*>(&endian)!=1) return false;
    payload.resize(size_t(bytes));
    for (size_t i=0;i<N;++i) if (table[i].bytes)
        std::memcpy(payload.data()+table[i].offset,spans[i].data,size_t(table[i].bytes));
    return true;
}
// Shared geometry validation. When bodies/cells are NULL the caller only knows
// body ids (bsi.rigid.scene.declare: the finite material lives inside sealed
// packets), so source-cell membership and physical-record equality are skipped;
// every structural, budget and finiteness check still runs.
inline bool geometryChecked(const bsi_motion_geometry_view* ptr,const std::vector<uint64_t>& ids,
    const bsi_motion_source* bodies,const bsi_fracture_cell* cells,const bsi_world_stamp& scene,
    double chordTolerance,uint32_t maxColliders,uint32_t maxVertices) {
    if (!ptr || ptr->struct_size<offsetof(bsi_motion_geometry_view,nTriangles)+sizeof(ptr->nTriangles)) return false;
    const auto& v=*ptr;
    if (!same(v.scene,scene)||v.nBodies!=ids.size()||v.nPieces>maxColliders||v.nVertices>maxVertices||
        uint64_t(v.nTriangles)>uint64_t(maxVertices)*2 || (v.nBodies&&!v.bodies)||(v.nPieces&&!v.pieces)||
        (v.nVertices&&!v.vertices)||(v.nTriangles&&!v.triangles)) return false;
    // Bound the entire response before following any variable-length span.
    if (uint64_t(v.nBodies)*104+uint64_t(v.nPieces)*48+uint64_t(v.nVertices)*24+uint64_t(v.nTriangles)*12>kPayloadLimit) return false;
    uint32_t piece=0,vertex=0,triangle=0;
    for (uint32_t i=0;i<v.nBodies;++i) {
        const auto& b=v.bodies[i];
        if (b.id!=ids[i]||!nonnegative(b.surfaceError)||b.surfaceError>chordTolerance||
            !fracture_wire::physical(b.physical)||(bodies&&std::memcmp(&b.physical,&bodies[i].physical,sizeof b.physical))||
            b.pieceFirst!=piece||!b.pieceCount||b.pieceCount>v.nPieces-piece) return false;
        std::set<Key> owned;
        if (bodies&&cells)for (uint32_t c=bodies[i].cellFirst;c<bodies[i].cellFirst+bodies[i].cellCount;++c) owned.insert(fracture_wire::key(cells[c].source));
        uint64_t previous=0;
        for (uint32_t j=0;j<b.pieceCount;++j,++piece) {
            const auto& p=v.pieces[piece];
            if (p.body!=b.id||!p.part||p.part<=previous||p.vertexFirst!=vertex||p.triangleFirst!=triangle||
                p.vertexCount<4||p.vertexCount>v.nVertices-vertex||p.triangleCount<4||p.triangleCount>v.nTriangles-triangle) return false;
            previous=p.part;
#ifndef BSI_TEST_RMW_SOURCE
            if (cells&&!owned.count({{p.source[0],p.source[1],p.source[2]}})) return false;
#endif
            for (uint32_t k=0;k<p.vertexCount;++k) if (!finite(v.vertices[vertex+k].xyz)) return false;
            for (uint32_t k=0;k<p.triangleCount;++k) {
                const auto& t=v.triangles[triangle+k];
#ifndef BSI_TEST_RMW_INDEX
                for (auto index:t.vertex) if (index<vertex||index>=uint64_t(vertex)+p.vertexCount) return false;
                if (t.vertex[0]==t.vertex[1]||t.vertex[0]==t.vertex[2]||t.vertex[1]==t.vertex[2]) return false;
#else
                (void)t;
#endif
            }
            vertex+=p.vertexCount; triangle+=p.triangleCount;
        }
    }
    return piece==v.nPieces&&vertex==v.nVertices&&triangle==v.nTriangles;
}
inline bool geometry(const bsi_motion_geometry_view* ptr,const Declaration& d,
                     std::vector<SectionInfo>& table,std::vector<uint8_t>& payload) {
    std::vector<uint64_t> ids;ids.reserve(d.bodies.size());for(const auto& b:d.bodies)ids.push_back(b.id);
    const auto& o=d.input;
    if (!geometryChecked(ptr,ids,d.bodies.data(),d.cells.data(),o.scene,o.chordTolerance,o.maxColliders,o.maxVertices)) return false;
    const auto& v=*ptr;
    const Span spans[]={{"motionBodies",v.bodies,v.nBodies,104},{"motionPieces",v.pieces,v.nPieces,48},
        {"motionVertices",v.vertices,v.nVertices,24},{"motionTriangles",v.triangles,v.nTriangles,12}};
    return pack(spans,table,payload);
}
// bsi.rigid.scene.declare response: geometry + birth states + energies.
inline bool sceneResult(const bsi_rigid_scene_view* ptr,const SceneDeclare& d,const std::vector<uint64_t>& ids,
                        std::vector<SectionInfo>& table,std::vector<uint8_t>& payload) {
    if (!ptr||ptr->struct_size<offsetof(bsi_rigid_scene_view,nEnergies)+sizeof(ptr->nEnergies)) return false;
    const auto& v=*ptr;const auto& o=d.input;
    if (v.nStates!=ids.size()||v.nEnergies!=v.nStates||(v.nStates&&!v.states)||(v.nEnergies&&!v.energies)||
        !v.geometry||uint64_t(v.nStates)*128+uint64_t(v.nEnergies)*48>kPayloadLimit) return false;
    for (uint32_t i=0;i<v.nStates;++i) {const auto& s=v.states[i];const auto& e=v.energies[i];
        if (s.id!=ids[i]||e.id!=s.id||!std::isfinite(s.time)||s.time<0||!finite(s.position)||!rotation(s.orientation)||
            !finite(s.linearMomentum)||!finite(s.angularMomentum)||!nonnegative(e.kinetic)||!nonnegative(e.rigidKinetic)||
            !nonnegative(e.internalKinetic)||!nonnegative(e.estimatedError)||
            std::abs(e.kinetic-e.rigidKinetic-e.internalKinetic)>2e-9*std::max(1.,e.kinetic)) return false;}
    if (!geometryChecked(v.geometry,ids,nullptr,nullptr,o.scene,o.chordTolerance,o.maxColliders,o.maxVertices)) return false;
    const auto& g=*v.geometry;
    const Span spans[]={{"motionBodies",g.bodies,g.nBodies,104},{"motionPieces",g.pieces,g.nPieces,48},
        {"motionVertices",g.vertices,g.nVertices,24},{"motionTriangles",g.triangles,g.nTriangles,12},
        {"motionStates",v.states,v.nStates,128},{"corotBodyEnergies",v.energies,v.nEnergies,48}};
    return pack(spans,table,payload);
}
inline bool subset(const uint64_t* values,uint32_t count,const std::vector<uint64_t>& ids) {
    if (count>ids.size()||(count&&!values)) return false;
    for (uint32_t i=0;i<count;++i) if ((i&&values[i-1]>=values[i])||!std::binary_search(ids.begin(),ids.end(),values[i])) return false;
    return true;
}
// ABI11: suspended/required replies carry only the required coverage rows;
// no state was committed. Rows are per escaping body (suspended) or every body
// (required query), id-ordered, closed axis-aligned bounds.
inline bool suspended(const bsi_motion_step_view* ptr,const Step& d,const std::vector<uint64_t>& ids,
                      std::vector<SectionInfo>& table,std::vector<uint8_t>& payload) {
    if (!ptr||ptr->struct_size<offsetof(bsi_motion_step_view,coverageStatus)+sizeof(ptr->coverageStatus)) return false;
    const auto& v=*ptr;const auto& o=d.input;
    if (!same(v.scene,o.scene)||!same(v.request,o.request)||v.coverageStatus<2||v.coverageStatus>3||
        (d.query?v.coverageStatus!=3:v.coverageStatus!=2)||(v.coverageStatus==3&&v.nCoverage!=ids.size())||
        v.nStates||v.states||v.nSleeping||v.nWoken||v.nCoverage>ids.size()||(v.nCoverage&&!v.coverage)) return false;
    for (uint32_t i=0;i<v.nCoverage;++i) {const auto& c=v.coverage[i];
        if ((i&&v.coverage[i-1].id>=c.id)||!std::binary_search(ids.begin(),ids.end(),c.id)||
            !finite(c.minimum)||!finite(c.maximum)||c.minimum[0]>=c.maximum[0]||
            c.minimum[1]>=c.maximum[1]||c.minimum[2]>=c.maximum[2]) return false;}
    const Span spans[]={{"motionCoverage",v.coverage,v.nCoverage,56}};
    return pack(spans,table,payload);
}
inline bool result(const bsi_motion_step_view* ptr,const Step& d,const std::vector<uint64_t>& ids,
                   std::vector<SectionInfo>& table,std::vector<uint8_t>& payload) {
    if (ptr&&ptr->struct_size>=offsetof(bsi_motion_step_view,coverageStatus)+sizeof(ptr->coverageStatus)&&
        ptr->coverageStatus>=2) return suspended(ptr,d,ids,table,payload);
    if (!ptr||ptr->struct_size<offsetof(bsi_motion_step_view,fullFallback)+sizeof(ptr->fullFallback)) return false;
    const auto& v=*ptr; const auto& o=d.input;
    if (ptr->struct_size>=offsetof(bsi_motion_step_view,coverageStatus)+sizeof(ptr->coverageStatus)&&
        (v.coverageStatus>1||((o.coverageFlags&3u)&&v.coverageStatus!=1))) return false;
    if (!same(v.scene,o.scene)||!same(v.request,o.request)||v.nStates!=o.nStates||v.nStates!=ids.size()||
        (v.nStates&&!v.states)||!subset(v.sleeping,v.nSleeping,ids)||!subset(v.woken,v.nWoken,ids)) return false;
    for (uint32_t i=0;i<v.nStates;++i) {
        const auto& s=v.states[i]; const auto& before=d.states[i];
#ifndef BSI_TEST_RMW_STATE
        if (s.id!=ids[i]||before.id!=ids[i]||before.revision>=uint64_t(INT64_MAX)||s.revision!=before.revision+1||
            !std::isfinite(s.time)||s.time!=before.time+o.dt||!finite(s.position)||!rotation(s.orientation)||
            !finite(s.linearMomentum)||!finite(s.angularMomentum)) return false;
#else
        (void)s; (void)before;
#endif
    }
#ifndef BSI_TEST_RMW_REPORT
    if (v.elapsed!=o.dt||!nonnegative(v.elapsed)||!nonnegative(v.maxPenetration)||!nonnegative(v.positionCorrection)||
        !nonnegative(v.numericalEnergyRemoved)||v.fullFallback>1||v.substeps>o.maxSubsteps||v.trials>o.maxTrials) return false;
#endif
    bsi_motion_report report{};
    report.elapsed=v.elapsed; report.maxPenetration=v.maxPenetration; report.positionCorrection=v.positionCorrection;
    report.numericalEnergyRemoved=v.numericalEnergyRemoved; report.substeps=v.substeps; report.trials=v.trials;
    report.contactSolves=v.contactSolves; report.contactPoints=v.contactPoints; report.projectionSweeps=v.projectionSweeps;
    report.wakeTrials=v.wakeTrials; report.integratedBodies=v.integratedBodies; report.equilibriumSolves=v.equilibriumSolves;
    report.fullFallback=v.fullFallback;
    const Span spans[]={{"motionReport",&report,1,104},{"motionStates",v.states,v.nStates,128},
        {"motionSleeping",v.sleeping,v.nSleeping,8},{"motionWoken",v.woken,v.nWoken,8}};
    return pack(spans,table,payload);
}
inline void sections(json::Writer& w,const std::vector<SectionInfo>& table) {
    w.key("sections"); w.beginArr();
    for (const auto& s:table) {
        w.beginObj(); w.kv("name",s.name); w.kv("offset",(unsigned long long)s.offset);
        w.kv("bytes",(unsigned long long)s.bytes); w.kv("count",(unsigned long long)s.count); w.endObj();
    }
    w.endArr();
}
static_assert(sizeof(bsi_motion_report)==104&&sizeof(bsi_motion_source)==192&&sizeof(bsi_motion_box)==120&&
    sizeof(bsi_motion_state)==128&&sizeof(bsi_motion_force)==56&&sizeof(bsi_motion_body_geometry)==104&&
    sizeof(bsi_motion_piece)==48&&sizeof(bsi_motion_vertex)==24&&sizeof(bsi_motion_triangle)==12,"motion wire layout");
static_assert(sizeof(bsi_motion_coverage_box)==48&&sizeof(bsi_motion_coverage)==56&&
    sizeof(bsi_corot_body)==72&&sizeof(bsi_corot_velocity)==64&&sizeof(bsi_corot_body_energy)==48,"finite source wire layout");
}} // namespace bsi::motion_wire
