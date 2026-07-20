package practice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class NpcCollisionTest {

    @Test
    public void testNoCollision() {
        // 離れた位置にあるロボット同士（衝突しないはず）
        PracticeManager.CollisionResult res = PracticeManager.checkObbCollision(
            2.0, 2.0, 0.0, 0.5,
            3.0, 2.0, 0.0, 0.5
        );
        assertFalse(res.collided, "離れたロボット同士は衝突しないはず");
    }

    @Test
    public void testCollision() {
        // 重なっているロボット同士（衝突するはず）
        PracticeManager.CollisionResult res = PracticeManager.checkObbCollision(
            2.0, 2.0, 0.0, 0.5,
            2.3, 2.0, 0.0, 0.5
        );
        assertTrue(res.collided, "重なっているロボット同士は衝突するはず");
        // MTV (最小移動ベクトル) の検証
        // AをBから押し離す方向。AのX=2.0, BのX=2.3なので、Aは左へ押し出されるべき。
        // 重なり幅は (0.25 + 0.25) - 0.3 = 0.2m
        assertTrue(res.mtvX < 0, "AはBの左側にあるため、左方向に押し戻されるべき");
        assertTrue(Math.abs(res.mtvX - (-0.2)) < 1e-5, "押し戻し量は約 0.2m であるべき");
    }

    @Test
    public void testRotatedCollision() {
        // 回転して角がめり込んでいるケース
        // 角度45度回転したロボットAと、その近くにあるロボットB
        double angleA = Math.toRadians(45.0);
        
        // 中心間距離 0.6m
        // 0度回転なら 0.25 + 0.25 = 0.5m が限界なので衝突しない。
        // しかし45度回転すると、Aの対角線の半分は 0.25 * sqrt(2) ≈ 0.354m になる。
        // よって、A(2.0, 2.0) と B(2.5, 2.0) は、0度回転なら衝突しないが、Aが45度回転していると衝突する。
        
        PracticeManager.CollisionResult resRotated = PracticeManager.checkObbCollision(
            2.0, 2.0, angleA, 0.5,
            2.5, 2.0, 0.0, 0.5
        );
        assertTrue(resRotated.collided, "45度回転により、0度では衝突しない距離でも角が衝突するはず");

        // 一方、完全に離れている場合は衝突しない
        PracticeManager.CollisionResult resFar = PracticeManager.checkObbCollision(
            2.0, 2.0, angleA, 0.5,
            3.0, 2.0, 0.0, 0.5
        );
        assertFalse(resFar.collided, "回転していても十分に離れていれば衝突しない");
    }
}
