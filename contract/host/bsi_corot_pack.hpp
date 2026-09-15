#pragma once
#include "bsi_corot_input.hpp"
namespace bsi { namespace corot_wire {
struct Field {size_t offset,size;};
template<class T,size_t N> bool copyRecords(Pack& pack,const char* name,const T* data,uint32_t count,const Field (&fields)[N]) {
    size_t start;if(!span(data,count)||!pack.begin(name,count,sizeof(T),start))return false;
    for(uint32_t i=0;i<count;++i)for(const auto& f:fields)pack.field(start+size_t(i)*sizeof(T)+f.offset,reinterpret_cast<const uint8_t*>(&data[i])+f.offset,f.size);
    return true;
}
#define BSI_CO_FIELD(f) {offsetof(T,f),sizeof(((T*)nullptr)->f)}
inline bool records(Pack& pack,const char* name,const bsi_corot_options* rows,uint32_t count) {
    using T=bsi_corot_options;const Field fields[]={
        BSI_CO_FIELD(struct_size),BSI_CO_FIELD(flags),BSI_CO_FIELD(expected),BSI_CO_FIELD(loadFactor),BSI_CO_FIELD(gravity),
        BSI_CO_FIELD(relativeTolerance),BSI_CO_FIELD(forceTolerance),BSI_CO_FIELD(momentTolerance),BSI_CO_FIELD(initialStep),BSI_CO_FIELD(minStep),
        BSI_CO_FIELD(maxStep),BSI_CO_FIELD(linearTolerance),BSI_CO_FIELD(pathTolerance),BSI_CO_FIELD(budgetDof),BSI_CO_FIELD(numThreads),
        BSI_CO_FIELD(maxIterations),BSI_CO_FIELD(maxAttempts),BSI_CO_FIELD(maxBacktracks),BSI_CO_FIELD(linearIterations),BSI_CO_FIELD(restart),
        BSI_CO_FIELD(pointBudget),BSI_CO_FIELD(fiberCells),BSI_CO_FIELD(radialCells),BSI_CO_FIELD(angularCells)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_node* rows,uint32_t count) {
    using T=bsi_corot_node;const Field fields[]={
        BSI_CO_FIELD(key),BSI_CO_FIELD(island),BSI_CO_FIELD(available),BSI_CO_FIELD(reference),BSI_CO_FIELD(position),
        BSI_CO_FIELD(orientation),BSI_CO_FIELD(reaction),BSI_CO_FIELD(applied),BSI_CO_FIELD(fixed)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_member* rows,uint32_t count) {
    using T=bsi_corot_member;const Field fields[]={
        BSI_CO_FIELD(island),BSI_CO_FIELD(nodes),BSI_CO_FIELD(material),BSI_CO_FIELD(section),BSI_CO_FIELD(profile),
        BSI_CO_FIELD(available),BSI_CO_FIELD(sourceFirst),BSI_CO_FIELD(sourceCount),BSI_CO_FIELD(active),BSI_CO_FIELD(length),
        BSI_CO_FIELD(storedEnergy),BSI_CO_FIELD(frame),BSI_CO_FIELD(localDisplacement),BSI_CO_FIELD(localAction),BSI_CO_FIELD(action),
        BSI_CO_FIELD(external)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_shell* rows,uint32_t count) {
    using T=bsi_corot_shell;const Field fields[]={
        BSI_CO_FIELD(island),BSI_CO_FIELD(nodes),BSI_CO_FIELD(material),BSI_CO_FIELD(available),BSI_CO_FIELD(sourceFirst),
        BSI_CO_FIELD(sourceCount),BSI_CO_FIELD(active),BSI_CO_FIELD(thickness),BSI_CO_FIELD(area),BSI_CO_FIELD(storedEnergy),
        BSI_CO_FIELD(frame),BSI_CO_FIELD(localDisplacement),BSI_CO_FIELD(localAction),BSI_CO_FIELD(action),BSI_CO_FIELD(external),
        BSI_CO_FIELD(sections)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_fiber* rows,uint32_t count) {
    using T=bsi_corot_fiber;const Field fields[]={
        BSI_CO_FIELD(y),BSI_CO_FIELD(z),BSI_CO_FIELD(area),BSI_CO_FIELD(constituent),BSI_CO_FIELD(law)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_station* rows,uint32_t count) {
    using T=bsi_corot_station;const Field fields[]={
        BSI_CO_FIELD(station),BSI_CO_FIELD(weight),BSI_CO_FIELD(section)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_point* rows,uint32_t count) {
    using T=bsi_corot_point;const Field fields[]={
        BSI_CO_FIELD(strain),BSI_CO_FIELD(plasticStrain),BSI_CO_FIELD(accumulatedPlastic),BSI_CO_FIELD(plasticDissipation),BSI_CO_FIELD(maximumT),
        BSI_CO_FIELD(maximumC),BSI_CO_FIELD(damageDissipation),BSI_CO_FIELD(stress),BSI_CO_FIELD(tangent),BSI_CO_FIELD(storedEnergy),
        BSI_CO_FIELD(dissipatedEnergy),BSI_CO_FIELD(workPotential),BSI_CO_FIELD(damageT),BSI_CO_FIELD(damageC),BSI_CO_FIELD(loading)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_profile* rows,uint32_t count) {
    using T=bsi_corot_profile;const Field fields[]={
        BSI_CO_FIELD(member),BSI_CO_FIELD(available),BSI_CO_FIELD(fiberFirst),BSI_CO_FIELD(fiberCount),BSI_CO_FIELD(stationFirst),
        BSI_CO_FIELD(stationCount),BSI_CO_FIELD(pointFirst),BSI_CO_FIELD(pointCount),BSI_CO_FIELD(physicalSection),BSI_CO_FIELD(area),
        BSI_CO_FIELD(Iy),BSI_CO_FIELD(Iz),BSI_CO_FIELD(storedEnergy),BSI_CO_FIELD(dissipatedEnergy),BSI_CO_FIELD(workPotential)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_joint* rows,uint32_t count) {
    using T=bsi_corot_joint;const Field fields[]={
        BSI_CO_FIELD(kind),BSI_CO_FIELD(available),BSI_CO_FIELD(index),BSI_CO_FIELD(node),BSI_CO_FIELD(end),
        BSI_CO_FIELD(master),BSI_CO_FIELD(position),BSI_CO_FIELD(orientation),BSI_CO_FIELD(relativeOrientation),BSI_CO_FIELD(coordinate),
        BSI_CO_FIELD(action)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_phase_failure* rows,uint32_t count) {
    using T=bsi_corot_phase_failure;const Field fields[]={
        BSI_CO_FIELD(constituent),BSI_CO_FIELD(flags),BSI_CO_FIELD(mode),BSI_CO_FIELD(station),BSI_CO_FIELD(fiber),
        BSI_CO_FIELD(coordinate),BSI_CO_FIELD(value),BSI_CO_FIELD(limit),BSI_CO_FIELD(utilization),BSI_CO_FIELD(failedArea),
        BSI_CO_FIELD(totalArea),BSI_CO_FIELD(fraction),BSI_CO_FIELD(requiredFraction)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_decision* rows,uint32_t count) {
    using T=bsi_corot_decision;const Field fields[]={
        BSI_CO_FIELD(microStep),BSI_CO_FIELD(cellFirst),BSI_CO_FIELD(cellCount),BSI_CO_FIELD(phaseFirst),BSI_CO_FIELD(phaseCount),
        BSI_CO_FIELD(member),BSI_CO_FIELD(station),BSI_CO_FIELD(coordinate),BSI_CO_FIELD(utilization)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_failure* rows,uint32_t count) {
    using T=bsi_corot_failure;const Field fields[]={
        BSI_CO_FIELD(member),BSI_CO_FIELD(station),BSI_CO_FIELD(flags),BSI_CO_FIELD(phaseFirst),BSI_CO_FIELD(phaseCount),
        BSI_CO_FIELD(coordinate),BSI_CO_FIELD(utilization)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_retired_member* rows,uint32_t count) {
    using T=bsi_corot_retired_member;const Field fields[]={
        BSI_CO_FIELD(member),BSI_CO_FIELD(island),BSI_CO_FIELD(profile),BSI_CO_FIELD(nodeKeys),BSI_CO_FIELD(archive),
        BSI_CO_FIELD(cellFirst),BSI_CO_FIELD(cellCount),BSI_CO_FIELD(referenceNodes),BSI_CO_FIELD(position),BSI_CO_FIELD(orientation),
        BSI_CO_FIELD(referenceMaterial),BSI_CO_FIELD(referenceSection),BSI_CO_FIELD(referenceVector),BSI_CO_FIELD(mechanics),BSI_CO_FIELD(releases)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_retired_shell* rows,uint32_t count) {
    using T=bsi_corot_retired_shell;const Field fields[]={
        BSI_CO_FIELD(shell),BSI_CO_FIELD(island),BSI_CO_FIELD(nodeKeys),BSI_CO_FIELD(archive),BSI_CO_FIELD(cellFirst),
        BSI_CO_FIELD(cellCount),BSI_CO_FIELD(referenceNodes),BSI_CO_FIELD(position),BSI_CO_FIELD(orientation),BSI_CO_FIELD(referenceMaterial),
        BSI_CO_FIELD(mechanics)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_archive* rows,uint32_t count) {
    using T=bsi_corot_archive;const Field fields[]={
        BSI_CO_FIELD(before),BSI_CO_FIELD(after),BSI_CO_FIELD(artifactNamespace),BSI_CO_FIELD(beforeGeneration),BSI_CO_FIELD(afterGeneration),
        BSI_CO_FIELD(flags),BSI_CO_FIELD(memberFirst),BSI_CO_FIELD(memberCount),BSI_CO_FIELD(shellFirst),BSI_CO_FIELD(shellCount),
        BSI_CO_FIELD(removedFirst),BSI_CO_FIELD(removedCount),BSI_CO_FIELD(installedFirst),BSI_CO_FIELD(installedCount),BSI_CO_FIELD(historyPoints),
        BSI_CO_FIELD(energy),BSI_CO_FIELD(beforeMass),BSI_CO_FIELD(retainedMass),BSI_CO_FIELD(afterMass),BSI_CO_FIELD(removedMass),
        BSI_CO_FIELD(installedMass)
    };return copyRecords(pack,name,rows,count,fields);
}
inline bool records(Pack& pack,const char* name,const bsi_corot_body_energy* rows,uint32_t count) {
    using T=bsi_corot_body_energy;const Field fields[]={
        BSI_CO_FIELD(id),BSI_CO_FIELD(kinetic),BSI_CO_FIELD(rigidKinetic),BSI_CO_FIELD(internalKinetic),BSI_CO_FIELD(estimatedError),
        BSI_CO_FIELD(samples)
    };return copyRecords(pack,name,rows,count,fields);
}
#undef BSI_CO_FIELD
inline bool diagnostics(Pack& pack,const bsi_corot_island* data,uint32_t count) {
    if(!span(data,count))return false;
    std::vector<bsi_corot_island_record> rows;std::vector<uint8_t> text;
    static_assert(offsetof(bsi_corot_island,reason)==offsetof(bsi_corot_island_record,reasonFirst),"corot diagnostic prefix");
    for(uint32_t i=0;i<count;++i){const auto& s=data[i];if(!s.reason)return false;uint32_t length=0;
        while(length<4096&&s.reason[length])++length;
        if(length==4096||length>fracture_wire::kPayloadLimit-text.size())return false;
        bsi_corot_island_record row{};std::memcpy(&row,&s,offsetof(bsi_corot_island,reason));row.reasonFirst=uint32_t(text.size());row.reasonCount=length;
        text.insert(text.end(),s.reason,s.reason+length);rows.push_back(row);}
    return pack.raw("corotIslands",rows.data(),uint32_t(rows.size()))&&pack.raw("corotText",text.data(),uint32_t(text.size()));
}
inline bool material(Pack& pack,const bsi_corot_material_view& m,bool retired=false) {
    return records(pack,retired?"corotRetiredProfiles":"corotProfiles",m.profiles,m.nProfiles)&&
        records(pack,retired?"corotRetiredFibers":"corotFibers",m.fibers,m.nFibers)&&records(pack,retired?"corotRetiredStations":"corotStations",m.stations,m.nStations)&&
        records(pack,retired?"corotRetiredPoints":"corotPoints",m.points,m.nPoints);
}
}} // namespace bsi::corot_wire
