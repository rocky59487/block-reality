# BSI_RETRY — C ABI 待交付回覆與 logical exactly-once

狀態：2026-09-06 判準先凍；基線 tectonic2 `82d4a53`，block-reality `6cad920`。
對應 tectonic2 #27。採 issue 的 B：prepare-and-cache，沒有一般交易回滾的宣稱。
本判準須各倉獨立 commit 後才改共用 host。既有 C 簽章 / ABI generation 不改；
契約雜湊兩倉一起更新，新舊 host 在 hello hash 檢查時拒絕混搭。

## 1. 所有權與行為（硬線）

每 handle 僅有 Idle、Pending、Failed 三態，由既有 mutex 序列化：
- Idle 驗 frame 與長度上限，先保存 request 全部位元組，再 dispatch 一次、編碼一次。
- Pending 保存完整 request 與 encoded reply。NEED_BIGGER 代表「回覆尚未交付」，
  **不代表未執行**；同一 request 的重試只複製回覆，禁止再次 dispatch。
- Pending 收到不同 request（含只改 id、payload 或 frame flags）回 PROTOCOL，
  不寫 out、不丟 pending、不改 session。request identity 是長度 + memcmp，不能只看 id。
- null out / outCap=0 / 太小：outLen=0、outNeeded=完整固定長度，out 位元組不動。
- 成功：outLen=outNeeded=完整長度，回覆 bytes 與直接給足緩衝的獨立 session 相同；
  釋放 request / reply 儲存，再回 Idle。之後同 bytes 是新呼叫，沒有永久 id 去重。
- 任意失敗先把 outLen/outNeeded 歸零；非 NEED_BIGGER 不回舊長度。
- close 釋放 pending，caller 必須與 call/last_error/close 序列化。不同 handle 獨立。
- 一個 request frame 與一個 reply frame 各最多 256 MiB（含 prefix），cache 最多
  512 MiB；不含 solver / writer 暫存。request 過大在 dispatch 前拒 PROTOCOL。
  reply 過大或 dispatch/編碼例外：Failed、回 INVALID、以後 call 不 dispatch，
  caller 只能 close/reopen；沒有 OOM 後可重試的承諾。
- 不改 stdio / arena、不宣稱跨成功呼叫去重、不宣稱跨程序或 crash exactly-once。

## 2. 獨立 oracle 與 fixture

在 gate 的 engine entry wrapper 計實際 vtable vocab/world/edit/solve 呼叫次數；
wrapper 轉送原引擎函式，不重作算術、不加入出貨 ABI。
同一份 host gate 分別載 counting stub 與真正 tectonic adapter。
合法 world/vocab 取既有 contract conformance 的 cantilever；真引擎 capabilities=[]，
測試須明記 probe / assumeCaps 僅供 harvest，絕不宣稱 bsi.core。
使用 direct-buffer 獨立 handle 取得完整 oracle reply，對照 retry 的所有 frame bytes。

| ID | 驗收（全部 [硬]） |
|---|---|
| RETRY-01 | hello/vocab/world/solve/edit 的 size=0、size=1、need-1、need 邊界；前 3 次 NEED_BIGGER、原 out 不動，最後 OK；每動詞僅 dispatch 1 次 |
| RETRY-02 | vocab retry 成功而非 VOCAB_ALREADY_DECLARED；edit 後 solve 的 world 與獨立 direct handle 相同；request revision echo 正確 |
| RETRY-03 | 同 id 改 body / payload / flags，以及不同 id 的新請求都在 Pending 拒絕；pending bytes 和所有 counter 不變，原 retry 仍成功 |
| RETRY-04 | Pending 時多個 concurrent caller 送不同請求全被拒絕；另一 handle 正常工作；close pending 後新 handle 正常 |
| RETRY-05 | 超過 64 KiB 的真引擎 solve 回覆由 BsiNative 的預設 buffer 擴容後逐位等於 direct oracle，vtable solve counter = 1 |
| RETRY-06 | null/坏 frame/over-limit request 不 dispatch、length 輸出歸零；給足緩衝成功後再次送同 bytes 確實是新 dispatch |
| RETRY-07 | 正常 gate DET ×3 輸出逐位相同，正常回覆 bytes 與同 fixture direct handle 相同；計數首跑後 dated 釘死，不降既有計數 |

變異驗證：
- `BSI_TEST_RETRY_REEXECUTE`：Pending retry 再 dispatch，RETRY-01 / 02 / 05 必紅。
- `BSI_TEST_RETRY_IDENTITY`：Pending 不驗 bytes，RETRY-03 必紅。
測試開關只能在顯式 `BSI_HOST_TEST_MUTATIONS` 建置出現，普通 configure/編譯拒絕。
這是鏡像 contract host 自己的 gate，BSI_TEST_* 不依賴引擎專屬 core/mut.h。
runner 須正常控制臂 PASS + 變異 exit 1 + 具名 FAIL；崩潰或編譯失敗不算咬。

## 3. 收版與誠實邊界

執行共用 host 原 suite / conformance selfcheck、兩倉 canonical LF contract hash 與逐檔比較，
既有引擎數值 gate 不改公式亦不刷新 pins；在可用環境執行 Windows native / 真 JNA；
Linux ASan/UBSan 未跑就是未驗證，不用 Windows 取代。
回覆變更只有修正 retry 的結果及 hello contract hash；既有整個 protocol state machine 不改。
本單元不關閉 #27，直到 issue 的跨平台 / sanitizer / 真引擎 JNA 驗收全有證據。
