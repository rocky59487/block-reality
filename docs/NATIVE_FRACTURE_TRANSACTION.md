# 原生碎塊與既有持久交易

2026-09-11，基線2422c22／engine 0fcb60a；判準先於實作獨立提交。
沿用ConstructionLedger、AtomicConstructionCoordinator及FileTransactionJournal。
這是v2完整倒塌生命週期的必要接線，不以橋接單元代替Forge/碰撞/非線性驗收。

1. GameWorldSnapshot以同次ConstructionLedger.Graph提供來源所有權；namespace、
   domain非零UUID，revision為原非負i64。每個結構格恰有一個正i64 artifact；缺格、
   外來格、重複來源或不完整graph拒絕。Java不建立物理分組或重新計算質量。
   原公開建構子及analysis overload保持；新的BsiFracture.World保存完整原生blocks，
   包含斷裂後暫時不相鄰的地基，不能偷偷改掉native候選的after-world。
2. InProcessEngine新增identified declare、prepare及finish，capability先驗；原
   five-CAPI/BsiNative/response decoder共用。prepare明確只支援契約已有的物理自重，
   非空外載不得省略。header ID/method/revision/stamp/namespace/request/token、七段
   順序/stride/完整payload、原始block bytes、分区、parents、events、機構座標均驗證。
   physical十個f64有限，質量非負，diagonal inertia非負；不在Java重算物理或把face5
   誤叫混凝土壓碎。所有交付資料不可從可變陣列被竄改。
3. native候選只存在記憶體。伺服器request的planHash綁定完整before-world/owners、
   domain/namespace/revision及prepare options。重放由原日誌先裁決，不重新prepare，
   同key換來源/options拒絕。預覽不執行持久碎塊交易。
4. 在既有Preparer內準備原生候選並產生伺服器before/after participant images；
   必須一起保存原生完整receipt（含物理來源/父代）與after-world/metadata/revision。
   receipt可按既有1MiB Value切段，但仍遵守16MiB record、8192 resources、256 pieces
   與既有總容量，超限明示拒絕且不截斷。raw token只作歷史證據，重啟絕不用它提交。
   Forge host負責真chunk/registry參與者；尚無正式呼叫者時必明示未完成遊戲交付。
5. 原協調器持久COMMITTED成立後，publication adapter才finish native commit及
   發布遊戲副作用。native拒絕、失效或lost ACK不能撤銷已持久世界；保持關閉並沿
   recover從當前持久世界重建native session。PREPARED按原規則回滾before-images。
   native candidate在拒絕/中止時discard；未知提交結果不偽稱已回滾。候選、host與
   coordinator均序列使用，不能將原生commit放入可回滾participant write內。
6. 真DLL/SO/JNA驗L形、混合梁殼、支承移除/初始自由群及機構前綴，完整來源對引擎
   oracle；失敗/重放/不同domain/namespace/source、81/144/96/80等wire邊界需具名測試。
   真FileTransactionJournal與兩次以上獨立JVM重啟，驗PREPARED/每次write/flush/
   durable decision/native finish/publication等中斷；沒有重複移除材料或舊世界覆蓋。
   此process gate不授予Minecraft server或停電保證。
7. 每個新拒絕/順序宣稱配正常完成可編譯反例；首次FAIL與完整raw保留。計數首跑
   harvest後獨立追記；consumer既有core/Forge/packet/source/native測試依受影響範圍
   回歸。engine核心未改時不重跑其114/2690。真接觸/滾動/視覺及完整v2保持開放。

2026-09-11 consumer首跑追記（不移上述判準）：首版69項、1 FAIL，完整source
綁定不足；第二版加入GameWorldSnapshot三項後共72項。凍結這72項及28個真JVM
中斷點，每點兩次獨立恢復（56 JVM）：prepare/commit/abort四個日誌寫入階段、
三個participant的write/flush及rollback write/flush、native prepared、checkpoint、
native finished與publication。來源/原始log/首次FAIL保留。
三個正常完成故障臂各須完整跑72項且至少一项失敗：縮回stamp/namespace來源比對、
省略planHash檢查、在participant flush前提早native commit。相同classpath的control
必須72 PASS。這些進程案例仍是file host，沒有Forge world/碰撞或停電合格宣稱。

2026-09-11審查追加兩個明確拒絕判準（先於修補提交）：recover的snapshot domain
必須等於journal domain，否則不能發布ready baseline；同座標的artifact declaration
必須等於當前材料/斷面/角色對應的宣告方向，不能把舊產品graph當作當前owner。
新增兩項使consumer共74項；先在未修版本跑出完整74項反例，再驗修補。
