# MC66a 雙平台候選包

> 此文件保存模組 #119 最新交付（10146b5，基於 #118）的資格範圍。
> HUD #120 與目前整合候選 c2a1b94 的雙平台完整測試、同 jar 與真遊戲證據，
> 另見 [NATIVE_CANDIDATE_RUNTIME](NATIVE_CANDIDATE_RUNTIME.md)。兩顆 jar 的 hash 與測試範圍不可混用。

2026-09-10。本分支合入固定 #118 dc94b2b4fc4fb9df55f67a635042cb300f6f80e9，
包含 #105–#117 的原生遊戲流程、局部危險語意、資料交付及持久身份等完整模組鏈。
使用已驗的同來源 Windows/Linux 自足庫。這是 0.4.0-dev 候選 jar，正式 v1.3 未被替換。
生產已用原生 session，Sidecar/舊 Java 力學只留在測試；CM/N25/倒塌等完整交付仍待驗。

## 可核對的身份

- 引擎來源：`42e10f5f7af166788588afcd2fb2fd97101d16dc`，version `1.3.0`，buildSha `42e10f5`。
- 契約：`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`，55 檔逐位對位。
- Windows DLL：`9761d73586277614a56132ec3492d324ae83c8489068c00c266c3d56f530acea`，31,769,600 bytes。
- Linux SO：`53aae7156b94c0a761ef5abb2322ad47a708362f417a50ca3f04105d9c50abf1`，28,500,368 bytes。
- 候選根 SHA256SUMS：`f587d8092e0798da4d1f9c8d0e7a4d1b806d64fbf5999d749622757181231cd5`。
- 已驗 jar：`ed3eeb58a360855f03088eb03a7877ec3006623c802ed7e7a7da986f05dc2d90`，15,198,720 bytes。

本機產物位於 tectonic2 checkout 的 `.agent-work/mc66a-native/assets` 和
`.agent-work/mc66a-latest/package`。這些二進位不提交 Git，沒有新的公開 release。
`assets` 有兩個 native ZIP、source.tar.gz、verification.json、SHA256SUMS；
`package` 有上述 jar、provenance.json、SHA256SUMS.txt。

## 沿既有流程重現

引擎負責人以該提交的 tools/release_source.py、build_release.py、package_release.py 交付建包，
其 source manifest 逐檔保存 5,045 份來源；本模組工作只消費已交付資產，不重建引擎。
兩平台需各自以真庫通過 artifact 與原生語料。
在本倉執行（把 `<assets>` / `<staged>` 換成實際絕對路徑，staged 必須不存在）：

```console
python scripts/stage_release_natives.py --release-dir <assets> --out <staged> --version 1.3.0 --source-commit 42e10f5f7af166788588afcd2fb2fd97101d16dc --sums-sha256 f587d8092e0798da4d1f9c8d0e7a4d1b806d64fbf5999d749622757181231cd5 --basis candidate
```

上列 hash 只用於核對本次已驗產物；重新編譯後需保存新產物的獨立收據與根 hash，
不能把這個 hash 套到不同二進位。`--basis candidate` 明示本地資格，不宣稱已發布；
原 published 模式和所有來源/SDK/hash 守門保留。
Forge 用 JDK17 執行 `gradlew build -x test -PbrEngineDir=none -PbrNativesDir=<staged>`，
包含 reobfJar。`-x test` 只用於已完成完整測試之後的組包，不能省略下列資格。

## 本次實測與固定原生來源

原生/i9與來源鏈測試沿MC66A_NATIVE已驗同一DLL/SO；本階段沒有重建或重跑這些項目。
最新模組、reobf jar、兩平台解包/回放與九bundle反例均重新執行。

| 範圍 | 結果 |
|---|---|
| Windows/Linux 原生 | 六匯出、OS-only 依賴；334 邊界、9 語料 PASS / C13 custom SKIP；DET3，C10 已執行 |
| i9 | 複製同一 Windows DLL，61 傳輸檔 SHA 核對；artifact/334/C10 語料 DET3 |
| Windows 最新模組 | core412：400 PASS / 12 平台 SKIP；Forge109 全過，共521登錄/509 PASS/12 SKIP |
| Linux 選集 | Engine5、Recovery8、Analysis1、Buckling2、GameInput3：19項native相關全過；另7項NativeGameRuntime控制器全過，無SKIP |
| 實際reobf jar | 201類別常數池與禁用類檢查；bundled-legacy/dormant-reference兩條可編譯反例被拒絕 |
| 同一 jar | 各平台48完整frame，含C10三情境×f64/f32；原庫/解包庫三輪逐位；兩JVM競爭一致 |
| 防錯 | 6收據反例、10來源鏈反例及4守門移除、9 bundle反例、兩平台匯出/runtime反例；錯身份與解包權限/快取/pin拒絕 |

12 個 Windows SKIP 是 11 個 POSIX fake-process lifecycle 與 1 個 POSIX 權限測試；
native-dependent 全部執行。jar 的 Windows ACL、Linux POSIX 權限拒絕另有真測試。
macOS、Minecraft client/server 實景、FPS 沒有在本單元驗收。GAME_RUNTIME已由#108完成；
#118的Linux真client probe讀數/圖像及三項視覺缺口保持，不能當作本輪CM/N25驗收。
引擎原2690與MC66a物理核本輪不重跑；core/contract源碼與已驗frame_v2保持不變。

第一次收據檢查誤比 corpus stdout 內各轮不同的報告路徑，改為比實際三輪 report；
第一次 staging 抓到本地 pin 的 CRLF，恢復 Git 已提交的 LF bytes，沒有改契約或放寬守門。
原始輸出/來源/產物 hash 見引擎 `gate/evidence/MC66A_NATIVE/RESULTS.md`。
该證據的18da0e77… jar只對應舊#106來源；最新來源與ed3eeb58… jar證據另封在
`gate/evidence/MC66A_NATIVE_LATEST/RESULTS.md`，不改舊證據。下一步依V1_MODULE_PROGRAM
接真client缺口、獨立排程及引擎damage/lifecycle依賴；不重做已完成的原生遊戲流程。

本輪整合head492dc9b的GitHub CI已完成4 job成功；15個native建置/JNA/stage/jar步驟因缺TECTONIC2_TOKEN跳過，不能取代上述本地真庫驗證。引擎head51b8076的7 job因帳務未起跑；詳細收據在引擎gate/evidence/MC66A_NATIVE_CI。後續文件提交不冒充該CI head。
