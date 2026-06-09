using FGC.Core;
using FGC.Input;

namespace FGC.Robot
{
    /// <summary>
    /// 人間操縦（ゲームパッド）。6スロットのうち人間担当スロットに割り当てる（PLAN.md §3.2）。
    /// 入力は GamepadInput（REV PS4互換）から取得。観測は使わず生入力をそのまま指令化。
    /// </summary>
    public sealed class HumanController : IRobotController
    {
        public DriveCommand GetCommand(in RobotObservation obs, float dt)
        {
            return GamepadInput.Read();
        }
    }
}
