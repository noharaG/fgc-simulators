# FGCシミュレーター レベルマックス — セットアップ手順（人間向け）

2026 FIRST Global Challenge を VR（Quest 2 PCVR）で選手視点から練習・対戦するシミュレーター。
夜間に自走で骨格コードを生成済み。**この手順書どおりに進めれば起動できる**。
設計の正典は [PLAN.md](PLAN.md)、実装の経緯と未検証項目は [HANDOFF.md](HANDOFF.md) を参照。

---

## 0. 所要時間の目安
- Unity導入＋ログイン: 30〜60分（DLが大半）
- プロジェクトを開く＋パッケージ解決: 10〜20分
- コンパイルエラーの微修正: 15〜30分（§4の既知5項目）
- シーン生成＆Play確認: 5分

---

## 1. 人間にしかできない作業（GUI必須）
1. **Unity Hub を起動**し、**無料 Personal ライセンスでサインイン**（Unityアカウント作成→ライセンス取得）。
   - これだけはCLI不可。エディタ本体DLやモジュール追加はCLIでも可（[HANDOFF.md](HANDOFF.md) §5）。
2. **Unity エディタ 6.3 LTS をインストール**（モジュール: `Windows Build Support (IL2CPP)`）。
   - **推奨（GUI・changeset不要）**: Hub の「インストール」→ **最新の 6.3 LTS（6000.3 系）** を選択し、
     追加モジュールで `Windows Build Support (IL2CPP)` にチェック。1クリックで完了。
   - **CLI（自動化したい場合）**: PowerShell の停止解析トークンで `--` を渡す。本プロジェクト基準版は
     **6000.3.9f1 / changeset `7a9955a4f2fa`**（検証済）:
     ```powershell
     & "C:\Program Files\Unity Hub\Unity Hub.exe" --% -- --headless install --version 6000.3.9f1 --changeset 7a9955a4f2fa -m windows-il2cpp --childModules
     ```
     ※ Unity Hub は Electron のため CLI が標準出力に何も返さないことがある（無言で進行/完了する）。
       インストール状況は Hub GUI で確認するのが確実。新しい 6.3 パッチを使う場合の changeset は
       https://unity-changeset.natsuneko.com/ で取得。`ProjectVersion.txt` は実導入版で開けば自動更新。
3. **Meta Quest Link** は既にセットアップ済み（開発者モードON・Link疎通確認済み）。

---

## 2. プロジェクトを開く
1. Unity Hub →「プロジェクトを追加」→ このフォルダ
   `C:\Users\genri\Desktop\FGCシミュレーターレベルマックス` を選択。
2. **エディタ版が `ProjectSettings/ProjectVersion.txt` と違う**と警告が出る → 導入した実バージョンで開いてOK。
   開いたら `ProjectVersion.txt` は自動更新される（仮置き値だったので問題なし）。
3. 初回オープンで `Packages/manifest.json` の依存を解決（Input System / OpenXR / XR Management /
   XR Interaction Toolkit / ML-Agents / URP / Newtonsoft.Json）。**数分かかる**。
   - Input System 有効化の再起動ダイアログが出たら **Yes**（Enable）。
   - URP を使うため、プロジェクトに URP アセットが必要なら自動 or 後述の設定で作成。

---

## 3. シーンを生成して動かす
1. 上部メニュー **「FGC > Build Field Scene」** をクリック。
   → フィールド（床/ガードレール/SUPPRESSION/FIRE SHIELD/BRACE/EXTINGUISHER/テープ）、
     WildfireManager（500球）、XR Origin（選手視点リグ）、MatchDirector、SetupMenu が生成される。
2. **Play** を押す。画面に IMGUI の「FGC セットアップ」窓が出る。
   - モード（練習 / 3v3）、スタート同盟（赤=x7側 / 青=x0側）、操縦者身長、6スロット割当を設定。
   - **「試合開始」** で 500球が放出され、150秒タイマー開始。NPCはルールベースで動く。
3. **ゲームパッド**（REV PS4互換 REV-31-2983）: 左スティックY=前後 / 右スティックX=旋回 /
   LT=回収 / RT=シュート /△=登攀（暫定）。
4. **VR で見る**: Quest Link 接続後、OpenXR が有効なら HMD に選手視点で表示。
   - ※ 初回は **Project Settings > XR Plug-in Management > OpenXR** を有効化し、
     Meta Quest feature group にチェック（GUI作業。HANDOFFの未対応項目参照）。

---

## 4. 朝イチで直す既知の項目（自走生成のため未コンパイル）
コードはUnity 6 / ML-Agents の標準APIに沿って書いたが、未起動のため以下は要確認・微修正の可能性大：

1. ✅ **`Rigidbody.velocity` → `linearVelocity`**（Unity 6 改称）→ **適用済み**（自走で全7箇所修正済）。
   `angularVelocity` は改称なしのため変更不要。もし導入版がさらに別名なら Console の指示に従う。
2. **asmdef のアセンブリ参照名**が実パッケージと一致するか。
   ※ 自走で依存グラフ監査済（循環なし・参照充足）。残るは「Unity側パッケージ名が実物と一致するか」だけ。
   `Unity.XR.CoreUtils` / `Unity.InputSystem` / `Unity.ML-Agents` / `Newtonsoft.Json`。
   不一致なら各 asmdef の references をUnityのInspectorで選び直す（GUIが確実）。
3. **MLAgentController の BehaviorParameters**: ロボに付く `Behavior Parameters` で
   Vector Observation Space Size=**22**、Actions=**連続2 + 離散3（各2分岐）** をGUI設定。
   ※自走でコード設定済（ConfigureBrain）。GUIで触る場合は `MLAgentController.ObservationSize`(=22)に合わせる。
   Behavior名は `fgc_ppo.yaml` の `FgcRobot` と一致させる。
4. **XROrigin API**（`Camera` / `CameraFloorOffsetObject` / `CameraYOffset` /
   `RequestedTrackingOriginMode`）が XRI 3.x の実名と一致するか。違えば該当プロパティ名を合わせる。
5. **球の描画**: `Resources.GetBuiltinResource<Mesh>("Sphere.fbx")` がURPで取れない場合、
   プリミティブSphereのメッシュ参照に差し替え。マテリアルは URP/Lit + Enable Instancing。

> エラーは Console に出る。多くは「名前変更」「参照名」だけなので、表示された型/プロパティ名に
> 合わせれば通る。構造（レイヤ分離・差込口IF）は変えなくてよい。

---

## 5. ML 学習を回す（任意・あとで）
1. **Python 3.10 を用意**（ML-Agentsは3.13不可）。`winget install Python.Python.3.10` など。
2. PowerShell で `./ml/setup_env.ps1` を実行 → `.venv310` に torch(cu121)+mlagents を導入。
3. **メニュー「FGC > Build Training Scene」**で学習用シーンを生成（VR無し・全6機ML・自動開始・連続エピソード）。
4. `./ml/train.ps1` で学習開始 → Unityで上記シーンを Play すると環境が接続（Editor学習）。
   - 高速化は ビルド済み環境を `./ml/train.ps1 -EnvExe <path>` で指定（ヘッドレス並列・time-scale20）。
5. 学習済み `.onnx` を ロボの `Behavior Parameters > Model` に割り当てると推論で動く。
   - ※対戦/体験は「FGC > Build Field Scene」（VR・SetupMenuあり）を使う。学習用とは別シーン。

---

## 6. フィールド配置の照合（重要）
自走生成の座標は `field_obstacles.json`（STEP由来の2D実測）＋ルール高さで組んでいる。
**ユーザー提供のフィールド外観図と必ず照合**すること（[HANDOFF.md](HANDOFF.md) §7末尾）：
- 上辺中央に EXTINGUISHER＋赤青 SUPPRESSION、対角に BRACE 2本、手前隅に赤青 FIRE SHIELD、
  左右外側にゾーンテープと ALLIANCE STATION。
- ズレていれば `FieldBuilder.cs` の各 Build 関数か `field_obstacles.json` を調整。
- より高精度なメッシュが要るなら STEP の厳密抽出（[HANDOFF.md](HANDOFF.md) §7 案A: OpenCASCADE/cadquery）。

---

## 7. ディレクトリ早見表
```
Assets/FGC/Scripts/
  Core/    定数・スコア・運動学・状態（純C#。エンジン非依存寄り）
  Field/   field_obstacles.json モデル＋フィールド3D構築
  Ball/    WildfireManager（500球）
  Robot/   IRobotController・RobotActor・Human/NPC/（ML差込口はML/）
  Input/   GamepadInput（REV PS4互換）
  Match/   MatchConfig・SlotAssignment（モード/同盟/身長/割当）
  VR/      VrPlayerRig（身長）・AllianceStationPlacer（赤青配置）
  UI/      SetupMenu（IMGUI）
  ML/      MLAgentController（ML-Agents Agent＋IRobotController）
  App/     MatchDirector（全層を結線する統合オーケストレータ）
Assets/FGC/Editor/  FieldSceneBuilder（FGCメニューで1クリック生成）
ml/        fgc_ppo.yaml・setup_env.ps1・train.ps1（Python学習）
```
