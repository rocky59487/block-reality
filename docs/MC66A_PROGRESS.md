# MC66a 最新引擎與消費端對位

2026-09-09。正式引擎仍 v1.3/1.3.0，模組仍 0.4.0-dev。
本分支基於模組 #103，對位引擎 #37；BSI opt-in eigen 已接 LiveState 同次線性快照，
完整每島狀態由 BsiBuckling 驗證，contract/pin 沿用上游 4b11cc738790…。

本輪补上 AnalysisResult 每島資料保存，使用 API IslandBuckling，不複製聚合或求解。
世界拒絕仍能取得有效 computed 島因子與局部 Critical；none 回覆保留獨立 disabled 快照。
完整125組消費案例、完整性/不可變與混合摘要封包回歸通過，兩個保存/身分故障臂具名失敗。
Windows core339=314PASS/25SKIP、Forge81=80PASS/1SKIP；Linux選取JNA相關16項全過，
其中真eigen、混合機構世界在f64/f32均驗逐島保存。真原生C10在內9PASS/1C13 SKIP。
原生庫為Linux開發建置，不當作新的Windows/native發布資格。結果見evidence/BUCKLING_RETENTION。

下一步：frame_v2 opt-in eigen沿同一快照、雙平台native包/來源核驗，
混合世界拒絕但局部Critical的HUD文字與真遊戲驗收。現在遊戲入口仍SidecarClient。
MC60d、殼挫屈、PE3–5、原v2效能FAIL、v3非線性/倒塌與v4整體融合按原帳保留。

同期重疊的試作#104/#38已由#103/#37取代；原分支保留其驗證與判準，
本分支不使用原試作契約，也不把那份417項host結果作為本輪新eigen證據。
