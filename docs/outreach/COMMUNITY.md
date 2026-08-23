# 社群發佈:CurseForge / Modrinth / Reddit

## 發佈前的素材清單(一次做齊,到處用)

- [ ] **Logo / icon**:400×400 PNG(CurseForge 要求方形)。應力等值圖配色的方塊即可
- [ ] **5+ 張截圖**:利用率鏡頭全景、斷面 HUD 特寫、挫屈警告、剪力牆、`/br members` 文字輸出
- [ ] **1–2 個 GIF**(<10 MB):放樑→加載→變紅;細長柱挫屈
- [ ] **60–90 秒影片**(同 OUTREACH.md 那支)
- [ ] GitHub release v0.2a 已發佈(zip 可下載)

## CurseForge 上架

- 專案類型:Mods → Minecraft;類別選 **Technology** + **Education**
- 遊戲版本 1.20.1,Mod loader 標 **Forge**;Environment: client + server
- 授權欄:Apache-2.0(倉庫連結)
- **重要——外部執行檔申報**:這個 mod 會啟動一個隨包附帶的原生執行檔
  (`br-sidecar`)。審核對「mod 下載/執行外部二進位」敏感。描述裡主動寫明:
  引擎是**開源、可自建、隨 zip 附 SHA-256**,mod 不下載任何東西,引擎缺席時
  mod 正常運作(分析停用)。第一次送審被打回就引用這段與 repo 的
  `docs/RELEASING.md`
- 上傳的是 **mod jar 單檔**——自 v0.3a 起引擎就在 jar 裡(D-027),玩家不需要再下載
  任何東西,install.bat/sh 變成選配。完整 zip 仍放 GitHub release 供查驗

> **逐欄位的上架文案與原生執行檔申報,見 [`LISTING.md`](LISTING.md)。** 本檔只留發佈
> 節奏與社群貼文;兩邊重複的那份以 LISTING.md 為準。

### 描述(英文,短版)

> **Real structural engineering inside Minecraft.** Blocks you place are extracted
> into 6-DOF beam members and MITC4 shell plates and solved by a real finite element
> engine running outside the game process. You get stress contours painted on the
> blocks, a demand/capacity ratio for every member and plate with the governing fibre
> named, and a linear buckling factor — so a slender column that "looks fine" on
> stress tells you it is about to fold anyway.
>
> Not a physics gimmick: 282 acceptance checks against textbook closed forms run on
> every build (worst relative error 1.2e-14), and the full verification record is
> public in the repository. Honest about its limits too — everything is linear
> elastic, and the section catalogue says "solid rectangle" because that is what is
> actually being solved.
>
> The analysis engine ships alongside the mod as an open-source, separately built
> binary (`br-sidecar`, SHA-256 in the archive). Without it the mod loads and plays
> normally with analysis disabled. Numbers cross via a zero-copy shared-memory
> transport; solves run off the tick thread (7 ms for a 200-member frame).

## Modrinth

- 同素材;類別 technology / education;License Apache-2.0
- Modrinth 對外部程序同樣要透明申報;文案同上
- 開 creator monetization(見 FUNDING.md)

## Reddit

規則共通:先讀各版 sidebar;不要同日多版連發(隔 1–2 天);貼文後**留在
留言區答問**——回覆品質決定 thread 的生死;附 imgur GIF 或影片直連。

### r/feedthebeast(mod 社群,最大受眾)

> **Title:** I put a real finite-element solver inside Minecraft — stress contours,
> demand/capacity, and buckling on the blocks you place [Forge 1.20.1]
>
> 內文骨架:GIF 開場 → 兩段講「是真 FEA 不是換皮」(引擎外掛程序、41 項閉合解
> gate、挫屈的教學點)→ 誠實限制(彈性、無 RC)→ 下載連結 + 「想聽你們想拿它
> 蓋什麼/壓垮什麼」。

### r/StructuralEngineering(內行人,口味不同)

> **Title:** Weekend cursed project: Minecraft blocks → 6-DOF members + MITC4
> shells → real solve, with the verification record to prove it
>
> 內文骨架:先報驗證數字(這裡的讀者只吃這個):閉合解 1.2e-14、clamped plate
> 支承彎矩 recovery 39.8%→14.3% @8 元素、剪力牆對梁理論 h/w≥5 到 1e-7
> (h/w=3 是 1e-5 級)、跨平台逐位元
> 決定性。再講一句「玩家在遇到公式之前先遇到 P_cr ∝ 1/L²」。**明說**彈性上限、
> 線性挫屈是上界。問題丟給版眾:「你會先加塑性鉸、載重組合,還是 RC 複合斷面?」
>
> (數字出處是 `evidence/VERIFICATION.md`;貼文前重新對一次,別背數字。)

### r/EngineeringStudents / r/civilengineering(次波)

角度:讀書工具。「statics 期中前拿它蓋一次懸臂,你就再也不會忘記哪一側受拉」。

### 中文社群

- 巴哈姆特 Minecraft 板 / Minecraft 中文論壇:用 README 中文區改寫,重點放
  「應力眼鏡」與挫屈演示
- 發佈時間配美東晚間(Reddit)與台灣晚間(中文)各自的高峰

## 發佈順序建議

1. GitHub release v0.2a(tag 推上去,CI 自動發)
2. CurseForge + Modrinth 送審(審核要排隊,先送)
3. 素材齊 → r/feedthebeast → 觀察兩天 → r/StructuralEngineering → 中文社群
4. 社群反應(截圖、引言)回頭充實教授信與資助申請
