# FGC シミュレーター

FIRST Global Challenge（FGC）の操縦練習のために作ったシミュレーターを、ひとつのリポジトリにまとめたものです。
中身は 3 つあり、それぞれ単独で動きます。

| フォルダ | 種類 | ひとことで | 必要なもの |
| --- | --- | --- | --- |
| [java-2d/](java-2d/) | Java / Swing | 上から見た 2D の操縦練習（通常版） | Java だけ |
| [java-2d-lite/](java-2d-lite/) | Java / Swing | 上の通常版を速くしたもの（軽量版） | Java だけ |
| [unity-levelmax/](unity-levelmax/) | Unity 6 | FGC 2026 のフィールドを再現した 3D。選手の目線で操縦する。強化学習の AI つき | Unity、ゲームパッド |

## どれを使えばよいか

- **とりあえず操縦を練習したい** → `java-2d-lite/`（軽量版）。軽くて、キーボードでも動きます。
- **3D で、本番のフィールドの見え方に慣れたい** → `unity-levelmax/`。Unity の導入に 1 時間ほどかかります。
- **AI を学習させたい** → `unity-levelmax/` に加えて Python の環境を作ります（[5. 強化学習](#5-強化学習unity-版任意)）。

## 通常版と軽量版の違い

軽量版は、通常版の対戦モード（3 対 3・ボール 500 個）が重すぎたため、動きを変えずに処理だけを速くした版です。
1 tick（1/50 秒）の処理時間が 261ms から 6.8ms になりました。
詳しくは [java-2d-lite/HANDOFF_軽量化作業.md](java-2d-lite/HANDOFF_軽量化作業.md) にあります。

ただし、ロボットが詰まって動けなくなったことを見つける処理（`idleStuckTimer`）は、通常版にしか入っていません。

---

## 目次

1. [フォルダ構成](#1-フォルダ構成)
2. [クローンのしかた（共通）](#2-クローンのしかた共通)
3. [2D 版（Java）の環境構築と使いかた](#3-2d-版javaの環境構築と使いかた)
4. [3D 版（Unity）の環境構築と使いかた](#4-3d-版unityの環境構築と使いかた)
5. [強化学習（Unity 版、任意）](#5-強化学習unity-版任意)
6. [つまずいたとき](#6-つまずいたとき)
7. [各フォルダに残っている資料について](#7-各フォルダに残っている資料について)
8. [ライセンス](#8-ライセンス)
9. [履歴について](#9-履歴について)

---

## 1. フォルダ構成

### 全体

```text
fgc-simulators/
├─ README.md          ← このファイル
├─ java-2d/           ← 2D 通常版（Java）
├─ java-2d-lite/      ← 2D 軽量版（Java）
└─ unity-levelmax/    ← 3D 版（Unity）
```

3 つのフォルダはお互いを参照していません。使うフォルダだけを開けば動きます。

### java-2d/（通常版）と java-2d-lite/（軽量版）

2 つは同じ作りです。軽量版にだけあるものは「軽量版のみ」と書きました。

```text
java-2d-lite/
├─ run.ps1                  ← 起動スクリプト（Windows）。これを実行すれば始まる
├─ gradlew / gradlew.bat    ← ビルドの道具 Gradle を自動で用意して動かすスクリプト
├─ gradle/wrapper/          ← 上のスクリプトが使う設定と本体（Gradle 8.5）
├─ build.gradle             ← 依存ライブラリと起動クラスの設定
├─ settings.gradle          ← ビルド用の JDK 17 を自動で取ってくる設定
├─ field_obstacles.json     ← フィールドの障害物の形と位置。起動時に必ず読む
├─ config/
│  └─ player_config.json    ← 自分の機体の設定。画面で変えると保存される
├─ presets/
│  └─ robot_presets.json    ← 機体設定の保存枠（プリセット）
├─ src/main/java/
│  ├─ core/                 ← 物理と当たり判定。画面に依存しない純粋な Java
│  ├─ input/                ← ゲームパッドとキーボードの入力
│  ├─ io/                   ← 走行ログの書き出し・読み戻し、プリセット、障害物の読み込み
│  ├─ practice/             ← 練習モードの NPC と、3 対 3 の対戦の進行・AI
│  └─ ui/                   ← 画面（Swing）。起動クラスは ui.SimulatorApp
├─ src/test/java/practice/  ← 自動テスト（JUnit 5、29 件）
├─ .vscode/                 ← VS Code で開いたときの起動設定
├─ README.md                ← 物理モデル・座標系・パラメータの技術的な説明
├─ セットアップガイド.md      ← 軽量版のみ。何も入っていない PC からの手順
├─ HANDOFF_軽量化作業.md      ← 軽量版のみ。高速化で何をどう変えたかの記録
└─ backup_baseline/src/     ← 軽量版のみ。高速化する前のソースの控え（ビルドには使わない）
```

実行すると、次のものが作られます。どれも Git には入りません。

| 作られるもの | 中身 |
| --- | --- |
| `logs/` | 走行ごとの記録。`run_日時_timeseries.csv`（50Hz の時系列）と `summary.csv`（1 走 1 行のまとめ） |
| `build/`、`.gradle/` | ビルドの途中経過と結果 |

主なソースの役割は次のとおりです。

| パッケージ | 主なファイル | 役割 |
| --- | --- | --- |
| `core` | `RobotPhysics.java` | 差動 2 輪の運動とスリップの計算 |
| | `SimulationEngine.java` | 50Hz の更新ループ |
| | `BallSimulation.java` | ボール 500 個の物理（空間グリッドで高速化） |
| | `CollisionResolver.java`、`*Obstacle.java` | ロボット・障害物・壁の当たり判定 |
| | `RobotConfig.java` | 機体の数値（ギア比、トレッド幅、回収容量など） |
| `input` | `GamepadInputAdapter.java`、`KeyboardInputAdapter.java` | 入力を操縦の指令に変える |
| `io` | `LogWriter.java`、`LogReader.java` | 走行ログの CSV を書く・読む（リプレイに使う） |
| `practice` | `PracticeManager.java`、`Behaviors.java` | 練習モードの NPC（12 種類の動き） |
| | `CompetitionManager.java` | 3 対 3 の対戦の配置・進行・NPC の判断 |
| `ui` | `SimulatorApp.java` | 起動クラスと画面全体 |
| | `FieldPanel.java`、`ControlPanel.java` | フィールドの描画、右側の設定パネル |

### unity-levelmax/（3D 版）

```text
unity-levelmax/
├─ Assets/
│  ├─ setting.unity          ← 操縦用のシーン（「FGC > Build Field Scene」で作って保存したもの）
│  ├─ TrainingScene.unity    ← 学習用のシーン（6 台すべて AI。学習用ビルドはこれを使う）
│  ├─ Models/Field/
│  │  ├─ FG26_F_v001.fbx     ← フィールドの 3D モデル（約 290MB、Git LFS）
│  │  └─ README_FBX要件.md   ← モデルを差し替えるときの条件
│  ├─ FGC/
│  │  ├─ Editor/             ← Unity のメニュー「FGC > …」でシーンを組み立てる道具と、学習用ビルド
│  │  ├─ ML/Models/
│  │  │  └─ FgcRobot.onnx    ← 学習済みの AI。観戦シーンが読み込む（Git LFS）
│  │  ├─ Scripts/            ← ゲーム本体（下の表）
│  │  └─ Tests/              ← 自動テスト（Unity の EditMode テスト）
│  ├─ ML-Agents/Timers/      ← ML-Agents が自動で書く計測ファイル
│  └─ XRI/                   ← VR を使っていた頃の設定の残り（今は使っていない）
├─ Packages/
│  ├─ manifest.json          ← 使う Unity パッケージの一覧
│  └─ packages-lock.json     ← パッケージの版の固定
├─ ProjectSettings/          ← Unity のプロジェクト設定（エディタの版は ProjectVersion.txt）
├─ ml/                       ← 強化学習の Python 側
│  ├─ setup_env.ps1          ← Python の仮想環境（ml/.venv310）を作る
│  ├─ train.ps1              ← 学習を始める（普通のターミナル用）
│  ├─ train_detached.ps1     ← 学習を裏で始める（ウィンドウを閉じても止まらない）
│  ├─ fgc_ppo.yaml           ← 学習の設定（PPO・自己対戦）
│  └─ train_run5*.log        ← 過去の学習ログ（参考用）
├─ tools/                    ← フィールド CAD（STEP）から形を取り出す Python スクリプト
├─ docs/環境構築ガイド.md      ← 旧版の手順書（7 章を参照）
├─ PLAN.md                   ← 設計書（何をどう作るか）
├─ HANDOFF.md                ← 開発の詳しい経緯と、はまったことの記録
├─ ECS移行_引継ぎ_2026-06-09.md、MORNING_RUNBOOK.md、README_SETUP.md ← 開発中のメモ（7 章を参照）
└─ *.csproj、*.slnx           ← Unity が自動で作る、エディタ（VS Code など）用のファイル
```

開くと Unity が `Library/`、`Temp/`、`Logs/`、`UserSettings/` を作ります（数 GB になります）。これらは Git に入りません。
学習を回すと `results/`、`ml/.venv310/` もできます。これらも Git に入りません。

`Assets/FGC/Scripts/` は、役割ごとにアセンブリ（`.asmdef`）を分けています。

| フォルダ | 中身 |
| --- | --- |
| `Core/` | ルールから決まる寸法（`FieldConstants`）、フィールド上の配置（`FieldLayout`）、差動 2 輪の運動（`DifferentialDriveModel`）、得点の計算（`ScoreCalculator`）。Unity にほとんど依存しない部分 |
| `Robot/` | ロボット本体（`RobotActor`）と、操縦の差し替え口（`IRobotController`）。人間（`HumanController`）・ルールで動く NPC（`RuleBasedNpcController`）・AI のどれでも同じ口で動かす |
| `ML/` | ML-Agents の AI（`MLAgentController`）。観測 110 次元、行動は連続 2（前後・旋回）＋離散 3（回収・射出・登攀） |
| `Match/` | 試合の設定。モード（練習／3 対 3）と、6 台それぞれを誰が動かすか |
| `Input/` | ゲームパッドの入力 |
| `UI/` | 試合前のセットアップ画面（`SetupMenu`） |
| `Ball/` | ボール 500 個（WILDFIRE）の旧方式の管理。今は下の ECS 版が既定 |
| `Sim/Shared/`、`Sim/ECS/` | 物理を Unity の ECS（DOTS）で多コア化するための部品と、試合タイマー |
| `App/` | 全体をつなぐ部分。試合の進行役（`MatchDirector`）、ECS 版の物理（`EcsBallSim`・`EcsRobotSim`）と判定（`EcsMechanics`）、画面表示（`MatchHud`）、AI の報酬（`RewardShaper`）、PC 用カメラ（`DesktopCameraRig`） |
| `Field/` | 今は空（アセンブリ定義だけ） |

---

## 2. クローンのしかた（共通）

### 2-1. 入れておくもの

| ソフト | 入れかた（Windows） | 必要な場面 |
| --- | --- | --- |
| Git | `winget install Git.Git` または <https://git-scm.com/> | 全部 |
| Git LFS | Git for Windows には最初から入っています。確認は `git lfs version` | Unity 版を使うとき |

### 2-2. 置き場所

**英数字だけで、スペースもないパスに置いてください。** 例: `C:\dev\fgc-simulators`

Unity 版の学習で使う通信ライブラリ（gRPC）が、日本語を含むパスから読み込めないためです。
Windows のユーザー名が日本語だと `C:\Users\<名前>\Desktop` も当てはまるので、`C:\dev` のような場所が無難です。

### 2-3. クローン

Unity 版も使うとき（LFS のファイル約 290MB も取ってきます）:

```sh
git lfs install
git clone https://github.com/noharaG/fgc-simulators.git C:/dev/fgc-simulators
```

2D 版だけ使うとき（LFS のファイルを取らないので速い）:

```sh
GIT_LFS_SKIP_SMUDGE=1 git clone https://github.com/noharaG/fgc-simulators.git C:/dev/fgc-simulators
```

PowerShell では 1 行目の代わりに `$env:GIT_LFS_SKIP_SMUDGE=1` を先に実行してから `git clone` します。
あとから Unity 版も使いたくなったら、フォルダの中で `git lfs pull` を実行すれば取ってこられます。

---

## 3. 2D 版（Java）の環境構築と使いかた

通常版・軽量版とも手順は同じです。以下は軽量版で書きます。

### 3-1. 動作環境

- Windows 10 / 11 で確認しています。macOS・Linux でも `./gradlew run` で動くはずですが、確かめていません。
- メモリ 4GB 以上。初回だけネット接続が要ります（ライブラリと JDK を自動で取ってくるため）。

### 3-2. Java を入れる

1. <https://adoptium.net/temurin/releases/> から **Temurin 17（LTS）** の Windows x64 用 `.msi` を落として入れる。途中の選択肢はそのままでよく、「Set JAVA_HOME variable」を有効にしておくと確実です。
2. PowerShell を開き直して `java -version` を実行し、版が表示されれば完了です。

**Java 8 〜 21 ならどれでも動きます。22 以降は入れないでください。** ビルドの道具 Gradle 8.5 が、22 以降の Java の上では動かないためです。
ビルドに使う JDK 17 は、手元の Java が何であっても Gradle が初回に自動で取ってきます（`settings.gradle` の設定）。

### 3-3. 起動する

```powershell
cd C:\dev\fgc-simulators\java-2d-lite
.\run.ps1
```

- 初回はライブラリと JDK を取ってくるので数分かかります。2 回目からは数秒です。
- ウィンドウを閉じると「キーを押すと再起動」の待ち状態になります。終わるときは Ctrl+C です。
- `run.ps1` が実行ポリシーで止められたときは、代わりに `.\gradlew.bat run` で起動できます。
- `field_obstacles.json` を読むので、**必ずこのフォルダの中で**起動してください。

### 3-4. VS Code で開く場合

1. 拡張機能「Extension Pack for Java」を入れる。
2. `java-2d-lite` フォルダを開く（リポジトリ全体ではなく、このフォルダを開く）。
3. 「実行とデバッグ」から `FGC Simulator` を選んで実行する。

### 3-5. 操作

| 操作 | キーボード | ゲームパッド（Xbox 系。つなげば自動で切り替わる） |
| --- | --- | --- |
| 前進・後退 | W / S（↑ / ↓ でも可） | 左スティック上下 |
| 左旋回・右旋回 | A / D（← / → でも可） | 右スティック左右 |
| 回収（intake） | Shift | LT |
| 射出（shoot） | Enter | RT |
| 排出（eject） | Space | A |

- 最初にスティック（キー）を倒した瞬間にタイマーが動き出します。2 分 30 秒で機体が止まり、ログが保存されます。
- ゲームパッドは起動する前につないでおいてください。

### 3-6. 画面とモード

- **上のタブ**: 「シングル走行（練習）」と「3v3 対戦」を切り替えます。
- **真ん中**: 7m × 7m のフィールド。ロボット・ボール・走った跡が出ます。
- **右のパネル**: 機体の設定（ギア比 1/12・1/15・1/20、トレッド幅、旋回の中心、摩擦係数、初期位置）、タイマー、リプレイ。
- **リプレイ**: `logs/` の CSV を読み込むと、そのときの設定ごと再現します。一時停止・シーク・0.25〜4 倍速ができます。

### 3-7. テスト

```powershell
.\gradlew.bat test
```

結果は `build/reports/tests/test/index.html` に出ます。

---

## 4. 3D 版（Unity）の環境構築と使いかた

### 4-1. 用意するもの

| 区分 | 必要なもの | 補足 |
| --- | --- | --- |
| OS | Windows 10 / 11 | Windows 前提で作っています |
| PC | 開発機は Ryzen 9 7945HX、RTX 4070 Laptop、メモリ 32GB | ボール 500 個を物理で動かすので、ある程度の性能が要ります |
| 空き容量 | 15GB 以上 | Unity エディタ本体と、開いたときに作られる `Library/` の分 |
| Unity | Unity Hub と、Unity エディタ **6000.0.76f1**（Unity 6.0 LTS） | 4-2 で入れます |
| ゲームパッド | PS4 互換のゲームパッド（開発機は REV Robotics の REV-31-2983） | **Unity 版はキーボードでは操縦できません**。なくても AI どうしの観戦はできます |
| Python | 3.10 | 強化学習をするときだけ（5 章） |

VR（Meta Quest）向けに作り始めましたが、2026-06-11 に VR はやめて、PC の画面で選手の目線から操縦する形にしました。

### 4-2. Unity を入れる

1. Unity Hub を入れる: `winget install Unity.UnityHub`（または <https://unity.com/download>）。
2. Unity Hub を起動し、Unity アカウントを作って**無料の Personal ライセンス**でサインインする。
3. Unity エディタ **6000.0.76f1** を入れる。
   - いちばん簡単なのは、ブラウザのアドレス欄に `unityhub://6000.0.76f1/6f7f9e1c9e8a` を入れて開く方法です。Unity Hub がその版の導入画面を開きます。
   - 別の版で開くとパッケージの版がずれることがあります。確かめているのは 6000.0.76f1 だけなので、同じ版を入れてください。
   - 追加のモジュールは **「Windows Build Support (IL2CPP)」** にチェックを入れてください（学習用のビルドで使います）。

### 4-3. プロジェクトを開く

1. Unity Hub の「プロジェクト」→「追加」→「ディスクから追加」で、`unity-levelmax` フォルダを選ぶ（リポジトリ全体ではありません）。
2. 初回は `Packages/manifest.json` のパッケージを取ってきて、`Library/` を作るので、**10〜20 分ほど**かかります。
3. 「Input System を有効にするため再起動するか」と聞かれたら **Yes** を選ぶ。

主なパッケージは次のとおりです（`manifest.json` から自動で入ります）。

| パッケージ | 版 | 用途 |
| --- | --- | --- |
| Entities / Unity Physics | 1.3.14 | ボールとロボットの物理を多コアで回す（ECS） |
| ML-Agents | 4.0.3 | 強化学習。推論用の Inference Engine も一緒に入る |
| Universal Render Pipeline | 17.4.0 | 描画 |
| Input System | 1.19.0 | ゲームパッド |
| Test Framework | 1.6.0 | 自動テスト |

### 4-4. シーンを作って遊ぶ

Unity の上のメニューに「**FGC**」が増えています。シーンはここから作ります。

| メニュー | できるシーン |
| --- | --- |
| FGC > Build Field Scene | 自分で操縦する試合。フィールド・ボール 500 個・ロボット 6 台・選手目線のカメラ・得点表示が並ぶ |
| FGC > Build Spectate Scene (学習済みAI観戦) | 6 台すべてが学習済みの AI（`FgcRobot.onnx`）で戦うのを見る。Play するとすぐ始まり、終わると次の試合に進む |
| FGC > Build Training Scene | 学習用（5 章で使う） |

自分で操縦するときの流れ:

1. 「FGC > Build Field Scene」を押す（保存済みの `Assets/setting.unity` を開いてもかまいません）。
2. 上の ▶（Play）を押す。
3. 左上の「FGC セットアップ」で、次のものを選ぶ。
   - モード: 練習 / 3v3
   - スタート同盟: 赤（x=7 側）/ 青（x=0 側）。カメラはその同盟の操縦席からの目線になります
   - 操縦者の身長（目線の高さ）
   - スロット割り当て: 6 台（赤 0〜2・青 3〜5）それぞれを「人間」「NPC」「ML（AI）」から選ぶ
4. 「試合開始」を押す。

### 4-5. 操作（ゲームパッド）

| 操作 | ボタン |
| --- | --- |
| 前進・後退 | 左スティック上下 |
| 旋回 | 右スティック左右 |
| 回収 | L2（LT） |
| 射出 | R2（RT） |
| 登攀 | △（Y）。仮の割り当て |

### 4-6. テスト

「Window > General > Test Runner」→「EditMode」→「Run All」。
得点計算、フィールドの配置、NPC の操縦、運動の計算などのテストが走ります。

---

## 5. 強化学習（Unity 版、任意）

ML-Agents の PPO で、3 対 3 の自己対戦をさせて学習します。**遊ぶだけなら不要です。**
学習済みの AI はすでに `Assets/FGC/ML/Models/FgcRobot.onnx` に入っています。

### 5-1. Python の環境を作る

1. Python 3.10 を入れる: `winget install -e --id Python.Python.3.10`
   （ML-Agents の都合で 3.10 が必要です。3.11 以降では動きません）
2. `unity-levelmax` フォルダで次を実行し、`ml/.venv310` に仮想環境を作る。

   ```powershell
   .\ml\setup_env.ps1
   ```

   実行ポリシーで止められたときは `powershell -ExecutionPolicy Bypass -File .\ml\setup_env.ps1` で実行します（`ml` の他のスクリプトも同じです）。

3. **PyTorch を CPU 版に入れ替える。** `setup_env.ps1` は CUDA（GPU）版の PyTorch を入れますが、CUDA 版は NVIDIA のドライバの中で落ちることがあったため、学習は CPU 版で回しています。

   ```powershell
   .\ml\.venv310\Scripts\python.exe -m pip install torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu --force-reinstall
   ```

   最終的に、Python 側は `mlagents 1.1.0`・`torch 2.5.1`（CPU 版）になります。Unity 側の ML-Agents 4.0.3 と組み合わせて動くことを確かめています。

### 5-2. 学習用の実行ファイルを作る

Unity エディタの中で学習させると、gRPC の都合でうまく接続できないことがありました。そのため、学習用シーンを実行ファイルにして、英数字だけのパス `C:\fgcbuild\fgc.exe` に書き出して使います。

Unity エディタを**閉じた状態で**、次を実行します（パスは自分の環境に合わせてください）。

```powershell
New-Item -ItemType Directory -Force C:\fgcbuild
& "C:\Program Files\Unity\Hub\Editor\6000.0.76f1\Editor\Unity.exe" -batchmode -quit `
  -projectPath C:\dev\fgc-simulators\unity-levelmax `
  -executeMethod FGC.Editor.BatchBuild.BuildTrainingPlayer `
  -logFile C:\fgcbuild\build.log
```

`Assets/TrainingScene.unity` がビルドされ、`C:\fgcbuild\fgc.exe` ができます。報酬や観測を変えたら、作り直してください。

### 5-3. 学習を始める

`unity-levelmax` フォルダで実行します。

```powershell
.\ml\train_detached.ps1 -RunId fgc_001
```

- 8 個の環境を並べ、20 倍速・画面なしで回します。変えるときは `-NumEnvs`、`-TimeScale` を付けます。
- 裏で動くので、ウィンドウを閉じても止まりません。様子はログで見ます: `Get-Content ml\train_fgc_001_*.log -Tail 20 -Wait`
- 止めるとき: `taskkill /F /IM mlagents-learn.exe` と `taskkill /F /IM fgc.exe`
- 続きから再開するとき: `-Resume` を付けて同じ `-RunId` で実行します。
- 普通のターミナルで前に出して回したいときは `.\ml\train.ps1 -RunId fgc_001 -EnvExe C:\fgcbuild\fgc.exe` でも動きます。

学習の設定は `ml/fgc_ppo.yaml` です。**このファイルには日本語を書かないでください。** 日本語の Windows では文字コードの違いで ML-Agents が読み込めなくなります（`ml/*.ps1` も同じ理由で英語だけにしてあります）。

### 5-4. 結果を見る・使う

- 学習の結果は `unity-levelmax/results/<RunId>/` に出ます。
- グラフで見る: `.\ml\.venv310\Scripts\tensorboard.exe --logdir results` を実行し、ブラウザで <http://localhost:6006> を開く。
- 学習した AI を観戦シーンで使う: `results/<RunId>/FgcRobot.onnx` を `Assets/FGC/ML/Models/FgcRobot.onnx` に上書きしてから、「FGC > Build Spectate Scene」を作り直します。

---

## 6. つまずいたとき

### 2D 版

| 症状 | 対処 |
| --- | --- |
| `java` が見つからない | 3-2 で Java を入れたあと、PowerShell を開き直す |
| 「Unsupported class file major version」などで Gradle が動かない | Java 22 以降が使われています。Java 17 か 21 を入れ、`JAVA_HOME` をそちらに向ける |
| `run.ps1` が実行できない | `.\gradlew.bat run` を使う |
| 初回のビルドが失敗する | ネットにつながっているか確認する。学校や会社のプロキシの中では、管理者に相談する |
| `field_obstacles.json` のエラーで起動しない | `run.ps1` があるフォルダの中から起動しているか確認する |
| ゲームパッドが反応しない | 起動する前につないでおく。つながっていなければキーボード操作になります |

### Unity 版

| 症状 | 対処 |
| --- | --- |
| FBX が数百 KB しかない、フィールドが出ない | Git LFS の中身を取ってきていません。`git lfs install` のあと `git lfs pull` を実行する |
| パッケージの読み込みが終わらない・エラーになる | Unity を閉じて `Packages/packages-lock.json` を消し、もう一度開く（作り直されます） |
| 画面がピンク一色になる | 描画の設定（URP）が外れています。「Project Settings > Graphics」と「Quality」に URP の設定が入っているか確認する |
| ゲームパッドで動かない | セットアップ画面で、そのスロットが「人間」になっているか確認する |
| 学習が「接続できない」で止まる | 実行ファイルを英数字だけのパスに書き出しているか確認する（5-2） |
| 学習が数分で黙って止まる | `train.ps1` を他の道具から起動すると、道具が子プロセスを片づけてしまうことがあります。`train_detached.ps1` を使う |

もっと細かいはまりどころは [unity-levelmax/HANDOFF.md](unity-levelmax/HANDOFF.md) の「ハマりどころ・教訓」にあります。

---

## 7. 各フォルダに残っている資料について

各フォルダには開発中に書いた資料がそのまま残っています。このファイルと食い違うところは、**このファイルが新しい**です。

| 資料 | 状態 |
| --- | --- |
| `java-2d*/README.md` | 物理モデル・座標系の説明として今も正確です |
| `java-2d-lite/セットアップガイド.md` | 今も使えます。ただし「zip を展開」とあるのは、配布用に書いたときの名残りです |
| `java-2d-lite/HANDOFF_軽量化作業.md` | 高速化の記録として正確です |
| `unity-levelmax/README_SETUP.md` | **古い部分があります。** 「Unity 6.3」「VR（Quest）で遊ぶ」と書いてありますが、今は Unity 6.0（6000.0.76f1）で、VR はやめています |
| `unity-levelmax/docs/環境構築ガイド.md` | **古い部分があります。** 「機械学習は無効化中」とありますが、今は有効です |
| `unity-levelmax/HANDOFF.md`、`PLAN.md` | 開発の経緯と設計の記録です。上ほど新しい記録です |
| `unity-levelmax/MORNING_RUNBOOK.md`、`ECS移行_引継ぎ_2026-06-09.md` | 開発中のその日の作業メモです |

資料の中には、開発した PC のパス（`C:\Users\...\Desktop\...`）がそのまま書かれているところがあります。自分の置き場所に読み替えてください。

---

## 8. ライセンス

このリポジトリは [MIT ライセンス](LICENSE) で公開しています。

- **自由に使ってかまいません。** 改変、再配布、自分の作品への組み込み、商用利用もできます。
- **条件はひとつです。** 使うときは、提供元の表示（`Copyright (c) 2026 noharaG`）と [LICENSE](LICENSE) の文を残してください。
  コードの一部を取り込む場合も、そのファイルか同梱の文書に提供元を書いてください。
- 無保証です。使ったことで起きた問題について、提供元は責任を負いません。

### ライセンスの対象外のもの

次のものは提供元に権利がないため、MIT ライセンスの対象外です。

| もの | 権利を持つところ |
| --- | --- |
| `unity-levelmax/Assets/Models/Field/FG26_F_v001.fbx`（フィールドの 3D モデル）と、それを埋め込んだシーン `Assets/setting.unity`・`Assets/TrainingScene.unity` のフィールドの形 | FIRST Global。FIRST Global が配布している FGC 2026 のフィールド CAD（`FG26_F.step`）を変換して作りました。扱いは FIRST Global の条件に従ってください |
| `java-2d*/gradle/wrapper/gradle-wrapper.jar` | Gradle（Apache License 2.0） |
| Unity・ML-Agents などのパッケージ（`Packages/manifest.json` に書いてあるもの。リポジトリには入っていません） | 各パッケージの提供元 |

---

## 9. 履歴について

軽量版と 3D 版は、もともと別々のリポジトリで管理していました。
`git subtree` で取り込んだので、それぞれのコミット履歴はこのリポジトリにそのまま残っています（`git log -- unity-levelmax` などで見られます）。
