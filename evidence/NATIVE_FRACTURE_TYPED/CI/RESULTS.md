# 未知ABI CI對位

2026-09-11，PR138首個job103065629514原始log及workflow保留。
舊CI把支援中的ABI3當未知，原腳本在Linux重現exit1；修正後兩倉原腳本皆exit0，
真ABI4 stub檔存在且host以明確ABI3拒絕訊息退出，另外真current ABI3 stub可載入exit0。
只改workflow版本常數來源，不改loader、contract或物理核心。
raw.json保留完整指令/stdout/stderr/exit，summary.json記錄host binary與workflow SHA。
host沿NATIVE_FRACTURE_TYPED/host-checks已建且契約相同的binary；重新驗證時可先
以CMake建contract/host，再依reproduce.py執行同樣三段腳本及current控制。
原始雲端host/corpus通過不等於原生力學通過；stub語料的SKIP仍保持SKIP。
