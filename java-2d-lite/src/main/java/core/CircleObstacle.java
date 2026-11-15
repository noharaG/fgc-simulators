package core;

/**
 * 円形状（Circle）のフィールド障害物を表すクラス。
 * ロボットのOBBと円の衝突判定をローカル座標への投影クランプ法で解き、最小移動ベクトル（MTV）を算出します。
 */
public class CircleObstacle implements FieldObstacle {
    private final String id;
    private final String category;
    private final double centerX;      // 円の中心X座標 (m)
    private final double centerY;      // 円の中心Y座標 (m)
    private final double radius;       // 円の半径 (m)
    private final double[] lean;       // 傾き [dx, dy] (将来拡張用、現状は未使用として保持)

    /**
     * @param id 障害物ID
     * @param category カテゴリ
     * @param center 中心座標 [x, y]
     * @param radius 半径
     * @param lean 傾き [dx, dy] (0.5mあたりの傾き)
     */
    public CircleObstacle(String id, String category, double[] center, double radius, double[] lean) {
        this.id = id;
        this.category = category;
        this.centerX = center[0];
        this.centerY = center[1];
        this.radius = radius;
        this.lean = lean != null ? lean : new double[]{0.0, 0.0};
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String category() {
        return category;
    }

    @Override
    public double[][] outlinePolygon() {
        // 円の描画・輪郭用に、円を16分割した多角形近似を返します
        int segments = 16;
        double[][] points = new double[segments][2];
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            points[i][0] = centerX + radius * Math.cos(angle);
            points[i][1] = centerY + radius * Math.sin(angle);
        }
        return points;
    }

    @Override
    public CollisionResult collideOBB(double cx, double cy, double theta, double halfSize) {
        return collideCircleVsOBB(centerX, centerY, radius, cx, cy, theta, halfSize);
    }

    /**
     * 静的汎用メソッド: 円 (circleX, circleY, circleRadius) と OBB (cx, cy, theta, halfSize) の衝突判定。
     * 外接円による早期棄却を先頭に置くことで、ほとんどの非衝突ペアを即座に除外できる。
     * 純関数（共有状態なし）のため parallelStream 内から安全に呼び出せる。
     */
    public static CollisionResult collideCircleVsOBB(double circleX, double circleY, double circleRadius,
                                                      double cx, double cy, double theta, double halfSize) {
        // 早期棄却: OBBの外接円より外なら衝突不可能（偽陰性ゼロ）
        double bound = circleRadius + halfSize * 1.4142135623730951;
        if ((circleX - cx) * (circleX - cx) + (circleY - cy) * (circleY - cy) > bound * bound) return null;

        // OBBのローカル軸
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        // 円中心からOBB中心へのベクトル
        double vx = circleX - cx;
        double vy = circleY - cy;

        // OBBローカル座標系へ円中心を変換
        // x_local = vx * cos + vy * sin
        // y_local = -vx * sin + vy * cos
        double localCenterX = vx * cos + vy * sin;
        double localCenterY = -vx * sin + vy * cos;

        // OBBの範囲 [-halfSize, halfSize] にクランプして最近接点を求める
        double clampedX = Math.max(-halfSize, Math.min(halfSize, localCenterX));
        double clampedY = Math.max(-halfSize, Math.min(halfSize, localCenterY));

        // 最近接点をワールド座標に戻す
        double nearestWorldX = cx + clampedX * cos - clampedY * sin;
        double nearestWorldY = cy + clampedX * sin + clampedY * cos;

        // 円中心と最近接点との距離
        double dx = nearestWorldX - circleX;
        double dy = nearestWorldY - circleY;
        double distSq = dx * dx + dy * dy;

        if (distSq >= circleRadius * circleRadius) {
            // 最近接点が円の外側にあるため衝突なし
            return null;
        }

        double dist = Math.sqrt(distSq);
        double mtvX, mtvY;
        double depth;

        if (dist > 1e-9) {
            // 通常の衝突判定
            depth = circleRadius - dist;
            // 押し戻し方向は、円中心から最近接点へ向かう方向 (ロボットを外へ押し出す)
            double nx = dx / dist;
            double ny = dy / dist;
            mtvX = nx * depth;
            mtvY = ny * depth;
        } else {
            // 円中心がちょうどロボットの輪郭/内部（d≒0）にある場合のフォールバック
            // ロボットのローカル座標で最も近い辺方向へ押し出す
            double distR = halfSize - localCenterX;
            double distL = localCenterX - (-halfSize);
            double distT = halfSize - localCenterY;
            double distB = localCenterY - (-halfSize);

            double minD = Math.min(Math.min(distR, distL), Math.min(distT, distB));
            depth = minD + circleRadius;

            double nx, ny;
            if (minD == distR) {
                // 右辺に近い（円は右から侵入） -> ロボットを左（ローカル -x方向）へ押し戻す
                nx = -cos;
                ny = -sin;
            } else if (minD == distL) {
                // 左辺に近い（円は左から侵入） -> ロボットを右（ローカル +x方向）へ押し戻す
                nx = cos;
                ny = sin;
            } else if (minD == distT) {
                // 上辺に近い（円は上から侵入） -> ロボットを下（ローカル -y方向）へ押し戻す
                nx = sin;
                ny = -cos;
            } else {
                // 下辺に近い（円は下から侵入） -> ロボットを上（ローカル +y方向）へ押し戻す
                nx = -sin;
                ny = cos;
            }
            mtvX = nx * depth;
            mtvY = ny * depth;
        }

        // 接触点は、円表面とOBBの重なりの中間付近
        double[] contactPoint = new double[]{
            nearestWorldX - mtvX * 0.5,
            nearestWorldY - mtvY * 0.5
        };

        return new CollisionResult(mtvX, mtvY, depth, contactPoint);
    }

    @Override
    public CollisionResult collideCircle(double cx, double cy, double r) {
        double dx = cx - centerX;
        double dy = cy - centerY;
        double dist = Math.hypot(dx, dy);
        double minDist = radius + r;
        if (dist >= minDist) {
            return null;
        }

        double mtvX, mtvY;
        double depth = minDist - dist;

        if (dist > 1e-9) {
            double nx = dx / dist; // 障害物(円)中心から相手(ボール)中心への方向
            double ny = dy / dist;
            mtvX = nx * depth;
            mtvY = ny * depth;
        } else {
            // 中心が完全に重なっている場合
            mtvX = depth;
            mtvY = 0.0;
        }

        double[] contactPoint = new double[]{
            centerX + (dx / (dist > 1e-9 ? dist : 1.0)) * (radius - depth * 0.5),
            centerY + (dy / (dist > 1e-9 ? dist : 1.0)) * (radius - depth * 0.5)
        };

        return new CollisionResult(mtvX, mtvY, depth, contactPoint);
    }

    // 将来の拡張用のゲッター（leanメタデータの保持）
    public double[] getLean() {
        return lean;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getRadius() {
        return radius;
    }
}
