package core;

/**
 * OBBとフィールド障害物の衝突判定結果を保持する不変（Immutable）クラス。
 * 衝突が発生した場合の最小移動ベクトル（MTV: Minimum Translation Vector）や、
 * めり込み深さ、概算接触点、および壁整列のための衝突辺情報を保持します。
 */
public class CollisionResult {
    public final double mtvX;            // ロボットを押し出すX方向の移動量 (m)
    public final double mtvY;            // ロボットを押し出すY方向の移動量 (m)
    public final double depth;           // 貫通深さ (m)
    public final double[] contactPoint;  // 概算接触点座標 [x, y] (m)
    
    // 壁整列用の追加フィールド
    public final boolean isEdgeOrigin;   // 衝突法線がポリゴンの辺に由来するか (OBB軸由来ではなく)
    public final double edgeAngle;       // 衝突したポリゴン辺の方向角 (rad)

    /**
     * フルコンストラクタ（ポリゴン障害物用）
     * 
     * @param mtvX 押し出しX方向量
     * @param mtvY 押し出しY方向量
     * @param depth 貫通深さ
     * @param contactPoint 概算接触点 (要素数2の配列 [x, y])
     * @param isEdgeOrigin 衝突法線が多角形辺由来か
     * @param edgeAngle 衝突した辺の方向角 (rad)
     */
    public CollisionResult(double mtvX, double mtvY, double depth, double[] contactPoint, boolean isEdgeOrigin, double edgeAngle) {
        this.mtvX = mtvX;
        this.mtvY = mtvY;
        this.depth = depth;
        this.contactPoint = contactPoint;
        this.isEdgeOrigin = isEdgeOrigin;
        this.edgeAngle = edgeAngle;
    }

    /**
     * 互換用コンストラクタ（円やカプセル、整列を行わない衝突用）
     * 
     * @param mtvX 押し出しX方向量
     * @param mtvY 押し出しY方向量
     * @param depth 貫通深さ
     * @param contactPoint 概算接触点 (要素数2の配列 [x, y])
     */
    public CollisionResult(double mtvX, double mtvY, double depth, double[] contactPoint) {
        this(mtvX, mtvY, depth, contactPoint, false, 0.0);
    }
}
