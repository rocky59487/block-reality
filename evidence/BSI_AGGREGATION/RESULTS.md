# BSI 完整挫屈聚合：本輪驗證

2026-09-09。consumer `codex/mc66a-bsi-handoff` 基於 #102；engine 同名分支基於 #36。
正式引擎仍 v1.3/1.3.0，模組仍 0.4.0-dev。先凍判準，再凍具名變異結果，最後正式重跑。
判準 `docs/MC66A_BSI_AGGREGATION.md`；固定名單 `MC66A_BSI_{HOST,JAVA}_COUNTS.json`。

本輪交付共同 host 的完整島集合驗證與最嚴重狀態聚合，以及 Java 不可變每島 API。
computed 島與拒絕/失敗島並存時，世界保留拒絕/失敗且無世界 factor；局部 Critical 不清除。
Java 保留 supplied flags，不比較 lambda 與 1。Forge 原 channel10 摘要格式無變更。

| 腿 | 結果 | 範圍 |
|---|---|---|
| Windows host | 417/417，6 變異固定 FAIL 集相符，每臂 DET3 | WinLibs GCC 16.1.0 UCRT，`BSI_STATIC`，Release |
| Linux host | 同上；正常 CTest 5/5 | WSL Ubuntu 22.04，GCC，Release |
| Windows CTest | 4/4 | buckling/recovery/retry/host；未宣稱 Windows 動態 stub 建置 |
| Windows Java | core 332：309 PASS/23 SKIP；Forge 81：80 PASS/1 SKIP | JDK 17.0.18；合計 413 登錄、389 PASS、24 SKIP、0 FAIL |
| Java 變異 | 14 項測試；FLATTEN 3、FACTOR_LEAK 2、CLEAR_CRITICAL 1、DROP_ISLANDS 3 FAIL | 四個隔離編譯版本，固定名單相符；工作原碼未被改寫 |
| Linux JNA 相關選集 | 14/14，零跳過 | InProcessEngineTest 5、InProcessRecoveryTest 8、BsiAnalysisNativeTest 1；新建真庫 |
| 真原生契約語料 | 8 PASS、2 SKIP、hard_red=0；repeat 3 | C10 eigen 與 C13 custom section 未提供，SKIP 不算綠 |
| 新舊原生回應 | 18 份非握手 frame 完全相同，各 DET3 | C5/C6/C8 × f64/f32 × vocab/declare/solve；hello hash 為預期變動 |
| 契約 | 52 檔，`91e64576fd2497e990706402ebfca07fe4285e1e331e45b493b99b499c0e286d` | 兩倉逐檔鏡像；selfcheck 10 案、0 問題 |

Windows 跳過的是 11 項需要新原生庫的 core 腿、11 項 POSIX 假程序生命週期測試、
1 項平台權限測試及 Forge 真原生封包 1 項。Linux 14 項補驗 core 原生路徑，
不把它稱為 Windows 真庫或完整 Linux Java/Forge 回歸。
本輪 Linux 庫用 `TEC_STATIC_DEPS=OFF` 建立，為開發驗證物，沒有通過新的發布資格。

六個 host 變異的 FAIL 數為 108/6/3/20/126/2；具體名稱全部釘在 counts，
每次 runner 都比對，編譯失敗或程序崩潰不算命中。Windows/Linux 原始逐項 stdout
與三輪 SHA 存在本機 build 目錄及 `/home/rocky/br-bsi-aggregation-final`；本目錄保存
執行摘要、Java/CTest 日誌、真 native 比對 hash 收據，來源可依 scripts 重跑。

首次未通過的驗證動作照登：Windows 預設 host CMake 的 dllimport、動態 stub 未解符號，
以既有 `BSI_STATIC` 及明列測試目標完成本單元，未修成通用 Windows build；
PowerShell 未加引號的 `-Dbr.sidecar` 被拆成 Gradle task，改用 BR_SIDECAR 環境設定；
`:core:check` 揭露既有 Exec DSL 的 `isIgnoreExitValue` 錯名，改成 `ignoreExitValue` 後通過。
長存工作樹的 CRLF 按既有 `.gitattributes` 正規化 LF 後重新 hash/建庫。
上述為環境/驗證入口失敗，沒有降低數值判線。Java 本輪正常測試未出現數值 FAIL。

下一單元：先凍 opt-in BSI/frame_v2 接 LiveState 同次挫屈快照，再驗 C10、混合世界、
臨界邊界、真 JNA、舊回應逐位與能力宣告。N16/N18、MC66b、MC60d、GAME_SWAP、
雙平台新 native 包與真 Minecraft HUD/FPS 仍未完成；本輪不改發布 tag、不清除歷史紅帳。
