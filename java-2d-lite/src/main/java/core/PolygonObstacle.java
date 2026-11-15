package core;

/**
 * 凸多角形（Polygon）のフィールド物理障害物。
 * すべての頂点はCCW（反時計回り）で定義されている凸多角形です。
 * 衝突判定には分離軸定理（SAT: Separating Axis Theorem）を用い、最小移動ベクトル（MTV）を算出します。
 */
public class PolygonObstacle implements FieldObstacle {
    private final String id;
    private final String category;
    private final double[][] vertices; // [N][2] 頂点座標 (m)

    /**
     * @param id 障害物ID
     * @param category カテゴリ名
     * @param vertices 凸多角形の頂点リスト（CCW、閉じる点は不要）
     */
    public PolygonObstacle(String id, String category, double[][] vertices) {
        this.id = id;
        this.category = category;
        this.vertices = vertices;
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
        // 描画用に頂点配列の複製を返す
        double[][] copy = new double[vertices.length][2];
        for (int i = 0; i < vertices.length; i++) {
            copy[i][0] = vertices[i][0];
            copy[i][1] = vertices[i][1];
        }
        return copy;
    }

    @Override
    public CollisionResult collideOBB(double cx, double cy, double theta, double halfSize) {
        // OBBの4つの頂点の座標を計算
        double[][] cornersOBB = getOBBCorners(cx, cy, theta, halfSize);

        // 分離軸のリストを作成
        // 1. OBBの2つのローカル軸
        double cosT = Math.cos(theta);
        double sinT = Math.sin(theta);
        double[][] axesOBB = {
            {cosT, sinT},
            {-sinT, cosT}
        };

        // 2. ポリゴンの各辺の外向き法線
        int nVertices = vertices.length;
        double[][] axesPoly = new double[nVertices][2];
        for (int i = 0; i < nVertices; i++) {
            double[] p1 = vertices[i];
            double[] p2 = vertices[(i + 1) % nVertices];
            double dx = p2[0] - p1[0];
            double dy = p2[1] - p1[1];
            // CCW（反時計回り）なので、右に90度回転した (dy, -dx) が外向き法線
            double len = Math.hypot(dx, dy);
            if (len > 1e-9) {
                axesPoly[i][0] = dy / len;
                axesPoly[i][1] = -dx / len;
            } else {
                axesPoly[i][0] = 1.0;
                axesPoly[i][1] = 0.0;
            }
        }

        double minOverlap = Double.MAX_VALUE;
        double mtvX = 0.0;
        double mtvY = 0.0;

        // 壁整列判定用の変数
        boolean isEdgeOrigin = false;
        double edgeAngle = 0.0;

        int totalAxes = axesOBB.length + axesPoly.length;

        // ポリゴンの重心（押し出しベクトルの向き判定用）
        double polyCenterX = 0.0;
        double polyCenterY = 0.0;
        for (double[] v : vertices) {
            polyCenterX += v[0];
            polyCenterY += v[1];
        }
        polyCenterX /= nVertices;
        polyCenterY /= nVertices;

        // すべての分離軸候補について重なりを検証
        for (int axisIdx = 0; axisIdx < totalAxes; axisIdx++) {
            double nx, ny;
            if (axisIdx < axesOBB.length) {
                nx = axesOBB[axisIdx][0];
                ny = axesOBB[axisIdx][1];
            } else {
                nx = axesPoly[axisIdx - axesOBB.length][0];
                ny = axesPoly[axisIdx - axesOBB.length][1];
            }

            // 形状を軸上に投影
            double[] projOBB = project(cornersOBB, nx, ny);
            double[] projPoly = project(vertices, nx, ny);

            // 重なり量を計算
            double overlap = getOverlap(projOBB, projPoly);
            if (overlap <= 0.0) {
                // 重なりがない（隙間がある）場合は衝突していない
                return null;
            }

            // 等しい重なりの場合は、ポリゴン辺由来 (axisIdx >= axesOBB.length) を優先する
            if (overlap < minOverlap || (overlap == minOverlap && axisIdx >= axesOBB.length && !isEdgeOrigin)) {
                minOverlap = overlap;
                
                // 押し戻し方向（ロボットをポリゴンの外側へ動かす方向）を決定
                // ポリゴン重心からロボット中心 G へのベクトルと法線の内積で向きを揃える
                double toRobotX = cx - polyCenterX;
                double toRobotY = cy - polyCenterY;
                if (toRobotX * nx + toRobotY * ny < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                mtvX = nx * overlap;
                mtvY = ny * overlap;

                // 衝突面がポリゴン辺由来であるかの判定
                isEdgeOrigin = (axisIdx >= axesOBB.length);
            }
        }

        if (isEdgeOrigin) {
            double len = Math.hypot(mtvX, mtvY);
            if (len > 1e-9) {
                double mx = mtvX / len;
                double my = mtvY / len;
                double maxDot = -Double.MAX_VALUE;
                int bestEdgeIdx = 0;
                for (int i = 0; i < nVertices; i++) {
                    double dot = axesPoly[i][0] * mx + axesPoly[i][1] * my;
                    if (dot > maxDot) {
                        maxDot = dot;
                        bestEdgeIdx = i;
                    }
                }
                double[] p1 = vertices[bestEdgeIdx];
                double[] p2 = vertices[(bestEdgeIdx + 1) % nVertices];
                edgeAngle = Math.atan2(p2[1] - p1[1], p2[0] - p1[0]);
            }
        }

        // 接触点の概算
        double[] contactPoint = getPolyOBBContactPoint(cornersOBB, mtvX, mtvY);

        return new CollisionResult(mtvX, mtvY, minOverlap, contactPoint, isEdgeOrigin, edgeAngle);
    }

    private double[][] getOBBCorners(double cx, double cy, double theta, double halfSize) {
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        return new double[][] {
            {cx + halfSize * cos - halfSize * sin, cy + halfSize * sin + halfSize * cos}, // 前左
            {cx - halfSize * cos - halfSize * sin, cy - halfSize * sin + halfSize * cos}, // 後左
            {cx - halfSize * cos + halfSize * sin, cy - halfSize * sin - halfSize * cos}, // 後右
            {cx + halfSize * cos + halfSize * sin, cy + halfSize * sin - halfSize * cos}  // 前右
        };
    }

    private double[] project(double[][] points, double nx, double ny) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] p : points) {
            double val = p[0] * nx + p[1] * ny;
            if (val < min) min = val;
            if (val > max) max = val;
        }
        return new double[]{min, max};
    }

    private double getOverlap(double[] projA, double[] projB) {
        return Math.max(0.0, Math.min(projA[1], projB[1]) - Math.max(projA[0], projB[0]));
    }

    /**
     * OBBの頂点の中から、衝突方向に最も近く、ポリゴンに最も深く貫通している頂点を概算接触点として返します。
     */
    private double[] getPolyOBBContactPoint(double[][] cornersOBB, double mtvX, double mtvY) {
        double len = Math.hypot(mtvX, mtvY);
        if (len < 1e-9) {
            return new double[]{cornersOBB[0][0], cornersOBB[0][1]};
        }
        double dx = -mtvX / len; // 障害物側に向かうベクトル
        double dy = -mtvY / len;

        double maxVal = -Double.MAX_VALUE;
        double[] bestCorner = cornersOBB[0];
        for (double[] corner : cornersOBB) {
            double val = corner[0] * dx + corner[1] * dy;
            if (val > maxVal) {
                maxVal = val;
                bestCorner = corner;
            }
        }
        
        // 押し戻した結果の接触点とするため、めり込みを考慮した中間座標にする
        return new double[]{
            bestCorner[0] - mtvX * 0.5,
            bestCorner[1] - mtvY * 0.5
        };
    }

    @Override
    public CollisionResult collideCircle(double cx, double cy, double r) {
        int nVertices = vertices.length;
        
        // 1. 最も近い頂点を特定する
        double minDistSq = Double.MAX_VALUE;
        double[] nearestVertex = null;
        for (double[] v : vertices) {
            double dx = cx - v[0];
            double dy = cy - v[1];
            double distSq = dx * dx + dy * dy;
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearestVertex = v;
            }
        }
        
        // 分離軸候補のリスト: 各辺の法線 + 最も近い頂点から円中心への軸
        double[][] axes = new double[nVertices + 1][2];
        
        // 辺の法線
        for (int i = 0; i < nVertices; i++) {
            double[] p1 = vertices[i];
            double[] p2 = vertices[(i + 1) % nVertices];
            double dx = p2[0] - p1[0];
            double dy = p2[1] - p1[1];
            double len = Math.hypot(dx, dy);
            if (len > 1e-9) {
                axes[i][0] = dy / len; // CCWなので外向き法線は (dy, -dx)
                axes[i][1] = -dx / len;
            } else {
                axes[i][0] = 1.0;
                axes[i][1] = 0.0;
            }
        }
        
        // 最も近い頂点から円の中心への軸
        if (nearestVertex != null) {
            double dx = cx - nearestVertex[0];
            double dy = cy - nearestVertex[1];
            double len = Math.hypot(dx, dy);
            if (len > 1e-9) {
                axes[nVertices][0] = dx / len;
                axes[nVertices][1] = dy / len;
            } else {
                axes[nVertices][0] = 1.0;
                axes[nVertices][1] = 0.0;
            }
        } else {
            axes[nVertices][0] = 1.0;
            axes[nVertices][1] = 0.0;
        }

        double minOverlap = Double.MAX_VALUE;
        double mtvX = 0.0;
        double mtvY = 0.0;
        
        boolean isEdgeOrigin = false;
        double edgeAngle = 0.0;

        // ポリゴン重心
        double polyCenterX = 0.0;
        double polyCenterY = 0.0;
        for (double[] v : vertices) {
            polyCenterX += v[0];
            polyCenterY += v[1];
        }
        polyCenterX /= nVertices;
        polyCenterY /= nVertices;

        for (int axisIdx = 0; axisIdx < axes.length; axisIdx++) {
            double nx = axes[axisIdx][0];
            double ny = axes[axisIdx][1];

            // ポリゴンを軸上に投影
            double minPoly = Double.MAX_VALUE;
            double maxPoly = -Double.MAX_VALUE;
            for (double[] v : vertices) {
                double val = v[0] * nx + v[1] * ny;
                if (val < minPoly) minPoly = val;
                if (val > maxPoly) maxPoly = val;
            }

            // 円を軸上に投影
            double centerProj = cx * nx + cy * ny;
            double minCircle = centerProj - r;
            double maxCircle = centerProj + r;

            // 重なり
            double overlap = Math.max(0.0, Math.min(maxPoly, maxCircle) - Math.max(minPoly, minCircle));
            if (overlap <= 0.0) {
                return null; // 隙間があるので衝突なし
            }

            if (overlap < minOverlap) {
                minOverlap = overlap;
                
                // 押し出し方向を決定（ポリゴンから円へ）
                double toCircleX = cx - polyCenterX;
                double toCircleY = cy - polyCenterY;
                if (toCircleX * nx + toCircleY * ny < 0) {
                    nx = -nx;
                }
                if (toCircleX * nx + toCircleY * ny < 0) {
                    ny = -ny;
                }
                // 正確な判定: toCircleX * nx + toCircleY * ny の符号をみる
                double dot = toCircleX * nx + toCircleY * ny;
                if (dot < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                mtvX = nx * overlap;
                mtvY = ny * overlap;

                // 衝突面がポリゴン辺由来であるかの判定と角度の算出
                if (axisIdx < nVertices) {
                    isEdgeOrigin = true;
                    double[] p1 = vertices[axisIdx];
                    double[] p2 = vertices[(axisIdx + 1) % nVertices];
                    edgeAngle = Math.atan2(p2[1] - p1[1], p2[0] - p1[0]);
                } else {
                    isEdgeOrigin = false;
                    edgeAngle = 0.0;
                }
            }
        }

        // 接触点の算出 (円の表面付近)
        double len = Math.hypot(mtvX, mtvY);
        double rx = 0.0;
        double ry = 0.0;
        if (len > 1e-9) {
            rx = -mtvX / len;
            ry = -mtvY / len;
        }
        double[] contactPoint = new double[]{
            cx + rx * (r - minOverlap * 0.5),
            cy + ry * (r - minOverlap * 0.5)
        };

        return new CollisionResult(mtvX, mtvY, minOverlap, contactPoint, isEdgeOrigin, edgeAngle);
    }

    // ゲッター（頂点データの取得用）
    public double[][] getVertices() {
        return vertices;
    }
}
