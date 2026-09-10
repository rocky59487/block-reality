# PHYSICAL_GRAVITY_NATIVE — 原生物理自重交付

2026-09-11初凍，engine7905b23／consumer Main d32c744。先於實作獨立提交。
接PHYSICAL_SELF_WEIGHT之PMA／PSW／PGM共同路徑，不在adapter或Java補算重量。
父MASS／完整v2及原Linux pins、41.7ms、父S1欠項保持。

## 契約與相容

`bsi.solve`增加`massModel: "analysis" | "physical"`，預設analysis，能力`bsi.mass.physical`。
每次請求決定模式，不沿用上次physical。selfWeight仍只控制重力載荷，gravity仍SI；
舊不查詢的完整response bytes維持（hello的新capability／contract hash另作預期加法）。
既有equilibrium結果足以核對總載荷；本單元不增加推測的碎塊記錄或第二份質量公式。

`bsi_solve_options`的layout和selfWeight非零語意完全不改，不借padding／includeMask
或boolean高位藏物理模式。使用原有append-only engine vtable的ABI2：尾端新增可選
solve_v2，參數`bsi_solve_options_v2 { uint32_t struct_size; bsi_solve_options common;
uint8_t massModel; }`。先檢查struct_size涵蓋massModel，再讀common／massModel；未知模式拒絕。
legacy solve永遠使用analysis。新adapter支援ABI1與2，ABI1不宣告physical能力。
host先協商2，僅entry回NULL時再問1；回傳ABI必須等於所協商版本，不能接受錯標vtable。
host在ABI1上只讀原prefix，physical需要能力且ABI2與非NULL solve_v2；拒絕必須發生在呼叫前。
BSI wire major與bsi_capi的五個簽章／ABI維持1；stub／counting fixture同步正確版本界線。

兩倉contract同bytes／hash獨立提交；schema／host／adapter依此實作。physical呼叫只啟用
LiveState的共同模式；資料無法由PMA／PGM表示則具名拒絕，不能靜默退回analysis。
此單元不宣告bsi.world.edit或rope非線性自重；世界編輯的native驗收使用當前支援的完整
world.declare快照序列，C++真E-A／B／C驗收繼續由PGM負責。

## 硬線與oracle

沿用PHYSICAL_SELF_WEIGHT質量／總力／一階矩[硬]2e-12*max(1,abs(oracle))，
一般位移／內力[硬]2e-10*max(1,abs(oracle))。獨立材料尺寸／密度／格數推導總重，
不以新映射自己計算自己的oracle。5格H400x200、tw=.008、tf=.013、rho7850為321.536kg。

1. 真DLL／SO及JNA：2／5／10／20格柱與原四門架，完整材料重量；任意向量gravity
   的equilibrium.applied=m*g，支承反力相反。明確設selfWeight=true，禁止零載荷假綠。
2. 5格柱側向：以PGM解析外伸半格的端力／端矩與構件內力核對；FE長度仍4m，
   材料量5m，不用改stiffness／rho／fill湊重量。fill序列1→.5→.75、移除／放回、模式
   physical→analysis→physical、自重關閉／重力改向，與C++同輸入的完整結果核對。
3. 既有BSI各適用C家族、原frame與真native讀回保持；整體core未改時不重複跑原2690
   冒充新證據。必須驗BSI host原套、C API重試、schema／契約對位與consumer既有解析測試。
4. 缺capability、ABI1、NULL solve_v2、錯ABI宣告、struct_size不足、未知massModel、
   schema錯型／未知值均具名拒絕；拒絕不呼叫不支援的slot，不能讀過舊options／vtable。
   真旧ABI1 build與新host雙向相容；新adapter對舊host1回覆舊prefix及能力。
5. DET3為同build完整payload／穩定response；禁止把不同buildSha／contractSha自動改成相同
   而宣稱整個hello逐位不變。Windows／Linux／i9同Windows binaries，LinuxASan／UBSan；
   該DLL／SO的真JNA與Minecraft生產呼叫需確實攜帶physical選項，不能只留下測試旁路。

預授PGN故障：MODE（忽略physical）、STICKY（未切回analysis）、CAP（漏能力守門）、
ABI（漏ABI／slot守門）、SIZE（漏新選項長度檢查）、RANGE（錯模式被接受）、LEGACY
（舊slot變physical）、ENTRY（錯版本协商被接受）、CLIENT（Java漏physical欄位）。
每條都要有可編譯可具名FAIL的反例，原輸出／來源／首FAIL保存。首完整run追加釘計數，
不能以少量C++或ctypes案例替代真JNA／生產呼叫。首輪不適用或SKIP不得算通過。

## 交付邊界

consumer只傳要求／消費原生结果，遷移說明註記小結構的絕對自重改變。舊正式庫沒有
physical能力，不能靜默假裝已啟用；啟用生產請求時須同時提供相容候選庫／明確能力拒絕。
完成原生接線後更新MASS工作帳及#94的實測證據；仍不等於持久碎塊身份、摩擦滾動、壓碎、
非線性、遊戲外觀或完整v2完成，不提前關閉那些出口。

## 2026-09-11 A1 — 首輪 typed／host 計數

首完整 Windows MSVC19.51 執行保留於 `.agent-work/pgn-first/windows`：
79 checks／0 failures，三次完整 stdout／stderr／exit 逐位相同。此處獨立釘死
typed adapter＋host gate 計數79；原物理硬線完全不改。八條native故障沿用初凍，
具名失敗及真DLL／SO／JNA／舊ABI1相容仍待完成，不據首輪關閉PGN或MASS。

## 2026-09-11 A2 — 真庫／JNA首輪與模式遷移回歸

Windows／Linux真DLL／SO首跑均571 checks／0 failures：每次66個完整response，
三次逐位重現；34個成功typed payload逐位對應，typed另有2個未知模式拒絕payload。
JNA首成功235 checks／0 failures：66 frames×3及遊戲production入口的矩形柱解析root force。
以上計數現在釘死；typed／host仍79。八故障Windows／Linux具名FAIL數依序為
MODE22、STICKY10、SIZE4、RANGE2、LEGACY9、CAP1、ABI2、ENTRY1。
最初typed stdout只列判斷，不據它宣稱payload DET；後續pgn-payload保留完整typed bytes。
Linux首建既有sn_chol計時c0因adapter抑制fprintf而unused，首FAIL保留；runner只允許
該warning類別以warning可見，其餘仍Werror。首JNA命令的PowerShell參數拆分失敗亦保留。

consumer production GameWorldSnapshot現在明確選physical，直接solve舊overload仍analysis。
首既有core回歸449登錄／1FAIL／40SKIP保留：GameInputNativeTest把physical遊戲結果
與預設analysis比較，maxDC為0.054844759821428536對0.047831485714285686，揭示模式差異。
對位比較改為明確physical；原4格FE自重reaction oracle另保留在legacy呼叫，並新增
5格完整材料reaction oracle。這是已預授的opt-in遷移，不改容差、不刪原例、不洗掉SKIP。
新舊host／engine四配對已用7905b23真正ABI1來源重建，Windows／Linux各DET3；
仍待CLIENT故障、i9、sanitizer、其餘契約／消費者回歸與交付整理，PGN未關閉。
