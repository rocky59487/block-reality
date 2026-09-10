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
