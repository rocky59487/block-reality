#pragma once
#include "bsi_corot_validate.hpp"

namespace bsi { namespace corot_wire {
inline bool phaseValid(const bsi_corot_phase_failure& p) {
    const double values[]={p.coordinate,p.value,p.limit,p.utilization,p.failedArea,p.totalArea,p.fraction,p.requiredFraction};
    return p.flags<=3&&p.mode<=3&&p.station>=0&&p.fiber>=-1&&finite(values)&&p.coordinate>=0&&p.coordinate<=1&&p.value>=0&&p.limit>=0&&p.utilization>=0&&
        p.failedArea>=0&&p.totalArea>0&&p.failedArea<=p.totalArea&&p.fraction>=0&&p.fraction<=1&&p.requiredFraction>0&&p.requiredFraction<=1&&(!(p.flags&2)||(p.flags&1));
}
inline bool archiveValid(const bsi_corot_fracture_view& v,bsi_id128 ns) {
    if(!span(v.archives,v.nArchives)||!span(v.retiredMembers,v.nRetiredMembers)||!span(v.retiredShells,v.nRetiredShells)||!span(v.retiredCells,v.nRetiredCells)||!span(v.archiveOwners,v.nArchiveOwners)||!materialValid(v.retiredMaterial))return false;
    uint32_t members=0,shells=0,owners=0,cells=0;std::vector<bool> profiles(v.retiredMaterial.nProfiles);
    for(uint32_t i=0;i<v.nArchives;++i){const auto& a=v.archives[i];
        if(a.flags>7||a.reserved||!same(a.artifactNamespace,ns)||!fracture_wire::nonzero(a.after.domain)||a.after.revision<0||((a.flags&1)&&(!same(a.before.domain,a.after.domain)||a.before.revision>a.after.revision))||
           a.memberFirst!=members||a.shellFirst!=shells||a.removedFirst!=owners||!range(members,a.memberCount,v.nRetiredMembers)||!range(shells,a.shellCount,v.nRetiredShells)||!range(owners,a.removedCount,v.nArchiveOwners)||!finite(a.energy)||
           !fracture_wire::physical(a.beforeMass)||!fracture_wire::physical(a.retainedMass)||!fracture_wire::physical(a.afterMass)||!fracture_wire::physical(a.removedMass)||!fracture_wire::physical(a.installedMass))return false;
        for(double e:a.energy)if(e<0)return false;
        owners+=a.removedCount;if(a.installedFirst!=owners||!range(owners,a.installedCount,v.nArchiveOwners))return false;owners+=a.installedCount;
        for(uint32_t j=0;j<a.memberCount;++j){const auto& m=v.retiredMembers[members+j];if(m.archive!=i||m.member<0||m.island<0||m.reserved||m.cellFirst!=cells||!range(cells,m.cellCount,v.nRetiredCells)||!memberValid(m.mechanics)||!finite(m.referenceMaterial)||!finite(m.referenceSection)||!finite(m.referenceVector))return false;cells+=m.cellCount;
            for(int end=0;end<2;++end)if(!finite(m.referenceNodes[end])||!finite(m.position[end])||!motion_wire::rotation(m.orientation[end])||(m.releases[end].available&&!jointValid(m.releases[end])))return false;
            if(m.profile!=-1){if(m.profile<0||uint32_t(m.profile)>=v.retiredMaterial.nProfiles||profiles[m.profile]||v.retiredMaterial.profiles[m.profile].member!=m.member)return false;profiles[m.profile]=true;}}
        for(uint32_t j=0;j<a.shellCount;++j){const auto& s=v.retiredShells[shells+j];if(s.archive!=i||s.shell<0||s.island<0||s.reserved||s.cellFirst!=cells||!range(cells,s.cellCount,v.nRetiredCells)||!shellValid(s.mechanics)||!finite(s.referenceMaterial))return false;cells+=s.cellCount;
            for(int end=0;end<4;++end)if(!finite(s.referenceNodes[end])||!finite(s.position[end])||!motion_wire::rotation(s.orientation[end]))return false;}
        members+=a.memberCount;shells+=a.shellCount;
    }
    if(members!=v.nRetiredMembers||shells!=v.nRetiredShells||owners!=v.nArchiveOwners||cells!=v.nRetiredCells||std::find(profiles.begin(),profiles.end(),false)!=profiles.end())return false;
    for(uint32_t i=0;i<v.nRetiredCells;++i)if(v.retiredCells[i].artifact<=0)return false;
    for(uint32_t i=0;i<v.nArchiveOwners;++i)if(v.archiveOwners[i].artifact<=0)return false;
    return true;
}
inline bool fracture(const bsi_corot_fracture_view* ptr,const Input& input,bsi_id128 ns,const std::vector<bsi_block>& world,
                     const std::vector<bsi_artifact_owner>& owners,const VocabStore& vocab,fracture_wire::Candidate& candidate,Pack& pack,std::string& error) {
    if(!ptr||ptr->struct_size<offsetof(bsi_corot_fracture_view,retiredMaterial)+sizeof(ptr->retiredMaterial))return false;
    const auto& v=*ptr;const auto& o=input.fracture;
    if(v.flags>15||v.end>2||!span(v.decisions,v.nDecisions)||!span(v.phases,v.nPhases)||!span(v.failures,v.nFailures)||!span(v.decisionCells,v.nDecisionCells)||!span(v.loads,v.nLoads)||!archiveValid(v,ns))return false;
    bsi_fracture_options base{};base.struct_size=sizeof(base);base.expected=o.analysis.options.expected;base.request=o.request;base.budget=o.budget+1;base.tier=BSI_TIER_COMMIT;
    if(!fracture_wire::receipt(v.physical,base,ns,world,owners,vocab.materials,candidate,pack.table,pack.bytes,error))return false;
    const auto& p=*v.physical;const bool pending=(v.flags&1)!=0;
    if(p.nEvents>o.budget||v.nDecisions!=p.nEvents+uint32_t(pending)||v.pending!=(pending?int32_t(p.nEvents):-1)||v.nLoads!=input.cellLoads.size()||((v.flags&2)&&!o.useInitial))return false;
    uint32_t cells=0,phases=0;
    for(uint32_t i=0;i<v.nDecisions;++i){const auto& d=v.decisions[i];
        if(d.microStep!=i||d.member<0||d.station<0||!std::isfinite(d.coordinate)||d.coordinate<0||d.coordinate>1||!nonnegative(d.utilization)||d.utilization<1||d.cellFirst!=cells||!d.cellCount||!range(cells,d.cellCount,v.nDecisionCells)||d.phaseFirst!=phases||!range(phases,d.phaseCount,v.nPhases))return false;
        for(uint32_t j=0;j<d.cellCount;++j){const auto cell=v.decisionCells[cells+j];if(cell>=p.nCells||p.cells[cell].group!=(i<p.nEvents?1u:0u))return false;
            if(i<p.nEvents){const auto& e=p.events[i];if(e.cellCount!=d.cellCount||e.utilization!=d.utilization||p.eventCells[e.cellFirst+j]!=cell)return false;}}
        cells+=d.cellCount;phases+=d.phaseCount;}
    if(cells!=v.nDecisionCells)return false;
    for(uint32_t i=0;i<v.nFailures;++i){const auto& f=v.failures[i];if(f.member!=int32_t(i)||f.station<-1||f.flags>7||f.reserved||f.phaseFirst!=phases||!range(phases,f.phaseCount,v.nPhases)||!nonnegative(f.coordinate)||f.coordinate>1||!nonnegative(f.utilization)||((f.flags&4)&&(!(f.flags&1)||f.station<0||f.utilization<1)))return false;phases+=f.phaseCount;}
    if(phases!=v.nPhases)return false;
    for(uint32_t i=0;i<v.nPhases;++i)if(!phaseValid(v.phases[i]))return false;
    for(uint32_t i=0;i<v.nLoads;++i){const auto& l=v.loads[i];if(l.cell>=p.nCells||std::memcmp(&l.input,&input.cellLoads[i],sizeof(bsi_load))||l.group!=p.cells[l.cell].group||fracture_wire::key(l.input)!=fracture_wire::key(p.cells[l.cell].source))return false;}
    bool unrepresented=false;for(uint32_t i=0;i<p.nCells;++i)unrepresented=unrepresented||bool(p.cells[i].flags&1);if(bool(v.flags&4)!=unrepresented)return false;
    auto remainingOptions=o.analysis.options;remainingOptions.expected=p.after;
    if(!analysis(v.remaining,remainingOptions,ns,candidate.remaining,candidate.owners,vocab,pack,false)||!tensorRequested(*v.remaining,input)||v.nFailures!=v.remaining->nMembers)return false;
    return records(pack,"corotDecisions",v.decisions,v.nDecisions)&&records(pack,"corotPhases",v.phases,v.nPhases)&&records(pack,"corotFailures",v.failures,v.nFailures)&&pack.raw("corotDecisionCells",v.decisionCells,v.nDecisionCells)&&
        pack.raw("corotFractureLoads",v.loads,v.nLoads)&&records(pack,"corotArchives",v.archives,v.nArchives)&&records(pack,"corotRetiredMembers",v.retiredMembers,v.nRetiredMembers)&&records(pack,"corotRetiredShells",v.retiredShells,v.nRetiredShells)&&
        pack.raw("corotRetiredCells",v.retiredCells,v.nRetiredCells)&&pack.raw("corotArchiveOwners",v.archiveOwners,v.nArchiveOwners)&&material(pack,v.retiredMaterial,true)&&retiredTensorMaterial(pack,v);
}
inline bool motionResult(const bsi_corot_motion_view* ptr,const Motion& d,const fracture_wire::Candidate& receipt,Pack& pack) {
    if(!ptr||ptr->struct_size<offsetof(bsi_corot_motion_view,source)+sizeof(ptr->source))return false;
    const auto& v=*ptr;const auto& o=d.input;
    if(!v.source||v.source->struct_size<offsetof(bsi_corot_fracture_view,retiredMaterial)+sizeof(v.source->retiredMaterial)||!v.source->physical||v.source->physical->struct_size<offsetof(bsi_fracture_view,flags)+sizeof(v.source->physical->flags)||
       !same(v.source->physical->token,o.fracture)||!same(receipt.token,o.fracture)||!receipt.committed||!v.geometry||v.geometry->struct_size<offsetof(bsi_motion_geometry_view,nTriangles)+sizeof(v.geometry->nTriangles)||
       v.nStates!=o.nBodies||v.nEnergies!=v.nStates||v.geometry->nBodies!=o.nBodies||!span(v.states,v.nStates)||!span(v.energies,v.nEnergies)||!span(v.geometry->bodies,v.geometry->nBodies)||!archiveValid(*v.source,v.source->physical->artifactNamespace))return false;
    const auto& p=*v.source->physical;if(!span(p.fragments,p.nFragments)||!span(p.cells,p.nCells))return false;
    motion_wire::Declaration geometry;geometry.input.scene=o.scene;geometry.input.nBodies=o.nBodies;geometry.input.chordTolerance=o.chordTolerance;geometry.input.maxColliders=o.maxColliders;geometry.input.maxVertices=o.maxVertices;
    for(uint32_t i=0;i<o.nBodies;++i){const auto& b=d.bodies[i];const auto& s=v.states[i];const auto& e=v.energies[i];const auto& g=v.geometry->bodies[i];
        if(b.fragment>=p.nFragments||s.id!=b.id||s.revision!=b.revision||s.time!=b.time||!finite(s.position)||!finite(s.linearMomentum)||!finite(s.angularMomentum)||!motion_wire::rotation(s.orientation)||
           e.id!=b.id||!nonnegative(e.kinetic)||!nonnegative(e.rigidKinetic)||!nonnegative(e.internalKinetic)||!nonnegative(e.estimatedError)||e.samples>o.maxSamples||!fracture_wire::physical(g.physical)||
           std::abs(g.physical.mass-p.fragments[b.fragment].physical.mass)>2e-9*std::max(1.,g.physical.mass))return false;
        for(int j=0;j<3;++j)if(g.physical.center[j]!=s.position[j])return false;
        if(std::abs(e.kinetic-e.rigidKinetic-e.internalKinetic)>2e-9*std::max(1.,e.kinetic))return false;
        bsi_motion_source source{};source.id=b.id;source.physical=g.physical;source.cellFirst=uint32_t(geometry.cells.size());
        for(uint32_t j=0;j<p.nCells;++j)if(p.cells[j].group==b.fragment+2)geometry.cells.push_back(p.cells[j]);
        source.cellCount=uint32_t(geometry.cells.size())-source.cellFirst;geometry.bodies.push_back(source);}
    if(!motion_wire::geometry(v.geometry,geometry,pack.table,pack.bytes))return false;
    return pack.raw("motionStates",v.states,v.nStates)&&records(pack,"corotBodyEnergies",v.energies,v.nEnergies)&&records(pack,"corotArchives",v.source->archives,v.source->nArchives)&&
        records(pack,"corotRetiredMembers",v.source->retiredMembers,v.source->nRetiredMembers)&&records(pack,"corotRetiredShells",v.source->retiredShells,v.source->nRetiredShells)&&
        pack.raw("corotRetiredCells",v.source->retiredCells,v.source->nRetiredCells)&&pack.raw("corotArchiveOwners",v.source->archiveOwners,v.source->nArchiveOwners)&&material(pack,v.source->retiredMaterial,true)&&retiredTensorMaterial(pack,*v.source);
}
}} // namespace bsi::corot_wire
