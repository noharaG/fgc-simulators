package io;

import com.google.gson.Gson;
import core.CircleObstacle;
import core.CapsuleObstacle;
import core.FieldObstacle;
import core.PolygonObstacle;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * field_obstacles.json を読み込み、FieldObstacle のリストを生成するローダークラス。
 * パース失敗時は明確な例外を投げ、サイレントな失敗（空のリストを返すなど）を防ぎます。
 */
public class FieldObstacleLoader {

    /**
     * 指定されたJSONパスから障害物の設定データを読み込み、FieldObstacleオブジェクトのリストを返します。
     * 
     * @param path JSONファイルのパス
     * @return 読み込まれた障害物オブジェクトのリスト
     * @throws IOException ファイルの読み込み、またはJSONのパースに失敗した場合
     */
    public static List<FieldObstacle> load(String path) throws IOException {
        try (Reader reader = new FileReader(path)) {
            Gson gson = new Gson();
            FieldObstaclesJson data = gson.fromJson(reader, FieldObstaclesJson.class);
            
            if (data == null) {
                throw new IOException("JSONファイルが空であるか、ルート要素が存在しません。パス: " + path);
            }
            if (data.obstacles == null) {
                throw new IOException("JSON内に 'obstacles' リストが見つかりません。スキーマを確認してください。パス: " + path);
            }

            List<FieldObstacle> list = new ArrayList<>();
            for (ObstacleJson obj : data.obstacles) {
                if (obj.id == null || obj.type == null) {
                    throw new IOException("障害物の 'id' または 'type' が定義されていません。");
                }

                // カテゴリが未定義の場合はIDやタイプからフォールバック
                String category = obj.category != null ? obj.category : "default";

                switch (obj.type) {
                    case "polygon":
                        if (obj.vertices == null || obj.vertices.length == 0) {
                            throw new IOException("多角形(polygon)障害物に 'vertices' (頂点リスト) が定義されていません。ID: " + obj.id);
                        }
                        list.add(new PolygonObstacle(obj.id, category, obj.vertices));
                        break;

                    case "circle":
                        if (obj.center == null || obj.center.length < 2) {
                            throw new IOException("円(circle)障害物に 'center' (中心座標) が定義されていません。ID: " + obj.id);
                        }
                        // 拡張点: 将来的にロボットが高さ0.5mを超えて干渉するようになったら、
                        // ここで lean_per_0_5m (傾き) を使って円柱から3Dカプセル化（または軸延長）して衝突判定に反映する。
                        list.add(new CircleObstacle(obj.id, category, obj.center, obj.radius, obj.lean_per_0_5m));
                        break;

                    case "capsule":
                        if (obj.p1 == null || obj.p1.length < 2 || obj.p2 == null || obj.p2.length < 2) {
                            throw new IOException("カプセル(capsule)障害物に軸の端点 'p1' または 'p2' が定義されていません。ID: " + obj.id);
                        }
                        // 拡張点: 同様に、将来的に高さ制限による傾き lean_per_0_5m を反映して、
                        // 3D空間上での軸線分の延長・傾斜変形を行い、高所干渉判定に対応する。
                        list.add(new CapsuleObstacle(obj.id, category, obj.p1, obj.p2, obj.radius, obj.lean_per_0_5m));
                        break;

                    default:
                        // 拡張点: ソフトな侵入禁止ゾーン（ロボットに反発係数をかける、あるいは摩擦係数を変えるような
                        // 物理応答の異なる非剛体障害物など）を追加する場合、ここに新しいタイプ判定（例: "soft_zone"）を追加する。
                        throw new IOException("サポートされていない障害物タイプです: " + obj.type + " (ID: " + obj.id + ")");
                }
            }
            return list;
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("障害物JSONファイルのパースエラー: " + e.getMessage(), e);
        }
    }

    // --- Gsonマッピング用の内部データ構造定義 ---

    private static class FieldObstaclesJson {
        String schema_version;
        List<ObstacleJson> obstacles;
    }

    private static class ObstacleJson {
        String id;
        String type;
        String category;
        double[][] vertices;
        double[] center;
        double radius;
        double[] p1;
        double[] p2;
        double[] lean_per_0_5m;
    }
}
