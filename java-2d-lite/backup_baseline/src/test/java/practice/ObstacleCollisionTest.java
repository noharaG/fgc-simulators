package practice;

import static org.junit.jupiter.api.Assertions.*;

import core.CollisionResult;
import core.PolygonObstacle;
import core.CircleObstacle;
import core.CapsuleObstacle;
import org.junit.jupiter.api.Test;

/**
 * 3種類のフィールド物理障害物（凸ポリゴン、円、カプセル）と
 * ロボット（OBB）との幾何的衝突判定・MTV算出の正確性を検証するテストクラス。
 */
public class ObstacleCollisionTest {

    @Test
    public void testPolygonObstacleCollision() {
        // 2m x 2m の正方形凸ポリゴン障害物 (中心: 3.0, 3.0)
        // CCW順に定義: (2,2) -> (4,2) -> (4,4) -> (2,4)
        double[][] vertices = {
            {2.0, 2.0},
            {4.0, 2.0},
            {4.0, 4.0},
            {2.0, 4.0}
        };
        PolygonObstacle poly = new PolygonObstacle("poly_test", "test", vertices);

        // 1. 衝突しない位置 (x=1.5, y=3.0, ロボット半長0.25 => 右端は 1.75 で障害物 2.0 と重ならない)
        CollisionResult noCol = poly.collideOBB(1.5, 3.0, 0.0, 0.25);
        assertNull(noCol, "衝突しないはずの位置で衝突が検知されました");

        // 2. 左側からのめり込み (x=1.9, y=3.0, ロボット半長0.25 => 右端は 2.15 で 0.15m 重なる)
        // 押し戻し方向は -x 方向 (左) になるべき
        CollisionResult col = poly.collideOBB(1.9, 3.0, 0.0, 0.25);
        assertNotNull(col, "衝突しているはずの位置で衝突が検知されませんでした");
        assertTrue(col.mtvX < 0.0, "押し出し方向は -x 方向であるべきです");
        assertEquals(0.15, Math.abs(col.mtvX), 1e-6, "押し出し量は 0.15m であるべきです");
        assertEquals(0.0, col.mtvY, 1e-6, "Y方向の押し出しは発生しないはずです");

        // 3. 辺由来（isEdgeOrigin）と辺角度（edgeAngle）の検証
        assertTrue(col.isEdgeOrigin, "ポリゴンの左辺に衝突しているため、isEdgeOriginはtrueであるべきです");
        // 左辺は (2.0, 4.0) から (2.0, 2.0) へのベクトルなので、角度は -PI/2
        assertEquals(-Math.PI / 2.0, col.edgeAngle, 1e-6, "衝突した辺の方向角は -PI/2 であるべきです");
    }

    @Test
    public void testCircleObstacleCollision() {
        // 中心 (3.0, 3.0), 半径 1.0m の円障害物
        CircleObstacle circle = new CircleObstacle("circle_test", "test", new double[]{3.0, 3.0}, 1.0, null);

        // 1. 衝突しない位置 (x=1.7, y=3.0, ロボット半長0.25 => 右端は 1.95 で円の左端 2.0 と重ならない)
        CollisionResult noCol = circle.collideOBB(1.7, 3.0, 0.0, 0.25);
        assertNull(noCol, "衝突しないはずの位置で衝突が検知されました");

        // 2. 左側からのめり込み (x=1.8, y=3.0, ロボット半長0.25 => 右端は 2.05 で 0.05m 重なる)
        // 押し戻し方向は -x 方向 (左)
        CollisionResult col = circle.collideOBB(1.8, 3.0, 0.0, 0.25);
        assertNotNull(col, "衝突している位置で衝突が検知されませんでした");
        assertTrue(col.mtvX < 0.0, "押し出し方向は -x 方向であるべきです");
        assertEquals(0.05, Math.abs(col.mtvX), 1e-3, "押し出し量は 0.05m であるべきです");
        assertEquals(0.0, col.mtvY, 1e-3, "Y方向の押し出しは発生しないはずです");
    }

    @Test
    public void testCapsuleObstacleCollision() {
        // 始点 (2.0, 2.0), 終点 (2.0, 4.0), 半径 0.5m のカプセル障害物 (中心軸は y方向)
        CapsuleObstacle capsule = new CapsuleObstacle("capsule_test", "test", new double[]{2.0, 2.0}, new double[]{2.0, 4.0}, 0.5, null);

        // 1. 衝突しない位置 (x=1.2, y=3.0, ロボット半長0.25 => 右端は 1.45 でカプセルの左端 1.5 と重ならない)
        CollisionResult noCol = capsule.collideOBB(1.2, 3.0, 0.0, 0.25);
        assertNull(noCol, "衝突しないはずの位置で衝突が検知されました");

        // 2. 左側からのめり込み (x=1.3, y=3.0, ロボット半長0.25 => 右端は 1.55 で 0.05m 重なる)
        // 押し戻し方向は -x 方向 (左)
        CollisionResult col = capsule.collideOBB(1.3, 3.0, 0.0, 0.25);
        assertNotNull(col, "衝突している位置で衝突が検知されませんでした");
        assertTrue(col.mtvX < 0.0, "押し出し方向は -x 方向であるべきです");
        assertEquals(0.05, Math.abs(col.mtvX), 1e-3, "押し出し量は 0.05m であるべきです");
        assertEquals(0.0, col.mtvY, 1e-3, "Y方向の押し出しは発生しないはずです");

        // 3. カプセル軸整列情報の検証
        assertTrue(col.isEdgeOrigin, "カプセルの軸に衝突しているため、isEdgeOriginはtrueであるべきです");
        // 始点 (2.0, 2.0) から 終点 (2.0, 4.0) への軸ベクトルなので、角度は PI/2
        assertEquals(Math.PI / 2.0, col.edgeAngle, 1e-6, "衝突した中心軸の方向角は PI/2 であるべきです");
    }

    @Test
    public void testCircleObstacleCollideCircle() {
        // 中心 (3.0, 3.0), 半径 1.0m の円障害物
        CircleObstacle circle = new CircleObstacle("circle_test", "test", new double[]{3.0, 3.0}, 1.0, null);

        // 1. 衝突しない位置 (x=1.8, y=3.0, ボール半径0.1 => 右端は 1.9 で円の左端 2.0 と重ならない)
        CollisionResult noCol = circle.collideCircle(1.8, 3.0, 0.1);
        assertNull(noCol, "衝突しないはずの位置で衝突が検知されました");

        // 2. 左側からのめり込み (x=1.95, y=3.0, ボール半径0.1 => 右端は 2.05 で 0.05m 重なる)
        // 押し戻し方向は -x 方向 (左)
        CollisionResult col = circle.collideCircle(1.95, 3.0, 0.1);
        assertNotNull(col, "衝突している位置で衝突が検知されませんでした");
        assertTrue(col.mtvX < 0.0, "押し出し方向は -x 方向であるべきです");
        assertEquals(0.05, Math.abs(col.mtvX), 1e-3, "押し出し量は 0.05m であるべきです");
        assertEquals(0.0, col.mtvY, 1e-3, "Y方向の押し出しは発生しないはずです");
    }

    @Test
    public void testCapsuleObstacleCollideCircle() {
        // 始点 (2.0, 2.0), 終点 (2.0, 4.0), 半径 0.5m のカプセル障害物 (中心軸は y方向)
        CapsuleObstacle capsule = new CapsuleObstacle("capsule_test", "test", new double[]{2.0, 2.0}, new double[]{2.0, 4.0}, 0.5, null);

        // 1. 衝突しない位置 (x=1.3, y=3.0, ボール半径0.1 => 右端は 1.4 でカプセルの左端 1.5 と重ならない)
        CollisionResult noCol = capsule.collideCircle(1.3, 3.0, 0.1);
        assertNull(noCol, "衝突しないはずの位置で衝突が検知されました");

        // 2. 左側からのめり込み (x=1.45, y=3.0, ボール半径0.1 => 右端は 1.55 で 0.05m 重なる)
        // 押し戻し方向は -x 方向 (左)
        CollisionResult col = capsule.collideCircle(1.45, 3.0, 0.1);
        assertNotNull(col, "衝突している位置で衝突が検知されませんでした");
        assertTrue(col.mtvX < 0.0, "押し出し方向は -x 方向であるべきです");
        assertEquals(0.05, Math.abs(col.mtvX), 1e-3, "押し出し量は 0.05m であるべきです");
        assertEquals(0.0, col.mtvY, 1e-3, "Y方向の押し出しは発生しないはずです");
    }

    @Test
    public void testPolygonObstacleCollideCircle() {
        // 2m x 2m の正方形凸ポリゴン障害物 (中心: 3.0, 3.0)
        double[][] vertices = {
            {2.0, 2.0},
            {4.0, 2.0},
            {4.0, 4.0},
            {2.0, 4.0}
        };
        PolygonObstacle poly = new PolygonObstacle("poly_test", "test", vertices);

        // 1. 衝突しない位置 (x=1.85, y=3.0, ボール半径0.1 => 右端は 1.95 で障害物 2.0 と重ならない)
        CollisionResult noCol = poly.collideCircle(1.85, 3.0, 0.1);
        assertNull(noCol, "衝突しないはずの位置で衝突が検知されました");

        // 2. 左側からのめり込み (x=1.95, y=3.0, ボール半径0.1 => 右端は 2.05 で 0.05m 重なる)
        CollisionResult col = poly.collideCircle(1.95, 3.0, 0.1);
        assertNotNull(col, "衝突しているはずの位置で衝突が検知されませんでした");
        assertTrue(col.mtvX < 0.0, "押し出し方向は -x 方向であるべきです");
        assertEquals(0.05, Math.abs(col.mtvX), 1e-6, "押し出し量は 0.05m であるべきです");
        assertEquals(0.0, col.mtvY, 1e-6, "Y方向の押し出しは発生しないはずです");
    }
}

