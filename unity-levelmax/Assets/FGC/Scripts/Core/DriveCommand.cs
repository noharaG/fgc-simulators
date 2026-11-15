namespace FGC.Core
{
    /// <summary>
    /// IRobotController が毎フレーム返す制御指令。Sim層（RobotActor）が剛体に適用する。
    /// 並進/旋回は正規化値 [-1,1]。機構アクションは離散トリガ。
    /// </summary>
    public struct DriveCommand
    {
        public float Forward;   // [-1,1] 前後（差動2輪の並進目標）
        public float Turn;      // [-1,1] 旋回（左右）
        public bool Intake;     // インテーク作動
        public bool Shoot;      // シュート（白ゴール/格納へ）
        public bool Climb;      // 登攀（BRACE）アクション

        public static DriveCommand Zero => new DriveCommand
        {
            Forward = 0f, Turn = 0f, Intake = false, Shoot = false, Climb = false
        };

        public DriveCommand Clamped()
        {
            return new DriveCommand
            {
                Forward = Clamp01Signed(Forward),
                Turn = Clamp01Signed(Turn),
                Intake = Intake,
                Shoot = Shoot,
                Climb = Climb
            };
        }

        private static float Clamp01Signed(float v)
        {
            if (v < -1f) return -1f;
            if (v > 1f) return 1f;
            return v;
        }
    }
}
