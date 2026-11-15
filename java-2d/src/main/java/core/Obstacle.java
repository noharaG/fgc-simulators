package core;

/**
 * 将来の衝突判定用の障害物（立入禁止エリア）インターフェース。
 */
public interface Obstacle {
    /**
     * 指定された座標が障害物に衝突（進入）しているかどうかを判定します。
     * 
     * @param x ロボットのX座標 (m)
     * @param y ロボットのY座標 (m)
     * @param robotRadius ロボットの半径 (m)
     * @return 衝突している場合は true
     */
    boolean collidesWith(double x, double y, double robotRadius);
}
