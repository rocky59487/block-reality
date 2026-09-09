# UI_VERDICTS：警示、未評估與過期結果

2026-09-10，接 #108；模組限定，實作之前凍結。

玩家正拿著應力眼鏡修改 Minecraft 結構，需要先知道目前結果能否代表眼前的世界，
以及引擎是否指出危險。畫面延續既有角落儀表與截面圖，不改成遮住建築的控制台。

設計依據：領域為梁、柱、板、支承、屈曲、載重、revision；自然色彩來自鋼灰、
混凝土灰、木褐、警戒黃、危險紅、截面圖青色。保留本產品的「方塊上的应力圖＋
角落的有 revision 儀表」識別。避免一律綠色成功、只有紅色沒有文字、把所有結果
塞進單一安全比值。重點為有文字的屈曲警示；世界未評估保持獨立訊息。

沿用 Minecraft font/GuiGraphics 與現有雙語字串，保持既有 6px 邊距與 11–12px 行距。
使用既有紅 0xFF6B6B、琥珀 0xC8A24A、灰 0xAAAAAA，不加面板陰影或新裝飾。
警示不做動畫；放置/改動是頻繁操作，狀態立即反映。此次是訊息判定與小幅插入，
不將沒有 Minecraft 視窗的驗證宣稱為 N25 視覺/客戶端完成。

## 固定行為

| gate | 要求與固定 oracle |
|---|---|
| UV-1 | supplied critical=true 時必有獨立警示，無論世界 COMPUTED/拒絕/未評估；世界無因子不得列 0 或自行求 min；BucklingReadoutTest.refusedWorldKeepsTheSuppliedLocalCriticalWarning |
| UV-2 | factor=0.5 但 critical=false 不創造危險；factor=2 但 critical=true 保留警示；BucklingReadoutTest.neverDerivesCriticalFromTheDisplayedFactor |
| UV-3 | 非 COMPUTED 的各世界狀態均有文字；COMPUTED 的因子只做顯示格式；兩個實際呼叫端（HUD/命令）使用同一 readout |
| UV-4 | HUD stale 在 mechanism 的提前 return 之前；命令 status/members/section 都標結果 revision 與 stale/current；Mixed-world 4 total/1 singular 顯示 3 solved/1 unrestrained |
| UV-5 | 命令殼 D/C 的警示色用 supplied overloaded，不能另做 dc > 1；反向數值/旗標證據沿現有 N19/N20 |
| UV-6 | 英文/繁中完整且參數數量相同；Forge build/check，readout unit、具名變異與真 server status/members/section；client 視覺仍未驗就保留未驗 |

固定故障臂：把 critical 警示加上 hasFactor 條件，UV-1 必為唯一 assertion FAIL；
將 critical 改以 factor<=1 推導，UV-2 必為唯一 assertion FAIL。必須編譯成功。
不更改 BSI 契約、engine ref、力學、數值判定或 packet protocol。

## 獨立後續

空模型/停用通知、EngineStatusPacket 的 dimension/revision、長文字 HUD 佈局、
方向材質/滑鼠互動與 N25 真客戶端列後續；不混成這個警示單元已通過的能力。

## 執行紀錄與真伺服器 fixture

首次 core readout3/3；首次 Forge 的 LangKeysTest.noDeadKeys 抓到退場後仍在的
br.hud.buckling/br.hud.buckling_critical。保留 XML/log，刪除兩語言這兩個舊鍵，
新共用 readout 的鍵由既有 source 掃描與 placeholder gate 繼續檢查。

UV-6 真 server 在原 GAME_RUNTIME 混合場景旁增加 (35,200..249,0) 的50格
steel_beam_100x200[axis=y]，(35,199,0) 為 stone；只有原生自重與現有 eigen。
固定期望：3 members/12 facets/1 unrestrained，同時有 local-critical 警示及 world
incomplete 訊息，沒有世界因子。Java 不以公式判定此 fixture；若引擎未回 critical，
這項 FAIL 照登，不調整長度找過線值。另驗 CURRENT/revision 與 3 solved/1 unrestrained
的原混合場景；最後移除長柱使世界恢復。

最終結果：core360/Forge85=445登錄、417PASS、28SKIP；兩個具名變異各一個 assertion FAIL。
原 fixture 未改長度即回原生 critical + world not-eligible；真 server 十個 smoke 條件通過，
members/section CURRENT 也執行，並實際記到十個 STALE status。結果見
`../evidence/UI_VERDICTS/RESULTS.md`。沒有 Minecraft 視窗、沒有 N25 視覺完成宣稱。
客户端在 result packet 到达时格式化 readout，render 只讀緩存，clear 同時清掉；
最後再跑 Forge build/check 通過。這項是減少每幀重複格式化，沒有 FPS/零分配宣稱。
