# MC66a 每島挫屈與共同求解快照

2026-09-09；在任何本單元core改動前獨立凍結。父規格與MC66A_SPARSE_LANE保留。
本單元交付typed世界聚合、共同模型/參考解投影、LiveState opt-in快照與每格/構件判定。
BSI/公開wire尚不宣告eigen；下一單元先同步contract宿主聚合，再接該快照，避免兩種語意。

## 先辨明適用性

父spec §1.1「取最差狀態、有任何computed仍放factor」與§1.6 factor iff computed矛盾。
現有BSI ReplyBuilder::bucklingState遇任一computed便回computed，亦會隱藏其他島失敗。
反例：島0=computed(.5)、島1=solver-failed；父§1.1產出failed+.5，宿主產出computed，
兩者都不能作完整世界成功。此例與以下真值表在實作前凍死；本輪不啟用舊宿主eigen。

本輪採用：有solver-failed→SolverFailed；否則有not-eligible→NotEligible；
否則有scale→Scale；否則有computed→Computed及各computed最小factor；
其餘NoPositive。空集合NotEligible。世界只有Computed可有有限正factor。
Computed+NoPositive表示所有島均已評估，可Computed；任何拒絕/失敗都不藏掉。
每島原值完整保留，拒絕島不能把其他島的有效局部判斷抹去。
Disabled由無請求表示，無請求不產出新快照；未宣告能力/混合screen/eigen仍待BSI契約處理。

臨時施加載重會在LiveState.solve返回前清除，因此挫屈必须在共同solveSnapshot_內，
使用同次Model、SolveResult.usedOptions/memberEnd與IslandAccount，不能在清除後另算。
父spec B7的Session拒絕是v0範圍；本輪加法支援commit Session，與Fresh/Island共用
同一次成功線性解。K_b仍另組另分解；display拒絕，不使用stale display當參考。

## 結構

1. 每島完整圖沿MC65A；所有節點、active構件/殼、rigid link、全部coupling masters、
   四載重族保序投影。集中建立一次分組與全域→局部index，逐島不重掃全世界。
   舊projectModel簽章保留，共用實際重映射器；原線性/fallback運算序與資料不變。
2. 從同次世界線性結果gather該島u/reactions/memberEnd及usedOptions，不能再solveLinear。
   逐島準備/組裝/求解仍用MC66A_SPARSE_LANE；一個pencil完成即可釋放，不保留全世界因子。
3. 未接地或線性未解島→NotEligible/mechanism，含shell→NotEligible/shells，
   tension-only→NotEligible/tension_only；拒絕優先於數值求解，不消耗eigen factor。
   無構件且已解之純固定節點島→NoPositive。材料/載重等非法資料仍明確拒絕。
   全部未解的有效Singular參考可產完整NotEligible世界；非機構線性錯誤不得冒充機構。
4. account與當前模型的圖、維度、solved只能0/1且屬grounded、參考狀態/有限資料均守門。
   世界函式錯誤false/error且out逐位不動；正常拒絕狀態true、完整每島結果。
   任意同尺寸偽造/過期力場仍是內部same-solve前置條件，不宣稱雜湊可驗出它。
5. LiveState新增solveWithBuckling、lastBuckling；既有solve/solveWithAccounting簽章不改。
   共同線性快照成功後、載重移除前建立結果。新實際solve開始失效；不帶請求不計算，
   也不沿用上次buckling。declare/edit/fracture沿既有detailsFresh守門；失敗無半份快照。
   純選項預檢拒絕可保留前一份合法快照，沿原線性API既有語意；不能稱為這次已求解。
6. commit每格/MemberDetail使用其島結果定案一次：Computed且factor<1→Critical/bit2，
   Computed>=1或NoPositive→NotCritical/bit2=0；拒絕→NotEvaluated/bit2=0。
   無owner、retired、未解格必未評估；保留原overloaded/failureType/其他旗標，原dc/mode/u/
   reactions/memberEnd逐位不動。只有新的opt-in出口會增加此判定。

## Oracle與硬線

- WS-aggregate：五態全125種三島組合、排列不變、最小factor與空集合；獨立固定真值表。
- WS-islands：混合穩定/臨界/全拉/殼/rope/浮空/接地轉動機構；完整規範id與各自狀態。
  單島 λ 對獨立構造的同物理模型相對<=1e-9；世界computed最小值；拒絕因子不得出現。
- WS-projection：所有四種載重、座標、釋放、offset rigid link、多主coupling正確重映射；
  同次參考投影u/reactions/end forces逐位；一次分組、每個幾何/載重項只索引一次。
  64個小島只建立一份index、逐島pencil；記錄投影數/自由度，不設新時間線。
- WS-reference：同一世界兩根不同頂載/中點載重柱，比例荷載λ反比<=1e-9；清除載重後
  NoPositive或回到自重因子；不得讀錯全域member索引或默認重算gravity/selfWeight。
- WS-budget：島DOF−1拒絕、島DOF通過，包括端私有DOF；一大一小島大者Scale、
  小者Computed且保留局部臨界bit。maxIter1實際難例SolverFailed；其他島不能被藏掉。
- WS-snapshot：真LiveState的Fresh/Session/Island三路commit；同次原線性數值逐位；
  appliedLoadCount返回0、重複請求無洩漏、無請求getter為空；E-A/E-B/E-C、fracture、
  失敗與display不得曝露過期結果。0.999999999/1/1.000000001因子判定使用double嚴格<。
- WS-invalid：錯圖/尺寸/非有限/錯solved/非機構線性失敗與選項；錯誤原子性。
- WS-regression：舊Windows2690、MC65A161、MC64 verdict147及原579+10/13變異回歸。
  新單元正常/具名变異Windows/Linux/i9 DET3、Linux ASan/UBSan零診斷（非LSan）。
  原Linux7FAIL、41.7ms與v2/v3/v4紅帳不重釘。固定名單首跑後dated独立commit釘定。

至少具名變異：WORLD_ANY_COMPUTED、WORLD_FACTOR_LEAK、WORLD_DROP、WORLD_REFERENCE、
WORLD_GRAVITY、WORLD_LOADS、WORLD_ACCOUNT_OPEN、WORLD_OUTPUT_EARLY、WORLD_REPEAT_INDEX、
WORLD_AFTER_LOADS、WORLD_ALWAYSON、WORLD_STALE、WORLD_CRITICAL_GE、WORLD_CRITICAL_F32、
WORLD_VERDICT_ALL。每條必須實際exit1且固定FAIL集合；缺少DLL/崩潰不算變異命中。

## 修訂

### 2026-09-09 首輪釋放端測例校正（原失敗保留）

Windows/Linux 首輪均364 checks / 363 PASS / 1 FAIL，三跑逐位；原 FAIL
`WS-project 3 lambda` 保留。該Y軸柱 local x=global Y，release[9] 是端j的
local rx，因此脫開的是global ry（全域index4）；測例卻固定global rx（index3），
留下零剛度的global ry，獨立單島與世界投影都正確回mechanism。
校正為固定global ry；釋放條件、荷載、材料、lambda相對1e-9硬線均不改。
這是建立原定「穩定且具端私有DOF」測例的座標校正，不把原機構改判computed。
首輪源碼、raw stdout/stderr與SHA連同校正後具名檢查入MC66A_WORLD證據。

### 2026-09-09 獨立對照入口補強（不改判線）

423項首輪變異harvest中 WORLD_LOADS 刪除投影模型的UDL/point load仍exit0：
單獨構造模型卻也走solveWorldBuckling，使錯誤入口同時污染兩邊。原raw與源碼保留。
WS-projection 的既定獨立同物理oracle改直接呼叫已驗MC66A_SPARSE_LANE的
buildMemberBucklingPencil/solveBucklingPencil，以單獨模型的線性解為參考，不經世界
分組/投影入口；增加對照pencil確實建立的具名檢查。原1e-9相對誤差線與fixtures不改。

### 2026-09-09 具名檢查固定（在固定驗證前獨立提交）

Windows第二次harvest：clean427項全部PASS；15個具名變異均exit1、三跑逐位。
完整順序/檢查名/FAIL集合釘在 gate/mc66a_world_counts.json，後續runner不准自動重釘。
DROP變異刻意漏島導致提前返回：377項、3 FAIL；其餘臂427項。其餘FAIL數如下：
- TEC_MUT_MC66A_WORLD_ANY_COMPUTED: 57 FAIL。
- TEC_MUT_MC66A_WORLD_FACTOR_LEAK: 59 FAIL。
- TEC_MUT_MC66A_WORLD_REFERENCE: 10 FAIL。
- TEC_MUT_MC66A_WORLD_GRAVITY: 8 FAIL。
- TEC_MUT_MC66A_WORLD_LOADS: 4 FAIL。
- TEC_MUT_MC66A_WORLD_ACCOUNT_OPEN: 4 FAIL。
- TEC_MUT_MC66A_WORLD_OUTPUT_EARLY: 11 FAIL。
- TEC_MUT_MC66A_WORLD_REPEAT_INDEX: 19 FAIL。
- TEC_MUT_MC66A_WORLD_AFTER_LOADS: 3 FAIL。
- TEC_MUT_MC66A_WORLD_ALWAYSON: 7 FAIL。
- TEC_MUT_MC66A_WORLD_STALE: 11 FAIL。
- TEC_MUT_MC66A_WORLD_CRITICAL_GE: 1 FAIL。
- TEC_MUT_MC66A_WORLD_CRITICAL_F32: 1 FAIL。
- TEC_MUT_MC66A_WORLD_VERDICT_ALL: 3 FAIL。

WORLD_LOADS現由四個獨立pencil對照lambda檢查咬合。原escape與第一個座標FAIL保留。
固定驗證必須重新建立新目錄並逐名核對，不把harvest本身當最終驗收。
