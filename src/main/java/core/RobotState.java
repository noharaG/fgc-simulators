package core;

/**
 * ロボットの特定の時点における状態を表す不変（Immutable）クラス。
 * ボディ中心座標 (x, y) と車軸中心座標 (xA, yA) の双方を保持します。
 */
public class RobotState {
    public final double time;                 // 経過時間 (秒)
    public final String dateTime;             // 実時刻 (yyyy-MM-dd HH:mm:ss.SSS)
    
    // ボディ（機体）中心のグローバル座標
    public final double x;                    // ボディ中心 X座標 (m)
    public final double y;                    // ボディ中心 Y座標 (m)
    
    // 車軸中心のグローバル座標 (積分対象)
    public final double xA;                   // 車軸中心 X座標 (m)
    public final double yA;                   // 車軸中心 Y座標 (m)
    
    public final double heading;              // 向き (ラジアン, +x基準で反時計回り)
    public final double v;                    // 車軸中心の実並進速度 (m/s)
    public final double omega;                // 実角速度 (rad/s)
    public final double leftWheelSpeed;       // 実左車輪速度 (m/s)
    public final double rightWheelSpeed;      // 実右車輪速度 (m/s)
    public final double accelV;               // 実並進加速度 (m/s^2)
    public final double accelOmega;           // 実角加速度 (rad/s^2)
    public final double rawInputY;            // 生並進入力 (スティックY値)
    public final double rawInputX;            // 生旋回入力 (スティックX値)
    public final double cmdV;                 // 指令並進速度 (m/s)
    public final double cmdOmega;             // 指令角速度 (rad/s)
    public final boolean slipFlag;            // スリップ発生中か
    public final double slipAmount;           // スリップ量 (横滑り速度 v_slide の絶対値 m/s)
    public final double cumulativeDistance;   // 累積走行距離 (m)
    public final double deltaDistance;        // このステップの移動量 (m)
    public final boolean isStopped;           // 停止中判定 (速度が閾値以下)
    public final double cumulativeStopTime;   // 累積停止時間 (秒)
    public final boolean hitWallFlag;         // 壁接触フラグ [NEW]
    
    // 衝突関連のフィールド
    public final boolean collisionFlag;        // NPCとの衝突中フラグ
    public final int collisionNpcId;          // 衝突相手のNPC ID (なしなら -1)
    public final double collisionPointX;      // 衝突点のX座標 (m)
    public final double collisionPointY;      // 衝突点のY座標 (m)
    public final int cumulativeCollisionCount; // 累積衝突回数
    public final double cumulativeCollisionTime; // 累積衝突時間 (秒)

    // 障害物との衝突関連のフィールド
    public final boolean obstacleCollisionFlag;         // 障害物との衝突中フラグ
    public final String obstacleCollisionId;           // 衝突相手の障害物ID (なしなら null または "")
    public final double obstacleCollisionPointX;        // 衝突点のX座標 (m)
    public final double obstacleCollisionPointY;        // 衝突点のY座標 (m)
    public final int cumulativeObstacleCollisionCount;  // 累積障害物衝突回数
    public final double cumulativeObstacleCollisionTime;// 累積障害物衝突時間 (秒)

    // ボールとの衝突関連のフィールド
    public final boolean ballContactFlag;               // ボールとの衝突中フラグ (そのフレームで接触あり)
    public final int cumulativeBallContactCount;        // 累積ボール接触回数

    // ボール回収（インテーク）状態
    public final int heldBallCount;                     // 現在のボール保持数
    public final boolean intakeActive;                  // そのフレームでインテーク動作中か

    // ボール射出（シューター）状態
    public final int scoreCount;                        // 現在の累積得点数
    public final boolean shootActive;                   // そのフレームで射出動作中か
    public final int shotAttemptCount;                  // そのフレームでの累積射出試行数

    // 白ゴール（Fire Shield 斜辺）状態
    public final int whiteScoreCount;                   // 累積白得点（両アライアンス共通ボーナス）

    /**
     * すべてのフィールドを指定して状態を初期化します。
     */
    public RobotState(double time, String dateTime, double x, double y, double xA, double yA, double heading,
                      double v, double omega, double leftWheelSpeed, double rightWheelSpeed, double accelV, double accelOmega,
                      double rawInputY, double rawInputX, double cmdV, double cmdOmega,
                      boolean slipFlag, double slipAmount, double cumulativeDistance, double deltaDistance,
                      boolean isStopped, double cumulativeStopTime, boolean hitWallFlag,
                      boolean collisionFlag, int collisionNpcId, double collisionPointX, double collisionPointY,
                      int cumulativeCollisionCount, double cumulativeCollisionTime,
                      boolean obstacleCollisionFlag, String obstacleCollisionId, double obstacleCollisionPointX, double obstacleCollisionPointY,
                      int cumulativeObstacleCollisionCount, double cumulativeObstacleCollisionTime,
                      boolean ballContactFlag, int cumulativeBallContactCount,
                      int heldBallCount, boolean intakeActive,
                      int scoreCount, boolean shootActive, int shotAttemptCount,
                      int whiteScoreCount) {
        this.time = time;
        this.dateTime = dateTime;
        this.x = x;
        this.y = y;
        this.xA = xA;
        this.yA = yA;
        this.heading = heading;
        this.v = v;
        this.omega = omega;
        this.leftWheelSpeed = leftWheelSpeed;
        this.rightWheelSpeed = rightWheelSpeed;
        this.accelV = accelV;
        this.accelOmega = accelOmega;
        this.rawInputY = rawInputY;
        this.rawInputX = rawInputX;
        this.cmdV = cmdV;
        this.cmdOmega = cmdOmega;
        this.slipFlag = slipFlag;
        this.slipAmount = slipAmount;
        this.cumulativeDistance = cumulativeDistance;
        this.deltaDistance = deltaDistance;
        this.isStopped = isStopped;
        this.cumulativeStopTime = cumulativeStopTime;
        this.hitWallFlag = hitWallFlag;
        this.collisionFlag = collisionFlag;
        this.collisionNpcId = collisionNpcId;
        this.collisionPointX = collisionPointX;
        this.collisionPointY = collisionPointY;
        this.cumulativeCollisionCount = cumulativeCollisionCount;
        this.cumulativeCollisionTime = cumulativeCollisionTime;
        this.obstacleCollisionFlag = obstacleCollisionFlag;
        this.obstacleCollisionId = obstacleCollisionId;
        this.obstacleCollisionPointX = obstacleCollisionPointX;
        this.obstacleCollisionPointY = obstacleCollisionPointY;
        this.cumulativeObstacleCollisionCount = cumulativeObstacleCollisionCount;
        this.cumulativeObstacleCollisionTime = cumulativeObstacleCollisionTime;
        this.ballContactFlag = ballContactFlag;
        this.cumulativeBallContactCount = cumulativeBallContactCount;
        this.heldBallCount = heldBallCount;
        this.intakeActive = intakeActive;
        this.scoreCount = scoreCount;
        this.shootActive = shootActive;
        this.shotAttemptCount = shotAttemptCount;
        this.whiteScoreCount = whiteScoreCount;
    }

    /**
     * 初期位置での初期状態を生成します。
     */
    public static RobotState createInitial(double initX, double initY, double initHeading, double d, String dateTime) {
        // ボディ中心 (initX, initY) から車軸 A の初期座標を計算
        // G = A - d * (cos theta, sin theta)  =>  A = G + d * (cos theta, sin theta)
        double initXA = initX + d * Math.cos(initHeading);
        double initYA = initY + d * Math.sin(initHeading);

        return new RobotState(
            0.0, dateTime, initX, initY, initXA, initYA, initHeading,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0,
            false, 0.0, 0.0, 0.0,
            true, 0.0, false,
            false, -1, 0.0, 0.0, 0, 0.0,
            false, null, 0.0, 0.0, 0, 0.0,
            false, 0,
            0, false,
            0, false, 0,
            0
        );
    }
}
