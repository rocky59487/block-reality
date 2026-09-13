#pragma once
#include "bsi_pdelta_pack.hpp"
#include "bsi_vocab.hpp"
namespace bsi { namespace pdelta_wire {
struct Input {
    bsi_pdelta_options options{};
    std::vector<bsi_load> loads;
};
inline bool input(const json::Value& body,const uint8_t* payload,size_t n,uint32_t threads,Input& out) {
    auto& o=out.options;o.struct_size=sizeof(o);o.expected=fracture_wire::stamp(*body.find("expected"));
    o.loadFactor=body.find("loadFactor")->num;o.tolerance=body.find("tolerance")->num;
    for(size_t i=0;i<3;++i)o.gravity[i]=body.find("gravity")->arr[i].num;
    o.selfWeight=uint8_t(body.find("selfWeight")->b);o.route=body.find("route")->str=="frozen"?1:0;
    o.budgetDof=uint32_t(body.find("budgetDof")->i64);o.maxIterations=uint32_t(body.find("maxIterations")->i64);
    o.numThreads=body.find("numThreads")?uint32_t(body.find("numThreads")->i64):threads;
    const uint32_t count=uint32_t(body.find("loads")->i64);
    if(!fracture_wire::nonzero(o.expected.domain)||!std::isfinite(o.loadFactor)||!finite(o.gravity)||
       !std::isfinite(o.tolerance)||!(o.tolerance>0&&o.tolerance<1)||uint64_t(count)*64!=n||n>kLimit||(n&&!payload))return false;
    out.loads.resize(count);if(n)std::memcpy(out.loads.data(),payload,n);
    for(const auto& f:out.loads)if(f.flags||!finite(f.f)||!zero(f.m))return false;
    return true;
}
inline bool same(const bsi_analysis_token& a,const bsi_analysis_token& b) {return fracture_wire::same(a.context,b.context)&&a.sequence==b.sequence;}
inline bool same(const bsi_pdelta_options& a,const bsi_pdelta_options& b) {
    return fracture_wire::same(a.expected,b.expected)&&a.loadFactor==b.loadFactor&&a.gravity[0]==b.gravity[0]&&a.gravity[1]==b.gravity[1]&&a.gravity[2]==b.gravity[2]&&
        a.tolerance==b.tolerance&&a.budgetDof==b.budgetDof&&a.numThreads==b.numThreads&&a.maxIterations==b.maxIterations&&a.selfWeight==b.selfWeight&&a.route==b.route;
}
inline void token(json::Writer& w,const bsi_analysis_token& t) {fracture_wire::writeToken(w,{t.context,t.sequence});}
inline bsi_analysis_token token(const json::Value& value){const auto t=fracture_wire::token(value);return {t.context,t.sequence};}
inline bool islandIndex(int32_t id,uint32_t count,bool inactive=false){return (id>=0&&uint32_t(id)<count)||(inactive&&id==-1);}
inline bool fields(const bsi_pdelta_view& v,const Input& input) {
    for(uint32_t i=0;i<v.nIslands;++i){const auto& s=v.islands[i];
        if(s.status>3||s.residualAvailable>1||s.indicativeShell>1||!nonnegative(s.residual)||!nonnegative(s.pivotRatio)||s.threadsUsed>256||
           (s.status==BSI_PD_CONVERGED&&(!s.residualAvailable||s.residual>input.options.tolerance)))return false;
    }
    for(uint32_t i=0;i<v.nNodes;++i){const auto& n=v.nodes[i];
        if(!islandIndex(n.island,v.nIslands)||!finite(n.position)||!finite(n.displacement)||!finite(n.reaction)||
           n.available!=(v.islands[n.island].status==BSI_PD_CONVERGED?uint32_t(BSI_PD_SOLVED):0u))return false;
        for(uint8_t fixed:n.fixed)if(fixed>1)return false;
        if(!n.available&&(!zero(n.displacement)||!zero(n.reaction)))return false;
    }
    uint32_t cursor=0;
    for(uint32_t i=0;i<v.nMembers;++i){const auto& m=v.members[i];
        if(m.active>1||!islandIndex(m.island,v.nIslands,!m.active)||m.available>31||
           !range(m.sourceFirst,m.sourceCount,v.nSourceIndices)||m.sourceFirst!=cursor||!finite(m.displacement)||!finite(m.endAction)||
           !std::isfinite(m.length)||!(m.length>0)||!std::isfinite(m.strengthScale)||!(m.strengthScale>0)||m.capacityFace>5||m.mode>6||m.fibre>6)return false;
#ifndef BSI_TEST_NPD_QUALIFICATION
        if(!peak(m.elastic,(m.available&BSI_PD_ELASTIC)!=0)||!peak(m.capacity,(m.available&BSI_PD_CAPACITY)!=0))return false;
#endif
        cursor+=m.sourceCount;
        if(m.geometry.id!=int32_t(i)||m.geometry.reserved||!finite(m.geometry.origin)||!finite(m.geometry.ex)||!finite(m.geometry.ey)||!finite(m.geometry.ez)||!finite(m.geometry.faceY)||!finite(m.geometry.faceZ))return false;
        for(int32_t node:m.nodes)if(node<0||uint32_t(node)>=v.nNodes)return false;
#ifndef BSI_TEST_NPD_QUALIFICATION
        const bool solved=m.active&&v.islands[m.island].status==BSI_PD_CONVERGED;
        if(bool(m.available&BSI_PD_SOLVED)!=solved||((m.available&BSI_PD_CAPACITY_COMPLETE)&&!(m.available&BSI_PD_CAPACITY))||
           (!solved&&(m.available||!zero(m.displacement)||!zero(m.endAction))))return false;
#endif
    }
    for(uint32_t i=0;i<v.nShells;++i){const auto& s=v.shells[i];
        if(s.active>1||!islandIndex(s.island,v.nIslands,!s.active)||!range(s.sourceFirst,s.sourceCount,v.nSourceIndices)||s.sourceFirst!=cursor||
           !std::isfinite(s.thickness)||!(s.thickness>0)||!finite(s.displacement)||!finite(s.endAction))return false;
        cursor+=s.sourceCount;for(int32_t node:s.nodes)if(node<0||uint32_t(node)>=v.nNodes)return false;
        const bool solved=s.active&&v.islands[s.island].status==BSI_PD_CONVERGED;
        if(s.available!=(solved?uint32_t(BSI_PD_SOLVED):0u)||(!solved&&(!zero(s.displacement)||!zero(s.endAction))))return false;
    }
    return cursor==v.nSourceIndices;
}
inline bool sourceMap(const bsi_pdelta_view& v,const std::vector<bsi_block>& world,const std::vector<bsi_artifact_owner>& owners,const VocabStore& vocab) {
    using Key=fracture_wire::Key;std::map<Key,size_t> present;std::map<Key,int64_t> ownership;
    for(size_t i=0;i<world.size();++i)present.emplace(fracture_wire::key(world[i]),i);
    // owner20 is packed; copy its 64-bit field before binding a map reference.
    for(const auto& o:owners){const int64_t artifact=o.artifact;ownership.emplace(fracture_wire::key(o),artifact);}
    std::set<Key> seen;size_t found=0;
    for(uint32_t i=0;i<v.nSources;++i){const auto& s=v.sources[i];const auto key=fracture_wire::key(s.block);
        if(!seen.insert(key).second||s.weightDisposition>BSI_PD_WEIGHT_NONE||s.block.mat<0||size_t(s.block.mat)>=vocab.materials.size())return false;
        const auto p=present.find(key);const auto o=ownership.find(key);
        if(p==present.end()){if(s.weightDisposition!=BSI_PD_WEIGHT_RETIRED||s.artifact)return false;continue;}
        if(s.weightDisposition==BSI_PD_WEIGHT_RETIRED||std::memcmp(&s.block,&world[p->second],sizeof(bsi_block))||s.artifact!=(o==ownership.end()?0:uint64_t(o->second)))return false;
        ++found;
    }
    if(found!=world.size())return false;
    for(uint32_t i=0;i<v.nSourceIndices;++i)if(v.sourceIndices[i]>=v.nSources)return false;
    std::map<uint64_t,std::pair<std::set<int32_t>,std::set<int32_t>>> expectedLinks;
    const auto associate=[&](uint32_t first,uint32_t count,int32_t element,bool shell){
        for(uint32_t j=0;j<count;++j){const auto id=v.sources[v.sourceIndices[first+j]].artifact;
            if(id)(shell?expectedLinks[id].second:expectedLinks[id].first).insert(element);}
    };
    for(uint32_t i=0;i<v.nMembers;++i){const auto& m=v.members[i];
        if(!m.sourceCount){if(m.material!=-1||m.section!=-1)return false;}
        else {
            if(m.material<0||size_t(m.material)>=vocab.materials.size()||m.section<0||size_t(m.section)>=vocab.sections.size())return false;
            const auto& first=v.sources[v.sourceIndices[m.sourceFirst]].block;
            if(m.material!=first.mat||m.section!=(first.sect<0?vocab.materials[size_t(first.mat)].defaultSection:first.sect))return false;
            if(m.active)associate(m.sourceFirst,m.sourceCount,int32_t(i),false);
        }
    }
    for(uint32_t i=0;i<v.nShells;++i){const auto& s=v.shells[i];
        if(!s.sourceCount){if(s.material!=-1)return false;}
        else {
            if(s.material!=v.sources[v.sourceIndices[s.sourceFirst]].block.mat)return false;
            if(s.active)associate(s.sourceFirst,s.sourceCount,int32_t(i),true);
        }
    }
    if(expectedLinks.size()!=v.nArtifacts)return false;
    uint32_t members=0,shells=0;uint64_t previous=0;
    for(uint32_t i=0;i<v.nArtifacts;++i){const auto& a=v.artifacts[i];
        if(!a.artifact||a.artifact<=previous||a.artifact>uint64_t(INT64_MAX)||a.memberFirst!=members||a.shellFirst!=shells||
           !range(a.memberFirst,a.memberCount,v.nArtifactMembers)||!range(a.shellFirst,a.shellCount,v.nArtifactShells))return false;
        previous=a.artifact;members+=a.memberCount;shells+=a.shellCount;
        const auto expected=expectedLinks.find(a.artifact);
        if(expected==expectedLinks.end()||expected->second.first.size()!=a.memberCount||expected->second.second.size()!=a.shellCount)return false;
        const auto linked=[&](uint32_t first,uint32_t count){for(uint32_t j=0;j<count;++j)if(v.sources[v.sourceIndices[first+j]].artifact==a.artifact)return true;return false;};
        int32_t last=-1;
        for(uint32_t j=0;j<a.memberCount;++j){const int32_t e=v.artifactMembers[a.memberFirst+j];if(e<=last||e<0||uint32_t(e)>=v.nMembers)return false;last=e;
            if(!v.members[e].active||!expected->second.first.count(e)||!linked(v.members[e].sourceFirst,v.members[e].sourceCount))return false;}
        last=-1;
        for(uint32_t j=0;j<a.shellCount;++j){const int32_t e=v.artifactShells[a.shellFirst+j];if(e<=last||e<0||uint32_t(e)>=v.nShells)return false;last=e;
            if(!v.shells[e].active||!expected->second.second.count(e)||!linked(v.shells[e].sourceFirst,v.shells[e].sourceCount))return false;}
    }
    return members==v.nArtifactMembers&&shells==v.nArtifactShells;
}
inline bool analysis(const bsi_pdelta_view* ptr,const Input& input,bsi_id128 ns,const std::vector<bsi_block>& world,
                     const std::vector<bsi_artifact_owner>& owners,const VocabStore& vocab,Pack& pack) {
    if(!ptr||ptr->struct_size<offsetof(bsi_pdelta_view,nLoads)+sizeof(ptr->nLoads))return false;
    const auto& v=*ptr;const auto& o=input.options;
    if(!fracture_wire::same(v.basis,o.expected)||!fracture_wire::same(v.artifactNamespace,ns)||!fracture_wire::nonzero(v.token.context)||!v.token.sequence||
       v.loadFactor!=o.loadFactor||v.selfWeight!=o.selfWeight||v.gravity[0]!=o.gravity[0]||v.gravity[1]!=o.gravity[1]||v.gravity[2]!=o.gravity[2]||v.nLoads!=input.loads.size()||
       !span(v.islands,v.nIslands)||!span(v.nodes,v.nNodes)||!span(v.members,v.nMembers)||!span(v.shells,v.nShells)||!span(v.sources,v.nSources)||
       !span(v.sourceIndices,v.nSourceIndices)||!span(v.artifacts,v.nArtifacts)||!span(v.artifactMembers,v.nArtifactMembers)||!span(v.artifactShells,v.nArtifactShells)||!span(v.loads,v.nLoads))return false;
    for(uint32_t i=0;i<v.nLoads;++i)if(std::memcmp(&v.loads[i],&input.loads[i],sizeof(bsi_load)))return false;
    if(!fracture_wire::physical(v.physical))return false;
    if(!fields(v,input))return false;
#ifndef BSI_TEST_NPD_MAPPING
    if(!sourceMap(v,world,owners,vocab))return false;
#else
    (void)world;(void)owners;(void)vocab;
#endif
    std::array<bsi_physical_properties,7> physical;physical[0]=v.physical;
    for(size_t i=0;i<6;++i){if(!fracture_wire::physical(v.weightGroups[i]))return false;physical[i+1]=v.weightGroups[i];}
    return options(pack,o)&&pack.raw("pdeltaPhysical",physical.data(),7)&&diagnostics(pack,v.islands,v.nIslands)&&nodes(pack,v.nodes,v.nNodes)&&members(pack,v.members,v.nMembers)&&
        shells(pack,v.shells,v.nShells)&&sources(pack,v.sources,v.nSources)&&pack.raw("pdeltaSourceIndices",v.sourceIndices,v.nSourceIndices)&&
        pack.raw("pdeltaArtifacts",v.artifacts,v.nArtifacts)&&pack.raw("pdeltaArtifactMembers",v.artifactMembers,v.nArtifactMembers)&&pack.raw("pdeltaArtifactShells",v.artifactShells,v.nArtifactShells)&&pack.raw("pdeltaLoads",v.loads,v.nLoads);
}
}} // namespace bsi::pdelta_wire
