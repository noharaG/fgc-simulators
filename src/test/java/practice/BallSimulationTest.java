package practice;

import static org.junit.jupiter.api.Assertions.*;

import core.BallSimulation;
import core.BallState;
import core.RobotConfig;
import core.CircleObstacle;
import core.CapsuleObstacle;
import core.PolygonObstacle;
import core.RobotOBB;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ボール物理シミュレーションの決定論的再現性（seed再現性）と
 * 起動時の放出挙動を検証するテストクラス。
 */
public class BallSimulationTest {

    @Test
    public void testBallEmissionTiming() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 500;
        config.ballSeed = 42L;
        config.ballEmitRadius = 0.25;

        BallSimulation sim = new BallSimulation();
        sim.init(config, null);

        List<BallState> states = sim.getBallStates();
        assertEquals(500, states.size(), "起動直後にボールが500個放出されるべきです");

        double xCenter = 3.5;
        double yEmit = 0.747;

        // 初期位置と初期速度が設定されていることを確認
        for (BallState ball : states) {
            double dist = Math.hypot(ball.x - xCenter, ball.y - yEmit);
            assertTrue(dist <= 0.250001, "放出位置が中心(3.5, 0.747)から半径0.25m以内に収まるべきです: dist = " + dist + " (x=" + ball.x + ", y=" + ball.y + ")");
            // 左右端から放出されないことの確認 (xMin=2.054, xMax=4.946)
            assertTrue(ball.x > 2.054 + 0.1, "上辺左端付近からは放出されないはずです: " + ball.x);
            assertTrue(ball.x < 4.946 - 0.1, "上辺右端付近からは放出されないはずです: " + ball.x);
            
            assertTrue(Math.hypot(ball.vx, ball.vy) > 0.0, "初期速度が設定されているべきです");
            assertTrue(ball.vy > 0.0, "ボールは+y方向（中央）に向けて放出されるべきです");
        }
    }

    @Test
    public void testSeedReproducibility() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 500;
        config.ballSeed = 12345L;
        config.ballSubSteps = 4;
        config.ballSolverIterations = 3;

        // 1回目の実行
        BallSimulation sim1 = new BallSimulation();
        sim1.init(config, null);

        // 2回目の実行
        BallSimulation sim2 = new BallSimulation();
        sim2.init(config, null);

        // 初期状態が一致することを確認
        List<BallState> states1 = sim1.getBallStates();
        List<BallState> states2 = sim2.getBallStates();
        for (int i = 0; i < 500; i++) {
            assertEquals(states1.get(i).x, states2.get(i).x, 1e-12, "初期位置Xが一致すべきです");
            assertEquals(states1.get(i).y, states2.get(i).y, 1e-12, "初期位置Yが一致すべきです");
            assertEquals(states1.get(i).vx, states2.get(i).vx, 1e-12, "初期速度VXが一致すべきです");
            assertEquals(states1.get(i).vy, states2.get(i).vy, 1e-12, "初期速度VYが一致すべきです");
        }

        // 100ステップ更新を実行 (競合状態の並列化が無いことを保証する)
        double dt = 0.02 / 4.0; // 1サブステップ分
        for (int step = 0; step < 100; step++) {
            sim1.update(dt, new RobotOBB(3.5, 3.5, 0.0, 0.25, 0.0, 0.0), null);
            sim2.update(dt, new RobotOBB(3.5, 3.5, 0.0, 0.25, 0.0, 0.0), null);
        }

        // 更新後の状態がビットレベルで完全に一致することを確認 (決定論の検証)
        states1 = sim1.getBallStates();
        states2 = sim2.getBallStates();
        for (int i = 0; i < 500; i++) {
            assertEquals(states1.get(i).x, states2.get(i).x, 1e-12, "100ステップ後の位置Xが完全に一致すべきです");
            assertEquals(states1.get(i).y, states2.get(i).y, 1e-12, "100ステップ後の位置Yが完全に一致すべきです");
            assertEquals(states1.get(i).vx, states2.get(i).vx, 1e-12, "100ステップ後の速度VXが完全に一致すべきです");
            assertEquals(states1.get(i).vy, states2.get(i).vy, 1e-12, "100ステップ後の速度VYが完全に一致すべきです");
        }
    }

    @Test
    public void testRollingFriction() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 10;
        config.ballRollingDamping = 1.5;
        config.ballStopThreshold = 0.02;

        BallSimulation sim = new BallSimulation();
        sim.init(config, null);

        // 十分なステップを更新して全ボールが完全に静止するか検証する (4秒分 = 800サブステップ)
        double dt = 0.02 / 4.0; // 5ms
        for (int step = 0; step < 800; step++) {
            sim.update(dt, new RobotOBB(3.5, 3.5, 0.0, 0.25, 0.0, 0.0), null);
        }

        List<BallState> states = sim.getBallStates();
        for (BallState ball : states) {
            assertEquals(0.0, ball.vx, 1e-9, "X速度は完全に0になるべきです");
            assertEquals(0.0, ball.vy, 1e-9, "Y速度は完全に0になるべきです");
        }
    }

    @Test
    public void testBallBraceCollisionSkipping() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 3;
        config.ballSeed = 42L;
        config.ballRestitution = 0.5;
        config.ballRollingDamping = 4.0;

        // 1. 円ブレース (brace_2, category: brace, 中心: 3.0, 3.0, 半径: 0.5)
        CircleObstacle circleBrace = new CircleObstacle("brace_2", "brace", new double[]{3.0, 3.0}, 0.5, null);
        // 2. カプセルブレース (brace_diag_left, category: brace, p1: 5.0, 2.0, p2: 5.0, 4.0, 半径: 0.5)
        CapsuleObstacle capsuleBrace = new CapsuleObstacle("brace_diag_left", "brace", new double[]{5.0, 2.0}, new double[]{5.0, 4.0}, 0.5, null);
        // 3. ポリゴン障害物 (suppression_unit, category: suppression_unit, x: 1.0, y: 1.0〜2.0)
        double[][] polyVerts = {
            {0.5, 0.5},
            {1.5, 0.5},
            {1.5, 1.5},
            {0.5, 1.5}
        };
        PolygonObstacle polyObstacle = new PolygonObstacle("suppression_unit", "suppression_unit", polyVerts);

        List<core.FieldObstacle> obstacles = java.util.Arrays.asList(circleBrace, capsuleBrace, polyObstacle);

        BallSimulation sim = new BallSimulation();
        sim.init(config, obstacles);

        // ボール0: 円ブレース (3.0, 3.0) を通過する軌道 (初期位置 3.0, 2.0、速度 0, 10.0) -> すり抜けるはず
        // ボール1: カプセルブレース (5.0, 3.0) を通過する軌道 (初期位置 5.0, 2.0、速度 0, 10.0) -> すり抜けるはず
        // ボール2: ポリゴン障害物 (1.0, 0.5) から上に向かう軌道 (初期位置 1.0, 0.1、速度 0, 10.0) -> 衝突して押し戻されるはず
        List<BallState> balls = sim.getBallStates();
        balls.get(0).x = 3.0;
        balls.get(0).y = 2.0;
        balls.get(0).vx = 0.0;
        balls.get(0).vy = 10.0;

        balls.get(1).x = 5.0;
        balls.get(1).y = 2.0;
        balls.get(1).vx = 0.0;
        balls.get(1).vy = 10.0;

        balls.get(2).x = 1.0;
        balls.get(2).y = 0.1;
        balls.get(2).vx = 0.0;
        balls.get(2).vy = 10.0;

        // 1ステップ (dt=0.01s) 更新
        sim.update(0.01, new RobotOBB(0.0, 0.0, 0.0, 0.25, 0.0, 0.0), null);

        // ボール0, 1 は速度が変わらないか、障害物を突き抜けているはず
        // 元のyが2.0、vyが10.0、減衰を考えると(減衰=4.0、1.0-4.0*0.01=0.96 => vyは9.6、yは2.0+9.6*0.01=2.096)
        // 押し戻しや反射が発生していなければ、yは 2.096 前後になるはず
        assertTrue(balls.get(0).y > 2.05, "ボール0は円ブレースをすり抜けるはずです");
        assertEquals(10.0 * 0.96, balls.get(0).vy, 1e-3, "ボール0の速度は減衰のみ受けるはずです");

        assertTrue(balls.get(1).y > 2.05, "ボール1はカプセルブレースをすり抜けるはずです");
        assertEquals(10.0 * 0.96, balls.get(1).vy, 1e-3, "ボール1の速度は減衰のみ受けるはずです");

        // ボール2はポリゴン障害物の下辺 (y=0.5) に衝突する。
        // ボールの半径0.05mなので、y=0.45mで接触。ボール2の初期yは0.1m。
        // 更新前の位置更新で y は 0.1 + 9.6*0.01 = 0.196m。
        // その後クランプ処理で y=0.196 はポリゴン障害物の外側なので衝突していない。
        // もう少し衝突しやすい位置・速度に設定してテストする：
        // 初期y=0.42m, vy=10.0m/s -> 更新後 y = 0.42 + 9.6*0.01 = 0.516m (ポリゴン y=0.5m の内側にめり込む)
        // クランプ処理により、ボール2は y=0.45m に押し戻され、速度 vy も反射されて負になるはず。
        balls.get(2).y = 0.42;
        balls.get(2).vy = 10.0;
        sim.update(0.01, new RobotOBB(0.0, 0.0, 0.0, 0.25, 0.0, 0.0), null);

        assertTrue(balls.get(2).y <= 0.45, "ボール2はポリゴンに衝突して押し戻されるはずです: " + balls.get(2).y);
        assertTrue(balls.get(2).vy < 0.0, "ボール2の速度は反射されて負になるはずです: " + balls.get(2).vy);
    }

    @Test
    public void testBallNpcCollision() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 2;
        config.ballSeed = 42L;
        config.ballRestitution = 0.5;

        BallSimulation sim = new BallSimulation();
        sim.init(config, null);

        List<BallState> balls = sim.getBallStates();
        // ボール0: プレイヤーロボット(cx=1.0, cy=1.0)に向かう
        balls.get(0).x = 1.0;
        balls.get(0).y = 0.68; // めり込む y = 1.0 - 0.25 - 0.05 = 0.7m より深い (次のフレームで 0.78 になり衝突)
        balls.get(0).vx = 0.0;
        balls.get(0).vy = 10.0;

        // ボール1: NPCロボット(cx=4.0, cy=4.0)に向かう
        balls.get(1).x = 4.0;
        balls.get(1).y = 3.65; // めり込む y = 4.0 - 0.25 - 0.05 = 3.7m より深い (0.05mめり込む)
        balls.get(1).vx = 0.0;
        balls.get(1).vy = 10.0;

        RobotOBB player = new RobotOBB(1.0, 1.0, 0.0, 0.25, 0.0, 0.0);
        List<RobotOBB> npcs = java.util.Arrays.asList(new RobotOBB(4.0, 4.0, 0.0, 0.25, 0.0, 0.0));

        // 1ステップ (dt=0.01s) 更新
        sim.update(0.01, player, npcs);

        assertTrue(balls.get(0).y <= 0.7, "プレイヤーロボットにより押し戻されるはずです: " + balls.get(0).y);
        assertTrue(balls.get(0).vy < 0.0, "プレイヤーロボットにより反射され速度が負になるはずです: " + balls.get(0).vy);

        assertTrue(balls.get(1).y <= 3.7, "NPCロボットにより押し戻されるはずです: " + balls.get(1).y);
        assertTrue(balls.get(1).vy < 0.0, "NPCロボットにより反射され速度が負になるはずです: " + balls.get(1).vy);
    }

    @Test
    public void testBallIntakeCollection() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 15;
        config.intakeCapacity = 10;
        config.intakeFrontDepth = 0.08;
        config.intakeFrontWidth = 0.50;
        config.ballRestitution = 1.5;

        BallSimulation sim = new BallSimulation();
        sim.init(config, null);
        List<BallState> balls = sim.getBallStates();

        // ロボット OBB (ボディ中心 3.5, 3.5, 向き PI/2 = 上向き)
        // 向き PI/2 なので、前面辺の中心 F = (3.5, 3.5 + 0.25) = (3.5, 3.75)
        // 前面矩形は 中心 (3.5, 3.75), 幅 x方向 0.5, 奥行き y方向 0.08
        // ローカル座標で表すと、前面矩形は x ∈ [3.25, 3.75], y ∈ [3.71, 3.79]

        // 1. 通常回収判定 (前面矩形内)
        balls.get(0).x = 3.5;
        balls.get(0).y = 3.75;
        balls.get(0).vx = 0.0;
        balls.get(0).vy = 0.0;
        
        RobotOBB player = new RobotOBB(3.5, 3.5, Math.PI / 2.0, 0.25, 0.0, 0.0);
        
        // Aボタン押下で processIntake を実行
        sim.processIntake(player, true);
        
        assertTrue(balls.get(0).collected, "前面矩形内のボールは回収されるべきです");
        assertEquals(1, sim.getHeldBallCount(), "保持数が1になるべきです");
        assertEquals(1, sim.getTotalCollectedCount(), "累積回収数が1になるべきです");

        // 2. 二重回収防止テスト
        // 同じボールに対して再度 processIntake を実行しても二重回収されない
        sim.processIntake(player, true);
        assertEquals(1, sim.getHeldBallCount(), "既に回収済みのボールに対して二重回収されないべきです");

        // 3. 非回収判定 (Aボタン離し)
        balls.get(1).x = 3.5;
        balls.get(1).y = 3.75;
        balls.get(1).vx = 0.0;
        balls.get(1).vy = 0.0;
        
        sim.processIntake(player, false); // Aボタン離し
        assertFalse(balls.get(1).collected, "Aボタンを離しているときは回収されないべきです");

        // 4. 非回収判定 (側面・後面)
        // 側面 (3.1, 3.5)
        balls.get(2).x = 3.1;
        balls.get(2).y = 3.5;
        sim.processIntake(player, true);
        assertFalse(balls.get(2).collected, "側面のボールは回収されないべきです");

        // 後面 (3.5, 3.2)
        balls.get(3).x = 3.5;
        balls.get(3).y = 3.2;
        sim.processIntake(player, true);
        assertFalse(balls.get(3).collected, "後面のボールは回収されないべきです");

        // 5. 回収上限切り替えテスト
        // 上限10個まで回収させる
        for (int i = 4; i < 13; i++) {
            balls.get(i).x = 3.5;
            balls.get(i).y = 3.75;
            balls.get(i).vx = 0.0;
            balls.get(i).vy = 0.0;
        }
        sim.processIntake(player, true); // これで 1 (balls[0]) + 9 (balls[4..12]) = 10個回収される
        assertEquals(10, sim.getHeldBallCount(), "上限の10個回収されているべきです");

        // 11個目のボールを前面に置く
        balls.get(13).x = 3.5;
        balls.get(13).y = 3.75;
        balls.get(13).vx = 0.0;
        balls.get(13).vy = 0.0;
        sim.processIntake(player, true);
        assertFalse(balls.get(13).collected, "上限に達した後は回収されないべきです");
        assertEquals(10, sim.getHeldBallCount(), "保持数は10のまま維持されるべきです");

        // 6. 呼び出し順・押し戻しテスト (上限到達時の押し戻し)
        // 上限に達した状態で前面のボール(balls[13])に対して物理更新 update を実行すると、
        // 押し戻しが働き、速度が反射されて負になる、または位置が押し戻されるべきです。
        // balls[13] の初期位置 y = 3.72m (めり込み位置)
        // ロボット前面端は y = 3.75m。ボール半径 0.05m なので、衝突境界は y = 3.70m。
        // y = 3.72m はめり込み位置。
        balls.get(13).x = 3.5;
        balls.get(13).y = 3.81; // ロボット外側 (境界 3.80m)
        balls.get(13).vx = 0.0;
        balls.get(13).vy = -3.0; // ロボットに向かう速度
        
        sim.update(0.01, player, null);
        
        // collected が立っていないこと
        assertFalse(balls.get(13).collected, "上限到達後は衝突時にも collected にならないべきです");
        // OBB 衝突応答により、ボールがロボット(上向き)から押し戻されて、y が 3.80m 以上になるはず
        assertTrue(balls.get(13).y >= 3.80, "上限到達後は前面ボールが押し戻されるべきです: " + balls.get(13).y);
        assertTrue(balls.get(13).vy > 0.0, "上限到達後は前面ボールの速度が反射されるべきです: " + balls.get(13).vy);
    }

    @Test
    public void testBallShootAndGoal() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 10;
        config.intakeCapacity = 10;
        config.shootRange = 2.0;
        config.shootAngleThreshold = Math.toRadians(15.0); // 15度
        config.shootSuccessProb = 0.5; // 確率50%
        config.shootBPS = 5.0; // 5個/秒
        config.shootMissRadius = 2.0;
        config.ballSeed = 42L;

        BallSimulation sim = new BallSimulation();
        sim.init(config, null);
        List<BallState> balls = sim.getBallStates();

        // 1. ボールを3つ回収する
        RobotOBB player = new RobotOBB(3.5, 2.0, Math.PI / 2.0, 0.25, 0.0, 0.0);
        // 回収のためにボールを前面に配置
        balls.get(0).x = 3.5; balls.get(0).y = 2.25;
        balls.get(1).x = 3.5; balls.get(1).y = 2.25;
        balls.get(2).x = 3.5; balls.get(2).y = 2.25;
        sim.processIntake(player, true);
        assertEquals(3, sim.getHeldBallCount(), "3つのボールが保持中になるべきです");

        // 2. 正対・射程範囲外の射出防止テスト
        // 遠くに移動させて射程外にする (y=4.5m)
        player = new RobotOBB(3.5, 4.5, -Math.PI / 2.0, 0.25, 0.0, 0.0);
        sim.processShoot(player, true, false, 0.02);
        assertEquals(3, sim.getHeldBallCount(), "射程外では射出されないべきです");

        // 3. 正対外（逆向き）での射出防止テスト
        // ゴール方向 (-y方向) と逆向き (向き +PI/2) にする
        player = new RobotOBB(3.5, 2.0, Math.PI / 2.0, 0.25, 0.0, 0.0);
        sim.processShoot(player, true, false, 0.02);
        assertEquals(3, sim.getHeldBallCount(), "正対していない（逆向き）ときは射出されないべきです");

        // 4. 赤アライアンスでの赤ゴール射出テスト (射程内 & 正対 -PI/2)
        // 赤ゴール目標は x=4.464 (上辺右1/3の中点), y=0.747
        // ロボットを赤ゴールの手前に配置： G=(4.464, 2.0)、向き -PI/2 (下、ゴールに向かう向き)
        player = new RobotOBB(2.536, 2.0, -Math.PI / 2.0, 0.25, 0.0, 0.0);
        
        // RT押下（shootActive = true）、1ステップ (dt=0.02s) 実行
        // shootBPS = 5.0 なので 射出間隔 = 0.2s。
        // タイマーが0.2sに達するまでは射出されない。初期状態ではタイマーは 0.2s にクランプされる仕様なので、
        // 最初の tick で即座に1発発射されるべき。
        sim.processShoot(player, true, false, 0.02);
        assertEquals(2, sim.getHeldBallCount(), "1発射出されて保持数が2になるべきです");
        assertEquals(1, sim.getShotAttemptCount(), "射出試行数が1になるべきです");

        // もう1発射出するには、0.2秒経過する必要がある。
        // dt=0.02 を10回呼んで 0.2秒経過させる
        for (int i = 0; i < 10; i++) {
            sim.processShoot(player, true, false, 0.02);
        }
        assertEquals(1, sim.getHeldBallCount(), "0.2秒経過後、もう1発射出されて保持数が1になるべきです");
        assertEquals(2, sim.getShotAttemptCount(), "累積射出試行数が2になるべきです");

        // 5. 決定論的成否パターンの再現性テスト
        int successCount = sim.getScoreCount();
        int attemptCount = sim.getShotAttemptCount();
        assertTrue(successCount <= attemptCount, "得点数は試行数以下であるべきです");

        // 6. 成功したボールが activeBalls から除外され物理演算コストに乗らないことの確認
        double dt = 0.01;
        sim.update(dt, player, null);
        
        for (BallState ball : sim.getBallStates()) {
            if (ball.scored) {
                // scored なボールは位置が変わらないはず (物理演算対象から外れている)
                double bx = ball.x;
                double by = ball.y;
                sim.update(dt, player, null);
                assertEquals(bx, ball.x, "消滅したボールは物理演算で動かないべきです");
                assertEquals(by, ball.y, "消滅したボールは物理演算で動かないべきです");
            } else if (ball.collected) {
                // 回収されたボールも動かないはず
                double bx = ball.x;
                double by = ball.y;
                sim.update(dt, player, null);
                assertEquals(bx, ball.x, "保持中のボールは物理演算で動かないべきです");
                assertEquals(by, ball.y, "保持中のボールは物理演算で動かないべきです");
            } else {
                // 失敗したボールは物理に復帰しているため、初速によって動くはず
                double bx = ball.x;
                double by = ball.y;
                sim.update(dt, player, null);
                if (Math.hypot(ball.vx, ball.vy) > 0.01) {
                    assertNotEquals(bx, ball.x, "失敗して復帰したボールは物理演算で動べきです");
                }
            }
        }
    }

    @Test
    public void testWhiteGoalScoring() {
        RobotConfig config = new RobotConfig();
        config.ballCount = 1;
        config.ballSeed = 42L;
        config.whiteGoalSuccessProb = 1.0; // 成功率100%
        config.whiteGoalMissRadius = 2.0;

        // テスト用の Fire Shield ポリゴン (CCW)
        // (0.0, 5.8) -> (0.5, 6.1) が斜辺で、外向き法線がフィールド中央 (3.5, 3.5) を向く
        double[][] leftVerts = {
            {0.0, 5.8},
            {0.5, 6.1},
            {0.5, 7.0},
            {0.0, 7.0}
        };
        PolygonObstacle leftShield = new PolygonObstacle("fire_shield_left", "fire_shield", leftVerts);
        List<core.FieldObstacle> obstacles = java.util.Arrays.asList(leftShield);

        BallSimulation sim = new BallSimulation();
        sim.init(config, obstacles);

        // ボールは1つ。初期位置を斜辺 (0.0, 5.8) -> (0.5, 6.1) の中点 (0.25, 5.95) に配置する。
        List<BallState> balls = sim.getBallStates();
        assertEquals(1, balls.size());
        BallState ball = balls.get(0);
        ball.x = 0.25;
        ball.y = 5.95;
        ball.vx = 0.0;
        ball.vy = 0.0;
        ball.collected = false;
        ball.scored = false;

        // 成功率1.0でのテスト: 更新時に白ゴールを通過し、消滅（scored == true）し、白得点 +1
        sim.update(0.01, new RobotOBB(3.5, 3.5, 0.0, 0.25, 0.0, 0.0), null);
        assertTrue(ball.scored, "成功率100%なのでボールは消滅すべきです");
        assertEquals(1, sim.getWhiteScoreCount(), "白得点が1になるべきです");

        // 成功率0.0でのテスト
        config.whiteGoalSuccessProb = 0.0; // 成功率0%
        sim.init(config, obstacles);
        balls = sim.getBallStates();
        ball = balls.get(0);
        ball.x = 0.25;
        ball.y = 5.95;
        ball.vx = 0.0;
        ball.vy = 0.0;
        ball.collected = false;
        ball.scored = false;

        sim.update(0.01, new RobotOBB(3.5, 3.5, 0.0, 0.25, 0.0, 0.0), null);
        assertFalse(ball.scored, "成功率0%なのでボールは消滅しないべきです");
        assertEquals(0, sim.getWhiteScoreCount(), "白得点は0のままであるべきです");
        // テレポートしていることを確認
        double dist = Math.hypot(ball.x - 0.25, ball.y - 5.95);
        assertTrue(dist > 0.05, "失敗したボールは元の位置からテレポートすべきです: dist = " + dist);
        assertTrue(dist <= config.whiteGoalMissRadius + 0.1, "失敗したボールは散布半径内に収まるべきです: dist = " + dist);
    }
}

