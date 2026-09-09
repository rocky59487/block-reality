# GAME_RUNTIME：遊戲切入原生引擎

2026-09-10；模組限定；先凍判準，再實作。接續 GAME_INPUT / PR #106。

## 執行與停用

遊戲只接受 inprocess/off。analysisEnabled=false 或 mode=off 要在 locate/unpack/load **之前**擋住。
每維度一份原生 session；declare/solve/reset/close 在 worker 序列化。主執行緒只採集快照、
提交工作與 apply；不得等待原生鎖。關世界/關伺服器先令結果失效，關閉在 daemon cleanup 執行，
即使 native 不理會 interrupt，也不能回主執行緒 close。已關閉維度不接收遲到結果。

原生逾時不是殺掉 native：達 requestTimeoutMs 後停用該 session 並撤銷結果，
worker 若返回才做安全關閉。native crash 仍可能帶掉 JVM（D-044），不冒稱程序隔離或可強制中止。
reset 在舊呼叫返回後重建 session，不能並行操作同一實例；新世界不得沿用舊世界 session。

庫位置遵守 config → system property → environment → override → bundled，且**明確指定但無效要拒絕**。
這會取代 EngineLocatorTest 中「invalid config falls through」舊假設；屬選庫行為修正，
不是削弱測試。具名候選從此不能悄悄換成另一顆庫。只檢查已存在路徑，不下載資產。

## 玩家宣告與採集

方塊保存明確 axis；新放置採玩家點擊面軸向，與原版 log 放置語意一致；旋轉不重猜幾何。
旧存檔沒有 axis 的方塊使用 undeclared 狀態，不能偷偷以 Y 代替；引導玩家重新宣告。
空手潛行右鍵可循環 X/Y/Z 並使 revision 前進。資料取樣對未知軸向明示拒絕整輪，
不傳缺件世界再假裝其餘正常。正常右鍵/眼鏡載重操作保持各自用途。

六面相鄰可站立面是已觀測 ground。不可讀取未載入 chunk；未知鄰域與缺件一起標示 incomplete。
支承只傳独立 ground 記錄，固定度交給引擎。完整 snapshot 通過 GAME_INPUT 後進 native。
原 native eigen 使用 DOF budget；舊按 block 數關閉篩選的策略不冒充新預算。
monolith/frame/panel 依 D-030 與產品目錄，舊 concrete/brick 名称同步標示實心材料語意。

## 固定驗收

| gate | 固定要求 |
|---|---|
| GR-1 | off 不呼叫 loader；首次 solve 才開庫，成功後重用；各維度獨立，缺能力/詞彙/hash 失敗明示 |
| GR-2 | 阻塞 fake native 中 requestClose/reset/逾時通知可返回，未釋放 barrier 前不得 close native；返回後舊結果不可用；cleanup 恰一次 |
| GR-3 | closed 不重開；reset 只在 idle 後重建；同 session 最大並行呼叫數=1；failed load 不無限重試 |
| GR-4 | invalid config/property/env 候選拒絕；不落到有效 bundled；invalid path 不炸 server；off 不自解 |
| GR-5 | 三軸放置/旋轉/舊存檔未宣告與六面採集有測試；編輯進 revision，過時結果不 apply；未知鄰域明示不完整 |
| GR-6 | StructureManager/BRNetwork/BRCommand/client 不依賴 SidecarClient；NoSubprocessTest ALLOWED={}；生產 jar 無 SidecarProcess，零 exe；legacy 僅測試用 |
| GR-7 | 真 native → GameRuntime → AnalysisResult，N19/N20 旗標/樣本一致；Linux server 啟动，柱/樑/板/機構世界有分析與明示失敗；具名來源/hash log |
| GR-8 | Windows/Forge build、所有相關 Java check；原生缺件列 SKIP；N25 客戶端與最新雙平台發布資產未跑前，仍不能發布已完成換裝的正式版 |

GR-2 的同步測試用 barrier/future 證明「不等 native」，不把測試機耗時當效能認證。
具名錯誤臂：忽略 closed 結果隔離、off 仍載入、未宣告軸向代 Y；每臂須編譯成功且相應斷言 FAIL。
第一個原生伺服器啟動可用已交付 Linux 開發庫；不建或修改引擎倉，正式資產等引擎團隊交付。

## 舊帳與非完成項

#86 持久 registry 本單元仍待後續，不以 chunk 目前載入集合當 v1 最終設計。
#89 只有 GR-7/N25 實跑與發布 jar 接線證據齊備才可結案。
Java 舊 StressFieldSpec/ShellFieldSpec/legacy codec 的移除另需使診斷改吃 samples；
此單元先移除可執行的生產 Sidecar 路徑，不聲稱全倉零後處理已完成。
