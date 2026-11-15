using FGC.Core;

namespace FGC.Robot
{
    /// <summary>
    /// ロボ制御の差し替え点（PLAN.md §3.2・最重要設計）。
    /// 6スロットそれぞれに Human / RuleBasedNPC / MLAgent のいずれかを実行時割り当て。
    /// 観測から1フレーム分の指令を返す純インターフェース。実装はSim/VR/ML層に置く。
    /// </summary>
    public interface IRobotController
    {
        /// <param name="obs">現在の観測</param>
        /// <param name="dt">経過時間[s]</param>
        /// <returns>このフレームの制御指令</returns>
        DriveCommand GetCommand(in RobotObservation obs, float dt);
    }
}
