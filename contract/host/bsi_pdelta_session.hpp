// Included in SessionImpl. Engine owns analysis and the one shared fracture trial.
    bool pdeltaDeclared_=false;
    bsi_analysis_token pdeltaToken_{};
    uint32_t pdeltaMembers_=0;
    bool pdeltaCapability(const Request& rq,Reply& out,bool fracture) {
        if(!has("bsi.pdelta")||(fracture&&!has("bsi.pdelta.fracture"))||engine_.vt->abi_version<6||
           !engine_.vt->pdelta_solve||!engine_.vt->pdelta_station||(fracture&&!engine_.vt->pdelta_fracture_prepare)) {
            wireError(rq,out,"UNSUPPORTED","engine lacks native P-Delta delivery");return false;
        }
        return true;
    }
    void pdeltaRequest(const Request& rq,const uint8_t* payload,size_t n,Reply& out) {
#ifdef BSI_TEST_NPD_ATOMIC
        struct ResetFault {bool& fault;~ResetFault(){fault=false;}} resetFault{nativeFault_};
#endif
        const bool prepare=rq.method=="bsi.pdelta.fracture.prepare",station=rq.method=="bsi.pdelta.station";
        if(!pdeltaCapability(rq,out,prepare)||!fractureBody(rq,out,prepare?"pdelta.fracture.prepare.body":station?"pdelta.station.body":"pdelta.solve.body",n,station))return;
        if(!identified_){wireError(rq,out,"NO_WORLD","no identified world");return;}
        ReplyBuilder builder(uint32_t(world_.size()),0,BSI_STORAGE_F64);bsi_writer writer{&builder};pdelta_wire::Pack pack;json::Writer jw;
        if(station) {
            bsi_pdelta_query query{};query.struct_size=sizeof(query);query.token=pdelta_wire::token(*rq.body->find("token"));
            query.member=int32_t(rq.body->find("member")->i64);query.fraction=rq.body->find("fraction")->num;query.side=int32_t(rq.body->find("side")->i64);
            if(!pdeltaDeclared_||!pdelta_wire::same(query.token,pdeltaToken_)||query.member<0||uint32_t(query.member)>=pdeltaMembers_) {wireError(rq,out,"SOLVE_FAILED","unknown analysis token or member");return;}
            bsi_pdelta_station value{};nativeFault_=true;const int status=engine_.vt->pdelta_station(inst_,&query,&value,&writer);
            if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
            if(builder.hasError()||!(value.available&BSI_PD_SOLVED)||(value.available&~uint32_t(BSI_PD_SOLVED|BSI_PD_ELASTIC|BSI_PD_FIBRES))||
               !pdelta_wire::finite(value.forces)||!pdelta_wire::finite(value.sigma)||!pdelta_wire::nonnegative(value.shear)||!pdelta_wire::nonnegative(value.elastic)||
               (!(value.available&BSI_PD_ELASTIC)&&value.elastic!=0)||(!(value.available&BSI_PD_FIBRES)&&!pdelta_wire::zero(value.sigma))||value.mode>6||value.fibre>6||!pdelta_wire::station(pack,value)) {
                wireError(rq,out,"INTERNAL","invalid P-Delta station; reopen session");return;
            }
            beginResponse(jw,rq,"response");jw.kv("status","sampled");jw.key("token");pdelta_wire::token(jw,query.token);
            jw.kv("member",query.member);jw.kv("side",query.side);
            bsi_pdelta_sample_record sample{};sample.member=query.member;sample.side=query.side;sample.fraction=query.fraction;
            if(!pack.raw("pdeltaSample",&sample,1)){wireError(rq,out,"INTERNAL","P-Delta sample exceeds payload budget");return;}
        }else {
            pdelta_wire::Input input;
            if(!pdelta_wire::input(*rq.body,payload,n,uint32_t(opts_.numThreads),input)||!fracture_wire::same(input.options.expected,worldStamp_)) {
                wireError(rq,out,"PROTOCOL_ERROR","invalid P-Delta input or world basis");return;
            }
            if(prepare) {
                const auto options=pdelta_wire::fractureOptions(*rq.body,input);
                if(!fracture_wire::nonzero(options.request)||options.analysis.expected.revision==INT64_MAX) {wireError(rq,out,"PROTOCOL_ERROR","invalid fracture identity");return;}
                auto next=std::make_unique<fracture_wire::Candidate>();const bsi_pdelta_fracture_view* view=nullptr;nativeFault_=true;
                const int status=engine_.vt->pdelta_fracture_prepare(inst_,&options,input.loads.data(),uint32_t(input.loads.size()),&view,&writer);
                if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
                uint32_t first=0,count=0;std::string error;
                if(builder.hasError()||!pdelta_wire::fracture(view,options,input,artifactNamespace_,world_,owners_,vocab_,*next,pack,first,count,error)) {
                    wireError(rq,out,"INTERNAL",error.empty()?"invalid P-Delta fracture; reopen session":error);return;
                }
                const auto& physical=*view->physical;beginResponse(jw,rq,"response");jw.kv("status","prepared");
                jw.kv("requestId",fracture_wire::hex(physical.request));jw.key("before");fracture_wire::writeStamp(jw,physical.before);
                jw.key("after");fracture_wire::writeStamp(jw,physical.after);jw.kv("artifactNamespace",fracture_wire::hex(physical.artifactNamespace));
                jw.key("token");fracture_wire::writeToken(jw,physical.token);jw.kv("steps",(unsigned long long)physical.steps);jw.kv("flags",(int)physical.flags);
                jw.kv("remainingBlocks",(unsigned long long)next->remaining.size());jw.kv("end",(unsigned long long)view->end);jw.kv("qualification",(unsigned long long)view->qualification);
                jw.kv("pdeltaFlags",(unsigned long long)view->flags);jw.kv("pending",view->pending);jw.kv("rollbackReasonFirst",(unsigned long long)first);jw.kv("rollbackReasonCount",(unsigned long long)count);
                motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;
                out.payload=std::move(pack.bytes);fracture_=std::move(next);nativeFault_=false;return;
            }
            const bsi_pdelta_view* view=nullptr;nativeFault_=true;
            const int status=engine_.vt->pdelta_solve(inst_,&input.options,input.loads.data(),uint32_t(input.loads.size()),&view,&writer);
            if(status!=BSI_OK){nativeFault_=false;errorFromBuilder(out,rq,status,builder);return;}
            if(builder.hasError()||!pdelta_wire::analysis(view,input,artifactNamespace_,world_,owners_,vocab_,pack)) {wireError(rq,out,"INTERNAL","invalid P-Delta analysis; reopen session");return;}
            beginResponse(jw,rq,"response");jw.kv("status","analyzed");jw.key("basis");fracture_wire::writeStamp(jw,view->basis);
            jw.kv("artifactNamespace",fracture_wire::hex(view->artifactNamespace));jw.kv("generation",fracture_wire::hex(view->generation));jw.key("token");pdelta_wire::token(jw,view->token);
            motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;
            out.payload=std::move(pack.bytes);pdeltaToken_=view->token;pdeltaMembers_=view->nMembers;pdeltaDeclared_=true;nativeFault_=false;return;
        }
        motion_wire::sections(jw,pack.table);jw.endObj();if(!wireHeader(rq,out,jw))return;
        out.payload=std::move(pack.bytes);nativeFault_=false;
    }
