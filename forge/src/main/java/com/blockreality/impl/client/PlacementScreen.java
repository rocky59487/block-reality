package com.blockreality.impl.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.*;
import static com.blockreality.impl.net.ConstructionProtocol.Status;

/** A measured native-game side panel. Its world-space preview remains owned by the same session. */
@OnlyIn(Dist.CLIENT)
final class PlacementScreen extends Screen {
    private final ClientConstruction.Session state;
    private final List<Button> axes=new ArrayList<>();
    private Button primary,close;
    private ItemStack icon=ItemStack.EMPTY;
    private List<net.minecraft.util.FormattedCharSequence> name=List.of(),rows=List.of();
    private int panelWidth,top,bottom,bodyTop,bodyBottom,scroll,maximumScroll;
    PlacementScreen(ClientConstruction.Session state) {super(Component.translatable("br.build.title"));this.state=state;}
    @Override public boolean isPauseScreen() {return false;}
    @Override protected void init() {
        panelWidth=Math.max(1,Math.min(width-12,Math.min(240,(int)(width*.44))));
        top=6;axes.clear();
        int available=panelWidth-12,axisWidth=(available-8)/3;
        for(int i=0;i<3;i++) {
            int axis=i;
            axes.add(addRenderableWidget(Button.builder(Component.literal("XYZ".substring(i,i+1)),
                    button->ClientConstruction.refresh(state,axis)).bounds(12+i*(axisWidth+4),0,axisWidth,20).build()));
        }
        primary=addRenderableWidget(Button.builder(Component.translatable("br.build.confirm"),button->{
            if(state.sent!=null && state.status==Status.EXPIRED)ClientConstruction.newPreviewAfterExpiry(state);
            else if(state.sent!=null || state.offer!=null && state.status==Status.READY)ClientConstruction.confirm(state);
            else if(state.preview!=null)ClientConstruction.refresh(state,state.preview.axis());
        }).bounds(12,0,available,20).build());
        close=addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),button->onClose())
                .bounds(12,0,available,20).build());
        layoutContent();updateButtons();setInitialFocus(primary.active?primary:close);
    }
    @Override public void tick() {
        if(!ClientConstruction.current(state)){onClose();return;}
        ClientConstruction.tick(state);layoutContent();updateButtons();
    }
    private void updateButtons() {
        for(int i=0;i<axes.size();i++) {
            axes.get(i).active=state.sent==null && state.preview!=null && !state.waiting;
            int selected=state.offer!=null?state.offer.axis():state.preview==null?-1:state.preview.axis();
            axes.get(i).setMessage(Component.literal((i==selected?"[":"")+"XYZ".charAt(i)+(i==selected?"]":"")));
        }
        primary.active=!state.waiting && !state.terminal && (state.sent!=null || state.preview!=null);
        primary.visible=!state.terminal;
        if(state.terminal && getFocused()==primary)setFocused(close);
        primary.setMessage(Component.translatable(state.sent!=null && state.status!=Status.EXPIRED?"br.build.retry":state.offer!=null && state.status==Status.READY?"br.build.confirm":"br.build.refresh"));
        close.setMessage(Component.translatable(state.sent!=null && !state.terminal?"br.build.close_pending":state.terminal?"gui.done":"gui.cancel"));
    }
    /** Cache text measurement between frames and retain button focus when the content height changes. */
    private void layoutContent() {
        int textWidth=Math.max(1,panelWidth-12);
        String product=state.offer!=null?state.offer.product():state.preview!=null?state.preview.product():"minecraft:air";
        icon=new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(product)));
        name=font.split(icon.isEmpty()?title:icon.getHoverName().copy().withStyle(net.minecraft.ChatFormatting.BOLD),Math.max(1,textWidth-24));
        bodyTop=Math.max(top+6+name.size()*12,top+22)+4;
        List<Component> text=new ArrayList<>();
        text.add(Component.translatable(state.offer!=null && state.offer.creative()?"br.build.creative":"br.build.cost"));
        if(state.offer!=null) {
            var pos=state.offer.target();text.add(Component.translatable("br.build.target",pos.getX(),pos.getY(),pos.getZ()));
            if(!state.offer.dimension().equals(state.dimension))text.add(Component.translatable("br.build.foreign_world"));
        }
        text.add(ClientConstruction.status(state));
        if(state.sent!=null && !state.terminal)text.add(Component.translatable("br.build.close_notice"));
        rows=new ArrayList<>();
        for(Component line:text){rows.addAll(font.split(line,textWidth));rows.add(Component.empty().getVisualOrderText());}
        int controlHeight=state.terminal?48:72;
        bottom=Math.min(height-6,bodyTop+rows.size()*12+controlHeight+4);
        int controls=bottom-controlHeight;bodyBottom=controls-4;
        for(Button axis:axes)axis.setY(controls);
        primary.setY(controls+24);close.setY(controls+(state.terminal?24:48));
        int viewport=Math.max(0,bodyBottom-bodyTop);
        maximumScroll=Math.max(0,rows.size()*12-viewport);scroll=Math.min(scroll,maximumScroll);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(6,top,6+panelWidth,bottom,0xEB141B22);
        graphics.renderItem(icon,12,top+6);
        int y=top+6;
        for(var line:name){graphics.drawString(font,line,36,y,HudPanel.PRIMARY,false);y+=12;}
        int textWidth=Math.max(1,panelWidth-12),viewport=Math.max(0,bodyBottom-bodyTop);
        if(viewport>0) {
            graphics.enableScissor(12,bodyTop,12+textWidth,bodyBottom);
            int rowY=bodyTop-scroll;
            for(var line:rows){graphics.drawString(font,line,12,rowY,HudPanel.PRIMARY,false);rowY+=12;}
            graphics.disableScissor();
            if(maximumScroll>0) {
                int marker=Math.max(6,viewport*viewport/(rows.size()*12));
                int offset=(viewport-marker)*scroll/maximumScroll;
                graphics.fill(6+panelWidth-3,bodyTop+offset,6+panelWidth-2,bodyTop+offset+marker,HudPanel.FOCUS|0xFF000000);
            }
        }
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        if(x>=6 && x<=6+panelWidth) {scroll=Math.max(0,Math.min(maximumScroll,scroll-(int)(delta*24)));return true;}
        return super.mouseScrolled(x,y,delta);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN || key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
            scroll=Math.max(0,Math.min(maximumScroll,scroll+(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN?48:-48)));return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
}
