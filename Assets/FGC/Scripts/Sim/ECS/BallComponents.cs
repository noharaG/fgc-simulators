using Unity.Entities;

namespace FGC.Sim.ECS
{
    /// <summary>
    /// WILDFIRE 球の ECS Entity 識別タグ（段階②）。
    /// 物理は Unity Physics 標準コンポーネント（PhysicsCollider/PhysicsVelocity/PhysicsMass…）で表現し、
    /// プール退避は Disabled（EntityManager.SetEnabled(false)）で行う。
    /// 段階④で StorageKind 等のゲーム用コンポーネントを足す予定。
    /// </summary>
    public struct BallTag : IComponentData { }

    /// <summary>ECS物理ワールド用の静的コライダ（床・外周壁）識別タグ。フィールド本体の移行は段階④/⑧。</summary>
    public struct SimStaticColliderTag : IComponentData { }

    /// <summary>
    /// 段階③: ロボの ECS Entity 識別タグ。球と同じ Unity Physics ワールドに動的剛体として置き、
    /// 球↔ロボの押し出し/堆積干渉を復活させる。駆動(目標v,ω)は EcsRobotSim が PhysicsVelocity 直書きで
    /// 与える（旧 RobotActor.ApplyDrive と同値のアーケード速度制御）。機構は段階④までGameObject側に残置。
    /// </summary>
    public struct SimRobotTag : IComponentData { }
}
