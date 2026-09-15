# 原生碎塊持久交易：consumer core

2026-09-11。判準先行提交 de39475、35e094a、b7047d2；engine pin維持
0fcb60a111f9f39c4912b8d372cb1ec8f02c62af、contract dae6bb356bac7…。
本次不改引擎核心、契約或正式原生套件，版本仍為1.5.0。

GameWorldSnapshot以現有ConstructionLedger提供完整來源身份；InProcessEngine沿
既有BsiNative/五CAPI讀取prepare/finish，驗證完整七段來源/物理/譜系/事件資料。
NativeFractureTransactions沿現有AtomicConstructionCoordinator/FileTransactionJournal
存儲before/after images與完整receipt，持久COMMITTED後才commit原生候選及發布。
恢復時從當前持久世界開新native session；舊token不參與恢復，重放不重新prepare。
既有16MiB record、1MiB Value及piece/resource界限保留，超限拒絕，不截斷資料。

| 實際執行 | 結果 |
|---|---|
| 新consumer驗證 | Windows與Linux各74 PASS，無SKIP |
| 真DLL/SO、production InProcessEngine | 十個既有oracle場景：L、梁殼、機構回滾/前綴、B/C編輯後來源、支承移除/保留、初始機構拒絕 |
| 最終Windows core | 523登錄：483 PASS、40 SKIP、0 FAIL |
| 最終Linux core | 523登錄：494 PASS、29 SKIP、0 FAIL |
| Windows Forge JUnit/編譯 | 124登錄：123 PASS、1平台SKIP、0 FAIL；不是啟動遊戲 |
| 真FileJournal與native進程中斷 | 每平台28個中斷JVM、56個獨立恢復JVM，全通過 |
| 最終三個正常完成故障臂 | 每臂完整74項，來源/plan/提早commit分別1/1/2 FAIL；同classpath control 74 PASS |

既有core的28個legacy sidecar engine測試未指定br.sidecar而SKIP；Windows另有
11個POSIX lifecycle與1個bundled權限case不適用，Linux另有1個legacy bundled
case SKIP。所有適用的BSI/JNA與新增測試均實際執行。API purity、contract自查與
production no-subprocess回歸通過。没有性能、coverage百分比或i9新增Java測量宣稱。

中斷涵蓋prepare/commit/abort的temp-force、replace、target-force、directory-force；
每個participant write/flush、rollback write/flush；native prepare後、checkpoint、
native finish後及publication後。每點重啟後再寫一次合法新世界，再以第二個新JVM
恢復，確認歷史COMMITTED不覆蓋後來編輯。終態request重放100次不重做移除。
單元另驗持久journal decision lost ACK、COMMITTED後native關閉、遊戲publication
失敗；未模擬CAPI指令内部的硬體中斷。Windows沒有Java directory fsync，故
process-interruption結果不代表停電保證。

首敗保留在raw.zip：

- nft-r1：69項，same-stamp不同owner的remaining應拒絕卻放行，1 FAIL。
  修為receipt保留不可變完整source，after-world必須來自同一來源（含ground）。
- nft-identity-before：74項，world domain守門1 FAIL；另1個fixture使用不存在
  的concrete產品而提前失敗，不當作有效反例。
- nft-identity-before-r2：修正fixture後完整74項，錯誤journal domain與同座標
  舊產品/方向graph均被有效反例抓到，2 FAIL；新增兩個正式守門後全過。
- 中間72項、首輪Windows/Linux process與回歸結果也保留，沒有覆寫成最終值。

raw.zip共4,805個原始檔案、2,766,755 bytes；SHA256
056717e7dc8179ef267a769fca2e3830bf18a98ec3364b7cc7c70b330099f35c。
FILES.json記錄每個檔案的長度/雜湊；RECORD.json綁定最終來源raw/LF雜湊、庫hash、
測試數與故障名稱。標準zip解壓，原始命令、sources、JUnit XML與process前後檔案俱全。

重跑：在mod/執行gradlew（Windows用gradlew.bat）`:core:check :api:check`
並給`-Dbr.engine=<同contract的絕對DLL/SO路徑>`；進程驗證另執行
`:core:nativeFractureRecoveryGate -Dbr.engine=<同庫> -Dbr.fractureEvidence=<不存在的絕對目錄>`。

仍未完成：正式Forge fracture Host/玩家入口、chunk與registry整合、原生姿態消費與
動態視覺、接觸/摩擦/滾動/停止、非線性壓碎與完整v2。Host目前是薄接介面，進程驗證
使用file participants，不把ConstructionLedger快照或這次JUnit稱為Minecraft交易完成。
Server仍須捕捉同一次ready ledger epoch並排除並行編輯；此方法無法從Graph推導epoch。
Terminal重放由原Request（含planHash）裁決；新增請求的planHash必須由伺服器以完整
來源及options建立。本次没有Java力學公式、FallingBlockEntity或替代日誌。
