# 引継ぎ資料 — FGCシミュレーター レベルマックス

最終更新: 2026-06-09 20:20（★ECS/DOTS移行セッション。段階①②③④検証済＋**段階⑤コンパイル0・Play例外なし**（HUDタイマー進行/終了の目視のみ残）。
チェックポイント要約は別紙 `ECS移行_引継ぎ_2026-06-09.md`。下の「新セッション再開ガイド」を最初に読む）
この資料だけ読めば作業を再開できるようにまとめてある。設計の正典は `PLAN.md`。
詳細な実装ログは §8以降（tick A〜R）。

---

## 🌙 夜間自走ログ（2026-06-09 夜〜・15分間隔ループ a5f32abb で進行中）
ユーザー就寝中の自走作業。承認不要(bypassPermissions)。本筋: ①Java版NPC挙動のUnity反映 → ②ML環境(ML-Agents)構築 → ③学習開始。
エージェントはUnity実行不可 → `Editor.log` を読んで検証。画面は1分でオフ(処理継続)。

> **🔴 起床後の最優先（重要）**: Editor.log は 6/9 23:39 で停止＝**夜間のコード変更はUnityで未コンパイル検証**。
> まず **Unityをフォーカス→再コンパイル→Editor.log でエラー0を確認**すること。各変更は通読で構文・参照を自己レビュー済(コンパイル前提OK)だが、Unity実機検証は未。
> 万一 error CS が出たら疑うべき夜間変更: `RuleBasedNpcController.cs`(PD操舵に全面書換) / `EcsRobotSim.cs`(速度供給追加) / `MatchDirector.cs`(BuildObservation/TagBraces) / `Assets/FGC/Tests/`(新規)。いずれも `git revert` で個別に戻せる（コミットは機能単位で分離済）。
> その後: Test Runner(EditMode)でNPCテスト緑 → Play で①NPC挙動目視 → ②Unity側ml-agents有効化(★手順) → ③学習。

- **[完了 00:55] ①競技NPC操舵則をJava `CompetitionManager` から忠実移植**: 従来Unity版は練習用 `Behaviors.java`(kP=6・Pのみ)を移植していたが、
  **競技NPCの正典は別物**と判明し置換。`RuleBasedNpcController` を競技則へ: heading=純目標70%＋移動方向(回避込)30%ブレンド(DIFF_HEAD_PURE_BLEND)、
  旋回=**PD制御 kP=3.5/kD=1.0**(errorDot≈-`obs.AngularVelocity`)・**5°デッドバンド**(首振り防止)・±4rad/s、前進=**45°ゲート内で正対度(1-|err|/45°)×距離比 min(1,dist*3/最高速1.51)**。
  射撃ゲート(射程2.0/正対15°)は正典維持。旧 steerTowards/kP=6 は撤去。**コンパイル前提OK(obs.AngularVelocity/FieldConstants使用)・要Play目視(挙動)**。
  ※Java競技NPCの更に高度な要素(チーム内の球回収割当・意思決定位相分散・シュートコミット状態機械・視線判定`checkShootTargetHit`)は未移植＝今後の候補。ただしNPC知能は最終的にMLで置換予定のため優先度は中。
- **[完了・前回] ①NPC壁回避＋他ロボ回避の移植**: Java `Behaviors.java` の `seekBallAvoidPlayer`(他ロボ回避)＋`isNearWall`(壁回避)を
  Unity `RuleBasedNpcController` に忠実移植（回避半径1.0m・重み×1.5・壁0.4m・操舵kP=6/±3rad/sは既存と同値）。
  `RobotObservation.NearestOpponent/NearestTeammate` を使用。これでNPCが球/ゴールへ向かいつつ近接ロボと壁を避けて回り込む。
  ※Java練習モードの12挙動(Still/Patrol/Circle/Wander/Bounce/Sweep/Spin/Drunk/Skittish 等)は競技NPCには不採用（練習用の動きパターンのため）。競技NPCの動き＝球回収→ゴール→シュート＋回避、が忠実な反映。
- **[進行中] ②ML環境構築**: 00:05 調査・着手。
  - **既存ML資産**: `ml/setup_env.ps1`(Python3.10 venv＋torch cu121＋`mlagents==1.1.0`)・`ml/train.ps1`・`ml/fgc_ppo.yaml`(PPO自己対戦・16次元観測・連続2+離散3行動)・`Assets/FGC/Scripts/ML/MLAgentController.cs`(#if FGC_MLAGENTS)。
  - **学習(Python側)はSentis不要**（Sentisはinference専用）。必要なのは Python3.10＋torch＋mlagents1.1.0 のみ。
  - **真の blocker = Unity側 `com.unity.ml-agents` が `Unity.Sentis` 依存**。Sentis→Inference Engine改称で旧ml-agents 3.0.0 はコンパイル不能。
  - **Web調査(2026-06)結果**: Unity 6対応の新しい com.unity.ml-agents が Inference Engine を使う形で存在する（Barracuda廃止→Sentis/Inference Engine 1.2系へ）。ただし Unity 6.3 で onnx インポータ競合の既知問題あり。
    → **方針**: manifestへ「Unity 6.0で解決可能な com.unity.ml-agents の版」を追加し、Inference Engine(com.unity.ai.inference)で解決させる。**ただし検証不能なまま本体manifestを壊すと全projectがコンパイル不能になるため慎重に**（変更後 Editor.log で版解決とコンパイルを確認→失敗なら即revert）。
  - **✅ Python学習環境 完成・GPU検証済(00:30)**: `ml/.venv310`（Python3.10.11）に **torch 2.5.1+cu121 / CUDA=True / RTX 4070 Laptop / mlagents 1.1.0(+mlagents-envs 1.1.0, onnx 1.15, protobuf 3.20.3, numpy 1.23.5)**。`mlagents-learn.exe --help` 動作OK。
    ※`ml/setup_env.ps1` は **日本語がWindows PowerShell5.1で文字化け→構文エラー**(UTF-8 BOM無し問題)で失敗するため、**.ps1を介さず同等コマンドを直接実行して構築**した。setup_env.ps1/train.ps1 は要BOM修正 or 直接実行。
  - **残blocker = Unity側 `com.unity.ml-agents`**: Python mlagents は **1.1.0**＝対応Unity版は **com.unity.ml-agents 3.0.0(Release 21)**。これが `Unity.Sentis` 依存で本エディタ(6000.0.76)ではコンパイル不能。
    新しいUnity版ml-agents(Inference Engine対応)は通信プロトコルがPython 1.1.0と不一致の恐れ＝版整合が要検証。
- **[次] Unity側ML再有効化（リスク管理しつつ）**: エージェントはUnity実行/ビルド/Play不可のため**完全自動で学習開始は不可**(Play必須)。方針:
  ①manifestへ ml-agents 追加は**検証不能なまま本体を壊さない**(壊すと全projectコンパイル不能)。Unityがアイドルだとmanifest変更を自動再解決しない可能性大→無人での検証ループが成立しにくい。
  ②そこで**ユーザー起床後に確実に流せる手順を用意**し、安全な範囲(Python側・設定リハーサル)を先行。Unity側のmanifest/asmdef/Define変更とBuild Training Scene→`ml/train.ps1`での学習開始は、Editor.logで版解決・コンパイルを確認しながら(失敗即revert)実施する。
  ③次イテレーション: setup_env.ps1/train.ps1 のBOM修正、`MLAgentController.cs` の観測/行動が yaml と一致するか確認、再有効化手順書を §「ML再有効化の手順」に統合。
- **✅ [完了 00:45] ML周辺の整備（安全・Unity非依存）**:
  - `MLAgentController.cs` 整合確認: **観測22次元(自12+最寄り球4+近傍ロボ6)・連続2＋離散[2,2,2]・Behavior名 "FgcRobot"**＝yamlと一致。観測次元はUnity側BehaviorParametersから供給されるためyamlに次元ハードコード無し＝機能不整合なし（yamlコメントを22次元へ修正）。
  - **`train.ps1` / `setup_env.ps1` をASCII化**（日本語コメントがPS5.1で文字化け→構文エラーだったのを英語化）。両者 PS5.1 で構文解析OKを確認＝**学習起動スクリプトが実行可能に**。`$args`(自動変数)も`$mlArgs`へ改名。
  - これで Python側は「torchGPU venv＋mlagents＋起動スクリプト」が完全に揃った。残るは Unity側 ml-agents パッケージのみ。
- **✅ [完了 01:05] ②Python学習パイプラインをE2E検証（Unity非依存）**: `mlagents-learn ml/fgc_ppo.yaml --run-id=... --force` を実行し、
  **`[INFO] Listening on port 5004. Start training by pressing the Play button in the Unity Editor.`** に到達＝torch/mlagents/yaml解析/trainer起動が全て正常。接続待ちまで確認後に停止。
  - **実バグ発見・修正**: `fgc_ppo.yaml` の**日本語コメントが mlagents(PyYAML) で cp932 デコードエラー**(`UnicodeDecodeError ... Make sure your file is saved using UTF-8`)＝学習を確実にブロックしていた。**yamlをASCII化**して解消（.ps1と同じ日本語エンコーディング問題）。
  - **結論: ②はPython側完全動作。あとは Unity で ml-agents を有効化し Play すれば学習が始まる**（Unity側手順は上記★参照。Unity実行/Playはエージェント不可＝起床後）。`results/` は .gitignore 追加。
- **✅ [完了 01:20] ③学習の報酬欠落バグを予防修正（#if FGC_MLAGENTS下・現ビルド非影響）**: `RewardShaper` は `StorageTrigger.OnDeposited` のみ購読していたが、
  **既定の useEcsMechanics ON では StorageTrigger は無効化**され、格納は `EcsMechanics.OnDeposited`(同シグネチャ・既に発火実装あり L170/196)で起きる。
  → このままML学習すると**主報酬(格納+1)が一切入らず学習が破綻**するところだった。`RewardShaper.BindEcs(EcsMechanics)` を追加し、`MatchDirector.CreateRewardShaper` で `_ecsMechanics` を購読するよう結線。これでECSモード学習でも格納報酬が確実に入る。
  ※時間ペナルティ/登攀ボーナスは RobotActor 経由でECSモードでも既に機能。報酬係数: 格納1.0/Extinguisher0.5/登攀0.3per段/時間-0.01per秒。
- **✅ [完了 01:35] ①NPC操舵の EditModeテスト追加（Play不要で挙動検証）**: `Assets/FGC/Tests/RuleBasedNpcControllerTests.cs` を新規作成。
  Unity Play不可の制約下でも操舵則を担保するため、決定論的に5ケース検証: ①正対→前進&旋回0 ②右90°→旋回&前進ゼロ(45°ゲート) ③5°内→デッドバンドで旋回0 ④0.35m内→Intake要求 ⑤壁0.4m内→前進停止。
  `FGC.Tests.asmdef` に `FGC.Robot` 参照を追加。Test Runner(EditMode)で緑になれば移植した競技PD操舵が正しい。**テストコード＝ランタイム非影響・安全**。
  追記(02:15): D項減衰テスト(既に旋回中はomegaが減衰)を追加＝計6ケース。`FieldLayout.NpcGoal` は純粋関数でEditMode例外なしを確認。
- **✅ [修正 02:45] ECSモードで観測の速度が0だった問題を解消**: `EcsRobotSim` が各ロボの実速度を供給するようにした。
  `Bot.LinVel`(=`PhysicsVelocity.Linear`・worldで確実)＋`Bot.YawRate`(=`eulerAngles.y`差分/dt＝heading と同一系で符号一致)を LateUpdate で算出し、
  `TryGetVelocity(RobotActor,out lin,out yaw)` で公開。`MatchDirector.BuildObservation` はECSロボ稼働時これを優先（非ECS時は従来のrb）。
  **効果**: ①NPCのPD **D項が正しく機能**（`-yawRate*Kd` でオーバーシュート減衰＝Java設計通り）／②**ML観測の自己速度が実値**に。
  前回慎重に避けた `PhysicsVelocity.Angular`(慣性空間)は使わず、heading差分でyawを出すため系が明確・符号も検証済（D項テストは観測直渡しのため不変）。#if非依存の実改善・コンパイル前提OK・要Play目視。
- **✅ [検証 02:15] ML配線の完全性を点検（修正不要＝良好）**: `MatchDirector` の ML経路を確認 — 観測注入 `ml.observationProvider=()=>BuildObservation(actor)`(L689)、
  エピソード終了 `OnFinish` で勝敗報酬(self_play用 +1/-1/0)＋`FinishEpisode()`(L145-159)、`BehaviorParameters/DecisionRequester` は `MLAgentController.Awake` で自動設定、
  報酬は前回修正で StorageTrigger＋EcsMechanics 両購読。**＝ML学習に必要な配線は全て揃っており、隠れたギャップは無い**（Unity側ml-agents有効化後そのまま学習可能）。
- **★Unity側ML再有効化の手順（起床後に実施推奨・要Editor.log検証/失敗即revert）**:
  - ★**[2026-06-10 Web調査で判明・重要]** Sentis blocker の正解は「**新しい `com.unity.ml-agents` 4.0.x 系を使う**」こと。
    **min対応Unityが 6000.0 に更新され、4.0.x は旧Sentisではなく Inference Engine(`com.unity.ai.inference`) ベース**＝当エディタ(6000.0.76)で解決可能。旧3.0.0(Unity.Sentis依存)に固執しない。
  1. **Window > Package Manager > 「ML Agents」を install**（Unity 6000.0 では 4.0.x 系に解決し、Inference Engine を自動で連れてくる）。manifest手書きより Package Manager が安全。
  2. Editor.log で **版解決＋コンパイル0** を確認。error CS が出たら manifest/packages-lock を即 revert（git）。
     ※`MLAgentController.cs` は Agent/BehaviorParameters/ActionSpec/VectorSensor を使用。4.0.x でAPI差があればここを微修正（基本は2.x〜4.xで安定）。
  3. **Python版を Unity版に合わせる（鉄則）**: venvは現在 `mlagents 1.1.0`。com.unity.ml-agents 4.0.x の対応Python版が異なる場合は
     `ml/.venv310/Scripts/python.exe -m pip install mlagents==<対応版>` で揃える（リリースノートで確認。通信APIはsemverチェック）。
  4. 通ったら `FGC.App.asmdef` に `FGC.ML` 参照を戻し、**Player Settings > Scripting Define Symbols に `FGC_MLAGENTS`** を追加（#if 部・MLAgentController・RewardShaper が復活。報酬のECS結線も有効化）。
  5. `FGC > Build Training Scene` → 別シェルで `./ml/train.ps1` → `Listening on port 5004` 表示後 Unity で **Play**（または `-EnvExe` でヘッドレス並列・time-scale=20）。

---

## 🟢 新セッション再開ガイド（最初にここだけ読めばOK）

### いま何をしているか
Unity 6.0 LTS(**6000.0.76f1**) のFGC2026 VRシミュレータ。**標準PhysX＋MonoBehaviour → Unity Physics(ECS/DOTS)＋Burst へ全面移行中**
（500球＋6ロボの物理をマルチコア化するのが目的）。**段階移行**で、各段階はビルドが通る単位＋ユーザーがUnityで都度検証。
- **全体計画書（承認済み・全8段階）**: `C:\Users\genri\.claude\plans\fancy-launching-umbrella.md`
- 移行前のゲーム本体は完成済み（試合全メカ＋スコア＋HUD＋VRリグ）。設計正典は `PLAN.md`。

### 移行の進捗
- **段階①「ECS土台」✅完了（検証済・テスト全緑）**: パッケージ導入＋運動学の純数学版`DriveKinematics`＋同値テスト。
- **段階②「球のECS化」✅完了（検証済）**: `EcsBallSim`がECS球500＋ECS床/壁を生成、Unity Physicsが物理(マルチコア)、描画は`Graphics.DrawMeshInstanced`。
- **段階③「ロボのECS化」✅完了（検証済）**: `EcsRobotSim`が6ロボをECS動的剛体化し球と同一物理ワールドへ。
  GameObjectロボはkinematic化して可視/機構/観測シェルとして残し、TransformをECSから同期。切替フラグ`useEcsRobots`(既定**ON**)。OFFで段階②へ即ロールバック。
  **Editor.log検証**: コンパイル0・Play稼働（`[FGC] ECSロボを生成: 6機`／Play中ランタイム例外なし）。
  停止時OnDestroyのEntityQuery.Dispose NRE（既存EcsBallSimにもあった）も `World.IsCreated` ガードで修正済。
- **段階④「判定機構のECS化」✅完了（検証済）**: Intake/Shoot/ShotTtl/Storage/Climb を **ECS球に対して**機能化。
  構造物コライダは可視FBXコライダから ECS静的ボックス334個を複製＝見た目と同位置で衝突（ユーザー確認済）。
  新規 `EcsMechanics` が唯一の判定経路（旧 RobotIntake/Shooter/Climb/StorageTrigger は無効化）。ゲーム状態は GameObject 側
  （RobotActor.HeldBalls/ClimbZone・wildfireの格納カウント）を更新＝ScoreBoard/HUDは不変のまま動く。切替`useEcsMechanics`(既定ON)。
  **検証手順**は下記「次の一手」。
- **段階⑤「試合/スコアのECS化」🟡実装完了＝Unity検証待ち**: タイマー/フェーズを真の `MatchSystem`(SystemBase)＋
  `MatchStateData`シングルトンへ完全移行（旧MatchManagerは `useEcsMatch=false` でフォールバック温存）。スコアは ScoreBoard が算出し
  `ScoreData`シングルトンへ公開（計算入力＝格納カウント/登攀がまだGameObject側のため計算は据置・結果のみECS化）。HUDはDirectorのアクセサ経由で
  シングルトンを読む。MatchDirectorは Begin/Reset で `EcsMatchBridge` 経由に開始/リセット指示、Update で Finished をポーリングして OnFinish。
- **段階⑥以降（未着手）**: ⑥VR/UI橋渡し(FgcEcsBootstrap)・Quest接続HMD確認 ⑦MLブリッジ ⑧旧PhysX撤去＋フィールドSubScene bake。
  ※フィールド**構造物コライダのECS化**（ロボ/球が構造物と衝突）は計画ではSubScene bake方式で別途（段階⑧寄り）。現状ロボはECS床/壁＋球とのみ衝突＝構造物すり抜けは想定内。

### ★必ず知っておくべき要点（ハマりどころ）
1. **パッケージ版**: このエディタでは **entities/physics は 1.3.14 が上限**（1.4.20は無い）。**entities.graphics は不採用**（版不整合＋VR×SinglePassリスク回避）。球描画は`DrawMeshInstanced`継続。3つ揃える鉄則は graphics を入れないことで回避。
2. **過渡的仕様（バグでない・ユーザー承認済）**: ECS球は GameObjectのロボ/フィールドと**別物理ワールド**なので、今は**球↔ロボ・球↔構造物が干渉しない**。段階③(ロボ)・④(フィールドコライダ)をECS化して同一ワールドに揃えば復活する。
3. **切替フラグ**: `MatchDirector.useEcsBalls`(既定ON)。OFFで旧GameObject球(WildfireManager)へ即ロールバック。
4. **検証のしかた**: 私(エージェント)はUnityをコンパイル/実行できない → **`C:\Users\genri\AppData\Local\Unity\Editor\Editor.log` を自分で読む**（ユーザーに貼付けを頼まない）。
5. **新規アセンブリ**: `FGC.Sim.Shared`(運動学Burst版) / `FGC.Sim.ECS`(コンポーネント・将来System)。新コードは `Assets/FGC/Scripts/Sim/` 配下。

### 次の一手（段階④の Unity 検証 ← 今ここ）
段階③は検証済（コンパイル0＋Play稼働）。**段階④（判定機構のECS化）のコードは実装完了**＝Unity でのコンパイル＆挙動確認が残るのみ。
1. **Unityをフォーカス**して再コンパイル（新規 `EcsMechanics.cs`＋`EcsBallSim`/`RobotActor`/`EcsRobotSim`/`MatchDirector` 変更を取込）。
   `Editor.log` でエラー0を確認（私が読む）。新APIは無し（`Physics.OverlapBoxNonAlloc`・`EntityManager.Exists`・既存プール操作のみ）＝低リスク。
2. `FGC > Build Field Scene`（既定で `Use Ecs Balls/Robots/Mechanics` 全ON）→ Play → SetupMenuで開始（or 学習シーンは autoBegin）。
   期待挙動（✅条件＝旧一致）:
   - **NPCが球を吸う**と `HeldBalls` が増える（満杯=10で停止）。
   - 満杯ロボが**自陣SUPPRESSION/EXTINGUISHERゾーンに入ると格納カウントが増え、HUDのスコアが上がる**。
   - **シュート**で撃った球がゴールゾーンに入ると得点化（撃った主の同盟一致をSUPPRESSIONは要求）。
   - 球↔ロボ干渉（段階③）も継続。Profilerで Unity Physics がマルチコア分散。
3. ロールバック: `Use Ecs Mechanics` OFF＝段階③（機構非機能）／`Use Ecs Robots` OFF＝段階②へ。各段独立に戻せる。

#### 実装メモ（段階③④で作ったECS層）
- **段階③ ロボ**: `EcsRobotSim`。ECS動的剛体 Box(0.5×0.486×0.5)・質量13.5・X/Z回転凍結(`InverseInertia.x/z=0`)・7mmクリアランス・
  `PhysicsVelocity`直書きで旧ApplyDrive同値。駆動=`DriveKinematics`、指令=人間Gamepad/他は既存`RuleBasedNpcController`再利用。
  GameObjectロボは `ExternallyDriven`＋`isKinematic` でシェル化しECS→Transform同期。各FixedUpdateで `SetLastCommand` も反映（機構用）。
- **段階④ 機構**: `EcsMechanics`（唯一の判定経路）。Intake=口前方のECS球を空間クエリで回収/`HeldBalls++`、Shoot=`EcsBallSim.LaunchBall`で
  ECS球射出＋飛来球追跡(ttl3s)、Storage=ロボ/飛来球のAABB判定→`wildfire.DepositToStorage`でカウント、Climb=`Physics.OverlapBox`でBRACE近接→`ClimbZone`。
  **ゲーム状態は全てGameObject側（RobotActor/wildfireカウント）を更新＝ScoreBoard/HUD/MLは不変**。旧GO機構は MatchDirector が無効化。
- `EcsBallSim` にプール操作API追加（`TryConsumeBallNear`/`LaunchBall`/`DespawnToPool`/`TryGetBallPosition`）＋放出をBallCountで打切り
  （intake回収球の再放出を防止）。OnDestroyのEntityQuery.Dispose NREは `World.IsCreated` ガードで修正済（EcsBallSim/EcsRobotSim両方）。
- **構造物コライダ（ユーザー報告「当たり判定ない」対応・2回目で確定）**: 当初FieldLayout推測座標の「シェル」を置いたが
  **可視フィールド(実CAD FBX)と位置が一致せず**ロボがゴールをすり抜けた（切り分け: 球は押し出す＝ロボcoll効く／外周壁で止まる＝静的coll効く
  →残る原因は位置ズレと断定）。**解決**: `MatchDirector.BuildEcsFieldColliders` が **可視フィールドのGameObjectコライダ(FBXのMeshCollider群)の
  ワールドAABBから ECS静的ボックスを複製**（`EcsBallSim.AddStaticBox`）。ECS=Unity同座標なので**見た目と必ず同位置**で衝突。
  除外: 巨大(maxDim>3m＝中央トラス全体/床/長い外周＝塞ぎ過ぎ) / 微小(<0.30m＝ボルト) / 極薄(y<0.05m＝床/テープ) / トリガ。
  個々のトラス部材は別々のコライダなら格子状に残る（球は隙間を通る＝solid block化しない）。フラグ `useEcsStructureColliders`(既定ON)。
  ※**[2026-06-09 修正] 斜め部材(ブレース)のすり抜けを解消**: 従来は `maxDim>3.0f` で除外していたため
  **対角6.4mのブレースがECS物理ワールドにコライダを持たず、ECSロボ/球がすり抜けていた**（ユーザー報告「ブレースの当たり判定が無い」）。
  対策＝巨大除外対象でも**細長部材(断面<0.5m＝パイプ/管/レール)は実メッシュ頂点からタイトなOBB(傾いた箱)を作って当たり判定を復活**
  （`MatchDirector.TryMakeThinOrientedBox`＋`EcsBallSim.AddStaticBoxOriented`）。斜めにbakeされた頂点でも長軸を2点法で求めるので正確、
  かつ細い箱なのでフィールドを塞がない。太い塊(ゴール背板/床/広い面=断面大)は従来通り除外して塞ぎ過ぎを防止。
  **✅検証済(Editor.log 27814/27969行)**: `ECS静的ボックスを 342 個生成（うち斜め部材OBB 8 個）`＝従来334個から斜め部材8個(ブレース等)が追加され当たり判定が復活。コンパイル0・Play例外なし。
  厳密形状は計画のSubScene bake方式（段階⑧寄り）で更に精緻化。
  ★**[2026-06-09 修正済] 登攀判定のBRACE名不一致を解消**: 従来 `RobotClimb`/`EcsMechanics` は `name.StartsWith("brace")` でBRACEを探していたが、
  実FBX部品名は「mesh#」「MAXTube…(REV-…)」で**"brace"を含まず登攀検出が機能していなかった**。対策＝新規 `BraceMarker` コンポーネントを
  `MatchDirector.TagBraces` が**形状判定**(細長い断面&lt;0.5m・長軸&gt;3m・登攀高さ帯を斜めに跨ぐ＝世界minY&lt;0.6かつmaxY&gt;1.2)で対角の鋼管2本に付与し、
  両登攀判定を `BraceMarker` 優先(旧 "brace" 名も後方互換で許容)に変更。これでロボがBRACE接触時に ClimbZone=Contact が入る（CLIMB倍率+0.05）。
  ※差動2輪は実登攀しないので Z1/Z2/Z3 へは原則進まない（既述「実質Contact/None」＝想定内）。
  **✅検証済(2026-06-09 Editor.log)**: `BRACE(登攀対象)を 8 個タグ付け`。診断ログで8個全てが**ブレース2基×各4管**(X≈4.88赤側/X≈2.12青側・Z≈3.7・長さ6.4m・|軸Y|≈0.26・Y帯0.25→2.01)と判明＝中央トラス等の誤検出は無し。コンパイル0。
- 切替フラグ（MatchDirector・全既定ON）: `useEcsBalls`(②) / `useEcsRobots`(③) / `useEcsMechanics`(④) / `useEcsStructureColliders`(④補完)。上位OFFで下位は自動無効。

### ユーザーの運用方針（重要・記憶にも保存済）
- **操作・選択・確認を極力求めない**。妥当なデフォルトで自走し、淡々と事実を報告。手順依頼は最小化。
- **HANDOFFは要所ごとに最新化し続ける**（この資料が常に現状＝再開の唯一の足場）。
- 承認なしで自走してよい（bypassPermissions運用）。

---

## ★★★最新セッション（2026-06-09 午後・最重要・ここが最新）

**到達点**: Unity 6.0 LTS（6000.0.76f1）で**コンパイルエラー0・Play稼働中**。
当初のプリミティブ自動生成から **実CADフィールドモデルの取込** へ移行し、ゲームパッド認識まで確認。

### ◆実CADフィールドの導入（プリミティブ→実寸モデル）
- `FG26_F.step`(44MB, AP242) → OBJ化（`FG26_F/FG26_F.obj` 748MB ＋ `.mtl`）→
  **`Assets/Models/Field/FG26_F_v001.fbx` として取込済み**。要件は `Assets/Models/Field/README_FBX要件.md`。
- **`Assets/FGC/Editor/FieldModelPostprocessor.cs`**: 取込時にメッシュを Read/Write 有効化
  （MeshCollider 生成に必須）等を自動設定する AssetPostprocessor。
- **`Assets/FGC/Scripts/App/FieldColliders.cs`**: フィールド当たり判定の共有ロジック。
  床=薄いBox、構造物=各メッシュに凹MeshCollider（最大辺 `colliderMinSize`=0.06m 未満の微小部品は除外）。

### ◆フィールド当たり判定の方針（★この回で「精密コライダ」へ戻した）
- `MatchDirector.ConfigureFieldColliders()` に「重い構造MeshCollider(約1612個)を実行時に無効化し
  軽量Box(床+外周壁)へ置換する自動最適化」を実装していたが、**ユーザー指示で既定を精密側へ差し戻し**。
- **`MatchDirector.useHeavyFieldColliders` の既定を `true`** に変更（= 精密MeshColliderをそのまま使う／形状忠実）。
  - `false` にすると従来の軽量Box最適化（500球でフレームが重い場合の保険）に切替わる。コードは温存。
  - 保存シーン `Assets/setting.unity` に本フラグはシリアライズされていない＝コード既定値が有効。
- **要・性能再確認**: 精密コライダ＋500球でフレームが保てるか（重ければ球数を絞るか最適化ONに戻す）。

### ◆現在の稼働状況（Editor.log 実測）
- コンパイルエラー無し。`FGC > Build Field Scene` → Play で MatchDirector がフィールド生成→稼働。
- ゲームパッド: USBデバイススキャン稼働中（REV PS4パッド入力系アクティブ）。
- **VR未接続**: `Main Camera does not use a Tracked Pose Driver` 警告。Quest Link接続＋OpenXR有効化でのHMD表示確認はまだ。
- マゼンタ問題は実モデル稼働により実質解消（色目視のみ残）。
- ML-Agents は引き続き `FGC_MLAGENTS` トグルOFF（Sentis廃止問題、コード温存）。

### ◆この回で触ったファイル（11:45以降）
RobotActor / FieldLayout / RuleBasedNpcController / AllianceStationPlacer / MatchDirector /
WildfireManager / SetupMenu / FieldSceneBuilder / FieldColliders / FieldModelPostprocessor / MaterialUtil。

### ◆赤青ゴールの左右とプレイヤー視点（この回で修正）
- **ゴールの左右が逆だった**のを修正。方針を「**赤=右(x大)/青=左(x小)＝各ロボ初期位置に近い側**」に統一
  （PLAN.md「赤=x7側」と一致）。`FieldLayout.SuppressionRed/BlueCenter` と `FireShieldRed/BlueCenter` の
  赤青を入替（NPCゴール・格納トリガもこの定数経由で自動追従）。関連コメントも全て赤=右へ修正。
- **プレイヤー視点を「人間操縦ロボの初期位置の真後ろ」へ**（従来は辺の中央 z=3.5 固定）。
  `AllianceStationPlacer` は `MatchConfig.HumanSlotIndex()` で操縦スロットを検出し、そのロボの z に合わせて
  外側に立ち、フィールド横断方向（自ロボ正面→敵陣）を向く。`Place(rig, config)` にAPI変更（呼出2箇所更新）。
- **操縦位置の左右変更も可**: 人間スロットを slot0/1/2(赤) or 3/4/5(青) に振り替えると、ロボ初期位置が
  DOWN/MID/UP（z=1.432/2.800/4.168）へ移り、**視点もその真後ろへ自動追従**する。
- **身長(eyeHeight)の起点を地上に修正**: 従来はリグ足元を y=0（RISER天面＝ロボ床）に置き身長を測っており、
  「人間が嵩上げフィールドに立つ」誤り状態だった（`GroundRaise=0.70` は定義のみで未使用）。
  `StationPosition` の足元 y を **−FieldConstants.GroundRaise(−0.70)＝観客床(地上)** に変更。
  以後 eyeHeight は地上起点＝目線world y は −0.70+eyeHeight（ロボ床から見ると約 eyeHeight−0.70 の高さ）。

### ◆ボール放出口を確定値で固定（この回）
- 放出口 BallEmitPoint を**ユーザー確定値 (3.5, 0.15, 0.45)** に固定。
  `FieldSceneBuilder.CreateWildfire()` と `WildfireManager.emitOrigin` 既定の両方に焼き込み済み
  （「Build Field Scene」再生成でも常にこの位置）。実行時にコードが位置を上書きすることは無い。
- 注意: `emitAnchor`(シーンのBallEmitPoint)があれば emitOrigin より優先。既存シーンに古い位置の
  BallEmitPoint が残っている場合は、**Play終了→FGC > Build Field Scene で作り直す**か、Edit中に手動で同値へ。

### ◆決定: 球の物理を DOTS/Unity Physics ハイブリッドへ（次の大改修・未着手）
- **背景**: 現状 500球は標準PhysX＝CPUシングル寄りでボトルネック。GPUは描画のみで遊んでいる。
  GPUパーティクル案は「個々の球をCPUで掴めない＝インテーク/格納/得点/堆積が崩壊」するため**不採用**
  （装飾エフェクト専用ならGPUパーティクルも可）。
- **採用方針（ユーザー合意 2026-06-09）**: **球だけ ECS + Unity Physics（Burst/Jobsでマルチコア=16C活用）** に載せる。
  ロボ/VR/UI/フィールドは現状のGameObjectのまま、球↔GameObject間を橋渡しする**ハイブリッド**。全ECS化はしない（高リスク回避）。
- **前提パッケージ（未導入）**: `com.unity.entities` `com.unity.physics` `com.unity.entities.graphics`
  `com.unity.burst` `com.unity.collections` を manifest に追加。
- **橋渡しが必要な既存接点**: WildfireManager(放出/プール/最寄り球) / RobotIntake(回収) / RobotShooter(射出) /
  StorageTrigger(格納カウント) / ScoreBoard(得点)。球の位置・個体をECS↔CPUでやり取りする層を設計する。
- **計画書**: `C:\Users\genri\.claude\plans\fancy-launching-umbrella.md`（全8段階・承認済み）。最終形=全ECS（VR/UI/エディタは橋渡し）。
  方針: 段階移行 / Unity Physics(DOTS)剛体 / MLは #if FGC_MLAGENTS 橋渡し設計(現状OFF)。

#### ECS移行 進捗
- **段階①「土台」= ✅検証済み完了（EditModeテスト全緑＝コンパイル0）**:
  - `Packages/manifest.json` に **entities 1.3.14 / physics 1.3.14** のみ追加（burst/collections/mathematicsは транзит解決）。
    ★**重要な版の事実**: このエディタ(6000.0.76f1)では **entities/physics の上限が 1.3.x**（1.4.20はレジストリに無い＝not found）。
    一方 entities.graphics は 1.3.14 が無く 1.4.20 しか出ない → **版が噛み合わない**。
  - ★**entities.graphics は採用しない**（決定 2026-06-09）: 版不整合＋計画リスク#2(VR×Entities.Graphics×SinglePass)回避のため。
    **球描画は既存 `Graphics.DrawMeshInstanced` を流用**し、ECS Entity の位置(LocalToWorld)から行列を作って描く折衷案。
    球の**物理だけ**を Unity Physics(ECS)化＝マルチコアの目的は達成。FGC.Sim.ECS asmdef の Unity.Entities.Graphics 参照は除去済み。
  - 新規 `Assets/FGC/Scripts/Sim/Shared/`（asmdef + `DriveKinematics.cs`）= 運動学の純数学・Burst安全版。
  - 新規 `Assets/FGC/Scripts/Sim/ECS/`（asmdef + `EcsBootstrapSmoke.cs`）= Entities解決確認用の**無動作**System(Enabled=false)。
  - `FGC.App.asmdef` に FGC.Sim.Shared/ECS 参照追加。`FGC.Tests.asmdef` に FGC.Sim.Shared 参照追加。
  - **設計上の判断**: 計画の「DifferentialDriveModelを委譲」は Core→Shared の循環依存になるため不採用。
    **Coreは不変**のまま `DriveKinematics` を独立実装し、`Tests/DriveKinematicsEquivalenceTests.cs` で**同値を担保**。
  - **ユーザー検証(段階①の成功条件)**: Unityでパッケージ解決→**コンパイルエラー0**、Test Runner(EditMode)で
    Core/Match/同値テストが**緑**、`FGC > Build Field Scene`→Play が**従来どおり**動く（ECSはまだ何もしない）。

- **段階②「球のECS化」= ✅完了**（コンパイル成功・ECS球500生成・Unity Physics稼働。ユーザー確認済み）。
  ※Editor.log上部の大量 entities.graphics エラー＋OverflowException は1.4.20を入れた一時状態の残骸（時系列で古い）。除去後にクリーン再コンパイル済み。
  - **既知の過渡的仕様（ユーザー承認済み）**: ECS球は GameObject のロボ/フィールドと**別物理ワールド**のため、現状
    **球↔ロボ・球↔構造物が干渉しない**（球はECS床/壁にのみ衝突）。これは段階③(ロボECS化)・④(フィールドコライダECS化)で
    同一ワールドに揃えば復活する。バグではない。

- **段階③「ロボのECS化」= ✅完了（検証済。Editor.logでコンパイル0＋Play稼働＋停止時NRE修正を確認）**:
  - **実装ファイル**: `Sim/ECS/BallComponents.cs` に `SimRobotTag` 追加 / **新規 `App/EcsRobotSim.cs`**（6ロボをECS動的剛体生成・
    FixedUpdateで指令算出→`DriveKinematics`→`PhysicsVelocity`直書き・LateUpdateでECS→GameObject Transform同期・最寄り球はECS球クエリ）/
    `Robot/RobotActor.cs` に `ExternallyDriven` ゲート追加 / `App/MatchDirector.cs` に `useEcsRobots`(既定OFF)＋`StartEcsRobots`
    （GameObjectロボを kinematic＋ExternallyDriven 化）＋Begin/Finish/ResetでのSetActive結線。asmdef変更不要（App は既に必要参照を保持）。
  - 目的: ロボを ECS Unity Physics 剛体にして球と同じワールドに入れ、**球↔ロボ干渉を復活**。
  - 設計: Robot Entity(Box0.5³・X/Z回転凍結=慣性テンソル0・PhysicsVelocity直書きで旧アーケード速度制御を同値再現)、
    DriveCommandData、DifferentialDriveSystem(=FGC.Sim.Shared.DriveKinematics をJob/Systemで適用)、
    NPC則をSystem化(RuleBasedNpcController移植: KP=6/MaxΩ=3/巡航0.7/IntakeRange0.35)、人間スロットはGamepad入力、
    観測は最寄り球をECS球クエリで算出。描画は可視cubeをECS→GameObject同期 or DrawMeshInstanced。
  - 注意: 機構(Intake/Shoot/Climb)は段階④まで非機能のまま（GameObject側に残置）。ロボ可視化と物理の二重管理に注意。
  - ★**要件(ユーザー指示・後で適用OK)**: 「ロボにとって床から7mm以下の干渉物は無視」。
    実装方針: ロボのコライダ底面に **7mmグランドクリアランス**（box底面を y=0.007 に＝床から7mm浮かせる）を与え、
    高さ≤7mmの微小干渉物（テープ/継ぎ目/微小段差/薄い破片等）はロボ下をすり抜ける。球(φ100)・本来の構造物は従来どおり当たる。
    段階③のロボEntity生成時、box高さを 0.5→0.493 にし中心を上げる等で実現。
  - 新規 `Assets/FGC/Scripts/Sim/ECS/BallComponents.cs`（`BallTag`/`SimStaticColliderTag`）。
  - 新規 `Assets/FGC/Scripts/App/EcsBallSim.cs`（MonoBehaviour）= ECS球500(Disabledプール)＋ECS静的床/外周壁を
    EntityManagerで生成、FixedUpdateで放出(pour)、LateUpdateで `Graphics.DrawMeshInstanced` 描画。
    物理は Unity Physics のSystem群(内部Burst)が担当＝マルチコア。値は FieldConstants/FieldLayout 流用。
  - `MatchDirector` に `useEcsBalls`(既定ON) を追加。ON時は WildfireManager の Prepare/EmitAll を呼ばず
    `EcsBallSim.Begin()` を起動（放出点/メッシュ/色は wildfire の設定 emitAnchor優先 を流用）。ResetMatchも分岐。
  - `FGC.App.asmdef` に Unity.Entities/Physics/Transforms/Mathematics/Collections 参照追加。
  - **重要な設計事実**: ECS物理ワールドは GameObject の MeshCollider と衝突しない → 球が落ちる床/壁をECSで別途生成。
    フィールド本体コライダのECS移行は段階④/⑧。この段階は「球が放出され押し出し/堆積/こぼれる」物理のみ検証。
  - **未検証リスク(Entities 1.3 API)**: PhysicsMass.CreateDynamic / PhysicsWorldIndex / SetEnabled / SphereCollider.Create 等の
    具体シグネチャはこちらでコンパイル検証不可。エラーが出たら文面で調整。
  - **ロールバック**: MatchDirector の `useEcsBalls` を OFF にすれば旧 WildfireManager 球へ即復帰。
  - **ユーザー検証(段階②成功条件)**: コンパイル0 → Play → 球がECSで放出され**球同士が押し合い・堆積・こぼれる**、
    Profiler(Window>Analysis>Profiler)でUnity Physics系がワーカースレッドに分散(マルチコア)。フレーム許容内。

### ◆次にやること
1. **VR/Quest接続**でHMD表示確認（Tracked Pose Driver / OpenXR有効化）。
2. **精密コライダ＋500球の性能確認**（重ければ `useHeavyFieldColliders=false` で軽量Boxに戻す）。
3. 挙動確認: 球放出・NPC移動・スコア加算・タイマー。
4. フィールド外観図と FieldLayout / FBX取込向き(`fieldEuler`/`fieldScale`)の照合。

---

## ★現在地サマリ（最優先で読む）

**到達点**: 計画(PLAN §5)のマイルストーン2〜9＋機構フェーズ①〜⑥が**全完了**。試合の全メカニクス
（インテーク/運搬/シュート/格納/登攀）＋スコア＋HUD＋ML報酬＋連続学習＋可視化まで実装済み。
**ただし Unity 未導入のため一度もコンパイル/実行していない**点に最大の留意（朝に疎通させる）。

**動かし方（Unity起動後）**:
- 対戦/体験 → メニュー **「FGC > Build Field Scene」** → Play（VR・SetupMenuで設定→試合開始）
- ML学習 → **「FGC > Build Training Scene」**（全ML/自動連続）→ `./ml/train.ps1` → Play

**朝の人間作業（これだけ）**:
1. Unity Hub で **6.3 LTS(6000.3.9f1)** 導入＋Personalログイン（GUI必須）。README §1。
2. プロジェクトを開く→パッケージ解決→**コンパイル**。エラーは README §4 の確認系（#2 パッケージ名/
   #3 BehaviorParameters/#4 XROrigin API/#5 球メッシュ）。velocity改称(#1)は自走で修正済。
3. フィールド外観図と座標照合（`Core/FieldLayout.cs` 1ファイルを直すだけで全反映）。
4. Test Runner(EditMode)で Core/Match の自動テストが緑か確認。

**コード地図**: Core(定数/スコア/運動学/状態/FieldLayout/MaterialUtil) → Field/Ball/Robot/Input/Match/
VR/UI/ML(各層) → App(MatchDirector が全結線) → Editor(FieldSceneBuilder で1クリック生成)。
**未検証の要注意点**: §8の各tick末尾「未検証/要確認」と §4(README)を参照。

---

## ★★Unity起動セッション結果（2026-06-09 続き・最重要・ここが最新）

**実際に使うエディタ = Unity 6.0 LTS（6000.0.76f1）**。当初想定の6.3/6.4ではない。理由は下記ML問題。
プロジェクトは**コンパイル成功・正常オープン済み**。上部に「FGC」メニューが出て **FGC > Build Field Scene** で
シーン生成→Play で MatchDirector が起動しフィールド/ロボ/球を実行時生成するところまで到達。

### ◆最重要: ML-Agents は現行Unityでは動かない（一時無効化した）
- **Unityが Sentis を「Inference Engine(com.unity.ai.inference)」へ改称・廃止**。これがレジストリ全体に及び、
  **6.0/6.3/6.4 のどれでも本物の `com.unity.sentis 2.1.0` は取得不可**（スタブ2.2.0しか来ない）。
  ML-Agents 3.0.0 は `Unity.Sentis` 名前空間を直接使うため**コンパイル不能**。Unity側エコシステムの問題で当方コード無関係。
- **対応**: ML を **スクリプト定義 `FGC_MLAGENTS` でトグル化して一時OFF**（コードは消さず温存）。
  - `Assets/FGC/Scripts/ML/FGC.ML.asmdef` に `defineConstraints:["FGC_MLAGENTS"]` を付与（未定義時コンパイル除外）。
  - `Assets/FGC/Scripts/App/FGC.App.asmdef` から `FGC.ML` 参照を削除。
  - `App/RewardShaper.cs` 全体を `#if FGC_MLAGENTS`〜`#endif` で囲み。
  - `App/MatchDirector.cs` の MLAgent生成ケース・OnFinishの報酬ループ・CreateRewardShaper(呼出＋本体) を
    `#if FGC_MLAGENTS` で囲み。**ML無効時はMLスロットも自動でNPC代替**。
  - `Packages/manifest.json` から `com.unity.ml-agents` と `com.unity.sentis` を削除。
- **ML再有効化の手順（将来）**: ①ML-AgentsがInference Engine対応版を出す or 本物Sentis入手 →
  ②manifestに ml-agents(+sentis)を戻す ③上記 asmdef 参照を戻す ④Player Settings の Scripting Define Symbols に
  **`FGC_MLAGENTS` を追加**。これで #if 部とFGC.MLが復活。学習用 ml/*.ps1・yaml はそのまま使える。
  - **学習成果の.onnxは標準形式**で移植可。ただし6.4等で推論実行するにはInference Engineベースの推論
    コントローラを別途書く必要（ML-Agents経由は不可）。当面はMLは保留でも本体は完全動作。

### ◆オープン時に直した不具合（すべて適用済み）
1. manifest の無効エントリ `com.unity.inputsystem.enableXRGrabInteractable`（実在しないパッケージ）を削除。
2. 無効モジュール `com.unity.modules.adaptiveperformance` / `com.unity.modules.vectorgraphics` を削除。
3. `PhysicMaterial`→`PhysicsMaterial`（Unity6改称）= UnityのAPI自動更新が WildfireManager.cs を更新。
4. `Assets/FGC/Editor/FGC.Editor.asmdef` に **`Unity.XR.CoreUtils` 参照を追加**（FieldSceneBuilderがXROrigin直接使用のため）。
5. **マゼンタ修正(対応中→適用済)**: `Core/MaterialUtil.cs` の FindShader を、`GraphicsSettings.currentRenderPipeline.
   defaultShader`（URPの正規Litシェーダ）を最優先で使うよう変更。`Shader.Find("URP/Lit")`が実行時nullで
   Standard(マゼンタ)に落ちていた問題を解消。**要・再Play検証**（直後にユーザーがPlayして色確認する段階）。
- ProjectVersion.txt は 6000.0.76f1 に。manifestの各パッケージ版は6.0が適正版へ自動解決済（packages-lockは一度削除して再生成）。

### ◆次にやること（このセッションの続き）
1. **マゼンタ解消の確認**: 再コンパイル→FGC>Build Field Scene→Play で、床/構造/ロボ/球が**色付き**になるか。
   まだマゼンタなら、Project Settings>Graphics と Quality にURPアセットが割当たっているか確認、または
   CreatePrimitive既定マテリアルを複製して色だけ変える方式へ切替（保険案）。
2. **挙動確認**: Play→SetupMenuで設定→試合開始 で、500球放出・NPCロボ移動・スコア加算・タイマーが動くか。
3. **VR**: OpenXR(Meta Quest)有効化(Project Settings>XR Plug-in Management)＋Quest Link接続でHMD表示確認。
4. **座標/見た目の照合**: フィールド外観図と FieldLayout(§W) を照合。選手立ち位置(z=3.5 vs FIRE_SHIELD奥)も確認。
- README §4 の朝チェック項目(#1 velocity済/#2 パッケージ名/#3 BehaviorParams=ML保留中/#4 XROrigin/#5 球メッシュ)も併せて。

---

## 0. これは何のプロジェクトか（30秒で把握）

2026 FIRST Global Challenge "Igniting Innovation" を **VR（Oculus Quest 2 / PCVR）** で
**選手視点**から練習・対戦するシミュレーター。**Unity 6 + C# + ML-Agents** で新規開発。

- 出力先（ここにだけ書く）: `C:\Users\genri\Desktop\FGCシミュレーターレベルマックス`
- **参照専用・絶対に変更しない**: `C:\Users\genri\Desktop\FGCシミュレータ`（既存2D Java版。ロボ数値の参照元）
- フィールドCAD（読取専用）: `C:\Users\genri\Downloads\FG26_F (1).step`（44MB, AP242）
- ルール: チャットで共有された FGC2026 Game Manual PDF（要点は §6）

### ユーザーの運用ルール（重要）
- **承認を求めない。** `bypassPermissions` で自走する（§4で設定済み）。確認で止まらない。
- 夜間ずっと走らせ続ける前提。環境構築も承認なしで進めてよい。

---

## 1. 確定要件（ユーザー合意済み）

| 項目 | 内容 |
|---|---|
| 目的 | VRで試合練習・対戦 |
| 機材 | Quest 2（**PCVR**：RTX4070でフル処理、Link/Air Link表示） |
| 操作 | REV USB PS4 Compatible Gamepad（REV-31-2983、実機同等） |
| モード | ①1人練習 ②3v3対戦（基本：自陣=人間1+NPC2／敵陣=NPC3） |
| 拡張 | 6ロボスロットを人間⇄NPC動的差し替え可能に |
| 視点 | **選手視点固定**（非ロボ視点・非俯瞰）。操縦者身長を後から選択可（eye height可変） |
| 同盟 | 赤/青スタート任意選択 |
| ロボ物理 | フル3D剛体で作り直し（既存Javaはスペック値の参照元） |
| ボール物理 | WILDFIRE 500球フル3D剛体 |
| NPC | ルールベース＋**ML学習基盤も今回構築**（差し替え可能Controller） |
| 性能 | GPU活用前提 |
| ルール | FGC2026 Game Manual 準拠 |

## 2. 技術スタック（決定）
- Unity 6 LTS / C# / OpenXR+Meta XR（Quest2 PCVR） / PhysX / **ML-Agents**（PPO・自己対戦）
- 座標: Unityは床面=XZ・Y上。既存Java座標 (x,y) → Unity (x, height, y)。フィールド7m×7m、原点=左下。

---

## 3. マシン環境の現状（実測）
- GPU: **NVIDIA RTX 4070 Laptop** ＋ CPU: **Ryzen 9 7945HX(16C)** ＋ RAM **32GB**。Legion機。VR十分。
- **Unity Hub**: インストール済み（winget `Unity.UnityHub` exit0）。`C:\Program Files\Unity Hub\Unity Hub.exe`
- **Unity エディタ本体**: ★まだ未インストール★（§5にCLI手順）
- **Python 3.13**: `C:\Users\genri\AppData\Local\Programs\Python\Python313\python.exe`（動作OK・STEP解析に使用）
- **Python 3.10**: winget で入れたが `py -3.10` で見つからない（所在不明）。ML-Agentsは3.10系必須なので要対処（§7）
- **Meta Quest Link**: ★ユーザー側セットアップ完了★（開発者モードON＋Link疎通確認済み）
- 電源: AC接続時スリープ無効・画面オフ3分（`powercfg`設定済み）。夜間稼働OK。

---

## 4. permission設定（変更済み・触らなくてよい）
`C:\Users\genri\Desktop\FGCシミュレータ\.claude\settings.local.json` を更新済み：
- `permissions.defaultMode = "bypassPermissions"`
- `allow` に `Bash(*) / PowerShell(*) / Write(*) / Edit(*) / WebFetch(*) / WebSearch`
- `additionalDirectories` と `sandbox.filesystem.allowWrite` に
  `FGCシミュレーターレベルマックス`・`Downloads`・`FGCシミュレータ` を追加
→ これでツール承認プロンプトは出ない想定。出るなら設定の再読込（セッション再起動）が必要かも。

---

## 5. Unity エディタのインストール（次にやる・自動化可能）
Unity Hub CLI は PowerShell だと `--` を食う。**cmd 経由が確実**：
```
cmd /c '"C:\Program Files\Unity Hub\Unity Hub.exe" -- --headless help'
cmd /c '"C:\Program Files\Unity Hub\Unity Hub.exe" -- --headless editors --releases'   # 入手可能版
```
- 最新 6000.x LTS のバージョン＋changeset を確認し：
```
cmd /c '"...\Unity Hub.exe" -- --headless install --version 6000.x.yf1 --changeset <hash> --module windows-il2cpp --childModules'
```
- **ライセンス（Personal）ログインだけはGUI必須＝人間作業**（Unity Hub起動して無料Personalでサインイン）。
  それ以外（エディタDL・モジュール追加）はCLIで自動化可。

---

## 6. ルール由来の確定寸法（フィールド構築の正解値）
点群抽出が不安定なので、**まずはこのルール値でUnityフィールドを組むのが確実**：
- フィールド: 7m×7m、RISER天面=床、地上70cm嵩上げ、GUARDRAIL高20cm
- WILDFIRE: 直径100mm(半径0.05m)・62g・500個・反発0.2・摩擦0.4（Java値）。試合2:30(150s)
- SUPPRESSION UNIT: 高2.01m / canopy下端1.65m・張出40cm / 開口・内部傾斜・正面格納域
- EXTINGUISHER: 背板2.01m / 開口1.66m・ledge40cm / 下部開口28cm（試合開始時に500球放出）
- FIRE SHIELD: PORT(下部開口)・CHUTE・LEVER/GATE。各コーナーのHUMAN PLAYER ZONE隣接
- BRACE: 鋼管6.4m、低端24cm〜高端197cm、中央たわみ100mm、3ゾーン（テープ位置）。φは半径2.4cm前後
- REGIONAL ZONE: 410cm×50cm（ガードレール沿い）。ALLIANCE STATION: 床にテープ、外側150cm
### スコア式（`ScoreCalculator`で実装）
```
REGIONAL = ceil(SUPPRESSION点 × CLIMB倍率) + PARTNER_CLIMB + EXTINGUISHER点 + COOPERTITION
CLIMB倍率 = 1 + Σ各ロボ増分（CONTACT0.05 / Z1 0.10 / Z2 0.20 / Z3 0.30）
PARTNER_CLIMB = 25 × 支持された仲間数
COOPERTITION（Z3に4台=10/5台=25/6台=40, GLOBAL加点）
SUPPRESSION点=1/球(該当REGIONAL), EXTINGUISHER点=1/球(GLOBAL6チーム)
```
端数切り上げ。試合終了5秒の静定判定(G21)。

### 既存Javaから移植するロボ数値（`RobotConfig.java`より）
- 機体0.5m角・質量13.5kg・ホイール径0.09m・無負荷6000rpm・ギア比 1/12・1/15・1/20
- 最高速 = (6000/60)·gearRatio·π·D·0.8（**1/15で≈1.51m/s**。RobotConfig.java:142が正典＝×0.8込み。
  旧記載「≈1.88」は×0.8前の誤記だったので修正済。RobotSpec.MaxSpeed実装・Coreテストもこの値で正しい）
- 最大加速 = 2·0.105·(1/gr)·0.85 / (D/2) / mass、maxOmega=4rad/s、maxAlpha=12rad/s²
- デッドゾーン0.05、expoカーブ、アクティブブレーキ、摩擦円スリップ（`RobotPhysics.java`に全式あり）
- ボール/インテーク/シュート/白ゴール各パラメータも `RobotConfig.java` に記載（参照のこと）

---

## 7. ★最大の未解決事項：STEPフィールド座標の高精度抽出★
ユーザー要望：「既存 field_obstacles.json は粒度が粗いのでSTEPから取り直したい」。

> **【2026-06-09 解決済み（tick32）】** cadquery(Python3.13)で387部品の精密AABB抽出に成功。
> 詳細・主要構造物の精密座標表・次の一手は §8末尾の「V.」を参照。以下は当時の調査メモ（歴史的記録）。

### STEP構造（解析で確定済み・再調査不要）
- entities 831,558。ルート `SHAPE_REPRESENTATION('FG26_F')` = #464695。
- 各部品: `SHAPE_REPRESENTATION('name',(#axisplacement),#ctx)`（配置のみ）
- 実ジオメトリ: `SHAPE_REPRESENTATION_RELATIONSHIP(#leafSR, #ADVANCED_BREP_SHAPE_REPRESENTATION)`
  で leafSR と同一座標系に連結（変換なし）。ABSR→MANIFOLD_SOLID_BREP→…→CARTESIAN_POINT
- アセンブリ配置: `CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(#rep_rel,#pds(NAUO))`、
  rep_rel = `(REPRESENTATION_RELATIONSHIP(child_SR,parent_SR) REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(#IDT))`、
  `IDT = ITEM_DEFINED_TRANSFORMATION(t1_in_child, t2_in_parent)` → child→parent変換 = t2·inv(t1)。
  NAUO がインスタンス名を保持。
- ルート直下サブアセンブリ（=主要構造物）:
  `FG26_F_02_CENTER_GOAL`(EXTINGUISHER中央), `FG26_F_03_SIDE_GOAL_RED/BLUE`(SUPPRESSION赤/青),
  `FG26_F_05_HUMAN_PLAYER_STATION_RED/BLUE`(FIRE SHIELD), `GUARD_RAIL`, `RISER`,
  `GAMEPIECE A`(球), `FG_F_06_01..04`, `RED/BLUE_FIELD_TAPE`, `RED/BLUE_ALLIANCE_TAPE`

### 現状の自前パーサ（`tools/extract_step_geometry.py` v2）の問題
出力 `Assets/FGC/Settings/field_geometry_step.json` は **まだ信用できない**：
- 全体サイズが `1.86 × 15.81 × 13.84 m` になった（期待: 約 7 × 7 × 2m）→ 異常
- GUARD_RAIL/RISER が 0.7m（本来7m級）と過小、CENTER_GOAL が 15.8m と過大、球が扁平
- **原因①**: 曲面（球=SPHERICAL_SURFACE、管=CYLINDRICAL_SURFACE）は半径が点に出ないため
  点群AABBでは寸法を取り違える（過小）。
- **原因②**: 一部オカレンスで変換が暴れて点が遠方へ飛ぶ（過大）。child/parentのSR順、
  またはt1/t2の取り違え、回転合成の検証が未完。中断時は GUARD_RAIL/RISER の
  「局所AABB vs 累積変換」を切り分けるデバッグの途中だった。

### 推奨する直し方（次セッションの最優先候補）
- **案A（厳密・推奨）**: OpenCASCADE を使う。Python 3.10 を確実に用意し（§3で要対処）、
  `pip install cadquery`（OCP同梱）。XCAFでラベル付きソリッドごとに**正確なBBox＋配置**を取得（約30行）。
  曲面も正しい。点群やアセンブリ自前解決の苦労が消える。これが本命。
- **案B（即・確実に前進）**: STEPからの**サブ部品AABB抽出は諦め**、
  §6のルール値でフィールドを組む。STEPからは**主要8構造物の配置（depth-1の単一変換）だけ**使う
  （単一変換なので誤差が乗りにくい）。既存 `field_obstacles.json`（下記）の2D座標で当面は十分動く。
- どちらでも、**最後に必ずユーザー提供のフィールド外観図と照合**すること
  （上辺中央にEXTINGUISHER＋赤青SUPPRESSION、対角にBRACE2本、手前隅に赤青FIRE SHIELD、
   左右外側にゾーンテープとALLIANCE STATION）。

### 既存 field_obstacles.json（粗いが妥当な2D座標・流用可）
`FGCシミュレータ/field_obstacles.json` に7m平面の実測座標あり：
suppression_unit 多角形(1.627,0)-(5.373,0)-(4.946,0.747)-(2.054,0.747)、
fire_shield_left/right、brace_2/4(円,r0.024)、brace_diag_left/right(カプセル)、
RED/BLUE_FIELD_TAPE。viewpoints: 赤=x7側/青=x0側。

---

## 8. 既に作成したファイル
- `PLAN.md` … 設計の正典（全体像・アーキ・マイルストーン）
- `HANDOFF.md` … 本書
- `tools/extract_step_geometry.py` … STEP抽出 v2（§7の通り未完。要修正 or 案Aへ）
- `Assets/FGC/Settings/field_geometry_step.json` / `field_geometry_report.txt` … 出力（**現状不正確**）
- **【2026-06-09 自走tick追加】** Unity雛形＋Core層（純C#）一式：
  - `Packages/manifest.json`（Input System / OpenXR / XR Management / XRI / ML-Agents 3.0.0 / URP）。
    ※Meta XR SDK は朝Unity起動後に追加。バージョンはPackage Managerで要解決。
  - `ProjectSettings/ProjectVersion.txt`（6000.0.32f1 と**仮置き**。実導入版に合わせ要修正）
  - `Assets/FGC/Settings/field_obstacles.json`（参照元 `FGCシミュレータ/` からコピー＝3D化の土台）
  - `Assets/FGC/Scripts/Core/`（asmdef: FGC.Core, noEngineRef=false）:
    `AllianceColor.cs`(AllianceColor/ClimbZone enum) / `FieldConstants.cs`(ルール由来寸法・§6を定数化) /
    `DriveCommand.cs`(制御指令struct) / `RobotObservation.cs`(観測struct) /
    `RobotSpec.cs`(Java移植スペック＋MaxSpeed/MaxAccel派生) /
    `DifferentialDriveModel.cs`(差動2輪運動学・deadzone/expo/加速度制限) /
    `ScoreCalculator.cs`(REGIONAL/CLIMB倍率/PARTNER_CLIMB/COOPERTITION・端数切上げ) /
    `MatchManager.cs`(タイマー・フェーズ状態機械・静定判定)
  - `Assets/FGC/Scripts/Robot/`（asmdef: FGC.Robot→FGC.Core参照）: `IRobotController.cs`（差込口IF・最重要設計）
  - **未検証**: Unity未導入のためコンパイル未確認。朝Unityで開いてパッケージ解決→微修正前提（PLAN§6）。
  - **要確認TODO**: ScoreCalculator の EXTINGUISHER点/COOPERTITION のリージョン配分解釈（GLOBAL値を各
    リージョンに単純加算してよいか）をGame Manualで再確認。ScoreCalculator.cs 冒頭コメント参照。
- **【2026-06-09 自走tick2追加】** Sim層フィールド構築：
  - `Packages/manifest.json` に `com.unity.nuget.newtonsoft-json 3.2.1` 追加（入れ子配列JSON解析用）
  - `Assets/FGC/Scripts/Field/`（asmdef: FGC.Field→FGC.Core, Newtonsoft.Json 参照）:
    `FieldData.cs`（field_obstacles.json のNewtonsoftモデル＝polygon/circle/capsule・(x,y)→(x,h,y)変換）/
    `FieldBuilder.cs`（床・ガードレール4辺・SUPPRESSION/FIRE_SHIELD=多角形押出メッシュ(凸MeshCollider)・
    BRACE円=円柱(0.24〜1.97m)・BRACE斜材=カプセル・テープ=薄板/衝突なし・EXTINGUISHER=ルール値で中央上辺生成）
  - **未検証**: Unity未導入のためコンパイル/見た目未確認。
    BuildPrismMesh の三角形巻き方向→**tick29で構造体マテリアルを両面表示(カリングOff)化し、向きに依らず
    必ず見えるようにした**（厳密な巻き方向の最適化は任意）。fire_shield を SuppressionHeight 流用している点
    （本来は開口/CHUTE構造）は朝に外観図と要照合。
  - **設計メモ**: FieldBuilder.Build(data, parent) は static。エディタの FieldSceneBuilder からも
    実行時 Awake からも呼べる。fieldJson は TextArea or StreamingAssets 読込（現状は手貼り想定）。
- **【2026-06-09 自走tick3追加】** Sim層 Ball/Robot：
  - `Assets/FGC/Scripts/Ball/`（asmdef FGC.Ball→FGC.Core）: `WildfireManager.cs`
    （500球Prepare/EmitAll、各球Rigidbody+SphereCollider+PhysicMaterial、GPU Instancing描画1023バッチ、
    StoreBall(格納カウント:Suppression赤/青・Extinguisher)）
  - `Assets/FGC/Scripts/Robot/`: `RobotActor.cs`（[RequireComponent(Rigidbody)]・FixedUpdateで
    Controller.GetCommand→DifferentialDriveModel.Step→水平速度&Yaw角速度を速度制御で適用・
    転倒防止にX/Z回転拘束・ObservationProviderでSim層から観測注入・ClimbZone/HeldBalls保持）
  - **未検証/次段**: 機構（インテーク=吸引トリガ/シュート=射出/登攀ジョイント）は HandleMechanisms が空。
    格納トリガ(StorageKind判定のColliderコンポーネント)とインテーク実装が次の具体化対象。
  - **注意**: Unity6で `Rigidbody.velocity` は非推奨(`linearVelocity`)の可能性。朝のコンパイルで要置換確認。
- **【2026-06-09 自走tick4追加】** Control層 Human/NPC（参照元Java挙動を移植）：
  - `Assets/FGC/Scripts/Input/`（asmdef FGC.Input→FGC.Core, Unity.InputSystem）: `GamepadInput.cs`
    （Gamepad.current から DriveCommand。マッピングはJava GamepadInputAdapter踏襲＝左stickY並進/右stickX旋回/
    LT回収>0.5/RTシュート>0.5/△登攀暫定。Input Systemは上+Yのため符号反転不要）
  - `Assets/FGC/Scripts/Robot/`: `HumanController.cs`（IRobotController・GamepadInput.Read()を返す）/
    `RuleBasedNpcController.cs`（Java Behaviors.java の steerTowards=P制御kP6.0/±3rad/s を移植。
    簡易FSM: 最寄り球へ→近接Intake→満杯でGoal(SUPPRESSION重心3.5,0.37暫定)へ→近接Shoot。
    向き偏差>0.6radで前進0.3倍。指令は正規化[-1,1]で返す）
  - FGC.Robot asmdef に FGC.Input 参照を追加。
  - **未検証/要確認**: ①右stick旋回の符号（RobotActorのYaw角速度向きと整合するか統合時に確認）。
    ②NPCのGoalTargetはリージョン別の正しいSUPPRESSION/EXTINGUISHER座標に要差し替え。
    ③player回避・複数球の取り合いは未移植（Java版 seekBallAvoidPlayer の回避ベクトルは将来追加）。
  - **次段**: RobotActor.HandleMechanisms の機構実装（インテーク吸引・格納トリガ Collider・シュート射出）。
    Sim層で観測(ObservationProvider)に最寄り球を充填する配線も必要。
- **【2026-06-09 自走tick5追加】** ML層（差込口＋Python学習環境）：
  - `Assets/FGC/Scripts/ML/`（asmdef FGC.ML→FGC.Core/FGC.Robot/Unity.ML-Agents）: `MLAgentController.cs`
    （`Agent` 継承かつ `IRobotController` 実装。観測16次元=位置/向き(sin,cos)/速度/角速度/保持数/登攀/残時間/
    自Suppression/Extinguisher/最寄り球相対。行動=連続2(並進,旋回)+離散3(Intake,Shoot,Climb)。
    Heuristic は RuleBasedNpcController にフォールバック＝自己対戦相手やデモに流用可）
  - `ml/fgc_ppo.yaml`（PPO＋self_play。behavior名=FgcRobot、observation16/連続2+離散3前提。max_steps 20M）
  - `ml/setup_env.ps1`（Python3.10探索→venv .venv310→torch cu121＋mlagents==1.1.0。
    HANDOFF§3の「py -3.10で見つからない」問題に対応し既知パス総当り探索を実装）
  - `ml/train.ps1`（mlagents-learn 起動。-RunId/-Resume/-EnvExe対応。EnvExe指定時 num-envs8/no-graphics/
    time-scale20でヘッドレス並列学習。run-id時刻はスクリプト側Get-Dateで付与＝Claude実行制約と無関係）
  - **要確認**: ML-Agents C#(3.0.0)とPython(mlagents 1.1.0)のバージョン対応を朝に突合（不一致なら合わせる）。
    観測/行動の次元はMLAgentControllerとyamlで一致必須（Behavior Parameters のVector Obs/Action設定も要GUI設定）。
- **【2026-06-09 自走tick6追加】** VR層＋Match設定：
  - `Assets/FGC/Scripts/Match/`（asmdef FGC.Match→FGC.Core/FGC.Robot）: `SlotAssignment.cs`
    （ControllerKind enum=Human/RuleBasedNpc/MLAgent、SlotAssignment struct）/ `MatchConfig.cs`
    （MatchMode=Practice/ThreeVsThree、PlayerAlliance、EyeHeight、6スロット割当。RebuildSlots=自陣人間1+NPC2/
    敵陣NPC3。SetSlotKindで実行時差し替え）
  - `Assets/FGC/Scripts/VR/`（asmdef FGC.VR→FGC.Core/FGC.Match/Unity.XR.CoreUtils）: `VrPlayerRig.cs`
    （XROrigin Device原点＋CameraYOffsetで目線高さ上書き＝実身長非依存。身長プリセット/スライダ・実行中変更可）/
    `AllianceStationPlacer.cs`（赤=x7外側/青=x0外側 150cm相当、中央向き。static Place(rig,alliance)）
  - `Assets/FGC/Scripts/UI/`（asmdef FGC.UI→FGC.Core/FGC.Match/FGC.VR）: `SetupMenu.cs`
    （IMGUI窓: モード/同盟/身長スライダ/6スロット割当/試合開始。OnStartRequested・OnConfigChangedで通知。
    依存少なくまず動かす方針。後でVRワールド空間Canvasへ載せ替え）
  - **未検証/次段**: ①XROrigin API（CameraYOffset/CameraFloorOffsetObject/RequestedTrackingOriginMode）の
    XRI3.x実APIを朝に確認。②SetupMenuはIMGUIなのでVR内では見えない→ワールド空間UI化が必要。
    ③配置はALLIANCE STATION厳密座標でなく辺中央＋150cmの簡易。外観図と要照合。

## 9. まだ未着手（PLAN.md §5 の残り）
- ✅ 2. Unityプロジェクト雛形（manifest/ProjectVersion/asmdef）→ **完了**（§8。版番号は要実値化）
- ✅ 3. Core（FieldConstants/ScoreCalculator/MatchManager/RobotSpec/DifferentialDriveModel）→ **完了**（§8）
- ✅ 4. Sim：FieldBuilder/FieldData/WildfireManager/RobotActor → **完了**（機構の具体化は残）
- ✅ 5. Control：HumanController/RuleBasedNpcController/MLAgentController差込口 → **完了**
- ✅ 6. VR：VrPlayerRig/AllianceStationPlacer/SetupMenu＋MatchConfig/SlotAssignment → **完了**（API確認残）
- ✅ 7. ML：MLAgentController + fgc_ppo.yaml + setup_env.ps1 + train.ps1 → **完了**（要バージョン突合）
- ✅ 8. Editor：`FieldSceneBuilder`（FGCメニュー1クリックでシーン生成）＋統合層 MatchDirector → **完了**
- ✅ 9. README_SETUP.md → **完了**（Unity導入/起動/Play/朝の修正5項目/ML学習/外観図照合/早見表を網羅）

### 🎉 PLAN.md §5 マイルストーン 2〜9 を全完了。以降は「機構の具体化」フェーズへ。
**次フェーズ（PLAN残・優先順）:**
1. ✅ **インテーク機構** → **完了**（tick8）。`App/RobotIntake.cs`（前方 isTrigger Box "IntakeMouth"＋
   `IntakeTriggerRelay` でOnTriggerStay中継。Intake指令中・容量5未満・クールダウン0.15s毎に1球。
   `WildfireManager.CollectBall(go)` で場の球のみ回収＝HeldBalls++）。RobotActorに `LastCommand` 公開追加。
   WildfireManagerに `CollectBall`(回収/非加算) と `LaunchBall`(シュート用放出) 追加。MatchDirectorで付与・結線。
2. ✅ **格納トリガ＋スコア** → **完了**（tick9）。`App/StorageTrigger.cs`（isTrigger Box、OnTriggerStayで
   保持球を持つロボから1球ずつ預ける。SUPPRESSIONは同盟一致のみ/EXTINGUISHERは全可。クールダウン律速）。
   `WildfireManager.DepositToStorage(kind,count)` 追加。`App/ScoreBoard.cs`（Core ScoreCalculatorで
   RED/BLUE REGIONAL＋COOPERTITION をUpdate毎に再計算）。MatchDirectorで格納3ゾーン生成＋ScoreBoard結線＋
   OnMatchFinishで最終スコアLog。**格納ゾーン座標は暫定（前辺左右＋上辺中央）→外観図と要照合**。
3. ✅ **シュート射出** → **完了**（tick10）。`App/RobotShooter.cs`（Shoot指令中、fireInterval0.3s毎に
   保持球1個を機体前方＋仰角25°/初速6m/sで `WildfireManager.LaunchBall` 放出、HeldBalls--）。MatchDirectorで付与・結線。
   ※調整余地: launchSpeed/elevationDeg/muzzleOffset は実機の見た目と格納口到達性で要チューニング。
4. ✅ **登攀(CLIMB)判定** → **完了**（tick11）。`App/RobotClimb.cs`（OnCollisionStayで名前"brace*"の
   コライダ接触を検知＝接触中、機体高さyを低端0.24〜高端1.97を3分割してContact/Z1/Z2/Z3判定→
   `RobotActor.ClimbZone`更新。非接触1.0s猶予後None）。MatchDirectorで付与。ScoreBoardが集計済→倍率に即反映。
   ※要校正: 3ゾーン境界は実テープ位置で要調整。物理的に差動2輪が管を登れるかは別問題（将来: 登攀補助/磁着）。
5. ✅ **HUD** → **完了**（tick12・IMGUI版）。`App/MatchHud.cs`（残時間mm:ss＋フェーズ上部中央、
   RED/BLUEスコア左右、COOPERTITION補足。GUIStyleで大字）。FieldSceneBuilderで生成・director結線。
   ※残: VRワールド空間uGUI Canvas化（HMD内で見えるように）＋SetupMenuのVR内Canvas化。自ロボ強調は未。
6. ✅ **ML報酬配線** → **完了**（tick13）。`App/RewardShaper.cs`（格納+1[自Suppression]/+0.5[Extinguisher]・
   登攀ゾーン上昇+0.3/段・毎秒-0.01の時間整形）。StorageTriggerに `OnDeposited(actor,kind)` イベント追加、
   MLAgentControllerに `AddShapedReward`/`FinishEpisode` 公開API追加。MatchDirectorが格納ゾーンをリスト保持
   →RewardShaperにBind、OnMatchFinishで各MLロボへFinishEpisode。**これでML学習が実際に回せる状態**。

### ★機構フェーズ①〜⑥すべて完了。試合の全メカニクス＋スコア＋HUD＋ML報酬が揃った。
**残（仕上げ・校正フェーズ。優先順）:**
A. **コンパイル疎通**（最優先・人間/Unity必須）: Unity起動→全asmdef参照解決→§4(README)の既知5項目修正
   （Rigidbody.velocity→linearVelocity 等）。これを通すまで全コードは未検証である点に留意。
   - 【tick20確定】導入版= **Unity 6.3 LTS / 6000.3.9f1 / changeset 7a9955a4f2fa**（検証済）。
     ProjectVersion.txt 反映済・READMEにGUI/CLI両手順記載済。CLIは `--%` で `--` を渡す（PowerShell停止解析）。
   - 【再確認】Unity Hub CLIは Electron のため標準出力に無言（前回cmd二重引用・今回 `--%` 単独実行とも無出力）。
     → CLIは「実行はされるが結果はGUIで確認」前提。版一覧の取得は不可なので version/changeset は外部DB
     （unity-changeset.natsuneko.com）で取得する運用に確定。**この件はもう調査不要**。
H. ✅ **観測16不整合バグ修正＋Core自動テスト** → **完了**（tick16）。
   - バグ: MLAgentController.CollectObservations が実際15観測なのに ObservationSize=16 だった（実行時に
     observation size mismatch でクラッシュする）。**最寄り球距離を1観測追加して16に整合**（コメントで番号明記）。
   - `Assets/FGC/Tests/`（asmdef FGC.Tests, Editor/UNITY_INCLUDE_TESTS, nunit参照）: `ScoreCalculatorTests.cs`
     （CLIMB倍率/PARTNER/COOP/CountZone3/RegionalScore切上げ）＋`CoreModelTests.cs`（MaxSpeed/デッドゾーン/
     加速度制限/左右輪速対称/MatchManagerフェーズ遷移＋終了イベント1回）。
   - 朝にUnity Test Runner(EditMode)で緑確認すればルール/運動学/状態機械の正しさが自動保証される。
   - ✅ 解決(tick17): MaxSpeed は ×0.8込みの ≈1.51m/s が正典（RobotConfig.java:142）。実装・テスト・
     PLAN/HANDOFFの数値を全て1.51に統一済。旧「≈1.88」は誤記だった。最高速の定義の曖昧さは無し。
I. ✅ **機構パラメータを正典値に修正＋集約** → **完了**（tick18）。RobotConfig.java から正しい値を確認し、
   `FieldConstants` に機構定数を集約（RobotBallCapacity=10/IntakeFrontDepth0.08/IntakeFrontWidth0.50/
   ShootRange2.0/ShootAngleThreshold0.2618/ShootSuccessProb0.7/ShootBps5.0/ShootMissRadius2.0）。
   - **修正したバグ**: ①保持容量が 5（誤）→**10**（正典）。RobotIntake/RuleBasedNpcController が5だった。
     MLの `HeldBalls/10f` 正規化は偶然正しかった→`/RobotBallCapacity` に統一。
     ②発射間隔 0.3s（誤）→**0.2s**（shootBPS=5正典）。RobotShooter.fireInterval=1/ShootBps に。
   - インテーク口寸法も正典の前面幅0.50/奥行0.08を反映。
   - ✅ 反映(tick19): 下記Jで射撃ゲート＋スプレッド実装済。
J. ✅ **シュート精度ロジック** → **完了**（tick19）。
   - `RuleBasedNpcController`: 射撃判断を正典値ゲートに変更＝「満杯保持＋ゴール射程内(ShootRange=2.0)＋
     正対(headingErr≤ShootAngleThreshold=15°)」で Shoot。重複していた局所const ShootRange(1.2)を削除し
     FieldConstants参照に統一。headingErrを1回算出して減速判定にも再利用。
   - `RobotShooter`: 狙いの小ばらつき `aimSpreadDeg=4°`（ヨー/ピッチに±2°）を発射方向に付与。
   - **設計判断（記録）**: Java版 shootSuccessProb(0.7)/shootMissRadius(2.0) の「命中サイコロ」は本3D版では
     **使わない**。球を実際に飛ばし格納トリガで成否が物理的に決まる（PLAN§3.4）ため。確率の代わりに
     aimSpreadで狙いの不完全さを表現。→ ShootSuccessProb/ShootMissRadius定数は当面未使用（arcadeモード用に保持）。
K. ✅ **ML連続学習用 エピソード間リセット＆自動再戦** → **完了**（tick21）。
   - `RobotActor.ResetState(pos,rot)`（位置/速度/角速度/運動学/HeldBalls/ClimbZone/LastCommand を初期化）。
   - `WildfireManager.ResetToPool()`（場の球を破棄せずプールへ戻し格納計数0。再利用で学習高速）。
   - `MatchDirector`: `autoRestart`フラグ＋`ResetMatch()`（_match.Reset/球Reset/全ロボを開始位置へ）。
     OnFinishで rewards→FinishEpisode の後、autoRestart時は ResetMatch()→BeginMatch() で次試合を即開始。
   - **これで学習が1試合で止まらず連続エピソードを回せる**。**学習時は MatchDirector.autoRestart=true** に
     する（VR対戦ではfalseのまま）。Editor学習では Inspector でON、ビルド環境学習では専用シーン/起動時設定で。
   - 注意: 現状エピソードは「全エージェント同時に150sで終了→env一括リセット」のグループ episode 方式。
     ML-Agents的には妥当だが、個別 MaxStep を使う設計に変えるならここを調整。
L. ✅ **学習用シーン生成（1クリック）** → **完了**（tick22）。
   - メニュー **「FGC > Build Training Scene」**: VRリグ無し・俯瞰カメラ・全6機ML・autoRestart=true・
     autoBegin=true でシーン生成。SetupMenu無しで Play すれば即・連続でエピソードが回る。
   - `MatchConfig.SetAllKind(kind)`（全スロット一括設定）と `MatchDirector.autoBegin`（Start時に即BeginMatch）を追加。
   - 運用: ①Editor学習= このシーンで mlagents-learn 起動→Play。②高速並列= このシーンをビルドして
     `./ml/train.ps1 -EnvExe <exe>`（no-graphics/time-scale20/num-envs8）。
   - 対戦用は従来どおり「FGC > Build Field Scene」（VR・SetupMenu・autoRestart=false）。
M. ✅ **シュート飛来球のゴール得点化** → **完了**（tick23）。これまでスコアは「ロボがゾーンで保持球を預ける」
   経路のみで、撃った球がゴールに入っても無得点だった欠落を解消。
   - `WildfireManager.LaunchBall` を bool→GameObject返しに変更（射出球を取得可能に）。
   - `App/ShotBall.cs`（撃った主RobotActor＋ttl3s保持。Updateで寿命切れ自壊、OnDisableでも自壊＝プール再利用や
     EXTINGUISHER放出球(spill)を誤って得点化しない）。RobotShooterが射出球にArmして付与。
   - `StorageTrigger.OnTriggerStay` 二経路化: (A)ShotBall付き飛来球→即捕捉して得点＋**撃った主へOnDeposited**
     （報酬帰属を維持）/(B)従来の保持球デポジット。同盟制限はSUPPRESSIONで撃った主の同盟一致を要求。
   - これで「運んで入れる」「撃って入れる」両方が得点化。報酬帰属が崩れないのでML学習にも整合。
   - 留意: 飛来球捕捉はクールダウン非依存・即時。EXTINGUISHER放出球はShotBall無しなので誤判定しない。
N. ✅ **インテークが発火しない実バグ修正** → **完了**（tick24）。
   - 原因: "IntakeMouth"トリガはロボ(Rigidbody)の子＝複合コライダ。Unity仕様で**トリガイベントは
     Rigidbody所有者(親)に送られる**ため、子に付けた `IntakeTriggerRelay.OnTriggerStay` は永久に発火せず
     インテークが全く動かない状態だった（皮肉にも当該コメント自身が「親に上がる」と書いていた）。
   - 修正: `RobotIntake` 自身に `OnTriggerStay` を実装し、`IntakeTriggerRelay` クラスを削除。
   - 横展開確認: RobotClimb=親GameObjectで `OnCollisionStay`（Rigidbody所有者が受信＝正しい）。
     StorageTrigger=独立トリガ(Rigidbody無し)で侵入Rigidbodyを検出＝正しい。両者は問題なし。
   - 教訓追記（§11）: 子トリガ＋親Rigidbodyの複合コライダでは、トリガ/衝突コールバックは親に来る。
O. ✅ **asmdef依存グラフ監査＋スロット割当テスト** → **完了**（tick25）。
   - 全12 asmdef の参照を実usingと突合。**循環なし(DAG)・必要参照は全て充足**を確認。
     （未使用参照が2件: Match→Robot, VR→Match。無害なので残置。将来Robot型を使う余地あり）
   - `Assets/FGC/Tests/MatchConfigTests.cs` 追加（FGC.Tests asmdef に FGC.Match 参照を追加）。
     Default(赤/青)のHuman位置・赤青振り分け・SetAllKind・SetSlotKind・RebuildSlots を検証。
   - これで Core(スコア/運動学/状態機械)＋Match(スロット割当) の純ロジックが自動テストで守られる。
P. ✅ **Rigidbody.velocity→linearVelocity 先行修正** → **完了**（tick26）。Unity6改称に合わせ全7箇所
   （WildfireManager×2/RobotActor×4/MatchDirector×1）を `linearVelocity` へ。`angularVelocity`は改称なしで維持。
   README §4 の修正項目#1を「適用済」に更新。→ 朝のコンパイル時の非推奨警告が1種減る。
   残るREADME§4の朝作業: #2 Unity側パッケージ名の一致確認・#3 BehaviorParametersのGUI設定(自走でコード化済だが
   念のため)・#4 XROrigin API名・#5 球メッシュ。いずれもUnity起動が必要な確認系。
Q. ✅ **ロボが不可視だった抜けを修正** → **完了**（tick27）。CreateRobotはCollider+Rigidbody+各機構のみで
   MeshRendererが無く、ロボがシーンに見えなかった（VRで観戦/操作する前提なのに不可視）。
   - `MatchDirector.AddRobotVisual()`: 同盟色(赤/青)の本体Cube＋向きが分かる白い前方マーカーを子に追加
     （コライダは親BoxColliderに任せるので Visual はコライダ破棄）。
   - `MakeColoredMaterial()`: レンダーパイプライン非依存（URP/Lit→Standard→Unlit/Color の順で探索、
     _BaseColor/_Color 両対応）。→ URP未設定でもマゼンタ化しにくい。
   - これでフィールド(プリミティブ=可視)・球(GPU Instancing=可視)・ロボ(今回追加=可視) が揃い**観戦可能**に。
   - 留意: フィールドのプリミティブは CreatePrimitive 既定マテリアルなのでURPでマゼンタの可能性。
     気になるなら FieldBuilder も MakeColoredMaterial 相当に差し替え（将来の小タスク）。→ tick28で解消。
R. ✅ **全描画物の色付け（URPマゼンタ解消）** → **完了**（tick28）。
   - `Core/MaterialUtil.cs` 新設: パイプライン非依存の色付きマテリアル生成（URP/Lit→Standard→Unlit探索、
     _BaseColor/_Color両対応）＋カテゴリ別プリセット（Floor/Rail/Structure/Brace/Red/BlueTape/Extinguisher）。
   - `FieldBuilder`: 床/レール/SUPPRESSION・FIRE_SHIELD/BRACE/テープ(赤青id判別)/EXTINGUISHER に色割当。
     CreateBox・BuildPrism にMaterial引数追加。
   - `MatchDirector`: 重複していたMakeColoredMaterialを削除しMaterialUtilへ集約。
   - `FieldSceneBuilder`: 球マテリアルもMaterialUtil(橙)に統一。
   - これでフィールド/ロボ/球が**URP環境でもマゼンタにならず識別しやすい色**で表示される。観戦性が向上。
S. ✅ **角柱押出メッシュの裏返り不可視リスクを解消** → **完了**（tick29）。
   - 自前生成のSUPPRESSION/FIRE_SHIELD角柱は、入力多角形のCW/CCW次第で面が裏返りカリングで消える懸念があった。
   - `MaterialUtil.Colored(color, doubleSided)` 追加（URP/Litの `_Cull=0` で両面表示）。`Structure()` を両面化。
   - 巻き方向の理論最適化は検証なしでは危険なため、**両面表示で視認を確実に保証**する安全策を採用。
     厳密な法線/巻き方向の最適化はUnityで見ながら行う任意タスク（lighting微差のみ・視認には影響なし）。
T. ✅ **ML観測に近傍ロボ追加（3v3戦術学習）** → **完了**（tick30）。PLAN §3.8の「相手位置」観測の欠落を解消。
   - `RobotObservation` に NearestOpponent/HasOpponent/NearestTeammate/HasTeammate を追加。
   - `MatchDirector.BuildObservation` が全ロボから自機を除き、同盟一致=味方/不一致=相手の最寄りを算出。
   - `MLAgentController.CollectObservations` に相手・味方の相対x/z＋有無=6観測を追加。**ObservationSize 16→22**。
   - README §4 #3 の Vector Obs Size を22に更新（ConfigureBrainで自動設定済）。yamlは観測数非依存(normalize)なので変更不要。
   - これでエージェントが相手/味方を知覚でき、3v3の協調・妨害を学習できる土台になった。
   - 留意: 観測は「最寄り1体ずつ」。全5体を入れるならグリッド/レイセンサ化が将来の拡張（PLAN §3.8の任意項）。
U. ✅ **自ロボ強調マーカー（PLAN §3.7）** → **完了**（tick31）。VRで人間が自機を見失わないよう、
   人間操作スロットのロボ頭上(y0.6)に緑の浮遊球マーカーを表示。`MatchDirector.SetPlayerMarker(actor,on)` を
   ReassignControllers から呼び、kind==Humanなら出す/それ以外は消す（差し替え時も追従）。コライダ無し。
   - これでPLAN §3.7「HUD：自ロボ強調」の強調部分が満たされた。HUDのスコア/時計は既に MatchHud で実装済み。
V. ✅ **STEP高精度抽出（案A）完遂** → **完了**（tick32）。当初HANDOFF§7「最大の未解決事項」を解決。
   - **解決の鍵**: cadquery-ocp は **cp313 wheel あり**＝**Python 3.13 で動く**（3.10は不要＝ML-Agents専用の話と
     取り違えていた）。`py -3.13 -m pip install --user cadquery` で導入(cadquery 2.7.0/OCP OK)。
   - `tools/extract_step_cadquery.py`: OCP XCAF でラベル付き387部品を再帰し AddOptimal(曲面対応)で精密AABB取得
     → `Assets/FGC/Settings/field_geometry_cadquery.json`。**寸法が正確に**: RISER=7.0×0.7×7.0 /
     GUARD_RAIL=7.4×0.9×7.4 / GAMEPIECE(球)=0.100³ / EXTINGUISHERトラス管=2.001m / SUPPRESSION=2.009m。
     旧自前パーサが壊していた寸法(1.86×15.8等)を完全に解消。
   - `tools/distill_field_structures.py`: 主要構造物に集約 → `Assets/FGC/Settings/field_structures.json`。
   - **★判明した実フィールド配置（STEP中心原点 x:±5.2/z:±3.7。Unity変換 = step+3.5 で 0..7系へ）**:
     | 構造 | STEP center_xz | Unity center(x,z) | サイズ(x,h,z) |
     |---|---|---|---|
     | RISER | (0,0) | (3.5,3.5) | 7.0×0.7×7.0 |
     | SUPPRESSION_RED | (-1.405,-2.95) | (2.095,0.55) | 1.82×2.01×1.11 |
     | SUPPRESSION_BLUE | (1.405,-2.95) | (4.905,0.55) | 1.82×2.01×1.11 |
     | EXTINGUISHER(中央トラス) | (0,-0.116) | (3.5,3.384) | 4.71×2.02×6.85 |
     | FIRE_SHIELD_RED | (-2.756,2.903) | (0.744,6.403) | 1.51×0.98×1.19 |
     | FIRE_SHIELD_BLUE | (2.77,2.903) | (6.27,6.403) | 1.54×0.97×1.19 |
     | ALLIANCE_TAPE_RED | x=-4.45 | x≈-0.95(左外) | 1.5×_×7.4 |
     | ALLIANCE_TAPE_BLUE | x=4.45 | x≈7.95(右外) | 1.5×_×7.4 |
   - **重要な気づき**: 実配置は私の推測FieldLayoutと違う。①SUPPRESSION赤青は「前辺左右」で正しいが z≈0.55、
     ②FIRE_SHIELDは反対側 z≈6.4、③EXTINGUISHERは中央を横断する大トラス、④ALLIANCE_TAPE(=選手位置)は
     **左右の外**(x≈-0.95/7.95)。⑤CADでは赤=左(x小)/青=右(x大)で、field_obstacles.jsonのviewpoints(赤=x7)とは
     左右が逆。赤青はユーザ選択式なので致命的でないが、AllianceStationPlacer/FieldLayoutの座標は上表が正典。
   - **次の一手**: 上表の精密値を `Core/FieldLayout.cs` へ反映（格納ゾーン/STATION/NPCゴール）。
     `FieldBuilder` のEXTINGUISHERも中央トラスとして作り直す。BRACE/truss詳細は cadquery JSONから個別に拾える。
   - これで「§7最大の未解決事項」が完全クローズ。**案Aは上振れどころか本命を回収**。
W. ✅ **STEP実測値を FieldLayout へ反映** → **完了**（tick33）。Vの精密値をコードに落とした。
   - `Core/FieldLayout.cs`: SUPPRESSION赤=(2.095,0.55)/青=(4.905,0.55)・サイズ1.82×1.11、EXTINGUISHER中央(3.5,3.384)、
     FIRE_SHIELD赤(0.744,6.403)/青(6.27,6.403)を追加、ロボ開始位置と STATION後退0.95mも実測準拠に。
   - **赤=左/青=右に統一**（CAD・json FIELD_TAPE と一致）。旧コードは赤=右で左右逆だった→`AllianceStationPlacer`
     も赤=x0側(左)/青=x7側(右)に修正＋クラスコメント更新。
   - 効果: 格納ゾーン・NPCゴール・ロボ初期配置・選手立ち位置が**実フィールド形状に整合**。
   - **残（次の一手）**: ①FieldBuilderのEXTINGUISHERを「上辺の背板」から「中央を横断する大トラス(z3.384中心・
     幅4.71×奥6.85×高2.0)」へ作り直す。②SUPPRESSIONの視覚体も z≈0.55 の実位置・実サイズへ。
     ③現状 FieldBuilder は field_obstacles.json(旧2D・赤右系)を読むので、構造の視覚位置とFieldLayout(新)に
     ズレが残る。FieldBuilderをfield_structures.json駆動に寄せるのが本筋（次tick以降）。
     ※ロジック(格納/得点/NPC)はFieldLayout参照なので既に新座標。視覚との一致が残課題。→ tick34で解消。
X. ✅ **FieldBuilderをSTEP実測駆動に（視覚とロジックの整合）** → **完了**（tick34）。
   - `Field/FieldStructures.cs` 新設: field_structures.json をNewtonsoftで読み、STEP→Unity変換
     (x,z は +3.5 / y は -0.7=RISER天面床) で主要構造物を生成。
     SUPPRESSION赤青/FIRE_SHIELD赤青=実AABBの色付きソリッド箱(コライダ付)、テープ=平面マーカー、
     **EXTINGUISHER=中央トラスを「開いた枠(上辺4本＋四隅支柱)」で表現**＝場を塞がない(ソリッド箱だと4.7×6.85で
     フィールド中央が埋まる問題を回避)。
   - `MatchDirector.BuildField`: structuresJson優先→無ければ旧 fieldJson(FieldBuilder)にフォールバック。
     `structuresJson` フィールド追加。`FieldSceneBuilder` が field_structures.json を読み込んで渡す。
   - これで**視覚(FieldStructures)とロジック(FieldLayout)が同じSTEP実測座標で一致**。赤=左/青=右も視覚反映。
   - **残（任意の精緻化）**: ①EXTINGUISHERの開口/格納口の正確位置はトラス枠の中。実遊技で要調整。
     ②SUPPRESSIONの canopy/開口・FIRE_SHIELDのPORT/CHUTE等の内部構造は箱近似（cadquery JSONに個別部品
     (FG_F_03_xx等)があるので必要なら細部を起こせる）。③旧 field_obstacles.json と FieldBuilder は
     フォールバックとして残置（BRACE等は今のstructuresに無いので、BRACEを別途 field_structures に
     追加抽出すると完全）。BRACEはCAD名 FG_F_01_(truss)/FG_F_02 系。次の精緻化候補。→ tick35で暫定対応。
Y. ✅ **新ビルダーにBRACE生成を追加（登攀の機能欠落を解消）** → **完了**（tick35）。
   - 問題: FieldStructures は BRACE を作らないため、RobotClimb("brace*"検出)が反応せず**登攀が機能しなかった**。
   - `FieldStructures.BuildBraces()`: 既存実測座標(field_obstacles.json由来)で brace_2/brace_4(垂直円柱)＋
     brace_diag_left/right(斜材カプセル)を生成。名前"brace_*"・高さBraceLowEnd〜HighEnd・コライダ付。
   - これで新ビルダーでも登攀判定が成立。**位置はCAD/外観図で要確認**（中央トラスとの関係含む）。
     より厳密にはCADの truss(FG_F_01系)から BRACE を field_structures に追加抽出するのが理想（任意精緻化）。
Z. ✅ **球放出口の不整合を修正** → **完了**（tick36）。フィールド再配置でEXTINGUISHERが中央(z3.384)へ移った
   のに、球の放出口 emitOrigin が上辺(z6.8)のまま＝ルール§6「EXTINGUISHER下部開口から500球放出」と矛盾していた。
   - FieldSceneBuilder の emitOrigin を `FieldLayout.ExtinguisherCenter`(x,z)へ。WildfireManager既定も(3.5,0.4,3.384)に。
   - これで開幕の球放出がEXTINGUISHER（中央トラス）下から正しく起きる。格納ゾーン(Extinguisher)も同座標で整合。
   - 教訓: フィールド座標を動かしたら、それに依存する emitOrigin/ゾーン/開始位置を横断的に追従させる必要がある。
AA. ✅ **座標依存値の横断監査** → **完了**（tick37）。Zの教訓を受け、stale座標が他に無いか全Scriptをgrep監査。
   - 結果: **emitOrigin(Z で修正済)が唯一の取り残しだった**。他は全て FieldConstants/FieldLayout 参照 or
     ロボ相対のローカルオフセット(mouthOffset/muzzleOffset/visual等)で、フィールド再配置に正しく追従。整合確認。
   - **発見した要確認事項**: 選手立ち位置は左右辺の中央(z=3.5)だが、CADでは HUMAN_PLAYER_STATION(=FIRE_SHIELD)が
     奥(z≈6.4)。ドライバーは自陣FIRE_SHIELD付近に立つのが本来かも。AllianceStationPlacerにTODOコメントを明記。
     朝に外観図/Game Manualで確認し、必要なら z を FireShieldRed/BlueCenter 側へ寄せる（FieldLayoutに座標あり）。
B. 🔶 **座標校正の集約** → 一元化完了（tick15）。`Core/FieldLayout.cs` に格納ゾーン3箇所・ロボ開始位置・
   NPCゴール（リージョン別）・STATION後退距離を集約。MatchDirector/RuleBasedNpcController/AllianceStationPlacer
   が全て FieldLayout 参照に。**朝はこの1ファイルを外観図に合わせて直すだけで全体反映**。
   残: BRACE3ゾーン境界（RobotClimb）も将来 FieldLayout へ寄せる余地。実数値の照合自体は人間＋外観図で実施。
C. ✅ **ML設定の実体化** → **完了**（tick14）。`MLAgentController.ConfigureBrain()`（Awakeで
   BehaviorParameters[name=FgcRobot/ObsSize=16/ActionSpec(連続2,離散[2,2,2])]＋DecisionRequester[period5]を
   無ければAddComponentして設定）。定数 BehaviorNameConst/ObservationSize/ContinuousActions も公開。
   ※朝のコンパイルで ML-Agents 3.0.0 の API名（BrainParameters.ActionSpec/VectorObservationSize、
   DecisionRequester.DecisionPeriod/TakeActionsBetweenDecisions）を要確認。観測16はCollectObservationsの
   AddObservation回数(現状16)と一致必須（数えて担保済だが朝に再確認）。
D. ✅ **勝敗報酬** → **完了**（tick14）。MatchDirector.OnFinishで自リージョン>相手=+1/<相手=-1/同点0を
   各MLロボへ AddShapedReward してから FinishEpisode（self_play用の終端報酬）。
E. **VR仕上げ**: HUD/SetupMenu をワールド空間Canvas化。OpenXR(Meta Quest)有効化（GUI）。
F. **物理の作り込み**: ロボが実際にBRACEを登れる機構（差動2輪では登れない→登攀補助/磁着/専用ジョイント要検討）。
G. （別軸）STEP厳密抽出（§7案A: cadquery）／Unity本体CLI導入の再トライ。
7. （別軸）STEP厳密抽出（§7案A: cadquery）／Unity本体CLI導入の再トライ。

### 【2026-06-09 自走tick7追加】統合層＋Editor（Play可能状態に到達）
- `Assets/FGC/Scripts/App/`（asmdef FGC.App→全層参照）: `MatchDirector.cs`
  （フィールド構築→6ロボ生成(BoxCollider+Rigidbody+RobotActor)→Controller割当(Human/NPC/ML)→
  観測配線(ObservationProvider=最寄り球をWildfireから/残時間をMatchManagerから)→VR配置→
  BeginMatch(球放出EmitAll＋MatchManager.StartMatch)。UpdateでMatch.Tick）
- `Assets/FGC/Scripts/Ball/WildfireManager.cs` に `TryGetNearestBall(from,out pos,maxRange)` と `LiveCount` 追加
- `Assets/FGC/Editor/`（asmdef FGC.Editor, Editorのみ・全層+FGC.UI参照）: `FieldSceneBuilder.cs`
  （MenuItem "FGC/Build Field Scene"。空シーンに Light/WildfireManager/XR Origin(+Camera+VrPlayerRig)/
  MatchDirector(field_obstacles.json読込)/SetupMenu を生成し OnStartRequested→BeginMatch を結線。
  球メッシュは内蔵Sphere、マテリアルはURP/Lit+Instancing暫定）
- **これで Unity起動→FGCメニュー実行→Play で試合が回る最小構成が揃った**（コンパイル通れば）。
- **未検証/朝の要対応**: ①各asmdefのUnity側アセンブリ参照名（Unity.XR.CoreUtils/Unity.InputSystem/
  Unity.ML-Agents/Newtonsoft.Json）が実パッケージ名と一致するか。②`Rigidbody.velocity`→`linearVelocity`(Unity6)。
  ③MLAgentControllerは BehaviorParameters(Vector Obs=16/連続2+離散3)のGUI設定が別途必要。
  ④XROrigin API（Camera/CameraFloorOffsetObject/CameraYOffset）のXRI3.x実名確認。
  ⑤Resources.GetBuiltinResource("Sphere.fbx")の可用性（URPで差し替えるかも）。

## 10. 次tickの推奨着手順
1. （STEP）案A: Python3.10確保→cadquery導入→厳密BBox再抽出。困難なら案Bで即前進。
2. （並行）Unityエディタ本体をCLIで導入開始（§5）。**注意: Unity Hub CLIは `editors --releases`
   を叩いても標準出力に何も返らなかった（Electronがデタッチ？）。cmd二重引用も無効。
   別手段（Hub GUIで版選択 or `Unity Hub.exe` のログ確認、もしくは既知の最新6000.x LTS版を直指定で
   `install`）を試す。ここで詰まったらUnity導入は保留し、純C#で進められるSim/Control設計を先行。**
3. 次の実装は **Sim層（マイルストーン4）**: FieldBuilder（field_obstacles.json読込→ルール高さで押出）→
   RobotActor（Rigidbody＋DifferentialDriveModelの目標v,ωを接地速度制御で適用）→ WildfireManager。
4. その後 Control（HumanController=GamepadInput / RuleBasedNpcController）→ VR → ML。
5. 各構造物配置を必ずフィールド外観図と照合（§7末尾）。

## 11. ハマりどころ・教訓
- Unity Hub CLI は PowerShell が `--` を消費する。cmd 経由で渡す。
- ML-Agents は Python 3.13 不可（torch/mlagents が3.10系）。3.10を別途用意。
- STEP曲面は点群AABBで取れない（球・管が壊れる）。OpenCASCADE推奨。
- ツール呼び出しは必ず正規の関数呼び出し書式で（`<invoke>`直書きはmalformedになる）。
- 【Unity物理】子コライダ＋親Rigidbodyの複合コライダでは、OnTrigger/OnCollisionコールバックは
  **Rigidbody所有者（親）のGameObject**に送られる。子に付けたスクリプトでは受け取れない（tick24でハマった）。
