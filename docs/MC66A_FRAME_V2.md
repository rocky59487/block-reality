# MC66a frame_v2 配對工作

本檔保留 #107 文件分支的引擎對位紀錄；下述「遊戲仍 Sidecar」是該分支當時的狀態。
已在 GAME_RUNTIME 整合 #105/#106 與本分支，當前模組已走 BSI/JNA，見末段。

2026-09-10，配對引擎 MC66A_FRAME_V2 判準先凍。
frame_v2 eigen 將沿已驗同次快照傳全部選項與完整島狀態；世界拒絕保留局部Critical。
原screen獨立保留且explain才標euler_screen；無請求舊bytes不變。
本倉BSI契約55檔/pin 4b11cc738790d32d576ac71ecd1cb57f04d3ed9186a9e43f89492ae13c258801
不因frame_v2變動重新pin。遊戲仍走原Sidecar；不能把frame_v2當已換裝。
先完成引擎Windows/Linux/i9、數值閉合解、原回歸與故障臂，再接雙平台包/HUD。
正式引擎1.3.0、模組0.4.0-dev；BUCK_MEMBERS/GAME_SWAP仍待驗收。

## 已完成的引擎對位（2026-09-10）

frame_v2已local_verified：504項/十故障臂三箱DET3；Windows/Linux/i9各60份舊回應逐位，
Linux ASan/UBSan無診斷。本轮原Windows2690、MC65A161+55、MC64 verdict147+82、
BSI833正常與334原生邊界/真C10回歸。原生開發庫重建，正式native/jar未更新。
本倉只有文件對位，沒有Java程式修改或重新執行Java的宣稱；55份contract及engine ref不動。
下一段沿既有NATIVE_CONSUMER更新同來源雙平台自足包，再處理混合世界局部Critical HUD。
來源/首敗/原始SHA以引擎gate/evidence/MC66A_FRAME/RESULTS.md為準。

同期[#105](https://github.com/rocky59487/block-reality/pull/105)與
[#106](https://github.com/rocky59487/block-reality/pull/106)已分別補共用結果的各島保存、
詞彙ID與遊戲輸入快照。本文件分支仍基於#103，只對位引擎；下一次整合先核對
上述分支及其docs/V1_MODULE_PROGRAM.md，不另建重疊實作。本輪沒有checkout或重验它們。

## GAME_RUNTIME 整合

2026-09-10：#107 `6a38cdf` 已合入 `codex/game-runtime`，保留其 ancestry。
只有文件衝突，CLAUDE 以較新的真 native 遊戲迴圈現況為準；沒有重做或覆寫 #105/#106。
本輪 Linux server 使用原 #37 開發庫 `95a03e82`，不是 #39 重建的新庫；上述 frame_v2
數值測試屬上游提供的紀錄，這次没有在模組工作中重跑或修改引擎。
下一段模組可獨立處理 HUD/命令 revision 與局部屈曲警示；新庫發布驗收仍待引擎交付。

2026-09-10 候選對位：#118完整模組鏈已以42e10f5双平台庫重新驗證與組包；精確身份與範圍見 [NATIVE_CANDIDATE](NATIVE_CANDIDATE.md)。舊server/client證據來源保持。
