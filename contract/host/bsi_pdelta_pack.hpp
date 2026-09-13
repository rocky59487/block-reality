#pragma once
#include "bsi_fracture_wire.hpp"
namespace bsi { namespace pdelta_wire {
constexpr size_t kLimit=fracture_wire::kPayloadLimit;
template<class T> bool span(const T* p,uint32_t n) {return (!n||p)&&uint64_t(n)*sizeof(T)<=kLimit;}
template<size_t N> bool finite(const double (&v)[N]) {for(double x:v)if(!std::isfinite(x))return false;return true;}
template<size_t N> bool zero(const double (&v)[N]) {for(double x:v)if(x!=0)return false;return true;}
inline bool nonnegative(double x) {return std::isfinite(x)&&x>=0;}
inline bool range(uint32_t first,uint32_t count,uint32_t size){return first<=size&&count<=size-first;}
inline bool peak(const bsi_pdelta_peak& p,bool available) {
    return available?(nonnegative(p.value)&&std::isfinite(p.fraction)&&p.fraction>=0&&p.fraction<=1&&
        (p.side==-1||p.side==1)&&p.surface<6&&finite(p.forces)):
        (p.value==0&&p.fraction==0&&p.side==0&&p.surface==0&&zero(p.forces));
}
struct Pack {
    std::vector<SectionInfo> table;
    std::vector<uint8_t> bytes;
    bool begin(const char* name,uint32_t count,size_t stride,size_t& offset) {
        const uint16_t endian=1;if(*reinterpret_cast<const uint8_t*>(&endian)!=1)return false;
        const uint64_t n=uint64_t(count)*stride;
        if(n>kLimit-bytes.size())return false;
        offset=bytes.size();table.push_back({name,offset,size_t(n),count});bytes.resize(offset+size_t(n),0);return true;
    }
    void field(size_t offset,const void* data,size_t n) {if(n)std::memcpy(bytes.data()+offset,data,n);}
    template<class T> bool raw(const char* name,const T* data,uint32_t count) {
        size_t offset;if(!span(data,count)||!begin(name,count,sizeof(T),offset))return false;
        field(offset,data,size_t(count)*sizeof(T));return true;
    }
};
inline bool options(Pack& pack,const bsi_pdelta_options& input) {
    bsi_pdelta_options_record row{};row.loadFactor=input.loadFactor;std::copy_n(input.gravity,3,row.gravity);
    row.tolerance=input.tolerance;row.budgetDof=input.budgetDof;row.numThreads=input.numThreads;row.maxIterations=input.maxIterations;
    row.selfWeight=input.selfWeight;row.route=input.route;return pack.raw("pdeltaOptions",&row,1);
}
#define BSI_PD_FIELD(type,fieldName) pack.field(offset+offsetof(type,fieldName),&row.fieldName,sizeof(row.fieldName))
inline bool nodes(Pack& pack,const bsi_pdelta_node* rows,uint32_t count) {
    size_t start;if(!span(rows,count)||!pack.begin("pdeltaNodes",count,136,start))return false;
    for(uint32_t i=0;i<count;++i){const auto& row=rows[i];const size_t offset=start+size_t(i)*136;
        BSI_PD_FIELD(bsi_pdelta_node,island);BSI_PD_FIELD(bsi_pdelta_node,available);BSI_PD_FIELD(bsi_pdelta_node,position);
        BSI_PD_FIELD(bsi_pdelta_node,displacement);BSI_PD_FIELD(bsi_pdelta_node,reaction);BSI_PD_FIELD(bsi_pdelta_node,fixed);}
    return true;
}
inline bool members(Pack& pack,const bsi_pdelta_member* rows,uint32_t count) {
    size_t start;if(!span(rows,count)||!pack.begin("pdeltaMembers",count,560,start))return false;
    for(uint32_t i=0;i<count;++i){const auto& row=rows[i];const size_t offset=start+size_t(i)*560;
        BSI_PD_FIELD(bsi_pdelta_member,island);BSI_PD_FIELD(bsi_pdelta_member,nodes);BSI_PD_FIELD(bsi_pdelta_member,material);BSI_PD_FIELD(bsi_pdelta_member,section);
        BSI_PD_FIELD(bsi_pdelta_member,available);BSI_PD_FIELD(bsi_pdelta_member,sourceFirst);BSI_PD_FIELD(bsi_pdelta_member,sourceCount);
        BSI_PD_FIELD(bsi_pdelta_member,length);BSI_PD_FIELD(bsi_pdelta_member,strengthScale);BSI_PD_FIELD(bsi_pdelta_member,geometry);
        BSI_PD_FIELD(bsi_pdelta_member,displacement);BSI_PD_FIELD(bsi_pdelta_member,endAction);BSI_PD_FIELD(bsi_pdelta_member,elastic);BSI_PD_FIELD(bsi_pdelta_member,capacity);
        BSI_PD_FIELD(bsi_pdelta_member,mode);BSI_PD_FIELD(bsi_pdelta_member,fibre);BSI_PD_FIELD(bsi_pdelta_member,capacityFace);BSI_PD_FIELD(bsi_pdelta_member,active);}
    return true;
}
inline bool shells(Pack& pack,const bsi_pdelta_shell* rows,uint32_t count) {
    size_t start;if(!span(rows,count)||!pack.begin("pdeltaShells",count,440,start))return false;
    for(uint32_t i=0;i<count;++i){const auto& row=rows[i];const size_t offset=start+size_t(i)*440;
        BSI_PD_FIELD(bsi_pdelta_shell,island);BSI_PD_FIELD(bsi_pdelta_shell,nodes);BSI_PD_FIELD(bsi_pdelta_shell,material);BSI_PD_FIELD(bsi_pdelta_shell,available);
        BSI_PD_FIELD(bsi_pdelta_shell,sourceFirst);BSI_PD_FIELD(bsi_pdelta_shell,sourceCount);BSI_PD_FIELD(bsi_pdelta_shell,thickness);
        BSI_PD_FIELD(bsi_pdelta_shell,displacement);BSI_PD_FIELD(bsi_pdelta_shell,endAction);BSI_PD_FIELD(bsi_pdelta_shell,active);}
    return true;
}
inline bool sources(Pack& pack,const bsi_pdelta_source* rows,uint32_t count) {
    size_t start;if(!span(rows,count)||!pack.begin("pdeltaSources",count,56,start))return false;
    for(uint32_t i=0;i<count;++i){const auto& row=rows[i];const size_t offset=start+size_t(i)*56;
        BSI_PD_FIELD(bsi_pdelta_source,block);BSI_PD_FIELD(bsi_pdelta_source,artifact);BSI_PD_FIELD(bsi_pdelta_source,weightDisposition);}
    return true;
}
inline bool station(Pack& pack,const bsi_pdelta_station& row) {
    size_t offset;if(!pack.begin("pdeltaStation",1,112,offset))return false;
    BSI_PD_FIELD(bsi_pdelta_station,available);BSI_PD_FIELD(bsi_pdelta_station,forces);BSI_PD_FIELD(bsi_pdelta_station,sigma);
    BSI_PD_FIELD(bsi_pdelta_station,shear);BSI_PD_FIELD(bsi_pdelta_station,elastic);BSI_PD_FIELD(bsi_pdelta_station,mode);BSI_PD_FIELD(bsi_pdelta_station,fibre);return true;
}
#undef BSI_PD_FIELD
inline bool diagnostics(Pack& pack,const bsi_pdelta_island* rows,uint32_t count,const char* rollback=nullptr,uint32_t* rollbackFirst=nullptr,uint32_t* rollbackCount=nullptr) {
    if(!span(rows,count))return false;
    std::vector<bsi_pdelta_island_record> records;std::vector<uint8_t> text;
    const auto append=[&](const char* s,uint32_t& first,uint32_t& length){
        first=uint32_t(text.size());length=0;if(!s)return false;
        while(length<4096&&s[length])++length;
        if(length==4096||length>kLimit-text.size())return false;
        text.insert(text.end(),s,s+length);return true;
    };
    for(uint32_t i=0;i<count;++i){const auto& d=rows[i];bsi_pdelta_island_record row{};
        if(d.status>3||d.residualAvailable>1||d.indicativeShell>1||!nonnegative(d.residual)||!nonnegative(d.pivotRatio)||d.threadsUsed>256)return false;
        row.status=d.status;row.freeDof=d.freeDof;row.iterations=d.iterations;row.factors=d.factors;row.threadsUsed=d.threadsUsed;
        row.residualAvailable=d.residualAvailable;row.indicativeShell=d.indicativeShell;row.residual=d.residual;row.pivotRatio=d.pivotRatio;
        if(!append(d.reason,row.reasonFirst,row.reasonCount))return false;
        records.push_back(row);
    }
    if(rollback){uint32_t first=0,length=0;if(!append(rollback,first,length))return false;if(rollbackFirst)*rollbackFirst=first;if(rollbackCount)*rollbackCount=length;}
    return pack.raw("pdeltaIslands",records.data(),uint32_t(records.size()))&&pack.raw("pdeltaText",text.data(),uint32_t(text.size()));
}
static_assert(sizeof(bsi_pdelta_island_record)==48&&sizeof(bsi_pdelta_node)==136&&sizeof(bsi_pdelta_member)==560&&
    sizeof(bsi_pdelta_shell)==440&&sizeof(bsi_pdelta_source)==56&&sizeof(bsi_pdelta_artifact)==24&&sizeof(bsi_pdelta_station)==112&&
    sizeof(bsi_pdelta_options_record)==56&&sizeof(bsi_pdelta_sample_record)==16,"P-Delta wire layouts");
}} // namespace bsi::pdelta_wire
