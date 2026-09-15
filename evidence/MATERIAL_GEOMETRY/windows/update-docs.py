from pathlib import Path
root=Path(__file__).resolve().parents[2]
for name in ['README.md','QUICKSTART.md','docs/RESEARCH_BRIEF.md','docs/outreach/LISTING.md']:
    p=root/name;t=p.read_text(encoding='utf-8');t=t.replace('521','528').replace('412 core','419 core').replace('core 412','core 419')
    t=t.replace('19 cover in-process','20 cover in-process').replace('19 項涵蓋 BSI','20 項涵蓋 BSI')
    t=t.replace('latest Windows run: 490 pass, 29 skip','latest Windows run: 516 pass, 12 platform skips')
    t=t.replace('Windows: 490 pass, 29 skip','Windows: 516 pass, 12 platform skips')
    p.write_text(t,encoding='utf-8')
p=root/'CLAUDE.md';t=p.read_text(encoding='utf-8')
t=t.replace('- Windows core412/Forge109：521登錄、509PASS、12平台SKIP；Linux520PASS、1平台SKIP。\n  19項 native 相關全部執行。',
            '- Windows core419/Forge109：528登錄、516PASS、12平台SKIP；Linux527PASS、1平台SKIP。\n  20項 native 相關全部執行。')
entry='''- MATERIAL_GEOMETRY 已使4種矩形產品依宣告尺寸呈現，模型/目標/碰撞框共用bounds，
  X/Y/Z端面與未宣告警示可見，原生掃描保留寬深與站點不連續。Java不增加力學/運動。
  真Linux客戶端127項模型、14項原版入口互動、原16圖/45項守門與6張補充圖通過。
  最終528登錄：Windows516PASS/12平台SKIP、Linux527PASS/1平台SKIP；3個可編譯反例被抓到。
  jar15,216,948B/SHA8e587a715ebd…帶原42e10f5雙庫；207class/2負向臂與bundle9反例通過。
  Windows首個直連/jar逐位比較因兩程序BLAS設定不同而失敗，設定一致後48frame×3通過；
  首次只改Python環境仍失敗，後以啟動環境統一測試driver。原始回覆/失敗照存。
  詳 evidence/MATERIAL_GEOMETRY；panel仍是材料格，Windows CM/N25/FPS/動態仍未接受。
'''
t=t.replace('- `GAME_INPUT` 已接',entry+'- `GAME_INPUT` 已接',1);p.write_text(t,encoding='utf-8')
p=root/'docs/V1_MODULE_PROGRAM.md';t=p.read_text(encoding='utf-8')
t=t.replace('## 現況\n','''## 現況

MATERIAL_GEOMETRY 已在模組來源b10884b完成矩形產品外觀/目標/碰撞形狀、端面方向、
未宣告警示及原生取樣映射。真Linux127項模型檢查、14項程式驅動原版客戶端互動、
原16圖/45項守門及6張補充圖通過；Windows516PASS/12平台SKIP、Linux527PASS/1平台SKIP。
目前jar15,216,948B、SHA8e587a715ebd…，兩庫仍42e10f5。來源/封裝/首敗見
`../evidence/MATERIAL_GEOMETRY/RESULTS.md`。這不是Windows CM/N25/FPS或v1資格。

本輪同步：引擎#40仍e20b416（交付庫42e10f5），沒有引擎側修改。
新模組分支`claude/security-functionality-review-yftgf8`/cde57d5只補CI METIS下載pin，
已讀差異，待獨立凍門檻/驗收後整合；本單元不編譯或修改引擎。

''',1);p.write_text(t,encoding='utf-8')
p=root/'.interface-design/system.md';t=p.read_text(encoding='utf-8');t+='''

## Declared material geometry (MATERIAL_GEOMETRY)

Four rectangular member products use the SI catalogue bounds for atlas model baking,
target outline and collision. Steel sizes 200×400,150×300,100×200 mm and timber140×240 mm
must remain visibly unequal. Cut/end faces follow the declared X/Y/Z axis; the other
faces retain longitudinal material texture. Undeclared cells show an ochre ! on each face.
Concrete/brick remain material cubes; panel placement axis is not a physical normal.
Keep panel cells honest until their physical form is supplied by the input/engine path.
Scans fit these presentation bounds and interpolate native samples with separate width
and depth extents. Changing product/axis suppresses incompatible old surfaces.
Actual baked quads, target/collision boxes, item models and vanilla interaction receipts
are in evidence/MATERIAL_GEOMETRY. Original installed Windows acceptance remains open.
''';p.write_text(t,encoding='utf-8')
