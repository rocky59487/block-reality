# 原生每島結果在共用 API 的保存

2026-09-09；接續模組 #103 / 引擎 #37，實作前凍結。
同期完成的上游 BSI eigen 已包含五態聚合與完整 codec，本單元沿用其 contract、
BsiBuckling 與 native pin，絕不保留另一份聚合或契約。

仍缺的消費端接點：BsiAnalysisResult 解碼出完整 Snapshot 後只把摘要放入 AnalysisResult，
丟掉逐島 state/factor，後續呈現無法分辨臨界島與拒絕島。

- 加法 API IslandBuckling，AnalysisResult 保存不可變完整列表，舊建構子維持。
- 僅映射上游已驗證 Snapshot，不重算聚合、factor 或 Critical；保留每島 id/kind/state/f64。
- 獨立125個混合狀態案例通過，拒絕世界無全域因子但保留局部 Critical；原三態/空集合/非法輸入守門維持。
- 真 JNA 的單島/混合機構世界在 f64/f32 均保存完整島結果；none 不沿用 eigen 快照。
- Forge 混合摘要 round-trip 保留拒絕 state/零世界 factor/true Critical。
- DROP_ISLANDS 與錯島 identity 兩個隔離源碼變異必須具名測試失敗；正常 Java/Forge 與 API purity/checkContract 通過。
- 修正執行既有 :core:check 揭露的 Gradle Exec 屬性錯名；不換遊戲入口、不發 native 包。

先前試作模組 #104 / 引擎 #38 已由同期上游接線取代；原驗證與提交留在原分支供追溯。
這份規格不把此前舊契約的417項宿主驗證當成新契約或新eigen的證據。
