# MC66a 單一分量稀疏挫屈對位

2026-09-09。引擎正式版仍 v1.3 / 1.3.0；本倉 0.4.0-dev。
本輪引擎將固定細分、同次軸力場、釋放端私有轉角及共同耦合組裝接到 typed 稀疏求解器。
原線性解與機構門檻保留；獨立門架 oracle 為2376DOF，非小元素矩陣替代。

MC66A_SPARSE_LANE.md 與 MC66A_SPARSE_LANE_COUNTS.json 逐位鏡像引擎判準及名單。
正常579項、門架10項、13變異；實際平台與raw證據以引擎 gate/evidence/MC66A_LANE
為準。原反例與門架首輪3FAIL保存在 MC66A_LANE_PREFREEZE / MC66A_LANE_SHIFT。

釋放端不能先按彈性K消去後直接當完整buckling pencil；保留端轉角，λ=0再消去才
還原線性K。全1 seed 殘差0也可能錯模態，因此加最小正值驗證與確定性重啟。
近臨界驗證矩陣用原始Cholesky狀態；原結構求解仍用既有機構保護。

下一步先凍每島/世界狀態與 factor 聚合，沿共同 accounted solve 接 opt-in frame_v2、
BSI C10 與真JNA。不要在Java重建力學，也不把部分島失敗隱藏成全域成功。
目前native jar仍是先前版本，未含這批新核；contract、capability、pin與遊戲入口未變。
N16/N18、MC60d/MC66b與GAME_SWAP未結案；原判線、P-Delta、v2/v3/v4與效能紅帳均保留。
