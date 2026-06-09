using FGC.Core;
using Unity.Entities;

namespace FGC.Sim.ECS
{
    /// <summary>
    /// 段階⑤: 試合タイマー/フェーズを進める ECS System（旧 FGC.Core.MatchManager.Tick の System 版）。
    /// MatchStateData シングルトンを唯一の真実源として毎フレーム更新する。論理は MatchManager と同値：
    /// Running 中のみ Elapsed を加算 → 残5sで Settling → MatchDuration で Finished＋Running停止。
    ///
    /// 開始/リセットは GameObject 側（MatchDirector→EcsMatchBridge）が Running/Phase を書き換えて指示する。
    /// 自動生成・自動更新（[DisableAutoCreation] なし）。useEcsMatch がOFFのときは MatchDirector が Running を立てないので
    /// このSystemは何もしない（singletonは存在するが進まない）＝旧MatchManager運用と無害共存。
    /// </summary>
    public partial class MatchSystem : SystemBase
    {
        protected override void OnCreate()
        {
            // シングルトンが無ければ作る（MatchStateData と ScoreData を同一Entityに同居）。
            // OnCreate では SystemAPI ではなく EntityManager/GetEntityQuery を使う（確実）。
            var q = GetEntityQuery(typeof(MatchStateData));
            if (q.CalculateEntityCount() == 0)
            {
                Entity e = EntityManager.CreateEntity(typeof(MatchStateData), typeof(ScoreData));
                EntityManager.SetComponentData(e, new MatchStateData
                {
                    Phase = (int)MatchPhase.PreMatch,
                    Elapsed = 0f,
                    Remaining = FieldConstants.MatchDuration,
                    Running = 0
                });
                EntityManager.SetComponentData(e, new ScoreData());
            }
        }

        protected override void OnUpdate()
        {
            var s = SystemAPI.GetSingleton<MatchStateData>();
            if (s.Running == 0) return; // 準備中/終了後はタイマー停止

            float dt = SystemAPI.Time.DeltaTime;
            s.Elapsed += dt;

            float remaining = FieldConstants.MatchDuration - s.Elapsed;
            s.Remaining = remaining < 0f ? 0f : remaining;

            if (s.Phase == (int)MatchPhase.Running && s.Remaining <= FieldConstants.SettleTime)
                s.Phase = (int)MatchPhase.Settling;

            if (s.Elapsed >= FieldConstants.MatchDuration)
            {
                s.Elapsed = FieldConstants.MatchDuration;
                s.Remaining = 0f;
                s.Phase = (int)MatchPhase.Finished;
                s.Running = 0;
            }

            SystemAPI.SetSingleton(s);
        }
    }
}
