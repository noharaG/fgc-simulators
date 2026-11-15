# FGC シミュレーター

FIRST Global Challenge（FGC）の操縦練習のために作ったシミュレーターを、ひとつのリポジトリにまとめたものです。
中身は 3 つあり、それぞれ独立して動きます。

| フォルダ | 種類 | 内容 |
|---|---|---|
| [java-2d/](java-2d/) | Java / Swing | 差動2輪ロボットの上から見た 2D 練習シミュレーター（通常版） |
| [java-2d-lite/](java-2d-lite/) | Java / Swing | 上の通常版を軽くしたもの（軽量版） |
| [unity-levelmax/](unity-levelmax/) | Unity 6 | FGC 2026 のフィールドを再現した 3D・VR シミュレーター（レベルマックス）。ML-Agents による強化学習つき |

## 2D の通常版と軽量版の違い

軽量版は、通常版の対戦モード（3 対 3・ボール 500 個）が重すぎたため、動きを変えずに処理だけを速くした版です。
1 tick あたりの処理時間が 261ms から 6.8ms になりました。
詳しくは [java-2d-lite/HANDOFF_軽量化作業.md](java-2d-lite/HANDOFF_軽量化作業.md) を見てください。

ただし、ロボットが止まって動けなくなったことを見つける処理（`idleStuckTimer`）は、通常版にしか入っていません。

## 動かしかた

- 2D の 2 つ: 各フォルダの `README.md` と `run.ps1` を見てください。動かすには Java 8 以上が要ります。ビルドに使う JDK 17 は、初回に Gradle が自動で用意します。
- レベルマックス: [unity-levelmax/README_SETUP.md](unity-levelmax/README_SETUP.md) の手順で Unity から開きます。

## クローンするときの注意

レベルマックスのフィールドのモデル（約 290MB）は Git LFS で置いています。

- Unity 版も使うとき: 先に [Git LFS](https://git-lfs.com/) を入れてから `git clone` してください。
- 2D 版だけ使うとき: LFS のファイルを取らずにクローンできます。

  ```sh
  GIT_LFS_SKIP_SMUDGE=1 git clone https://github.com/noharaG/fgc-simulators.git
  ```

## フィールドのモデルの出所

`unity-levelmax/Assets/Models/Field/FG26_F_v001.fbx` と、それを使うシーンは、
FIRST Global が配布している FGC 2026 のフィールド CAD（`FG26_F.step`）を変換して作りました。
フィールドの設計の権利は FIRST Global にあります。

## 履歴について

軽量版とレベルマックスは、もともと別々のリポジトリで管理していました。
`git subtree` で取り込んだので、それぞれのコミット履歴はこのリポジトリにそのまま残っています。
