# 學術 outreach:找教授給建議與合作

目標:讓一位真的懂結構力學或工程教育的人,用 30 分鐘告訴你(1)驗證方法有沒有洞、
(2)下一個最值得做的能力是什麼、(3)這東西在課堂上有沒有用。

## 寄信前的前置檢查(缺一封信就弱一分)

- [ ] repo 公開、README 首屏就能看懂在做什麼(已就緒)
- [ ] `evidence/VERIFICATION.md` 是最新一次建置產生的(已就緒,由 `scripts/package.sh` 自動更新)
- [ ] **60–90 秒的 demo 影片**(還沒有——這是最重要的缺件):
  懸臂 → 應力剖面 HUD → 加載到 D/C 變紅 → 細長柱挫屈警告 → 剪力牆。
  螢幕錄影即可,不用配音,字幕寫數字。上傳 YouTube(unlisted 也行)
- [ ] GitHub release 可直接下載試玩(v0.2a 發佈後就緒)

## 找誰(2026-08 查證;以系網頁現任教師為準)

**普渡結構組**(Lyles School of Civil and Construction Engineering,
[faculty 頁](https://engineering.purdue.edu/CCE/Academics/Groups/Structural/facstaff)):

| 人 | 為什麼是他們 |
|---|---|
| Amit H. Varma(Kettelhut Professor,Bowen Lab 主任) | 鋼結構與複合構造;你的「RC 複合斷面該怎麼做才誠實」正是他的領域 |
| Shirley Dyke(ME + CCE 合聘) | 即時混合模擬(real-time hybrid simulation)——「遊戲迴圈裡跑真求解器」與她的方法論同族 |
| Ayhan Irfanoglu(Professor, Associate Head) | 結構動力與震害;也管系務,適合問「這能不能進課堂」 |

**普渡工程教育**(School of Engineering Education,全美第一個工程教育系,
[faculty 頁](https://engineering.purdue.edu/ENE/People/Faculty)):
研究領域列表裡有「Conceptual change using simulations」與「Technology supported
learning environments」——逐個看 faculty 頁,挑一位發表過 simulation/game-based
learning 論文的助理或副教授(他們回信率通常比講座教授高)。

**不限普渡**:任何學校開「Structural Analysis I/II」且個人頁提到 educational
technology 的老師都是好對象。一次寄 3–5 封,分開寫,不群發。

## 信怎麼寫(規則)

- 主旨要具體到讓人想點開,不寫「Collaboration opportunity」
- 三段落內講完:你是誰、這是什麼(附一個會讓內行人挑眉的數字)、要他 30 分鐘做什麼
- **誠實邊界寫在信裡**(彈性、線性挫屈上界、無 RC 複合)——內行人第一眼就會戳這裡,
  你先說,信用就是你的
- 三個具體問題,不要開放式的「您覺得如何」
- 學生身分是資產不是弱點,寫明

## 範本 A:結構工程教授(英文)

> **Subject: A Minecraft mod that runs a real MITC4/6-DOF FE solver — would value 30 minutes of your skepticism**
>
> Dear Professor \<name\>,
>
> I am a student building **Block Reality**, a Minecraft mod in which placed blocks are
> extracted into 6-DOF Euler-Bernoulli members and MITC4 shell facets and solved by a
> finite element engine running out-of-process. Players see stress contours, per-member
> demand/capacity with the governing fibre, and a linear buckling factor — a slender
> column "looks safe" on stress alone and the eigenvalue says otherwise, which is
> exactly the lesson I want players to trip over.
>
> The part I hope earns your 30 minutes: the verification record. 282 acceptance
> checks run against closed forms and solver-independent invariants on every build —
> worst relative error 1.2e-14 over 31 non-zero references, clamped-plate support
> moments recovered by interior extrapolation (39.8% low raw → 14.3% at 8 elements),
> shear walls against beam theory to 1e-7 from h/w ≥ 5 (a few parts in 1e5 at
> h/w = 3), byte-identical solves across Windows and
> Linux. Honest boundaries, stated in the README: everything is linear elastic, plate
> D/C is a surface screen only, and there are no composite RC sections yet — the
> section tokens say "solid rectangle" because that is what is being solved.
>
> Repo and evidence: https://github.com/rocky59487/block-reality
> (90-second demo: \<video link\>)
>
> Three questions, if you are willing:
> 1. Is there a hole in this verification approach — a class of error these gates
>    cannot see?
> 2. Which single capability would add the most engineering truth next: plastic
>    hinges, load combinations, or an honest RC composite section?
> 3. Would any of your students find this worth breaking?
>
> A 30-minute call or an email reply would both be enormously helpful.
>
> Thank you,
> \<name, school, year\>

## 範本 B:工程教育教授(英文)

> **Subject: Students meet buckling before the formula — a Minecraft mod with a real FE engine, seeking education-research eyes**
>
> Dear Professor \<name\>,
>
> I am a student building a Minecraft mod where the blocks are literal: place a beam,
> and a real finite element engine (6-DOF members, MITC4 shells, linear buckling)
> solves it and paints the stress field on the block faces. My design bet is that a
> player who *watches* a slender column report "stress fine, stability not" internalises
> P_cr ∝ 1/L² before ever seeing the formula.
>
> The engineering is verified (41 closed-form acceptance checks in a 282-check suite per build, record in
> the repo), but the *learning* claims are exactly that — claims. That is what I would
> value your judgement on:
> 1. What would a minimal, honest classroom pilot look like for a tool like this?
> 2. Which misconceptions in early statics/mechanics could this surface or *create*?
> 3. Is there existing literature on sandbox-game physics learning I should be
>    standing on rather than rediscovering?
>
> Repo: https://github.com/rocky59487/block-reality (90-second demo: \<video link\>)
>
> Thank you,
> \<name, school, year\>

## 追蹤

- 一週沒回,寄一次(僅一次)兩行的 follow-up,附上新進度一條
- 每封信寄出、回覆、結論記在下表

| 日期 | 對象 | 範本 | 回覆 | 結論 |
|---|---|---|---|---|
| | | | | |
