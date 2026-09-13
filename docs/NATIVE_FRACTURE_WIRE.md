# 原生斷裂wire配對

2026-09-11，先凍判準，續接NATIVE_FRACTURE_TYPED同分支。

本次同步引擎fafb653a3706163b3f8e89866cc668db79cab608的完整contract/，
hash dae6bb356bac7dd5a70b790b4218a62b7f4cb9c80892bbf8179c51e094563465（56 covered）。
identified declare/edit及fracture prepare/finish沿同一WFA候選，資料包含完整來源格、
所有權、mass/COM/tensor、分區、parents與事件；capability及ABI3 slots同時滿足才呼叫。

驗收：契約每個檔案與引擎該commit相同，兩行pin在同一implementation commit更新；
本倉hash、schema11/Python5與全目錄對位通過。引擎附Windows/Linux/i9 71／八故障、
ASan/UBSan、真庫四transport與現有production BsiNative/BsiFrame雙平台JNA各39項。
證據來源為引擎gate/evidence/NATIVE_FRACTURE_WIRE，不冒稱已在遊戲交付。

這次只配對契約與來源pin，不換正式jar native bundle。下一步薄接
ConstructionLedger／AtomicConstructionCoordinator／FileTransactionJournal；
先持久化decision再發布副作用，native拒絕後從權威world重建，startup先recovery。
artifact ownership、kill/restart、真MC斷裂視覺與接觸滾動仍須驗收，不另寫Java物理。

2026-09-11後續：來源pin已推進至0fcb60a的支承移除修正；同分支已完成consumer
core的identified輸入、完整receipt解碼及既有持久日誌橋接，真DLL/SO各74項與每平台
28個中斷/56次独立恢復通過。具體來源、首敗及限制見
[NATIVE_FRACTURE_TRANSACTION](../evidence/NATIVE_FRACTURE_TRANSACTION/RESULTS.md)。
正式Forge Host、動態姿態、碎塊呈現與接觸滾動仍待；沒有替換正式原生bundle。
