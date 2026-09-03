// bsi_reply.hpp -- the reply builder behind bsi_writer_* (host-internal).
// Engines write typed structs; the builder validates counts and consistency and
// lays the payload out in the fixed section order (BSI.md B.5). The same layout
// routine serves the heap sink and the arena sink, so the two are bitwise equal
// by construction (MC68-06).
#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include "../bsi_engine.h"

namespace bsi {

struct SectionInfo { std::string name; uint64_t offset = 0, bytes = 0, count = 0; };
struct UnassignedGroup { std::string why; int32_t island = -1; std::vector<int32_t> xyz; };
struct WarningCount { std::string code; uint32_t count = 0; };

enum IncludeBits { kIncMembers = 1, kIncStations = 2, kIncShells = 4, kIncAttrsEcho = 8 };

class ReplyBuilder {
public:
    ReplyBuilder(uint32_t declaredBlocks, uint32_t includeMask, uint8_t storage)
        : declared_(declaredBlocks), include_(includeMask), storage_(storage) {}

    // ---- engine-facing (called through bsi_writer_*) ----
    int blocks(const bsi_block_result* r, uint32_t n);
    int member(const bsi_member_result* m, const int32_t* xyz, uint32_t nb, const bsi_station* st, uint32_t ns);
    int facet(const bsi_facet_result* f, const int32_t* xyz, uint32_t nb, const bsi_surface top[4], const bsi_surface bottom[4]);
    int unassigned(const char* why, int32_t island, const int32_t* xyz, uint32_t nb);
    int warning(const char* code, uint32_t count);
    int equilibrium(const double applied[3], const double reaction[3], double residual);
    int quality(double achievedRel, int32_t iterations, uint8_t tierHonoured, uint8_t warmStartUsed, uint8_t timedOut);
    int buckling(int32_t island, uint8_t state, uint8_t kind, double factor);
    int editClass(char cls, const char* downgraded);
    int diag(uint32_t nodes, uint32_t members, uint32_t facets, uint32_t islands, uint32_t singularIslands, uint32_t refusedBlocks);
    int error(const char* code, const char* message, const int32_t* at);
    int attrsEcho(const bsi_attr* a, uint32_t n);

    // ---- host-facing ----
    bool hasError() const { return hasError_; }
    const std::string& errorCode() const { return errCode_; }
    const std::string& errorMessage() const { return errMsg_; }
    bool errorHasAt() const { return errHasAt_; }
    const int32_t* errorAt() const { return errAt_; }

    // Validate consistency for a solve reply and lay out the payload. On a
    // violation returns false with `why` (=> INTERNAL). partial: status partial.
    bool finalizeSolve(std::string& why);
    bool finalizeDeclare(std::string& why);

    // After finalize: the payload bytes, the section table, and header material.
    const std::vector<uint8_t>& payload() const { return payload_; }
    const std::vector<SectionInfo>& sections() const { return sections_; }
    const std::vector<UnassignedGroup>& unassignedGroups() const { return unassigned_; }
    const std::vector<WarningCount>& warnings() const { return warnings_; }
    // What blocks.flags bit2 (bucklingCritical) is allowed to mean, answered in one
    // place so the rule cannot drift between the writer and the checker: the
    // island's own record says computed, and its factor is finite and below 1.
    // hasRecord distinguishes "this island is safe" from "this island was never
    // evaluated", which are different answers and must not share a bit.
    bool islandBucklingCritical(int32_t island, bool& hasRecord) const;
    bool haveDiag() const { return haveDiag_; }
    uint32_t nodes() const { return nodes_; }
    uint32_t members() const { return members_; }
    uint32_t facets() const { return facets_; }
    uint32_t islands() const { return islands_; }
    uint32_t singularIslands() const { return singular_; }
    uint32_t refusedBlocks() const { return refused_; }
    bool haveEdit() const { return haveEdit_; }
    char editCls() const { return editCls_; }
    const std::string& editDowngraded() const { return editDowngraded_; }
    bool timedOut() const { return haveQuality_ && qual_.timedOut != 0; }
    // buckling summary for the header (README: worst-first collapse)
    std::string bucklingState(uint8_t requestedMode) const;
    uint32_t declaredBlocks() const { return declared_; }

private:
#pragma pack(push, 1)
    struct Equilibrium { double applied[3], reaction[3], residual; };
    struct Quality { double achievedRel; int32_t iterations; uint8_t tierHonoured, warmStartUsed, storage, timedOut; };
    struct Buckling { int32_t island; uint8_t state, kind; uint16_t reserved; double factor; };
    struct StationF32 { float s, x, y, z, sigma[4], tau, naY, naZ; };
#pragma pack(pop)
    static_assert(sizeof(Equilibrium) == 56, "equilibrium 56 B");
    static_assert(sizeof(Quality) == 16, "quality 16 B");
    static_assert(sizeof(Buckling) == 16, "buckling 16 B");
    static_assert(sizeof(StationF32) == 44, "stations:f32 44 B");

    uint32_t declared_, include_;
    uint8_t storage_;
    std::vector<bsi_block_result> blocks_;
    bool haveBlocks_ = false, blocksTwice_ = false;
    std::vector<bsi_member_result> members_v_;
    std::vector<int32_t> memberBlocks_;
    std::vector<bsi_station> stations_;
    std::vector<bsi_facet_result> facets_v_;
    std::vector<int32_t> facetBlocks_;
    std::vector<bsi_surface> surfaces_;       // 8 per facet: top[4], bottom[4]
    std::vector<UnassignedGroup> unassigned_;
    std::vector<WarningCount> warnings_;
    bool haveEq_ = false; Equilibrium eq_{};
    bool haveQuality_ = false; Quality qual_{};
    std::vector<Buckling> buckling_;
    std::vector<bsi_attr> attrsEcho_;
    bool haveDiag_ = false; uint32_t nodes_ = 0, members_ = 0, facets_ = 0, islands_ = 0, singular_ = 0, refused_ = 0;
    bool haveEdit_ = false; char editCls_ = 0; std::string editDowngraded_;
    bool hasError_ = false, errHasAt_ = false; std::string errCode_, errMsg_; int32_t errAt_[3] = {0, 0, 0};
    std::vector<uint8_t> payload_;
    std::vector<SectionInfo> sections_;

    void appendSection(const char* name, const void* data, uint64_t bytes, uint64_t count);
};

// The opaque handles the C ABI passes around.
struct HostServices { int logLevel = 0; volatile int cancelled = 0; };

}  // namespace bsi

struct bsi_writer { bsi::ReplyBuilder* b; };
struct bsi_host { bsi::HostServices* s; };
