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
只改一邊 → 另一邊 `check_contract.py` 紅、`bsi.hello` 的 `contractSha256` 對不上 → `BSI_VERSION`。
