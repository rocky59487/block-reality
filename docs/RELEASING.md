# 發版

一次發版產出一個檔案：`blockreality-<version>.zip`。裡面是 `dist/` 的完整內容，
使用者解開後執行 `install.bat` 或 `install.sh` 就完成安裝。

## 重新打包（需要 FrameCore）

`br-sidecar` 靜態連結 FrameCore，而 FrameCore 是本倉庫不收錄的外部 source
dependency，因此完整的打包只能在有它的機器上進行：

```bash
scripts/package.sh /path/to/architect_simulator/Plugins/FrameSolver/Source/FrameCore
```

這支腳本會依序：

1. 編譯 `br-sidecar`（host），並在打包前跑 `sidecar/verify.py`。gate 沒過就不會有產物
2. 若有 `x86_64-w64-mingw32-g++`，交叉編譯 `br-sidecar.exe`
3. 執行 `scripts/evidence.py` 更新 `evidence/VERIFICATION.md` 與 `evidence/verification.json`。
   這兩份**不進壓縮檔**——發行包裡放的是能玩所需的東西，證據留在倉庫裡
4. 建置 mod jar
5. 從 `scripts/` 複製安裝器，從 `scripts/dist-docs/` 複製兩份說明文件
6. 對 `dist/` 內每個檔案產生 `SHA256SUMS.txt`
7. 打包成根目錄的 zip

Windows 版引擎需要 `apt-get install -y g++-mingw-w64-x86-64`；沒有就只出 Linux 版。

## 只改文件或安裝器

安裝器與說明文件不需要重新編譯任何東西。改完之後重新同步 `dist/`，重新產生雜湊與 zip：

```bash
cp scripts/install.sh scripts/install.bat dist/
cp scripts/dist-docs/START-HERE.txt scripts/dist-docs/讀我-中文.txt dist/
chmod +x dist/install.sh dist/br-sidecar
rm -f dist/SHA256SUMS.txt
(cd dist && sha256sum -- * > SHA256SUMS.txt)
rm -f blockreality-0.1a.zip
(cd dist && zip -q -r ../blockreality-0.1a.zip . -x '*.zip')
```

## 發到 GitHub Releases

`.github/workflows/release.yml` 在推 tag 時觸發。它不編譯引擎——hosted runner 沒有
FrameCore，重現不了——只把已經在 `dist/` 裡的東西打包發布，並且在發布前先驗
`SHA256SUMS.txt`。內容與雜湊對不上就不會發。

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
