package com.blockreality.impl.net;

import com.blockreality.api.*;
import com.blockreality.api.geom.*;
import java.util.*;

final class DisplayDeliveryFixtures {
    static MemberSnapshot member(int id, int ns, int nb) {
        Vec3d o = new Vec3d(29_999_000_500., 64500, -3500), x = new Vec3d(1,0,0), y = new Vec3d(0,1,0), z = new Vec3d(0,0,1);
        List<StressStation> stations = new ArrayList<>();
        for (int i=0; i<ns; i++) {
            double s = ns == 1 ? 0 : (double)(i/2)/Math.max(1, (ns-1)/2), at = s*2000;
            stations.add(new StressStation(at, o.plus(x.scaled(at)), List.of(
                    new Fibre("TOP_Y",y,200,2+i), new Fibre("BOT_Y",y.scaled(-1),200,-4-i),
                    new Fibre("PLUS_Z",z,100,6+i), new Fibre("MINUS_Z",z.scaled(-1),100,-8-i)),
                    6+i,8+i,9+i,Optional.of(50.),Optional.empty(),Optional.of(new StressStation.Identity(s,i%2==0?-1:1))));
        }
        Optional<BeamDisplayField> display = ns == 0 ? Optional.empty() : Optional.of(new BeamDisplayField(o,x,y,z,2000,200,100,stations));
        return new MemberSnapshot(id,"steel","rect",2000,1,GoverningFibre.TENSION,ns==0?-1:ns/2,
                EndForces.ZERO,EndForces.ZERO,blocks(nb),stations,Optional.empty(),display,true,Optional.empty());
    }
    static List<BlockKey> blocks(int n) {
        List<BlockKey> blocks = new ArrayList<>();
        for(int i=0;i<n;i++) blocks.add(new BlockKey(29_999_000+i,64,-3));
        return blocks;
    }
    static ShellSnapshot shell(int id, int nb) {
        var face=List.of(new ShellDisplayField.Surface(2,-1,.1,10),new ShellDisplayField.Surface(3,-2,.2,11),
                new ShellDisplayField.Surface(4,-3,.3,12),new ShellDisplayField.Surface(5,-4,.4,13));
        double x=29_999_000_500.;
        var f=new ShellDisplayField(List.of(new Vec3d(x,64500,500),new Vec3d(x+1000,64500,500),
                new Vec3d(x+1000,64500,1500),new Vec3d(x,64500,1500)),
                new Vec3d(1,0,0),new Vec3d(0,0,1),new Vec3d(0,-1,0),face,face);
        return new ShellSnapshot(id,"slab","slab",200,1,Double.NaN,true,false,blocks(nb),
                Optional.empty(),Optional.of(f),true,6);
    }
    static AnalysisResult result(List<MemberSnapshot> m, List<ShellSnapshot> s, int governing, String kind) {
        return new AnalysisResult(new WorldRevision(19),true,false,"",1,governing,kind,1,0,0,0,
                BucklingState.DISABLED_BY_REQUEST,m,s,List.of(),true,false);
    }
    static AnalysisResult fixture(String name) {
        List<MemberSnapshot> m=new ArrayList<>(); List<ShellSnapshot> s=new ArrayList<>();
        int nm=name.equals("dense")?64:name.equals("shell-heavy")?0:4;
        int nf=name.equals("dense")||name.equals("shell-heavy")?512:4;
        for(int i=0;i<nm;i++)m.add(member(i,name.equals("dense")?64:4,name.equals("dense")?300:32));
        for(int i=0;i<nf;i++)s.add(shell(i,name.equals("shell-heavy")?256:4));
        if(name.equals("oversize"))m.add(member(999,4096,65536));
        return result(m,s,name.equals("oversize")?999:name.equals("shell-heavy")?511:nm-1,
                name.equals("shell-heavy")?"shell":"member");
    }
}
