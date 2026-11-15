package core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 空間グリッド (Spatial Hashing) と ForkJoinPool / 並列ストリームを用いた
 * 高速かつ高精度なボール物理シミュレーションの実装クラス。
 */
public class BallSimulation implements IBallSimulation {
    private static class GoalSegment {
        final double ax, ay;
        final double bx, by;
        final double normalAngle;
        
        GoalSegment(double ax, double ay, double bx, double by, double normalAngle) {
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
            this.normalAngle = normalAngle;
        }
    }

    // 白ゴール（Fire Shield 斜辺）の線分定義
    private static class WhiteGoalSegment {
        final double ax, ay;
        final double bx, by;

        WhiteGoalSegment(double ax, double ay, double bx, double by) {
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
        }
    }

    private RobotConfig config;
    private List<FieldObstacle> obstacles;
    private final List<BallState> balls = new ArrayList<>();
    private final List<BallState> activeBalls = new ArrayList<>();
    private final List<BallState> heldBalls = new ArrayList<>();
    
    private final AtomicInteger contactCount = new AtomicInteger(0);
    private final AtomicBoolean contactInFrame = new AtomicBoolean(false);
    private BallEventListener eventListener;

    private int heldBallCount = 0;
    private int totalCollectedCount = 0;
    private boolean intakeActive = false;

    // --- シューター（射出）用変数 ---
    private int scoreCount = 0;
    private int shotAttemptCount = 0;
    private double shootTimer = 0.0;
    private boolean shootActive = false;
    private Random shootRandom;

    // --- 白ゴール（Fire Shield 斜辺）用変数 ---
    private final List<WhiteGoalSegment> whiteGoalSegments = new ArrayList<>();
    private int whiteScoreCount = 0;
    private Random whiteRandom;

    // --- 3v3 対戦用の複数ロボット管理マップ ---
    private final java.util.Map<Integer, List<BallState>> heldBallsMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> heldBallCountMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> scoreCountMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> shotAttemptCountMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, Double> shootTimerMap = new java.util.HashMap<>();
    private final java.util.Map<Integer, Double> ejectTimerMap = new java.util.HashMap<>();
    
    // 空間グリッドパラメータ
    private static final int GRID_SIZE = 70; // 7.0m / 0.1m = 70 セル
    private static final double CELL_SIZE = 0.1; // セルサイズはボール直径 0.1m と同一

    // 禁止エリアワープの有効フラグ（デフォルトOFF。ボールばらまき後に必要に応じてONにする）
    private boolean warpEnabled = false;

    public void setWarpEnabled(boolean enabled) {
        this.warpEnabled = enabled;
    }

    public boolean isWarpEnabled() {
        return warpEnabled;
    }

    public void setEventListener(BallEventListener listener) {
        this.eventListener = listener;
    }

    @Override
    public void init(RobotConfig config, List<FieldObstacle> obstacles) {
        this.config = config;
        this.obstacles = obstacles;
        this.balls.clear();
        this.activeBalls.clear();
        this.heldBalls.clear();
        this.contactCount.set(0);
        this.contactInFrame.set(false);
        this.heldBallCount = 0;
        this.totalCollectedCount = 0;
        this.intakeActive = false;
        this.scoreCount = 0;
        this.shotAttemptCount = 0;
        this.shootTimer = 0.0;
        this.shootActive = false;
        this.whiteScoreCount = 0;
        this.whiteGoalSegments.clear();

        // 3v3 対戦用マップの初期化
        this.heldBallsMap.clear();
        this.heldBallsMap.put(1, this.heldBalls);
        this.heldBallCountMap.clear();
        this.heldBallCountMap.put(1, 0);
        this.scoreCountMap.clear();
        this.scoreCountMap.put(1, 0);
        this.shotAttemptCountMap.clear();
        this.shotAttemptCountMap.put(1, 0);
        this.shootTimerMap.clear();
        this.shootTimerMap.put(1, 0.0);
        this.ejectTimerMap.clear();
        this.ejectTimerMap.put(1, 0.0);

        // 決定論的乱数シードの適用
        Random rnd = new Random();
        long seedToUse;
        if (config.ballSeed != null) {
            seedToUse = config.ballSeed;
            rnd.setSeed(seedToUse);
        } else {
            seedToUse = rnd.nextLong();
            rnd.setSeed(seedToUse);
        }
        System.out.println("ボール物理初期シード値: " + seedToUse);
        this.shootRandom = new Random(seedToUse + 999L);
        this.whiteRandom = new Random(seedToUse + 7777L);

        // 放出位置の動的算出: Suppression Unit の上辺（台形の狭い辺、フィールド内側）
        double minX = 2.054;
        double maxX = 4.946;
        double yEmit = 0.747;

        if (obstacles != null) {
            for (FieldObstacle obs : obstacles) {
                if ("suppression_unit".equals(obs.id()) && obs instanceof PolygonObstacle) {
                    PolygonObstacle poly = (PolygonObstacle) obs;
                    double[][] vertices = poly.getVertices();
                    double yMax = -Double.MAX_VALUE;
                    for (double[] v : vertices) {
                        if (v[1] > yMax) {
                            yMax = v[1];
                        }
                    }
                    final double finalYMax = yMax;
                    double xMin = Double.MAX_VALUE;
                    double xMax = -Double.MAX_VALUE;
                    for (double[] v : vertices) {
                        if (v[1] > finalYMax - 0.01) {
                            if (v[0] < xMin) xMin = v[0];
                            if (v[0] > xMax) xMax = v[0];
                        }
                    }
                    if (xMin < xMax) {
                        minX = xMin;
                        maxX = xMax;
                        yEmit = yMax;
                    }
                    break;
                }
            }
        }

        // Suppression Unit 上辺の中心点
        double xCenter = (minX + maxX) / 2.0;

        // Fire Shield の白ゴール斜辺の動的抽出
        if (obstacles != null) {
            for (FieldObstacle obs : obstacles) {
                if (obs instanceof PolygonObstacle) {
                    String obsId = obs.id();
                    if ("fire_shield_left".equals(obsId) || "fire_shield_right".equals(obsId)) {
                        PolygonObstacle poly = (PolygonObstacle) obs;
                        double[][] verts = poly.getVertices();
                        // ポリゴンの簡易重心を計算
                        double gx = 0;
                        double gy = 0;
                        for (double[] v : verts) {
                            gx += v[0];
                            gy += v[1];
                        }
                        gx /= verts.length;
                        gy /= verts.length;

                        // フィールド中央 (3.5, 3.5) に面した最も長く斜めな辺を特定
                        double bestScore = -1.0;
                        int bestIdx = 0;
                        for (int i = 0; i < verts.length; i++) {
                            int j = (i + 1) % verts.length;
                            double edgeDx = verts[j][0] - verts[i][0];
                            double edgeDy = verts[j][1] - verts[i][1];
                            double len = Math.hypot(edgeDx, edgeDy);
                            if (len < 1e-9) continue;
                            
                            // 斜め度 (0.0〜1.0)
                            double absDx = Math.abs(edgeDx);
                            double absDy = Math.abs(edgeDy);
                            double maxD = Math.max(absDx, absDy);
                            double diagonality = (maxD > 1e-9) ? Math.min(absDx, absDy) / maxD : 0.0;
                            
                            // 仮の法線
                            double nx = edgeDy / len;
                            double ny = -edgeDx / len;
                            
                            // 重心から辺の中点へのベクトル
                            double mx = (verts[i][0] + verts[j][0]) / 2.0;
                            double my = (verts[i][1] + verts[j][1]) / 2.0;
                            double gmx = mx - gx;
                            double gmy = my - gy;
                            
                            // もし仮の法線が重心方向（内側）を向いていたら反転して外向きにする
                            if (nx * gmx + ny * gmy < 0) {
                                nx = -nx;
                                ny = -ny;
                            }
                            
                            // 辺の中点からフィールド中央 (3.5, 3.5) へのベクトルを正規化
                            double toCenterX = 3.5 - mx;
                            double toCenterY = 3.5 - my;
                            double toCenterLen = Math.hypot(toCenterX, toCenterY);
                            if (toCenterLen > 1e-9) {
                                toCenterX /= toCenterLen;
                                toCenterY /= toCenterLen;
                            }
                            
                            // 中央向きか
                            double facing = nx * toCenterX + ny * toCenterY;
                            if (facing > 0) {
                                double score = len * (0.5 + diagonality);
                                if (score > bestScore) {
                                    bestScore = score;
                                    bestIdx = i;
                                }
                            }
                        }
                        int j = (bestIdx + 1) % verts.length;
                        whiteGoalSegments.add(new WhiteGoalSegment(
                            verts[bestIdx][0], verts[bestIdx][1],
                            verts[j][0], verts[j][1]
                        ));
                        double actualLen = Math.hypot(verts[j][0] - verts[bestIdx][0], verts[j][1] - verts[bestIdx][1]);
                        System.out.println("[WG INIT] " + obsId + "=(" +
                            String.format("%.3f,%.3f", verts[bestIdx][0], verts[bestIdx][1]) + ")->(" +
                            String.format("%.3f,%.3f", verts[j][0], verts[j][1]) + ") len=" + String.format("%.3f", actualLen));
                    }
                }
            }
        }

        // === グリッドベースのボール初期配置 ===
        // 旧実装は O(ballCount × retry × ballCount) ≈ 12.5M 演算で
        // 対戦開始直後に数秒のカクつきを起こしていた。
        // 半径 ballEmitRadius の円内をボール直径相当のセルに分割し、
        // 各セルへ最大1個ずつ配置することで重複チェックの全走査を排除する
        // (計算量を実質 O(ballCount) に削減)。セル内にジッタを加えて
        // 見た目のランダム性とゲーム性を維持する。
        {
            final double emitR = Math.max(0.0, config.ballEmitRadius);
            // セルピッチ。ボール直径 0.1m より僅かに大きくして重なりを抑制
            final double pitch = 0.11;
            // セル中心からのジッタ最大量（重ならない範囲）
            final double jitter = (pitch - 0.1) * 0.5 * 0.9;

            // 円内に入る格子セルの中心候補を列挙
            int cellsPerSide = (emitR > 0) ? (int) Math.ceil(emitR / pitch) : 0;
            List<double[]> candidates = new ArrayList<>();
            for (int gy = -cellsPerSide; gy <= cellsPerSide; gy++) {
                for (int gx = -cellsPerSide; gx <= cellsPerSide; gx++) {
                    double cxp = gx * pitch;
                    double cyp = gy * pitch;
                    // 円内のセルのみ採用（中心が半径内）
                    if (cxp * cxp + cyp * cyp <= emitR * emitR + 1e-9) {
                        candidates.add(new double[]{cxp, cyp});
                    }
                }
            }
            // 中心から外側へ向かって埋めるため距離順にソート
            candidates.sort((a, b) ->
                Double.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));

            // ボール数がセル候補数より多い場合に備え、足りない分は中心付近に重ねる
            for (int i = 0; i < config.ballCount; i++) {
                double offX, offY;
                if (!candidates.isEmpty() && i < candidates.size()) {
                    double[] c = candidates.get(i);
                    offX = c[0] + (rnd.nextDouble() - 0.5) * 2.0 * jitter;
                    offY = c[1] + (rnd.nextDouble() - 0.5) * 2.0 * jitter;
                } else {
                    // 候補を使い切った場合は円内ランダム配置（稀なフォールバック）
                    double angle = rnd.nextDouble() * 2 * Math.PI;
                    double r = rnd.nextDouble() * emitR;
                    offX = r * Math.cos(angle);
                    offY = r * Math.sin(angle);
                }

                // ジッタ後も放出半径 emitR 内に厳密に収める（中心からの距離をクランプ）
                double offLen = Math.hypot(offX, offY);
                if (offLen > emitR && offLen > 1e-9) {
                    double s = emitR / offLen;
                    offX *= s;
                    offY *= s;
                }

                double bx = xCenter + offX;
                double by = yEmit + offY;

                // フィールド外（壁の外）に出ないようクランプ
                bx = Math.max(0.05, Math.min(6.95, bx));
                by = Math.max(0.05, Math.min(6.95, by));

                // 初期速度：vy = min〜max の一様乱数、vx = vy * tan(±30°) のランダムばらつき
                double vy = config.ballMinEmitSpeed + rnd.nextDouble() * (config.ballMaxEmitSpeed - config.ballMinEmitSpeed);
                double dirAngle = (rnd.nextDouble() - 0.5) * Math.toRadians(60.0); // ±30度
                double vx = vy * Math.tan(dirAngle);

                BallState ball = new BallState(i, bx, by, vx, vy);
                balls.add(ball);
                activeBalls.add(ball);
            }
        }
    }

    @Override
    public void update(double dt, RobotOBB playerOBB, List<RobotOBB> npcOBBs) {
        if (activeBalls.isEmpty()) return;

        // 1. 空間グリッド再構築 (シングルスレッド、O(N))
        List<BallState>[] grid = new ArrayList[GRID_SIZE * GRID_SIZE];
        for (BallState ball : activeBalls) {
            int cx = (int)(ball.x / CELL_SIZE);
            int cy = (int)(ball.y / CELL_SIZE);
            cx = Math.max(0, Math.min(GRID_SIZE - 1, cx));
            cy = Math.max(0, Math.min(GRID_SIZE - 1, cy));
            int cellId = cy * GRID_SIZE + cx;
            if (grid[cellId] == null) {
                grid[cellId] = new ArrayList<>();
            }
            grid[cellId].add(ball);
        }

        // 2. 衝突ペア収集 (シングルスレッド)
        List<int[]> pairs = new ArrayList<>();
        // 重複ペアを避けるため、自身のセルと右、上、右上、右下のセルのみをチェック
        int[][] neighborOffsets = {
            {0, 0}, {1, 0}, {0, 1}, {1, 1}, {1, -1}
        };

        for (int cy = 0; cy < GRID_SIZE; cy++) {
            for (int cx = 0; cx < GRID_SIZE; cx++) {
                int cellId = cy * GRID_SIZE + cx;
                List<BallState> cellBalls = grid[cellId];
                if (cellBalls == null || cellBalls.isEmpty()) continue;

                for (int[] offset : neighborOffsets) {
                    int nx = cx + offset[0];
                    int ny = cy + offset[1];
                    if (nx < 0 || nx >= GRID_SIZE || ny < 0 || ny >= GRID_SIZE) continue;

                    int neighborId = ny * GRID_SIZE + nx;
                    List<BallState> neighborBalls = grid[neighborId];
                    if (neighborBalls == null || neighborBalls.isEmpty()) continue;

                    for (BallState bi : cellBalls) {
                        for (BallState bj : neighborBalls) {
                            if (cellId == neighborId) {
                                // 同一セル内での重複判定回避
                                if (bi.id < bj.id) {
                                    pairs.add(new int[]{bi.id, bj.id});
                                }
                            } else {
                                pairs.add(new int[]{bi.id, bj.id});
                            }
                        }
                    }
                }
            }
        }

        // 3. ボール同士の衝突応答計算 (決定論的seed再現性のためシングルスレッドで逐次処理)
        for (int iter = 0; iter < config.ballSolverIterations; iter++) {
            for (int[] pair : pairs) {
                // IDは balls リストに対応するインデックスと一致している前提
                BallState bi = balls.get(pair[0]);
                BallState bj = balls.get(pair[1]);
                
                double dx = bj.x - bi.x;
                double dy = bj.y - bi.y;
                double dist = Math.hypot(dx, dy);
                double minDist = 0.1; // 直径 0.1m (0.05m * 2)
                
                if (dist < minDist) {
                    if (dist < 1e-6) {
                        dx = 1e-4;
                        dist = 1e-4;
                    }
                    double nx = dx / dist;
                    double ny = dy / dist;
                    double rvx = bj.vx - bi.vx;
                    double rvy = bj.vy - bi.vy;
                    double v_rel = rvx * nx + rvy * ny;
                    
                    if (v_rel < 0) {
                        // impulse ベースの簡易弾性衝突
                        double impulse = -(1.0 + config.ballRestitution) * v_rel / (2.0 / config.ballMass);
                        double ix = impulse * nx;
                        double iy = impulse * ny;
                        bi.vx -= ix / config.ballMass;
                        bi.vy -= iy / config.ballMass;
                        bj.vx += ix / config.ballMass;
                        bj.vy += iy / config.ballMass;
                    }
                    
                    // 位置補正 (めり込みの幾何解消)
                    double overlap = minDist - dist;
                    bi.x -= nx * (overlap / 2.0);
                    bi.y -= ny * (overlap / 2.0);
                    bj.x += nx * (overlap / 2.0);
                    bj.y += ny * (overlap / 2.0);
                }
            }
        }

        // 4. 各ボールの位置更新 pos += vel * dt (並列ストリーム、転がり摩擦減衰の適用を含む)
        activeBalls.parallelStream().forEach(ball -> {
            double speed = Math.hypot(ball.vx, ball.vy);
            if (speed > 0) {
                ball.vx *= Math.max(0.0, 1.0 - config.ballRollingDamping * dt);
                ball.vy *= Math.max(0.0, 1.0 - config.ballRollingDamping * dt);
                if (Math.hypot(ball.vx, ball.vy) < config.ballStopThreshold) {
                    ball.vx = 0.0;
                    ball.vy = 0.0;
                }
            }
            ball.x += ball.vx * dt;
            ball.y += ball.vy * dt;
        });

        // 4.5. 白ゴール（Fire Shield 斜辺）接触判定（メインスレッド、決定論的演算、物理衝突より優先）
        if (!whiteGoalSegments.isEmpty()) {
            double ballRadius = 0.05;
            
            // 【デバッグ用】全アクティブボールと白ゴール線分の最小距離を計算
            double minDistToWhite = Double.MAX_VALUE;
            for (BallState ball : activeBalls) {
                for (WhiteGoalSegment seg : whiteGoalSegments) {
                    double segDx = seg.bx - seg.ax;
                    double segDy = seg.by - seg.ay;
                    double segLenSq = segDx * segDx + segDy * segDy;
                    if (segLenSq < 1e-12) continue;
                    double t = ((ball.x - seg.ax) * segDx + (ball.y - seg.ay) * segDy) / segLenSq;
                    t = Math.max(0.0, Math.min(1.0, t));
                    double closestX = seg.ax + t * segDx;
                    double closestY = seg.ay + t * segDy;
                    double dist = Math.hypot(ball.x - closestX, ball.y - closestY);
                    if (dist < minDistToWhite) {
                        minDistToWhite = dist;
                    }
                }
            }
            if (minDistToWhite < 0.3) {
                System.out.println("[WHITE GOAL DEBUG] min dist to white seg = " + String.format("%.4f", minDistToWhite) + " m (ballRadius=" + ballRadius + ")");
            }

            List<BallState> toRemove = new ArrayList<>();
            for (BallState ball : activeBalls) {
                boolean judged = false;
                for (WhiteGoalSegment seg : whiteGoalSegments) {
                    // ボール中心から線分 seg への最短距離を計算
                    double segDx = seg.bx - seg.ax;
                    double segDy = seg.by - seg.ay;
                    double segLenSq = segDx * segDx + segDy * segDy;
                    if (segLenSq < 1e-12) continue;
                    double t = ((ball.x - seg.ax) * segDx + (ball.y - seg.ay) * segDy) / segLenSq;
                    t = Math.max(0.0, Math.min(1.0, t));
                    double closestX = seg.ax + t * segDx;
                    double closestY = seg.ay + t * segDy;
                    double dist = Math.hypot(ball.x - closestX, ball.y - closestY);

                    if (dist <= ballRadius) {
                        // 接触判定！
                        if (whiteRandom.nextDouble() < config.whiteGoalSuccessProb) {
                            // 成功：ボール消滅
                            ball.scored = true;
                            ball.collected = true; // 描画からも除外
                            toRemove.add(ball);
                            whiteScoreCount++;
                            if (eventListener != null) {
                                eventListener.onBallEnterGoal(ball.id, "white");
                            }
                        } else {
                            // 失敗：接触点から半径 whiteGoalMissRadius 以内にテレポート
                            double r = whiteRandom.nextDouble() * config.whiteGoalMissRadius;
                            double angle = whiteRandom.nextDouble() * 2.0 * Math.PI;
                            double bx = closestX + r * Math.cos(angle);
                            double by = closestY + r * Math.sin(angle);
                            bx = Math.max(0.05, Math.min(6.95, bx));
                            by = Math.max(0.05, Math.min(6.95, by));
                            ball.x = bx;
                            ball.y = by;
                            // 軽い散乱初速
                            double scatterAngle = whiteRandom.nextDouble() * 2.0 * Math.PI;
                            double speed = whiteRandom.nextDouble() * 1.0;
                            ball.vx = speed * Math.cos(scatterAngle);
                            ball.vy = speed * Math.sin(scatterAngle);
                            if (eventListener != null) {
                                eventListener.onBallFired(ball.id);
                            }
                        }
                        judged = true;
                        break; // 1ボールにつき1判定/フレーム
                    }
                }
            }
            // 成功ボールを activeBalls から除外
            activeBalls.removeAll(toRemove);
        }

        // 5. 壁・障害物・ロボットクランプ (並列)
        activeBalls.parallelStream().forEach(ball -> {
            double radius = 0.05;
            double minPos = radius;
            double maxPos = 7.0 - radius;
            double rest = config.ballRestitution;
            double frict = config.ballFriction;

            // A. 外壁との衝突・クランプ
            if (ball.x < minPos) {
                ball.x = minPos;
                ball.vx = -ball.vx * rest;
                ball.vy = ball.vy * (1.0 - frict);
            } else if (ball.x > maxPos) {
                ball.x = maxPos;
                ball.vx = -ball.vx * rest;
                ball.vy = ball.vy * (1.0 - frict);
            }

            if (ball.y < minPos) {
                ball.y = minPos;
                ball.vy = -ball.vy * rest;
                ball.vx = ball.vx * (1.0 - frict);
            } else if (ball.y > maxPos) {
                ball.y = maxPos;
                ball.vy = -ball.vy * rest;
                ball.vx = ball.vx * (1.0 - frict);
            }

            // B. ロボット（OBB）との衝突判定・応答 (ロボットは質量無限大、ボールのみ100%押し戻す)
            boolean playerCollided = false;
            if (playerOBB != null) {
                CircleObstacle tempCircle = new CircleObstacle("ball_" + ball.id, "ball", new double[]{ball.x, ball.y}, radius, null);
                CollisionResult res = tempCircle.collideOBB(playerOBB.cx, playerOBB.cy, playerOBB.heading, playerOBB.halfSize);
                
                if (res != null) {
                    // ロボットがボールを100%押し戻す
                    ball.x -= res.mtvX;
                    ball.y -= res.mtvY;

                    double nx = -res.mtvX / res.depth;
                    double ny = -res.mtvY / res.depth;

                    // ロボットとの相対速度
                    double rvx = ball.vx - playerOBB.vx;
                    double rvy = ball.vy - playerOBB.vy;
                    double v_rel = rvx * nx + rvy * ny;
                    
                    if (v_rel < 0) {
                        double impulse_v = -(1.0 + config.ballRestitution) * v_rel;
                        ball.vx += impulse_v * nx;
                        ball.vy += impulse_v * ny;
                    }

                    // 接触統計の更新
                    if (!ball.wasCollidingWithRobot) {
                        ball.wasCollidingWithRobot = true;
                        contactCount.incrementAndGet();
                    }
                    contactInFrame.set(true);
                    playerCollided = true;
                }
            }
            if (!playerCollided) {
                ball.wasCollidingWithRobot = false;
            }

            // NPCロボットとの衝突判定 (質量無限大、統計カウントなし)
            if (npcOBBs != null && !npcOBBs.isEmpty()) {
                for (RobotOBB npc : npcOBBs) {
                    CircleObstacle tempCircle = new CircleObstacle("ball_" + ball.id, "ball", new double[]{ball.x, ball.y}, radius, null);
                    CollisionResult res = tempCircle.collideOBB(npc.cx, npc.cy, npc.heading, npc.halfSize);
                    
                    if (res != null) {
                        ball.x -= res.mtvX;
                        ball.y -= res.mtvY;

                        double nx = -res.mtvX / res.depth;
                        double ny = -res.mtvY / res.depth;

                        // 相対速度
                        double rvx = ball.vx - npc.vx;
                        double rvy = ball.vy - npc.vy;
                        double v_rel = rvx * nx + rvy * ny;
                        
                        if (v_rel < 0) {
                            double impulse_v = -(1.0 + config.ballRestitution) * v_rel;
                            ball.vx += impulse_v * nx;
                            ball.vy += impulse_v * ny;
                        }
                    }
                }
            }

            // C. 不動障害物との衝突判定・応答
            if (obstacles != null) {
                for (FieldObstacle obs : obstacles) {
                    if ("brace".equals(obs.category()) || "tape".equals(obs.category())) continue; // ボールはブレースやテープをすり抜ける

                    // 白ゴール斜辺付近での物理衝突スキップ（すり抜けを許容し、白判定に確実に当てる）
                    if ("fire_shield_left".equals(obs.id()) || "fire_shield_right".equals(obs.id())) {
                        boolean skipCollision = false;
                        for (WhiteGoalSegment seg : whiteGoalSegments) {
                            double segDx = seg.bx - seg.ax;
                            double segDy = seg.by - seg.ay;
                            double segLenSq = segDx * segDx + segDy * segDy;
                            if (segLenSq < 1e-12) continue;
                            double t = ((ball.x - seg.ax) * segDx + (ball.y - seg.ay) * segDy) / segLenSq;
                            t = Math.max(0.0, Math.min(1.0, t));
                            double closestX = seg.ax + t * segDx;
                            double closestY = seg.ay + t * segDy;
                            double dist = Math.hypot(ball.x - closestX, ball.y - closestY);
                            // ボールが白ゴール線分に非常に近い（0.08m以内）場合は衝突処理をスキップ
                            if (dist <= 0.08) {
                                skipCollision = true;
                                break;
                            }
                        }
                        if (skipCollision) {
                            continue;
                        }
                    }

                    CollisionResult obsRes = obs.collideCircle(ball.x, ball.y, radius);
                    if (obsRes != null) {
                        // 障害物は不動のため、ボール側を100%押し戻す (+MTV)
                        ball.x += obsRes.mtvX;
                        ball.y += obsRes.mtvY;

                        double nx = obsRes.mtvX / obsRes.depth;
                        double ny = obsRes.mtvY / obsRes.depth;

                        double v_rel = ball.vx * nx + ball.vy * ny;
                        if (v_rel < 0) {
                            double impulse_v = -(1.0 + config.ballRestitution) * v_rel;
                            ball.vx += impulse_v * nx;
                            ball.vy += impulse_v * ny;
                        }

                        if (eventListener != null) {
                            eventListener.onBallContact(ball.id, obs.id());
                        }
                    }
                }
                
                // 将来のゴール通過判定のプレースホルダー・コールバック
                // 例: Suppression Unit ポリゴンの領域内に入ったか判定
                if (ball.y < 0.747 && ball.x > 1.627 && ball.x < 5.373) {
                    if (eventListener != null) {
                        eventListener.onBallEnterGoal(ball.id, "suppression_unit");
                    }
                }
            }
        });

        // 6. 禁止エリア（フィールド外・障害物内部）に3秒以上いるボールをフィールド内にランダムワープ
        // warpEnabled が false（デフォルト）の場合はスキップ。ボール配置後に setWarpEnabled(true) で有効化する。
        if (warpEnabled && obstacles != null && !activeBalls.isEmpty()) {
            Random warpRng = this.shootRandom;

            for (BallState ball : activeBalls) {
                boolean inForbidden = false;

                // A. ボール中心がフィールド外
                if (ball.x < 0.0 || ball.x > 7.0 || ball.y < 0.0 || ball.y > 7.0) {
                    inForbidden = true;
                }

                // B. 障害物内部（Fire Shield, Suppression Unit のポリゴン内部）
                if (!inForbidden) {
                    for (FieldObstacle obs : obstacles) {
                        if ("tape".equals(obs.category()) || "brace".equals(obs.category())) continue;
                        double[][] vertices = obs.outlinePolygon();
                        if (vertices == null || vertices.length < 3) continue;
                        if (isPointInsidePolygon(ball.x, ball.y, vertices)) {
                            inForbidden = true;
                            break;
                        }
                    }
                }

                // C. Fire Shield斜辺の外側（壁側）三角形
                // 斜辺の中央向き法線の逆側（壁の角側）にあるボールは取り出せないので禁止エリアとする
                if (!inForbidden && !whiteGoalSegments.isEmpty()) {
                    for (WhiteGoalSegment seg : whiteGoalSegments) {
                        double edgeDx = seg.bx - seg.ax;
                        double edgeDy = seg.by - seg.ay;
                        double len = Math.hypot(edgeDx, edgeDy);
                        if (len < 1e-9) continue;
                        // 斜辺の中央向き法線（フィールド中央(3.5,3.5)側を正とする）
                        double nx = edgeDy / len;
                        double ny = -edgeDx / len;
                        double mx = (seg.ax + seg.bx) / 2.0;
                        double my = (seg.ay + seg.by) / 2.0;
                        if (nx * (3.5 - mx) + ny * (3.5 - my) < 0) { nx = -nx; ny = -ny; }
                        // 符号付き距離（負=壁側）かつ斜辺の高さ範囲内（下端より0.3m以上上）
                        double signedDist = nx * (ball.x - seg.ax) + ny * (ball.y - seg.ay);
                        if (signedDist < 0.0 && ball.y >= Math.min(seg.ay, seg.by) - 0.3) {
                            inForbidden = true;
                            break;
                        }
                    }
                }

                // タイマー加算 or リセット
                if (inForbidden) {
                    ball.forbiddenAreaTimer += dt;
                } else {
                    ball.forbiddenAreaTimer = 0.0;
                }

                // 3秒以上禁止エリアにいる場合のみワープ
                if (ball.forbiddenAreaTimer >= 3.0) {
                    ball.forbiddenAreaTimer = 0.0;
                    boolean placed = false;
                    for (int attempt = 0; attempt < 20; attempt++) {
                        double wx = 0.3 + warpRng.nextDouble() * 6.4;
                        double wy = 1.0 + warpRng.nextDouble() * 5.5;

                        boolean safe = true;
                        for (FieldObstacle obs : obstacles) {
                            if ("tape".equals(obs.category()) || "brace".equals(obs.category())) continue;
                            if (obs.collideCircle(wx, wy, 0.15) != null) {
                                safe = false;
                                break;
                            }
                        }
                        if (safe) {
                            ball.x = wx;
                            ball.y = wy;
                            ball.vx = 0.0;
                            ball.vy = 0.0;
                            placed = true;
                            break;
                        }
                    }
                    if (!placed) {
                        ball.x = 3.5 + (warpRng.nextDouble() - 0.5) * 2.0;
                        ball.y = 3.5 + (warpRng.nextDouble() - 0.5) * 2.0;
                        ball.vx = 0.0;
                        ball.vy = 0.0;
                    }
                }
            }
        }
    }

    @Override
    public List<BallState> getBallStates() {
        return balls;
    }

    @Override
    public int getContactCount() {
        return contactCount.get();
    }

    @Override
    public boolean hasContactInFrame() {
        return contactInFrame.get();
    }

    @Override
    public void resetContactFlag() {
        contactInFrame.set(false);
    }

    @Override
    public void processIntake(RobotOBB playerOBB, boolean intakeActive) {
        this.intakeActive = intakeActive;
        processIntake(1, playerOBB, intakeActive, config.intakeCapacity);
    }

    @Override
    public void processIntake(int robotId, RobotOBB robotOBB, boolean intakeActive, int capacity) {
        if (!intakeActive || robotOBB == null) {
            return;
        }

        double cosT = Math.cos(robotOBB.heading);
        double sinT = Math.sin(robotOBB.heading);
        
        // 前面辺の中心 F = G + halfSize * (cosθ, sinθ)
        double fx = robotOBB.cx + robotOBB.halfSize * cosT;
        double fy = robotOBB.cy + robotOBB.halfSize * sinT;

        List<BallState> held = heldBallsMap.computeIfAbsent(robotId, k -> new ArrayList<>());
        int heldCount = heldBallCountMap.getOrDefault(robotId, 0);

        List<BallState> toIntake = new ArrayList<>();
        // ボールごとに前面辺判定矩形内に入っているかチェック
        for (BallState ball : activeBalls) {
            // 保持数が上限に達している場合は回収しない
            if (heldCount + toIntake.size() >= capacity) {
                break;
            }

            // ベクトル V = B - F
            double dx = ball.x - fx;
            double dy = ball.y - fy;

            // 進行方向（法線方向）および接線方向（右方向）への投影
            double fwd = dx * cosT + dy * sinT;
            double side = dx * (-sinT) + dy * cosT;

            // 判定矩形: 幅 intakeFrontWidth, 奥行き intakeFrontDepth
            boolean inFront = (fwd >= 0.0) && (fwd <= config.intakeFrontDepth)
                           && (Math.abs(side) <= config.intakeFrontWidth / 2.0);

            if (inFront) {
                toIntake.add(ball);
            }
        }

        for (BallState ball : toIntake) {
            ball.collected = true;
            activeBalls.remove(ball);
            held.add(ball);
            heldCount++;
            if (robotId == 1) {
                totalCollectedCount++;
                this.heldBallCount = heldCount;
            }
            if (eventListener != null) {
                eventListener.onBallCollected(ball.id, heldCount);
            }
        }
        heldBallCountMap.put(robotId, heldCount);
    }

    @Override
    public int getHeldBallCount() {
        return heldBallCount;
    }

    @Override
    public int getTotalCollectedCount() {
        return totalCollectedCount;
    }

    @Override
    public boolean isIntakeActive() {
        return intakeActive;
    }

    @Override
    public void processShoot(RobotOBB playerOBB, boolean shootActive, boolean isBlueAlliance, double dt) {
        processShoot(1, playerOBB, shootActive, isBlueAlliance,
                     config.shootRange, config.shootBPS, config.shootSuccessProb, config.shootMissRadius,
                     false, dt);
    }

    @Override
    public void processShoot(int robotId, RobotOBB robotOBB, boolean shootActive, boolean isBlueAlliance,
                             double shootRange, double shootBPS, double shootSuccessProb, double shootMissRadius,
                             boolean isContacting, double dt) {
        if (robotOBB == null) {
            if (robotId == 1) {
                this.shootActive = false;
            }
            return;
        }

        // ゴール領域（台形）の頂点抽出
        double xBL = 1.627, yBL = 0.0;
        double xBR = 5.373, yBR = 0.0;
        double xTL = 2.054, yTL = 0.747;
        double xTR = 4.946, yTR = 0.747;

        if (obstacles != null) {
            for (FieldObstacle obs : obstacles) {
                if ("suppression_unit".equals(obs.id()) && obs instanceof PolygonObstacle) {
                    PolygonObstacle poly = (PolygonObstacle) obs;
                    double[][] vertices = poly.getVertices();
                    if (vertices.length >= 4) {
                        List<double[]> sortedByY = new ArrayList<>();
                        for (double[] v : vertices) {
                            sortedByY.add(v);
                        }
                        sortedByY.sort((v1, v2) -> Double.compare(v1[1], v2[1]));
                        
                        double[] b1 = sortedByY.get(0);
                        double[] b2 = sortedByY.get(1);
                        double[] t1 = sortedByY.get(2);
                        double[] t2 = sortedByY.get(3);
                        
                        if (b1[0] < b2[0]) {
                            xBL = b1[0]; yBL = b1[1];
                            xBR = b2[0]; yBR = b2[1];
                        } else {
                            xBL = b2[0]; yBL = b2[1];
                            xBR = b1[0]; yBR = b1[1];
                        }
                        
                        if (t1[0] < t2[0]) {
                            xTL = t1[0]; yTL = t1[1];
                            xTR = t2[0]; yTR = t2[1];
                        } else {
                            xTL = t2[0]; yTL = t2[1];
                            xTR = t1[0]; yTR = t1[1];
                        }
                    }
                    break;
                }
            }
        }

        double L = xTR - xTL;
        double xWL = xTL + L / 3.0;
        double xWR = xTL + 2.0 * L / 3.0;

        List<GoalSegment> targetSegments = new ArrayList<>();
        String goalId;

        if (isBlueAlliance) {
            // 青アライアンス: 右斜辺 + 上辺右1/3 (xが大きい側 = 青ゴール)
            double normal1 = Math.atan2(-(xBR - xTR), yBR - yTR);
            targetSegments.add(new GoalSegment(xTR, yTR, xBR, yBR, normal1));
            targetSegments.add(new GoalSegment(xWR, yTR, xTR, yTR, -Math.PI / 2.0));
            goalId = "blue_goal";
        } else {
            // 赤アライアンス: 左斜辺 + 上辺左1/3 (xが小さい側 = 赤ゴール)
            double normal1 = Math.atan2(xTL - xBL, -(yTL - yBL));
            targetSegments.add(new GoalSegment(xBL, yBL, xTL, yTL, normal1));
            targetSegments.add(new GoalSegment(xTL, yTL, xWL, yTL, -Math.PI / 2.0));
            goalId = "red_goal";
        }

        // 射出位置（前面辺の中心）
        double cosT = Math.cos(robotOBB.heading);
        double sinT = Math.sin(robotOBB.heading);
        double fx = robotOBB.cx + robotOBB.halfSize * cosT;
        double fy = robotOBB.cy + robotOBB.halfSize * sinT;

        // heading方向の半直線とゴールセグメントの交点を探す
        double closestX = 0.0;
        double closestY = 0.0;
        double minDistance = Double.MAX_VALUE;
        boolean rayIntersected = false;
        GoalSegment closestSeg = null;

        for (GoalSegment seg : targetSegments) {
            double vx = seg.bx - seg.ax;
            double vy = seg.by - seg.ay;
            double det = vx * sinT - vy * cosT;
            if (Math.abs(det) > 1e-9) {
                double t = ((seg.ay - fy) * vx - (seg.ax - fx) * vy) / det;
                double u = (cosT * (seg.ay - fy) - sinT * (seg.ax - fx)) / det;
                if (t >= 0.0 && u >= 0.0 && u <= 1.0) {
                    if (t < minDistance) {
                        minDistance = t;
                        closestX = fx + t * cosT;
                        closestY = fy + t * sinT;
                        rayIntersected = true;
                        closestSeg = seg;
                    }
                }
            }
        }

        boolean canShoot = false;
        if (rayIntersected && minDistance <= shootRange) {
            canShoot = true;
        } else {
            // heading方向のレイそのものは交差しないが、扇形（heading±shootAngleThreshold）が交差するか
            // ゴールセグメント上の点を細かくサンプリングして探索
            double bestDiff = Double.MAX_VALUE;
            double bestX = 0.0;
            double bestY = 0.0;
            double bestDist = Double.MAX_VALUE;
            GoalSegment bestSeg = null;
            boolean sectorIntersected = false;

            double shootAngleThreshold = (config != null) ? config.shootAngleThreshold : 0.2618;

            for (GoalSegment seg : targetSegments) {
                int samples = 30;
                for (int i = 0; i <= samples; i++) {
                    double u = (double) i / samples;
                    double px = seg.ax + u * (seg.bx - seg.ax);
                    double py = seg.ay + u * (seg.by - seg.ay);
                    double dist = Math.hypot(px - fx, py - fy);
                    if (dist <= shootRange) {
                        double angle = Math.atan2(py - fy, px - fx);
                        double diff = angle - robotOBB.heading;
                        while (diff > Math.PI) diff -= 2.0 * Math.PI;
                        while (diff < -Math.PI) diff += 2.0 * Math.PI;
                        
                        if (Math.abs(diff) <= shootAngleThreshold) {
                            sectorIntersected = true;
                            if (Math.abs(diff) < bestDiff) {
                                bestDiff = Math.abs(diff);
                                bestX = px;
                                bestY = py;
                                bestDist = dist;
                                bestSeg = seg;
                            }
                        }
                    }
                }
            }

            if (sectorIntersected) {
                canShoot = true;
                closestX = bestX;
                closestY = bestY;
                minDistance = bestDist;
                closestSeg = bestSeg;
            }
        }

        // マップからロボットの状態を取得
        double timer = shootTimerMap.getOrDefault(robotId, 0.0);
        List<BallState> held = heldBallsMap.computeIfAbsent(robotId, k -> new ArrayList<>());
        int heldCount = heldBallCountMap.getOrDefault(robotId, 0);
        int attemptCount = shotAttemptCountMap.getOrDefault(robotId, 0);
        int score = scoreCountMap.getOrDefault(robotId, 0);

        // タイマーの更新
        timer += dt;

        // 実効成功率の計算
        double effectiveProb = shootSuccessProb;
        if (canShoot && closestSeg != null) {
            // 距離ペナルティ
            double distPen = 1.0 - 0.7 * Math.pow(minDistance / shootRange, 2);
            distPen = Math.max(0.3, Math.min(1.0, distPen));

            // 角度ペナルティ
            double cosAngle = Math.cos(robotOBB.heading - closestSeg.normalAngle);
            double anglePen = cosAngle * cosAngle;

            // 接触ペナルティ
            double contactPen = isContacting ? 0.5 : 1.0;

            effectiveProb = shootSuccessProb * distPen * anglePen * contactPen;
            effectiveProb = Math.max(0.0, Math.min(1.0, effectiveProb));
        }

        if (canShoot && shootActive && heldCount > 0) {
            if (robotId == 1) {
                this.shootActive = true;
            }
            double shootInterval = 1.0 / shootBPS;
            int shotsThisFrame = 0;
            int maxShotsThisFrame = (int) Math.ceil(dt * shootBPS) + 1; // 1フレームの安全上限

            while (timer >= shootInterval && heldCount > 0) {
                timer -= shootInterval;

                // 保持しているリストから1つ取り出す
                if (!held.isEmpty()) {
                    BallState targetBall = held.remove(0);
                    heldCount--;
                    attemptCount++;
                    if (shootRandom.nextDouble() < effectiveProb) {
                        // 成功: scored = trueにして消滅 (activeBalls には戻さない)
                        targetBall.scored = true;
                        score++;
                        if (robotId == 1) {
                            this.scoreCount = score;
                        }
                        if (eventListener != null) {
                            eventListener.onBallEnterGoal(targetBall.id, goalId);
                        }
                    } else {
                        // 失敗: collected = false, scored = falseにして狙った最近接点周辺へ復帰
                        targetBall.collected = false;
                        targetBall.scored = false;

                        // 最近接点から半径 shootMissRadius 以内のランダム位置
                        double r = shootRandom.nextDouble() * shootMissRadius;
                        double angle = shootRandom.nextDouble() * 2.0 * Math.PI;
                        double bx = closestX + r * Math.cos(angle);
                        double by = closestY + r * Math.sin(angle);

                        // フィールド境界クランプ
                        bx = Math.max(0.05, Math.min(6.95, bx));
                        by = Math.max(0.05, Math.min(6.95, by));

                        targetBall.x = bx;
                        targetBall.y = by;

                        // 散乱初速設定（ランダム方向、0〜1.0 m/s 程度の軽い転がり）
                        double scatterAngle = shootRandom.nextDouble() * 2.0 * Math.PI;
                        double speed = shootRandom.nextDouble() * 1.0;
                        targetBall.vx = speed * Math.cos(scatterAngle);
                        targetBall.vy = speed * Math.sin(scatterAngle);

                        // activeBalls に戻す
                        activeBalls.add(targetBall);

                        if (eventListener != null) {
                            eventListener.onBallFired(targetBall.id);
                        }
                    }
                    
                    shotsThisFrame++;
                    if (shotsThisFrame >= maxShotsThisFrame) {
                        break;
                    }
                }
            }
        } else {
            if (robotId == 1) {
                this.shootActive = false;
            }
            // 射出していないときは、トリガーを引いた瞬間にすぐ射出できるようタイマーを shootInterval にクランプ
            double shootInterval = 1.0 / shootBPS;
            timer = shootInterval;
        }

        // マップに書き戻し
        shootTimerMap.put(robotId, timer);
        heldBallCountMap.put(robotId, heldCount);
        shotAttemptCountMap.put(robotId, attemptCount);
        scoreCountMap.put(robotId, score);

        // プレイヤー変数に同期
        if (robotId == 1) {
            this.heldBallCount = heldCount;
            this.shotAttemptCount = attemptCount;
            this.shootTimer = timer;
        }
    }

    @Override
    public int getScoreCount() {
        return scoreCount;
    }

    @Override
    public int getShotAttemptCount() {
        return shotAttemptCount;
    }

    @Override
    public boolean isShootActive() {
        return shootActive;
    }

    @Override
    public int getWhiteScoreCount() {
        return whiteScoreCount;
    }

    @Override
    public int getHeldBallCount(int robotId) {
        return heldBallCountMap.getOrDefault(robotId, 0);
    }

    @Override
    public int getScoreCount(int robotId) {
        return scoreCountMap.getOrDefault(robotId, 0);
    }

    @Override
    public int getShotAttemptCount(int robotId) {
        return shotAttemptCountMap.getOrDefault(robotId, 0);
    }

    @Override
    public int getWhiteScoreCount(int robotId) {
        return whiteScoreCount;
    }

    @Override
    public void checkWhiteGoalRobotContact(int robotId, RobotOBB robotOBB) {
        // 白ゴール近接の自動消費は廃止。ボールの直接接触判定のみに統一するため空実装とします。
    }

    @Override
    public void processEject(RobotOBB playerOBB, boolean ejectActive, double dt) {
        processEject(1, playerOBB, ejectActive, dt);
    }

    @Override
    public void processEject(int robotId, RobotOBB robotOBB, boolean ejectActive, double dt) {
        if (robotOBB == null) return;

        List<BallState> held = heldBallsMap.get(robotId);
        if (held == null || held.isEmpty()) return;

        double timer = ejectTimerMap.getOrDefault(robotId, 0.0);
        timer += dt;

        double ejectInterval = 0.2; // 0.2秒に1個

        // 前面（インテーク側）が白線（Fire Shield斜辺）に接触しているか（距離 0.1m 以内）の判定
        boolean isFrontContactingWhiteLine = false;
        double cosT = Math.cos(robotOBB.heading);
        double sinT = Math.sin(robotOBB.heading);
        double halfSize = robotOBB.halfSize;
        double fx = robotOBB.cx + halfSize * cosT;
        double fy = robotOBB.cy + halfSize * sinT;

        for (WhiteGoalSegment seg : whiteGoalSegments) {
            double segDx = seg.bx - seg.ax;
            double segDy = seg.by - seg.ay;
            double det = segDx * sinT - segDy * cosT;
            if (Math.abs(det) > 1e-9) {
                // レイ: (fx, fy) + t * (cosT, sinT)
                // 線分: (seg.ax, seg.ay) + u * (segDx, segDy)
                double t = ((seg.ay - fy) * segDx - (seg.ax - fx) * segDy) / det;
                double u = (cosT * (seg.ay - fy) - sinT * (seg.ax - fx)) / det;
                if (t >= 0.0 && t <= 0.1 && u >= 0.0 && u <= 1.0) {
                    isFrontContactingWhiteLine = true;
                    break;
                }
            }
        }

        if (ejectActive && isFrontContactingWhiteLine && !held.isEmpty()) {
            while (timer >= ejectInterval && !held.isEmpty()) {
                timer -= ejectInterval;

                BallState targetBall = held.remove(0);
                int currentCount = heldBallCountMap.getOrDefault(robotId, 0) - 1;
                heldBallCountMap.put(robotId, currentCount);

                // ロボットの前面位置（機体前面から少し前方）から放出
                double fxBall = robotOBB.cx + (robotOBB.halfSize + 0.02) * cosT;
                double fyBall = robotOBB.cy + (robotOBB.halfSize + 0.02) * sinT;

                targetBall.x = Math.max(0.05, Math.min(6.95, fxBall));
                targetBall.y = Math.max(0.05, Math.min(6.95, fyBall));

                // 初速：ロボット自身の速度 + 前方へ 1.0 m/s の逆転押し出し
                targetBall.vx = robotOBB.vx + 1.0 * cosT;
                targetBall.vy = robotOBB.vy + 1.0 * sinT;

                targetBall.collected = false;
                targetBall.scored = false;

                activeBalls.add(targetBall);

                if (robotId == 1) {
                    this.heldBallCount = currentCount;
                }

                if (eventListener != null) {
                    eventListener.onBallFired(targetBall.id);
                }
            }
        } else {
            // 排出中でない、または白線に接触していないときはすぐに排出できるようタイマーをクランプ
            timer = ejectInterval;
        }

        ejectTimerMap.put(robotId, timer);
    }

    /**
     * 凸多角形の内部に点 (px, py) が含まれるかを外積で判定します。
     * 頂点は時計回りまたは反時計回りの順序で与えられる必要があります。
     * 全辺の外積が同じ符号であれば内部に含まれると判定します。
     *
     * @param px 判定する点のX座標
     * @param py 判定する点のY座標
     * @param vertices 凸多角形の頂点配列 (各要素は {x, y})
     * @return 点が多角形の内部にあれば true
     */
    private boolean isPointInsidePolygon(double px, double py, double[][] vertices) {
        int n = vertices.length;
        if (n < 3) return false;

        boolean hasPositive = false;
        boolean hasNegative = false;

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double edgeX = vertices[j][0] - vertices[i][0];
            double edgeY = vertices[j][1] - vertices[i][1];
            double toPointX = px - vertices[i][0];
            double toPointY = py - vertices[i][1];

            double cross = edgeX * toPointY - edgeY * toPointX;
            if (cross > 0) hasPositive = true;
            if (cross < 0) hasNegative = true;

            // 符号が混在したら外側
            if (hasPositive && hasNegative) return false;
        }

        return true;
    }
}
