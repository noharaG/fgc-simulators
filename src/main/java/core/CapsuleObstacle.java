package core;

/**
 * カプセル形状（Capsule）のフィールド障害物を表すクラス。
 * 線分 p1-p2 を中心軸とし、半径 radius で太らせた形状です。
 * OBBとの衝突判定は、線分を複数点（端点＋等分点）にサンプリングし、
 * 各点について円とOBBの衝突判定を実行する簡易かつ十分な方法で解決します。
 */
public class CapsuleObstacle implements FieldObstacle {
    private final String id;
    private final String category;
    private final double[] p1;         // 始点座標 [x, y] (m)
    private final double[] p2;         // 終点座標 [x, y] (m)
    private final double radius;       // 半径 (m)
    private final double[] lean;       // 傾き [dx, dy] (将来拡張用、現状は未使用として保持)

    /**
     * @param id 障害物ID
     * @param category カテゴリ
     * @param p1 始点座標 [x, y]
     * @param p2 終点座標 [x, y]
     * @param radius 半径
     * @param lean 傾き [dx, dy]
     */
    public CapsuleObstacle(String id, String category, double[] p1, double[] p2, double radius, double[] lean) {
        this.id = id;
        this.category = category;
        this.p1 = p1;
        this.p2 = p2;
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
        // カプセル形状を多角形近似（半円＋長方形）して取得します
        // 2つの半円をそれぞれ8分割、計16頂点で構成
        int halfSegments = 8;
        int totalPoints = halfSegments * 2;
        double[][] points = new double[totalPoints][2];

        double dx = p2[0] - p1[0];
        double dy = p2[1] - p1[1];
        double phi = Math.atan2(dy, dx);

        int idx = 0;
        // 1. p2側の半円 (角度 phi - PI/2 から phi + PI/2 まで増加)
        for (int i = 0; i < halfSegments; i++) {
            double alpha = (phi - Math.PI / 2.0) + Math.PI * i / (halfSegments - 1);
            points[idx][0] = p2[0] + radius * Math.cos(alpha);
            points[idx][1] = p2[1] + radius * Math.sin(alpha);
            idx++;
        }

        // 2. p1側の半円 (角度 phi + PI/2 から phi + 3*PI/2 まで増加)
        for (int i = 0; i < halfSegments; i++) {
            double alpha = (phi + Math.PI / 2.0) + Math.PI * i / (halfSegments - 1);
            points[idx][0] = p1[0] + radius * Math.cos(alpha);
            points[idx][1] = p1[1] + radius * Math.sin(alpha);
            idx++;
        }

        return points;
    }

    @Override
    public CollisionResult collideOBB(double cx, double cy, double theta, double halfSize) {
        // 線分 p1-p2 を N 点にサンプリングして、OBBとそれぞれの円との衝突判定を行う
        int sampleCount = 7;
        CollisionResult deepestCollision = null;

        for (int i = 0; i < sampleCount; i++) {
            double t = (double) i / (sampleCount - 1);
            double sx = p1[0] + t * (p2[0] - p1[0]);
            double sy = p1[1] + t * (p2[1] - p1[1]);

            // 各サンプル点を中心とする仮想的な円障害物を生成
            CircleObstacle tempCircle = new CircleObstacle(id, category, new double[]{sx, sy}, radius, lean);
            CollisionResult res = tempCircle.collideOBB(cx, cy, theta, halfSize);

            if (res != null) {
                if (deepestCollision == null || res.depth > deepestCollision.depth) {
                    deepestCollision = res;
                }
            }
        }

        if (deepestCollision != null) {
            // カプセルの中心軸線分 p1 -> p2 の方向角 phi_axis を計算
            double phiAxis = Math.atan2(p2[1] - p1[1], p2[0] - p1[0]);
            return new CollisionResult(
                deepestCollision.mtvX,
                deepestCollision.mtvY,
                deepestCollision.depth,
                deepestCollision.contactPoint,
                true,
                phiAxis
            );
        }

        return null;
    }

    @Override
    public CollisionResult collideCircle(double cx, double cy, double r) {
        double dx = p2[0] - p1[0];
        double dy = p2[1] - p1[1];
        double lenSq = dx * dx + dy * dy;
        double t = 0.0;
        if (lenSq > 1e-9) {
            t = ((cx - p1[0]) * dx + (cy - p1[1]) * dy) / lenSq;
            t = Math.max(0.0, Math.min(1.0, t));
        }
        double qx = p1[0] + t * dx;
        double qy = p1[1] + t * dy;

        double dcx = cx - qx;
        double dcy = cy - qy;
        double dist = Math.hypot(dcx, dcy);
        double minDist = radius + r;
        if (dist >= minDist) {
            return null;
        }

        double depth = minDist - dist;
        double mtvX, mtvY;

        if (dist > 1e-9) {
            double nx = dcx / dist; // カプセル軸の最近接点から円中心への方向 (押し出し方向)
            double ny = dcy / dist;
            mtvX = nx * depth;
            mtvY = ny * depth;
        } else {
            // カプセル軸上に円中心が完全に乗っている場合
            mtvX = depth;
            mtvY = 0.0;
        }

        double[] contactPoint = new double[]{
            qx + (dcx / (dist > 1e-9 ? dist : 1.0)) * (radius - depth * 0.5),
            qy + (dcy / (dist > 1e-9 ? dist : 1.0)) * (radius - depth * 0.5)
        };

        // カプセル軸の方向角
        double phiAxis = Math.atan2(dy, dx);
        return new CollisionResult(mtvX, mtvY, depth, contactPoint, true, phiAxis);
    }

    // 将来の拡張用のゲッター（leanメタデータの保持）
    public double[] getLean() {
        return lean;
    }

    public double[] getP1() {
        return p1;
    }

    public double[] getP2() {
        return p2;
    }

    public double getRadius() {
        return radius;
    }
}
