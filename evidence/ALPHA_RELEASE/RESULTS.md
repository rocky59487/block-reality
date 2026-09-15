# Alpha 主線與封裝交付

模組 0.4.0-alpha.1，固定引擎 v2.0.0-alpha.1 / ddaaa92 / runtime 2.0.0 / ABI10。
同時保留 native-fracture-authority 與 transactional-placement 的全部歷史。

完整命令 scripts/package_native.py 由下載回驗相同 bytes 的 SDK 封裝；核心 561 項登錄，
521 PASS / 40 legacy sidecar SKIP；Forge 152 PASS。施工交易另有 37 次 JVM 中断、
37 次新 JVM 恢復及 1 個 concurrent owner 檢查通過。

最終同一 JAR 在 Windows／Linux 上各跑 96 個真 BSI 請求，direct CAPI 與 production JNA
各 DET3 相等，含線性梁／殼挫屈、快取故障、兩 JVM 同時抽取及契約缺失拒絕。
JAR 包含兩平台原生庫、契約、provenance 和授權；308 classes，零可執行檔。

SDK 15 種資產／完整來源篡改均拒絕，4 種 guard 移除被 oracle 抓到；JAR 11 種
篡改／缺檔／額外程式／授權與 schema 錯誤全部拒絕。

修復的發布問題：舊分支堆疊未收主線、舊 sidecar 發布路徑、缺完整 source archive 的
SDK profile、gzip 隨機讀取反覆解壓，以及 ABI10 schema 超過舊 64 KiB 限制被誤拒。
來源驗證現在單次串流核對每個檔案；schema 僅在與 canonical bytes 完全相等時放行。

首次失敗保留：schema 限制、Windows 篡改診斷編碼，以及 WSL /mnt/c 的 execute-bit
語意。Linux 權限檢查改在原生 Linux filesystem 執行，沒有放寬 oracle。

這些測試不代表實际 Minecraft 遊玩或完整 v2 完成。引擎 i9 本次 SSH 逾時、引擎
Actions 帳務阻擋另記於引擎 ALPHA_RELEASE 證據；module 的必要 CI 不得因此跳過。
最終資產 SHA、各平台回應 hash、命令／輸出及未完成邊界見 verification.json。
