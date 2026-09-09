# MC66a 幾何勁度第一單元對位

2026-09-09。引擎正式版仍為 v1.3/1.3.0；本倉 0.4.0-dev。

tectonic2 的一致 Kg 元素核已本地驗證：沿桿分段線性軸力、兩平面 Timoshenko，
端釋放與 K 共用同一映射；點載重僅切積分段，不主宰 DOF 拓撲。
判準是本倉 MC66A_GEOMETRIC_CORE.md，與引擎對應檔逐位鏡像。
Windows/Linux/i9 的新142項、九變異與 DET3 全過；Linux ASan/UBSan 無診斷，
舊 Windows2690/雙側137及十變異回歸亦過。詳細 raw/來源/二進位 hash 在引擎
gate/evidence/MC66A_GEOMETRIC，初凍 6c184fb、名單釘定28e809b。

這批程式尚未進入發布的 native jar。全域 sparse eigen、每島狀態、BSI C10 與
MC66a/b 的完整功能仍未完成，不能宣告 GAME_SWAP 或改掉現行 Sidecar 的最後入口。
本次不動契約、capability、pin、jar 與 Java 力學邊界，也不關 N16/N18 對應 issue。

接續順序：

1. 引擎由固定 subdivision 建獨立 buckling model，重用已準備的 N 場及共同 coupling/scatter。
2. K 正定與機構、正 Ritz 選取、物理殘差、budget/失敗/無壓力狀態先在 typed lane 驗證。
3. 先凍世界/島 factor-state 聚合語意，再接 opt-in frame_v2/BSI C10；有契約變動再同步兩倉 pin。
4. 按既有 MC60d/MC66b 依賴補齊梁殼，換装前重打包新 native、真 JNA 與 N25 世界/HUD/FPS。

N16-d 的0.5%要求要用預設 subdiv=2驗；元素 n1 診斷 +0.752% 不冒充該門檻。
Greenhill 小矩陣 n1/n2/n4 為 +0.659%/+0.251%/+0.020%，只是新核的解析驗證。
舊 N16-c 的「保守」與 Rayleigh-Ritz 上界的適用差異留待完整挫屈出口以 dated 修訂，
此文件不變更原 GATES 的判線。原 v2/v3/v4、P-Delta、殼挫屈及效能紅帳保留。
