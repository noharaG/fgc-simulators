package practice;

import java.util.Random;

/**
 * 12種類のNPCロボット挙動の実装をまとめたクラス。
 */
public class Behaviors {

    public static final int BEHAVIOR_COUNT = 12;

    /**
     * 指定されたインデックスに対応する新規の挙動インスタンスを生成して返します。
     * 内部状態が完全にリセットされたクリーンなインスタンスとなります。
     */
    public static RobotBehavior createBehavior(int index) {
        switch (index) {
            case 0: return new StillBehavior();
            case 1: return new LinePatrolBehavior();
            case 2: return new CircleBehavior();
            case 3: return new RadialBehavior();
            case 4: return new WanderBehavior();
            case 5: return new BounceBehavior();
            case 6: return new LoopPatrolBehavior();
            case 7: return new SweepBehavior();
            case 8: return new StopGoBehavior();
            case 9: return new SpinBehavior();
            case 10: return new DrunkBehavior();
            case 11: return new SkittishBehavior();
            default: return new StillBehavior();
        }
    }

    // 角度偏差を正規化してP制御で旋回角速度を算出するヘルパー
    private static double steerTowards(double currentHeading, double targetHeading) {
        double diff = targetHeading - currentHeading;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        
        // Pゲイン 6.0 で目標角度へ旋回。最大角速度 3.0 rad/s に制限
        double kP = 6.0;
        double omega = diff * kP;
        return Math.max(-3.0, Math.min(3.0, omega));
    }

    // 2点間の距離を算出するヘルパー
    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    // 最寄りの未回収ボールを返す（無ければ null）。練習モードのボール回収追従用。
    private static core.BallState nearestBall(NpcState self, PracticeContext ctx, double maxRange) {
        if (ctx.balls == null || ctx.balls.isEmpty()) return null;
        core.BallState best = null;
        double bestD = maxRange;
        for (core.BallState b : ctx.balls) {
            if (b.collected || b.scored) continue;
            double d = distance(self.x, self.y, b.x, b.y);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    /**
     * ボール回収追従＋プレイヤー回避を加えた簡易ステアリングを返す。
     * 近くにボールがあればそれを目標に向かい、プレイヤーが近いと回避ベクトルを
     * ブレンドして避ける。ボールが無ければ null を返し、呼び出し側の既定挙動に委ねる。
     * (練習モードNPCを「少し賢く」する用途。設計は壊さない簡易版)
     */
    private static DriveCommand seekBallAvoidPlayer(NpcState self, PracticeContext ctx, double speed) {
        core.BallState ball = nearestBall(self, ctx, 2.5);
        if (ball == null) return null;

        // 目標方向（ボールへ）
        double dirX = ball.x - self.x;
        double dirY = ball.y - self.y;
        double dlen = Math.hypot(dirX, dirY);
        if (dlen < 1e-6) return new DriveCommand(0.0, 0.0);
        dirX /= dlen; dirY /= dlen;

        // プレイヤー回避ベクトル（近いほど強く）
        double distToPlayer = distance(self.x, self.y, ctx.playerX, ctx.playerY);
        final double AVOID_R = 1.0;
        if (distToPlayer < AVOID_R && distToPlayer > 1e-6) {
            double awayX = (self.x - ctx.playerX) / distToPlayer;
            double awayY = (self.y - ctx.playerY) / distToPlayer;
            double w = (AVOID_R - distToPlayer) / AVOID_R; // 0〜1
            dirX += awayX * w * 1.5;
            dirY += awayY * w * 1.5;
            double bl = Math.hypot(dirX, dirY);
            if (bl > 1e-6) { dirX /= bl; dirY /= bl; }
        }

        double targetHeading = Math.atan2(dirY, dirX);
        double omega = steerTowards(self.heading, targetHeading);
        // 壁が迫っていれば前進を抑える
        double fwd = isNearWall(self.x, self.y, self.heading, 0.4) ? 0.0 : speed;
        // 向きが大きくずれている間は減速して旋回優先
        if (Math.abs(normalizeAngleStatic(self.heading - targetHeading)) > 0.6) {
            fwd *= 0.3;
        }
        return new DriveCommand(fwd, omega);
    }

    private static double normalizeAngleStatic(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    // 壁との接近距離チェック (L/2 にマージンを加えた値以下なら接近)
    private static boolean isNearWall(double x, double y, double heading, double limit) {
        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        
        // 進行方向の壁までの距離を見積もる
        double distToLeft = cosH < 0 ? (x / -cosH) : Double.MAX_VALUE;
        double distToRight = cosH > 0 ? ((7.0 - x) / cosH) : Double.MAX_VALUE;
        double distToBottom = sinH < 0 ? (y / -sinH) : Double.MAX_VALUE;
        double distToTop = sinH > 0 ? ((7.0 - y) / sinH) : Double.MAX_VALUE;
        
        double minDist = Math.min(Math.min(distToLeft, distToRight), Math.min(distToBottom, distToTop));
        return minDist < limit;
    }

    // ----------------------------------------------------
    // #1 STILL (静止)
    // ----------------------------------------------------
    public static class StillBehavior implements RobotBehavior {
        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            return new DriveCommand(0.0, 0.0);
        }
    }

    // ----------------------------------------------------
    // #2 LINE_PATROL (前後往復)
    // ----------------------------------------------------
    public static class LinePatrolBehavior implements RobotBehavior {
        private boolean movingForward = true;
        private double targetHeading = 0.0;
        private boolean isRotating = false;
        private double timer = 0.0;
        private boolean initialized = false;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            if (!initialized) {
                initialized = true;
                targetHeading = self.heading;
                timer = 2.0; // 2秒間直進
            }

            if (!isRotating) {
                timer -= dt;
                boolean nearWall = isNearWall(self.x, self.y, self.heading, 0.5);
                if (timer <= 0.0 || nearWall) {
                    isRotating = true;
                    movingForward = !movingForward;
                    targetHeading = normalizeAngle(targetHeading + Math.PI);
                }
            }

            if (isRotating) {
                double omega = steerTowards(self.heading, targetHeading);
                if (Math.abs(normalizeAngle(self.heading - targetHeading)) < 0.05) {
                    isRotating = false;
                    timer = 2.0; // 再度2秒間直進
                    return new DriveCommand(0.0, 0.0);
                }
                return new DriveCommand(0.0, omega);
            } else {
                return new DriveCommand(0.5, 0.0);
            }
        }

        private double normalizeAngle(double angle) {
            while (angle > Math.PI) angle -= 2 * Math.PI;
            while (angle < -Math.PI) angle += 2 * Math.PI;
            return angle;
        }
    }

    // ----------------------------------------------------
    // #3 CIRCLE (半径1m円運動)
    // ----------------------------------------------------
    public static class CircleBehavior implements RobotBehavior {
        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            double r = 1.0;
            double v = 0.5;
            double omega = v / r;
            return new DriveCommand(v, omega);
        }
    }

    // ----------------------------------------------------
    // #4 RADIAL (中心と壁の往復)
    // ----------------------------------------------------
    public static class RadialBehavior implements RobotBehavior {
        private int state = 0; // 0:中心へ, 1:中心で旋回, 2:壁へ直進
        private double targetHeading = 0.0;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            double cx = 3.5, cy = 3.5;
            
            switch (state) {
                case 0: // 中心へ移動
                    double distToCenter = distance(self.x, self.y, cx, cy);
                    if (distToCenter < 0.15) {
                        state = 1;
                        // ランダムな方向を決定
                        targetHeading = ctx.random.nextDouble() * 2 * Math.PI - Math.PI;
                        return new DriveCommand(0.0, 0.0);
                    }
                    double angleToCenter = Math.atan2(cy - self.y, cx - self.x);
                    double omega = steerTowards(self.heading, angleToCenter);
                    if (Math.abs(normalizeAngle(self.heading - angleToCenter)) < 0.2) {
                        return new DriveCommand(0.6, omega);
                    }
                    return new DriveCommand(0.0, omega);

                case 1: // 中心で旋回
                    double w = steerTowards(self.heading, targetHeading);
                    if (Math.abs(normalizeAngle(self.heading - targetHeading)) < 0.05) {
                        state = 2;
                        return new DriveCommand(0.0, 0.0);
                    }
                    return new DriveCommand(0.0, w);

                case 2: // 壁へ直進
                    if (isNearWall(self.x, self.y, self.heading, 0.5)) {
                        state = 0;
                        return new DriveCommand(0.0, 0.0);
                    }
                    return new DriveCommand(0.6, 0.0);
            }
            return new DriveCommand(0.0, 0.0);
        }

        private double normalizeAngle(double angle) {
            while (angle > Math.PI) angle -= 2 * Math.PI;
            while (angle < -Math.PI) angle += 2 * Math.PI;
            return angle;
        }
    }

    // ----------------------------------------------------
    // #5 WANDER (ランダム走行)
    // ----------------------------------------------------
    public static class WanderBehavior implements RobotBehavior {
        private int state = 0; // 0:停止待機, 1:旋回, 2:前進
        private double timer = 0.0;
        private double targetHeading = 0.0;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            // 改善（タスクB-4）: 近くにボールがあれば回収追従＋プレイヤー回避を優先
            DriveCommand seek = seekBallAvoidPlayer(self, ctx, 0.7);
            if (seek != null) {
                state = 0; // 既定のさまよい状態をリセット
                timer = 0.0;
                return seek;
            }

            timer -= dt;

            // 壁に衝突または接近しすぎたら強制的に停止・方向転換へ
            if (state == 2 && (isNearWall(self.x, self.y, self.heading, 0.45))) {
                state = 0;
                timer = 0.3; // 少し停止
            }

            switch (state) {
                case 0: // 停止待機
                    if (timer <= 0) {
                        state = 1;
                        targetHeading = ctx.random.nextDouble() * 2 * Math.PI - Math.PI;
                    }
                    return new DriveCommand(0.0, 0.0);

                case 1: // 旋回
                    double omega = steerTowards(self.heading, targetHeading);
                    if (Math.abs(normalizeAngle(self.heading - targetHeading)) < 0.05) {
                        state = 2;
                        double targetDist = 1.0 + ctx.random.nextDouble() * 1.5; // 1m〜2.5m
                        timer = targetDist / 0.6; // 前進時間 (秒)
                    }
                    return new DriveCommand(0.0, omega);

                case 2: // 前進
                    if (timer <= 0) {
                        state = 0;
                        timer = 0.5 + ctx.random.nextDouble() * 1.0; // 0.5〜1.5秒停止
                        return new DriveCommand(0.0, 0.0);
                    }
                    return new DriveCommand(0.6, steerTowards(self.heading, targetHeading));
            }
            return new DriveCommand(0.0, 0.0);
        }

        private double normalizeAngle(double angle) {
            while (angle > Math.PI) angle -= 2 * Math.PI;
            while (angle < -Math.PI) angle += 2 * Math.PI;
            return angle;
        }
    }

    // ----------------------------------------------------
    // #6 BOUNCE (跳ね返り)
    // ----------------------------------------------------
    public static class BounceBehavior implements RobotBehavior {
        private boolean initialized = false;
        private double vx = 0.0;
        private double vy = 0.0;
        private static final double SPEED = 0.7;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            if (!initialized) {
                initialized = true;
                double angle = self.heading;
                vx = Math.cos(angle) * SPEED;
                vy = Math.sin(angle) * SPEED;
            }

            double r = 0.25;
            boolean bounced = false;

            // X軸方向の壁反射
            if ((self.x - r < 0.05 && vx < 0) || (self.x + r > 6.95 && vx > 0)) {
                vx = -vx;
                bounced = true;
            }
            // Y軸方向の壁反射
            if ((self.y - r < 0.05 && vy < 0) || (self.y + r > 6.95 && vy > 0)) {
                vy = -vy;
                bounced = true;
            }

            double targetHeading = Math.atan2(vy, vx);
            double omega = steerTowards(self.heading, targetHeading);
            
            // 跳ね返り時は、向きが完全に揃うまで前進速度を少し下げる
            double speedScale = Math.abs(steerTowards(self.heading, targetHeading)) > 0.5 ? 0.2 : 1.0;
            return new DriveCommand(SPEED * speedScale, omega);
        }
    }

    // ----------------------------------------------------
    // #7 LOOP_PATROL (予測可能巡回)
    // ----------------------------------------------------
    public static class LoopPatrolBehavior implements RobotBehavior {
        // フィールド内に安全に収まる waypoint
        private final double[][] waypoints = {
            {2.0, 2.0},
            {5.0, 2.0},
            {5.0, 5.0},
            {2.0, 5.0}
        };
        private int currentIdx = 0;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            // 改善（タスクB-4）: 近くにボールがあれば回収追従＋プレイヤー回避を優先
            DriveCommand seek = seekBallAvoidPlayer(self, ctx, 0.6);
            if (seek != null) {
                return seek;
            }

            double tx = waypoints[currentIdx][0];
            double ty = waypoints[currentIdx][1];

            double dist = distance(self.x, self.y, tx, ty);
            if (dist < 0.25) {
                currentIdx = (currentIdx + 1) % waypoints.length;
                tx = waypoints[currentIdx][0];
                ty = waypoints[currentIdx][1];
            }

            double targetHeading = Math.atan2(ty - self.y, tx - self.x);
            double omega = steerTowards(self.heading, targetHeading);

            if (Math.abs(normalizeAngle(self.heading - targetHeading)) < 0.2) {
                return new DriveCommand(0.6, omega);
            } else {
                return new DriveCommand(0.1, omega);
            }
        }

        private double normalizeAngle(double angle) {
            while (angle > Math.PI) angle -= 2 * Math.PI;
            while (angle < -Math.PI) angle += 2 * Math.PI;
            return angle;
        }
    }

    // ----------------------------------------------------
    // #8 SWEEP (蛇行スキャン)
    // ----------------------------------------------------
    public static class SweepBehavior implements RobotBehavior {
        private double targetY = 6.0;
        private double currentXLine = -1;
        private int phase = 0; // 0:直進, 1:横スライド旋回, 2:スライド前進
        private double targetHeading = Math.PI / 2;
        private double timer = 0.0;
        private boolean sweepingRight = true;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            if (currentXLine < 0) {
                currentXLine = self.x;
                targetHeading = (self.y < 3.5) ? Math.PI / 2 : -Math.PI / 2;
                targetY = (targetHeading > 0) ? 6.2 : 0.8;
            }

            timer -= dt;

            switch (phase) {
                case 0: // Y軸方向直進
                    double distToY = Math.abs(self.y - targetY);
                    if (distToY < 0.2) {
                        phase = 1;
                        targetHeading = sweepingRight ? 0.0 : Math.PI; // 横向き
                        return new DriveCommand(0.0, 0.0);
                    }
                    return new DriveCommand(0.65, steerTowards(self.heading, targetHeading));

                case 1: // 横向き旋回
                    double w1 = steerTowards(self.heading, targetHeading);
                    if (Math.abs(normalizeAngle(self.heading - targetHeading)) < 0.05) {
                        phase = 2;
                        timer = 0.7 / 0.4; // 0.7m を 0.4m/s で進む時間 (1.75秒)
                        return new DriveCommand(0.0, 0.0);
                    }
                    return new DriveCommand(0.0, w1);

                case 2: // 横に少し進む
                    // 端の壁にぶつかりそうなら進行方向反転
                    boolean wallLimit = isNearWall(self.x, self.y, self.heading, 0.6);
                    if (timer <= 0.0 || wallLimit) {
                        if (wallLimit) {
                            sweepingRight = !sweepingRight;
                        }
                        // 直進方向を逆に設定
                        targetHeading = (targetY > 3.5) ? -Math.PI / 2 : Math.PI / 2;
                        targetY = (targetHeading > 0) ? 6.2 : 0.8;
                        phase = 0; // 再度直進へ戻す
                        return new DriveCommand(0.0, 0.0);
                    }
                    return new DriveCommand(0.4, 0.0);
            }
            return new DriveCommand(0.0, 0.0);
        }

        private double normalizeAngle(double angle) {
            while (angle > Math.PI) angle -= 2 * Math.PI;
            while (angle < -Math.PI) angle += 2 * Math.PI;
            return angle;
        }
    }

    // ----------------------------------------------------
    // #9 STOP_GO (停止と前進の繰り返し)
    // ----------------------------------------------------
    public static class StopGoBehavior implements RobotBehavior {
        private boolean isMoving = true;
        private double timer = 1.0;
        private double targetHeading = 0.0;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            // 改善（タスクB-4）: 近くにボールがあれば回収追従＋プレイヤー回避を優先
            DriveCommand seek = seekBallAvoidPlayer(self, ctx, 0.6);
            if (seek != null) {
                isMoving = true;
                return seek;
            }

            timer -= dt;

            if (isMoving && isNearWall(self.x, self.y, self.heading, 0.45)) {
                isMoving = false;
                timer = 1.0 + ctx.random.nextDouble() * 1.0;
            }

            if (timer <= 0) {
                isMoving = !isMoving;
                if (isMoving) {
                    timer = 0.5 + ctx.random.nextDouble() * 1.0; // 0.5〜1.5秒前進
                    targetHeading = ctx.random.nextDouble() * 2 * Math.PI - Math.PI;
                } else {
                    timer = 1.0 + ctx.random.nextDouble() * 1.5; // 1.0〜2.5秒停止
                }
            }

            if (isMoving) {
                double omega = steerTowards(self.heading, targetHeading);
                return new DriveCommand(0.6, omega);
            } else {
                return new DriveCommand(0.0, 0.0);
            }
        }
    }

    // ----------------------------------------------------
    // #10 SPIN (その場回転)
    // ----------------------------------------------------
    public static class SpinBehavior implements RobotBehavior {
        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            return new DriveCommand(0.0, 2.0); // 2.0 rad/s で回転
        }
    }

    // ----------------------------------------------------
    // #11 DRUNK (千鳥足走行)
    // ----------------------------------------------------
    public static class DrunkBehavior implements RobotBehavior {
        private double baseHeading = 0.0;
        private boolean initialized = false;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            if (!initialized) {
                initialized = true;
                baseHeading = self.heading;
            }

            // 毎フレーム -0.15 〜 +0.15 ラジアンの揺らぎを追加
            double noise = (ctx.random.nextDouble() - 0.5) * 0.3;
            baseHeading = normalizeAngle(baseHeading + noise);

            // 壁に近づいたら回避
            if (isNearWall(self.x, self.y, baseHeading, 0.6)) {
                baseHeading = normalizeAngle(baseHeading + Math.PI / 2.0 + ctx.random.nextDouble() * Math.PI / 2.0);
            }

            double omega = steerTowards(self.heading, baseHeading);
            return new DriveCommand(0.35, omega);
        }

        private double normalizeAngle(double angle) {
            while (angle > Math.PI) angle -= 2 * Math.PI;
            while (angle < -Math.PI) angle += 2 * Math.PI;
            return angle;
        }
    }

    // ----------------------------------------------------
    // #12 SKITTISH (プレイヤー回避)
    // ----------------------------------------------------
    public static class SkittishBehavior implements RobotBehavior {
        private boolean isFleeing = false;

        @Override
        public DriveCommand update(NpcState self, PracticeContext ctx, double dt) {
            double distToPlayer = distance(self.x, self.y, ctx.playerX, ctx.playerY);

            // 1.5m以内に入ったら逃走、2.2m以上離れたら停止
            if (distToPlayer < 1.5) {
                isFleeing = true;
            } else if (distToPlayer > 2.2) {
                isFleeing = false;
            }

            if (isFleeing) {
                // プレイヤーと反対方向へ進む
                double angleFromPlayer = Math.atan2(self.y - ctx.playerY, self.x - ctx.playerX);
                
                // 壁に追い詰められているときは停止する
                boolean nearWall = isNearWall(self.x, self.y, angleFromPlayer, 0.35);
                if (nearWall) {
                    return new DriveCommand(0.0, steerTowards(self.heading, angleFromPlayer));
                }
                
                double omega = steerTowards(self.heading, angleFromPlayer);
                return new DriveCommand(1.2, omega); // 最大全力 1.2 m/s で逃げる
            } else {
                return new DriveCommand(0.0, 0.0);
            }
        }
    }
}
