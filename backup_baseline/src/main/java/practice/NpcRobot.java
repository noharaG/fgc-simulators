package practice;

import core.RobotConfig;
import java.util.Locale;
import java.util.Random;

/**
 * 練習モードにおけるNPCロボットの物理状態（位置、速度、向き）を保持し、更新を行うクラス。
 * プレイヤーと同様の差動2輪運動学およびクランプ判定を用いますが、スリップは適用せず簡易的な積分を行います。
 */
public class NpcRobot {
    private final int id;
    private final RobotConfig config;
    private RobotBehavior behavior; // 可変に変更

    // 可変の物理状態
    private double xA;       // 車軸中心 X座標 (m)
    private double yA;       // 車軸中心 Y座標 (m)
    private double heading;  // 向き (ラジアン)
    private double v;        // 実並進速度 (m/s)
    private double omega;    // 実角速度 (rad/s)

    // ボディ中心
    private double x;        // ボディ中心 X座標 (m)
    private double y;        // ボディ中心 Y座標 (m)

    // 挙動決定と物理積分の分離用一時変数
    private DriveCommand pendingCmd = null;

    // 動的挙動切替用メンバ変数
    private final Random random;
    private double behaviorTimer;

    // スリップ有効化の拡張用フラグ
    private final boolean slipEnabled = false;

    /**
     * 後方互換用のコンストラクタ
     */
    public NpcRobot(int id, double initX, double initY, double initHeading, RobotConfig config, RobotBehavior behavior) {
        this(id, initX, initY, initHeading, config, behavior, id * 1000L);
    }

    /**
     * @param id NPCの一意のID
     * @param initX 初期X座標 (m)
     * @param initY 初期Y座標 (m)
     * @param initHeading 初期向き (ラジアン)
     * @param config ロボット設定
     * @param behavior 割り当てられた挙動
     * @param seed NPC固有の乱数シード
     */
    public NpcRobot(int id, double initX, double initY, double initHeading, RobotConfig config, RobotBehavior behavior, long seed) {
        this.id = id;
        this.config = config;
        this.behavior = behavior;
        this.heading = initHeading;
        this.random = new Random(seed);
        // 初期の挙動タイマーを 1.0 〜 5.0 秒でランダムに設定
        this.behaviorTimer = 1.0 + random.nextDouble() * 4.0;
        
        // ボディ中心 (initX, initY) から車軸 A の初期座標を計算
        // G = A - d * (cos theta, sin theta) => A = G + d * (cos theta, sin theta)
        this.xA = initX + config.pivotOffsetD * Math.cos(initHeading);
        this.yA = initY + config.pivotOffsetD * Math.sin(initHeading);
        this.x = initX;
        this.y = initY;
        this.v = 0.0;
        this.omega = 0.0;
    }

    /**
     * 次のステップの目標速度指令を決定します（運動積分前）。
     *
     * @param engine シミュレーションエンジン（コメントログ出力用）
     * @param ctx 更新コンテキスト
     * @param dt タイムステップ (秒)
     */
    public void planBehavior(core.SimulationEngine engine, PracticeContext ctx, double dt) {
        this.behaviorTimer -= dt;
        if (this.behaviorTimer <= 0.0) {
            changeBehaviorRandomly(engine);
        }

        NpcState selfState = getState();
        this.pendingCmd = behavior.update(selfState, ctx, dt);
    }

    /**
     * 後方互換用のplanBehaviorメソッド
     */
    public void planBehavior(PracticeContext ctx, double dt) {
        // ダミーエンジンによる互換呼び出し
        core.SimulationEngine dummyEngine = new core.SimulationEngine(config, new input.InputSource() {
            @Override public double getTranslationInput() { return 0; }
            @Override public double getRotationInput() { return 0; }
            @Override public void update() {}
            @Override public boolean isIntakePressed() { return false; }
            @Override public boolean isEjectPressed() { return false; }
            @Override public boolean isShootPressed() { return false; }
        });
        planBehavior(dummyEngine, ctx, dt);
    }

    /**
     * 挙動を動的かつランダムに切り替えます。
     * 切り替え時は、現在位置で軌道が成立するかを判定し、不成立時は再抽選、最終的にフォールバックします。
     */
    private void changeBehaviorRandomly(core.SimulationEngine engine) {
        RobotBehavior nextBehavior = null;
        boolean success = false;

        // 最大10回再抽選
        for (int attempt = 0; attempt < 10; attempt++) {
            int behaviorIdx = random.nextInt(Behaviors.BEHAVIOR_COUNT);
            RobotBehavior candidate = Behaviors.createBehavior(behaviorIdx);

            if (PracticeManager.isTrajectoryInsideField(config, candidate, this.x, this.y, engine.getFieldObstacles())) {
                nextBehavior = candidate;
                success = true;
                break;
            }
        }

        // 10回とも不成立だった場合、無条件成立する挙動（Still, Wander, Spin, Drunk, Skittish, Bounce）からフォールバック
        if (!success) {
            int[] fallbackIndices = {0, 4, 5, 9, 10, 11};
            int fallbackIdx = fallbackIndices[random.nextInt(fallbackIndices.length)];
            nextBehavior = Behaviors.createBehavior(fallbackIdx);
        }

        this.behavior = nextBehavior;
        
        // 次の切替タイマーを再抽選 (1.0 〜 5.0秒)
        this.behaviorTimer = 1.0 + random.nextDouble() * 4.0;

        // コメントイベントを記録
        String behaviorName = behavior.getClass().getSimpleName();
        String timeStr = String.format(Locale.US, "%.3f", engine.getSimTime());
        String eventMsg = String.format("Event: t=%s, NPC#%d switched to %s", timeStr, id, behaviorName);
        System.out.println(eventMsg);
        engine.logComment(eventMsg);
    }

    /**
     * 決定された目標速度指令を用いて、物理移動（運動積分）を行います（運動積分）。
     *
     * @param dt タイムステップ (秒)
     */
    public void integrate(double dt) {
        if (this.pendingCmd == null) {
            this.pendingCmd = new DriveCommand(0.0, 0.0);
        }

        // 1. 加速度・角加速度制限を適用して実速度を更新
        double deltaV = this.pendingCmd.v - this.v;
        double maxDeltaV = config.maxAccel * dt;
        this.v += Math.max(-maxDeltaV, Math.min(maxDeltaV, deltaV));

        double deltaOmega = this.pendingCmd.omega - this.omega;
        double maxDeltaOmega = config.maxAlpha * dt;
        this.omega += Math.max(-maxDeltaOmega, Math.min(maxDeltaOmega, deltaOmega));

        // 2. スリップ無効版 of オイラー積分 (車軸ポーズ A of 更新)
        double vSlide = 0.0;

        this.heading += this.omega * dt;
        this.heading = normalizeAngle(this.heading);

        double cosH = Math.cos(this.heading);
        double sinH = Math.sin(this.heading);

        double xADot = this.v * cosH - vSlide * sinH;
        double yADot = this.v * sinH + vSlide * cosH;

        this.xA += xADot * dt;
        this.yA += yADot * dt;

        // 3. ボディ中心 G of 幾何導出
        this.x = this.xA - config.pivotOffsetD * cosH;
        this.y = this.yA - config.pivotOffsetD * sinH;

        // 4. 壁クランプ処理
        clampToFieldBoundaries();

        this.pendingCmd = null; // 指令をリセット
    }

    /**
     * ロボットの状態を1タイムステップ更新します（計画と積分を一括実行）。
     *
     * @param ctx 更新コンテキスト
     * @param dt タイムステップ (秒)
     */
    public void update(PracticeContext ctx, double dt) {
        planBehavior(ctx, dt);
        integrate(dt);
    }

    /**
     * フィールド境界でのクランプ処理（角のめり込み対応）を行い、押し戻された場合は速度クランプも行います。
     */
    public void clampToFieldBoundaries() {
        double robotRadius = config.robotSize / 2.0;
        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        double half = robotRadius * (Math.abs(cosH) + Math.abs(sinH));

        double origX = this.x;
        double origY = this.y;

        // X軸方向のクランプ
        double minX = half;
        double maxX = 7.0 - half;
        if (this.x < minX) {
            this.x = minX;
        } else if (this.x > maxX) {
            this.x = maxX;
        }

        // Y軸方向のクランプ
        double minY = half;
        double maxY = 7.0 - half;
        if (this.y < minY) {
            this.y = minY;
        } else if (this.y > maxY) {
            this.y = maxY;
        }

        double dx = this.x - origX;
        double dy = this.y - origY;

        if (Math.hypot(dx, dy) > 1e-6) {
            // 壁に衝突して押し戻されたので、速度をクランプ
            clampVelocity(dx, dy);

            // 車軸位置も再計算
            this.xA = this.x + config.pivotOffsetD * cosH;
            this.yA = this.y + config.pivotOffsetD * sinH;
        }
    }

    /**
     * 衝突等による押し戻し時に、ボディ中心位置を強制的に補正し、めり込み方向の速度をクランプします。
     *
     * @param correctedX 補正後のX座標
     * @param correctedY 補正後のY座標
     * @param dx 押し戻しベクトルX (補正後の位置 - 補正前の位置)
     * @param dy 押し戻しベクトルY (補正後の位置 - 補正前の位置)
     */
    public void overridePosition(double correctedX, double correctedY, double dx, double dy) {
        this.x = correctedX;
        this.y = correctedY; // バグ修正: Y座標の上書き漏れを解消
        
        // 押し戻し方向の速度クランプ
        clampVelocity(dx, dy);

        // 補正されたボディ位置と向きから車軸位置を逆算
        double cosH = Math.cos(this.heading);
        double sinH = Math.sin(this.heading);
        this.xA = this.x + config.pivotOffsetD * cosH;
        this.yA = this.y + config.pivotOffsetD * sinH;

        // 壁クランプも再適用
        clampToFieldBoundaries();
    }

    /**
     * 衝突等による押し戻し時に、ボディ中心位置を強制的に補正します。
     *
     * @param correctedX 補正後のX座標
     * @param correctedY 補正後のY座標
     */
    public void overridePosition(double correctedX, double correctedY) {
        overridePosition(correctedX, correctedY, 0.0, 0.0);
    }

    /**
     * 指定された補正ベクトル方向への速度成分をクランプします。
     * 押し戻し方向と逆（めり込む方向）に進もうとしている場合、進行速度 v を壁と平行な成分に射影します。
     */
    public void clampVelocity(double dx, double dy) {
        double len = Math.hypot(dx, dy);
        if (len > 1e-6) {
            double nx = dx / len;
            double ny = dy / len;
            double ux = Math.cos(this.heading);
            double uy = Math.sin(this.heading);
            double dot = ux * nx + uy * ny;
            
            if (this.v * dot < 0.0) {
                this.v = this.v * Math.sqrt(Math.max(0.0, 1.0 - dot * dot));
            }
        }
    }

    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * 現在の不変なNpcStateを生成して返します。
     */
    public NpcState getState() {
        return new NpcState(id, x, y, heading, v, omega, behavior.getClass().getSimpleName());
    }

    public int getId() { return id; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getHeading() { return heading; }
    public double getV() { return v; }
    public double getOmega() { return omega; }
    public double getXA() { return xA; }
    public double getYA() { return yA; }
}
