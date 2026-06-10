// ML-Agents 無効時は本ファイル全体を除外（FGC_MLAGENTS 定義時のみ有効）。
#if FGC_MLAGENTS
using System.Collections.Generic;
using FGC.Ball;
using FGC.Core;
using FGC.ML;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// ML報酬の整形（PLAN.md §3.8・次フェーズ⑥）。Sim のイベント／状態から MLAgentController へ AddReward。
    ///   ・格納+1相当（自リージョンSUPPRESSION）/ EXTINGUISHER寄与（GLOBAL）
    ///   ・登攀ゾーン上昇ボーナス（Contact→Z1→Z2→Z3）
    ///   ・時間整形（毎フレーム微小ペナルティで素早い得点を促す）
    /// 学習に使う MLAgentController を持つロボにのみ適用（人間/ルールベースには無害）。
    /// </summary>
    public sealed class RewardShaper : MonoBehaviour
    {
        public MatchDirector director;

        [Header("報酬係数")]
        public float suppressionReward = 1.0f;     // 自リージョンSUPPRESSION格納
        public float extinguisherReward = 0.5f;    // EXTINGUISHER格納（GLOBAL）
        public float climbStepReward = 0.3f;       // 1ゾーン上昇あたり
        public float timePenaltyPerSec = 0.01f;    // 毎秒の微小ペナルティ
        // 中間報酬（fgc_run10対策）: 「球に近づく→Intake→運ぶ→格納」の行動チェーンは終端(格納+1)しか
        // 報酬が無く、ランダム探索では学習の足がかりを掴めずELO/報酬が頭打ちになった。
        // 最初のリンク「球を拾う」に密な報酬を与えて段階学習を可能にする（格納1.0より小さく保つ）。
        public float intakeReward = 0.1f;          // 球を1個拾う（HeldBalls増加）あたり

        // ★ファーミング対策(2026-06-10): 登攀は「エピソード内の最高到達ゾーン」を超えた時だけ報酬。
        // 旧実装(直前ゾーンとの差分)はブレース接触→離脱(猶予1s)→再接触の往復で+0.3が無限に稼げた
        // (1試合で+20超=得点報酬を圧倒するハック)。OnMatchReset でエピソード毎にクリアする。
        private readonly Dictionary<RobotActor, ClimbZone> _bestZone = new Dictionary<RobotActor, ClimbZone>();
        private readonly Dictionary<RobotActor, int> _lastHeld = new Dictionary<RobotActor, int>();

        /// <summary>試合リセット時に MatchDirector から呼ぶ：エピソード単位の報酬状態をクリアする。</summary>
        public void OnMatchReset()
        {
            _bestZone.Clear();
            _lastHeld.Clear();
        }

        /// <summary>MatchDirector から格納ゾーン（旧GameObjectトリガ）を受け取り購読する。
        /// 段階④以降の既定（useEcsMechanics ON）では StorageTrigger は無効化されるため、
        /// ECS機構側の格納イベントは BindEcs で別途購読する必要がある（両方購読しても二重発火しない＝排他的に有効）。</summary>
        public void Bind(IEnumerable<StorageTrigger> triggers)
        {
            if (triggers == null) return;
            foreach (var t in triggers)
                if (t != null) t.OnDeposited += HandleDeposit;
        }

        /// <summary>ECS判定機構(EcsMechanics)の格納イベントを購読する。ECSモード学習で**主報酬(格納)を確実に発火**させる。
        /// StorageTrigger と同一シグネチャ・同一ハンドラを使う。ECSモードでは StorageTrigger 無効＝こちらだけが発火する。</summary>
        public void BindEcs(EcsMechanics ecs)
        {
            if (ecs != null) ecs.OnDeposited += HandleDeposit;
        }

        private void HandleDeposit(RobotActor actor, StorageKind kind)
        {
            var ml = actor.GetComponent<MLAgentController>();
            if (ml == null) return;

            switch (kind)
            {
                case StorageKind.Extinguisher:
                    ml.AddShapedReward(extinguisherReward);
                    break;
                case StorageKind.SuppressionRed:
                case StorageKind.SuppressionBlue:
                    ml.AddShapedReward(suppressionReward); // 同盟一致はStorageTrigger側で担保済
                    break;
            }
        }

        private void Update()
        {
            if (director == null) return;
            float dt = Time.deltaTime;

            foreach (var actor in director.Robots)
            {
                var ml = actor.GetComponent<MLAgentController>();
                if (ml == null) continue;

                // 時間整形
                ml.AddShapedReward(-timePenaltyPerSec * dt);

                // 登攀ゾーン上昇ボーナス（エピソード内の最高到達を超えた分のみ＝接触往復ファーミング不可）
                _bestZone.TryGetValue(actor, out var best);
                if ((int)actor.ClimbZone > (int)best)
                {
                    int steps = (int)actor.ClimbZone - (int)best;
                    ml.AddShapedReward(climbStepReward * steps);
                    _bestZone[actor] = actor.ClimbZone;
                }

                // 球ピックアップ報酬: HeldBalls の増加分のみ（格納/シュートでの減少や試合リセットの0化は対象外）
                _lastHeld.TryGetValue(actor, out int prevHeld);
                if (actor.HeldBalls > prevHeld)
                    ml.AddShapedReward(intakeReward * (actor.HeldBalls - prevHeld));
                _lastHeld[actor] = actor.HeldBalls;
            }
        }
    }
}
#endif
