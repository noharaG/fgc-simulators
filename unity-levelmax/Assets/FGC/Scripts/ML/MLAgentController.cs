using FGC.Core;
using FGC.Robot;
using Unity.MLAgents;
using Unity.MLAgents.Actuators;
using Unity.MLAgents.Policies;
using Unity.MLAgents.Sensors;
using UnityEngine;

namespace FGC.ML
{
    /// <summary>
    /// ML-Agents による学習可能コントローラ（PLAN.md §3.2 / §3.8）。
    /// ML-Agents の Agent を継承しつつ IRobotController を実装し、6スロットの差込口に置ける。
    ///   観測: 自ロボ位置/向き/速度・最寄り球・格納数・時計・登攀状態（ベクトル観測）
    ///   行動: 連続[並進, 旋回] + 離散[インテーク, シュート, 登攀]
    ///   報酬: 格納+1相当・EXTINGUISHER寄与・登攀ボーナス・反則ペナルティ・時間整形（Sim側から AddReward）
    /// 学習前/推論なし時は Heuristic で RuleBasedNpcController にフォールバック。
    /// </summary>
    public sealed class MLAgentController : Agent, IRobotController
    {
        [Tooltip("観測を供給する関数（Sim層 RobotActor から注入）")]
        public System.Func<RobotObservation> observationProvider;

        private DriveCommand _latest = DriveCommand.Zero;
        private readonly RuleBasedNpcController _fallback = new RuleBasedNpcController();

        // フィールド正規化用スケール
        private const float PosScale = 7.0f;
        private const float SpeedScale = 2.0f;

        // 脳設定（yaml の behavior 名・観測/行動次元と一致させる）
        public const string BehaviorNameConst = "FgcRobot";
        public const int ObservationSize = 110;       // CollectObservations の追加数と一致
        // 内訳: 自分12 + 最寄り球4 + 近傍敵3 + 近傍味方3 + 自陣ゴール/同盟3
        //     + 全能ボール(2-5番目の球8 + 残球数1)=9 + 味方意図(位置2+速度2+保持1+有無1)×2=12
        //     + 全500球の密度グリッド7×7=49（選手の客観視）
        //     + 敵3台の可視情報(位置2+向き2+有無1)×3=15（位置と向きのみ＝意図は読めない。2026-06-12拡張）
        public const int ContinuousActions = 2;       // 並進, 旋回
        private static readonly int[] DiscreteBranches = { 2, 2, 2 }; // Intake, Shoot, Climb

        private void Awake()
        {
            ConfigureBrain();
        }

        /// <summary>
        /// BehaviorParameters / DecisionRequester をコードで実体化（PLAN.md §3.8・仕上げC）。
        /// GUIでの手設定が無くても学習・推論が回るよう、観測16・連続2＋離散[2,2,2] を設定する。
        /// </summary>
        private void ConfigureBrain()
        {
            var bp = GetComponent<BehaviorParameters>();
            if (bp == null) bp = gameObject.AddComponent<BehaviorParameters>();
            bp.BehaviorName = BehaviorNameConst;
            // **重要**: Default でないと外部トレーナ(mlagents-learn)へ接続しない（学習時の必須設定）。
            bp.BehaviorType = BehaviorType.Default;
            // **自己対戦の必須設定**: 赤=Team0 / 青=Team1。全員Team0だと ghost trainer のチーム交代と
            // ELO計算が機能しない（fgc_run9で全agentが FgcRobot?team=0 になりELOが指標にならなかった）。
            // alliance は MatchDirector.CreateRobot が本コンポーネント追加前に設定済み＝Awake時点で確定。
            var actorForTeam = GetComponent<RobotActor>();
            bp.TeamId = (actorForTeam != null && actorForTeam.alliance == AllianceColor.Blue) ? 1 : 0;
            bp.BrainParameters.VectorObservationSize = ObservationSize;
            bp.BrainParameters.NumStackedVectorObservations = 1;
            bp.BrainParameters.ActionSpec = new ActionSpec(ContinuousActions, DiscreteBranches);

            var dr = GetComponent<DecisionRequester>();
            if (dr == null) dr = gameObject.AddComponent<DecisionRequester>();
            dr.DecisionPeriod = 5;
            dr.TakeActionsBetweenDecisions = true;

            // 診断: ML エージェントが実際に生成・設定されたか（Editor.log で接続前提を確認する用）。
            Debug.Log($"[FGC] MLAgentController 設定: behavior='{bp.BehaviorName}' team={bp.TeamId} type={bp.BehaviorType} obs={ObservationSize} on '{gameObject.name}'");
        }

        /// <summary>IRobotController：直近に decision で確定した指令を返す。</summary>
        public DriveCommand GetCommand(in RobotObservation obs, float dt)
        {
            // ML-Agents の意思決定は OnActionReceived 経由で _latest を更新する。
            // ここでは最新の確定指令を返すだけ（RequestDecision の周期は DecisionRequester に委ねる）。
            return _latest;
        }

        public override void CollectObservations(VectorSensor sensor)
        {
            var o = observationProvider != null ? observationProvider() : default;

            sensor.AddObservation(o.Position.x / PosScale);
            sensor.AddObservation(o.Position.z / PosScale);
            sensor.AddObservation(Mathf.Sin(o.HeadingRad));
            sensor.AddObservation(Mathf.Cos(o.HeadingRad));
            sensor.AddObservation(o.Velocity.x / SpeedScale);
            sensor.AddObservation(o.Velocity.z / SpeedScale);
            // ★修正(2026-06-10): 旧 /MatchDuration(150) はスケール誤り（±4rad/sが±0.027に潰れ実質死に観測）。
            // ロボの最大角速度 maxOmega=4rad/s で正規化する。
            sensor.AddObservation(o.AngularVelocity / 4f);

            sensor.AddObservation(o.HeldBalls / (float)FieldConstants.RobotBallCapacity);
            sensor.AddObservation((int)o.ClimbZone / 4f);
            sensor.AddObservation(o.MatchTimeRemaining / FieldConstants.MatchDuration);
            sensor.AddObservation(o.OwnRegionSuppression / 100f);
            sensor.AddObservation(o.GlobalExtinguisher / 100f);

            // 最寄り球（相対位置・距離・有無）= 4観測。ここまでで16次元。
            Vector3 rel = o.HasNearestBall ? (o.NearestBall - o.Position) : Vector3.zero;
            sensor.AddObservation(rel.x / PosScale);                       // 13
            sensor.AddObservation(rel.z / PosScale);                       // 14
            sensor.AddObservation(o.HasNearestBall ? rel.magnitude / PosScale : 1f); // 15 距離
            sensor.AddObservation(o.HasNearestBall ? 1f : 0f);             // 16 有無

            // 近傍ロボ（相手・味方の相対位置＋有無）= 6観測。合計22次元（3v3戦術学習用・PLAN §3.8）。
            Vector3 relOpp = o.HasOpponent ? (o.NearestOpponent - o.Position) : Vector3.zero;
            sensor.AddObservation(relOpp.x / PosScale);                    // 17
            sensor.AddObservation(relOpp.z / PosScale);                    // 18
            sensor.AddObservation(o.HasOpponent ? 1f : 0f);               // 19
            Vector3 relMate = o.HasTeammate ? (o.NearestTeammate - o.Position) : Vector3.zero;
            sensor.AddObservation(relMate.x / PosScale);                   // 20
            sensor.AddObservation(relMate.z / PosScale);                   // 21
            sensor.AddObservation(o.HasTeammate ? 1f : 0f);               // 22

            // ★追加(2026-06-10): 自陣ゴール相対位置＋同盟フラグ = 3観測（23-25）。
            // 赤(Team0)青(Team1)が同一方策を共有するため、これが無いと「自分がどちらの
            // SUPPRESSIONへ運ぶべきか」を観測から判別できず、得点行動が原理的に学習困難だった。
            Vector2 ownGoal = FieldLayout.NpcGoal(o.Alliance);
            sensor.AddObservation((ownGoal.x - o.Position.x) / PosScale);  // 23 自陣ゴールへのΔx
            sensor.AddObservation((ownGoal.y - o.Position.z) / PosScale);  // 24 自陣ゴールへのΔz
            sensor.AddObservation(o.Alliance == AllianceColor.Blue ? 1f : 0f); // 25 同盟(赤0/青1)

            // ★全能ボール観測(2026-06-12): 近傍2〜5番目の球の相対位置(8) + 残球数(1) = 26-34
            AddRelOrZero(sensor, o, o.Ball2, o.BallsValid > 1);
            AddRelOrZero(sensor, o, o.Ball3, o.BallsValid > 2);
            AddRelOrZero(sensor, o, o.Ball4, o.BallsValid > 3);
            AddRelOrZero(sensor, o, o.Ball5, o.BallsValid > 4);
            sensor.AddObservation(o.FieldBallCount / (float)FieldConstants.BallCount); // 残球数(供給の枯渇)

            // ★味方の意図(2026-06-12): 位置Δ(2)+速度(2)+保持(1)+有無(1) ×2台 = 35-46。
            // 速度=進行方向(何をしに行くか)、保持数=満載なら帰投/空なら回収中、の予測材料。
            AddMate(sensor, o, o.Mate1Pos, o.Mate1Vel, o.Mate1Held, o.HasMate1);
            AddMate(sensor, o, o.Mate2Pos, o.Mate2Vel, o.Mate2Held, o.HasMate2);

            // ★全500球の密度グリッド7×7(2026-06-12): 選手の客観視。自陣スタンドから遠いマスほど
            // 距離減衰ノイズで曖昧（MatchDirector.ApplyPerceptionFalloff）。各マス球数を/15で正規化 = 47-95。
            var grid = o.BallGrid;
            for (int i = 0; i < 49; i++)
                sensor.AddObservation(grid != null && i < grid.Length ? Mathf.Min(1f, grid[i] / 15f) : 0f);

            // ★敵3台の可視情報(2026-06-12 情報非対称): 位置Δ(2)+向きsin/cos(2)+有無(1) ×3 = 96-110。
            // 「位置と向きは見える。どの球を狙うか等の意図(速度/保持)は読めない」。
            AddOpponent(sensor, o, o.Opp1Pos, o.Opp1HeadingRad, o.HasOpp1);
            AddOpponent(sensor, o, o.Opp2Pos, o.Opp2HeadingRad, o.HasOpp2);
            AddOpponent(sensor, o, o.Opp3Pos, o.Opp3HeadingRad, o.HasOpp3);
        }

        private static void AddOpponent(VectorSensor sensor, in RobotObservation o, Vector3 pos, float headingRad, bool has)
        {
            Vector3 rel = has ? (pos - o.Position) : Vector3.zero;
            sensor.AddObservation(rel.x / PosScale);
            sensor.AddObservation(rel.z / PosScale);
            sensor.AddObservation(has ? Mathf.Sin(headingRad) : 0f);
            sensor.AddObservation(has ? Mathf.Cos(headingRad) : 0f);
            sensor.AddObservation(has ? 1f : 0f);
        }

        private static void AddRelOrZero(VectorSensor sensor, in RobotObservation o, Vector3 worldPos, bool valid)
        {
            Vector3 rel = valid ? (worldPos - o.Position) : Vector3.zero;
            sensor.AddObservation(rel.x / PosScale);
            sensor.AddObservation(rel.z / PosScale);
        }

        private static void AddMate(VectorSensor sensor, in RobotObservation o, Vector3 pos, Vector3 vel, int held, bool has)
        {
            Vector3 rel = has ? (pos - o.Position) : Vector3.zero;
            sensor.AddObservation(rel.x / PosScale);
            sensor.AddObservation(rel.z / PosScale);
            sensor.AddObservation(has ? vel.x / SpeedScale : 0f);
            sensor.AddObservation(has ? vel.z / SpeedScale : 0f);
            sensor.AddObservation(has ? held / (float)FieldConstants.RobotBallCapacity : 0f);
            sensor.AddObservation(has ? 1f : 0f);
        }

        public override void OnActionReceived(ActionBuffers actions)
        {
            var c = actions.ContinuousActions;
            var d = actions.DiscreteActions;

            _latest = new DriveCommand
            {
                Forward = c.Length > 0 ? Mathf.Clamp(c[0], -1f, 1f) : 0f,
                Turn = c.Length > 1 ? Mathf.Clamp(c[1], -1f, 1f) : 0f,
                Intake = d.Length > 0 && d[0] == 1,
                Shoot = d.Length > 1 && d[1] == 1,
                Climb = d.Length > 2 && d[2] == 1
            };
        }

        public override void Heuristic(in ActionBuffers actionsOut)
        {
            // 学習・推論が無い時はルールベースNPCで動かす（自己対戦の相手やデモに使える）。
            var o = observationProvider != null ? observationProvider() : default;
            DriveCommand cmd = _fallback.GetCommand(in o, Time.fixedDeltaTime);

            var c = actionsOut.ContinuousActions;
            if (c.Length > 0) c[0] = cmd.Forward;
            if (c.Length > 1) c[1] = cmd.Turn;
            var dd = actionsOut.DiscreteActions;
            if (dd.Length > 0) dd[0] = cmd.Intake ? 1 : 0;
            if (dd.Length > 1) dd[1] = cmd.Shoot ? 1 : 0;
            if (dd.Length > 2) dd[2] = cmd.Climb ? 1 : 0;
        }

        public override void OnEpisodeBegin()
        {
            _latest = DriveCommand.Zero;
            // ロボ/球のリセットは MatchManager 連携で Sim 側が実施する。
        }

        /// <summary>学習済みモデルを設定して推論モードにする（観戦用・2026-06-12）。
        /// BehaviorType=Default のままトレーナ未接続なら、Model が設定されていれば Inference Engine で推論する。</summary>
        public void SetModel(Unity.InferenceEngine.ModelAsset model)
        {
            var bp = GetComponent<BehaviorParameters>();
            if (bp == null) bp = gameObject.AddComponent<BehaviorParameters>();
            bp.Model = model;
        }

        /// <summary>外部（RewardShaper）から報酬を加算する公開API（PLAN.md §3.8）。</summary>
        public void AddShapedReward(float r) => AddReward(r);

        /// <summary>エピソード終了（試合終了）を通知。</summary>
        public void FinishEpisode() => EndEpisode();
    }
}
