# GAME_INPUT 驗收結果

2026-09-10。模組限定；未修改 tectonic2、BSI 契約或 pin。
先凍 `docs/GAME_INPUT.md`（937a206），具名故障臂再凍（751118e），首跑失敗照登（f730197）。

| 執行環境 | 登錄 | PASS | SKIP | FAIL |
|---|---:|---:|---:|---:|
| Windows JDK17 core 完整 check | 350 | 323 | 27 | 0 |
| Windows Forge 完整 test | 82 | 81 | 1 | 0 |
| Linux JDK17 真 JNA 相關選集 | 18 | 18 | 0 | 0 |

Windows 共 432 registered / 404 PASS / 28 SKIP。core 的 SKIP 是 15 個 native-dependent、
11 個 POSIX fake-process lifecycle、1 個平台 permission 測試；Forge 1 個 native 封包測試。
Linux 是相關選集，不是完整 Java 套件；直接使用先前已交付的開發庫
`/home/rocky/br-bsi-retention-native/libbsi_tectonic.so`。不是新發布庫或雙平台 jar 資格。
該庫 SHA256：`bbf38ff3d4633d4c9e0e7f42fb87e1e3183a6f9d7418ed88432b05392b36c121`。

- GI-1..4：9 個純 Java 測試全過；含 24 組 axis/rotation/joint 與 SI/64-bit revision 保存。
- GI-5：2 個新增真 JNA 測試全過。完整出貨材料詞彙可宣告，鋼懸臂在 f64/f32 的旗標、
  站點、幾何、每島結果對同次世界原始回覆一致；重宣告拒絕後舊詞彙/世界不能使用。
  其餘材料本輪只驗**宣告/映射**，未據此聲稱其結構功能全過。
- GI-6：ID_ORDER / SWAP_YZ / DROP_GROUND 各編譯成功，唯一指定斷言各 FAIL；
  `python scripts/check_game_input.py --out <new-directory>` 可重現，無生產源碼修改。
- Forge 真資源/九種現有方塊 mapping gate 已執行；core API purity 與契約 hash 檢查通過。
- Forge `build -PbrEngineDir=none`（含 reobfJar）成功；36 處文件計數核對通過。
  這是無原生資產的開發 jar，未把它當作可交付遊戲版。

首跑 compileTestJava 少傳 BsiFrame length；首跑 native 17 PASS/1 FAIL 是對沒有 value equality
的 BeamDisplayField 比了物件身分。按原判準改為逐欄比較，未變 fixture/庫/容差。
首跑文件計數查到 RESEARCH_BRIEF/LISTING 尚寫 420，更新為實測 432。
`receipts.json` 的 hash 指本機原始 log；此目錄的文字副本已移除行末空白。

未完成：Forge 玩家軸向狀態/六面觀測、持久 registry、GAME_RUNTIME 的原生載入/關閉/狀態、
舊 Sidecar 生產路徑退場、真 HUD/倒塌/rigid dynamics。這個 commit 完成可測的輸入消費層，
遊戲入口仍是 SidecarClient。引擎側 frame_v2/新原生資產由另一工作負責。
