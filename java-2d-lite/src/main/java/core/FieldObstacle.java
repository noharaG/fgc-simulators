package core;

/**
 * フィールド上の物理障害物を抽象化するインターフェース。
 * 衝突判定および描画用の輪郭取得メソッドを提供します。
 */
public interface FieldObstacle {
    /**
     * 障害物のユニークIDを返します。
     * @return 障害物ID
     */
    String id();

    /**
     * 障害物のカテゴリ（例: "suppression_unit", "fire_shield", "brace"）を返します。
     * @return カテゴリ名
     */
    String category();

    /**
     * ロボットのOBB（Oriented Bounding Box）との衝突判定を行います。
     * 
     * @param cx ロボットボディ中心のX座標 (m)
     * @param cy ロボットボディ中心のY座標 (m)
     * @param theta ロボットの向き (rad)
     * @param halfSize ロボットの一辺の半長 (一辺0.5mなら0.25) (m)
     * @return 衝突している場合は衝突結果の CollisionResult、していない場合は null
     */
    CollisionResult collideOBB(double cx, double cy, double theta, double halfSize);

    /**
     * 円（ボールなど）との衝突判定を行います。
     * 
     * @param cx 円の中心X座標 (m)
     * @param cy 円の中心Y座標 (m)
     * @param radius 円の半径 (m)
     * @return 衝突している場合は衝突結果の CollisionResult、していない場合は null
     */
    CollisionResult collideCircle(double cx, double cy, double radius);

    /**
     * 描画用の輪郭点列を返します。
     * 各点は [x, y] 形式の座標です。ポリゴン形状、あるいは円やカプセルを近似多角形化したものです。
     * 
     * @return 輪郭頂点リスト (double[N][2])
     */
    double[][] outlinePolygon();
}

