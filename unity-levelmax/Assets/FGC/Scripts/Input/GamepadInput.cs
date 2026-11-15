using FGC.Core;
using UnityEngine;
using UnityEngine.InputSystem;

namespace FGC.Input
{
    /// <summary>
    /// REV USB PS4 Compatible Gamepad（実機同等）から DriveCommand を生成する。
    /// マッピングは既存Java版 GamepadInputAdapter を踏襲（参照専用元より移植）:
    ///   並進 = 左スティックY / 旋回 = 右スティックX / 回収=LT(>0.5) / シュート=RT(>0.5) / 排出=□(west)。
    /// ※Unity Input System は上方向が +Y のため、SDL版の符号反転は不要。
    /// </summary>
    public static class GamepadInput
    {
        /// <summary>現在接続中のゲームパッドから1フレーム分の指令を読む。未接続なら Zero。</summary>
        public static DriveCommand Read()
        {
            var gp = Gamepad.current;
            if (gp == null) return DriveCommand.Zero;

            float forward = gp.leftStick.y.ReadValue();   // 上=前進(+)
            float turn = gp.rightStick.x.ReadValue();     // 右=右旋回(+)（符号は統合時に要確認）

            bool intake = gp.leftTrigger.ReadValue() > 0.5f;   // LT
            bool shoot = gp.rightTrigger.ReadValue() > 0.5f;   // RT
            bool climb = gp.buttonNorth.isPressed;             // △/Y を登攀に割当（暫定）

            return new DriveCommand
            {
                Forward = forward,
                Turn = turn,
                Intake = intake,
                Shoot = shoot,
                Climb = climb
            };
        }

        public static bool IsConnected => Gamepad.current != null;
    }
}
