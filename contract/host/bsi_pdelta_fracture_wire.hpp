#pragma once
#include "bsi_pdelta_wire.hpp"
namespace bsi { namespace pdelta_wire {
inline bsi_pdelta_fracture_options fractureOptions(const json::Value& body,const Input& input) {
    bsi_pdelta_fracture_options o{};o.struct_size=sizeof(o);o.analysis=input.options;
    o.request=fracture_wire::id(*body.find("requestId"));o.budget=uint32_t(body.find("budget")->i64);o.tier=BSI_TIER_COMMIT;
    if(const auto* initial=body.find("initialAnalysis")){o.useInitial=1;o.initialAnalysis=token(*initial);}
    return o;
}
inline bool fracture(const bsi_pdelta_fracture_view* ptr,const bsi_pdelta_fracture_options& o,const Input& input,
                     bsi_id128 ns,const std::vector<bsi_block>& world,const std::vector<bsi_artifact_owner>& owners,
                     const VocabStore& vocab,fracture_wire::Candidate& candidate,Pack& pack,
                     uint32_t& rollbackFirst,uint32_t& rollbackCount,std::string& error) {
    if(!ptr||ptr->struct_size<offsetof(bsi_pdelta_fracture_view,nLoads)+sizeof(ptr->nLoads))return false;
    const auto& v=*ptr;
    if(v.options.struct_size<offsetof(bsi_pdelta_fracture_options,useInitial)+sizeof(v.options.useInitial)||
       v.options.analysis.struct_size<offsetof(bsi_pdelta_options,route)+sizeof(v.options.analysis.route)||
       !same(v.options.analysis,o.analysis)||!fracture_wire::same(v.options.request,o.request)||v.options.budget!=o.budget||v.options.tier!=o.tier||
       v.options.useInitial!=o.useInitial||!same(v.options.initialAnalysis,o.initialAnalysis)||v.end>3||v.qualification>7||v.flags>1||
       !span(v.decisions,v.nDecisions)||!span(v.decisionCells,v.nDecisionCells)||!span(v.islands,v.nIslands)||!span(v.loads,v.nLoads))return false;
    bsi_fracture_options base{};base.struct_size=sizeof(base);base.expected=o.analysis.expected;base.request=o.request;
    base.budget=o.budget+1;base.tier=o.tier;base.numThreads=o.analysis.numThreads;std::copy_n(o.analysis.gravity,3,base.gravity);
    if(!fracture_wire::receipt(v.physical,base,ns,world,owners,vocab.materials,candidate,pack.table,pack.bytes,error))return false;
    const auto& p=*v.physical;const bool pending=v.pending>=0;
    if(p.nEvents>o.budget||v.nDecisions!=p.nEvents+uint32_t(pending)||(pending?v.pending!=int32_t(p.nEvents):v.pending!=-1)||
       v.nLoads!=input.loads.size()||!range(v.rollbackIslandFirst,v.rollbackIslandCount,v.nIslands)||
       p.steps+v.flags<p.nEvents||p.steps+v.flags>p.nEvents+1||((v.flags&1)&&!o.useInitial))return false;
    const uint32_t expectedEnd=(p.flags&2)?BSI_PDF_UNSTABLE_CUT_ROLLED_BACK:(p.flags&1)?BSI_PDF_BUDGET:v.qualification?BSI_PDF_UNQUALIFIED:BSI_PDF_WITHIN_CAPACITY;
    if(v.end!=expectedEnd||pending!=bool(p.flags)||((p.flags&1)&&p.nEvents!=o.budget))return false;
    uint32_t cells=0,islands=0;
    for(uint32_t i=0;i<v.nDecisions;++i){const auto& d=v.decisions[i];
        if(d.microStep!=i||d.member<0||d.cellFirst!=cells||!d.cellCount||!range(d.cellFirst,d.cellCount,v.nDecisionCells)||
           d.islandFirst!=islands||!range(d.islandFirst,d.islandCount,v.nIslands)||!peak(d.capacity,true)||d.capacity.value<1||d.face<1||d.face>5)return false;
        for(uint8_t reserved:d.reserved)if(reserved)return false;
        for(uint32_t j=0;j<d.islandCount;++j){const auto& s=v.islands[d.islandFirst+j];
            if(s.status!=BSI_PD_CONVERGED||!s.residualAvailable||s.residual>o.analysis.tolerance)return false;}
        std::set<uint32_t> seen;
        for(uint32_t j=0;j<d.cellCount;++j){const uint32_t cell=v.decisionCells[d.cellFirst+j];
            if(cell>=p.nCells||!seen.insert(cell).second||p.cells[cell].group!=(i<p.nEvents?1u:0u))return false;
            if(i<p.nEvents){const auto& e=p.events[i];if(e.cellCount!=d.cellCount||e.face!=d.face||e.utilization!=d.capacity.value||p.eventCells[e.cellFirst+j]!=cell)return false;}
            else if(p.flags&2){if(p.nMechanismCells!=d.cellCount)return false;const auto& b=p.cells[cell].source;const auto* xyz=p.mechanismXyz+size_t(j)*3;if(xyz[0]!=b.x||xyz[1]!=b.y||xyz[2]!=b.z)return false;}
        }
        cells+=d.cellCount;islands+=d.islandCount;
    }
    if(cells!=v.nDecisionCells||islands!=v.rollbackIslandFirst||islands+v.rollbackIslandCount!=v.nIslands)return false;
    bool unstable=false;
    for(uint32_t i=0;i<v.rollbackIslandCount;++i){const auto& d=v.islands[v.rollbackIslandFirst+i];
        if(d.status!=BSI_PD_CONVERGED&&d.status!=BSI_PD_UNSTABLE)return false;
        unstable|=d.status==BSI_PD_UNSTABLE;}
    if((v.end==BSI_PDF_UNSTABLE_CUT_ROLLED_BACK)!=unstable)return false;
    for(uint32_t i=0;i<v.nLoads;++i){const auto& l=v.loads[i];
        if(l.cell>=p.nCells||std::memcmp(&l.input,&input.loads[i],sizeof(bsi_load))||l.group!=p.cells[l.cell].group||fracture_wire::key(l.input)!=fracture_wire::key(p.cells[l.cell].source))return false;}
    bool unrepresented=false;for(uint32_t i=0;i<p.nCells;++i)unrepresented|=(p.cells[i].flags&1)!=0;
    if(unrepresented!=bool(v.qualification&BSI_PDF_UNREPRESENTED_MATERIAL))return false;
    if(!v.rollbackReason||(v.end==BSI_PDF_UNSTABLE_CUT_ROLLED_BACK?!*v.rollbackReason:*v.rollbackReason!=0))return false;
    return options(pack,o.analysis)&&pack.raw("pdeltaDecisions",v.decisions,v.nDecisions)&&pack.raw("pdeltaDecisionCells",v.decisionCells,v.nDecisionCells)&&
        diagnostics(pack,v.islands,v.nIslands,v.rollbackReason,&rollbackFirst,&rollbackCount)&&pack.raw("pdeltaFractureLoads",v.loads,v.nLoads);
}
}} // namespace bsi::pdelta_wire
