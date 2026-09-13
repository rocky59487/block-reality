#pragma once
#include "bsi_corot_pack.hpp"
#include <set>
namespace bsi { namespace corot_wire {
inline bool tensorMaterial(Pack& pack,const bsi_corot_shell_material_view* ptr,uint32_t shellCount,bool retired=false) {
    if(!ptr)return true;
    if(reinterpret_cast<uintptr_t>(ptr)%alignof(bsi_corot_shell_material_view)||ptr->struct_size<offsetof(bsi_corot_shell_material_view,nPoints)+sizeof(ptr->nPoints)||ptr->reserved)return false;
    const auto& m=*ptr;
    if(!span(m.profiles,m.nProfiles)||!span(m.layers,m.nLayers)||!span(m.points,m.nPoints)||m.nProfiles>shellCount)return false;
    std::set<int> seen;uint32_t layers=0,points=0;
    for(uint32_t i=0;i<m.nProfiles;++i){const auto& p=m.profiles[i];const double values[]={p.E,p.nu,p.yield,p.isotropic,p.kinematic,p.storedEnergy,p.dissipatedEnergy,p.workPotential};
        if(p.shell<0||uint32_t(p.shell)>=shellCount||!seen.insert(p.shell).second||(p.available!=0&&p.available!=6&&p.available!=7)||!finite(values)||p.E<=0||p.nu<=-1||p.nu>=.5||p.yield<=0||p.isotropic<0||p.kinematic<0||p.storedEnergy<0||p.dissipatedEnergy<0||p.workPotential<0||
           p.layerFirst!=layers||!p.layerCount||!range(layers,p.layerCount,m.nLayers)||p.pointFirst!=points||!range(points,p.pointCount,m.nPoints)||p.pointCount!=(p.available?4ull*p.layerCount:0))return false;
        double moments[3]{},previous=-1;
        for(uint32_t n=0;n<p.layerCount;++n){const auto& l=m.layers[layers+n];if(!std::isfinite(l.position)||!std::isfinite(l.weight)||l.weight<=0||l.position<-.5||l.position>.5||l.position<=previous)return false;
            previous=l.position;moments[0]+=l.weight;moments[1]+=l.weight*l.position;moments[2]+=l.weight*l.position*l.position;}
        if(std::abs(moments[0]-1)>1e-12||std::abs(moments[1])>1e-12||std::abs(moments[2]-1./12)>1e-12)return false;
        for(uint32_t n=0;n<p.pointCount;++n){const auto& q=m.points[points+n];
            if(!finite(q.strain)||!finite(q.plastic)||!finite(q.stress)||!finite(q.tangent)||!nonnegative(q.accumulated)||!nonnegative(q.dissipation)||!nonnegative(q.storedEnergy)||!nonnegative(q.workPotential)||q.loading>1||q.reserved)return false;
            if(std::abs(q.plastic[0]+q.plastic[1]+q.plastic[2])>2e-10*std::max(1.,q.accumulated)||
               std::abs(q.dissipation-p.yield*q.accumulated)>2e-10*std::max(1.,q.dissipation))return false;
            double e=0;for(double v:q.strain)e=std::max(e,std::abs(v));
            if(std::abs(q.stress[2])>2e-12*(p.yield+p.E*e)||q.stress[4]!=0||q.stress[5]!=0)return false;
        }
        layers+=p.layerCount;points+=p.pointCount;
    }
    if(layers!=m.nLayers||points!=m.nPoints)return false;
    // All three record layouts consist exclusively of initialized scalar fields.
    return pack.raw(retired?"corotRetiredShellProfiles":"corotShellProfiles",m.profiles,m.nProfiles)&&
        pack.raw(retired?"corotRetiredShellLayers":"corotShellLayers",m.layers,m.nLayers)&&
        pack.raw(retired?"corotRetiredTensorPoints":"corotTensorPoints",m.points,m.nPoints);
}
inline bool tensorMaterial(Pack& pack,const bsi_corot_view& v){
    return v.struct_size<offsetof(bsi_corot_view,shellMaterial)+sizeof(v.shellMaterial)||tensorMaterial(pack,v.shellMaterial,v.nShells);
}
inline bool retiredTensorMaterial(Pack& pack,const bsi_corot_fracture_view& v){
    return v.struct_size<offsetof(bsi_corot_fracture_view,retiredShellMaterial)+sizeof(v.retiredShellMaterial)||tensorMaterial(pack,v.retiredShellMaterial,v.nRetiredShells,true);
}
inline bool tensorRequested(const bsi_corot_view& v,const Input& input){
    if(input.shellLaws.empty())return true;
    if(v.struct_size<offsetof(bsi_corot_view,shellMaterial)+sizeof(v.shellMaterial))return false;
    uint32_t expected=0;for(uint32_t i=0;i<v.nShells;++i)if(v.shells[i].active){bool selected=false;
        for(const auto& l:input.shellLaws)selected=selected||v.shells[i].material==l.material;
        if(!selected)continue;++expected;bool found=false;if(v.shellMaterial)for(uint32_t p=0;p<v.shellMaterial->nProfiles;++p)found=found||v.shellMaterial->profiles[p].shell==int32_t(i);if(!found)return false;}
    return !v.shellMaterial?expected==0:v.shellMaterial->nProfiles==expected;
}
}} // namespace bsi::corot_wire
