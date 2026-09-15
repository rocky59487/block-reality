# Block Reality — 0.4.0-alpha.1

Minecraft Java 1.20.1 / Forge 47.x 的結構工程模組，使用 Tectonic `v2.0.0-alpha.1`。
本次將原生引擎、施工交易、持久化與顯示流程整合回 `Main`，交付內含兩平台引擎的單一 JAR。
**這是 alpha；完整 v2 與全部遊戲流程的驗收尚未完成。**

[下載 alpha](https://github.com/rocky59487/block-reality/releases/tag/v0.4.0-alpha.1) ·
[安裝與封裝](QUICKSTART.md) · [交付內容與未完成邊界](docs/ALPHA_RELEASE.md)

將 `blockreality-0.4.0-alpha.1.jar` 放入客戶端與伺服器的 `mods/`。
使用 Java 17；Windows x86_64 或 Linux x86_64（glibc 2.35 以上）。
JAR 已含引擎及授權，首次分析時驗證 SHA-256 並在程序內載入，無需另外安裝 FrameCore 或 sidecar。

開發與重新封裝使用唯一入口：

```sh
python scripts/package_native.py --out build/release-alpha
```

命令依 `native-release.json` 下載固定 SDK，驗證來源／契約／二進位雜湊，執行 Java 與 Forge
測試並封裝完整 JAR。引擎倉目前為私有，下載 SDK 需要已有存取權限的 `gh auth login`；
離線資產可用 `--release-dir /path/to/sdk-assets`。玩家安裝已發布 JAR 不需要 GitHub 權限或下載引擎。
直接執行 Forge `build` 產生的是開發 JAR；正式封裝應使用上述命令。

後續開發從 `Main` 建立短功能分支，PR 直接合併回 `Main`。引擎 source commit 與契約 pin
固定於 `native-release.json`、`.github/tectonic2-contract-ref`，升級時一起更新。
舊 sidecar 僅保留於 `sidecar/legacy-dist` 作相容回歸測試，不進 alpha 安裝包。

[先前版本說明](docs/history/README-pre-alpha.md) 保留歷史背景，並非目前安裝入口。
