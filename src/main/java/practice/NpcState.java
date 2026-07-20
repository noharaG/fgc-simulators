package practice;

/**
 * 特定のフレームにおけるNPCロボットの物理状態および情報を保持する不変クラス。
 * 意思決定（Behavior）時や衝突処理時に使用されます。
 */
public class NpcState {
    /** NPCの識別番号 */
    public final int id;
    /** ボディ中心のX座標 (m) */
    public final double x;
    /** ボディ中心のY座標 (m) */
    public final double y;
    /** 向き (ラジアン, +x基準で反時計回り) */
    public final double heading;
    /** 実速度 (m/s) */
    public final double v;
    /** 実角速度 (rad/s) */
    public final double omega;
    /** 現在実行中の挙動の名称 */
    public final String behaviorName;

    /**
     * @param id NPCのID
     * @param x X座標 (m)
     * @param y Y座標 (m)
     * @param heading 向き (ラジアン)
     * @param v 速度 (m/s)
     * @param omega 角速度 (rad/s)
     * @param behaviorName 挙動名
     */
    public NpcState(int id, double x, double y, double heading, double v, double omega, String behaviorName) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.v = v;
        this.omega = omega;
        this.behaviorName = behaviorName;
    }
}
