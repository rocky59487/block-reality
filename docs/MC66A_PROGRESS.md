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

下一步先凍BSI宿主五態聚合、kind/disabled與完整島集合，再同步contract/Java codec，
接同一LiveState快照至BSI/frame_v2。現有host任一computed便回世界computed的邏輯
必須先修；不可在Java或adapter複製求解或lambda比較。真C10、JNA與舊回應逐位後
才能宣告eigen capability，並重建/核驗雙平台native包。

目前native jar仍是先前版本，未含這批新核；contract54檔、capability、pin與遊戲入口不變。
N16/N18、MC60d/MC66b、GAME_SWAP及原v2/v3/v4欠項保留；不把typed出口當已遊戲換裝。
