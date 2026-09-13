#pragma once
#include "bsi_corot_pack.hpp"

namespace bsi { namespace corot_wire {
inline bool law(const bsi_corot_law& l) {
    const double values[]={l.E,l.tension,l.compression,l.kinematic,l.isotropic,l.fractureEnergyT,l.fractureEnergyC,l.compressionPlasticity};
    if(l.reserved||l.kind>2||!finite(values)||l.E<=0||l.tension<=0||l.compression<=0||l.kinematic<0||l.isotropic<0)return false;
    return l.kind==0?l.fractureEnergyT==0&&l.fractureEnergyC==0&&l.compressionPlasticity==0:
        l.kinematic==0&&l.isotropic==0&&l.fractureEnergyT>0&&l.fractureEnergyC>0&&l.compressionPlasticity>=0&&l.compressionPlasticity<=1&&(l.kind==2||l.compressionPlasticity==0);
}
inline bool materialValid(const bsi_corot_material_view& m) {
    if(!span(m.profiles,m.nProfiles)||!span(m.fibers,m.nFibers)||!span(m.stations,m.nStations)||!span(m.points,m.nPoints))return false;
    uint32_t fiber=0,station=0,point=0;
    for(uint32_t i=0;i<m.nProfiles;++i){const auto& p=m.profiles[i];
        if(p.member<0||p.available>7||p.reserved||p.physicalSection>1||!nonnegative(p.area)||!nonnegative(p.Iy)||!nonnegative(p.Iz)||!nonnegative(p.storedEnergy)||!nonnegative(p.dissipatedEnergy)||!nonnegative(p.workPotential)||
           !p.fiberCount||!p.stationCount||p.fiberFirst!=fiber||p.stationFirst!=station||p.pointFirst!=point||!range(fiber,p.fiberCount,m.nFibers)||!range(station,p.stationCount,m.nStations)||!range(point,p.pointCount,m.nPoints)||
           (p.available?(p.available&6)!=6||uint64_t(p.fiberCount)*p.stationCount!=p.pointCount:p.pointCount!=0))return false;
        if(p.physicalSection&&(p.area<=0||p.Iy<=0||p.Iz<=0))return false;
        if(!p.available&&(p.storedEnergy!=0||p.dissipatedEnergy!=0||p.workPotential!=0))return false;
        double previous=-1;
        for(uint32_t j=0;j<p.stationCount;++j){const auto& s=m.stations[station+j];if(!std::isfinite(s.station)||s.station<=previous||s.station>1||!std::isfinite(s.weight)||s.weight<=0||!finite(s.section)||(!p.available&&!zero(s.section)))return false;previous=s.station;}
        fiber+=p.fiberCount;station+=p.stationCount;point+=p.pointCount;
    }
    if(fiber!=m.nFibers||station!=m.nStations||point!=m.nPoints)return false;
    for(uint32_t i=0;i<m.nFibers;++i){const auto& f=m.fibers[i];if(f.reserved||!std::isfinite(f.y)||!std::isfinite(f.z)||!std::isfinite(f.area)||f.area<=0||!law(f.law))return false;}
    for(uint32_t i=0;i<m.nPoints;++i){const auto& p=m.points[i];const double values[]={p.strain,p.plasticStrain,p.accumulatedPlastic,p.plasticDissipation,p.maximumT,p.maximumC,p.damageDissipation,p.stress,p.tangent,p.storedEnergy,p.dissipatedEnergy,p.workPotential,p.damageT,p.damageC};
        if(p.reserved||p.loading<-1||p.loading>1||!finite(values)||p.accumulatedPlastic<0||p.plasticDissipation<0||p.maximumT<0||p.maximumC<0||p.damageDissipation<0||p.storedEnergy<0||p.dissipatedEnergy<0||p.workPotential<0||p.damageT<0||p.damageT>1||p.damageC<0||p.damageC>1)return false;}
    return true;
}
inline bool memberValid(const bsi_corot_member& m) {
    if(m.available>7||m.active>1||!nonnegative(m.length)||!nonnegative(m.storedEnergy)||!finite(m.frame)||!finite(m.localDisplacement)||!finite(m.localAction)||!finite(m.action)||!finite(m.external))return false;
    return !(m.available&BSI_CO_SOLVED)||(m.length>0&&motion_wire::rotation(m.frame));
}
inline bool shellValid(const bsi_corot_shell& s) {
    if(s.available>3||s.active>1||!nonnegative(s.thickness)||!nonnegative(s.area)||!nonnegative(s.storedEnergy)||!finite(s.frame)||!finite(s.localDisplacement)||!finite(s.localAction)||!finite(s.action)||!finite(s.external))return false;
    for(const auto& section:s.sections)if(!finite(section))return false;
    return !(s.available&BSI_CO_SOLVED)||(s.thickness>0&&s.area>0&&motion_wire::rotation(s.frame));
}
inline bool jointValid(const bsi_corot_joint& j) {
    if(j.kind>4||j.available>3||j.index<0||!finite(j.position)||!finite(j.orientation)||!finite(j.relativeOrientation)||!finite(j.coordinate)||!finite(j.action))return false;
    return !j.available||j.kind>1||(motion_wire::rotation(j.orientation)&&motion_wire::rotation(j.relativeOrientation));
}
inline bool optionsMatch(const bsi_corot_options& a,const bsi_corot_options& b) {
    if(a.struct_size<offsetof(bsi_corot_options,angularCells)+sizeof(a.angularCells))return false;
    auto x=a,y=b;x.struct_size=y.struct_size=sizeof(a);Pack left,right;
    return records(left,"options",&x,1)&&records(right,"options",&y,1)&&left.bytes==right.bytes;
}
inline bool analysis(const bsi_corot_view* ptr,const bsi_corot_options& options,bsi_id128 ns,const std::vector<bsi_block>& world,
                     const std::vector<bsi_artifact_owner>& owners,const VocabStore& vocab,Pack& pack,bool current=true) {
    if(!ptr||ptr->struct_size<offsetof(bsi_corot_view,material)+sizeof(ptr->material))return false;
    const auto& v=*ptr;
    if(v.fullyConverged>1||!same(v.basis,options.expected)||!same(v.artifactNamespace,ns)||!optionsMatch(v.options,options)||
       (current&&(!fracture_wire::nonzero(v.token.context)||!v.token.sequence))||!span(v.islands,v.nIslands)||!span(v.nodes,v.nNodes)||!span(v.members,v.nMembers)||!span(v.shells,v.nShells)||
       !span(v.joints,v.nJoints)||!span(v.sources,v.nSources)||!span(v.sourceIndices,v.nSourceIndices)||!span(v.artifacts,v.nArtifacts)||!span(v.artifactMembers,v.nArtifactMembers)||!span(v.artifactShells,v.nArtifactShells)||!materialValid(v.material))return false;
    bool converged=true;
    for(uint32_t i=0;i<v.nIslands;++i){const auto& s=v.islands[i];
        if(s.status>4||s.flags>7||!nonnegative(s.forceResidual)||!nonnegative(s.momentResidual)||std::isnan(s.historyError)||s.historyError<0||!std::isfinite(s.historyLoadFactor)||!nonnegative(s.storedEnergy)||!nonnegative(s.dissipatedEnergy))return false;
        if(s.status!=0&&s.status!=4)converged=false;else if(!std::isfinite(s.historyError))return false;}
    if(bool(v.fullyConverged)!=converged)return false;
    for(uint32_t i=0;i<v.nNodes;++i){const auto& n=v.nodes[i];
        if(!pdelta_wire::islandIndex(n.island,v.nIslands)||n.available!=(v.islands[n.island].status==0?1u:0u)||!finite(n.reference)||!finite(n.position)||!finite(n.reaction)||!finite(n.applied))return false;
        for(auto b:n.fixed)if(b>1)return false;
        for(auto b:n.reserved)if(b)return false;
        if(n.available?!motion_wire::rotation(n.orientation):!zero(n.position)||!zero(n.orientation)||!zero(n.reaction)||!zero(n.applied))return false;}
    uint32_t cursor=0;std::vector<bool> profiles(v.material.nProfiles);
    for(uint32_t i=0;i<v.nMembers;++i){const auto& m=v.members[i];
        if(!memberValid(m)||!pdelta_wire::islandIndex(m.island,v.nIslands,!m.active)||m.sourceFirst!=cursor||!range(m.sourceFirst,m.sourceCount,v.nSourceIndices))return false;
        cursor+=m.sourceCount;
        for(int n:m.nodes)if(n<0||uint32_t(n)>=v.nNodes)return false;
        if(bool(m.available&BSI_CO_SOLVED)!=(m.active&&v.islands[m.island].status==0))return false;
        if(m.profile!=-1){if(m.profile<0||uint32_t(m.profile)>=v.material.nProfiles||profiles[m.profile])return false;profiles[m.profile]=true;const auto& p=v.material.profiles[m.profile];if(p.member!=int32_t(i)||p.available!=(m.available&7))return false;}
        else if(m.available&6)return false;}
    if(std::find(profiles.begin(),profiles.end(),false)!=profiles.end())return false;
    for(uint32_t i=0;i<v.nShells;++i){const auto& s=v.shells[i];if(!shellValid(s)||!pdelta_wire::islandIndex(s.island,v.nIslands,!s.active)||s.sourceFirst!=cursor||!range(s.sourceFirst,s.sourceCount,v.nSourceIndices))return false;cursor+=s.sourceCount;
        for(int n:s.nodes)if(n<0||uint32_t(n)>=v.nNodes)return false;
        if(bool(s.available&1)!=(s.active&&v.islands[s.island].status==0))return false;}
    if(cursor!=v.nSourceIndices||!pdelta_wire::sourceMap(v,world,owners,vocab))return false;
    for(uint32_t i=0;i<v.nJoints;++i){const auto& j=v.joints[i];if(!jointValid(j)||j.node<0||uint32_t(j.node)>=v.nNodes||(j.kind==1&&(uint32_t(j.index)>=v.nMembers||j.end<0||j.end>1)))return false;}
    std::array<bsi_physical_properties,7> mass;mass[0]=v.physical;for(size_t i=0;i<6;++i)mass[i+1]=v.weightGroups[i];for(const auto& p:mass)if(!fracture_wire::physical(p))return false;
    if(!records(pack,"corotOptions",&v.options,1)||!pack.raw("corotPhysical",mass.data(),7)||!diagnostics(pack,v.islands,v.nIslands)||!records(pack,"corotNodes",v.nodes,v.nNodes)||!records(pack,"corotMembers",v.members,v.nMembers)||!records(pack,"corotShells",v.shells,v.nShells)||!records(pack,"corotJoints",v.joints,v.nJoints))return false;
    const size_t start=pack.table.size();if(!pdelta_wire::sources(pack,v.sources,v.nSources))return false;pack.table[start].name="corotSources";
    return pack.raw("corotSourceIndices",v.sourceIndices,v.nSourceIndices)&&pack.raw("corotArtifacts",v.artifacts,v.nArtifacts)&&pack.raw("corotArtifactMembers",v.artifactMembers,v.nArtifactMembers)&&pack.raw("corotArtifactShells",v.artifactShells,v.nArtifactShells)&&material(pack,v.material);
}
}} // namespace bsi::corot_wire
