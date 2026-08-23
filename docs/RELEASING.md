# 發版

一次發版產出一個檔案：`blockreality-<version>.zip`。裡面是 `dist/` 的完整內容，
使用者解開後執行 `install.bat` 或 `install.sh` 就完成安裝。

## 跨平台決定性：三步，因為沒有 wine 也要做得到

`evidence.py --windows <wine 包裝>` 是舊路。**在 Windows 上原生跑那顆 .exe 是更強的證據**,
而且不需要 wine。順序：

1. WSL：`scripts/package.sh <FrameCore 路徑>` —— 建出兩顆引擎、跑完 gate、組出 `dist/`。
   若 `evidence/replies-windows.jsonl` 對不上這次建的 .exe,determinism 會誠實地留白。
2. Windows：`python scripts\evidence.py dist\br-sidecar.exe --emit-replies evidence\replies-windows.jsonl`
3. WSL：`python3 scripts/evidence.py dist/br-sidecar --windows-binary dist/br-sidecar.exe --replies evidence/replies-windows.jsonl`

第 3 步只重生 `evidence/`,不動 `dist/`,所以 `SHA256SUMS.txt` 仍然有效。

reply 檔的第一行記著產生它那顆二進位的 sha256,對不上就**拒絕**——否則一個過期的
reply 檔會變成一組偽造的「8/8 相同」。

> 自 v0.3a 起 Windows 建置加了 `-Wl,--no-insert-timestamp`,**同樣的原始碼產生同樣的
> .exe**。所以只要 `sidecar/` 沒動,舊的 reply 檔仍然對得上,第 1 步就會自己把
> determinism 補完,第 2、3 步只有在引擎真的改了才需要跑。

## 重新打包（需要 FrameCore）

`br-sidecar` 靜態連結 FrameCore，而 FrameCore 是本倉庫不收錄的外部 source
dependency，因此完整的打包只能在有它的機器上進行：

```bash
scripts/package.sh /path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
```

這支腳本把所有產物組進 **staging 目錄**，整條 pipeline 全綠才會原子替換 `dist/`——
中途失敗不會留下半空的 dist/。依序：

1. 編譯 `br-sidecar`（host），並在打包前跑 `sidecar/verify.py`。gate 沒過就不會有產物
2. 若有 `x86_64-w64-mingw32-g++`，交叉編譯 `br-sidecar.exe`
3. 執行 `scripts/evidence.py` 更新 `evidence/VERIFICATION.md` 與 `evidence/verification.json`。
   這兩份**不進壓縮檔**——發行包裡放的是能玩所需的東西，證據留在倉庫裡。
   evidence 的 gate 要求引擎 provenance 可解析（commit SHA、clean tree）——查不到就整包紅
4. **跑完整 Java 測試**（對剛編出來的引擎跑跨語言 gate），再建置 mod jar。
   打包從不 `-x test`（#46）
5. 從 `scripts/` 複製安裝器，從 `scripts/dist-docs/` 複製兩份說明文件
6. 對 `dist/` 內每個檔案產生 `SHA256SUMS.txt`
7. 打包成根目錄的 zip（zip 是本機產物，不進版控）

Windows 版引擎需要 `apt-get install -y g++-mingw-w64-x86-64`；沒有就只出 Linux 版。

## 只改文件或安裝器

安裝器與說明文件不需要重新編譯任何東西。改完之後重新同步 `dist/`，重新產生雜湊與 zip：

```bash
cp scripts/install.sh scripts/install.bat dist/
cp scripts/dist-docs/START-HERE.txt scripts/dist-docs/讀我-中文.txt dist/
chmod +x dist/install.sh dist/br-sidecar
rm -f dist/SHA256SUMS.txt
(cd dist && sha256sum -- * > SHA256SUMS.txt)
v=$(basename dist/blockreality-*.jar .jar); v=${v#blockreality-}
rm -f "blockreality-${v}.zip"
(cd dist && zip -q -r "../blockreality-${v}.zip" . -x '*.zip')
```

（版本號從 jar 推導，不寫死——這段曾經寫死 `0.1a`，版本一換就把新內容打進舊檔名。）
這條捷徑不重編引擎，所以 evidence 記錄的 binary hash 仍與 `dist/br-sidecar` 一致，
release workflow 的一致性 gate 照樣會過。

## 發到 GitHub Releases

`.github/workflows/release.yml` 在推 tag 時觸發。它不編譯引擎——hosted runner 沒有
FrameCore，重現不了——只把已經在 `dist/` 裡的東西打包發布。發布前有兩道 gate：

1. `SHA256SUMS.txt` 逐檔驗過，內容與雜湊對不上就不發
2. **版本一致性**（#48）：tag、jar 檔名、jar 內 mods.toml 的 version、
   evidence 記錄的引擎 binary hash 四者必須互相吻合——舊 binary 改個 tag
   名重新發布這條路被封死

另外 `.github/workflows/ci.yml` 在每個 PR 跑完整 gate（verify.py 對入庫二進位、
mod 跨語言測試、forge 建置）。CI 綠是 merge 條件。

```bash
git tag v0.1a
git push origin v0.1a
```

也可以從 Actions 分頁手動觸發，並自行指定 tag 名稱。

## 版本號

版本號寫在 `forge/build.gradle` 的 `version`，並且要與 `mods.toml` 的 `version` 一致。
zip 檔名由 jar 檔名推得，改一處就會跟著改。

**README 的下載連結是釘在版本上的**（`releases/download/v<版本>/blockreality-<版本>.zip`），
不會自己跟著動。換版本時要一起改，中英兩段各一處。釘住是刻意的：`releases/latest/download/`
需要寫死附件檔名，那個檔名本身就含版本號，換版本一樣會斷，還斷得比較安靜。
