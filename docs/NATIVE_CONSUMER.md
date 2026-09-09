# NATIVE 消費者原生庫封裝判準

2026-09-09，實作前凍結。來源：SWAP_PROGRAM N19–N24、consumer #90、
RELEASE_V1_3、NATIVE_CONSUMER_HANDOFF；配對 consumer docs/NATIVE_CONSUMER.md 逐位鏡像。

## 範圍與來源

[硬] 本單元使用正式 v1.3 / 1.3.0、來源 c90b448194b52b9c7b4a48dc049581d4b640d2d4
的 Windows/Linux x86_64 庫，契約 hash 保持
4977f57308e6520a6df1903013d757a62546e20809d57a9cf0bc6009ee3b3253。
來源链須核對已發布 SHA256SUMS 的外部可信 hash、ZIP hash、內部清單每檔 hash、
provenance 的來源 commit / version / contract / binary size+hash，再核對 staged 與 jar bytes。
拒絕缺少平台、重複平台、路徑越界、来源/契約/hash 不符；不把自報的 provenance 當作簽章。
自建庫原 stage_natives.py 仍以真 bsi.hello 取得身份；正式資產 staging 不在異平台假裝載入。

## 解包與共用規則

[硬] 清單最多 64 KiB；非空資料列須七欄、每平台唯一。契約不符或模組缺有效 pin，
在打開庫 resource 或採用快取前拒絕。保持清楚診斷與 Optional.empty，不能逃逸至模組載入。
[硬] 快取身份 lib/<platform>/<完整 SHA256>/<file>；同 bytes 可重用；不同 hash 或平台隔離。
本次明確取代舊 anOlderLibraryIsRemovedButNothingElseIs 的清除行為：啟動時不清理其他版本，
因其可能正被另一 JVM 準備載入。舊測試來源/commit保留，本單元改為驗證舊目錄及新版本均保留。
[硬] 唯一暫存檔、串流 hash、限制最多寫入宣告 size，額外一 byte 即拒絕；短讀/錯 hash/IO失敗
均不可發布壞 target，失敗移除本次暫存。有效 target 不重寫；既有腐損可修復。
[硬] 至少兩 JVM 同 cache 啟動可取得相同完整 bytes；版本隔離、錯 bytes、中途 IO失敗、
權限拒絕、競爭移動採用正確 target 各有測試。POSIX 不增加 executable bit。
平台正規化共用既有 BundledEngine 規則，Darwin 不得誤判為 Windows；macOS 僅邏輯測試。

## 真 jar 與回歸

[硬] 同一 jar 包含兩個正式平台庫、正確 contract resource、授權及來源資訊，
check_bundle 真 ELF/PE 分支 PASS，零 Sidecar executable；原 native staging / override 優先序保留。
[硬] Windows 與 Linux 各自從該 jar 解包、進程內 hello、梁與殼求解。
封裝前同一庫與封裝後庫使用完全相同 BSI frame，numThreads=1，完整回應 bytes 相同；
三次同 build 重跑逐位相同。同時保留解析梁/殼 oracle 的既有真 JNA 測試，避免只用自比宣稱數值正確。
[硬] 核心與 Forge 全套、API purity、契約逐位、program/registry/doc-count 守門及 diff check。
首次失敗照登；所有新拒絕/位元/隔離宣稱須有對應會被 assertion 咬住的變異。
原始命令、stdout/stderr/XML bytes 以 base64+SHA256 保留，輸入與 staged/commit Git blobs 核對。

## 誠實邊界

不改 engine 核心/BSI；不宣稱新的求解效能。尚未替換 Minecraft 遊戲迴圈的 SidecarClient，
本單元不完成 GAME_SWAP/N25 真世界 HUD/FPS、MC66 或 DISPLAY_BAND。
Linux WSL 與 Windows 各自具名；macOS/i9 未执行不得宣稱。保留原 41.7ms FAIL、Linux7FAIL，
v2/v3/v4 仍為長期目標；本單元驗收不自動发布新引擎版本。

## 2026-09-09 身份欄位實測追記

首次真 hello 驗收因把 buildSha 當成完整 40 字元而 FAIL；正式 v1.3 的 wire buildSha
實際為 c90b448，release provenance/source archive 為完整 c90b448194b52b9c7b4a48dc049581d4b640d2d4。
两條各驗其原格式：發布鏈仍須完整 commit 相等，wire 必須精確為 c90b448，且所載庫的全 SHA
須等於已驗的正式庫。不以任意前綴放行其他庫；清單 engineVersion 使用實際七字元 buildSha。
hello 的 threads 為引擎可用容量；本單元 open 與所有 solve 請求均明寫 numThreads=1。
