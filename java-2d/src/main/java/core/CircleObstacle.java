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
        // OBBのローカル軸
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        // 円中心からOBB中心へのベクトル
        double vx = centerX - cx;
        double vy = centerY - cy;

        // OBBローカル座標系へ円中心を変換
        // x_local = vx * cos + vy * sin
        // y_local = -vx * sin + vy * cos
        double localCenterX = vx * cos + vy * sin;
        double localCenterY = -vx * sin + vy * cos;

        // OBBの範囲 [-halfSize, halfSize] にクランプして最近接点を求める
        double clampedX = clamp(localCenterX, -halfSize, halfSize);
        double clampedY = clamp(localCenterY, -halfSize, halfSize);

        // 最近接点をワールド座標に戻す
        double nearestWorldX = cx + clampedX * cos - clampedY * sin;
        double nearestWorldY = cy + clampedX * sin + clampedY * cos;

        // 円中心と最近接点との距離
        double dx = nearestWorldX - centerX;
        double dy = nearestWorldY - centerY;
        double dist = Math.hypot(dx, dy);

        if (dist >= radius) {
            // 最近接点が円の外側にあるため衝突なし
            return null;
        }

        double mtvX, mtvY;
        double depth;

        if (dist > 1e-9) {
            // 通常の衝突判定
            depth = radius - dist;
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

            double minDist = Math.min(Math.min(distR, distL), Math.min(distT, distB));
            depth = minDist + radius;

            double nx, ny;
            if (minDist == distR) {
                // 右辺に近い（円は右から侵入） -> ロボットを左（ローカル -x方向）へ押し戻す
                nx = -cos;
                ny = -sin;
            } else if (minDist == distL) {
                // 左辺に近い（円は左から侵入） -> ロボットを右（ローカル +x方向）へ押し戻す
                nx = cos;
                ny = sin;
            } else if (minDist == distT) {
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

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
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
