package core;

/**
 * ロボットの物理パラメータ、機体パラメータ、および初期設定を保持するクラス。
 */
public class RobotConfig {
    // 質量 (kg) - ※今回の簡易モデルでは挙動に影響しません（デッドパラメータ）。
    // 将来的に a_max = F/m として駆動力と質量から加速度を計算する拡張用のフックとして残しています。
    public final double mass;

    // トレッド幅 w (m) - 左右の車輪間距離
    public final double trackWidth;
    // ホイール直径 (m) - 既定 90mm = 0.09m
    public final double wheelDiameter;
    // モーター無負荷回転数 (rpm) - 既定 6000rpm
    public final double motorNoLoadSpeed;
    // ギア比 (1/12, 1/15, 1/20)
    public final double gearRatio;

    // 旋回ピボットオフセット d (m) - 車軸中心から前方への距離（d>0で前方、d<0で後方）
    public final double pivotOffsetD;
    
    // 静止摩擦係数 muStatic (スリップ開始判定用) - 既定 0.8
    public final double muStatic;
    // 動摩擦係数 muKinetic (スリップ中の横滑り減速用) - 既定 0.7
    public final double muKinetic;

    // 初期状態
    public final double initX;
    public final double initY;
    public final double initHeading; // ラジアン (+x基準、反時計回り正)

    // 運動限界・特性
    public final double maxSpeed;      // 最高速度 (m/s)
    public final double maxAccel;      // 最大加速度 (m/s^2)
    public final double maxOmega;      // 最大角速度 (rad/s)
    public final double maxAlpha;      // 最大角加速度 (rad/s^2)
    public final double frictionAccel; // 摩擦による自然減速度 (m/s^2)

    // 機体の外形サイズ (正方形の一辺) - 既定 0.5m
    public final double robotSize = 0.5;

    // 練習モード用の乱数シード (null の場合はランダム)
    public final Long practiceSeed;

    // 壁整列挙動のパラメータ (外部トグル可能なため final を外しています)
    public double k_align = 5.0;
    public double omegaAlignMax = 2.0;
    public boolean wallAlignEnabled = true;

    // 練習モードにおけるプレイヤーとNPCの衝突時の押し戻し比率 (プレイヤー側の配分)
    public double playerPushRatio = 0.2;

    // 入力カーブ（expo）およびアクティブブレーキのパラメータ
    public double expoDrive = 1.0;
    public double expoTurn = 1.0;
    public double brakeGain = 10.0;
    public double brakeThreshold = 0.05;
    public boolean brakeEnabled = true;
    public double turnBrakeGain = 10.0;
    public double turnBrakeThreshold = 0.05;
    public boolean turnBrakeEnabled = true;

    // 障害物定義JSONのファイルパス
    public String fieldObstaclesPath = "field_obstacles.json";

    // --- ボール物理パラメータ ---
    public int ballCount = 500;
    public double ballMass = 0.062; // ボール質量 62g (実機スペック)
    public double ballRestitution = 0.2; // 反発係数 (カーペット想定)
    public double ballFriction = 0.4; // 摩擦係数
    public Long ballSeed = null; // ボール用シード値 (nullでランダム)
    public int ballSubSteps = 4; // ボール物理サブステップ数 (既定4 => 200Hz相当)
    public int ballSolverIterations = 3; // 衝突応答の反復回数
    public double ballMinEmitSpeed = 1.0; // 放出時の最小速度 (m/s)
    public double ballMaxEmitSpeed = 3.0; // 放出時の最大速度 (m/s)
    public double ballRollingDamping = 1.5; // 転がり摩擦減衰率 (1/s)
    public double ballStopThreshold = 0.02; // 静止判定速度閾値 (m/s)
    public double ballEmitRadius = 0.25; // 放出位置の半径範囲 (m)

    // --- ボール回収（インテーク）パラメータ ---
    public int intakeCapacity = 10;          // 回収上限（1〜100）
    public double intakeFrontDepth = 0.08;   // 前面辺からの回収判定奥行き (m)
    public double intakeFrontWidth = 0.50;   // 前面辺の横幅 (m)

    // --- ボール射出（シューター）パラメータ ---
    public double shootRange = 2.0;               // 射程 (m)
    public double shootAngleThreshold = 0.2618;  // 正対角度閾値 (rad, 15度 = 0.2618rad)
    public double shootSuccessProb = 0.7;        // 射出成功確率 (0.0〜1.0)
    public double shootBPS = 5.0;                // 秒間射出数 (1〜10)
    public double shootMissRadius = 2.0;         // 失敗時のばらつき半径 (m)

    // --- 白ゴール（Fire Shield 斜辺）パラメータ ---
    public double whiteGoalSuccessProb = 0.5;    // 白ゴール接触時の成功確率 (0.0〜1.0)
    public double whiteGoalMissRadius = 2.0;     // 失敗時のばらつき半径 (m)

    /**
     * デフォルトパラメータで設定を生成します。
     */
     public RobotConfig() {
        this(13.5, 1.0 / 15.0, 0.09, 0.45, 0.0, 0.8, 0.7, 3.5, 3.5, Math.PI / 2, null);
    }

    /**
     * パラメータを指定して設定を生成します。
     * 
     * @param mass 質量 (kg)
     * @param gearRatio ギア比 (1/12, 1/15, 1/20)
     * @param wheelDiameter ホイール直径 (m)
     * @param trackWidth トレッド幅 w (m)
     * @param pivotOffsetD 旋回ピボットオフセット d (m)
     * @param muStatic 静止摩擦係数
     * @param muKinetic 動摩擦係数
     * @param initX 初期X位置 (m)
     * @param initY 初期Y位置 (m)
     * @param initHeading 初期向き (ラジアン)
     * @param practiceSeed 練習モード用乱数シード
     */
    public RobotConfig(double mass, double gearRatio, double wheelDiameter, double trackWidth,
                       double pivotOffsetD, double muStatic, double muKinetic,
                       double initX, double initY, double initHeading, Long practiceSeed) {
        this.mass = mass;
        this.gearRatio = gearRatio;
        this.wheelDiameter = wheelDiameter;
        this.trackWidth = trackWidth;
        this.pivotOffsetD = pivotOffsetD;
        this.muStatic = muStatic;
        this.muKinetic = muKinetic;
        this.initX = initX;
        this.initY = initY;
        this.initHeading = initHeading;
        this.practiceSeed = practiceSeed;
        this.motorNoLoadSpeed = 6000.0;

        // --- 物理特性の計算根拠 ---
        // モーター単体の無負荷回転数: N_free = 6000 rpm = 100 rps
        // ホイール無負荷回転数: N_wheel = N_free * gearRatio
        // 最大無負荷速度: v_max_free = N_wheel * PI * wheelDiameter
        // 実走効率を見込んで約8割 (0.8) を乗算
        double nFree = motorNoLoadSpeed / 60.0; // rps
        double nWheel = nFree * gearRatio;
        this.maxSpeed = nWheel * Math.PI * wheelDiameter * 0.8;

        // モーター単体のストールトルク: T_stall = 0.105 N・m (REV HD Hex Motor 仕様)
        // ギアヘッド後の合成トルク: T_wheel = 2モーター * T_stall * (1 / gearRatio) * 効率(0.85)
        // 駆動力: F_drive = T_wheel / (wheelDiameter / 2)
        // 最大加速度: a_max = F_drive / mass
        double tStall = 0.105;
        double efficiency = 0.85;
        double tWheel = 2.0 * tStall * (1.0 / gearRatio) * efficiency;
        double rWheel = wheelDiameter / 2.0;
        double fDrive = tWheel / rWheel;
        this.maxAccel = fDrive / mass;

        // 運動限界値のデフォルト
        this.maxOmega = 4.0;          // rad/s (約 230 deg/s)
        this.maxAlpha = 12.0;         // rad/s^2
        this.frictionAccel = 0.5;     // 摩擦減速度 (m/s^2)
    }

    /**
     * すべてのパラメータを上書き指定できるフルコンストラクタ（CSVロード・リプレイ用）。
     */
    public RobotConfig(double mass, double trackWidth, double wheelDiameter, double motorNoLoadSpeed,
                       double gearRatio, double pivotOffsetD, double muStatic, double muKinetic,
                       double initX, double initY, double initHeading, double maxSpeed, double maxAccel,
                       double maxOmega, double maxAlpha, double frictionAccel, Long practiceSeed) {
        this.mass = mass;
        this.trackWidth = trackWidth;
        this.wheelDiameter = wheelDiameter;
        this.motorNoLoadSpeed = motorNoLoadSpeed;
        this.gearRatio = gearRatio;
        this.pivotOffsetD = pivotOffsetD;
        this.muStatic = muStatic;
        this.muKinetic = muKinetic;
        this.initX = initX;
        this.initY = initY;
        this.initHeading = initHeading;
        this.maxSpeed = maxSpeed;
        this.maxAccel = maxAccel;
        this.maxOmega = maxOmega;
        this.maxAlpha = maxAlpha;
        this.frictionAccel = frictionAccel;
        this.practiceSeed = practiceSeed;
    }
}
