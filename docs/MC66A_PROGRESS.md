# MC66a 世界快照對位

2026-09-09。引擎正式版 v1.3 / 1.3.0；本倉 0.4.0-dev。
引擎已將完整每島挫屈結果接到同次LiveState快照，覆蓋Fresh/Session/Island。
在臨時載重清除前完成，逐島保留computed/no-positive/scale/refusal/failure；
任何失敗島都不能隱藏成世界成功，世界拒絕也不清除其他島的有效局部Critical。
block/member bit2與stability由引擎double嚴格lambda<1定案，Java仍只讀旗標。

MC66A_ISLAND_SNAPSHOT.md與MC66A_ISLAND_SNAPSHOT_COUNTS.json鏡像判準/固定名單。
427正常checks、15故障變異；Windows/Linux/i9 DET3、Linux ASan/UBSan與原功能回歸
以引擎 gate/evidence/MC66A_WORLD/RESULTS.md 為準。首輪釋放座標FAIL、漏載重變異
escape及補強獨立oracle均保留，未放寬原線。前置579+10/13變異保留。

BSI宿主五態聚合與Java完整每島消費已完成本地驗證：Windows/Linux各417正常checks，
六個宿主與四個Java變異具名咬合；Java保存IslandBuckling，世界拒絕不抹去局部Critical。
契約52檔hash91e64576fd24…同步兩倉；舊54檔為過期計數，原版本實際51檔。
Windows core332/Forge81共413登錄：389PASS/24SKIP；Linux另選JNA相關14項全過。
真native語料8PASS/2SKIP（C10與custom section），C5/C6/C8×f64/f32的18份非握手回覆
在原/新版Linux開發庫間逐位、各DET3。這些不是eigen數值或出貨包的驗收。
詳細結果與限制見 evidence/BSI_AGGREGATION/RESULTS.md。

下一步先凍LiveState快照至BSI/frame_v2的opt-in eigen接線，真C10、混合世界、
臨界邊界、JNA與舊回應逐位通過後才能宣告eigen capability，再核驗雙平台native包。
目前發布jar仍是先前版本，未含這批新核/契約；遊戲入口仍SidecarClient。
N16/N18、MC60d/MC66b、GAME_SWAP及原v2/v3/v4欠項保留。
