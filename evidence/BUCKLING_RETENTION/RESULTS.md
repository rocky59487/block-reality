# 原生每島結果保存：驗證結果

2026-09-09，接續模組 #103（b91c575）/引擎 #37（9ab3e2d）。
契約與引擎 pin 完全沿用上游，hash `4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`。
判準先凍 `491e7f4`，變異名單先凍 `4407f96`，實作與驗證分開保存。

| 驗證 | 結果 |
|---|---|
| Windows core | 339 登錄，314 PASS / 25 SKIP / 0 FAIL |
| Windows Forge | 81 登錄，80 PASS / 1 SKIP / 0 FAIL |
| Linux JNA 相關選集 | 16/16 PASS，零跳過：Engine 5、Recovery 8、Analysis 1、Buckling 2 |
| 125 種混合消費狀態 | 完整逐島 id/kind/state/f64、世界摘要與不可變列表通過 |
| 真 native eigen | 單島/浮空混合、f64/f32，computed 島保留因子、拒絕島保留 NaN；none 無 stale eigen |
| 隔離 Java 變異 | 14 項用例，DROP_ISLANDS 3 FAIL，WRONG_ID 1 FAIL，固定名單吻合 |
| 真原生契約語料 | 9 PASS / 1 SKIP，含 C10 Euler/Greenhill；三次重跑，hard_red=0 |
| 其他 | API purity、checkContract、Gradle check、36 處文件數字與 diff 檢查通過 |

Windows JDK17.0.18；Linux WSL Ubuntu22.04/JDK17，原生庫為本機從引擎 #37 來源
建的共享依賴開發庫（TEC_STATIC_DEPS=OFF）。未重建 Windows 新原生庫。
Windows 跳過 core 原生 13、POSIX 程序生命週期 11、平台權限 1、Forge 原生封包 1；
Linux 選集補驗原生路徑，不冒充完整 Linux Java/Forge 回歸。C13 custom section 仍 SKIP。

本輪唯一新增產品資料是 AnalysisResult.bucklingIslands，直接映射已驗證 BsiBuckling
Snapshot。未新增世界聚合器，未改力學或 lambda 判定，舊建構子仍可用。
Forge 增加混合世界摘要的回歸測試，封包布局不變；HUD 與遊戲視窗未實跑。
另修復原有 `:core:check` 的 Gradle Exec 屬性錯名 `isIgnoreExitValue` → `ignoreExitValue`。

同期完成的 #103/#37 已涵蓋較早試作 #104/#38；本輪以新契約接續，原試作分支保留。
這裡的 native 證據只對本輪新來源成立，不沿用原試作的417項宿主宣稱。
正式版本/發布資產未改。frame_v2 同快照、新雙平台包、混合世界 HUD 與 GAME_SWAP 留待後續。
