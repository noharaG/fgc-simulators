package core;

import input.InputSource;
import io.LogWriter;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * シミュレーションの実行状態を管理し、更新ループを実行するエンジンクラス。
 */
public class SimulationEngine {

    /**
     * エンジンの動作イベントを通知するためのリスナーインターフェース。
     */
    public interface EngineListener {
        /** タイマーが自動起動したときに呼び出されます。 */
        void onTimerStarted();
        /** 状態が更新されるたびに呼び出されます (50Hz)。 */
        void onStateUpdated(RobotState state);
        /** 制限時間 (150秒) に達して強制停止したときに呼び出されます。 */
        void onTimeLimitReached();
    }

    private final RobotConfig config;
    private final InputSource inputSource;
    private final List<Obstacle> obstacles;
    private final List<FieldObstacle> fieldObstacles;
    private final List<EngineListener> listeners;

    private RobotState currentState;
    private SimulationPhase currentPhase;
    private final IBallSimulation ballSimulation;
    private boolean timerStarted;
    private boolean timeLimitReached;
    private double simTime; // タイマー起動後の経過秒数
    private boolean isBlueAlliance = false; // アライアンス設定 (デフォルトは赤)

    // 対戦モード
    private boolean competitionMode = false;
    private practice.CompetitionManager competitionManager = null;

    private volatile LogWriter logWriter;
    private String runId;
    private String logFilePath;

    // 走行統計 (サマリーログ用)
    private double maxSpeedSeen;
    private double maxOmegaSeen;
    private int stopCount;
    private boolean wasStoppedPrev;
    private int slipCount;
    private boolean wasSlippingPrev;
    private double totalSlipTime;

    // 衝突統計
    private int collisionCount;
    private double totalCollisionTime;
    private boolean wasCollidingPrev;
    private Long activePracticeSeed;

    // 障害物衝突統計
    private int obstacleCollisionCount;
    private double totalObstacleCollisionTime;
    private boolean wasObstacleCollidingPrev;

    // パフォーマンス計測用（ミリ秒単位）
    public double lastAiTimeMs = 0.0;
    public double lastBallTimeMs = 0.0;

    // 日時フォーマッタ（tick/startTimer/finalizeLogging はすべて同一シミュレーションスレッドから
    // 呼ばれるため SimpleDateFormat のスレッド安全性は問題ない）
    private final SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // タイマー起動からこの秒数が経過したらボールワープを自動有効化
    private static final double WARP_ENABLE_DELAY = 3.0;

    /**
     * シミュレーションエンジンを初期化します。
     * 
     * @param config ロボット設定
     * @param inputSource 入力ソース (キーボードまたはゲームパッド)
     */
    public SimulationEngine(RobotConfig config, InputSource inputSource) {
        this.config = config;
        this.inputSource = inputSource;
        this.obstacles = new ArrayList<>();
        this.fieldObstacles = new ArrayList<>();
        this.listeners = new ArrayList<>();
        this.currentState = RobotState.createInitial(config.initX, config.initY, config.initHeading, config.pivotOffsetD, getFormattedDateTime());
        this.currentPhase = SimulationPhase.SETUP;
        initObstacles();
        this.ballSimulation = new BallSimulation();
        this.ballSimulation.init(config, this.fieldObstacles);
        this.timerStarted = false;
        this.timeLimitReached = false;
        this.simTime = 0.0;
        this.wasStoppedPrev = true;
        this.wasSlippingPrev = false;
        resetStats();
    }

    private void initObstacles() {
        try {
            List<FieldObstacle> loaded = io.FieldObstacleLoader.load(config.fieldObstaclesPath);
            this.fieldObstacles.addAll(loaded);
            System.out.println("フィールド障害物をロードしました。計: " + fieldObstacles.size() + " 個");
        } catch (java.io.IOException e) {
            throw new RuntimeException("障害物設定ファイルの読み込みに失敗しました: " + e.getMessage(), e);
        }
    }

    private void resetStats() {
        this.maxSpeedSeen = 0.0;
        this.maxOmegaSeen = 0.0;
        this.stopCount = 0;
        this.slipCount = 0;
        this.totalSlipTime = 0.0;
        this.collisionCount = 0;
        this.totalCollisionTime = 0.0;
        this.wasCollidingPrev = false;
        this.activePracticeSeed = null;
        this.obstacleCollisionCount = 0;
        this.totalObstacleCollisionTime = 0.0;
        this.wasObstacleCollidingPrev = false;
    }

    public List<FieldObstacle> getFieldObstacles() {
        return fieldObstacles;
    }

    public void addListener(EngineListener listener) {
        listeners.add(listener);
    }

    public void addObstacle(Obstacle obstacle) {
        obstacles.add(obstacle);
    }

    public List<Obstacle> getObstacles() {
        return obstacles;
    }

    public RobotState getCurrentState() {
        return currentState;
    }

    public RobotConfig getConfig() {
        return config;
    }

    public boolean isTimerStarted() {
        return timerStarted;
    }

    public boolean isTimeLimitReached() {
        return timeLimitReached;
    }

    public double getSimTime() {
        return simTime;
    }

    public String getLogFilePath() {
        return logFilePath;
    }

    public void setActivePracticeSeed(Long seed) {
        this.activePracticeSeed = seed;
    }

    public SimulationPhase getCurrentPhase() {
        return currentPhase;
    }

    public void setBlueAlliance(boolean isBlue) {
        this.isBlueAlliance = isBlue;
    }

    public boolean isBlueAlliance() {
        return isBlueAlliance;
    }

    public void setCompetitionMode(boolean enabled, practice.CompetitionManager manager) {
        this.competitionMode = enabled;
        this.competitionManager = manager;
        if (enabled && manager != null) {
            CompetitionRobot player = manager.getPlayerRobot();
            if (player != null) {
                this.currentState = new RobotState(
                    0.0, getFormattedDateTime(), player.x, player.y, player.xA, player.yA, player.heading,
                    player.v, player.omega, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0,
                    false, 0.0, 0.0, 0.0,
                    true, 0.0, false,
                    false, -1, 0.0, 0.0, 0, 0.0,
                    false, null, 0.0, 0.0, 0, 0.0,
                    false, 0,
                    player.heldBallCount, player.intakeActive,
                    player.scoreCount, player.shootActive, player.shotAttemptCount,
                    player.whiteScoreCount
                );
            }
        }
    }

    public boolean isCompetitionMode() {
        return competitionMode;
    }

    public practice.CompetitionManager getCompetitionManager() {
        return competitionManager;
    }

    public IBallSimulation getBallSimulation() {
        return ballSimulation;
    }

    /**
     * 時系列ログファイルにコメント行としてテキストを記録します。
     * 
     * @param comment 記録するコメントテキスト
     */
    public void logComment(String comment) {
        if (logWriter != null) {
            logWriter.writeComment(comment);
        }
    }

    /**
     * 1ステップ (dt秒) シミュレーションを更新します。
     * 
     * @param dt タイムステップ (秒)
     * @param resolver 衝突解消フック
     */
    public void tick(double dt, CollisionResolver resolver) {
        // 現在の生入力を取得
        double rawInputY = inputSource.getTranslationInput();
        double rawInputX = inputSource.getRotationInput();

        // 画面座標系（フィールドX軸が画面上方、Y軸が画面右方向）に合わせて旋回入力を反転
        rawInputX = -rawInputX;

        // 1. タイマー自動起動の判定
        if (currentPhase == SimulationPhase.SETUP) {
            double deadzone = 0.05;
            if (Math.abs(rawInputY) > deadzone || Math.abs(rawInputX) > deadzone) {
                startTimer();
            }
        }
  
        // 2. 制限時間 (150秒) の判定と入力制限
        double finalInputY = rawInputY;
        double finalInputX = rawInputX;
  
        if (currentPhase == SimulationPhase.SETUP) {
            // SETUPフェーズは入力を無視してロボットを停止させる
            finalInputY = 0.0;
            finalInputX = 0.0;
        } else if (currentPhase == SimulationPhase.RUNNING) {
            simTime += dt;
            // 開始から WARP_ENABLE_DELAY 秒後にボールワープを自動有効化
            if (!ballSimulation.isWarpEnabled() && simTime >= WARP_ENABLE_DELAY) {
                ballSimulation.setWarpEnabled(true);
            }
            if (simTime >= 150.0) {
                // 制限時間到達時の強制停止
                simTime = 150.0;
                currentPhase = SimulationPhase.FINISHED;
                timeLimitReached = true;
                timerStarted = false;
                finalInputY = 0.0;
                finalInputX = 0.0;
 
                // ログファイルのクローズと走行サマリーの追記
                finalizeLogging();
 
                // リスナーに強制停止を通知
                for (EngineListener l : listeners) {
                    l.onTimeLimitReached();
                }
            }
        } else if (currentPhase == SimulationPhase.FINISHED) {
            // 終了後は入力を受け付けない
            finalInputY = 0.0;
            finalInputX = 0.0;
        }

        // 3. NPCの挙動 update（運動積分前、前フレーム確定実位置基準）
        if (resolver != null) {
            long aiStart = System.nanoTime();
            resolver.updateBehaviors(this, dt);
            lastAiTimeMs = (System.nanoTime() - aiStart) / 1_000_000.0;
        }

        // 4. 物理・運動学の更新
        String currentDateTime = getFormattedDateTime();
        double timeForState = (currentPhase == SimulationPhase.RUNNING) ? simTime : ((currentPhase == SimulationPhase.FINISHED) ? 150.0 : 0.0);
        
        RobotState nextState;
        if (competitionMode && competitionManager != null) {
            CompetitionRobot player = competitionManager.getPlayerRobot();
            if (player != null) {
                // 対戦パラメータの動的同期 (ボール回収・射出用にconfigに適用)
                config.intakeCapacity = player.getIntakeCapacity();
                config.shootSuccessProb = player.getShootSuccessProb();
                config.shootRange = player.getShootRange();
                config.shootBPS = player.getShootBPS();

                double inputV = (currentPhase == SimulationPhase.SETUP) ? 0.0 : finalInputY * player.getConfig().maxSpeed;
                double inputOmega = (currentPhase == SimulationPhase.SETUP) ? 0.0 : finalInputX * player.getConfig().maxOmega;
                
                player.integrate(inputV, 0.0, inputOmega, dt);

                nextState = new RobotState(
                    timeForState, currentDateTime, player.x, player.y, player.xA, player.yA, player.heading,
                    player.v, player.omega, 0.0, 0.0, 0.0, 0.0,
                    finalInputY, finalInputX, inputV, inputOmega,
                    false, 0.0, 0.0, 0.0,
                    player.v == 0.0, 0.0, player.hitWallFlag,
                    player.collisionFlag, -1, 0.0, 0.0, 0, 0.0,
                    player.obstacleCollisionFlag, player.obstacleCollisionId, 0.0, 0.0, 0, 0.0,
                    false, 0,
                    player.heldBallCount, player.intakeActive,
                    player.scoreCount, player.shootActive, player.shotAttemptCount,
                    player.whiteScoreCount
                );
            } else {
                nextState = RobotPhysics.update(
                    currentState, config, finalInputY, finalInputX, dt, fieldObstacles, currentDateTime
                );
            }
        } else {
            nextState = RobotPhysics.update(
                currentState, config, finalInputY, finalInputX, dt, fieldObstacles, currentDateTime
            );
        }

        // ボール物理の更新 (FINISHEDでなければ4サブステップ回す)
        if (currentPhase != SimulationPhase.FINISHED) {
            long ballStart = System.nanoTime();
            int subSteps = config.ballSubSteps;
            double subDt = dt / subSteps;

            double rx_start = currentState.x;
            double ry_start = currentState.y;
            double rHeading_start = currentState.heading;

            double rx_end = nextState.x;
            double ry_end = nextState.y;
            double rHeading_end = nextState.heading;

            // ロボットのこのステップでの速度
            double rvx = (rx_end - rx_start) / dt;
            double rvy = (ry_end - ry_start) / dt;

            // resolver から NPC ロボットの OBB 情報を取得 (NPCの補間は行わない)
            List<RobotOBB> npcOBBs = java.util.Collections.emptyList();
            if (resolver != null) {
                npcOBBs = resolver.getNpcOBBs();
            }

            ballSimulation.resetContactFlag();

            for (int sub = 0; sub < subSteps; sub++) {
                double t = (sub + 0.5) / subSteps;
                // ロボット位置の線形補間
                double rx_sub = lerp(rx_start, rx_end, t);
                double ry_sub = lerp(ry_start, ry_end, t);
                double rHeading_sub = lerpAngle(rHeading_start, rHeading_end, t);

                RobotOBB playerOBB = new RobotOBB(rx_sub, ry_sub, rHeading_sub, config.robotSize / 2.0, rvx, rvy);
                ballSimulation.update(subDt, playerOBB, npcOBBs);
            }
            lastBallTimeMs = (System.nanoTime() - ballStart) / 1_000_000.0;
        }

        // ボール物理更新のサブステップループ完了後に processIntake を呼び出す
        boolean currentIntakeActive = false;
        boolean currentShootActive = false;
        if (currentPhase != SimulationPhase.FINISHED) {
            currentIntakeActive = inputSource.isIntakePressed();
            double rx = nextState.x;
            double ry = nextState.y;
            double rHeading = nextState.heading;
            double vx = nextState.v * Math.cos(rHeading);
            double vy = nextState.v * Math.sin(rHeading);
            RobotOBB playerOBB = new RobotOBB(rx, ry, rHeading, config.robotSize / 2.0, vx, vy);
            ballSimulation.processIntake(playerOBB, currentIntakeActive);
            
            currentShootActive = inputSource.isShootPressed();
            boolean isContacting = false;
            if (competitionMode && competitionManager != null) {
                CompetitionRobot player = competitionManager.getPlayerRobot();
                if (player != null) {
                    isContacting = player.collisionFlag;
                }
            }
            ballSimulation.processShoot(1, playerOBB, currentShootActive, isBlueAlliance,
                                        config.shootRange, config.shootBPS, config.shootSuccessProb, config.shootMissRadius,
                                        isContacting, dt);
            
            // プレイヤーの排出（Eject）処理の実行
            boolean currentEjectActive = inputSource.isEjectPressed();
            ballSimulation.processEject(1, playerOBB, currentEjectActive, dt);
        }

        boolean currentBallContact = (currentPhase != SimulationPhase.FINISHED) && ballSimulation.hasContactInFrame();
        int currentBallContactCount = (currentPhase != SimulationPhase.FINISHED) ? ballSimulation.getContactCount() : currentState.cumulativeBallContactCount;
        int currentHeldBallCount = (currentPhase != SimulationPhase.FINISHED) ? ballSimulation.getHeldBallCount() : currentState.heldBallCount;
        int currentScoreCount = (currentPhase != SimulationPhase.FINISHED) ? ballSimulation.getScoreCount() : currentState.scoreCount;
        boolean currentShootActiveState = (currentPhase != SimulationPhase.FINISHED) && ballSimulation.isShootActive();
        int currentShotAttemptCount = (currentPhase != SimulationPhase.FINISHED) ? ballSimulation.getShotAttemptCount() : currentState.shotAttemptCount;
        int currentWhiteScoreCount = (currentPhase != SimulationPhase.FINISHED) ? ballSimulation.getWhiteScoreCount() : currentState.whiteScoreCount;
        
        if (competitionMode && competitionManager != null) {
            CompetitionRobot player = competitionManager.getPlayerRobot();
            if (player != null) {
                // ゲーム状態をプレイヤーロボット側に書き戻す
                player.heldBallCount = currentHeldBallCount;
                player.scoreCount = currentScoreCount;
                player.shotAttemptCount = currentShotAttemptCount;
                player.whiteScoreCount = currentWhiteScoreCount;
                player.intakeActive = currentIntakeActive;
                player.shootActive = currentShootActiveState;
            }
        }

        currentState = new RobotState(
            timeForState, nextState.dateTime, nextState.x, nextState.y, nextState.xA, nextState.yA, nextState.heading,
            nextState.v, nextState.omega, nextState.leftWheelSpeed, nextState.rightWheelSpeed,
            nextState.accelV, nextState.accelOmega, nextState.rawInputY, nextState.rawInputX,
            nextState.cmdV, nextState.cmdOmega, nextState.slipFlag, nextState.slipAmount,
            nextState.cumulativeDistance, nextState.deltaDistance, nextState.isStopped, nextState.cumulativeStopTime,
            nextState.hitWallFlag,
            false, -1, 0.0, 0.0,
            collisionCount, totalCollisionTime,
            nextState.obstacleCollisionFlag, nextState.obstacleCollisionId, nextState.obstacleCollisionPointX, nextState.obstacleCollisionPointY,
            nextState.cumulativeObstacleCollisionCount, nextState.cumulativeObstacleCollisionTime,
            currentBallContact, currentBallContactCount,
            currentHeldBallCount, currentIntakeActive,
            currentScoreCount, currentShootActiveState, currentShotAttemptCount,
            currentWhiteScoreCount
        );

        if (resolver != null) {
            resolver.resolveCollisions(this, dt);
        }

        // 衝突解決後の最終物理座標をCompetitionRobotおよびcurrentStateに反映
        if (competitionMode && competitionManager != null) {
            CompetitionRobot player = competitionManager.getPlayerRobot();
            if (player != null) {
                currentState = new RobotState(
                    timeForState, currentState.dateTime, player.x, player.y, player.xA, player.yA, player.heading,
                    player.v, player.omega, 0.0, 0.0, 0.0, 0.0,
                    finalInputY, finalInputX, finalInputY * player.getConfig().maxSpeed, finalInputX * player.getConfig().maxOmega,
                    false, 0.0, 0.0, 0.0,
                    player.v == 0.0, 0.0, player.hitWallFlag,
                    player.collisionFlag, -1, 0.0, 0.0, collisionCount, totalCollisionTime,
                    player.obstacleCollisionFlag, player.obstacleCollisionId, 0.0, 0.0, obstacleCollisionCount, totalObstacleCollisionTime,
                    currentBallContact, currentBallContactCount,
                    player.heldBallCount, currentIntakeActive,
                    player.scoreCount, currentShootActiveState, player.shotAttemptCount,
                    player.whiteScoreCount
                );
            }
        }

        // 6. 走行統計の更新
        if (timerStarted) {
            updateStats(dt);
            // 時系列ログの書き出し
            if (logWriter != null) {
                try {
                    logWriter.writeTimeSeriesLine(currentState);
                } catch (IOException e) {
                    System.err.println("時系列ログの書き込みに失敗しました: " + e.getMessage());
                }
            }
        }

        // 7. リスナーへ通知
        for (EngineListener l : listeners) {
            l.onStateUpdated(currentState);
        }
    }

    public void tick(double dt) {
        tick(dt, null);
    }

    /**
     * 障害物との衝突状態を更新します。
     */
    public void updateObstacleCollisionState(boolean flag, String obsId, double cx, double cy, double dt) {
        if (timeLimitReached) return;

        if (timerStarted) {
            if (flag) {
                totalObstacleCollisionTime += dt;
                if (!wasObstacleCollidingPrev) {
                    obstacleCollisionCount++;
                    wasObstacleCollidingPrev = true;
                }
            } else {
                wasObstacleCollidingPrev = false;
            }
        }

        currentState = new RobotState(
            currentState.time, currentState.dateTime,
            currentState.x, currentState.y, currentState.xA, currentState.yA, currentState.heading,
            currentState.v, currentState.omega, currentState.leftWheelSpeed, currentState.rightWheelSpeed,
            currentState.accelV, currentState.accelOmega, currentState.rawInputY, currentState.rawInputX,
            currentState.cmdV, currentState.cmdOmega, currentState.slipFlag, currentState.slipAmount,
            currentState.cumulativeDistance, currentState.deltaDistance, currentState.isStopped, currentState.cumulativeStopTime,
            currentState.hitWallFlag || flag,
            currentState.collisionFlag, currentState.collisionNpcId, currentState.collisionPointX, currentState.collisionPointY,
            currentState.cumulativeCollisionCount, currentState.cumulativeCollisionTime,
            flag, obsId, cx, cy,
            obstacleCollisionCount, totalObstacleCollisionTime,
            currentState.ballContactFlag, currentState.cumulativeBallContactCount,
            currentState.heldBallCount, currentState.intakeActive,
            currentState.scoreCount, currentState.shootActive, currentState.shotAttemptCount,
            currentState.whiteScoreCount
        );
    }

    /**
     * 衝突判定および位置補正結果をエンジンの状態に反映します。
     */
    public void updateCollisionState(boolean collisionFlag, int npcId, double cx, double cy,
                                     double correctedX, double correctedY, double correctedXA, double correctedYA,
                                     double clampDx, double clampDy, double dt) {
        if (timeLimitReached) return;

        // 統計の更新
        if (timerStarted) {
            if (collisionFlag) {
                totalCollisionTime += dt;
                if (!wasCollidingPrev) {
                    collisionCount++;
                    wasCollidingPrev = true;
                }
            } else {
                wasCollidingPrev = false;
            }
        }

        // A/v 整合および速度クランプ処理の適用
        double correctedV = currentState.v;
        
        // 位置補正量を計算。位置補正が無くとも、強制クランプ用ベクトルがあればそれを使用
        double dx = correctedX - currentState.x;
        double dy = correctedY - currentState.y;
        if (Math.hypot(dx, dy) < 1e-6 && Math.hypot(clampDx, clampDy) > 1e-6) {
            dx = clampDx;
            dy = clampDy;
        }

        double len = Math.hypot(dx, dy);

        if (len > 1e-6) {
            // 押し戻し方向の単位ベクトル
            double nx = dx / len;
            double ny = dy / len;
            
            // ロボットの進行方向
            double ux = Math.cos(currentState.heading);
            double uy = Math.sin(currentState.heading);
            
            // 進行方向と押し戻し方向の内積
            double dot = ux * nx + uy * ny;
            
            // 押し戻し方向と「逆」(めり込む方向) に移動しようとしている場合、速度を壁と平行な成分に射影
            if (correctedV * dot < 0.0) {
                correctedV = correctedV * Math.sqrt(Math.max(0.0, 1.0 - dot * dot));
            }
        }

        // 左右車輪速度の再計算 (v が補正されたため)
        double leftWheelSpeed = correctedV - (currentState.omega * config.trackWidth / 2.0);
        double rightWheelSpeed = correctedV + (currentState.omega * config.trackWidth / 2.0);

        currentState = new RobotState(
            currentState.time, currentState.dateTime,
            correctedX, correctedY, correctedXA, correctedYA, currentState.heading,
            correctedV, currentState.omega, leftWheelSpeed, rightWheelSpeed,
            currentState.accelV, currentState.accelOmega, currentState.rawInputY, currentState.rawInputX,
            currentState.cmdV, currentState.cmdOmega, currentState.slipFlag, currentState.slipAmount,
            currentState.cumulativeDistance, currentState.deltaDistance, currentState.isStopped, currentState.cumulativeStopTime,
            currentState.hitWallFlag || (len > 1e-6) || collisionFlag, // 押し戻された場合や衝突中も接触扱い
            collisionFlag, npcId, cx, cy,
            collisionCount, totalCollisionTime,
            currentState.obstacleCollisionFlag, currentState.obstacleCollisionId, currentState.obstacleCollisionPointX, currentState.obstacleCollisionPointY,
            currentState.cumulativeObstacleCollisionCount, currentState.cumulativeObstacleCollisionTime,
            currentState.ballContactFlag, currentState.cumulativeBallContactCount,
            currentState.heldBallCount, currentState.intakeActive,
            currentState.scoreCount, currentState.shootActive, currentState.shotAttemptCount,
            currentState.whiteScoreCount
        );
    }

    /**
     * プレイヤーとフィールド上の物理障害物との衝突解決を行い、位置補正およびA/v整合クランプを適用します。
     * 多形状への同時接触を考慮し、3回の反復で解決を図ります。
     */
    private void resolvePlayerObstacleCollisions(double dt) {
        boolean collidedAny = false;
        String collidedId = null;
        double contactX = 0.0;
        double contactY = 0.0;

        double px = currentState.x;
        double py = currentState.y;
        double pHeading = currentState.heading;
        double halfSize = config.robotSize / 2.0;

        double accumulatedDx = 0.0;
        double accumulatedDy = 0.0;

        // 3回反復して重なりを幾何的に解消
        for (int iter = 0; iter < 3; iter++) {
            for (FieldObstacle obs : fieldObstacles) {
                if ("tape".equals(obs.category())) continue; // スタートテープは除外
                CollisionResult res = obs.collideOBB(px, py, pHeading, halfSize);
                if (res != null) {
                    collidedAny = true;
                    collidedId = obs.id();
                    contactX = res.contactPoint[0];
                    contactY = res.contactPoint[1];

                    // ロボット側を100%押し戻す
                    px += res.mtvX;
                    py += res.mtvY;
                    accumulatedDx += res.mtvX;
                    accumulatedDy += res.mtvY;
                }
            }
        }

        // 進入方向速度のクランプ
        double correctedV = currentState.v;
        double len = Math.hypot(accumulatedDx, accumulatedDy);
        if (len > 1e-6) {
            double nx = accumulatedDx / len;
            double ny = accumulatedDy / len;
            double ux = Math.cos(pHeading);
            double uy = Math.sin(pHeading);
            double dot = ux * nx + uy * ny;
            
            if (correctedV * dot < 0.0) {
                correctedV = correctedV * Math.sqrt(Math.max(0.0, 1.0 - dot * dot));
            }
        }

        // 車軸ポーズ A を補正後のボディ中心 G から逆算して A/v 整合を維持
        double pCos = Math.cos(pHeading);
        double pSin = Math.sin(pHeading);
        double pxA = px + config.pivotOffsetD * pCos;
        double pyA = py + config.pivotOffsetD * pSin;

        // 統計情報の更新（タイマー動作時のみ）
        if (timerStarted) {
            if (collidedAny) {
                totalObstacleCollisionTime += dt;
                if (!wasObstacleCollidingPrev) {
                    obstacleCollisionCount++;
                    wasObstacleCollidingPrev = true;
                }
            } else {
                wasObstacleCollidingPrev = false;
            }
        }

        // クランプされた速度から左右の車輪速度を再計算
        double leftWheelSpeed = correctedV - (currentState.omega * config.trackWidth / 2.0);
        double rightWheelSpeed = correctedV + (currentState.omega * config.trackWidth / 2.0);

        // 状態を適用
        currentState = new RobotState(
            currentState.time, currentState.dateTime,
            px, py, pxA, pyA, pHeading,
            correctedV, currentState.omega, leftWheelSpeed, rightWheelSpeed,
            currentState.accelV, currentState.accelOmega, currentState.rawInputY, currentState.rawInputX,
            currentState.cmdV, currentState.cmdOmega, currentState.slipFlag, currentState.slipAmount,
            currentState.cumulativeDistance, currentState.deltaDistance, currentState.isStopped, currentState.cumulativeStopTime,
            currentState.hitWallFlag || (len > 1e-6) || collidedAny,
            currentState.collisionFlag, currentState.collisionNpcId, currentState.collisionPointX, currentState.collisionPointY,
            currentState.cumulativeCollisionCount, currentState.cumulativeCollisionTime,
            currentState.obstacleCollisionFlag || collidedAny, currentState.obstacleCollisionId, currentState.obstacleCollisionPointX, currentState.obstacleCollisionPointY,
            obstacleCollisionCount, totalObstacleCollisionTime,
            currentState.ballContactFlag, currentState.cumulativeBallContactCount,
            currentState.heldBallCount, currentState.intakeActive,
            currentState.scoreCount, currentState.shootActive, currentState.shotAttemptCount,
            currentState.whiteScoreCount
        );
    }

    /**
     * タイマーを手動で停止（または強制終了）し、現在の走行ログを保存します。
     * UI からの「リセット」時や手動停止時に呼び出されます。
     */
    public void stopAndSave() {
        if (currentPhase == SimulationPhase.RUNNING) {
            currentPhase = SimulationPhase.FINISHED;
            timerStarted = false;
            timeLimitReached = true;
            finalizeLogging();
        }
    }

    private void startTimer() {
        currentPhase = SimulationPhase.RUNNING;
        timerStarted = true;
        simTime = 0.0;
        ballSimulation.setWarpEnabled(false); // リセット時はOFFに戻す
        resetStats();

        // ログファイルの作成（I/Oをバックグラウンドで実行してメインループの遅延を防ぐ）
        runId = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File logDir = new File("logs");
        logDir.mkdirs();

        final File timeseriesFile = new File(logDir, "run_" + runId + "_timeseries.csv");
        logFilePath = timeseriesFile.getAbsolutePath();

        final RobotConfig capturedConfig = this.config;
        Thread logInitThread = new Thread(() -> {
            try {
                LogWriter lw = new LogWriter(timeseriesFile);
                lw.writeMetadata(capturedConfig);
                lw.writeHeader();
                logWriter = lw; // volatile なので他スレッドから即座に可視
            } catch (IOException e) {
                System.err.println("ログファイルの作成に失敗しました: " + e.getMessage());
            }
        }, "log-init");
        logInitThread.setDaemon(true);
        logInitThread.start();

        // リスナーにタイマースタートを通知
        for (EngineListener l : listeners) {
            l.onTimerStarted();
        }
    }

    private void updateStats(double dt) {
        double currentSpeed = Math.abs(currentState.v);
        double currentOmega = Math.abs(currentState.omega);

        if (currentSpeed > maxSpeedSeen) {
            maxSpeedSeen = currentSpeed;
        }
        if (currentOmega > maxOmegaSeen) {
            maxOmegaSeen = currentOmega;
        }

        // 停止回数のカウント (動いている状態から停止状態へ遷移した瞬間)
        if (!currentState.isStopped && wasStoppedPrev) {
            wasStoppedPrev = false;
        } else if (currentState.isStopped && !wasStoppedPrev) {
            stopCount++;
            wasStoppedPrev = true;
        }

        // スリップ回数のカウントとスリップ時間
        if (currentState.slipFlag) {
            totalSlipTime += dt;
            if (!wasSlippingPrev) {
                slipCount++;
                wasSlippingPrev = true;
            }
        } else {
            wasSlippingPrev = false;
        }
    }

    private void finalizeLogging() {
        if (logWriter != null) {
            try {
                logWriter.close();
                
                // サマリーログへの追記
                File summaryFile = new File("logs/summary.csv");
                LogWriter.writeSummaryLine(
                    summaryFile,
                    getFormattedDateTime(),
                    runId,
                    currentState.cumulativeDistance,
                    currentState.cumulativeDistance / Math.max(0.1, simTime), // 平均速度
                    maxSpeedSeen,
                    maxOmegaSeen,
                    currentState.cumulativeStopTime,
                    stopCount,
                    slipCount,
                    totalSlipTime,
                    collisionCount,
                    totalCollisionTime,
                    activePracticeSeed,
                    config,
                    simTime,
                    obstacleCollisionCount,
                    totalObstacleCollisionTime,
                    config.ballCount,
                    config.ballSeed,
                    currentState.cumulativeBallContactCount,
                    ballSimulation.getTotalCollectedCount(),
                    config.intakeCapacity,
                    currentState.scoreCount,
                    ballSimulation.getShotAttemptCount(),
                    config.shootSuccessProb,
                    config.shootBPS,
                    currentState.whiteScoreCount
                );
            } catch (IOException e) {
                System.err.println("サマリーログの保存に失敗しました: " + e.getMessage());
            } finally {
                logWriter = null;
            }
        }
    }

    private String getFormattedDateTime() {
        return dateTimeFormatter.format(new Date());
    }

    private double lerp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    private double lerpAngle(double start, double end, double t) {
        double diff = end - start;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return start + diff * t;
    }
}
