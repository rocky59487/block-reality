# CI測試登錄修正

ddab510的run34546299695先通過mod/Forge測試、舊construction process、contract/
host/corpus及packaging，最後quoted-count檢查失敗；原始failed log保留。
原因有兩個：文件仍寫573，且三個native TestFactory在列出個別case前便因未提供
原生庫而整組abort，使CI只登錄633而不是完整647。不是原生引擎計算失敗。

現在每個native case個別標SKIP，無庫時仍保留完整測試清單；不要求CI假裝有庫，
也沒有把SKIP改成PASS。文件總數647（core523、Forge124）由JUnit XML核對。
修補後Windows真DLL仍為core483 PASS/40 SKIP；無br.engine為444 PASS/79 SKIP，
兩次均登錄523且測試名稱集合完全相同。39個需真庫的case在真DLL那次全部執行；
引擎整合分類另含3個不依賴庫的邊界case，共42個。
新增74項的native流程、source與transaction assertions保持不變。

`python scripts/check_docs.py dist/br-sidecar.exe`已實際執行：engine330、
closed-form41、Java647、legacy-sidecar28，36個引用全符。
比較的完整JUnit XML、命令、source/diff與log在registration.zip，hash與計數在
REGISTRATION.json。主目錄raw.zip及RECORD.json仍是ddab510實作階段的原證據，未覆寫。
本修正只調整測試的登錄/略過時機與文件；沒有更改已驗證的production source、
原生庫或契約，不重跑未受影響的引擎114/2690或process crash矩陣。
