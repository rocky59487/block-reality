# MC66a 固定細分與 typed 稀疏挫屈 lane

2026-09-09，實作前獨立凍結。父判準 MC66A_BUCKLING_MEMBERS 與 GEOMETRIC_CORE 保留；
本次先交付單一已驗證分量的 typed pencil/求解器，per-island wire/BSI 聚合另外整合。
BUCK_MEMBERS 未全部完成前不宣告 capability，不重打包 v1.3，不換裝 GAME_SWAP。

## 適用性與反例（先於實作）

gate/probe_mc66a_lane_prefreeze.py 以獨立 SymPy 場方程與精確有理矩陣量到：

- K=I、Kg=[[3/2,-1/2],[-1/2,3/2]]，原固定全1 seed 的 Ritz μ=1，殘差0，
  但真正最大 μ=2；回 λ=1 而非1/2。只檢查殘差不足以確認最小正λ。
- 鉸支 EB 梁端轉角按彈性 K 先消去，n1 無彎曲 DOF、n2 λ=10，誤差+1.321184%
  （原 <=1% 線 **MISS**），n4 +0.057104%。保留端轉角則 n1 λ=12、n2 +0.752233%、
  n4 +0.051214%，吻合父 spec 先前用的數學模型。不能以放寬門檻掩蓋不一致。

故本輪先修訂模型適用性，再實作：原 GEOMETRIC_CORE 的 H^T Kg H 核保留且仍回歸，
它是彈性消去映射的正確矩陣變換；但不能當完整 K-λKg 的釋放消去。
挫屈 pencil 保留每個 released local DOF 的私有未知數，K 與 Kg 都在同一個
未消去的運動空間組裝；λ=0 消去私有 DOF 應還原原線性 K。

## 架構與輸入

1. 新 lane 使用同次已成功的 Model/SolveResult 視圖（r.usedOptions、memberEnd），
   不自行重算參考線性答案。這是內部 precondition，不宣稱驗出任意偽造/過期 r。
   非有限資料、錯誤尺寸/選項、失敗線性解、非單一分量要明確拒絕；無半份 pencil。
2. 父節點順序保留；active member 按輸入序、k/subdiv 等分增加內節點；subdiv1..8。
   每個母構件只準備一次完整分段 N 場，子元素裁切重用。點力站位不生成 DOF。
   子元素只在母端保留 release，內部連續；原 Model/u/reactions/end forces 不修改。
3. compileModel 原簽章/路徑保留，加內部選項提供每構件獨立端 DOF。
   既有 extended element 的 L 經局部保留/釋放投影後附私有列；同一 CSC/scatter
   同時用於 K/Kg，原 rigid link/多主 coupling 只處理一次，不另造約束求解器。
   新私有 DOF 不冒充物理節點，不影響原模型、Session 或 wire。
4. budgetDof=0 無限制；正數低於含私有 DOF 的精確自由度數即 scale 拒絕，
   在子網格/稀疏矩陣/因子配置前判定；int/size 溢位、非法區間亦拒絕。
   材料/座標/負剪切面積、未接地、shell、tension-only 依父邊界拒絕。
5. typed state：Computed / NoPositive / NotEligible / Scale / SolverFailed；
   輸入錯誤由 build false/error 表示；Disabled 屬未來 opt-in 接線。
   factor 使用 optional，只有 Computed 才有有限正值。

## 稀疏求解与額外驗證

K 使用既有 sn analyze/factorizeSuperParallel/solveSuper，迭代在 K^-1 Kg 上，
K 內積、兩遍完整再正交化，保留 basis/K*basis；小型投影矩陣用 LAPACK 對稱解法。
最大正 Ritz 值對應 λ；不用最大絕對值。原 seed K^-1*1 保留；子空間 breakdown
時依 DOF 序選取與既有基底獨立的 K^-1*e_i 接續，仍受 maxIter 硬上限約束。

Computed 除物理殘差 ||Kφ-λKgφ||inf/||Kφ||inf<=tol 外，還必須驗
K-λ(1-δ)Kg 的 Cholesky 成功，δ=min(tol,1e-8)；以同一 pattern/symbolic重用，
額外數值分解次數明列成本。精確算術下，正定性給 λ(1-δ)<λ_min，與 Ritz 上界
組成區間；這是依 GSEP 對稱定型轉換的推論，不把浮點 Cholesky 稱為形式化證明。
若條件太差/shift 無法表達/不收斂，回 SolverFailed，不回未證實的 factor。
參考：[LAPACK GSEP](https://www.netlib.org/lapack/lug/node54.html)、
[DPOTRF](https://www.netlib.org/lapack/explore-html/d0/d8a/dpotrf_8f_source.html)。
無正 Ritz 且無壓力 → NoPositive；有壓力卻無正值 → SolverFailed。
K 非 SPD、maxIter 到頂、非有限運算均不得 Computed。

## 本輪可執行硬線

- SL-topology：subdiv1/2/4/8 的 node/member/私有 DOF 數及母構件映射精確；
  新增/移動極小點載重時拓撲與 K 逐位不變；subdiv1/無release 的 K 對原 assembleK 逐位。
- SL-release：釋放私有 DOF 的 λ=0 Schur 補還原原彈性 K（maxabs相對2e-12）；
  鉸支 n1/n2/n4 與自然自由端轉角模型 λ 相對<=1e-10，兩平面/旋轉/耦合皆有腿。
- SL-closed：真線性參考的 Euler/Greenhill n1/2/4：非負誤差嚴格遞減且n1<=1%；
  自然鉸/明確端釋放 n2<=1%；Engesser phi=.5 n4<=1.5%、n8<=.5%。
  保持上一輪同一門檻，預設Euler n2<=.5%。
- SL-dense：與 LAPACK dsygv 的最大正μ對照相對<=1e-8（包括變號N/耦合/約2400DOF
  的具名門架；門架實際尺寸/DOF 必須照登，不能用小 pencil 冒充）。
- SL-seed：上述2×2陷阱與重根/零Kg子空間不可錯誤Computed；足夠maxIter須取得λ=.5
  相對<=1e-12；maxIter1不准返回錯λ。shift拒絕、重新播種必須有會咬變異。
- SL-states：全拉力NoPositive且無factor；shell/tension-only/未接地拒絕；
  K非SPD、maxIter1門架、非法輸入/NaN/overflow/零/負/非有限tol、錯r尺寸皆有腿。
  scale在budget=dof-1拒絕、budget=dof通過；factor iffComputed。
- SL-equivalence：rigid link與合併等價相對<=1e-9，多主coupling能量相對2e-12；
  鏡射λ相對<=1e-12；1e-9*qL點力微擾λ相對<=1e-6，線性輸入答案不被修改。
- SL-provenance：正常+每條具名變異 Windows/Linux/i9 DET3；固定check名單/計數；
  ASan/UBSan零診斷（非LSan）。上一輪142/九變異與Windows原2690回歸。
  原Linux7FAIL不重釘。效能只RECORDED，附fixture@DOF@箱@backend/threads，
  numeric/symbolic分解、迭代/重新播種次數與記憶體規模。

新增變異至少覆蓋：NOSUB、LOAD_MESH、RELEASE_DROP、RELEASE_LOCAL、NOCOUPLE、
N_CLIP、SCALE_OPEN、INPUT_OPEN、OUTPUT_EARLY、NOFILTER_NEG、CONV_LIE、
NO_SHIFT_CHECK、NO_RESEED；必要時拆多條，中央登錄，每条必须具名命中。

## 修訂紀錄
