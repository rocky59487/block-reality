# MC66a 雙平台候選包

2026-09-10。本分支整合 #105 的各島結果保存、#106 的遊戲输入與 #107 文件對位，
再使用同一來源重建的 Windows/Linux 自足原生庫。正式 v1.3 資產沒有被替換。
這是 0.4.0-dev 候選 jar；遊戲入口仍 SidecarClient，未完成 GAME_RUNTIME / HUD 實景。

## 可核對的身份

- 引擎來源：`42e10f5f7af166788588afcd2fb2fd97101d16dc`，version `1.3.0`，buildSha `42e10f5`。
- 契約：`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`，55 檔逐位對位。
- Windows DLL：`9761d73586277614a56132ec3492d324ae83c8489068c00c266c3d56f530acea`，31,769,600 bytes。
- Linux SO：`53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1`，28,500,368 bytes。
- 候選根 SHA256SUMS：`f587d8092e0798da4d1f9c8d0e7a4d1b806d64fbf5999d749622757181231cd5`。
- 已驗 jar：`18da0e77caf1c217edd2de52d11401e585417df79fe876bed827e8c5d5b871f6`，15,148,828 bytes。

本機產物位於 tectonic2 checkout 的 `.agent-work/mc66a-native/assets` 和
`.agent-work/mc66a-native/package`。這些二進位不提交 Git，沒有新的公開 release。
`assets` 有兩個 native ZIP、source.tar.gz、verification.json、SHA256SUMS；
`package` 有上述 jar、provenance.json、SHA256SUMS.txt。

## 沿既有流程重現

先由引擎該提交的 tools/release_source.py、build_release.py、package_release.py 建包，
其 source manifest 逐檔保存 5,045 份來源。兩平台需各自以真庫通過 artifact 與原生語料。
在本倉執行（把 `<assets>` / `<staged>` 換成實際絕對路徑，staged 必須不存在）：

```console
python scripts/stage_release_natives.py --release-dir <assets> --out <staged> --version 1.3.0 --source-commit 42e10f5f7af166788588afcd2fb2fd97101d16dc --sums-sha256 f587d8092e0798da4d1f9c8d0e7a4d1b806d64fbf5999d749622757181231cd5 --basis candidate
```

上列 hash 只用於核對本次已驗產物；重新編譯後需保存新產物的獨立收據與根 hash，
不能把這個 hash 套到不同二進位。`--basis candidate` 明示本地資格，不宣稱已發布；
原 published 模式和所有來源/SDK/hash 守門保留。
Forge 用 JDK17 執行 `gradlew build -x test -PbrEngineDir=none -PbrNativesDir=<staged>`，
包含 reobfJar。`-x test` 只用於已完成完整測試之後的組包，不能省略下列資格。

## 本次實測

| 範圍 | 結果 |
|---|---|
| Windows/Linux 原生 | 六匯出、OS-only 依賴；334 邊界、9 語料 PASS / C13 custom SKIP；DET3，C10 已執行 |
| i9 | 複製同一 Windows DLL，61 傳輸檔 SHA 核對；artifact/334/C10 語料 DET3 |
| Windows 模組 | core350：338 PASS / 12 平台 SKIP；Forge82 全過，共432登錄/420 PASS/12 SKIP |
| Linux 真 JNA 選集 | Engine5、Recovery8、Analysis1、Buckling2、GameInput2，18/18，無 SKIP |
| 同一 jar | 各平台48完整frame，含C10三情境×f64/f32；原庫/解包庫三輪逐位；兩JVM競爭一致 |
| 防錯 | 6收據反例、10來源鏈反例及4守門移除、9 bundle反例、兩平台匯出/runtime反例；錯身份與解包權限/快取/pin拒絕 |

12 個 Windows SKIP 是 11 個 POSIX fake-process lifecycle 與 1 個 POSIX 權限測試；
native-dependent 全部執行。jar 的 Windows ACL、Linux POSIX 權限拒絕另有真測試。
macOS、Minecraft client/server 實景、FPS、GAME_RUNTIME 沒有在本單元驗收。
引擎原2690與MC66a物理核本輪不重跑；core/contract源碼與已驗frame_v2保持不變。

第一次收據檢查誤比 corpus stdout 內各轮不同的報告路徑，改為比實際三輪 report；
第一次 staging 抓到本地 pin 的 CRLF，恢復 Git 已提交的 LF bytes，沒有改契約或放寬守門。
原始輸出/來源/產物 hash 見引擎 `gate/evidence/MC66A_NATIVE/RESULTS.md`。
下一段接混合世界局部 Critical HUD、Forge 觀測採集與 GAME_RUNTIME；Java 不重算力學。
