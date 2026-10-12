// Included in SessionImpl. Only scene identity is cached; no second motion state.
    bool motionDeclared_ = false;
    bsi_world_stamp motionStamp_{};
    std::vector<uint64_t> motionIds_;
    bool motionCapability(const Request& rq,Reply& out) {
#ifndef BSI_TEST_RMW_CAP
        if (!has("bsi.rigid.motion")) { wireError(rq,out,"UNSUPPORTED","engine lacks rigid motion capability"); return false; }
#endif
        if (
#ifndef BSI_TEST_RMW_ABI
            engine_.vt->abi_version<4 ||
#endif
            !engine_.vt->motion_declare||!engine_.vt->motion_step) {
            wireError(rq,out,"UNSUPPORTED","engine lacks ABI4 rigid motion entry points"); return false;
        }
        return true;
    }
    void motionRequest(const Request& rq,const uint8_t* payload,size_t n,Reply& out,bool declare) {
        const bool finiteScene=rq.method=="bsi.rigid.scene.declare";
        if (finiteScene) {
            if (!has("bsi.rigid.finite.sources")||engine_.vt->abi_version<11||!engine_.vt->rigid_scene_declare)
                { wireError(rq,out,"UNSUPPORTED","engine lacks finite rigid sources"); return; }
        } else if (!motionCapability(rq,out)) return;
        if (!fractureBody(rq,out,finiteScene?"rigid.scene.declare.body":declare?"rigid.declare.body":"rigid.step.body",n,false)) return;
        if (!vocabDeclared_) { wireError(rq,out,"VOCAB_INVALID","no vocabulary declared"); return; }
        ReplyBuilder builder(0,0,BSI_STORAGE_F64); bsi_writer writer{&builder};
        std::vector<SectionInfo> table; json::Writer jw;
        if (finiteScene) {
            motion_wire::SceneDeclare input;
            if (!motion_wire::sceneDeclare(*rq.body,payload,n,input)) {
                wireError(rq,out,"PROTOCOL_ERROR","invalid finite rigid scene payload or bounds"); return;
            }
            std::vector<uint64_t> ids;ids.reserve(input.ids.size());
            for (const auto id:input.ids) ids.push_back(id);
            std::sort(ids.begin(),ids.end());
            const bsi_rigid_scene_view* view=nullptr;nativeFault_=true;
            int status=engine_.vt->rigid_scene_declare(inst_,&input.input,&view,&writer);
            if (status!=BSI_OK) { nativeFault_=false; errorFromBuilder(out,rq,status,builder); return; }
            if (builder.hasError()||!motion_wire::sceneResult(view,input,ids,table,out.payload)) {
                wireError(rq,out,"INTERNAL","invalid native finite rigid scene; reopen session"); return;
            }
            beginResponse(jw,rq,"response"); jw.kv("status","declared"); jw.key("scene"); fracture_wire::writeStamp(jw,view->geometry->scene);
            motion_wire::sections(jw,table); jw.endObj();
            if (!wireHeader(rq,out,jw)) return;
            motionStamp_=view->geometry->scene; motionIds_=std::move(ids); motionDeclared_=true; nativeFault_=false; return;
        }
        if (declare) {
            motion_wire::Declaration input;
            if (!motion_wire::declaration(*rq.body,payload,n,input)) {
                wireError(rq,out,"PROTOCOL_ERROR","invalid rigid source payload or bounds"); return;
            }
            std::vector<uint64_t> ids; ids.reserve(input.bodies.size());
            for (const auto& b:input.bodies) ids.push_back(b.id);
            const bsi_motion_geometry_view* view=nullptr;
            nativeFault_=true;
            int status=engine_.vt->motion_declare(inst_,&input.input,&view,&writer);
            if (status!=BSI_OK) { nativeFault_=false; errorFromBuilder(out,rq,status,builder); return; }
            if (builder.hasError()||!motion_wire::geometry(view,input,table,out.payload)) {
                wireError(rq,out,"INTERNAL","invalid native rigid geometry; reopen session"); return;
            }
            beginResponse(jw,rq,"response"); jw.kv("status","declared"); jw.key("scene"); fracture_wire::writeStamp(jw,view->scene);
            motion_wire::sections(jw,table); jw.endObj();
            if (!wireHeader(rq,out,jw)) return;
            motionStamp_=view->scene; motionIds_=std::move(ids); motionDeclared_=true;
        } else {
            if (!motionDeclared_) { wireError(rq,out,"NO_WORLD","no rigid scene declared"); return; }
            motion_wire::Step input;
            if (!motion_wire::step(*rq.body,payload,n,input)) {
                wireError(rq,out,"PROTOCOL_ERROR","invalid rigid state payload or bounds"); return;
            }
            if (!fracture_wire::same(input.input.scene,motionStamp_)) {
                wireError(rq,out,"SOLVE_FAILED","stale rigid scene"); return;
            }
            if ((input.input.coverageFlags&3u)&&!has("bsi.rigid.coverage")) {
                wireError(rq,out,"UNSUPPORTED","engine lacks rigid sweep coverage"); return;
            }
            const bsi_motion_step_view* view=nullptr;
            nativeFault_=true;
            int status=engine_.vt->motion_step(inst_,&input.input,&view,&writer);
            if (status!=BSI_OK) { nativeFault_=false; errorFromBuilder(out,rq,status,builder); return; }
            if (builder.hasError()||!motion_wire::result(view,input,motionIds_,table,out.payload)) {
                wireError(rq,out,"INTERNAL","invalid native rigid state; reopen session"); return;
            }
            const uint32_t covStatus=view->struct_size>=offsetof(bsi_motion_step_view,coverageStatus)+sizeof(view->coverageStatus)?view->coverageStatus:0;
            beginResponse(jw,rq,"response");
            jw.kv("status",covStatus==2?"suspended":covStatus==3?"required":"stepped"); jw.key("scene"); fracture_wire::writeStamp(jw,view->scene);
            jw.kv("requestId",fracture_wire::hex(view->request)); motion_wire::sections(jw,table); jw.endObj();
            if (!wireHeader(rq,out,jw)) return;
        }
        nativeFault_=false;
    }
