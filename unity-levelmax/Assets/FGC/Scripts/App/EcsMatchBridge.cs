using FGC.Core;
using FGC.Sim.ECS;
using Unity.Entities;

namespace FGC.App
{
    /// <summary>
    /// 段階⑤: GameObject 側（MatchDirector/HUD）から ECS の試合シングルトン（MatchStateData/ScoreData）を
    /// 読み書きするブリッジ。EntityManager のシングルトンアクセスを1か所へ集約し、API誤りの面を最小化する。
    /// System(MatchSystem) がシングルトンを生成・タイマー進行を担い、ここは「開始/リセット指示」と「読取/スコア公開」だけ行う。
    /// </summary>
    internal static class EcsMatchBridge
    {
        private static World CurrentWorld => World.DefaultGameObjectInjectionWorld;

        /// <summary>MatchStateData シングルトンEntityを取得（無ければ false）。</summary>
        private static bool TryEntity(out EntityManager em, out Entity e)
        {
            em = default; e = Entity.Null;
            var world = CurrentWorld;
            if (world == null || !world.IsCreated) return false;
            em = world.EntityManager;
            var q = em.CreateEntityQuery(typeof(MatchStateData));
            bool ok = q.CalculateEntityCount() == 1;
            if (ok) e = q.GetSingletonEntity();
            q.Dispose();
            return ok;
        }

        /// <summary>試合開始：タイマーを0から走らせる（MatchManager.StartMatch 同値）。</summary>
        public static void StartMatch()
        {
            if (!TryEntity(out var em, out var e)) return;
            var s = em.GetComponentData<MatchStateData>(e);
            s.Elapsed = 0f;
            s.Remaining = FieldConstants.MatchDuration;
            s.Phase = (int)MatchPhase.Running;
            s.Running = 1;
            em.SetComponentData(e, s);
        }

        /// <summary>準備フェーズへ戻す（MatchManager.Reset 同値）。</summary>
        public static void ResetMatch()
        {
            if (!TryEntity(out var em, out var e)) return;
            var s = em.GetComponentData<MatchStateData>(e);
            s.Elapsed = 0f;
            s.Remaining = FieldConstants.MatchDuration;
            s.Phase = (int)MatchPhase.PreMatch;
            s.Running = 0;
            em.SetComponentData(e, s);
        }

        /// <summary>現在の試合状態を読む。</summary>
        public static bool TryGetState(out MatchStateData state)
        {
            state = default;
            if (!TryEntity(out var em, out var e)) return false;
            state = em.GetComponentData<MatchStateData>(e);
            return true;
        }

        /// <summary>ScoreBoard が算出したスコアを ECS シングルトンへ公開する。</summary>
        public static void PublishScore(int red, int blue, int coop)
        {
            if (!TryEntity(out var em, out var e)) return;
            if (!em.HasComponent<ScoreData>(e)) return;
            em.SetComponentData(e, new ScoreData { RedRegional = red, BlueRegional = blue, Coopertition = coop });
        }
    }
}
