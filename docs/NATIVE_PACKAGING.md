# v1.3 原生庫 jar 封裝

新版開發候選包與MC66a eigen資格見 [NATIVE_CANDIDATE](NATIVE_CANDIDATE.md)；下文保留正式v1.3的重現方式。

2026-09-09，consumer 0.4.0-dev。此流程產生含正式 Windows/Linux x86_64 原生庫的開發 jar。
Minecraft 遊戲迴圈仍為 SidecarClient；此 jar 尚未通過 GAME_SWAP/N25 真世界驗收，不是新的遊戲發布。
引擎正式版本仍 [v1.3](https://github.com/rocky59487/tectonic2/releases/tag/v1.3)，不宣稱 v2/v4 完成。

先取得該 release 的五個資產（含 source.tar.gz、verification.json、SHA256SUMS），
將它們放在同一個目錄。從 consumer 根目錄執行：

```console
python scripts/stage_release_natives.py --release-dir <資產目錄> --out <全新 staging 目錄> --version 1.3.0 --source-commit c90b448194b52b9c7b4a48dc049581d4b640d2d4 --sums-sha256 6a8edd7847b2653d81492c7da1b0126b34d453b2ca24b901098a24e38bbc8504
```

腳本核對外部根 checksum、每個發布資產、來源 archive 的 3,761 個檔案，以及每個
native ZIP 的內容/來源 commit/SDK/契約/庫 hash。輸出 provenance 是最後寫入的完成記號。
它不載入異平台庫；需要自行建庫時仍用原 scripts/stage_natives.py，該流程以真 hello 取得身份。

在 forge 目錄以 JDK 17 相容工具鏈執行（Windows 使用 gradlew.bat）：

```console
./gradlew jar -PbrEngineDir=none -PbrNativesDir=<staging 絕對路徑>
```

jar 位於 forge/build/libs。Gradle 用同一份記憶體 bytes 做 hash 與寫出，拒絕 staging hash/size/
契約不符、平台重複與非法路徑；授權全文和完整來源 receipt 放入 META-INF。
不帶 Sidecar executable；原生庫 libbsi_tectonic.so / bsi_tectonic.dll 由 JNA 在進程內載入。

首次解包位置為 `<engine-root>/lib/<platform>/<完整 SHA256>/<file>`。
每次確認 hash，損壞可重解；契約不合先拒絕，不載入錯庫。啟動不清理舊版快取，
讓共用目錄的不同 JVM 可各自載入。舊版快取會占用磁碟，僅在所有使用該版本的實例關閉後手動清理。
override/config/property/environment 的原優先序維持。

驗收入口：

- `scripts/check_native_release.py`：對真正式資產做十種破壞來源鏈的反例。
- `scripts/check_native_jar.py`：需要 jar、Java、`mod/core/build/classes/java/test` 的 NativeJarProbe、
  JNA 5.12.1 jar、同平台原庫、全新輸出目錄；參數見 `--help`。在 consumer/mod 先執行 `:core:testClasses`。
  Python CAPI 與 jar 中的正式 Java class 使用相同 C5/C6/C8 × f64/f32 的 24 個 frame；
  各三輪與兩 JVM 競爭逐位比對。Windows ACL 與 Linux POSIX 權限拒絕均有實際腿，測後還原測試目錄權限。
- `scripts/check_bundle.py <package-dir>` / `check_bundle_selftest.py <package-dir>`：目錄放 jar、
  provenance.json 及列出它們的 SHA256SUMS.txt。九個注入反例，包括授權文字篡改與未登錄大檔。

Windows 本輪 core327=315PASS/12SKIP、Forge80PASS，共407登錄/395PASS/12SKIP；
13項真 JNA 與28項舊 Sidecar 測試均執行。Linux 僅本段真 jar 門檻，不冒充全套 Java/Linux engine 回歸。
macOS 只有平台名稱邏輯測試，未出庫；無新 i9、FPS 或求解效能宣稱。
判準及修訂見 [NATIVE_CONSUMER](NATIVE_CONSUMER.md)，配對引擎 gate/evidence/NATIVE_CONSUMER 保存原始證據。
