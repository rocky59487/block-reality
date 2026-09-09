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

## 執行登記

首次 Forge：85 registered / 83 PASS / 1 FAIL / 1 SKIP。失敗是舊 BucklingPolicyTest
要求 BRConfig 仍有 600-block 預設。依本規格與 D-040，遊戲已改送 per-island DOF 預算，
因此該「當前預設」測試需改驗新路徑，舊兩個 policy 邊界測試仍留測試範圍。
新預設 2400 DOF 是可配置的資源上限，**沒有已實測的耗時保證**；舊 sidecar 的 cost table
不能移作新引擎性能依據，這項結論降為未驗。舊失敗 XML 保留，不把退場算作產品性能通過。

首次 Linux server 已到 Done，BSI/JNA 真鋼懸臂回報 1 member / D/C 0.0264。
移除唯一石頭支承後，revision 仍為 1、舊結果未撤銷：確認原事件只關注結構方塊，
漏掉地面改變。此為模組缺陷，GR-5/GR-7 原判準已涵蓋；補地面放置/拆除/鄰域/爆炸通知。
保存 `game-runtime-server-first.json`，停止原 server 後再把修正的模組重建驗證，未修改引擎。

第二次 server：支承移除後 revision 由成功懸臂的值前進至 9，回覆為
`FAILED — BSI analysis: SOLVE_FAILED: no solved island`。地面通知已生效，但 smoke
原先預期 `MECHANISM`，因此第二次仍 FAIL，原 JSON 保留。原生回覆只有錯誤 code/message；
契約明定 message 不是分流契約，Java 不得據字串推斷機構或捏造 island 結果。
此項降為「新 revision 明示原生拒絕且撤銷舊成功結果」的傳輸驗證；全機構的 typed
結果展示仍未驗收。後續 smoke 依 SOLVE_FAILED 與 revision 檢查這條較窄結論，
另外用已支承梁柱板加漂浮梁驗原生 mixed-world 的正式 singular 計數。

故障臂固定具名（執行前補凍）：

| arm | 唯一變更 | 固定 oracle |
|---|---|---|
| OFF_LOAD | runtime 建構時 off 仍呼叫 factory | NativeGameRuntimeTest.offNeverCallsTheLoader |
| CLOSE_IGNORED | requestClose 不設 closing、不增 generation | NativeGameRuntimeTest.closeRevokesBlockedResultsWithoutClosingAnActiveNativeCall |
| UNDECLARED_Y | Axis.UNDECLARED.wire 回傳 Y | PlacementAxisTest.undeclaredLegacyBlocksNeverInventAWorldAxis |

每臂只覆蓋隔離 classpath 中一個 class；必須成功編譯且該 oracle 產生唯一
AssertionFailedError。不得把編譯錯誤、其他測試失敗、SKIP 或 timeout 記成咬合。

最終 Windows core357/Forge85：442 registered / 414 PASS / 28 SKIP；三個故障臂各
1 registered / 1 指定 assertion FAIL。Linux server 最終重跑七個場景全部通過較窄的
模組輸送/狀態條件；混合模型為 2 members / 12 plate facets / 1 unrestrained。
原始指令回覆、首敗、log 摘錄與來源 hash 見 `../evidence/GAME_RUNTIME/RESULTS.md`。
`br status` 目前把 islands 總數標成 solved（4 應為 3），且重算期間沒有列結果 revision；
這兩項真實 UI 缺口保留給下一單元，不把本次 RCON 當 N25 客戶端驗收。

## 開發版操作與遷移

- 仍為 0.4.0-dev；預設 `gradlew build` 的 jar 不含原生庫。
  `-PbrNativesDir=<staging>` 才打入有 provenance 的庫；舊 `-PbrEngineDir` 除 `none` 外會拒絕。
  第一次 Windows 反例因未引用 PowerShell 參數而變成 task-not-found，屬呼叫錯誤，不是門檻通過；
  引用 `'-PbrEngineDir=../dist'` 重跑後由 GAME_RUNTIME 明確拒絕，兩份 log 都保留。
- `BR_ENGINE` 指向已交付且契約相符的 DLL/SO，或在世界 `serverconfig/blockreality-server.toml`
  設 `engine.enginePath`。`engine.mode="OFF"` 或 `analysisEnabled=false` 可在選庫前停用。
  `run.bat` / `run.sh` 已移除拷貝 sidecar 的舊流程；使用 `/br status` 看選庫結果與診斷。
- 舊 `sidecarPath` / `bucklingBlockLimit` 不再用於遊戲；`bucklingDofBudget=2400` 是暫定
  per-island 資源上限，未有相應性能承諾。正式 v1.3 庫契約較舊，不能硬繞握手。
- 新方塊以點擊面的軸向放置；空手潛行右鍵切換 X/Y/Z。舊存檔方塊未宣告時，整輪明示拒絕。
  指令放置可用 `blockreality:steel_beam[axis=x]`，之後 `/br scan` 採入。
  任意六面相鄰的非結構 sturdy 面為觀測 ground；結果/固定度全部由引擎定案。
- `/br reset` 撤銷舊 session 結果，等舊 native 返回後重新載入。native timeout 不能強制終止。
  僅 Linux 隔離 dedicated server 已實跑；沒有聲稱滑鼠互動/材質方向或 Windows 新庫已驗。
