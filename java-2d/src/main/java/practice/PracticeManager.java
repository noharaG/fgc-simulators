package practice;

import core.CollisionResolver;
import core.RobotConfig;
import core.RobotState;
import core.RobotOBB;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 練習モード全体のNPC生成、更新、衝突判定（SAT/OBB）、および描画を統括するマネージャクラス。
 * プレイヤー側コアに依存しない設計となっています。
 */
public class PracticeManager implements CollisionResolver {

    private final RobotConfig playerConfig;
    private final List<NpcRobot> npcs;
    private final Random random;
    private final long currentSeed;

    // プレイヤー衝突のバッファリング用一時変数
    private boolean playerCollidedThisFrame = false;
    private int collidedNpcId = -1;
    private double collisionPX = 0.0;
    private double collisionPY = 0.0;

    /**
     * @param playerConfig プレイヤーの設定 (NPC生成時のパラメータ計算に使用)
     * @param customSeed 指定された乱数シード。null の場合はランダムに決定
     */
    public PracticeManager(RobotConfig playerConfig, Long customSeed) {
        this.playerConfig = playerConfig;
        this.npcs = new ArrayList<>();
        
        if (customSeed != null) {
            this.currentSeed = customSeed;
            this.random = new Random(customSeed);
        } else {
            this.currentSeed = System.currentTimeMillis();
            this.random = new Random(this.currentSeed);
        }

        List<core.FieldObstacle> obstaclesForSpawn = new ArrayList<>();
        try {
            obstaclesForSpawn = io.FieldObstacleLoader.load(playerConfig.fieldObstaclesPath);
        } catch (Exception e) {
            System.err.println("NPCスポーン用の障害物データの読み込みに失敗しました: " + e.getMessage());
        }

        spawnNpcs(obstaclesForSpawn);
    }

    /**
     * 5台のNPCを重ならない、かつ障害物とも衝突しない有効な位置にスポーンさせます。
     */
    private void spawnNpcs(List<core.FieldObstacle> fieldObstacles) {
        npcs.clear();
        
        // 使用可能な挙動のリスト
        RobotBehavior[] availableBehaviors = {
            new Behaviors.StillBehavior(),
            new Behaviors.LinePatrolBehavior(),
            new Behaviors.CircleBehavior(),
            new Behaviors.RadialBehavior(),
            new Behaviors.WanderBehavior(),
            new Behaviors.BounceBehavior(),
            new Behaviors.LoopPatrolBehavior(),
            new Behaviors.SweepBehavior(),
            new Behaviors.StopGoBehavior(),
            new Behaviors.SpinBehavior(),
            new Behaviors.DrunkBehavior(),
            new Behaviors.SkittishBehavior()
        };

        for (int i = 0; i < 5; i++) {
            // ランダムに挙動を選択
            RobotBehavior behavior = availableBehaviors[random.nextInt(availableBehaviors.length)];
            
            // 重複しない初期位置を探索 (最大100回リトライ)
            double spawnX = 3.5;
            double spawnY = 3.5;
            boolean validPosition = false;

            for (int attempt = 0; attempt < 100; attempt++) {
                // 壁に接触しない範囲でスポーン (0.5m 〜 6.5m)
                spawnX = 0.5 + random.nextDouble() * 6.0;
                spawnY = 0.5 + random.nextDouble() * 6.0;

                // 割り当てられた挙動の軌道全体がフィールド内に収まり、障害物とも交差しないか保証
                if (!isTrajectoryInsideField(playerConfig, behavior, spawnX, spawnY, fieldObstacles)) {
                    continue;
                }

                // プレイヤーの初期位置（デフォルト設定値）から十分離れているか
                double distToPlayerInit = Math.hypot(spawnX - playerConfig.initX, spawnY - playerConfig.initY);
                if (distToPlayerInit < 1.5) {
                    continue; // プレイヤーのスポーン位置に近すぎる
                }

                // 既存のNPCと重なっていないか
                boolean collidesWithOther = false;
                for (NpcRobot other : npcs) {
                    if (Math.hypot(spawnX - other.getX(), spawnY - other.getY()) < 0.8) {
                        collidesWithOther = true;
                        break;
                    }
                }

                if (!collidesWithOther) {
                    validPosition = true;
                    break;
                }
            }

            // 万が一妥当な位置が見つからない場合は、軌道制限を考慮せず再探索
            if (!validPosition) {
                for (int attempt = 0; attempt < 100; attempt++) {
                    spawnX = 0.5 + random.nextDouble() * 6.0;
                    spawnY = 0.5 + random.nextDouble() * 6.0;
                    boolean collidesWithOther = false;
                    for (NpcRobot other : npcs) {
                        if (Math.hypot(spawnX - other.getX(), spawnY - other.getY()) < 0.8) {
                            collidesWithOther = true;
                            break;
                        }
                    }
                    if (!collidesWithOther) {
                        validPosition = true;
                        break;
                    }
                }
            }

            double initHeading = random.nextDouble() * 2 * Math.PI - Math.PI;
            long npcSeed = random.nextLong(); // 各NPC個別の乱数シードを生成
            npcs.add(new NpcRobot(i + 1, spawnX, spawnY, initHeading, playerConfig, behavior, npcSeed));
        }
    }

    /**
     * 指定された挙動の軌跡がフィールド (7m x 7m) 内に完全に収まるか判定するヘルパー。
     */
    public static boolean isTrajectoryInsideField(RobotConfig playerConfig, RobotBehavior behavior, double x, double y, List<core.FieldObstacle> fieldObstacles) {
        double r = playerConfig.robotSize / 2.0;

        boolean inside = true;
        if (behavior instanceof Behaviors.CircleBehavior) {
            // 半径 1.0m の円運動。マージンを含め 1.4m 以上内側
            double margin = 1.0 + r + 0.15;
            inside = (x >= margin && x <= (7.0 - margin) && y >= margin && y <= (7.0 - margin));
        }
        else if (behavior instanceof Behaviors.SweepBehavior) {
            // SWEEP: Y直進限界 0.8m 〜 6.2m。スライド X方向 0.7m 進む。
            // 左右スライドマージンを含め 0.35 <= x <= 5.95、かつ 0.8 <= y <= 6.2
            double minX = r + 0.1;
            double maxX = 7.0 - 0.7 - r - 0.1;
            double minY = 0.8;
            double maxY = 6.2;
            inside = (x >= minX && x <= maxX && y >= minY && y <= maxY);
        }
        else if (behavior instanceof Behaviors.LoopPatrolBehavior) {
            // LOOP_PATROL: 固定 waypoint [2.0, 5.0]。フィールド内に完全に収まるため常に安全。
            inside = true;
        }

        if (!inside) {
            return false;
        }

        if (fieldObstacles == null || fieldObstacles.isEmpty()) {
            return true;
        }

        // 軌道に応じたサンプリング点が障害物に衝突（貫通）しないか検証
        List<double[]> samplePoints = new ArrayList<>();
        if (behavior instanceof Behaviors.CircleBehavior) {
            double radius = 1.0;
            for (int i = 0; i < 8; i++) {
                double angle = 2.0 * Math.PI * i / 8.0;
                samplePoints.add(new double[]{x + radius * Math.cos(angle), y + radius * Math.sin(angle)});
            }
        } else if (behavior instanceof Behaviors.SweepBehavior) {
            samplePoints.add(new double[]{x, y});
            samplePoints.add(new double[]{x + 0.7, y});
            samplePoints.add(new double[]{x, y + 1.0});
            samplePoints.add(new double[]{x + 0.7, y + 1.0});
        } else if (behavior instanceof Behaviors.LoopPatrolBehavior) {
            samplePoints.add(new double[]{2.0, 2.0});
            samplePoints.add(new double[]{5.0, 2.0});
            samplePoints.add(new double[]{5.0, 5.0});
            samplePoints.add(new double[]{2.0, 5.0});
        } else {
            samplePoints.add(new double[]{x, y});
        }

        for (double[] pt : samplePoints) {
            for (core.FieldObstacle obs : fieldObstacles) {
                if (obs.collideOBB(pt[0], pt[1], 0.0, r) != null) {
                    return false; // 障害物と交差するため不成立
                }
            }
        }

        return true;
    }

    @Override
    public void updateBehaviors(core.SimulationEngine engine, double dt) {
        boolean isTimerRunning = engine.isTimerStarted();
        if (isTimerRunning) {
            RobotState playerState = engine.getCurrentState();
            List<NpcState> npcStates = getNpcStates();
            for (NpcRobot npc : npcs) {
                // 自分を除外したコンテキスト情報を作成
                List<NpcState> otherNpcs = new ArrayList<>();
                for (NpcState ns : npcStates) {
                    if (ns.id != npc.getId()) {
                        otherNpcs.add(ns);
                    }
                }
                // ボール状態を取得（練習モードのボール回収追従用、無ければ空リスト）
                List<core.BallState> balls;
                try {
                    core.IBallSimulation bs = engine.getBallSimulation();
                    balls = (bs != null) ? bs.getBallStates() : java.util.Collections.emptyList();
                } catch (Exception ex) {
                    balls = java.util.Collections.emptyList();
                }
                // 実位置基準で次の挙動を計画（目標指令を決定）
                PracticeContext ctx = new PracticeContext(7.0, playerState.x, playerState.y, playerState.heading, otherNpcs, random, balls);
                npc.planBehavior(engine, ctx, dt);
            }
        }
    }

    @Override
    public void resolveCollisions(core.SimulationEngine engine, double dt) {
        playerCollidedThisFrame = false;
        collidedNpcId = -1;
        collisionPX = 0.0;
        collisionPY = 0.0;

        boolean isTimerRunning = engine.isTimerStarted();
        RobotState playerState = engine.getCurrentState();

        // 1. NPCの運動積分（移動）を先に実行する
        if (isTimerRunning) {
            for (NpcRobot npc : npcs) {
                npc.integrate(dt);
            }
        }

        // 2. 多体衝突の反復解消 (5回反復)
        // タイマーが停止・終了していても、めり込みを解消するため衝突解消のみは実行する (凍結前めり込み解消保証)
        double px = playerState.x;
        double py = playerState.y;
        double pHeading = playerState.heading;
        double colRatio = playerConfig.playerPushRatio;

        // プレイヤーの累積押し戻し量
        double accumulatedPlayerDx = 0.0;
        double accumulatedPlayerDy = 0.0;

        // 強制速度クランプ用の法線ベクトル (最後に発生した衝突のMTVを保持)
        double clampDx = 0.0;
        double clampDy = 0.0;

        for (int iter = 0; iter < 5; iter++) {
            // A. NPC同士の衝突解消
            for (int i = 0; i < npcs.size(); i++) {
                NpcRobot npc1 = npcs.get(i);
                for (int j = i + 1; j < npcs.size(); j++) {
                    NpcRobot npc2 = npcs.get(j);
                    
                    // 精密化されたブロードフェーズ判定 (rA + rB 閾値判定)
                    double rA = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
                    double rB = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
                    double centerDist = Math.hypot(npc1.getX() - npc2.getX(), npc1.getY() - npc2.getY());
                    if (centerDist > (rA + rB)) {
                        continue;
                    }

                    CollisionResult res = checkObbCollision(
                        npc1.getX(), npc1.getY(), npc1.getHeading(), playerConfig.robotSize,
                        npc2.getX(), npc2.getY(), npc2.getHeading(), playerConfig.robotSize
                    );
                    if (res.collided) {
                        // NPC同士は 0.5 ずつ逆方向に押し戻し、かつ速度をクランプ
                        double dx1 = 0.5 * res.mtvX;
                        double dy1 = 0.5 * res.mtvY;
                        npc1.overridePosition(npc1.getX() + dx1, npc1.getY() + dy1, dx1, dy1);

                        double dx2 = -0.5 * res.mtvX;
                        double dy2 = -0.5 * res.mtvY;
                        npc2.overridePosition(npc2.getX() + dx2, npc2.getY() + dy2, dx2, dy2);
                    }
                }
            }

            // A.5 NPCとフィールド障害物の衝突解消
            List<core.FieldObstacle> fieldObstacles = engine.getFieldObstacles();
            if (fieldObstacles != null) {
                double npcHalfSize = playerConfig.robotSize / 2.0;
                for (NpcRobot npc : npcs) {
                    for (core.FieldObstacle obs : fieldObstacles) {
                        core.CollisionResult res = obs.collideOBB(npc.getX(), npc.getY(), npc.getHeading(), npcHalfSize);
                        if (res != null) {
                            // NPC側を100%押し戻す
                            npc.overridePosition(npc.getX() + res.mtvX, npc.getY() + res.mtvY, res.mtvX, res.mtvY);
                        }
                    }
                }
            }

            // B. プレイヤーとNPCの衝突解消
            for (NpcRobot npc : npcs) {
                // 精密化されたブロードフェーズ判定 (rP + rN 閾値判定)
                double rP = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
                double rN = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
                double centerDist = Math.hypot(px - npc.getX(), py - npc.getY());
                if (centerDist > (rP + rN)) {
                    continue;
                }

                CollisionResult res = checkObbCollision(
                    px, py, pHeading, playerConfig.robotSize,
                    npc.getX(), npc.getY(), npc.getHeading(), playerConfig.robotSize
                );
                if (res.collided) {
                    playerCollidedThisFrame = true;
                    collidedNpcId = npc.getId();
                    collisionPX = res.cPointX;
                    collisionPY = res.cPointY;

                    // 重み付け押し戻し (プレイヤー: playerPushRatio, NPC: 1.0 - playerPushRatio)
                    double dxP = colRatio * res.mtvX;
                    double dyP = colRatio * res.mtvY;
                    px += dxP;
                    py += dyP;
                    accumulatedPlayerDx += dxP;
                    accumulatedPlayerDy += dyP;

                    clampDx = dxP;
                    clampDy = dyP;

                    double dxN = -(1.0 - colRatio) * res.mtvX;
                    double dyN = -(1.0 - colRatio) * res.mtvY;
                    npc.overridePosition(npc.getX() + dxN, npc.getY() + dyN, dxN, dyN);
                }
            }

            // C. プレイヤーの壁クランプ
            double pRadius = playerConfig.robotSize / 2.0;
            double pCos = Math.cos(pHeading);
            double pSin = Math.sin(pHeading);
            double pHalf = pRadius * (Math.abs(pCos) + Math.abs(pSin));
            
            double origPx = px;
            double origPy = py;
            px = Math.max(pHalf, Math.min(7.0 - pHalf, px));
            py = Math.max(pHalf, Math.min(7.0 - pHalf, py));

            double wallDx = px - origPx;
            double wallDy = py - origPy;
            if (Math.hypot(wallDx, wallDy) > 1e-6) {
                accumulatedPlayerDx += wallDx;
                accumulatedPlayerDy += wallDy;
                clampDx = wallDx;
                clampDy = wallDy;
            }
        }

        // D. 保険ガード: 5回反復しても解消しきれない残留貫通がある場合、位置補正は行わないが速度クランプのみ維持する
        for (int i = 0; i < npcs.size(); i++) {
            NpcRobot npc1 = npcs.get(i);
            for (int j = i + 1; j < npcs.size(); j++) {
                NpcRobot npc2 = npcs.get(j);
                
                double rA = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
                double rB = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
                if (Math.hypot(npc1.getX() - npc2.getX(), npc1.getY() - npc2.getY()) > (rA + rB)) {
                    continue;
                }

                CollisionResult res = checkObbCollision(
                    npc1.getX(), npc1.getY(), npc1.getHeading(), playerConfig.robotSize,
                    npc2.getX(), npc2.getY(), npc2.getHeading(), playerConfig.robotSize
                );
                if (res.collided) {
                    npc1.clampVelocity(res.mtvX, res.mtvY);
                    npc2.clampVelocity(-res.mtvX, -res.mtvY);
                }
            }
        }

        // プレイヤーとNPCの残留衝突ガード
        for (NpcRobot npc : npcs) {
            double rP = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
            double rN = (playerConfig.robotSize / 2.0) * Math.sqrt(2);
            if (Math.hypot(px - npc.getX(), py - npc.getY()) > (rP + rN)) {
                continue;
            }

            CollisionResult res = checkObbCollision(
                px, py, pHeading, playerConfig.robotSize,
                npc.getX(), npc.getY(), npc.getHeading(), playerConfig.robotSize
            );
            if (res.collided) {
                npc.clampVelocity(-res.mtvX, -res.mtvY);
                
                playerCollidedThisFrame = true;
                collidedNpcId = npc.getId();
                collisionPX = res.cPointX;
                collisionPY = res.cPointY;

                clampDx = res.mtvX;
                clampDy = res.mtvY;
            }
        }

        // プレイヤーの車軸位置 A を補正座標から逆算 (A/v 整合維持)
        double pCos = Math.cos(pHeading);
        double pSin = Math.sin(pHeading);
        double pxA = px + playerConfig.pivotOffsetD * pCos;
        double pyA = py + playerConfig.pivotOffsetD * pSin;

        // 最終的なプレイヤーの状態を SimulationEngine に反映
        engine.updateCollisionState(
            playerCollidedThisFrame,
            collidedNpcId,
            collisionPX,
            collisionPY,
            px,
            py,
            pxA,
            pyA,
            clampDx,
            clampDy,
            dt
        );
    }

    /**
     * 後方互換およびテスト用メソッド。
     */
    @Deprecated
    public PlayerCollisionResult update(double dt, RobotState playerState, boolean isTimerRunning) {
        // ダミーエンジンを作成して物理状態をシミュレート
        core.SimulationEngine dummyEngine = new core.SimulationEngine(playerConfig, new input.InputSource() {
            @Override public double getTranslationInput() { return 0; }
            @Override public double getRotationInput() { return 0; }
            @Override public void update() {}
            @Override public boolean isIntakePressed() { return false; }
            @Override public boolean isEjectPressed() { return false; }
            @Override public boolean isShootPressed() { return false; }
        });
        dummyEngine.updateCollisionState(false, -1, 0, 0, playerState.x, playerState.y, playerState.xA, playerState.yA, 0, 0, 0);

        if (isTimerRunning) {
            updateBehaviors(dummyEngine, dt);
        }
        resolveCollisions(dummyEngine, dt);

        RobotState nextState = dummyEngine.getCurrentState();
        return new PlayerCollisionResult(
            playerCollidedThisFrame,
            collidedNpcId,
            collisionPX,
            collisionPY,
            nextState.x,
            nextState.y,
            nextState.xA,
            nextState.yA
        );
    }

    /**
     * NPCを描画します。プレイヤーの描画と同様の基準（車軸中心 A オフセット）でオレンジ系半透明にて描画します。
     */
    public void render(Graphics2D g2, double scale, int offsetX, int offsetY, double fieldSize, boolean blueView) {
        for (NpcRobot npc : npcs) {
            double sax = toScreenXA(npc, scale, offsetX, blueView);
            double say = toScreenYA(npc, scale, offsetY, blueView);
            double sHeading = toScreenHeading(npc.getHeading(), blueView);

            AffineTransform old = g2.getTransform();
            g2.translate(sax, say);
            g2.rotate(sHeading);

            double wPx = (playerConfig.trackWidth / 2.0) * scale;
            double wheelLength = 0.12 * scale;
            double wheelWidth = 0.03 * scale;

            // 1. 車輪の描画
            g2.setColor(new Color(60, 50, 40));
            g2.fill(new Rectangle2D.Double(-wheelLength / 2.0, -wPx - wheelWidth / 2.0, wheelLength, wheelWidth));
            g2.fill(new Rectangle2D.Double(-wheelLength / 2.0, wPx - wheelWidth / 2.0, wheelLength, wheelWidth));

            // 車軸の描画
            g2.setColor(new Color(110, 90, 80));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new Line2D.Double(0, -wPx, 0, wPx));

            // 2. ボディの描画
            double dPx = playerConfig.pivotOffsetD * scale;
            double sizePx = playerConfig.robotSize * scale;

            // 衝突中かどうか
            boolean isColliding = playerCollidedThisFrame && collidedNpcId == npc.getId();
            Color bodyOutline = isColliding ? new Color(255, 80, 80, 220) : new Color(255, 160, 60, 200);
            Color bodyFill = isColliding ? new Color(200, 40, 40, 100) : new Color(220, 120, 30, 90);

            g2.setColor(bodyOutline);
            g2.setStroke(new BasicStroke(2.5f));
            g2.draw(new Rectangle2D.Double(-dPx - sizePx / 2.0, -sizePx / 2.0, sizePx, sizePx));
            g2.setColor(bodyFill);
            g2.fill(new Rectangle2D.Double(-dPx - sizePx / 2.0, -sizePx / 2.0, sizePx, sizePx));

            // 3. 進行方向の矢印
            Path2D.Double arrow = new Path2D.Double();
            double arrowSize = 0.13 * scale;
            double frontEdge = -dPx + sizePx / 2.0;
            arrow.moveTo(frontEdge - 4, 0);
            arrow.lineTo(frontEdge - arrowSize - 4, -arrowSize / 2.0);
            arrow.lineTo(frontEdge - arrowSize - 4, arrowSize / 2.0);
            arrow.closePath();
            g2.setColor(new Color(255, 240, 220, 200));
            g2.fill(arrow);

            g2.setTransform(old);

            // 4. IDと挙動のデバッグテキスト（グローバル座標系で描画）
            double gx;
            double gy;
            if (blueView) {
                gx = offsetX + (7.0 - npc.getY()) * scale;
                gy = offsetY + npc.getX() * scale;
            } else {
                gx = offsetX + npc.getY() * scale;
                gy = offsetY + (7.0 - npc.getX()) * scale;
            }
            g2.setColor(new Color(240, 200, 160, 220));
            g2.setFont(new Font("Monospaced", Font.BOLD, 10));
            String name = npc.getState().behaviorName.replace("Behavior", "");
            g2.drawString("NPC#" + npc.getId() + ":" + name, (int)(gx - sizePx / 2.0), (int)(gy - sizePx / 2.0 - 5));
        }
    }

    /**
     * OBB-OBB衝突判定および最小押し出しベクトル（MTV）を算出します。
     * SAT (分離軸定理) を用います。
     */
    public static CollisionResult checkObbCollision(double ax, double ay, double aHeading, double aSize,
                                                     double bx, double by, double bHeading, double bSize) {
        double[][] cornersA = getCorners(ax, ay, aHeading, aSize);
        double[][] cornersB = getCorners(bx, by, bHeading, bSize);

        // 投影軸 (Aのローカル軸2本、Bのローカル軸2本)
        double[][] axes = {
            {Math.cos(aHeading), Math.sin(aHeading)},
            {-Math.sin(aHeading), Math.cos(aHeading)},
            {Math.cos(bHeading), Math.sin(bHeading)},
            {-Math.sin(bHeading), Math.cos(bHeading)}
        };

        double minOverlap = Double.MAX_VALUE;
        double mtvX = 0.0;
        double mtvY = 0.0;

        for (double[] axis : axes) {
            double len = Math.hypot(axis[0], axis[1]);
            double nx = axis[0] / len;
            double ny = axis[1] / len;

            double[] projA = project(cornersA, nx, ny);
            double[] projB = project(cornersB, nx, ny);

            double overlap = getOverlap(projA, projB);
            if (overlap <= 0.0) {
                return new CollisionResult(false, 0.0, 0.0, 0.0, 0.0);
            }

            if (overlap < minOverlap) {
                minOverlap = overlap;
                double dx = ax - bx;
                double dy = ay - by;
                if (dx * nx + dy * ny < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                mtvX = nx * overlap;
                mtvY = ny * overlap;
            }
        }

        double cPointX = (ax + bx) / 2.0;
        double cPointY = (ay + by) / 2.0;

        return new CollisionResult(true, mtvX, mtvY, cPointX, cPointY);
    }

    private static double[][] getCorners(double cx, double cy, double heading, double size) {
        double r = size / 2.0;
        double cos = Math.cos(heading);
        double sin = Math.sin(heading);
        return new double[][]{
            {cx + r * cos - r * sin, cy + r * sin + r * cos},
            {cx - r * cos - r * sin, cy - r * sin + r * cos},
            {cx - r * cos + r * sin, cy - r * sin - r * cos},
            {cx + r * cos + r * sin, cy + r * sin + r * cos}
        };
    }

    private static double[] project(double[][] corners, double nx, double ny) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] c : corners) {
            double val = c[0] * nx + c[1] * ny;
            if (val < min) min = val;
            if (val > max) max = val;
        }
        return new double[]{min, max};
    }

    private static double getOverlap(double[] projA, double[] projB) {
        return Math.max(0.0, Math.min(projA[1], projB[1]) - Math.max(projA[0], projB[0]));
    }

    public List<NpcState> getNpcStates() {
        List<NpcState> list = new ArrayList<>();
        for (NpcRobot r : npcs) {
            list.add(r.getState());
        }
        return list;
    }

    public long getCurrentSeed() {
        return currentSeed;
    }

    // ----------------------------------------------------
    // 衝突判定の結果を表すデータ構造
    // ----------------------------------------------------
    public static class CollisionResult {
        public final boolean collided;
        public final double mtvX;
        public final double mtvY;
        public final double cPointX;
        public final double cPointY;

        public CollisionResult(boolean collided, double mtvX, double mtvY, double cPointX, double cPointY) {
            this.collided = collided;
            this.mtvX = mtvX;
            this.mtvY = mtvY;
            this.cPointX = cPointX;
            this.cPointY = cPointY;
        }
    }

    // プレイヤー位置補正用クラス
    public static class PlayerCollisionResult {
        public final boolean collided;
        public final int npcId;
        public final double cPointX;
        public final double cPointY;
        public final double px;
        public final double py;
        public final double pxA;
        public final double pyA;

        public PlayerCollisionResult(boolean collided, int npcId, double cPointX, double cPointY,
                                     double px, double py, double pxA, double pyA) {
            this.collided = collided;
            this.npcId = npcId;
            this.cPointX = cPointX;
            this.cPointY = cPointY;
            this.px = px;
            this.py = py;
            this.pxA = pxA;
            this.pyA = pyA;
        }
    }

    @Override
    public List<RobotOBB> getNpcOBBs() {
        List<RobotOBB> obbs = new ArrayList<>();
        double halfSize = playerConfig.robotSize / 2.0;
        for (NpcRobot npc : npcs) {
            double heading = npc.getHeading();
            double v = npc.getState().v;
            double vx = v * Math.cos(heading);
            double vy = v * Math.sin(heading);
            obbs.add(new RobotOBB(npc.getX(), npc.getY(), heading, halfSize, vx, vy));
        }
        return obbs;
    }

    private double toScreenXA(NpcRobot npc, double scale, int offsetX, boolean blueView) {
        if (blueView) {
            return offsetX + (7.0 - npc.getYA()) * scale;
        } else {
            return offsetX + npc.getYA() * scale;
        }
    }

    private double toScreenYA(NpcRobot npc, double scale, int offsetY, boolean blueView) {
        if (blueView) {
            return offsetY + npc.getXA() * scale;
        } else {
            return offsetY + (7.0 - npc.getXA()) * scale;
        }
    }

    private double toScreenHeading(double heading, boolean blueView) {
        if (blueView) {
            return Math.atan2(Math.cos(heading), -Math.sin(heading));
        } else {
            return Math.atan2(-Math.cos(heading), Math.sin(heading));
        }
    }
}
