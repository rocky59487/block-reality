// Included in SessionImpl: all transports and pending replies share this path.
    void corotRequest(const Request& rq,const uint8_t* payload,size_t n,Reply& out) {
        const bool fracture=rq.method=="bsi.corot.fracture.prepare",motion=rq.method=="bsi.corot.rigid.declare";
        if(!has("bsi.corot")||(fracture&&!has("bsi.corot.fracture"))||(motion&&!has("bsi.corot.rigid"))||engine_.vt->abi_version<7||!engine_.vt->corot_solve||
           (fracture&&(!engine_.vt->corot_fracture_prepare||!engine_.vt->fracture_finish))||(motion&&(!engine_.vt->corot_motion_declare||!engine_.vt->motion_step))) {wireError(rq,out,"UNSUPPORTED","engine lacks finite native delivery");return;}
        if(!fractureBody(rq,out,motion?"corot.rigid.declare.body":fracture?"corot.fracture.prepare.body":"corot.solve.body",n,false))return;
        if(!identified_){wireError(rq,out,"NO_WORLD","no identified world");return;}
        ReplyBuilder builder(uint32_t(world_.size()),0,BSI_STORAGE_F64);bsi_writer writer{&builder};corot_wire::Pack pack;json::Writer jw;
        if(motion){corot_wire::Motion input;
            if(!corot_wire::motion(*rq.body,payload,n,input)){wireError(rq,out,"PROTOCOL_ERROR","invalid finite motion payload");return;}
            if(!fracture_||!fracture_->committed||!fracture_wire::same(fracture_->token,input.input.fracture)){wireError(rq,out,"EXTRACT_FAILED","finite motion requires committed fracture");return;}
            std::vector<uint64_t> ids;for(const auto& b:input.bodies)ids.push_back(b.id);const bsi_corot_motion_view* view=nullptr;nativeFault_=true;
            const int status=engine_.vt->corot_motion_declare(inst_,&input.input,&view,&writer);if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
            if(builder.hasError()||!corot_wire::motionResult(view,input,*fracture_,pack)){wireError(rq,out,"INTERNAL","invalid finite motion delivery; reopen session");return;}
            beginResponse(jw,rq,"response");jw.kv("status","declared");jw.key("scene");fracture_wire::writeStamp(jw,view->geometry->scene);jw.key("fracture");fracture_wire::writeToken(jw,input.input.fracture);
            motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;out.payload=std::move(pack.bytes);
            motionStamp_=view->geometry->scene;motionIds_=std::move(ids);motionDeclared_=true;nativeFault_=false;return;
        }
        corot_wire::Input input;if(!corot_wire::input(*rq.body,payload,n,uint32_t(opts_.numThreads),input,fracture)||!fracture_wire::same(input.input.options.expected,worldStamp_)){wireError(rq,out,"PROTOCOL_ERROR","invalid finite analysis payload or basis");return;}
        if(fracture&&input.input.nLoads&&!has("bsi.corot.fracture.loads")){wireError(rq,out,"UNSUPPORTED","engine lacks finite fracture load bindings");return;}
        const bool tensor=!input.shellLaws.empty();
        if(tensor&&(!has("bsi.corot.shell.materials")||engine_.vt->abi_version<10||!engine_.vt->corot_shell_solve||!engine_.vt->corot_shell_fracture_prepare)){
            wireError(rq,out,"UNSUPPORTED","engine lacks finite shell materials");return;}
        if(fracture){auto next=std::make_unique<fracture_wire::Candidate>();const bsi_corot_fracture_view* view=nullptr;nativeFault_=true;
            bsi_corot_shell_fracture_options extended{sizeof(extended),0,input.fracture,input.shellMaterial};
            const int status=tensor?engine_.vt->corot_shell_fracture_prepare(inst_,&extended,&view,&writer):engine_.vt->corot_fracture_prepare(inst_,&input.fracture,&view,&writer);if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
            std::string error;if(builder.hasError()||!corot_wire::fracture(view,input,artifactNamespace_,world_,owners_,vocab_,*next,pack,error)){wireError(rq,out,"INTERNAL",error.empty()?"invalid finite fracture delivery; reopen session":error);return;}
            const auto& p=*view->physical;beginResponse(jw,rq,"response");jw.kv("status","prepared");jw.kv("requestId",fracture_wire::hex(p.request));jw.key("before");fracture_wire::writeStamp(jw,p.before);jw.key("after");fracture_wire::writeStamp(jw,p.after);
            jw.kv("artifactNamespace",fracture_wire::hex(p.artifactNamespace));jw.key("token");fracture_wire::writeToken(jw,p.token);jw.kv("steps",(unsigned long long)p.steps);jw.kv("flags",(int)p.flags);jw.kv("remainingBlocks",(unsigned long long)next->remaining.size());
            jw.kv("end",(int)view->end);jw.kv("corotFlags",(int)view->flags);jw.kv("pending",view->pending);jw.kv("fullyConverged",bool(view->remaining->fullyConverged));jw.kv("remainingGeneration",fracture_wire::hex(view->remaining->generation));
            motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;out.payload=std::move(pack.bytes);fracture_=std::move(next);nativeFault_=false;return;
        }
        const bsi_corot_view* view=nullptr;nativeFault_=true;bsi_corot_shell_request extended{sizeof(extended),0,input.input,input.shellMaterial};
        const int status=tensor?engine_.vt->corot_shell_solve(inst_,&extended,&view,&writer):engine_.vt->corot_solve(inst_,&input.input,&view,&writer);
        if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
        if(builder.hasError()||!corot_wire::analysis(view,input.input.options,artifactNamespace_,world_,owners_,vocab_,pack)||!corot_wire::tensorRequested(*view,input)){wireError(rq,out,"INTERNAL","invalid finite analysis delivery; reopen session");return;}
        beginResponse(jw,rq,"response");jw.kv("status","analyzed");jw.key("basis");fracture_wire::writeStamp(jw,view->basis);jw.kv("artifactNamespace",fracture_wire::hex(view->artifactNamespace));
        jw.kv("generation",fracture_wire::hex(view->generation));jw.key("token");pdelta_wire::token(jw,view->token);jw.kv("fullyConverged",bool(view->fullyConverged));
        motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;out.payload=std::move(pack.bytes);nativeFault_=false;
    }
