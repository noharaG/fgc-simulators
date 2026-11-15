package core;

/**
 * ロボットの境界ボックス（OBB: Oriented Bounding Box）情報と、
 * 衝突応答のための物理情報をまとめた軽量データクラス。
 */
public class RobotOBB {
    /** ボディ中心のX座標 (m) */
    public final double cx;
    /** ボディ中心のY座標 (m) */
    public final double cy;
    /** 向き（ラジアン） */
    public final double heading;
    /** ロボットの一辺の半長 (m) */
    public final double halfSize;
    /** X方向の走行速度 (m/s) */
    public final double vx;
    /** Y方向の走行速度 (m/s) */
    public final double vy;

    /**
     * 速度情報なしでOBBを生成します（デフォルト速度0）。
     */
    public RobotOBB(double cx, double cy, double heading, double halfSize) {
        this(cx, cy, heading, halfSize, 0.0, 0.0);
    }

    /**
     * 位置、向き、サイズ、および速度を指定してOBBを生成します。
     */
    public RobotOBB(double cx, double cy, double heading, double halfSize, double vx, double vy) {
        this.cx = cx;
        this.cy = cy;
        this.heading = heading;
        this.halfSize = halfSize;
        this.vx = vx;
        this.vy = vy;
    }
}
