using Unity.Burst;
using Unity.Entities;

namespace FGC.Sim.ECS
{
    /// <summary>
    /// 段階①「土台」のスモークテスト用。
    /// Entities / Physics / Burst パッケージと FGC.Sim.ECS アセンブリが正しく解決・コンパイルできることを
    /// 確認するための最小コンポーネント＆System。**実挙動には一切影響しない**（System は自動で無効化）。
    /// 段階②以降で本物の Ball/Robot System を追加する際、このファイルは削除して構わない。
    /// </summary>
    public struct EcsBootstrapMarker : IComponentData { }

    [BurstCompile]
    public partial struct EcsBootstrapSmokeSystem : ISystem
    {
        [BurstCompile]
        public void OnCreate(ref SystemState state)
        {
            // 何もしない土台。既存のGameObject/PhysX経路を一切変えないため無効化しておく。
            state.Enabled = false;
        }

        [BurstCompile]
        public void OnUpdate(ref SystemState state) { }
    }
}
