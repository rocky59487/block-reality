# 原生 alpha 發布

引擎標籤 `v2.0.0-alpha.1`，模組版本 `0.4.0-alpha.1`，兩者獨立版控。
`native-release.json` 固定引擎 tag、純三段 runtime 版本、完整 source commit、SHA256SUMS 的雜湊。
`.github/tectonic2-contract-ref` 的 source commit 與契約也必須同步。

`python scripts/package_native.py --out <new-directory>` 下載固定 SDK、嚴格驗證完整 source archive
及兩平台 ZIP，再跑核心／施工重啟／Forge 測試與 bundle gate。離線可加 `--release-dir`；
尚未發布的本地 SDK 加 `--candidate`，provenance 不得冒稱已發布。

將已驗的完整輸出以明確檔名更新受版控的 `dist/`；不加入任何舊 sidecar 或散落 build 產物。
CI 直接從 dist JAR 核對並取出相同 native bytes，跑真 BSI corpus、Java 接線與 JAR/JNA replay。
因此 CI 不需持有私有引擎倉 token，玩家安裝也不會下載引擎。

兩個既有必要檢查名稱保留，且檢查實際發布的原生庫。舊 sidecar 測試仍明確指向
`sidecar/legacy-dist` 作回歸；歷史量測不等於 alpha 性能保證。
合併到 `Main` 後才標記模組 tag。release workflow 要求確切 commit 的必要檢查全過、
標籤與 modVersion 一致、HEAD 為 Main 祖先，核對完整 dist 後發布 prerelease。

完整 v2 的遊戲功能與數值／效能欠項保留，不由 alpha tag 清除。
