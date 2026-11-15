package core;

import java.util.Random;

/**
 * 3v3対戦モードにおける各ロボットの設定および物理状態を保持するクラス。
 */
public class CompetitionRobot {

    public enum Alliance {
        RED, BLUE
    }

    public enum SpawnPosition {
        UP, MID, DOWN
    }

    public enum DriveType {
        DIFF, OMNI
    }

    public enum BehaviorType {
        SHOOT, WHITE
    }

    /**
     * NPC（敵味方共通エンジン）の難易度プリセット。
     * 反応速度・予測精度・シュート精度・ミス率をパラメータ化する。
     * デフォルトは STRONGEST（最強）。STRONGEST のパラメータは従来挙動と等価
     * （倍率1.0・追加ミス無し）であり、既存テストの想定を変えない。
     */
    public enum AiDifficulty {
        // reactionInterval: 意思決定間隔(秒, 小さいほど反応速い)
        // predictionAccuracy: 軌道予測リード採用率(0〜1, 1で完全先読み)
        // shootAccuracyMult: シュート実効成功率の倍率
        // missRateAdd: 行動に上乗せするランダムな揺らぎ(0で無し)
        STRONGEST(0.15, 1.00, 1.00, 0.00),
        STRONG   (0.22, 0.80, 0.90, 0.05),
        MEDIUM   (0.35, 0.55, 0.75, 0.15);

        public final double reactionInterval;
        public final double predictionAccuracy;
        public final double shootAccuracyMult;
        public final double missRateAdd;

        AiDifficulty(double reactionInterval, double predictionAccuracy,
                     double shootAccuracyMult, double missRateAdd) {
            this.reactionInterval = reactionInterval;
            this.predictionAccuracy = predictionAccuracy;
            this.shootAccuracyMult = shootAccuracyMult;
            this.missRateAdd = missRateAdd;
        }
    }

    // --- ロボット設定 ---
    private final int id;
    private final String name;
    private Alliance alliance;
    private final boolean isPlayer;
    private SpawnPosition spawnPosition;
    private DriveType driveType;
    private BehaviorType behaviorType;

    // 対戦パラメータ
    private int intakeCapacity;
    private double shootSuccessProb;
    private double shootRange;
    private double shootBPS;
    private double disturbWeight;

    // 詳細機体スペック
    private RobotConfig config;

    // --- 物理状態 ---
    public double x;             // ボディ中心 X (m)
    public double y;             // ボディ中心 Y (m)
    public double xA;            // 車軸中心 X (m)
    public double yA;            // 車軸中心 Y (m)
    public double heading;       // 向き (rad)
    public double v;             // 前後並進速度 (m/s)
    public double vy;            // 左右並進速度 (m/s, OMNI用)
    public double omega;         // 角速度 (rad/s)

    // フラグ・統計
    public boolean collisionFlag = false;
    public boolean hitWallFlag = false;
    public boolean obstacleCollisionFlag = false;
    public String obstacleCollisionId = null;

    // ゲーム状態
    public int heldBallCount = 0;
    public boolean intakeActive = false;
    public boolean ejectActive = false;
    public boolean shootActive = false;
    public int scoreCount = 0;
    public int shotAttemptCount = 0;
    public int whiteScoreCount = 0;

    // シューター制限タイマー
    public double shootTimer = 0.0;

    // 住み分け用（前フレームの選択行動）
    public String lastChosenAction = "WANDER";
    public double interfereWeight = 0.5;  // 妨害重み (0=得点専念, 1=妨害重視)
    public int interfereTargetId = -1;    // 現在の妨害標的ID (-1=なし)

    // 膠着検知用
    public double stallCheckX = Double.NaN;
    public double stallCheckY = Double.NaN;
    public double stallCheckTimer = 0.0;
    public double stallRecoveryTimer = 0.0;
    public double stallRecoveryAngle = 0.0;
    // 「目標に到達したが行動できず固まる」デッドロック検知用（速度0かつ非生産状態の継続時間）
    public double idleStuckTimer = 0.0;

    // シュートコミット（撃ち始めたら保持0まで撃ち切る）
    public boolean shootCommitActive = false;
    public double shootCommitStuckTimer = 0.0;
    public double shootCommitTurnTimer = 0.0;
    public double shootCommitTargetX = Double.NaN;
    public double shootCommitTargetY = Double.NaN;
    public double shootCommitTargetHeading = Double.NaN;
    public double shootCommitGoalPosX = Double.NaN;
    public double shootCommitGoalPosY = Double.NaN;
    public double shootCommitIdealTargetX = Double.NaN;
    public double shootCommitIdealTargetY = Double.NaN;
    public double shootCommitIdealTargetHeading = Double.NaN;

    // 角度制御用の前フレーム角度偏差
    public double prevAngleError = Double.NaN;

    // 回収時に現在ターゲットにしているボールのID
    public int collectTargetBallId = -1;

    // チーム全体最適化（貪欲＋調整割当）で割り当てられたボールID（-1で未割当）。
    // 効用評価でこのボールに割当ボーナスを与え、群がりを防ぎつつ得点期待値合計を最大化する。
    public int assignedBallId = -1;

    // 経路計画ウェイポイント（1点、NaNで無効）
    public double pathWaypointX = Double.NaN;
    public double pathWaypointY = Double.NaN;

    // --- AI難易度（敵味方共通エンジンの色違い、デフォルト最強） ---
    private AiDifficulty aiDifficulty = AiDifficulty.STRONGEST;

    public AiDifficulty getAiDifficulty() { return aiDifficulty; }
    public void setAiDifficulty(AiDifficulty d) {
        if (d != null) {
            this.aiDifficulty = d;
            this.decisionInterval = d.reactionInterval;
        }
    }

    // --- 意思決定間引きとキャッシュ ---
    public double decisionInterval = 0.15; // 間隔 (秒)
    public double decisionTimer = 0.0;     // タイマー (秒)
    // 初回意思決定後にワンタイムで加算する位相スタッガ (秒)。
    // 全NPCが初回tickで意思決定する互換性を保ちつつ、2回目以降の再評価tickをずらす。
    public double initialDecisionStagger = 0.0;
    
    public double cachedTargetX = Double.NaN;
    public double cachedTargetY = Double.NaN;
    public double cachedTargetHeading = Double.NaN;
    public String cachedChosenAction = "WANDER";
    public double cachedGoalPosX = Double.NaN;
    public double cachedGoalPosY = Double.NaN;
    public int cachedNewInterfereTargetId = -1;
    public int cachedBestCollectBallId = -1;
    public boolean cachedCanHitGoal = false;

    // --- 白排出引っかかり回避 ---
    public double ejectStuckTimer = 0.0;
    public int ejectRetryCount = 0;
    public double ejectBackupTimer = 0.0;

    // 連続膠着カウント（STALL_CHECK_INTERVAL ごとにインクリメント、進めたらリセット）
    public int stallCount = 0;

    // --- 前回意思決定時の状態スナップショット（状態急変による即時再評価判定用） ---
    public int lastEvalHeldBallCount = Integer.MIN_VALUE;
    public BehaviorType lastEvalBehaviorType = null;
    public double lastEvalX = Double.NaN;
    public double lastEvalY = Double.NaN;
    public int lastEvalCollectTargetBallId = -1;
    public boolean lastEvalShootCommitActive = false;
    public double lastEvalShootRange = Double.NaN;
    public boolean lastEvalEvaluated = false;

    // 一時デバッグ用
    public double dbgCmdV = Double.NaN;
    public double dbgAbsAngle = Double.NaN;
    public double dbgDistToTarget = Double.NaN;
    public boolean dbgDoBackup = false;
    public boolean dbgInRecovery = false;
    public double dbgRecvCmdV = Double.NaN;
    public boolean dbgFriction = false;
    public double dbgVAfter = Double.NaN;

    // NPC動的挙動制御用
    private practice.RobotBehavior behavior;
    private double behaviorTimer = 0.0;
    private final Random random;

    public CompetitionRobot(int id, String name, Alliance alliance, boolean isPlayer, SpawnPosition spawnPosition, DriveType driveType) {
        this.id = id;
        this.name = name;
        this.alliance = alliance;
        this.isPlayer = isPlayer;
        this.spawnPosition = spawnPosition;
        this.driveType = driveType;
        this.behaviorType = BehaviorType.SHOOT;

        // デフォルト設定の割り当て
        this.config = new RobotConfig();
        this.intakeCapacity = config.intakeCapacity;
        this.shootSuccessProb = config.shootSuccessProb;
        this.shootRange = config.shootRange;
        this.shootBPS = config.shootBPS;
        this.disturbWeight = 0.5;

        this.random = new Random(id * 12345L);
        this.behaviorTimer = 1.0 + random.nextDouble() * 4.0;
    }

    /**
     * ロボットの物理状態を1ステップ積分します。
     *
     * @param cmdV 目標前後速度 (m/s)
     * @param cmdVy 目標左右速度 (m/s, OMNI用)
     * @param cmdOmega 目標角速度 (rad/s)
     * @param dt タイムステップ (秒)
     */
    public void integrate(double cmdV, double cmdVy, double cmdOmega, double dt) {
        // 0. 車軸ポーズ(xA,yA)をボディ中心(x,y)と整合させる。
        //    外部から x,y を直接設定した場合（テストや配置リセット等）に
        //    xA が古い値のまま残ると、DIFF積分で x が車軸基準に巻き戻ってワープしてしまう。
        //    ここで毎ステップ x,y を真値として xA を再導出し、整合を保証する。
        {
            double cosH0 = Math.cos(heading);
            double sinH0 = Math.sin(heading);
            if (driveType == DriveType.OMNI) {
                xA = x;
                yA = y;
            } else {
                xA = x + config.pivotOffsetD * cosH0;
                yA = y + config.pivotOffsetD * sinH0;
            }
        }

        // 1. 駆動方式に応じた加速度制限と自然減速
        double targetV = cmdV;
        double targetVy = (driveType == DriveType.OMNI) ? cmdVy : 0.0;
        double targetOmega = cmdOmega;
        dbgRecvCmdV = cmdV; // 一時デバッグ: integrate受領cmdV
        dbgFriction = (targetV == 0.0 && targetVy == 0.0 && targetOmega == 0.0); // frictionブランチか

        // 自然摩擦減衰（無入力時）
        if (targetV == 0.0 && targetVy == 0.0 && targetOmega == 0.0) {
            double frictionDecel = config.frictionAccel;
            if (Math.abs(v) < frictionDecel * dt) v = 0.0;
            else v -= Math.signum(v) * frictionDecel * dt;

            if (driveType == DriveType.OMNI) {
                if (Math.abs(vy) < frictionDecel * dt) vy = 0.0;
                else vy -= Math.signum(vy) * frictionDecel * dt;
            }

            double frictionDecelOmega = config.frictionAccel * 2.0;
            if (Math.abs(omega) < frictionDecelOmega * dt) omega = 0.0;
            else omega -= Math.signum(omega) * frictionDecelOmega * dt;
        } else {
            // 加速度制限の適用
            double deltaV = targetV - v;
            double maxDeltaV = config.maxAccel * dt;
            v += Math.max(-maxDeltaV, Math.min(maxDeltaV, deltaV));

            if (driveType == DriveType.OMNI) {
                double deltaVy = targetVy - vy;
                double maxDeltaVy = config.maxAccel * dt;
                vy += Math.max(-maxDeltaVy, Math.min(maxDeltaVy, deltaVy));
            } else {
                vy = 0.0;
            }

            double deltaOmega = targetOmega - omega;
            double maxDeltaOmega = config.maxAlpha * dt;
            omega += Math.max(-maxDeltaOmega, Math.min(maxDeltaOmega, deltaOmega));
        }
        dbgDistToTarget = v; // 一時デバッグ: 加速度処理直後の v

        // 2. 向きの積分
        heading += omega * dt;
        heading = normalizeAngle(heading);

        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);

        // 3. 座標の積分
        if (driveType == DriveType.OMNI) {
            // 全方位：車軸ポーズと幾何中心は一致 (d = 0)
            double vxGlobal = v * cosH - vy * sinH;
            double vyGlobal = v * sinH + vy * cosH;

            x += vxGlobal * dt;
            y += vyGlobal * dt;

            xA = x;
            yA = y;
        } else {
            // 差動2輪：車軸ポーズを基準に積分し、ボディ中心をオフセット算出
            double xADot = v * cosH;
            double yADot = v * sinH;

            xA += xADot * dt;
            yA += yADot * dt;

            x = xA - config.pivotOffsetD * cosH;
            y = yA - config.pivotOffsetD * sinH;
        }

        // 4. 壁クランプ処理
        clampToFieldBoundaries();
        dbgVAfter = v;
    }

    /**
     * フィールド外縁との接触判定と位置補正（壁クランプ）
     */
    public void clampToFieldBoundaries() {
        double robotRadius = config.robotSize / 2.0;
        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        // OBBのバウンディングボックスサイズを簡易計算 (または詳細OBB)
        double half = robotRadius * (Math.abs(cosH) + Math.abs(sinH));

        double origX = this.x;
        double origY = this.y;

        // X軸クランプ
        double minX = half;
        double maxX = 7.0 - half;
        if (this.x < minX) this.x = minX;
        else if (this.x > maxX) this.x = maxX;

        // Y軸クランプ
        double minY = half;
        double maxY = 7.0 - half;
        if (this.y < minY) this.y = minY;
        else if (this.y > maxY) this.y = maxY;

        double dx = this.x - origX;
        double dy = this.y - origY;

        if (Math.hypot(dx, dy) > 1e-6) {
            // 壁に衝突して補正されたので速度を制限
            clampVelocity(dx, dy);

            // 車軸位置を再計算
            if (driveType == DriveType.OMNI) {
                xA = x;
                yA = y;
            } else {
                xA = x + config.pivotOffsetD * cosH;
                yA = y + config.pivotOffsetD * sinH;
            }
        }
    }

    /**
     * 補正ベクトル(dx, dy)に基づいて速度成分を制限します。
     * めり込み方向の速度をゼロにし、壁に沿ったスライドのみを許可します。
     */
    public void clampVelocity(double dx, double dy) {
        double len = Math.hypot(dx, dy);
        if (len > 1e-6) {
            double nx = dx / len;
            double ny = dy / len;

            double cosH = Math.cos(heading);
            double sinH = Math.sin(heading);

            // グローバル速度
            double vxGlobal = v * cosH - vy * sinH;
            double vyGlobal = v * sinH + vy * cosH;

            double dot = vxGlobal * nx + vyGlobal * ny;
            if (dot < 0.0) {
                // 壁に突き刺さる成分をキャンセル
                vxGlobal -= dot * nx;
                vyGlobal -= dot * ny;

                // ローカル速度に逆変換
                v = vxGlobal * cosH + vyGlobal * sinH;
                vy = -vxGlobal * sinH + vyGlobal * cosH;
            }
        }
    }

    /**
     * 外部からの強制的な位置補正処理
     */
    public void overridePosition(double correctedX, double correctedY, double dx, double dy) {
        this.x = correctedX;
        this.y = correctedY;

        clampVelocity(dx, dy);

        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        if (driveType == DriveType.OMNI) {
            xA = x;
            yA = y;
        } else {
            xA = x + config.pivotOffsetD * cosH;
            yA = y + config.pivotOffsetD * sinH;
        }

        clampToFieldBoundaries();
    }

    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    // --- ゲッターとセッター ---
    public int getId() { return id; }
    public String getName() { return name; }
    public Alliance getAlliance() { return alliance; }
    public void setAlliance(Alliance alliance) { this.alliance = alliance; }
    public boolean isPlayer() { return isPlayer; }
    public SpawnPosition getSpawnPosition() { return spawnPosition; }
    public void setSpawnPosition(SpawnPosition pos) { this.spawnPosition = pos; }
    public DriveType getDriveType() { return driveType; }
    public void setDriveType(DriveType type) { this.driveType = type; }
    public BehaviorType getBehaviorType() { return behaviorType; }
    public void setBehaviorType(BehaviorType type) { this.behaviorType = type; }

    public int getIntakeCapacity() { return intakeCapacity; }
    public void setIntakeCapacity(int cap) { this.intakeCapacity = cap; }
    public double getShootSuccessProb() { return shootSuccessProb; }
    public void setShootSuccessProb(double prob) { this.shootSuccessProb = prob; }
    public double getShootRange() { return shootRange; }
    public void setShootRange(double range) { this.shootRange = range; }
    public double getShootBPS() { return shootBPS; }
    public void setShootBPS(double bps) { this.shootBPS = bps; }
    public double getDisturbWeight() { return disturbWeight; }
    public void setDisturbWeight(double weight) { this.disturbWeight = weight; }

    public RobotConfig getConfig() { return config; }
    public void setConfig(RobotConfig config) { this.config = config; }

    public practice.RobotBehavior getBehavior() { return behavior; }
    public void setBehavior(practice.RobotBehavior behavior) { this.behavior = behavior; }
    public double getBehaviorTimer() { return behaviorTimer; }
    public void setBehaviorTimer(double timer) { this.behaviorTimer = timer; }
    public Random getRandom() { return random; }

    /**
     * ロボットの OBB 情報を生成して返します。
     */
    public RobotOBB getOBB() {
        double halfSize = config.robotSize / 2.0;
        // グローバル座標系での速度 vx, vy を計算
        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        double vxGlobal = v * cosH - vy * sinH;
        double vyGlobal = v * sinH + vy * cosH;
        return new RobotOBB(x, y, heading, halfSize, vxGlobal, vyGlobal);
    }
}
