from pathlib import Path
import json
root=Path(__file__).resolve().parents[2]/'forge/src/main/resources/assets/blockreality'
products=[('steel_beam','steel','steel_rect_200x400','iron_block','netherite_block'),
('steel_beam_150x300','steel','steel_rect_150x300','iron_block','netherite_block'),
('steel_beam_100x200','steel','steel_rect_100x200','iron_block','netherite_block'),
('timber_beam','timber','timber_rect_140x240','stripped_oak_log','stripped_oak_log_top'),
('concrete_beam','concrete','concrete_rect_400x600','gray_concrete','smooth_stone'),
('brick_pier','brick','brick_rect_230x350','bricks','red_terracotta'),
('concrete_slab','concrete','concrete_slab_200','smooth_stone','polished_andesite'),
('concrete_slab_150','concrete','concrete_slab_150','smooth_stone','polished_andesite'),
('steel_plate','steel','steel_plate_20','iron_block','netherite_block')]
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
for name,material,section,side,end in products:
    model='blockreality:block/'+name
    write(root/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','loader':'blockreality:product','material':material,'section':section,'textures':{'side':'minecraft:block/'+side,'end':'minecraft:block/'+end,'particle':'#side','warning':'minecraft:block/yellow_concrete'}})
    write(root/'models/block'/f'{name}_undeclared.json',{'parent':model,'loader':'blockreality:product','undeclared':True})
    write(root/'blockstates'/f'{name}.json',{'variants':{'axis=x':{'model':model,'y':90},'axis=y':{'model':model,'x':90,'y':90},'axis=z':{'model':model},'axis=undeclared':{'model':model+'_undeclared'}}})
print('Wrote declaration-backed loader resources for 9 products and all 4 placement states')
