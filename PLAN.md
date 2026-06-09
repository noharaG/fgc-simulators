# FGCシミュレーター レベルマックス 実装計画書

2026 FIRST Global Challenge "Igniting Innovation" を **VR（Oculus Quest 2 / PCVR）** 上で
**選手視点**から練習・対戦できるシミュレーター。本書は設計の正典。

> 生成: 自走ビルド（夜間）。出力先は `C:\Users\genri\Desktop\FGCシミュレーターレベルマックス` のみ。
> 既存 `FGCシミュレータ`（2D Java版）は**参照専用**で一切変更しない。

---

## 1. 確定要件（ユーザー合意）

| 項目 | 内容 |
|---|---|
| 目的 | VRで試合（FGC2026）の練習・対戦 |
| 機材 | Oculus Quest 2（**PCVR**：RTX4070でフル処理、Link/Air Link表示） |
| 操作 | REV USB PS4 Compatible Gamepad（REV-31-2983、実機同等マッピング） |
| モード | ①1人練習 ②3v3対戦（基本：自陣=人間1+NPC2／敵陣=NPC3） |
| 拡張 | 6ロボスロットを **人間⇄NPC 動的差し替え可能** に |
| 視点 | **選手（ドライバー）視点固定**（非ロボ視点・非俯瞰）。**操縦者身長を後から選択可**（eye height可変） |
| 同盟 | **赤/青スタートを任意選択** |
| ロボ物理 | **フル3D剛体で作り直し**（既存Java版はスペック値の参照元） |
| ボール物理 | **WILDFIRE 500球をフル3D剛体**（押し出し・詰まり・こぼれ再現） |
| NPC | ルールベース＋**ML学習基盤も今回構築**（差し替え可能Controller） |
| 性能 | GPU活用前提（高負荷） |
| ルール | 共有された FGC2026 Game Manual に準拠 |

---

## 2. 技術スタック（決定）

- **エンジン**: Unity 6 LTS（6000.x LTS） / C#
- **VR**: OpenXR + Meta XR（Quest 2 PCVR、Link/Air Link）
- **物理**: Unity PhysX（剛体：6ロボ＋500球）。CPU=Ryzen9 7945HX(16C)で実時間、学習時はヘッドレス＋タイムスケール加速
- **入力**: Unity Input System（HID, PS4ゲームパッド）
- **ML**: Unity **ML-Agents**（Python/PyTorch、強化学習）。ヘッドレス並列Envで学習
- **データ**: 既存 `field_obstacles.json`（STEP由来のフィールド座標）を3D化の土台に流用

### 座標系（Unity）
- Unityは左手系・Yが上。**フィールド平面=XZ**、Y=高さ。
- 既存Java座標 (x_field, y_field) → Unity (x=x_field, z=y_field, y=height)。原点はフィールド左下隅、7m×7m。
- RISER天面=床（y=0）。観客から見た高さはルール値（SUPPRESSION 2.01m 等）。

---

## 3. アーキテクチャ

### 3.1 レイヤ分離（VRと学習でコア共有）
```
[Core] エンジン非依存に近いゲームロジック（スコア/ルール/状態機械/差動2輪運動学）
   ├─ [Sim] MonoBehaviour層：剛体ロボ・500球・フィールド・衝突
   ├─ [Control] IRobotController（Human / RuleBasedNPC / MLAgent）を6スロットに差込
   ├─ [VR] 選手視点リグ・身長・同盟配置・UI （描画あり）
   └─ [Headless/ML] 描画なし高速実行・ML-Agents Env （学習用）
```
- **同じ Sim + Core を VR とヘッドレス学習で共有**。違いは「描画の有無」「制御ソース」「タイムスケール」だけ。

### 3.2 ロボ制御の差し替え（最重要設計）
```csharp
interface IRobotController {
    DriveCommand GetCommand(RobotObservation obs, float dt); // 並進/旋回 + インテーク/シュート等
}
```
- `HumanController`（ゲームパッド）/ `RuleBasedNpcController`（既存Behaviors移植）/ `MLAgentController`（ML-Agents `Agent`）
- 6スロット（赤3・青3）それぞれに任意のControllerを実行時に割り当て。練習=スロット0が人間、他NPC。3v3=自陣1人間+5NPC。

### 3.3 ロボ物理（差動2輪、Java版から移植）
- 機体 0.5m角、質量 13.5kg、ホイール径 0.09m、無負荷6000rpm、ギア比 1/12・1/15・1/20。
- 最高速 = nWheel·π·D·0.8（nWheel=(6000/60)·gearRatio）。**1/15で≈1.51m/s**（×0.8込み・RobotConfig.java:142が正典）。最大加速 = 2·Tstall·(1/gr)·η / r / m（Tstall=0.105, η=0.85）。
- maxOmega=4 rad/s, maxAlpha=12 rad/s²。デッドゾーン0.05、expo・アクティブブレーキ・摩擦円スリップ。
- **3D化方針**: 上記は「車輪が路面に与える目標速度則」として残し、`Rigidbody` に対し
  目標 v,ω を満たすホイール接地力／速度制御で駆動。垂直方向・転倒・段差・BRACE登攀は剛体に委ねる。
- ボール/構造物/他ロボとの衝突は PhysX のコライダで処理（Java版の手計算押し戻しは置換）。

### 3.4 WILDFIRE（500球）
- 直径100mm（半径0.05m）、62g、反発0.2、摩擦0.4（カーペット想定、Java値踏襲）。
- 各球 `Rigidbody`+`SphereCollider`。描画は **GPU Instancing / `Graphics.DrawMeshInstanced`** で軽量化。
- 試合開始時、EXTINGUISHER下から500球を放出（Java: emit速度1〜3m/s, 放出半径0.25m）。
- 格納判定：SUPPRESSION UNIT / EXTINGUISHER の格納トリガに入った球をカウントしプールへ退避（物理から外す）。
- 性能対策：静止球はsleep、`solverIterations`調整、必要なら**領域外/堆積球の簡易化**は将来オプション。

### 3.5 フィールド再現
- `field_obstacles.json`（7m平面の実座標：SUPPRESSION UNIT, FIRE SHIELD L/R, BRACE×4, TAPE）を読み、
  ルール高さで押し出して3D化：
  - SUPPRESSION UNIT 高2.01m / canopy下端1.65m・40cm張り出し / 開口・内部傾斜・格納域
  - EXTINGUISHER 背板2.01m / 開口1.66m・ledge40cm / 下部開口28cm（球放出口）
  - FIRE SHIELD（PORT下部開口・CHUTE・LEVER/GATE）
  - BRACE 6.4m鋼管、低24cm〜高197cm、中央たわみ100mm、3ゾーン（テープ位置）
  - GUARDRAIL 20cm、PLAYING FIELD 7×7、REGIONAL ZONE 410×50cm、ALLIANCE STATION（床面150cm）
- **エディタ自動構築**: `FGC > Build Field Scene` メニューでシーンをプリミティブ生成（STEPメッシュ無しで動作）。
- STEP視覚メッシュは任意（Onshape→glTF/FBX書き出し→差し替え）。必須ではない。

### 3.6 スコアリング（ルール準拠・`ScoreCalculator`）
```
REGIONAL = ceil( SUPPRESSION点 × CLIMB倍率 ) + PARTNER_CLIMB + EXTINGUISHER点 + COOPERTITION
CLIMB倍率 = 1 + Σ(各ロボ増分)   増分: CONTACT0.05 / Z1 0.10 / Z2 0.20 / Z3 0.30
PARTNER_CLIMB = 25 × 支持された仲間ロボ数
COOPERTITION(Z3に4台=10 / 5台=25 / 6台=40)  ※GLOBAL（全6チーム）に加点
EXTINGUISHER点 = 1/球（GLOBAL）, SUPPRESSION点 = 1/球（該当REGIONAL）
```
- 試合 2:30（150s）。終了5秒の静定判定（G21）。端数切り上げ。
- 反則（M/G/R）は段階導入。まずスコア・タイマー・格納・登攀を正確に。

### 3.7 VR・選手視点
- `XR Origin`（OpenXR）。プレイヤーは ALLIANCE STATION（フィールド端、150cm範囲）に立つ。
- **身長プリセット/スライダ**で eye height を上書き（Quest実身長 → 任意値）。設定UIで実行中変更可。
- 赤=x=7側 / 青=x=0側（json viewpoints）。選択で立ち位置・対応構造物の色が左右反転。
- ロボはVR内で外から目視して操作（本番同等）。HUD：スコア・残時間・自ロボ強調。

### 3.8 ML-Agents 学習基盤
- `MLAgentController` は ML-Agents `Agent`：
  - 観測：自ロボ位置/向き/速度、近傍球、格納数、相手位置、時計、登攀状態 等（ベクトル観測＋任意でグリッド/レイキャスト）
  - 行動：連続[並進, 旋回] + 離散[インテーク, シュート, 登攀] 等
  - 報酬：格納+1相当、EXTINGUISHER寄与、登攀ボーナス、反則ペナルティ、時間整形
- `Academy`/環境：**描画なし＋複数Env並列＋タイムスケール**で高速学習。
- `ml/fgc_ppo.yaml`（PPO/自己対戦）, `ml/setup_env.ps1`（Python3.10 venv+mlagents+torch）, `ml/train.ps1`。
- 学習済み `.onnx` を `MLAgentController` に流し込みヒューリスティック→推論へ切替。

---

## 4. ディレクトリ構成（出力）
```
FGCシミュレーターレベルマックス/
  PLAN.md                       本書
  README_SETUP.md               人間向けセットアップ手順
  Assets/FGC/
    Scripts/Core/               FieldConstants, AllianceColor, ScoreCalculator, MatchManager,
                                RobotSpec, DifferentialDriveModel, DriveCommand, RobotObservation
    Scripts/Robot/              IRobotController, HumanController, RuleBasedNpcController,
                                MLAgentController, RobotActor
    Scripts/Ball/               WildfireManager
    Scripts/Field/              FieldData(json), FieldBuilder
    Scripts/Input/              GamepadInput
    Scripts/VR/                 VrPlayerRig, AllianceStationPlacer
    Scripts/UI/                 SetupMenu
    Scripts/Match/              MatchConfig, SlotAssignment
    Editor/                     FieldSceneBuilder（1クリック構築）
    Settings/field_obstacles.json   （既存からコピー＝参照値）
  Packages/manifest.json        Input System / XR / OpenXR / ML-Agents 依存
  ProjectSettings/ProjectVersion.txt
  ml/                           requirements / setup_env.ps1 / train.ps1 / fgc_ppo.yaml
```

---

## 5. マイルストーン（夜間自走の順序）
1. ✅ 計画書・移植元仕様抽出
2. プロジェクト雛形（manifest / ProjectVersion / asmdef）
3. Core：定数・スコア・運動学・状態（純C#でテスト可能）
4. Sim：RobotActor（剛体）・WildfireManager・FieldBuilder
5. Control：IRobotController + Human + RuleBasedNPC
6. VR：選手視点リグ・身長・同盟配置・SetupMenu
7. ML：MLAgentController + config + Python環境スクリプト
8. Editor：FieldSceneBuilder（1クリック）
9. README_SETUP（人間の残作業＝Unity起動とMeta Link接続のみ）

---

## 6. 検証状況（正直な但し書き）
- 本マシンに **Unity未導入のため、C#のコンパイル/実行検証はできていない**（夜間にバックグラウンドでUnity Hub導入を試行）。
- コードはUnity 6 / ML-Agents の標準APIに沿って記述。**朝、Unityで開いた時にパッケージ解決→微修正前提**。
- Python側（ml-agents）は Python 3.10 導入後に `setup_env.ps1` で構築可能（3.13は非対応のため別途3.10を導入）。
- フィールド座標は既存JSONの実測値を流用（STEP由来）。高さはルール値で付与。
