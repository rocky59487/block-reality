# MC66a 第一單元：共用一致幾何勁度

2026-09-09，實作前獨立凍結。父判準 MC66A_BUCKLING_MEMBERS 全部保留。

## 範圍與分層

先交付元素積分與回收轉接，尚不交付全域 Lanczos、島狀態、BSI capability 或遊戲換裝。
element/geom_stiffness.h 只讀材料、斷面與分段線性軸力；lane/buckling_geometry.h
以既有 MemberStations / memberStationForcesAt 建立積分段。元素層不反向依賴 solve。
區間以原構件的實際 x 表示；呼叫者決定細分端點。點載重位置僅切積分段，不產生節點。
目前站位核在中點切換端力回收；此處也切積分段，保留同一側的運算，避免另寫平衡公式。
不新增任何線性分析路徑，不改舊 bucklingScreen 語意。

端釋放使用 K 的消去映射 H，Kg'=H^T Kg H；不能對 Kg 單獨 Schur 凝縮。
既有三參數 condenseReleases 簽章與算術順序保留，加四參數 overload 取得 H。
元素核輸出未釋放 Kg；配對函式同時計算 K/Kg 的釋放。現有旋轉、rigid link、
extended coupling 變換用於驗證能量相容性；真正全域組裝仍屬後續單元。
所有新函式遇非法/非有限输入、非有限結果、局部釋放機構時拒絕，輸出保留呼叫前值。
MemberStations 是有效同次線性解的內部視圖；本單元不宣稱識別任意過期 SolveResult。

## Oracle 與硬線

1. **GC-polynomial**：獨立 SymPy 由平衡、剪切關係與四個端點邊界解多項式，
   精確有理積分 N(x)w'(x)^2；禁止直接複製 C++ 形函數公式。
   EB、Timoshenko 兩平面不同剪切面積、變號 N、非中點軸力跳躍，矩陣
   maxabs 差 <= 2e-12 * max(1, oracle maxabs)。對稱、軸向/扭轉零塊皆檢查。
2. **GC-recovery**：真 solveLinear 的自重懸臂與偏心站位軸向點力，依解析平衡得到
   分段 N；輸出對照上述獨立矩陣。任意 caller 細分區間的局部座標映射亦驗證。
3. **GC-release**：H 的保留 DOF 為 identity，K 的釋放列乘 H 殘差 <= 1e-12 *
   max(1,maxabs K)；K/Kg 的能量用獨立向量乘積檢查（相對 2e-12）。
   原三參數 K/Q 與新 overload 結果逐位相同；雙端扭轉釋放機構拒絕。
4. **GC-transform**：旋轉與剛性偏置、多主耦合對 K/Kg 的能量保持，相對 <= 2e-12。
5. **GC-closed**：測試程式用 production 元素 K/Kg 組小矩陣，LAPACK dsygv
   求全部廣義特徵值作診斷（這不是 production eigen lane）。Euler 懸臂 n=1/2/4、
   Greenhill n=1/2/4 誤差非負、嚴格遞減，n=1 <=1%；鉸支 n=2 <=1%。
   Timoshenko phi_total=.5 的鉸支 n=4 <=1.5%、n=8 <=.5%。
   沿用父判準解析常數及既有 prefreeze；這些線在本單元直接凍為硬線。
6. **GC-probe**：自重柱另加 1e-9*qL 的非中點軸力，維持相同 caller subdivision；
   最小正 lambda 變化 <=1e-6。全拉力矩陣不產生正特徵值；變號 N 有正負特徵值。
   原 linear solve 的 u/reaction/memberEnd 不被元素積分寫入，逐位比較。
7. **GC-invalid**：空/未覆蓋/重疊/逆序區間、非有限 N/材料/站位資料、零長、
   負剪切面積、非法 caller 區間、機構/溢位均拒絕且不污染輸出。
8. **GC-provenance**：Windows/Linux/i9 同 build 三跑 stdout/stderr/exit 逐位一致；
   每條 check 唯一且釘數，mutation 要指定實際 FAIL，不能把建置或啟動失敗算命中。
   Linux ASan/UBSan 零診斷（非 LSan），舊 Windows 2690 checks 及 MC65B 雙側站位回歸。

## 變異與證據

中央登錄獨立開關：KG_MAXN、KG_MIDN、KG_HERMITE_ONLY、NSIGN、KG_NOSPLIT、
KG_RELEASE_IDENTITY、KG_INVALID、KG_PUBLISH_EARLY；各自對應變軸力、Timo、拉壓號、
非中點跳躍、釋放能量、非法資料及失敗輸出不變腿。
測試 fixture 還要對耦合單側變換的既有變異 TEC_MUT_MC60B_ONESIDEDEXT 會咬。
首次失败 raw 照登；每次 build/執行 stdout/stderr 原始 base64+SHA256；來源清單、
binary SHA、三跑輸出與判決一起保存。暫未釘定的 check 名單只准 HARVEST，
首跑後 dated 追加名單計數，再驗證固定名單；不得調整上方數值線。

## 誠實邊界與後續

本單元通過只代表「一致幾何勁度核心已本地驗證」。BUCK_MEMBERS 維持 active，
下一單元才做固定細分拓撲、共享稀疏組裝、K 正定性、Lanczos 正值篩選/收斂、
島狀態與 wire/BSI C10。Session eigen、殼挫屈、P-Delta、遊戲 HUD/FPS 仍未交付。
本輪不重打包 native，不以 v1.3 的舊二進位宣稱此新核已進入 Minecraft。

## 修訂紀錄
