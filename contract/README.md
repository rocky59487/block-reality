# contract/ — BSI v1（方塊結構介面）契約，兩倉逐位相同

| 檔 | 內容 |
|---|---|
| `BSI.md` | 契約規格（原則、訊息、記錄、慣例、引擎轉接 ABI、零複製 arena、版本、一致性） |
| `bsi.schema.json` | 機器可讀：header schema、列舉、二進位記錄排布、arena 排布、規範序 |
| `bsi_engine.h` | 引擎轉接 ABI（任何引擎只實作這個 vtable） |
| `conformance/` | 一致性語料（case 格式見其 README）與 runner |
| `check_contract.py` | 重算契約雜湊並與 `CONTRACT_SHA256` 比對（兩倉 CI 都跑） |
| `CONTRACT_SHA256` | 契約雜湊（覆蓋本目錄除本檔與 `check_contract.py` 以外的全部檔案，路徑排序後串接） |

**強制機制**：改契約 = 改本目錄 + `python3 contract/check_contract.py --write` 重算 + **兩倉各自 commit 同一份**。

強制分三層，**強度由弱到強，前兩層各有具名的盲點**（2026-09-03 更正；上一版只寫「只改一邊 → 另一邊 CI 紅」，那句在 CI 層不成立）：

| 層 | 抓得到 | 抓不到 |
|---|---|---|
| **本倉自洽**（`check_contract.py`，N23-a/e）| 改了 `contract/` 卻沒重釘雜湊 | 對面倉改了什麼 |
| **跨倉比對**（CI）| 對面主線的雜湊與本倉不同 —— **前提是真的去抓對面的主線**。block-reality 公開，任何 CI 都抓得到它的 `Main`；tectonic2 私有，需要 token | 沒設定這一步、或比對的是釘死的舊 commit 而非對面主線時，什麼都抓不到 |
| **執行期握手**（`bsi.hello` 的 `contractSha256`，N23-d/N24-b2）| 引擎與消費者實際不同版 → `BSI_VERSION`、引擎停用 | 太晚：已經到玩家的機器上了 |

**只有第三層是無條件的。** 前兩層是為了讓分歧在 merge 之前就被看見，不是為了取代它。
