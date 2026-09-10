# 原生碎裂typed契約配對判準

2026-09-11，接native-physical-gravity（462b010）。本單元只同步引擎新增ABI3 typed
準備點的contract及来源pin，不把未完成的wire/JNA/遊戲流程宣稱成已交付。

1. contract/與engine同bytes，CONTRACT_SHA256實際重算一致，來源ref兩行皆指向
   同一份已提交engine契約；舊ABI1/2 prefix與五個CAPI函式保持。
2. 新typed world identity/fracture structs供後续shared-host接線；本單元不新增
   Java欄位、不宣告bsi.fracture或新遊戲能力，也不替換已發布的原生套件。
3. 後續以既有ConstructionLedger/AtomicConstructionCoordinator/FileTransactionJournal
   接世界domain/revision/artifact與候選結果。先持久化遊戲decision再發副作用；
   native失效時重新同步權威世界，不能重造碎塊事件。真JNA及kill/restart之前，
   不宣稱MC exactly-once、倒塌、壓碎或剛體滾動已驗收。

引擎完整判準：[BSI_FRACTURE_AUTHORITY](https://github.com/rocky59487/tectonic2/blob/codex/v2-native-fracture/docs/specs/BSI_FRACTURE_AUTHORITY.md)。

## 2026-09-11 配對結果

引擎來源 `643607f5aa784d6a4f06b4289e16e4b28cb8162b`，契約 `d95cf9a776159c49017e765c6f26eb3ca69f37990cc66737828a84c646dfc146`。
57個實際檔案逐位比對，54個hash覆蓋檔，來源ref及hash同次更新；
contract自查、schema 11 cases/0 problems與5個Python測試通過。
完整檔案hash與指令結果見 evidence/NATIVE_FRACTURE_TYPED/PAIR.json。
引擎typed85／十故障三箱與sanitizer包含最短合法參數尾端不可讀檢查；
本倉未換裝套件，JNA／遊戲倒塌／日誌接線尚待，沿同一分支續做。
