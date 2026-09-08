# BSI_RETRY：2026-09-06 實跑證據

狀態：本地實作已驗證，尚未合入兩倉主線。對應 tectonic2 #27。
判準提交：引擎 2a52036 / 3c67a1b；消費者 c51ea9f / 1142b8b。
BSI ABI 仍為 1，契約 hash 由 717aaced… 正式更新為
7104a36d8a5e53f43a88d14a6552d865e7349cf857171dd29961da2fc4936707。
46 個 hash 涵蓋檔在兩倉 canonical Git artifact 逐位相同。

## 實作與驗收

共用 contract/host/bsi_capi.cpp 擁有 pending request bytes 與完整 encoded reply。
NEED_BIGGER 可以已執行，但 retry 永不重入 Session::handle。不同 request 在 pending
期間被拒絕；成功交付釋放快取。prepare 例外使 handle 失效、不能冒險再執行。
每個 request/reply frame 上限 256 MiB；這不是一般 edit rollback 或 crash recovery。

| 臂 | 實際結果 |
|---|---|
| Windows MSVC 14.51，正常 stub / 真 adapter | 各 128/128，DET ×3 |
| Windows mutation | 兩種引擎 × REEXECUTE / IDENTITY，4/4 被具名 assertion 咬到 |
| Ubuntu 22.04 WSL2，g++ 11.4 | 同樣各 128/128，DET ×3，mutation 4/4 |
| 原 host suite | Windows / Linux 90/90，既有計數未改 |
| ASan + UBSan，Linux | host / stub / 真 adapter 全通過；三個 stderr 都 0 bytes；detect_leaks=0 |
| Windows 生產 BsiNative + JNA 5.12.1 | 72,646 B reply，實際 solve vtable 呼叫 1 次 |
| Linux Java 17 + 相同 BsiNative | 72,646 B reply，實際 solve vtable 呼叫 1 次 |
| Java 變異 REEXECUTE | assertion 顯示 actual solve executions=2，exit 1（正常同 fixture PASS） |
| 消費者正式 Gradle :core:bsiRetryGate | PASS，檢查 consumer contract resource 與 native fixture hash |
| 消費者既有 :core:test | 260 tests：220 過、40 跳、0 失敗；未提供舊 sidecar，跳過不算綠 |
| i9-10900F（10C/20T）同 Windows binary | 真 adapter 128/128，DET ×3，兩條 mutation 2/2；SHA256 與本地相同 |

數值臂全部 OpenBLAS CORETYPE=Haswell、NUM_THREADS=4。不做跨箱時間比值。
Java gate 使用測試用 entry wrapper 計實際 vtable 呼叫，原 adapter 原樣連結；
**這顆附計數符號的庫不是出貨庫，不進 jar**。兩倉 CI 已接入持續驗證，但未聲稱遠端 CI 已跑。

## 語料、失敗與範圍

- Contract selfcheck：10 cases，0 problems。
- Stub C ABI 語料：執行 1、跳過 9、hard_red=0；stub 沒有力學，不能代稱語料全過。
- 真 adapter harvest：執行 5、跳過 5，其中 2 案維持 MC64 / MC65a 紅帳；
  hard_red=0、stale_account=0、exit 4（依設計）。capabilities 仍為 []。
- 真 adapter 的 world_edit slot 仍為 nullptr：native 腿證明 UNSUPPORTED / dispatch 0；
  成功 edit 只執行一次由 shared-host counting stub 驗證，不宣稱 MC67 已落地。
- 第一版 runner 的 PASS 計數 regex 沒接 Windows CRLF，工具報 AssertionError；已修為容許 CRLF，
  不改 fixture、128 數或 assertion。128 = 首跑 124 + 4 個失效 handle / 例外檢查。
- Linux 初次 configure 的 PowerShell 引數傳遞丟失路徑，改用 bash 傳入明確 PATH 後成功。
  /mnt/c 的 make 曾記錄小於 2 秒 clock skew；各臂皆全新目錄建置，沒有沿用舊 binary。
- 未量測 256 MiB 真實記憶體壓力上限、OOM 全部發生位置、LSan、遊戲換裝、出貨庫的獨立封装。
- #27 / #26 保持 open：本輪是本地配對 commit，不以測試總數代替合流或發行證據。

## 重現

Windows（先安裝 repo 原有 MSVC / OpenBLAS / METIS 相依）：

    powershell -ExecutionPolicy Bypass -File gate/run_bsi_retry.ps1 -Python <absolute-python.exe>

Linux（正常 LF checkout）：

    bash gate/build_bsi_retry.sh build_bsi_retry
    bash gate/build_bsi_retry.sh build_bsi_retry_sanitize sanitize

Windows autocrlf checkout 使用 gate/prepare_bsi_contract.py 產生獨立 canonical LF 快照；
預設只驗證，修改契約後需顯式 --write 才重新釘 hash，不會改其他工作檔或全域 Git 設定。
Linux 在同一 Windows checkout 可令 BSI_CONTRACT_SOURCE 指向快照中的 contract。

JNA gate：以 gate/bsi_retry/CMakeLists.txt 建 native_clean、bsi_retry_native；
native_clean <C4-json> <frames-directory> 產生 direct oracle 與 request frames，再跑：

    ./gradlew :core:bsiRetryGate -Dbr.retryEngine=<counted-library> -Dbr.retryFrames=<frames-directory>

原始 log 與 binary 留在 .agent-work/bsi-retry；本目錄的 verification.json 記錄雜湊，
各平台三次正常 stdout 的雜湊均相同。未刷新任何數值 pins 或降低既有門檻。
