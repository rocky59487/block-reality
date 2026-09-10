import com.blockreality.api.render.StressPalette;
public final class PaletteReadout {
    public static void main(String[] args) {
        for (var stop : StressPalette.utilizationLegend()) System.out.println(stop);
        for (double dc : new double[]{-1,0,.1,.6,.95,Math.nextDown(1.0),1,Math.nextUp(1.0),3,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
            for (boolean overloaded : new boolean[]{false,true})
                System.out.println(dc+" / "+overloaded+" = "+StressPalette.utilization(dc,overloaded));
        for (var palette : StressPalette.values())
            for (double stress : new double[]{-100,-1,0,1,100})
                System.out.println(palette+" / "+stress+" = "+palette.signedStress(stress,100)+" / "+palette.hatchFor(stress,100));
    }
}
