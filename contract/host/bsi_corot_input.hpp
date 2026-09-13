#pragma once
#include "bsi_pdelta_wire.hpp"
#include "bsi_motion_wire.hpp"

namespace bsi { namespace corot_wire {
using pdelta_wire::Pack;
using pdelta_wire::span;
using pdelta_wire::range;
using pdelta_wire::finite;
using pdelta_wire::nonnegative;
using pdelta_wire::zero;
using fracture_wire::same;
inline uint32_t count(const json::Value& b,const char* name) {return uint32_t(b.find(name)->i64);}
inline bsi_corot_options options(const json::Value& b,uint32_t threads) {
    bsi_corot_options o{};o.struct_size=sizeof(o);o.expected=fracture_wire::stamp(*b.find("expected"));o.loadFactor=b.find("loadFactor")->num;
    for(int i=0;i<3;++i)o.gravity[i]=b.find("gravity")->arr[size_t(i)].num;
    o.flags=(b.find("selfWeight")->b?1u:0u)|(b.find("followerLocal")->b?2u:0u)|(b.find("retainLoadPath")->b?4u:0u)|(b.find("transferTopology")->b?8u:0u)|(b.find("useMetis")->b?16u:0u);
    if(const auto* reinstall=b.find("reinstallRetired"))if(reinstall->b)o.flags|=32u;
    o.relativeTolerance=b.find("relativeTolerance")->num;o.forceTolerance=b.find("forceTolerance")->num;o.momentTolerance=b.find("momentTolerance")->num;
    o.initialStep=b.find("initialStep")->num;o.minStep=b.find("minStep")->num;o.maxStep=b.find("maxStep")->num;o.linearTolerance=b.find("linearTolerance")->num;o.pathTolerance=b.find("pathTolerance")->num;
    o.budgetDof=count(b,"budgetDof");o.numThreads=b.find("numThreads")?count(b,"numThreads"):threads;o.maxIterations=count(b,"maxIterations");o.maxAttempts=count(b,"maxAttempts");
    o.maxBacktracks=count(b,"maxBacktracks");o.linearIterations=count(b,"linearIterations");o.restart=count(b,"restart");o.pointBudget=count(b,"pointBudget");
    o.fiberCells=count(b,"fiberCells");o.radialCells=count(b,"radialCells");o.angularCells=count(b,"angularCells");return o;
}
struct Input {
    bsi_corot_request input{};
    bsi_corot_fracture_options fracture{};
    std::vector<bsi_corot_material> materials;
    std::vector<bsi_load> cellLoads;
    std::vector<bsi_corot_prescribed> prescribed;
    std::vector<bsi_corot_load> loads;
    std::vector<bsi_corot_failure_rule> rules;
};
inline bool input(const json::Value& b,const uint8_t* payload,size_t n,uint32_t threads,Input& d,bool fracture) {
    auto& o=d.input;o.struct_size=sizeof(o);o.options=options(*b.find("options"),threads);
    o.nMaterials=count(b,"materials");o.nCellLoads=count(b,"cellLoads");o.nPrescribed=count(b,"prescribed");o.nLoads=count(b,"loads");
    const uint32_t nr=fracture?count(b,"rules"):0;
    const uint64_t bytes=uint64_t(o.nMaterials)*sizeof(bsi_corot_material)+uint64_t(o.nCellLoads)*sizeof(bsi_load)+uint64_t(o.nPrescribed)*sizeof(bsi_corot_prescribed)+uint64_t(o.nLoads)*sizeof(bsi_corot_load)+uint64_t(nr)*sizeof(bsi_corot_failure_rule);
    if(bytes!=n||n>fracture_wire::kPayloadLimit||(n&&!payload)||!fracture_wire::nonzero(o.options.expected.domain))return false;
    size_t offset=0;motion_wire::read(payload,offset,o.nMaterials,d.materials);motion_wire::read(payload,offset,o.nCellLoads,d.cellLoads);
    motion_wire::read(payload,offset,o.nPrescribed,d.prescribed);motion_wire::read(payload,offset,o.nLoads,d.loads);motion_wire::read(payload,offset,nr,d.rules);
    o.materials=d.materials.data();o.cellLoads=d.cellLoads.data();o.prescribed=d.prescribed.data();o.loads=d.loads.data();
    if(fracture){auto& f=d.fracture;f.struct_size=sizeof(f);f.analysis=o;f.request=fracture_wire::id(*b.find("requestId"));f.budget=count(b,"budget");f.rules=d.rules.data();f.nRules=nr;
        if(const auto* token=b.find("initialAnalysis")){f.useInitial=1;f.initialAnalysis=pdelta_wire::token(*token);}}
    return true;
}
struct Motion {
    bsi_corot_motion_declare input{};
    std::vector<bsi_corot_body> bodies;
    std::vector<bsi_corot_velocity> velocities;
    std::vector<bsi_motion_box> terrain;
};
inline bool motion(const json::Value& b,const uint8_t* payload,size_t n,Motion& d) {
    auto& o=d.input;o.struct_size=sizeof(o);o.scene=fracture_wire::stamp(*b.find("scene"));o.fracture=fracture_wire::token(*b.find("fracture"));
    o.nBodies=count(b,"bodies");o.nVelocities=count(b,"velocities");o.nTerrain=count(b,"terrain");
    const uint64_t bytes=uint64_t(o.nBodies)*sizeof(bsi_corot_body)+uint64_t(o.nVelocities)*sizeof(bsi_corot_velocity)+uint64_t(o.nTerrain)*sizeof(bsi_motion_box);
    if(bytes!=n||n>fracture_wire::kPayloadLimit||(n&&!payload)||!fracture_wire::nonzero(o.scene.domain))return false;
    size_t offset=0;motion_wire::read(payload,offset,o.nBodies,d.bodies);motion_wire::read(payload,offset,o.nVelocities,d.velocities);motion_wire::read(payload,offset,o.nTerrain,d.terrain);
    std::sort(d.bodies.begin(),d.bodies.end(),[](const auto& a,const auto& c){return a.id<c.id;});
    o.bodies=d.bodies.data();o.velocities=d.velocities.data();o.terrain=d.terrain.data();o.chordTolerance=b.find("chordTolerance")->num;o.relativeTolerance=b.find("relativeTolerance")->num;o.absoluteTolerance=b.find("absoluteTolerance")->num;
    o.maxBodies=count(b,"maxBodies");o.maxCells=count(b,"maxCells");o.maxColliders=count(b,"maxColliders");o.maxVertices=count(b,"maxVertices");o.maxRefinements=count(b,"maxRefinements");o.maxSamples=count(b,"maxSamples");return true;
}
}} // namespace bsi::corot_wire
