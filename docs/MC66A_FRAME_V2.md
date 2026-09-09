# MC66a frame_v2 配對工作

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
