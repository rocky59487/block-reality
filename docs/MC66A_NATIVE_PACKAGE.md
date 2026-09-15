# MC66a 雙平台原生候選包

2026-09-10，封裝實作前凍結。沿用 RELEASE_V1_3 / NATIVE_CONSUMER 的完整來源鏈、
六匯出、OS-only 依賴、授權、cache 與兩 JVM 競爭判準；原 v1.3 證據不改寫。
本單元以 frame_v2 #39 後的乾淨 Git 來源重建 Windows/Linux x86_64 自足庫。
consumer 整合 #106 c77f0a8、#105 6e72d99 與文件 #107，不另寫結果保存或遊戲輸入。
兩倉 contract 55 檔仍相同，pin 4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801。

## 身份與發行範圍

[硬] 來源以完整 40 字元 commit 與 SOURCE_MANIFEST 核對；兩平台必須同一來源。
wire buildSha 精確等於該 commit 的七字元，版本仍 1.3.0，capabilities 含 bsi.buckling.eigen。
同版本以 buildSha 與完整 binary hash 區分；不可覆寫正式 v1.3 tag、release、資產或舊包。
產物是本地可驗候選包，staging provenance 必須標示 locally qualified candidate。
既有 published 模式保留，明示預期 version/revision，不從待驗庫本身推得期望身份。
沒有新的 force push、遊戲版本發布、v2/v3/v4 完成或效能勝負宣稱。

## 封裝與來源

[硬] 使用原 release_source/build_release/package_release 與 stage_release_natives 管線，
逐檔驗 SOURCE_MANIFEST、依賴 receipt、SDK/契約、binary、ZIP、外部 SHA256SUMS、staged/jar。
release provenance 的驗收數量須讀實際 raw/三輪 report，不能寫死旧 310 / 8+2。
每輪 raw SHA/exit、固定 PASS/SKIP 集合、DET3、summary binary/contract 均核對後才封裝；
本次預期 boundary 334、corpus 9 PASS / 1 C13 custom SKIP，C10 必須執行。
宣稱拒絕的具名反例：ACCEPT_HASH、ACCEPT_EXIT、ACCEPT_COUNT、ACCEPT_DET、ACCEPT_CASES、
ACCEPT_IDENTITY；改 raw 時須重算其 hash，避免前層守門遮住所測條款。
既有十種來源鏈反例、九種 bundle 反例與六匯出/外部 runtime 反例維持。

## 真庫與真 jar

[硬] Windows/Linux 各自 artifact gate、334 邊界與 C10 在內語料 DET3。
同一個含兩平台原生庫、完整授權與來源的 jar，零 Sidecar executable。
兩箱從該 jar 載入真庫；既有 C5/C6/C8 的 24 frame 仍驗，新增 C10 eigen 的 f64/f32 frame。
封裝前後完整回應逐位、每臂三輪；两 JVM 同 cache 必須取得同樣完整回應。
身份錯 version/buildSha、缺 pin/非法 pin、權限拒絕、污染 cache 等原故障判準保留。
C10 數值正確性由獨立 Euler/Greenhill 語料 gate 與真 JNA 測試負責，不以自比代替。
Windows 完整 core/Forge 與 Linux 原生相關選集驗 #105/#106；新庫下 native-dependent
項目不得 SKIP。原 POSIX/平台特定 SKIP 逐項記錄，不能視為 PASS。
i9 複製同一 Windows binary，逐檔 SHA 驗傳輸後跑 artifact/原生語料，不在遠端重建。

## 證據與後續

[硬] 首敗與修正另記，原判準不覆寫；所有本轮來源、binary、raw/XML、產物 hash 留存，
編譯來源與最終 Git blob 比對，disk/index/HEAD 可審计；只提交來源、文件與文字證據。
原引擎 2690/MC66a 機械測試本單元不修改 core，不冒充重新執行；以已驗來源鏈接續。
GitHub CI 必須分辨實際執行與帳務 steps=0、token SKIP。
BUCK_MEMBERS 仍 active；混合世界局部 Critical HUD、GAME_RUNTIME、MC60d/殼屈曲與
原 41.7ms FAIL、Linux 7 FAIL、v2/v3/v4 欠項保留。
