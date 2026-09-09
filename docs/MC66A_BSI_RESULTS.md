# MC66A BSI eigen 同次快照驗證（2026-09-09）

本單元 local_verified；正式引擎仍 1.3.0、模組 0.4.0-dev。BUCK_MEMBERS 仍 active。
新 native 開發庫已驗證；已發布 v1.3 / 原 jar 的 native 資產未更新。
初凍 a500006、EB 前置 a71e3c3、固定名單 fa2236d；判準均獨立先於實作提交。

## 已接通

BSI eigen 經 LiveState.solveWithBuckling 取得同次參考載重快照；adapter 僅映射所有島與
已定案 block/member flags。core SolverFailed=4 明確轉 BSI=5。現有 ABI 1 不擴充；
budgetDof 0..INT_MAX，固定 subdiv=2/maxIter=300/tol=1e-8。未知進階選項明確拒絕。
材料 Euler–Bernoulli 走共用 buildSection 的材料/斷面 OR，不修改別的材料共用的斷面；
兩材料、顯式/預設斷面、旋轉方向的短梁位移以解析 EB/Timo 式驗證至相對1e-9。

宿主在布局前驗每島完整身分、kind/state/factor，再排序並以島 id 常數時間查旗標。
世界摘要採完整五態順序，局部成功不能掩蓋別島失敗；Java BsiBuckling 保存不可變每島
結果並驗證摘要，BsiAnalysisResult 只 OR 引擎旗標。世界未完成仍保留局部 Critical。
additive Java eigen headers/solve/analyze 不改舊呼叫；沒有重新計算 λ<1。

## 結果

| 驗證 | 結果與範圍 |
|---|---|
| Windows / Linux / i9 | 833 正常 checks；12 個具名故障全部咬合；每臂 DET×3 |
| i9-10900F | 138 傳輸檔 SHA 核對；13 個 exe/結果與 Windows 完全相同；另真 native 334邊界及C10 DET3 |
| Linux sanitizer | 833 checks DET3，ASan/UBSan 零診斷；非 LSan |
| 真 BSI C10 | 原 Euler/Greenhill/probe 語料 PASS；新gate解析相對0.5%、probe1e-6硬線均過 |
| 原生語料 | 三箱 CAPI 九項PASS、一項SKIP（C13 custom）；無assume；Linux stdio-b64/frame/arena 一致 |
| 宿主回歸 | Linux recovery500、retry、host、ABI 四套全部通過 |
| Java core | 334 登錄 = 322 PASS / 12 原有 SKIP / 0 FAIL；15 真 JNA（原13+新2），原Sidecar28執行 |
| Forge | 80 PASS / 0 FAIL；八 Java 隔離變異有具名失敗 |
| Windows原核 | 十套2690 PASS；MC65A161+wire55、MC64 verdict147+wire82、WORLD427、lane579均回歸 |
| 舊回應 | Windows 同箱前版/本版48組完整未請求solve header/payload逐位一致；不冒充Linux跨版本比較 |
| 殼拒絕 | 真 C6 梁殼世界 eigen 明示 not-eligible，完整每島記錄，DET3 |

C++故障 FAIL 數：AGG113、IDS4、KIND5、FACTOR8、ORDER503、CRITICAL1、SNAPSHOT16、
STATE1、BUDGET2、FLAGS4、EB_IGNORE4、EB_SHARED8。精確名字與集合以 bsi_buckling_counts.json
為準。EB_IGNORE 的歷史 registry 標籤 CORE-EB 保留，其物理反例已遷至本 gate 的短梁腿。

## 原始失敗保留

- 833首輪825PASS/8FAIL：測試以整數格址找節點；節點在格中心，改定位不改解析式/線。
- 舊BSI310首次15FAIL：新capability/eigen/EB取代舊拒絕語意；dated遷移並增至334。
- 334首次9FAIL：巢狀buckling schema忽略subdiv/maxIter/tol；補closed schema。
  schema-open原庫以SHA/source/raw保留為 SCHEMA_UNKNOWN 真反例。
- host500首次89FAIL：舊fixture漏兩筆屈曲記錄；補disabled/none/NaN，不改500個回收斷言。
- i9原生初次 DLL 載入失敗：Python依賴搜尋沒有採用PATH；放入相同SHA的相鄰OpenBLAS DLL後重驗。
  第一份native-results與後續native-results-r2並存；原13個C++臂已過，未覆蓋重跑。
- MC64回歸shadow漏wire_pre.txt，首份stderr保留；補原始fixture後147+82通過。
- Java第一次全核心未提供Sidecar而多跳28項，後續明確指定原可執行檔重跑；SKIP不當PASS。

## 來源與可重現性

contract 共55檔（hash覆蓋52），兩倉逐位相同；pin
`4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801`。
raw保存原stdout/stderr base64與SHA、binary SHA、sources SHA；manifest檢查所有證據。
編譯來源以canonical LF（NUL檔不正規化）對照disk/index/HEAD；Java另先核對原始編譯SHA再
記canonical對照。審計程式與原重現helpers在repro；快照與第一次輸格沒有覆寫。
Windows 使用 MSVC 動態OpenBLAS，Linux是本地開發依賴形態，均非新正式自足發布包。

## 未完成與接續

1. frame_v2 opt-in eigen/進階選項接同一快照，保留原screen與未請求回應；勿另建求解器。
2. 更新兩平台native封裝與來源链；現行正式jar仍舊契約，不能混裝此開發庫。
3. GAME_SWAP前驗證world拒絕但局部critical的Forge封包/HUD呈現。現有封包保留boolean，
   HUD目前以hasFactor選分支，仍需明示局部臨界且不捏造世界因子；本輪未實跑遊戲視窗。
4. MC60d/殼挫屈、PE3–5、原v2效能FAIL、v3 P-Delta/co-rot/塑鉸/倒塌、v4整體融合按原帳接續。
沒有新效能勝負宣稱，沒有把原Linux歷史7FAIL或遊戲41.7msFAIL洗綠。

引擎證據入口：rocky59487/tectonic2 的 gate/evidence/MC66A_BSI/RESULTS.md。
