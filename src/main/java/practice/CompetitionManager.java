package practice;

import core.CollisionResolver;
import core.CollisionResult;
import core.CompetitionRobot;
import core.FieldObstacle;
import core.PolygonObstacle;
import core.RobotConfig;
import core.RobotOBB;
import core.SimulationEngine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 3v3対戦モードにおけるロボットの初期配置、運動積分、衝突解消を統括するマネージャクラス。
 */
public class CompetitionManager implements CollisionResolver {

    // 回避ステアリング定数
    private static final double AVOID_RADIUS          = 0.8;  // 回避を開始する距離 (m)
    private static final double AVOID_STRENGTH        = 1.5;  // 回避ベクトルの強さ
    private static final double STALL_CHECK_INTERVAL  = 0.5;  // 膠着チェック間隔 (秒)
    private static final double STALL_DIST_THRESHOLD  = 0.1;  // 膠着判定の移動量閾値 (m)
    private static final double STALL_RECOVERY_DURATION = 0.5; // 脱出継続時間 (秒)

    // 赤青/白ゴールの効用比
    private static final double GOAL_UTILITY_RATIO = 2.0;
    private static final double DESIRED_RANGE_RATIO = 0.6; // 射程に対する目標射撃距離の比率 (0.5〜0.7)

    // 味方住み分け・妨害・ヒステリシス定数
    private static final double ALLY_GOAL_OVERLAP_PENALTY = 0.45;
    private static final double ALLY_SHOOT_OVERLAP_PENALTY = 0.60; // 赤青ゴール重複ペナルティ（2台目で0.36、3台目で0.22）
    private static final double ALLY_BALL_RADIUS           = 1.5;
    private static final double ALLY_AVOID_MULT            = 1.8;
    private static final double ALLY_PREDICT_SECONDS       = 0.5;
    private static final double HYSTERESIS_BONUS           = 0.15;

    // 団子化解消・妨害スケール定数
    private static final double SEPARATION_RADIUS      = 2.5;  // 広範囲分散の有効距離 (m)
    private static final double SEPARATION_STRENGTH    = 6.0;  // 分散力の強さ（約10倍に強化）
    private static final double SEPARATION_NEAR_DIST   = 1.0;  // 目標近接時の分散減衰開始距離 (m)
    private static final double SEPARATION_NEAR_SCALE  = 0.3;  // 目標直前での分散倍率（目標優先）
    private static final double SEPARATION_COMMIT_SCALE = 0.3; // シュートコミット/接触妨害中の分散倍率
    private static final double SEPARATION_MAX_CLAMP   = 2.5;  // 分散貢献の上限クランプ（目標方向を消さない）
    private static final double SEPARATION_DENSITY_FACTOR = 0.40; // 密度比例係数（密集時だけ分散を強化）
    private static final double ZONE_COLLECT_PENALTY   = 0.55; // 同フィールドゾーンに味方がCOLLECT中のペナルティ係数
    private static final double TEAM_ASSIGN_BONUS      = 0.30; // チーム全体最適化で割り当てられたボールへの効用ボーナス
    private static final double INTERFERE_GLOBAL_SCALE = 0.3;  // 妨害全体スケール（得点優先）

    // 壁際ペナルティ定数（回収効用）
    private static final double WALL_PENALTY_NEAR = 0.3;  // 壁から0.3m以内は最小係数に固定 (m)
    private static final double WALL_PENALTY_FAR  = 1.5;  // 壁から1.5m以上は係数1.0 (m)
    private static final double WALL_PENALTY_MIN  = 0.15; // 壁近接時の最小効用係数

    // 自陣ゴール近接ボーナス定数（回収効用）
    private static final double GOAL_BONUS_STRENGTH = 0.5; // ゴール直前ボールの最大追加ボーナス倍率
    private static final double GOAL_BONUS_DECAY    = 2.0; // ボーナスの距離減衰スケール (m)

    // 射出回転効率のための最低保持率定数（改善2）
    private static final double MIN_SHOOT_HOLD_RATIO = 0.40; // この割合以上溜まったら射出効用を最大化（満タン待ちなし）

    // 射撃位置選択の先読み定数（改善1: 撃ち終わり後の回収アクセスボーナス）
    private static final double LOOKAHEAD_WEIGHT      = 0.03; // 先読みボーナスの重み（命中を犠牲にしない程度）
    private static final double LOOKAHEAD_BALL_RADIUS = 2.0;  // 先読みに使う周辺ボール半径 (m)

    // 射撃位置選択の移動コスト定数（改善5: 時間あたり価値評価）
    private static final double MOVE_TIME_REFERENCE_DURATION = 1.5; // 典型的な射出セッション時間 (秒)

    // シュートコミット定数
    private static final double SHOOT_COMMIT_TIMEOUT = 3.0; // コミット中に撃てない継続秒数の解除閾値

    // 差動2輪NPC旋回制御定数
    private static final double DIFF_KP_OMEGA       = 3.5;                // 旋回P制御ゲイン（大きすぎるとオーバーシュート）
    private static final double DIFF_KD_OMEGA       = 1.0;                // 旋回D制御ゲイン
    private static final double DIFF_ANGLE_DEADBAND = 0.087;              // 旋回打切り幅（デッドバンド、rad ≈ 5度、首振り防止）
    private static final double DIFF_FORWARD_ANGLE  = Math.PI / 4.0;     // 前進開始角度差閾値 (rad = 45度)
    private static final double DIFF_HEAD_PURE_BLEND = 0.7;              // heading計算での純目標方向ブレンド比率

    private final RobotConfig baseConfig;
    private final List<CompetitionRobot> robots;
    private boolean isBlueAlliance = false; // プレイヤーの陣営選択状態
    private int debugLogCounter = 0; // デバッグログ用カウンター

    // スタートテープ領域の座標境界 (CAD実寸のデフォルト値、赤＝左、青＝右)
    private double redXMin = 0.000, redXMax = 0.552, redYMin = 0.748, redYMax = 4.852;
    private double blueXMin = 6.448, blueXMax = 7.000, blueYMin = 0.748, blueYMax = 4.852;

    // --- レイキャスト用の障害物エッジキャッシュ（初期化時に一度だけ構築） ---
    // isRayBlocked が毎回 obstacles.outlinePolygon()（新規 double[N][2] アロケーション）と
    // ポリゴン頂点の展開を行うのを排除するため、遮蔽対象エッジを flat 配列へ前展開して保持する。
    // フィールド障害物は読み込み後に変化しない静的ジオメトリのため安全にキャッシュできる。
    // 各エッジ i について edgeX1[i],edgeY1[i] - edgeX2[i],edgeY2[i] が線分の端点、
    // edge{Min,Max}{X,Y} がエッジのAABB（レイ vs エッジの事前棄却用）。
    private boolean rayCacheBuilt = false;
    private List<FieldObstacle> rayCacheSource = null; // キャッシュ構築元リスト（同一性検証用）
    private double[] edgeX1, edgeY1, edgeX2, edgeY2;
    private double[] edgeMinX, edgeMaxX, edgeMinY, edgeMaxY;
    private int edgeCount = 0;

    /**
     * 障害物エッジキャッシュを構築する。isRayBlocked の semantics（tape/brace 除外、
     * suppression_unit 上辺(y==0.747)の開口部除外）を保ったまま、遮蔽対象となるエッジのみ展開する。
     * 「狙い点(sx,sy)がエッジ上なら遮蔽扱いしない」判定は呼び出しごとに sx,sy が変わるため
     * キャッシュには含めず、isRayBlocked 内で従来通り実施する。
     */
    private void buildRayCache(List<FieldObstacle> obstacles) {
        rayCacheSource = obstacles;
        rayCacheBuilt = true;
        edgeCount = 0;
        if (obstacles == null) {
            edgeX1 = edgeY1 = edgeX2 = edgeY2 = new double[0];
            edgeMinX = edgeMaxX = edgeMinY = edgeMaxY = new double[0];
            return;
        }
        // エッジ総数を数える（isRayBlocked と同じ除外条件を適用）
        int total = 0;
        for (FieldObstacle obs : obstacles) {
            if ("tape".equals(obs.category()) || "brace".equals(obs.category())) continue;
            double[][] vertices = obs.outlinePolygon();
            if (vertices == null || vertices.length < 3) continue;
            int numV = vertices.length;
            boolean suppression = "suppression_unit".equals(obs.id());
            for (int i = 0; i < numV; i++) {
                int j = (i + 1) % numV;
                if (suppression) {
                    double vy1 = vertices[i][1];
                    double vy2 = vertices[j][1];
                    if (Math.abs(vy1 - 0.747) < 1e-4 && Math.abs(vy2 - 0.747) < 1e-4) continue;
                }
                total++;
            }
        }
        edgeX1 = new double[total]; edgeY1 = new double[total];
        edgeX2 = new double[total]; edgeY2 = new double[total];
        edgeMinX = new double[total]; edgeMaxX = new double[total];
        edgeMinY = new double[total]; edgeMaxY = new double[total];
        int k = 0;
        for (FieldObstacle obs : obstacles) {
            if ("tape".equals(obs.category()) || "brace".equals(obs.category())) continue;
            double[][] vertices = obs.outlinePolygon();
            if (vertices == null || vertices.length < 3) continue;
            int numV = vertices.length;
            boolean suppression = "suppression_unit".equals(obs.id());
            for (int i = 0; i < numV; i++) {
                int j = (i + 1) % numV;
                double vx1 = vertices[i][0], vy1 = vertices[i][1];
                double vx2 = vertices[j][0], vy2 = vertices[j][1];
                if (suppression && Math.abs(vy1 - 0.747) < 1e-4 && Math.abs(vy2 - 0.747) < 1e-4) continue;
                edgeX1[k] = vx1; edgeY1[k] = vy1;
                edgeX2[k] = vx2; edgeY2[k] = vy2;
                edgeMinX[k] = Math.min(vx1, vx2); edgeMaxX[k] = Math.max(vx1, vx2);
                edgeMinY[k] = Math.min(vy1, vy2); edgeMaxY[k] = Math.max(vy1, vy2);
                k++;
            }
        }
        edgeCount = k;
    }

    // --- ボール空間グリッド（密度カウント・近傍探索の O(N^2) 排除用、再利用スクラッチ） ---
    // セルサイズ1.0m。座標は物理側で [0.05, 6.95] にクランプされるため、
    // floor(coord)+1 でセル番号を取れば [-1,7] のオフセットで GRID_DIM=9 に収まりクランプ不要。
    // グリッドはセル粗選別→正確な距離フィルタの2段構えで、全走査と完全一致する結果を返す。
    // updateBehaviors は単一スレッドから逐次呼ばれ、各ロボットの意思決定直前にその時点の
    // ボール状態で再構築するため再入・並行アクセスは無い。
    private static final int BALL_GRID_DIM = 9; // coords floor in [-1,7] -> idx [0,8]
    private static final int BALL_GRID_CELLS = BALL_GRID_DIM * BALL_GRID_DIM;
    private final int[] ballCellHead = new int[BALL_GRID_CELLS]; // 各セルの先頭ボールインデックス（-1=空）
    private int[] ballCellNext;   // 連結リスト: 同一セル内の次のボールインデックス
    private int[] ballGridCellX;  // 各ボールのセルX（density近傍探索で再利用）
    private int[] ballGridCellY;  // 各ボールのセルY
    private int[] ballDensity;    // 各ボールの density（半径1.0m内の有効ボール数, 全走査と一致）
    private int gridBallCount = 0;

    private static int ballCellIndex(double coord) {
        int c = (int) Math.floor(coord) + 1;
        if (c < 0) c = 0;
        else if (c >= BALL_GRID_DIM) c = BALL_GRID_DIM - 1;
        return c;
    }

    /**
     * 現在のボール状態（collected/scored 除外）で空間グリッドを構築し、
     * 併せて各ボールの density（半径1.0m以内の有効ボール数）を全走査と完全一致するよう算出する。
     * 各ロボットの意思決定直前に呼び出すことで、その時点のボール状態を正確に反映する。
     */
    private void buildBallGrid(List<core.BallState> balls) {
        int n = balls.size();
        gridBallCount = n;
        if (ballCellNext == null || ballCellNext.length < n) {
            ballCellNext = new int[n];
            ballGridCellX = new int[n];
            ballGridCellY = new int[n];
            ballDensity = new int[n];
        }
        for (int i = 0; i < BALL_GRID_CELLS; i++) ballCellHead[i] = -1;
        // 1パス目: 有効ボールをセルへ挿入（無効ボールは cellX=-1 で印を付け除外）
        for (int i = 0; i < n; i++) {
            core.BallState b = balls.get(i);
            if (b.collected || b.scored) {
                ballGridCellX[i] = -1;
                continue;
            }
            int cx = ballCellIndex(b.x);
            int cy = ballCellIndex(b.y);
            ballGridCellX[i] = cx;
            ballGridCellY[i] = cy;
            int cell = cy * BALL_GRID_DIM + cx;
            ballCellNext[i] = ballCellHead[cell];
            ballCellHead[cell] = i;
        }
        // 2パス目: 各有効ボールの半径1.0m以内の有効ボール数を 3x3 近傍セル粗選別＋正確距離で数える。
        // セルサイズ=半径=1.0 のため、距離1.0以内の点は必ず ±1 セル内に存在し全走査と一致する。
        for (int i = 0; i < n; i++) {
            int cx = ballGridCellX[i];
            if (cx < 0) { ballDensity[i] = 0; continue; }
            int cy = ballGridCellY[i];
            core.BallState b = balls.get(i);
            double bx = b.x, by = b.y;
            int cnt = 0;
            int gx0 = cx - 1; if (gx0 < 0) gx0 = 0;
            int gx1 = cx + 1; if (gx1 >= BALL_GRID_DIM) gx1 = BALL_GRID_DIM - 1;
            int gy0 = cy - 1; if (gy0 < 0) gy0 = 0;
            int gy1 = cy + 1; if (gy1 >= BALL_GRID_DIM) gy1 = BALL_GRID_DIM - 1;
            for (int gy = gy0; gy <= gy1; gy++) {
                int rowBase = gy * BALL_GRID_DIM;
                for (int gx = gx0; gx <= gx1; gx++) {
                    for (int o = ballCellHead[rowBase + gx]; o != -1; o = ballCellNext[o]) {
                        core.BallState ob = balls.get(o);
                        double dx = ob.x - bx, dy = ob.y - by;
                        if (dx * dx + dy * dy <= 1.0) cnt++; // 半径1.0 -> 2乗距離 1.0
                    }
                }
            }
            ballDensity[i] = cnt;
        }
    }

    // 近傍探索の候補インデックス収集用スクラッチ（半径<=1.0m の 3x3 セル列挙、再利用）
    private int[] ballNeighborScratch;

    // --- 射撃位置サンプリングの静的グリッド点キャッシュ（意思決定ごとの 36 個 new double[] を排除） ---
    // グリッド点座標は xStart/xEnd（アライアンス依存の定数）と固定ステップから決まる純定数演算で、
    // ロボット位置に依存しない。値は実行時計算とビット単位同一（同じ定数・同じ浮動小数点演算順序）。
    // 先頭に必ず加える「現在地 {r.x, r.y}」は動的なのでキャッシュせず、評価ループ側で別途処理する。
    // [0]=赤グリッド, [1]=青グリッド。各要素は long[36] 相当の double[36][2]。
    private double[][] shootGridRed, shootGridBlue;

    private double[][] getShootSampleGrid(boolean isBlue) {
        double[][] cached = isBlue ? shootGridBlue : shootGridRed;
        if (cached != null) return cached;
        double xStart = isBlue ? 3.3 : 1.5;
        double xEnd   = isBlue ? 5.5 : 3.7;
        double yStart = 0.5;
        double yEnd   = 2.5;
        int xSteps = 6;
        int ySteps = 6;
        double[][] grid = new double[xSteps * ySteps][];
        int idx = 0;
        for (int ix = 0; ix < xSteps; ix++) {
            double sx = xStart + (double) ix / (xSteps - 1) * (xEnd - xStart);
            for (int iy = 0; iy < ySteps; iy++) {
                double sy = yStart + (double) iy / (ySteps - 1) * (yEnd - yStart);
                grid[idx++] = new double[]{sx, sy};
            }
        }
        if (isBlue) shootGridBlue = grid; else shootGridRed = grid;
        return grid;
    }

    /**
     * クエリ点 (qx,qy) の 3x3 近傍セル（cellRadius=1）に属する有効ボールのインデックスを収集する。
     * 半径 <= 1.0m の探索用。詳細は {@link #collectBallNeighbors(double, double, int)} を参照。
     */
    private int collectBallNeighbors(double qx, double qy) {
        return collectBallNeighbors(qx, qy, 1);
    }

    /**
     * クエリ点 (qx,qy) の (2*cellRadius+1)^2 近傍セルに属する有効ボールのインデックスを
     * scratch に収集して件数を返す。セルサイズ=1.0m のため探索半径 <= cellRadius*1.0m は
     * 必ずこの近傍に収まる（全走査と同じ候補集合の上位集合）。呼び出し側で正確な距離フィルタを
     * 行うことで全走査と完全一致する。返り値は ballNeighborScratch の有効長（昇順インデックス）。
     */
    private int collectBallNeighbors(double qx, double qy, int cellRadius) {
        if (ballNeighborScratch == null || ballNeighborScratch.length < gridBallCount) {
            ballNeighborScratch = new int[Math.max(16, gridBallCount)];
        }
        int cx = ballCellIndex(qx);
        int cy = ballCellIndex(qy);
        int gx0 = cx - cellRadius; if (gx0 < 0) gx0 = 0;
        int gx1 = cx + cellRadius; if (gx1 >= BALL_GRID_DIM) gx1 = BALL_GRID_DIM - 1;
        int gy0 = cy - cellRadius; if (gy0 < 0) gy0 = 0;
        int gy1 = cy + cellRadius; if (gy1 >= BALL_GRID_DIM) gy1 = BALL_GRID_DIM - 1;
        int count = 0;
        for (int gy = gy0; gy <= gy1; gy++) {
            int rowBase = gy * BALL_GRID_DIM;
            for (int gx = gx0; gx <= gx1; gx++) {
                for (int o = ballCellHead[rowBase + gx]; o != -1; o = ballCellNext[o]) {
                    ballNeighborScratch[count++] = o;
                }
            }
        }
        // ボールリスト出現順（昇順インデックス）にそろえる。重心など合算を伴う呼び出し側で
        // 浮動小数点の加算順序を全走査と一致させ、結果をビット単位で同一に保つため。
        if (count > 1) Arrays.sort(ballNeighborScratch, 0, count);
        return count;
    }

    public CompetitionManager(RobotConfig baseConfig, boolean isBlueAlliance) {
        this.baseConfig = baseConfig;
        this.isBlueAlliance = isBlueAlliance;
        this.robots = new ArrayList<>();

        // フィールドテープの動的取得
        loadFieldTapes();

        // ロボットの初期化 (6台)
        // ID 1: プレイヤー (YOU)
        robots.add(new CompetitionRobot(1, "YOU", isBlueAlliance ? CompetitionRobot.Alliance.BLUE : CompetitionRobot.Alliance.RED, true, CompetitionRobot.SpawnPosition.MID, CompetitionRobot.DriveType.DIFF));
        
        // ID 2, 3: 味方NPC 1, 2
        robots.add(new CompetitionRobot(2, "ALLIED 1", isBlueAlliance ? CompetitionRobot.Alliance.BLUE : CompetitionRobot.Alliance.RED, false, CompetitionRobot.SpawnPosition.UP, CompetitionRobot.DriveType.DIFF));
        robots.add(new CompetitionRobot(3, "ALLIED 2", isBlueAlliance ? CompetitionRobot.Alliance.BLUE : CompetitionRobot.Alliance.RED, false, CompetitionRobot.SpawnPosition.DOWN, CompetitionRobot.DriveType.DIFF));

        // ID 4, 5, 6: 敵NPC 1, 2, 3
        robots.add(new CompetitionRobot(4, "ENEMY 1", isBlueAlliance ? CompetitionRobot.Alliance.RED : CompetitionRobot.Alliance.BLUE, false, CompetitionRobot.SpawnPosition.UP, CompetitionRobot.DriveType.DIFF));
        robots.add(new CompetitionRobot(5, "ENEMY 2", isBlueAlliance ? CompetitionRobot.Alliance.RED : CompetitionRobot.Alliance.BLUE, false, CompetitionRobot.SpawnPosition.MID, CompetitionRobot.DriveType.DIFF));
        robots.add(new CompetitionRobot(6, "ENEMY 3", isBlueAlliance ? CompetitionRobot.Alliance.RED : CompetitionRobot.Alliance.BLUE, false, CompetitionRobot.SpawnPosition.DOWN, CompetitionRobot.DriveType.DIFF));

        // 初期配置の適用
        resetRobotPositions();
    }

    /**
     * field_obstacles.json からスタートゾーンのテープ情報をロードします。
     */
    private void loadFieldTapes() {
        try {
            List<FieldObstacle> loaded = io.FieldObstacleLoader.load(baseConfig.fieldObstaclesPath);
            for (FieldObstacle obs : loaded) {
                if ("RED_FIELD_TAPE".equals(obs.id()) && obs instanceof PolygonObstacle) {
                    double[][] v = ((PolygonObstacle) obs).getVertices();
                    calculateBounds(v, true);
                } else if ("BLUE_FIELD_TAPE".equals(obs.id()) && obs instanceof PolygonObstacle) {
                    double[][] v = ((PolygonObstacle) obs).getVertices();
                    calculateBounds(v, false);
                }
            }
        } catch (IOException e) {
            System.err.println("スタートテープ情報のロードに失敗しました。デフォルト値を使用します: " + e.getMessage());
        }
    }

    private void calculateBounds(double[][] vertices, boolean isRed) {
        double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double[] pt : vertices) {
            if (pt[0] < xMin) xMin = pt[0];
            if (pt[0] > xMax) xMax = pt[0];
            if (pt[1] < yMin) yMin = pt[1];
            if (pt[1] > yMax) yMax = pt[1];
        }
        if (isRed) {
            redXMin = xMin; redXMax = xMax; redYMin = yMin; redYMax = yMax;
        } else {
            blueXMin = xMin; blueXMax = xMax; blueYMin = yMin; blueYMax = yMax;
        }
    }

    /**
     * アライアンスのトグル切替を反映し、ロボットの所属と初期配置・向きをリセットします。
     */
    public void setBlueAlliance(boolean isBlue) {
        this.isBlueAlliance = isBlue;
        for (CompetitionRobot r : robots) {
            if (r.isPlayer()) {
                r.setAlliance(isBlue ? CompetitionRobot.Alliance.BLUE : CompetitionRobot.Alliance.RED);
            } else if (r.getId() == 2 || r.getId() == 3) {
                r.setAlliance(isBlue ? CompetitionRobot.Alliance.BLUE : CompetitionRobot.Alliance.RED);
            } else {
                r.setAlliance(isBlue ? CompetitionRobot.Alliance.RED : CompetitionRobot.Alliance.BLUE);
            }
        }
        resetRobotPositions();
    }

    public boolean isBlueAlliance() {
        return isBlueAlliance;
    }

    /**
     * ロボットの設定（SpawnPosition、DriveType、各種パラメータ）を保持したまま、
     * 位置・速度・スコア・AI状態のみをリセットします。対戦開始時のリセットに使用します。
     */
    public void softReset() {
        resetRobotPositions();
    }

    public List<CompetitionRobot> getRobots() {
        return robots;
    }

    /**
     * 全NPC（プレイヤー以外）の難易度プリセットを一括設定する。
     * 敵味方は同一エンジンの色違いのため、両陣営のNPCに同じ難易度を適用する。
     */
    public void setNpcDifficulty(CompetitionRobot.AiDifficulty difficulty) {
        for (CompetitionRobot r : robots) {
            if (!r.isPlayer()) {
                r.setAiDifficulty(difficulty);
            }
        }
    }

    /**
     * 陣営別に難易度を設定する（味方NPCと敵NPCを別難易度にしたい場合に使用）。
     */
    public void setNpcDifficulty(CompetitionRobot.Alliance alliance, CompetitionRobot.AiDifficulty difficulty) {
        for (CompetitionRobot r : robots) {
            if (!r.isPlayer() && r.getAlliance() == alliance) {
                r.setAiDifficulty(difficulty);
            }
        }
    }

    /**
     * チーム全体最適化によるボール回収割当（貪欲＋調整 ≒ ハンガリアン法の近似）。
     *
     * 各アライアンス（赤/青）ごとに、回収余力のある全NPCを対象として
     * 「どのロボットがどのボールを取るか」を到達時間コスト最小の貪欲マッチングで決める。
     * 1ボールに複数ロボットが群がるのを防ぎ（割当済みボールは除外）、
     * アライアンス3台全体での回収カバレッジ＝得点期待値合計を高める。
     * 結果は各ロボットの {@code assignedBallId} に格納され、COLLECT効用評価で
     * 割当ボールにボーナスを与えることで分散誘導する。プレイヤー機は割当対象外。
     */
    private void assignTeamBallTargets(List<core.BallState> balls) {
        for (CompetitionRobot r : robots) {
            r.assignedBallId = -1;
        }
        for (CompetitionRobot.Alliance alliance : CompetitionRobot.Alliance.values()) {
            // 回収余力のあるNPCを収集（満タンに近い機体は割当対象外＝撃ちに回る）
            List<CompetitionRobot> collectors = new ArrayList<>();
            for (CompetitionRobot r : robots) {
                if (r.isPlayer()) continue;
                if (r.getAlliance() != alliance) continue;
                if (r.heldBallCount >= r.getIntakeCapacity()) continue;
                // WHITE役（白ゴール投入担当）は回収割当の対象外（役割分担を尊重）
                if (r.getBehaviorType() == CompetitionRobot.BehaviorType.WHITE) continue;
                collectors.add(r);
            }
            if (collectors.isEmpty()) continue;

            // 割当候補ボール（未回収・未得点のもの）
            List<core.BallState> avail = new ArrayList<>();
            for (core.BallState b : balls) {
                if (b.collected || b.scored) continue;
                avail.add(b);
            }
            if (avail.isEmpty()) continue;

            // コスト行列（到達時間近似 = 距離 / 最高速度）を作り、
            // 全ペアの中から最小コストのペアを順に確定する貪欲マッチング。
            boolean[] robotAssigned = new boolean[collectors.size()];
            boolean[] ballAssigned = new boolean[avail.size()];
            int assignable = Math.min(collectors.size(), avail.size());
            for (int k = 0; k < assignable; k++) {
                double bestCost = Double.MAX_VALUE;
                int bestRi = -1, bestBi = -1;
                for (int ri = 0; ri < collectors.size(); ri++) {
                    if (robotAssigned[ri]) continue;
                    CompetitionRobot r = collectors.get(ri);
                    double speed = Math.max(0.3, r.getConfig().maxSpeed);
                    for (int bi = 0; bi < avail.size(); bi++) {
                        if (ballAssigned[bi]) continue;
                        core.BallState b = avail.get(bi);
                        double[] aim = estimateInterceptPoint(r.x, r.y, b, r.getAiDifficulty().predictionAccuracy);
                        double cost = dist(aim[0] - r.x, aim[1] - r.y) / speed;
                        if (cost < bestCost) {
                            bestCost = cost;
                            bestRi = ri;
                            bestBi = bi;
                        }
                    }
                }
                if (bestRi < 0) break;
                robotAssigned[bestRi] = true;
                ballAssigned[bestBi] = true;
                collectors.get(bestRi).assignedBallId = avail.get(bestBi).id;
            }
        }
    }

    /**
     * 全ロボットをスタート位置 (UP/MID/DOWN) とアライアンスに基づいて再配置します。
     */
    public void resetRobotPositions() {
        int npcIndex = 0; // NPCごとの意思決定位相分散用インデックス
        for (CompetitionRobot r : robots) {
            boolean isRed = r.getAlliance() == CompetitionRobot.Alliance.RED;
            double xMin = isRed ? redXMin : blueXMin;
            double xMax = isRed ? redXMax : blueXMax;
            double yMin = isRed ? redYMin : blueYMin;
            double yMax = isRed ? redYMax : blueYMax;

            double H = yMax - yMin;
            double h = H / 3.0;

            double targetX = (xMin + xMax) / 2.0;
            double targetY = yMin + h / 2.0; // DOWN

            if (r.getSpawnPosition() == CompetitionRobot.SpawnPosition.MID) {
                targetY = yMin + 1.5 * h;
            } else if (r.getSpawnPosition() == CompetitionRobot.SpawnPosition.UP) {
                targetY = yMax - h / 2.0;
            }

            r.x = targetX;
            r.y = targetY;
            // 差動なら車軸ピボットも設定
            r.heading = isRed ? 0.0 : Math.PI; // 赤は右向き(+x)、青は左向き(-x)（向かい合う向き）

            double cosH = Math.cos(r.heading);
            double sinH = Math.sin(r.heading);
            if (r.getDriveType() == CompetitionRobot.DriveType.OMNI) {
                r.xA = r.x;
                r.yA = r.y;
            } else {
                r.xA = r.x + r.getConfig().pivotOffsetD * cosH;
                r.yA = r.y + r.getConfig().pivotOffsetD * sinH;
            }

            r.v = 0.0;
            r.vy = 0.0;
            r.omega = 0.0;
            r.heldBallCount = 0;
            r.scoreCount = 0;
            r.shotAttemptCount = 0;
            r.whiteScoreCount = 0;
            r.stallCheckX = Double.NaN;
            r.stallCheckY = Double.NaN;
            r.stallCheckTimer = 0.0;
            r.stallRecoveryTimer = 0.0;
            r.stallRecoveryAngle = 0.0;
            r.lastChosenAction = "WANDER";
            r.interfereTargetId = -1;
            r.shootCommitActive = false;
            r.shootCommitStuckTimer = 0.0;
            r.shootCommitTurnTimer = 0.0;
            r.shootCommitTargetX = Double.NaN;
            r.shootCommitTargetY = Double.NaN;
            r.shootCommitTargetHeading = Double.NaN;
            r.shootCommitGoalPosX = Double.NaN;
            r.shootCommitGoalPosY = Double.NaN;
            r.shootCommitIdealTargetX = Double.NaN;
            r.shootCommitIdealTargetY = Double.NaN;
            r.shootCommitIdealTargetHeading = Double.NaN;
            r.prevAngleError = Double.NaN;
            r.collectTargetBallId = -1;

            // 意思決定間引きとキャッシュの初期化
            // 対戦開始直後に全NPCの意思決定が同一tickで一斉に満期化すると
            // 最初の1tickでフル意思決定が集中し数tickのカクつきを生む。
            // NPC通し番号で位相を1interval超に均等分散し、
            // 各NPCが異なるtickで初回満期を迎えるようにする
            // (1tick=dt=0.02s に対し十分な間隔を確保)。さらにNPC1台あたり
            // 0.02s以上ずらして同一tickの集中を避ける。
            if (r.isPlayer()) {
                // プレイヤーはAI意思決定対象外なので分散不要
                r.decisionTimer = 0.0;
            } else {
                // 初回 updateBehaviors では全NPCが必ず意思決定すること（テスト互換）。
                r.decisionTimer = 0.0;
                r.initialDecisionStagger = 0.0;
                npcIndex++;
            }
            r.cachedTargetX = Double.NaN;
            r.cachedTargetY = Double.NaN;
            r.cachedTargetHeading = Double.NaN;
            r.cachedChosenAction = "WANDER";
            r.cachedGoalPosX = Double.NaN;
            r.cachedGoalPosY = Double.NaN;
            r.cachedNewInterfereTargetId = -1;
            r.cachedBestCollectBallId = -1;
            r.cachedCanHitGoal = false;
            r.pathWaypointX = Double.NaN;
            r.pathWaypointY = Double.NaN;

            // 白線排出引っかかり回避の初期化
            r.ejectStuckTimer = 0.0;
            r.ejectRetryCount = 0;
            r.ejectBackupTimer = 0.0;
            r.stallCount = 0;
        }
    }

    private static class TempSegment {
        double ax, ay, bx, by;
        TempSegment(double ax, double ay, double bx, double by) {
            this.ax = ax; this.ay = ay; this.bx = bx; this.by = by;
        }
    }

    private boolean checkShootTargetHit(CompetitionRobot r, double rx, double ry, double heading, List<FieldObstacle> obstacles) {
        double xBL = 1.627, yBL = 0.0;
        double xBR = 5.373, yBR = 0.0;
        double xTL = 2.054, yTL = 0.747;
        double xTR = 4.946, yTR = 0.747;
        double L = xTR - xTL;
        double xWL = xTL + L / 3.0;
        double xWR = xTL + 2.0 * L / 3.0;

        List<TempSegment> targetSegments = new ArrayList<>();
        boolean isBlue = r.getAlliance() == CompetitionRobot.Alliance.BLUE;
        if (isBlue) {
            targetSegments.add(new TempSegment(xTR, yTR, xBR, yBR));
            targetSegments.add(new TempSegment(xWR, yTR, xTR, yTR));
        } else {
            targetSegments.add(new TempSegment(xBL, yBL, xTL, yTL));
            targetSegments.add(new TempSegment(xTL, yTL, xWL, yTL));
        }

        double cosT = Math.cos(heading);
        double sinT = Math.sin(heading);
        double halfSize = r.getConfig().robotSize / 2.0;
        double fx = rx + halfSize * cosT;
        double fy = ry + halfSize * sinT;
        double shootRange = r.getShootRange();

        // 1. 直線レイによる厳密な交差判定
        for (TempSegment seg : targetSegments) {
            double vx = seg.bx - seg.ax;
            double vy = seg.by - seg.ay;
            double det = vx * sinT - vy * cosT;
            if (Math.abs(det) > 1e-9) {
                double t = ((seg.ay - fy) * vx - (seg.ax - fx) * vy) / det;
                double u = (cosT * (seg.ay - fy) - sinT * (seg.ax - fx)) / det;
                if (t >= 0.0 && u >= 0.0 && u <= 1.0) {
                    if (t <= shootRange) {
                        double cx = fx + t * cosT;
                        double cy = fy + t * sinT;
                        if (!isRayBlocked(fx, fy, cx, cy, obstacles)) {
                            return true;
                        }
                    }
                }
            }
        }

        // 2. 扇形（heading +/- shootAngleThreshold）による交差判定（物理エンジンと整合）
        double shootAngleThreshold = r.getConfig().shootAngleThreshold;
        double shootRange2 = shootRange * shootRange; // 比較のみ（dist <= shootRange）の2乗距離化用
        for (TempSegment seg : targetSegments) {
            int samples = 15;
            for (int i = 0; i <= samples; i++) {
                double u = (double) i / samples;
                double px = seg.ax + u * (seg.bx - seg.ax);
                double py = seg.ay + u * (seg.by - seg.ay);
                // 比較のみ（dist <= shootRange）のため2乗距離化（厳密同値）
                double pfx = px - fx, pfy = py - fy;
                if (pfx * pfx + pfy * pfy <= shootRange2) {
                    double angle = Math.atan2(py - fy, px - fx);
                    double diff = normalizeAngle(angle - heading);
                    if (Math.abs(diff) <= shootAngleThreshold) {
                        if (!isRayBlocked(fx, fy, px, py, obstacles)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    /**
     * ユークリッド距離 sqrt(dx^2 + dy^2)。
     * Math.hypot はオーバーフロー安全のためのスケーリング処理を含み非常に低速。
     * 本シミュレータの座標はフィールド内（数m程度）に収まりオーバーフローは起き得ないため、
     * 直接 sqrt(dx*dx+dy*dy) で算出する（結果は hypot と最終桁レベルでのみ差異が生じうる）。
     * 第2弾最適化の hypot→sqrt 置換で多用する純粋関数（副作用なし）。
     */
    private static double dist(double dx, double dy) {
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ボール転がり摩擦の減衰率（BallSimulation.update と整合: vx *= (1 - damping*dt)）
    private static final double BALL_ROLLING_DAMPING = 1.5; // 1/s
    // NPCの代表移動速度（インターセプト到達時間の見積もり用 m/s）
    private static final double NPC_NOMINAL_SPEED = 1.2;
    // インターセプト先読みの最大時間（秒）
    private static final double INTERCEPT_MAX_LOOKAHEAD = 1.2;

    /**
     * 指定秒数 t 先のボール位置を予測する。
     * BallSimulation の積分（位置: x += vx*dt, 速度: vx *= (1 - damping*dt)）の
     * 連続時間近似として、指数減衰する速度の積分で変位を求める。
     * 壁・障害物の反射は無視する単純予測（数フレーム先の先回り用途には十分）。
     */
    private double[] predictBallPosition(core.BallState ball, double t) {
        double k = BALL_ROLLING_DAMPING;
        double disp; // 単位速度あたりの変位係数 ∫0^t e^{-k s} ds = (1 - e^{-k t}) / k
        if (k > 1e-9) {
            disp = (1.0 - Math.exp(-k * t)) / k;
        } else {
            disp = t;
        }
        double px = ball.x + ball.vx * disp;
        double py = ball.y + ball.vy * disp;
        // フィールド内にクランプ（壁反射は無視するが場外には出さない）
        px = Math.max(0.05, Math.min(6.95, px));
        py = Math.max(0.05, Math.min(6.95, py));
        return new double[]{px, py};
    }

    /**
     * 移動中のボールに対するインターセプト点を見積もる。
     * NPCの代表速度で近づける時間とボール予測位置を反復的に整合させ、
     * 先回り可能な目標点を返す。静止に近いボールは現在位置をそのまま返す。
     */
    private double[] estimateInterceptPoint(double rx, double ry, core.BallState ball) {
        return estimateInterceptPoint(rx, ry, ball, 1.0);
    }

    /**
     * 移動中ボールの先回りインターセプト点を推定する。
     * predictionAccuracy（難易度の予測精度, 0〜1）でリード量をブレンドする。
     * 1.0 で完全先読み（従来挙動）、小さいほど現在位置寄り（読みが甘い）。
     */
    private double[] estimateInterceptPoint(double rx, double ry, core.BallState ball, double predictionAccuracy) {
        // 比較のみ（speed < 0.15）のため2乗速度化（厳密同値）。speed はこの判定以外で未使用。
        double speed2 = ball.vx * ball.vx + ball.vy * ball.vy;
        if (speed2 < 0.0225) { // 0.15^2
            return new double[]{ball.x, ball.y};
        }
        // 反復: t = (NPCがボール予測位置に到達する時間) を数回更新して収束させる
        double t = 0.0;
        double[] pred = new double[]{ball.x, ball.y};
        for (int iter = 0; iter < 4; iter++) {
            double d = dist(pred[0] - rx, pred[1] - ry);
            double tNew = Math.min(INTERCEPT_MAX_LOOKAHEAD, d / Math.max(0.3, NPC_NOMINAL_SPEED));
            t = 0.5 * t + 0.5 * tNew; // 緩やかに更新して振動を防ぐ
            pred = predictBallPosition(ball, t);
        }
        if (predictionAccuracy >= 0.999) return pred;
        double a = Math.max(0.0, Math.min(1.0, predictionAccuracy));
        return new double[]{
            ball.x + (pred[0] - ball.x) * a,
            ball.y + (pred[1] - ball.y) * a
        };
    }

    /**
     * シュート成功率を動的に算出する（タスクB-3）。
     * 固定の getShootSuccessProb() を基準に、以下で補正する:
     *  - 距離: 射程に近いほど低下（distPen）
     *  - 角度: ロボットの向きと目標方向の差が大きいほど低下（anglePen）
     *  - 遮蔽: 射線上に障害物・敵がいると isRayBlocked で大幅低下
     *  - 混雑: ゴール前に他ロボットが密集するほど低下
     * goalPos が未確定（NaN）の場合は基準値をそのまま返す。
     */
    private double computeDynamicShootProb(CompetitionRobot r, double goalPosX, double goalPosY,
                                           List<FieldObstacle> obstacles) {
        double base = r.getShootSuccessProb();
        if (Double.isNaN(goalPosX) || Double.isNaN(goalPosY)) {
            return base;
        }
        double halfSize = r.getConfig().robotSize / 2.0;
        double fx = r.x + halfSize * Math.cos(r.heading);
        double fy = r.y + halfSize * Math.sin(r.heading);
        double dx = goalPosX - fx;
        double dy = goalPosY - fy;
        double distance = dist(dx, dy);
        double shootRange = Math.max(0.1, r.getShootRange());

        // 距離ペナルティ（射程端ほど低下、近距離は満点）
        double distRatio = Math.min(1.0, distance / shootRange);
        double distPen = 1.0 - 0.5 * distRatio * distRatio;

        // 角度ペナルティ（ロボットの向きと目標方向の差）
        double aimAngle = Math.atan2(dy, dx);
        double angleErr = Math.abs(normalizeAngle(aimAngle - r.heading));
        double cosErr = Math.cos(angleErr);
        double anglePen = Math.max(0.0, cosErr); // 後方は0、正面は1

        // 遮蔽ペナルティ（射線が障害物で塞がれていれば大幅低下）
        double occlusionPen = 1.0;
        if (isRayBlocked(fx, fy, goalPosX, goalPosY, obstacles)) {
            occlusionPen = 0.25;
        }

        // 混雑ペナルティ（ゴール前に他ロボットが密集するほど低下）
        double crowdPen = 1.0;
        for (CompetitionRobot other : robots) {
            if (other == r) continue;
            // 射線近傍に他ロボットがいると弾道が乱される想定（比較のみ < 1.2 → 2乗距離化、厳密同値）
            double ogx = other.x - goalPosX, ogy = other.y - goalPosY;
            if (ogx * ogx + ogy * ogy < 1.44) {
                crowdPen *= 0.85;
            }
        }

        double prob = base * distPen * anglePen * occlusionPen * crowdPen;
        // 難易度プリセットによるシュート精度補正（最強=1.0で従来同等）
        prob *= r.getAiDifficulty().shootAccuracyMult;
        double maxProb = base * r.getAiDifficulty().shootAccuracyMult;
        // 基準値の30%〜上限の範囲にクランプ（過度に下げて完全に撃てなくしない）
        return Math.max(base * 0.3 * r.getAiDifficulty().shootAccuracyMult, Math.min(maxProb, prob));
    }

    @Override
    public void updateBehaviors(SimulationEngine engine, double dt) {
        class GoalAimCandidate {
            final double x, y;
            final double nx, ny;
            final boolean isSlope;
            GoalAimCandidate(double x, double y, double nx, double ny, boolean isSlope) {
                this.x = x; this.y = y;
                this.nx = nx; this.ny = ny;
                this.isSlope = isSlope;
            }
        }

        core.IBallSimulation ballSim = engine.getBallSimulation();
        List<core.BallState> balls = ballSim.getBallStates();

        // [AI] デバッグ println は削除（毎30tickの文字列整形コストを排除）。挙動には無関係。

        // 妨害係数計算用: 全ロボット中の最大効率で正規化
        double maxEff = 0.0;
        for (CompetitionRobot rr : robots) {
            double eff = rr.getShootSuccessProb() * rr.getShootBPS();
            if (eff > maxEff) maxEff = eff;
        }

        // チーム全体最適化: 各アライアンスでボール回収割当を計算（群がり防止・到達時間最小化）
        assignTeamBallTargets(balls);

        for (CompetitionRobot r : robots) {
            if (r.isPlayer()) continue;

            // このロボットが見る時点のボール状態で空間グリッドと density を構築する。
            // ボール状態は各ロボット末尾の intake/shoot/eject でのみ変化するため、
            // ロボット単位で再構築すれば密度カウント・近傍探索を全走査と完全一致させられる。
            buildBallGrid(balls);

            if (r.shootTimer > 0.0) {
                r.shootTimer = Math.max(0.0, r.shootTimer - dt);
            }

            // 白線排出後退タイマーの減算
            if (r.ejectBackupTimer > 0.0) {
                r.ejectBackupTimer = Math.max(0.0, r.ejectBackupTimer - dt);
            }

            // 意思決定タイマーの減算
            r.decisionTimer -= dt;

            // 状態急変（例：ボール保持数が0になった）時、または白排出のスタック脱出開始時などは即座に再評価
            boolean immediateReevaluate = false;
            if (r.heldBallCount == 0 && !"WANDER".equals(r.lastChosenAction) && r.decisionTimer > 0.0) {
                immediateReevaluate = true;
            }
            if (r.ejectBackupTimer > 0.0 && r.decisionTimer > 0.0) {
                immediateReevaluate = true;
            }
            // 前回意思決定時からの状態変化を検知して即時再評価する。
            // これにより、間引きタイマー(decisionInterval)が残っていても外部からの状態変更
            // （保持数・行動タイプ・大きな移動・回収ターゲット変更・コミット状態・スタール検知）に即応する。
            if (r.lastEvalEvaluated && r.decisionTimer > 0.0) {
                boolean stateChanged = false;
                if (r.heldBallCount != r.lastEvalHeldBallCount) stateChanged = true;
                if (r.getBehaviorType() != r.lastEvalBehaviorType) stateChanged = true;
                if (r.collectTargetBallId != r.lastEvalCollectTargetBallId) stateChanged = true;
                if (r.shootCommitActive != r.lastEvalShootCommitActive) stateChanged = true;
                // 射程変化はコミット中の射点（移動目標）を動かすため即時再評価する
                if (r.shootCommitActive && !Double.isNaN(r.lastEvalShootRange)
                        && Math.abs(r.getShootRange() - r.lastEvalShootRange) > 1e-6) {
                    stateChanged = true;
                }
                if (!Double.isNaN(r.lastEvalX) && !Double.isNaN(r.lastEvalY)) {
                    // 比較のみ（> 0.03）のため2乗距離化（厳密同値）
                    double mvx = r.x - r.lastEvalX, mvy = r.y - r.lastEvalY;
                    if (mvx * mvx + mvy * mvy > 0.0009) stateChanged = true;
                }
                // スタール検知が即時発動条件を満たしているときも再評価（後退モード移行を即反映）
                if (r.stallCheckTimer >= STALL_CHECK_INTERVAL || r.stallRecoveryTimer > 0.0) {
                    stateChanged = true;
                }
                if (stateChanged) immediateReevaluate = true;
            }
            if (immediateReevaluate) {
                r.decisionTimer = 0.0;
            }

            boolean needsDecision = (r.decisionTimer <= 0.0);

            // 保持数が0になった、またはCOLLECTが選ばれたときは白線リトライ回数をリセット
            if (r.heldBallCount == 0 || "COLLECT".equals(r.lastChosenAction)) {
                r.ejectRetryCount = 0;
            }

            // 妨害係数 = 妨害重み × (1 − 効率)  ※効率低い機体ほど妨害に出る
            double myEff = (maxEff > 1e-9) ? (r.getShootSuccessProb() * r.getShootBPS()) / maxEff : 0.0;
            double interfereCoeff = r.interfereWeight * (1.0 - myEff);

            // 前フレームの行動（ヒステリシス・住み分け用）
            String prevAction = r.lastChosenAction;

            double maxUtility = -Double.MAX_VALUE;
            String chosenAction = "WANDER";
            double targetX = r.x;
            double targetY = r.y;
            double targetHeading = r.heading;
            int newInterfereTargetId = -1;
            int bestCollectBallId = -1;

            // needsDecision ブロック内外で共有する変数
            double goalPosX = Double.NaN;
            double goalPosY = Double.NaN;
            boolean canHitGoal = false;
            List<FieldObstacle> obstacles = engine.getFieldObstacles();
            boolean frontContactWhite = false;
            double distToWhite = Double.MAX_VALUE;


            if (needsDecision) {

            double heldRatio = (double) r.heldBallCount / r.getIntakeCapacity();
            double capacityMargin = 1.0 - heldRatio;

            // 残り時間とBPSに基づく緊急度係数（試合終了前にボールを撃ち切れるよう調整）
            double remainingTime = Math.max(0.0, 150.0 - engine.getSimTime());
            boolean timerRunning = engine.isTimerStarted() && !engine.isTimeLimitReached();
            double effectiveBPS = r.getShootBPS() * r.getShootSuccessProb();
            // 残り時間で撃てる期待数（タイマー未起動なら十分余裕があるとみなす）
            double shotCapacity = timerRunning ? remainingTime * effectiveBPS : Double.MAX_VALUE;
            // SHOOT urgency boost：保持数が残り撃てる量を超えるほど強くSHOOTを押す
            double shootUrgencyBoost = (timerRunning && r.heldBallCount > 0)
                ? Math.max(0.0, r.heldBallCount - shotCapacity) * 0.5
                : 0.0;
            // COLLECT urgency factor：残り撃てる量以上に持っているならCOLLECTを抑制
            double collectUrgencyFactor = (timerRunning && r.heldBallCount > 0 && shotCapacity < r.heldBallCount)
                ? Math.max(0.1, shotCapacity / r.heldBallCount)
                : 1.0;

            // 回転効率ベースの射出準備係数
            // held < minShootHold: 回収優先（線形ランプ）　held >= minShootHold: 満タン待ちなし（1.0固定）
            double minShootHoldRatio = MIN_SHOOT_HOLD_RATIO;
            if (timerRunning && remainingTime < 30.0) {
                // 終盤は徐々に下げ、残り0秒では1個まで下げる
                double lateGameFraction = 1.0 - (remainingTime / 30.0);
                double minRatio = 1.0 / Math.max(1, r.getIntakeCapacity());
                minShootHoldRatio = MIN_SHOOT_HOLD_RATIO * (1.0 - lateGameFraction) + minRatio * lateGameFraction;
            }
            double minShootHold = Math.max(1.0, minShootHoldRatio * r.getIntakeCapacity());
            double heldReadiness = (r.heldBallCount < minShootHold)
                ? (r.heldBallCount / minShootHold)
                : 1.0;

            double maxCollectU = 0.0;
            double maxShootU = 0.0;
            double maxWhiteU = 0.0;

            // 白ゴール座標（住み分け分析・効用計算で共用）
            final double whiteLeftX = 0.55, whiteLeftY = 5.06;
            final double whiteRightX = 6.45, whiteRightY = 5.06;

            // --- 味方の行動分析（ゴール住み分け） ---
            int alliesChoosingSHOOT = 0;
            int alliesAtWhiteLeft = 0, alliesAtWhiteRight = 0;
            for (CompetitionRobot ally : robots) {
                if (ally == r || ally.getAlliance() != r.getAlliance()) continue;
                if ("SHOOT".equals(ally.lastChosenAction)) alliesChoosingSHOOT++;
                if ("WHITE".equals(ally.lastChosenAction)) {
                    // 比較のみ（dAllyLeft <= dAllyRight）のため2乗距離化（厳密同値）
                    double dlx = ally.x - whiteLeftX, dly = ally.y - whiteLeftY;
                    double drx = ally.x - whiteRightX, dry = ally.y - whiteRightY;
                    if (dlx * dlx + dly * dly <= drx * drx + dry * dry) alliesAtWhiteLeft++;
                    else alliesAtWhiteRight++;
                }
            }
            double allyShootPenalty  = Math.pow(ALLY_SHOOT_OVERLAP_PENALTY, alliesChoosingSHOOT);
            double allyWhiteLeftPenalty  = Math.pow(ALLY_GOAL_OVERLAP_PENALTY, alliesAtWhiteLeft);
            double allyWhiteRightPenalty = Math.pow(ALLY_GOAL_OVERLAP_PENALTY, alliesAtWhiteRight);

            // 自陣ゴール座標および動的シュート目標（コミット・効用評価・アクション実行で共用）
            boolean isBlueGoal = r.getAlliance() == CompetitionRobot.Alliance.BLUE;
            double goalX = isBlueGoal ? 4.464 : 2.536;
            double goalY = 0.747;

            // シュートコミット解除チェック
            if (r.shootCommitActive) {
                if (r.heldBallCount <= 0) {
                    r.shootCommitActive = false;
                    r.shootCommitStuckTimer = 0.0;
                } else if (r.shootActive) {
                    r.shootCommitStuckTimer = 0.0;
                } else {
                    r.shootCommitStuckTimer += dt;
                    if (r.shootCommitStuckTimer >= SHOOT_COMMIT_TIMEOUT) {
                        r.shootCommitActive = false;
                        r.shootCommitStuckTimer = 0.0;
                    }
                }
            }

            goalPosX = Double.NaN;
            goalPosY = Double.NaN;
            double dynTargetX = Double.NaN;
            double dynTargetY = Double.NaN;
            double dynTargetHeading = Double.NaN;
            canHitGoal = false;

            // 幾何評価に基づく最適な射撃目標の事前算出（全状態評価・コミット有無問わず利用）
            class SegmentDef {
                double ax, ay, bx, by;
                double normalAngle;
                SegmentDef(double ax, double ay, double bx, double by, double normalAngle) {
                    this.ax = ax; this.ay = ay; this.bx = bx; this.by = by;
                    this.normalAngle = normalAngle;
                }
            }

            List<SegmentDef> segs = new ArrayList<>();
            boolean isBlue = r.getAlliance() == CompetitionRobot.Alliance.BLUE;
            if (isBlue) {
                segs.add(new SegmentDef(4.946, 0.747, 5.373, 0.0, Math.atan2(-0.427, -0.747)));
                segs.add(new SegmentDef(3.982, 0.747, 4.946, 0.747, -Math.PI / 2.0));
            } else {
                segs.add(new SegmentDef(1.627, 0.0, 2.054, 0.747, Math.atan2(0.427, -0.747)));
                segs.add(new SegmentDef(2.054, 0.747, 3.018, 0.747, -Math.PI / 2.0));
            }

            // サンプル点：先頭に現在地 {r.x, r.y}、続いて静的グリッド（アライアンス別キャッシュ）。
            // 反復順序は従来（現在地→グリッド昇順）と完全一致。グリッド点は new せず再利用する。
            double[][] sampleGrid = getShootSampleGrid(isBlue);
            int sampleCount = sampleGrid.length + 1; // +1 = 現在地

            double bestScore = -Double.MAX_VALUE;
            double bestTargetX = r.x;
            double bestTargetY = r.y;
            double bestTargetHeading = r.heading;
            double bestGoalPosX = Double.NaN;
            double bestGoalPosY = Double.NaN;
            double bestProb = 0.0;
            boolean anyValidProb = false;

            double shootRange = r.getShootRange();

            for (int spi = 0; spi < sampleCount; spi++) {
                // spi==0 は現在地、それ以降は静的グリッド（従来の反復順序と完全一致）
                double px = (spi == 0) ? r.x : sampleGrid[spi - 1][0];
                double py = (spi == 0) ? r.y : sampleGrid[spi - 1][1];

                double bestProbAtP = 0.0;
                double bestHeadingAtP = r.heading;
                double bestGoalXAtP = Double.NaN;
                double bestGoalYAtP = Double.NaN;

                // コミット中の場合はゴール点をコミット開始時の位置に固定する
                if (r.shootCommitActive && !Double.isNaN(r.shootCommitGoalPosX)) {
                    double sx = r.shootCommitGoalPosX;
                    double sy = r.shootCommitGoalPosY;
                    double dx = sx - px;
                    double dy = sy - py;
                    double distance = dist(dx, dy);

                    if (distance <= shootRange && distance >= 0.1 && !isRayBlocked(px, py, sx, sy, obstacles)) {
                        double distRatio = distance / shootRange;
                        double distPen = 1.0 - 0.7 * distRatio * distRatio;
                        distPen = Math.max(0.3, Math.min(1.0, distPen));

                        double normalAngle = -Math.PI / 2.0;
                        if (isBlue) {
                            if (sx > 4.946) normalAngle = Math.atan2(-0.427, -0.747);
                        } else {
                            if (sx < 2.054) normalAngle = Math.atan2(0.427, -0.747);
                        }

                        double shootAngle = Math.atan2(dy, dx);
                        double cosAngle = Math.cos(shootAngle - normalAngle);
                        double anglePen = cosAngle * cosAngle;

                        double contactPen = 1.0;
                        for (CompetitionRobot other : robots) {
                            if (other == r) continue;
                            // 比較のみ（< 0.6）のため2乗距離化（厳密同値）
                            double odx = other.x - px, ody = other.y - py;
                            if (odx * odx + ody * ody < 0.36) {
                                contactPen *= 0.7;
                            }
                        }

                        double prob = r.getShootSuccessProb() * distPen * anglePen * contactPen;
                        if (prob > bestProbAtP) {
                            bestProbAtP = prob;
                            bestHeadingAtP = shootAngle;
                            bestGoalXAtP = sx;
                            bestGoalYAtP = sy;
                        }
                    }
                } else {
                    // 通常時の総当たり評価
                    for (SegmentDef seg : segs) {
                        int segSamples = 5;
                        for (int is = 0; is < segSamples; is++) {
                            double u = (double) is / (segSamples - 1);
                            double sx = seg.ax + u * (seg.bx - seg.ax);
                            double sy = seg.ay + u * (seg.by - seg.ay);

                            double dx = sx - px;
                            double dy = sy - py;
                            double distance = dist(dx, dy);

                            if (distance > shootRange || distance < 0.1) continue;

                            if (isRayBlocked(px, py, sx, sy, obstacles)) {
                                continue;
                            }

                            double distRatio = distance / shootRange;
                            double distPen = 1.0 - 0.7 * distRatio * distRatio;
                            distPen = Math.max(0.3, Math.min(1.0, distPen));

                            double shootAngle = Math.atan2(dy, dx);
                            double cosAngle = Math.cos(shootAngle - seg.normalAngle);
                            double anglePen = cosAngle * cosAngle;

                            double contactPen = 1.0;
                            for (CompetitionRobot other : robots) {
                                if (other == r) continue;
                                // 比較のみ（< 0.6）のため2乗距離化（厳密同値）
                                double odx = other.x - px, ody = other.y - py;
                                if (odx * odx + ody * ody < 0.36) {
                                    contactPen *= 0.7;
                                }
                            }

                            double prob = r.getShootSuccessProb() * distPen * anglePen * contactPen;
                            if (prob > bestProbAtP) {
                                bestProbAtP = prob;
                                bestHeadingAtP = shootAngle;
                                bestGoalXAtP = sx;
                                bestGoalYAtP = sy;
                            }
                        }
                    }
                }

                // スコア計算
                double distToP = dist(px - r.x, py - r.y);

                // 改善5: 移動時間コストを時間あたり価値で評価（近い中成功率 vs 遠い高成功率を公平比較）
                double moveTimeSec = distToP / Math.max(0.1, r.getConfig().maxSpeed);
                double timeDiscount = MOVE_TIME_REFERENCE_DURATION / (moveTimeSec + MOVE_TIME_REFERENCE_DURATION);

                // 改善1: 撃ち終わった後の回収アクセスボーナス（撃つ→拾うの動線先読み）
                double collectAccessBonus = 0.0;
                if (r.heldBallCount > 0) {
                    // 半径2.0m -> セル1.0mで cellRadius=2 の 5x5 近傍粗選別＋正確距離。
                    // 昇順インデックス走査で候補集合・加算順序とも全走査と一致（ビット単位同一）。
                    int nbL = collectBallNeighbors(px, py, 2);
                    for (int ni = 0; ni < nbL; ni++) {
                        core.BallState ball = balls.get(ballNeighborScratch[ni]);
                        if (ball.collected || ball.scored) continue;
                        double ballDist = dist(ball.x - px, ball.y - py);
                        if (ballDist < LOOKAHEAD_BALL_RADIUS) {
                            collectAccessBonus += LOOKAHEAD_WEIGHT / (1.0 + ballDist * 2.0);
                        }
                    }
                }

                // 改善4: 射撃位置の混雑ペナルティ（半径拡大・係数強化・味方の射撃目標位置も考慮）
                double congestionPen = 0.0;
                for (CompetitionRobot other : robots) {
                    if (other == r || other.getAlliance() != r.getAlliance()) continue;
                    double distToOther = dist(other.x - px, other.y - py);
                    if (distToOther < 2.0) { // 1.2m → 2.0m に拡大
                        congestionPen += 0.30 * (2.0 - distToOther) / 2.0; // 係数 0.2 → 0.30
                    }
                    // 既に同付近を射撃目標としている味方がいれば追加ペナルティ
                    if ("SHOOT".equals(other.lastChosenAction) && !Double.isNaN(other.cachedTargetX)) {
                        // 比較のみ（< 1.5）のため2乗距離化（厳密同値）
                        double atdx = other.cachedTargetX - px, atdy = other.cachedTargetY - py;
                        if (atdx * atdx + atdy * atdy < 2.25) {
                            congestionPen += 0.25;
                        }
                    }
                }

                double score = bestProbAtP * timeDiscount + collectAccessBonus - congestionPen;
                if (score > bestScore && bestProbAtP > 0.01) {
                    bestScore = score;
                    bestTargetX = px;
                    bestTargetY = py;
                    bestTargetHeading = bestHeadingAtP;
                    bestGoalPosX = bestGoalXAtP;
                    bestGoalPosY = bestGoalYAtP;
                    bestProb = bestProbAtP;
                    anyValidProb = true;
                }
            }

            if (!anyValidProb) {
                bestGoalPosX = isBlue ? 4.464 : 2.536;
                bestGoalPosY = 0.747;
                bestTargetX = bestGoalPosX;
                bestTargetY = 1.5;
                bestTargetHeading = Math.atan2(bestGoalPosY - r.y, bestGoalPosX - r.x);
                bestProb = 0.0;
            }

            if (r.shootCommitActive && r.heldBallCount > 0) {
                // コミット中：探索をスキップして、計算された最良の目標位置へ向かう
                chosenAction = "SHOOT";

                // コミット中のゴール狙い点を確定（探索で有効点が得られればそれを、なければロック点を使う）
                double commitGoalX = !Double.isNaN(bestGoalPosX) ? bestGoalPosX : r.shootCommitGoalPosX;
                double commitGoalY = !Double.isNaN(bestGoalPosY) ? bestGoalPosY : r.shootCommitGoalPosY;
                goalPosX = commitGoalX;
                goalPosY = commitGoalY;

                // 射点（移動目標）は、ゴール狙い点から射程に応じた距離だけ離れた位置とする。
                // 射点がグリッド角に張り付いて射程変化で動かない問題を回避するため、
                // ゴール→ロボット現在地 方向に sqrt(0.9)*射程 だけ離れた線上の点を射点とする。
                // 射程を伸ばすと射点はゴールから遠ざかる（より遠くから狙える）。
                double goalToRobotDist = dist(r.x - commitGoalX, r.y - commitGoalY);
                if (!Double.isNaN(commitGoalX) && !Double.isNaN(commitGoalY) && goalToRobotDist > 1e-6) {
                    double dirX = (r.x - commitGoalX) / goalToRobotDist;
                    double dirY = (r.y - commitGoalY) / goalToRobotDist;
                    // 射程の 90% を狙い距離とし、ロボットが既に射程内ならそれ以上は近づかない
                    double shootDist = Math.min(r.getShootRange() * 0.9, goalToRobotDist);
                    dynTargetX = commitGoalX + dirX * shootDist;
                    dynTargetY = commitGoalY + dirY * shootDist;
                    dynTargetHeading = Math.atan2(commitGoalY - dynTargetY, commitGoalX - dynTargetX);
                } else {
                    dynTargetX = bestTargetX;
                    dynTargetY = bestTargetY;
                    dynTargetHeading = bestTargetHeading;
                }

                targetX = dynTargetX;
                targetY = dynTargetY;
                targetHeading = dynTargetHeading;
                r.shootCommitTargetX = targetX;
                r.shootCommitTargetY = targetY;
                r.shootCommitTargetHeading = targetHeading;
                newInterfereTargetId = -1;
                canHitGoal = checkShootTargetHit(r, r.x, r.y, r.heading, obstacles);

                // デッドロック検知と狙い点自動修正（正対しているのに射撃不可の場合）
                // コミット中のゴール点が無効（白の中央部など、実セグメント外）でも正対してしまうと
                // 永遠に射撃できないため、ロックを無視して有効な赤/青セグメント上の点へ強制再照準する。
                double toGoalAngle = Math.atan2(goalPosY - r.y, goalPosX - r.x);
                double toGoalDiff = Math.abs(normalizeAngle(toGoalAngle - r.heading));
                if (toGoalDiff < DIFF_ANGLE_DEADBAND && !canHitGoal) {
                    // 現在のロック狙い点が有効な赤/青セグメント上にあるかを判定する。
                    boolean lockedOnValidSegment = false;
                    if (!Double.isNaN(goalPosX) && !Double.isNaN(goalPosY)) {
                        for (SegmentDef seg : segs) {
                            double sdx = seg.bx - seg.ax;
                            double sdy = seg.by - seg.ay;
                            double segLen2 = sdx * sdx + sdy * sdy;
                            if (segLen2 < 1e-12) continue;
                            double tproj = ((goalPosX - seg.ax) * sdx + (goalPosY - seg.ay) * sdy) / segLen2;
                            if (tproj < -0.01 || tproj > 1.01) continue;
                            double closestX = seg.ax + tproj * sdx;
                            double closestY = seg.ay + tproj * sdy;
                            // 比較のみ（< 0.05）のため2乗距離化（厳密同値）
                            double gcx = goalPosX - closestX, gcy = goalPosY - closestY;
                            if (gcx * gcx + gcy * gcy < 0.0025) {
                                lockedOnValidSegment = true;
                                break;
                            }
                        }
                    }
                    // 無効な狙い点（白中央など）に正対してデッドロックしている場合は、
                    // 自陣ゴール中央（赤=2.536 / 青=4.464、共にセグメント上）へ強制的に再照準する。
                    if (!lockedOnValidSegment) {
                        r.shootCommitGoalPosX = goalX;
                        r.shootCommitGoalPosY = goalY;
                        r.shootCommitTargetHeading = Math.atan2(goalY - r.y, goalX - r.x);
                        goalPosX = goalX;
                        goalPosY = goalY;
                        targetHeading = r.shootCommitTargetHeading;
                        canHitGoal = checkShootTargetHit(r, r.x, r.y, r.heading, obstacles);
                    } else if (!Double.isNaN(bestGoalPosX) && bestProb > 0.01) {
                        // 有効点に正対しているが遮蔽等で撃てない場合は、探索最良点へ再照準。
                        // 射点はゴール狙い点から射程に応じた距離だけ離れた線上の点とする（射程変化に追従）。
                        r.shootCommitGoalPosX = bestGoalPosX;
                        r.shootCommitGoalPosY = bestGoalPosY;
                        double g2rDist = dist(r.x - bestGoalPosX, r.y - bestGoalPosY);
                        if (g2rDist > 1e-6) {
                            double dirX = (r.x - bestGoalPosX) / g2rDist;
                            double dirY = (r.y - bestGoalPosY) / g2rDist;
                            double shootDist = Math.min(r.getShootRange() * 0.9, g2rDist);
                            r.shootCommitTargetX = bestGoalPosX + dirX * shootDist;
                            r.shootCommitTargetY = bestGoalPosY + dirY * shootDist;
                            r.shootCommitTargetHeading = Math.atan2(bestGoalPosY - r.shootCommitTargetY, bestGoalPosX - r.shootCommitTargetX);
                        } else {
                            r.shootCommitTargetX = bestTargetX;
                            r.shootCommitTargetY = bestTargetY;
                            r.shootCommitTargetHeading = bestTargetHeading;
                        }
                        goalPosX = bestGoalPosX;
                        goalPosY = bestGoalPosY;
                        targetX = r.shootCommitTargetX;
                        targetY = r.shootCommitTargetY;
                        targetHeading = r.shootCommitTargetHeading;
                        canHitGoal = checkShootTargetHit(r, r.x, r.y, r.heading, obstacles);
                    }
                }
            } else {
                // 通常時：最良の射撃位置を目標にする
                goalPosX = bestGoalPosX;
                goalPosY = bestGoalPosY;
                dynTargetX = bestTargetX;
                dynTargetY = bestTargetY;
                dynTargetHeading = bestTargetHeading;
                canHitGoal = checkShootTargetHit(r, r.x, r.y, r.heading, obstacles);

                // --- 1. ボール回収の効用 ---
                if (!r.shootCommitActive || r.heldBallCount <= 0) {
                int ballCount1 = balls.size();
                for (int ballIdx1 = 0; ballIdx1 < ballCount1; ballIdx1++) {
                    core.BallState ball = balls.get(ballIdx1);
                    if (ball.collected || ball.scored) continue;

                    // 改善（タスクB-1）: ボール軌跡予測によるインターセプト。
                    // 移動中のボールは現在位置でなく先回り可能な予測点を狙う（移動目標 aim 用）。
                    double[] intercept = estimateInterceptPoint(r.x, r.y, ball, r.getAiDifficulty().predictionAccuracy);
                    double aimX = intercept[0];
                    double aimY = intercept[1];
                    // 効用の近接評価には「現在位置までの距離」を用いる。
                    // 予測点での距離を使うと、その場でほぼ拾えるボールでも初速が残っていると
                    // 遠方扱いされて選ばれなくなる。近接性は現在位置で公平に比較する。
                    double dist = dist(ball.x - r.x, ball.y - r.y);

                    // density: 半径1.0m以内の有効ボール数。空間グリッドで事前計算済みの値を参照
                    // （O(N^2) の全走査を排除。結果は全走査と完全一致）。
                    int density = ballDensity[ballIdx1];

                    // 味方が近い・向かっているボールは効用減（エリア住み分け）
                    double allyBallPenalty = 1.0;
                    for (CompetitionRobot ally : robots) {
                        if (ally == r || ally.getAlliance() != r.getAlliance()) continue;
                        double allyToBall = dist(ball.x - ally.x, ball.y - ally.y);
                        if (allyToBall < ALLY_BALL_RADIUS) {
                            double overlap = (ALLY_BALL_RADIUS - allyToBall) / ALLY_BALL_RADIUS;
                            allyBallPenalty *= (1.0 - overlap * 0.70); // 近接ペナルティ強化 0.5→0.70
                        }
                        if ("COLLECT".equals(ally.lastChosenAction) || "STEAL".equals(ally.lastChosenAction)) {
                            double allyToBallAngle = Math.atan2(ball.y - ally.y, ball.x - ally.x);
                            double allyHDiff = Math.abs(normalizeAngle(ally.heading - allyToBallAngle));
                            if (allyHDiff < Math.toRadians(30.0) && allyToBall < dist) {
                                allyBallPenalty *= ALLY_GOAL_OVERLAP_PENALTY;
                            }
                        }
                    }
                    // 改善2: ゾーン分担ペナルティ（フィールドを2x2分割して同ゾーンに味方がCOLLECT中なら効用を下げる）
                    int ballZoneX = (int)(Math.min(ball.x, 6.99) / 3.5);
                    int ballZoneY = (int)(Math.min(ball.y, 6.99) / 3.5);
                    for (CompetitionRobot zoneAlly : robots) {
                        if (zoneAlly == r || zoneAlly.getAlliance() != r.getAlliance()) continue;
                        if (!"COLLECT".equals(zoneAlly.lastChosenAction)) continue;
                        int allyZoneX = (int)(Math.min(zoneAlly.x, 6.99) / 3.5);
                        int allyZoneY = (int)(Math.min(zoneAlly.y, 6.99) / 3.5);
                        if (allyZoneX == ballZoneX && allyZoneY == ballZoneY) {
                            allyBallPenalty *= ZONE_COLLECT_PENALTY;
                        }
                    }

                    // 他ロボット（味方・敵）が同じボールに向かうほど効用減（標的重複回避）
                    double targetOverlapPenalty = 1.0;
                    // 2乗距離で比較（otherToBall < dist*0.8 && < 2.0 はいずれも非負同士の比較のため
                    // 両辺2乗しても厳密同値。sqrt を1回省ける）
                    double dist08sq = (dist * 0.8) * (dist * 0.8);
                    for (CompetitionRobot other : robots) {
                        if (other == r) continue;
                        double obx = ball.x - other.x, oby = ball.y - other.y;
                        double otherToBall2 = obx * obx + oby * oby;
                        if (("COLLECT".equals(other.lastChosenAction) || "STEAL".equals(other.lastChosenAction))
                                && otherToBall2 < dist08sq && otherToBall2 < 4.0) {
                            targetOverlapPenalty *= 0.7;
                        }
                    }

                    // 自陣ゴール近接ボーナス（自分のアライアンスの赤/青ゴールに近いボールを優先）
                    double ownGoalX = (r.getAlliance() == CompetitionRobot.Alliance.BLUE) ? 4.464 : 2.536;
                    double ballToGoalDist = dist(ball.x - ownGoalX, ball.y - 0.747);
                    double goalProximityBonus = 1.0 + GOAL_BONUS_STRENGTH * Math.exp(-ballToGoalDist / GOAL_BONUS_DECAY);

                    // 壁際ペナルティ（壁に近いほど効用を相当強く下げる）
                    double wallDist = Math.min(Math.min(ball.x, 7.0 - ball.x), Math.min(ball.y, 7.0 - ball.y));
                    double wallPenalty;
                    if (wallDist <= WALL_PENALTY_NEAR) {
                        wallPenalty = WALL_PENALTY_MIN;
                    } else if (wallDist >= WALL_PENALTY_FAR) {
                        wallPenalty = 1.0;
                    } else {
                        double wt = (wallDist - WALL_PENALTY_NEAR) / (WALL_PENALTY_FAR - WALL_PENALTY_NEAR);
                        wallPenalty = WALL_PENALTY_MIN + (1.0 - WALL_PENALTY_MIN) * wt * wt;
                    }

                    // 密集ボーナスを上限付きに抑える（団子化防止）
                    double collectU = 0.0;
                    if (r.heldBallCount < r.getIntakeCapacity()) {
                        collectU = capacityMargin * allyBallPenalty * targetOverlapPenalty
                                * (1.0 / (1.0 + dist)) * (1.0 + 0.1 * Math.min(density, 3))
                                * goalProximityBonus * wallPenalty
                                * collectUrgencyFactor;
                        if ("COLLECT".equals(prevAction)) collectU += HYSTERESIS_BONUS;
                        if (ball.id == r.collectTargetBallId) {
                            collectU += 0.25; // ターゲットボールヒステリシス
                        }
                        // チーム全体最適化の割当ボーナス（このロボットに割り当てられたボールを優先）。
                        // 群がりを防ぎ、アライアンス3台でボールを分担して総回収量を最大化する。
                        if (r.assignedBallId >= 0 && ball.id == r.assignedBallId) {
                            collectU += TEAM_ASSIGN_BONUS;
                        }
                    }
                    if (collectU > maxCollectU) {
                        maxCollectU = collectU;
                    }
                    if (collectU > maxUtility) {
                        maxUtility = collectU;
                        chosenAction = "COLLECT";
                        // 予測インターセプト点を移動目標にする（先回り）
                        targetX = aimX;
                        targetY = aimY;
                        bestCollectBallId = ball.id;
                    }
                }

                // --- 2. 自陣シュートの効用 ---
                double estProb = bestProb; // 理論上の最大成功率を基準にする
                canHitGoal = checkShootTargetHit(r, r.x, r.y, r.heading, obstacles);
                double shootWeight = (r.getBehaviorType() == CompetitionRobot.BehaviorType.SHOOT) ? 1.5 : 0.2;
                // ゴール周辺の混雑ペナルティ（赤青ゴール近くに味方がいるほど効用減）
                double goalAreaCongestion = 1.0;
                for (CompetitionRobot ally : robots) {
                    if (ally == r || ally.getAlliance() != r.getAlliance()) continue;
                    // 比較のみ（< 1.5）のため2乗距離化（厳密同値）
                    double agx = ally.x - goalX, agy = ally.y - goalY;
                    if (agx * agx + agy * agy < 2.25) {
                        goalAreaCongestion *= ALLY_SHOOT_OVERLAP_PENALTY;
                    }
                }
                double shootU;
                if (canHitGoal) {
                    // heldReadiness: MIN_SHOOT_HOLD以上で満タン待ちなし（回転効率優先）
                    shootU = heldReadiness * estProb * shootWeight * GOAL_UTILITY_RATIO
                            * allyShootPenalty * goalAreaCongestion;
                } else {
                    // ゴールを狙えない位置・向きでも、保持数があるなら「狙える位置へ移動する」効用を持たせる
                    double distToShootPos = dist(dynTargetX - r.x, dynTargetY - r.y);
                    double moveProb = 1.0 / (1.0 + distToShootPos);
                    shootU = heldReadiness * bestProb * moveProb * shootWeight * GOAL_UTILITY_RATIO
                            * allyShootPenalty * goalAreaCongestion * 0.8;
                }
                if ("SHOOT".equals(prevAction)) shootU += HYSTERESIS_BONUS;
                shootU += shootUrgencyBoost;
                maxShootU = shootU;
                if (shootU > maxUtility) {
                    maxUtility = shootU;
                    chosenAction = "SHOOT";
                    targetX = dynTargetX;
                    targetY = dynTargetY;
                    targetHeading = dynTargetHeading;
                }

                // --- 3. 白ゴールの効用（左右を個別評価し、混んでいない方を優先） ---
                double robotRadius = r.getConfig().robotSize / 2.0;
                double ejectDist = robotRadius - 0.02; // 接触マージンを考慮して白線より手前に設定

                // 左白線のパラメータ（中点と内向き法線）
                double wlAimX = 0.264, wlAimY = 5.95;
                double wlNx = 0.503, wlNy = -0.864;
                double wlTargetX = wlAimX - wlNx * ejectDist;
                double wlTargetY = wlAimY - wlNy * ejectDist;
                double wlTargetHeading = Math.atan2(-wlNy, -wlNx);

                // 右白線のパラメータ（中点と内向き法線）
                double wrAimX = 6.75, wrAimY = 5.943;
                double wrNx = -0.494, wrNy = -0.869;
                double wrTargetX = wrAimX - wrNx * ejectDist;
                double wrTargetY = wrAimY - wrNy * ejectDist;
                double wrTargetHeading = Math.atan2(-wrNy, -wrNx);

                double distToLeft  = dist(wlTargetX - r.x, wlTargetY - r.y);
                double distToRight = dist(wrTargetX - r.x, wrTargetY - r.y);
                double whiteWeight = (r.getBehaviorType() == CompetitionRobot.BehaviorType.WHITE) ? 1.5 : 0.2;

                // アライアンス全体で白狙いは1台のみ（左右合計で1台以上いれば選ばせない）
                int totalAlliesAtWhite = alliesAtWhiteLeft + alliesAtWhiteRight;

                double whiteLeftU = heldReadiness * (1.0 / (1.0 + distToLeft)) * whiteWeight * allyWhiteLeftPenalty;
                if ("WHITE".equals(prevAction)) whiteLeftU += HYSTERESIS_BONUS;
                if (r.ejectRetryCount >= 3 || totalAlliesAtWhite >= 1) {
                    whiteLeftU = 0.0;
                }
                if (whiteLeftU > maxUtility) {
                    maxUtility = whiteLeftU;
                    chosenAction = "WHITE";
                    targetX = wlTargetX;
                    targetY = wlTargetY;
                    targetHeading = wlTargetHeading;
                }

                double whiteRightU = heldReadiness * (1.0 / (1.0 + distToRight)) * whiteWeight * allyWhiteRightPenalty;
                if ("WHITE".equals(prevAction)) whiteRightU += HYSTERESIS_BONUS;
                if (r.ejectRetryCount >= 3 || totalAlliesAtWhite >= 1) {
                    whiteRightU = 0.0;
                }
                if (whiteRightU > maxUtility) {
                    maxUtility = whiteRightU;
                    chosenAction = "WHITE";
                    targetX = wrTargetX;
                    targetY = wrTargetY;
                    targetHeading = wrTargetHeading;
                }
                maxWhiteU = Math.max(whiteLeftU, whiteRightU);

                // --- 4. 妨害行動の効用 (interfereCoeff > 0 の場合のみ) ---
                if (interfereCoeff > 0.01) {
                    double interfereCapacityPenalty = (r.heldBallCount >= r.getIntakeCapacity()) ? 0.1 : 1.0;

                    // 4a. 接触妨害 (BODYBLOCK): 保持多く射出体勢の敵に体当たり
                    {
                        CompetitionRobot bbTarget = null;
                        double bbBest = 0.0;
                        for (CompetitionRobot enemy : robots) {
                            if (enemy.getAlliance() == r.getAlliance()) continue;
                            if ("WHITE".equals(enemy.lastChosenAction)) continue; // 白狙いは妨害対象外
                            double enemyHeld = (double) enemy.heldBallCount / Math.max(1, enemy.getIntakeCapacity());
                            boolean enemyCanShoot = checkShootTargetHit(enemy, enemy.x, enemy.y, enemy.heading, obstacles);
                            double dToEnemy = dist(enemy.x - r.x, enemy.y - r.y);
                            double urgency = enemyHeld * (enemyCanShoot ? 1.5 : 0.5) / (1.0 + dToEnemy);
                            for (CompetitionRobot ally : robots) {
                                if (ally == r || ally.getAlliance() != r.getAlliance()) continue;
                                if (ally.interfereTargetId == enemy.getId() && "BODYBLOCK".equals(ally.lastChosenAction)) {
                                    urgency *= ALLY_GOAL_OVERLAP_PENALTY;
                                }
                            }
                            if (urgency > bbBest) { bbBest = urgency; bbTarget = enemy; }
                        }
                        if (bbTarget != null) {
                            double bbU = bbBest * interfereCoeff * INTERFERE_GLOBAL_SCALE * interfereCapacityPenalty;
                            if ("BODYBLOCK".equals(prevAction)) bbU += HYSTERESIS_BONUS;
                            if (bbU > maxUtility) {
                                maxUtility = bbU;
                                chosenAction = "BODYBLOCK";
                                targetX = bbTarget.x;
                                targetY = bbTarget.y;
                                targetHeading = Math.atan2(targetY - r.y, targetX - r.x);
                                newInterfereTargetId = bbTarget.getId();
                            }
                        }
                    }

                    // 4b. ボール奪取 (STEAL): 敵が向かっているボールを先取り
                    if (capacityMargin > 0.01) {
                        for (core.BallState ball : balls) {
                            if (ball.collected || ball.scored) continue;
                            double dSelf = dist(ball.x - r.x, ball.y - r.y);
                            double minEnemyDist = Double.MAX_VALUE;
                            for (CompetitionRobot enemy : robots) {
                                if (enemy.getAlliance() == r.getAlliance()) continue;
                                double d = dist(ball.x - enemy.x, ball.y - enemy.y);
                                if (d < minEnemyDist) minEnemyDist = d;
                            }
                            if (minEnemyDist > 2.0) continue; // 敵が近づいていないボールは奪取対象外
                            double urgency = 2.0 / (1.0 + minEnemyDist);
                            double stealU = capacityMargin * urgency * (1.0 / (1.0 + dSelf)) * interfereCoeff * INTERFERE_GLOBAL_SCALE;
                            if ("STEAL".equals(prevAction)) stealU += HYSTERESIS_BONUS;
                            if (stealU > maxUtility) {
                                stealU = stealU;
                                maxUtility = stealU;
                                chosenAction = "STEAL";
                                targetX = ball.x;
                                targetY = ball.y;
                                targetHeading = Math.atan2(targetY - r.y, targetX - r.x);
                                newInterfereTargetId = -1;
                            }
                        }
                    }

                    // 4c. ディフェンス (DEFENSE): 自陣ゴールと敵の間に割り込む
                    {
                        CompetitionRobot defTarget = null;
                        double defBest = 0.0;
                        for (CompetitionRobot enemy : robots) {
                            if (enemy.getAlliance() == r.getAlliance()) continue;
                            if ("WHITE".equals(enemy.lastChosenAction)) continue; // 白狙いは妨害対象外
                            double dEnemyToGoal = dist(enemy.x - goalX, enemy.y - goalY);
                            double hToGoal = Math.atan2(goalY - enemy.y, goalX - enemy.x);
                            double hdiff = Math.abs(normalizeAngle(enemy.heading - hToGoal));
                            boolean facingOwnGoal = hdiff < Math.toRadians(60.0);
                            double urgency = (facingOwnGoal ? 1.5 : 0.5) / (1.0 + dEnemyToGoal);
                            for (CompetitionRobot ally : robots) {
                                if (ally == r || ally.getAlliance() != r.getAlliance()) continue;
                                if (ally.interfereTargetId == enemy.getId() && "DEFENSE".equals(ally.lastChosenAction)) {
                                    urgency *= ALLY_GOAL_OVERLAP_PENALTY;
                                }
                            }
                            if (urgency > defBest) { defBest = urgency; defTarget = enemy; }
                        }
                        if (defTarget != null) {
                            double defIntX = (goalX + defTarget.x) / 2.0;
                            double defIntY = (goalY + defTarget.y) / 2.0;
                            double dToDefPos = dist(defIntX - r.x, defIntY - r.y);
                            double defU = defBest * (1.0 / (1.0 + dToDefPos)) * interfereCoeff * INTERFERE_GLOBAL_SCALE * interfereCapacityPenalty;
                            if ("DEFENSE".equals(prevAction)) defU += HYSTERESIS_BONUS;
                            if (defU > maxUtility) {
                                maxUtility = defU;
                                chosenAction = "DEFENSE";
                                targetX = defIntX;
                                targetY = defIntY;
                                targetHeading = Math.atan2(defTarget.y - r.y, defTarget.x - r.x);
                                newInterfereTargetId = defTarget.getId();
                            }
                        }
                    }

                    // 4d. 撹乱 (DISRUPT): 敵が狙うボール塊に突っ込んで散らす（回収効率低下）
                    for (CompetitionRobot enemy : robots) {
                        if (enemy.getAlliance() == r.getAlliance()) continue;
                        if ("WHITE".equals(enemy.lastChosenAction)) continue; // 白狙い敵は対象外
                        if (!"COLLECT".equals(enemy.lastChosenAction) && !"STEAL".equals(enemy.lastChosenAction)) continue;

                        double eTargetX = !Double.isNaN(enemy.cachedTargetX) ? enemy.cachedTargetX : enemy.x;
                        double eTargetY = !Double.isNaN(enemy.cachedTargetY) ? enemy.cachedTargetY : enemy.y;

                        // 敵目標周辺 0.8m のボール密集度と重心
                        int clusterCount = 0;
                        double clusterCX = 0.0, clusterCY = 0.0;
                        // 敵目標周辺 0.8m(<=セル1.0m) を空間グリッドで粗選別＋正確距離。
                        // 昇順インデックス走査で候補集合・加算順序とも全走査と一致（ビット単位同一）。
                        int nbD = collectBallNeighbors(eTargetX, eTargetY);
                        for (int ni = 0; ni < nbD; ni++) {
                            core.BallState ball = balls.get(ballNeighborScratch[ni]);
                            if (ball.collected || ball.scored) continue;
                            // 比較のみ（<= 0.8）のため2乗距離化（厳密同値。重心加算順序は不変）
                            double cdx = ball.x - eTargetX, cdy = ball.y - eTargetY;
                            if (cdx * cdx + cdy * cdy <= 0.64) {
                                clusterCount++;
                                clusterCX += ball.x;
                                clusterCY += ball.y;
                            }
                        }
                        if (clusterCount < 3) continue; // 3個未満は散らす価値なし
                        clusterCX /= clusterCount;
                        clusterCY /= clusterCount;

                        // 味方がこの塊を狙っていれば除外
                        boolean allyTargeting = false;
                        for (CompetitionRobot ally : robots) {
                            if (ally == r || ally.getAlliance() != r.getAlliance()) continue;
                            if (!"COLLECT".equals(ally.lastChosenAction)) continue;
                            double aTX = Double.isNaN(ally.cachedTargetX) ? ally.x : ally.cachedTargetX;
                            double aTY = Double.isNaN(ally.cachedTargetY) ? ally.y : ally.cachedTargetY;
                            // 比較のみ（< 1.5）のため2乗距離化（厳密同値）
                            double atcx = aTX - clusterCX, atcy = aTY - clusterCY;
                            if (atcx * atcx + atcy * atcy < 2.25) {
                                allyTargeting = true;
                                break;
                            }
                        }
                        if (allyTargeting) continue;

                        double distToCluster = dist(clusterCX - r.x, clusterCY - r.y);

                        // 経路近接度: 現在の目標への移動経路からの側方距離（経路上の塊は高評価）
                        double pathProximity = 1.0;
                        if (!Double.isNaN(targetX) && !Double.isNaN(targetY)) {
                            double mvDx = targetX - r.x, mvDy = targetY - r.y;
                            double mvLen2 = mvDx * mvDx + mvDy * mvDy;
                            if (mvLen2 > 0.25) {
                                double t = Math.max(0.0, Math.min(1.0,
                                    ((clusterCX - r.x) * mvDx + (clusterCY - r.y) * mvDy) / mvLen2));
                                double projX = r.x + t * mvDx, projY = r.y + t * mvDy;
                                double lateralDist = dist(clusterCX - projX, clusterCY - projY);
                                pathProximity = 1.0 / (1.0 + lateralDist * 2.5);
                            }
                        }

                        double clusterValue = Math.min(clusterCount, 8) / 4.0; // 最大8個でスケーリング
                        double enemyProximity = 1.0 / (1.0 + dist(enemy.x - clusterCX, enemy.y - clusterCY) * 0.5);
                        double selfAccess = 1.0 / (1.0 + distToCluster * 0.4);
                        double disruptScore = clusterValue * enemyProximity * selfAccess * pathProximity;
                        double disruptU = disruptScore * interfereCoeff * INTERFERE_GLOBAL_SCALE;
                        if ("DISRUPT".equals(prevAction)) disruptU += HYSTERESIS_BONUS;
                        if (disruptU > maxUtility) {
                            maxUtility = disruptU;
                            chosenAction = "DISRUPT";
                            targetX = clusterCX;
                            targetY = clusterCY;
                            targetHeading = Math.atan2(clusterCY - r.y, clusterCX - r.x);
                            newInterfereTargetId = enemy.getId();
                        }
                    }
                }
                } // end: !shootCommitActive || heldBallCount <= 0
            }

            // SHOOT が選ばれた（または継続中）ならコミット開始
            if ("SHOOT".equals(chosenAction) && r.heldBallCount > 0) {
                if (!r.shootCommitActive) {
                    r.shootCommitActive = true;
                    r.shootCommitStuckTimer = 0.0;
                    r.shootCommitTurnTimer = 0.0;
                    r.shootCommitTargetX = targetX;
                    r.shootCommitTargetY = targetY;
                    r.shootCommitTargetHeading = targetHeading;
                    r.shootCommitGoalPosX = goalPosX;
                    r.shootCommitGoalPosY = goalPosY;
                    r.shootCommitIdealTargetX = targetX;
                    r.shootCommitIdealTargetY = targetY;
                    r.shootCommitIdealTargetHeading = targetHeading;
                }
            }

            // --- 前面接触判定 (WHITE排出用) ---
            frontContactWhite = false;
            distToWhite = Double.MAX_VALUE;
            if ("WHITE".equals(chosenAction)) {
                boolean isLeftWhite = (targetX < 3.5);
                double aimX = isLeftWhite ? 0.264 : 6.75;
                double aimY = isLeftWhite ? 5.95 : 5.943;
                distToWhite = dist(aimX - r.x, aimY - r.y);
                double angleErr = Math.abs(normalizeAngle(targetHeading - r.heading));
                double rRad = r.getConfig().robotSize / 2.0;
                boolean isCollidingFireShield = r.obstacleCollisionFlag && r.obstacleCollisionId != null 
                    && (r.obstacleCollisionId.equals("fire_shield_left") || r.obstacleCollisionId.equals("fire_shield_right"));
                if (distToWhite <= rRad + 0.12 && angleErr < Math.toRadians(20.0)) {
                    frontContactWhite = true;
                } else if (isCollidingFireShield && distToWhite <= rRad + 0.18 && angleErr < Math.toRadians(30.0)) {
                    frontContactWhite = true;
                }
            }

            // --- アクション決定・実行フラグ ---
            if ("COLLECT".equals(chosenAction)) {
                r.intakeActive = true;
                r.shootActive = false;
                r.ejectActive = false;
                
                // 周辺ボールのカウントと重心計算
                int nearbyBallCount = 0;
                double sumBallX = 0.0;
                double sumBallY = 0.0;
                double surroundRadius = 0.8;
                // 半径0.8m(<=セル1.0m)近傍を空間グリッドで粗選別＋正確距離フィルタ。
                // 昇順インデックス走査で候補集合・加算順序とも全走査と一致（ビット単位同一）。
                int nbS = collectBallNeighbors(r.x, r.y);
                double surroundRadius2 = surroundRadius * surroundRadius;
                for (int ni = 0; ni < nbS; ni++) {
                    core.BallState ball = balls.get(ballNeighborScratch[ni]);
                    if (ball.collected || ball.scored) continue;
                    // 比較のみ（<= surroundRadius）のため2乗距離化（厳密同値・重心加算順序は不変）
                    double bdx = ball.x - r.x, bdy = ball.y - r.y;
                    if (bdx * bdx + bdy * bdy <= surroundRadius2) {
                        nearbyBallCount++;
                        sumBallX += ball.x;
                        sumBallY += ball.y;
                    }
                }

                if (nearbyBallCount >= 2) {
                    // 囲まれ状態：目標位置を重心方向にするが、壁や障害物を避ける
                    double cx = sumBallX / nearbyBallCount;
                    double cy = sumBallY / nearbyBallCount;

                    double dirX = cx - r.x;
                    double dirY = cy - r.y;
                    double dirLen = dist(dirX, dirY);
                    if (dirLen > 1e-5) {
                        dirX /= dirLen;
                        dirY /= dirLen;
                    }
                    
                    // 壁回避（0.5m以内なら壁から離れる方向を強制的に加算）
                    double avoidForceX = 0.0;
                    double avoidForceY = 0.0;
                    
                    if (r.x < 0.5) {
                        avoidForceX += (0.5 - r.x) * 2.0;
                    } else if (7.0 - r.x < 0.5) {
                        avoidForceX -= (0.5 - (7.0 - r.x)) * 2.0;
                    }
                    
                    if (r.y < 0.5) {
                        avoidForceY += (0.5 - r.y) * 2.0;
                    } else if (7.0 - r.y < 0.5) {
                        avoidForceY -= (0.5 - (7.0 - r.y)) * 2.0;
                    }
                    
                    // 障害物回避（0.5m以内なら障害物から離れる方向を強制的に加算）
                    for (FieldObstacle obs : obstacles) {
                        if ("tape".equals(obs.category())) continue;
                        CollisionResult res = obs.collideCircle(r.x, r.y, 0.50);
                        if (res != null) {
                            double mLen = dist(res.mtvX, res.mtvY);
                            if (mLen > 1e-6) {
                                double weight = res.depth / 0.50;
                                avoidForceX += (res.mtvX / mLen) * weight * 1.5;
                                avoidForceY += (res.mtvY / mLen) * weight * 1.5;
                            }
                        }
                    }

                    double finalDirX = dirX + avoidForceX;
                    double finalDirY = dirY + avoidForceY;
                    double finalDirLen = dist(finalDirX, finalDirY);
                    if (finalDirLen > 1e-5) {
                        finalDirX /= finalDirLen;
                        finalDirY /= finalDirLen;
                    }
                    
                    // targetX, targetY は安全な0.5m先の位置に設定
                    targetX = r.x + finalDirX * 0.5;
                    targetY = r.y + finalDirY * 0.5;
                    
                    // 目標角度は、現在角度に重心方向への偏差を30%だけブレンド（緩く向く）
                    double gravityHeading = Math.atan2(finalDirY, finalDirX);
                    double angleDiffToGravity = normalizeAngle(gravityHeading - r.heading);
                    targetHeading = normalizeAngle(r.heading + angleDiffToGravity * 0.3);
                } else {
                    targetHeading = Math.atan2(targetY - r.y, targetX - r.x);
                }
            } else if ("SHOOT".equals(chosenAction)) {
                // 射線がゴールに通っているなら即座に射撃フラグを立てる
                if (canHitGoal) {
                    r.shootActive = true;
                } else {
                    r.shootActive = false;
                }
                r.intakeActive = false;
                r.ejectActive = false;
            } else if ("WHITE".equals(chosenAction)) {
                if (frontContactWhite) {
                    r.ejectActive = true;
                    r.ejectStuckTimer = 0.0;
                    r.ejectRetryCount = 0;
                } else {
                    r.ejectActive = false;
                    if (r.ejectBackupTimer <= 0.0) {
                        r.ejectStuckTimer += dt;
                        if (r.ejectStuckTimer >= 1.5) {
                            r.ejectBackupTimer = 0.6; // 0.6秒間後退する
                            r.ejectRetryCount++;
                            r.ejectStuckTimer = 0.0;
                            r.decisionTimer = 0.0; // 即時再評価
                        }
                    }
                }
                r.intakeActive = false;
                r.shootActive = false;
            } else if ("BODYBLOCK".equals(chosenAction) || "DEFENSE".equals(chosenAction)) {
                // 接触系妨害: 目標地点に突進、インテーク不要
                r.intakeActive = false;
                r.shootActive = false;
                r.ejectActive = false;
                targetHeading = Math.atan2(targetY - r.y, targetX - r.x);
            } else if ("STEAL".equals(chosenAction)) {
                r.intakeActive = true;
                r.shootActive = false;
                r.ejectActive = false;
                targetHeading = Math.atan2(targetY - r.y, targetX - r.x);
            } else if ("DISRUPT".equals(chosenAction)) {
                // 撹乱: 塊に突進しながら拾えるボールは回収。インテーク有効で二重効果（散らす＋奪う）
                r.intakeActive = true;
                r.shootActive = false;
                r.ejectActive = false;
                targetHeading = Math.atan2(targetY - r.y, targetX - r.x);
            } else { // WANDER
                r.intakeActive = false;
                r.shootActive = false;
                r.ejectActive = false;
            }

            // 行動・標的を保存（次フレームのヒステリシス・住み分けに使用）
            if (!chosenAction.equals(prevAction)) {
                r.prevAngleError = Double.NaN;
            }
            r.lastChosenAction = chosenAction;
            r.interfereTargetId = newInterfereTargetId;
            if ("COLLECT".equals(chosenAction)) {
                r.collectTargetBallId = bestCollectBallId;
            } else {
                r.collectTargetBallId = -1;
            }

            // 経路計画（改善2）: 目標への直線が障害物に塞がれている場合はウェイポイントを設定
            if (!Double.isNaN(targetX) && !Double.isNaN(targetY)) {
                double[] wp = computeMovementWaypoint(r.x, r.y, targetX, targetY, obstacles);
                r.pathWaypointX = (wp != null) ? wp[0] : Double.NaN;
                r.pathWaypointY = (wp != null) ? wp[1] : Double.NaN;
            }

            // 決定内容をキャッシュに保存
            r.cachedTargetX = targetX;
            r.cachedTargetY = targetY;
            r.cachedTargetHeading = targetHeading;
            r.cachedChosenAction = chosenAction;
            r.cachedGoalPosX = goalPosX;
            r.cachedGoalPosY = goalPosY;
            r.cachedNewInterfereTargetId = newInterfereTargetId;
            r.cachedBestCollectBallId = bestCollectBallId;
            r.cachedCanHitGoal = canHitGoal;

            // 次回の状態急変検知用に、今回意思決定時の状態をスナップショット
            r.lastEvalHeldBallCount = r.heldBallCount;
            r.lastEvalBehaviorType = r.getBehaviorType();
            r.lastEvalX = r.x;
            r.lastEvalY = r.y;
            r.lastEvalCollectTargetBallId = r.collectTargetBallId;
            r.lastEvalShootCommitActive = r.shootCommitActive;
            r.lastEvalShootRange = r.getShootRange();
            r.lastEvalEvaluated = true;

            r.decisionTimer = r.decisionInterval;
        } else {
            // キャッシュから復元
            targetX = r.cachedTargetX;
            targetY = r.cachedTargetY;
            targetHeading = r.cachedTargetHeading;
            chosenAction = r.cachedChosenAction;
            goalPosX = r.cachedGoalPosX;
            goalPosY = r.cachedGoalPosY;
            newInterfereTargetId = r.cachedNewInterfereTargetId;
            bestCollectBallId = r.cachedBestCollectBallId;
            canHitGoal = r.cachedCanHitGoal;

            // 以前選択されていたアクション名を同期
            if ("COLLECT".equals(chosenAction)) {
                r.collectTargetBallId = bestCollectBallId;
            } else {
                r.collectTargetBallId = -1;
            }
            r.interfereTargetId = newInterfereTargetId;
            r.lastChosenAction = chosenAction;
        }

            // 囲まれ状態の目標位置を毎フレーム動的に更新（意思決定間引きに関係なく毎フレーム計算）
            if ("COLLECT".equals(chosenAction)) {
                int nearbyBallCount = 0;
                double sumBallX = 0.0, sumBallY = 0.0;
                // 半径0.8m(<=セル1.0m)の近傍ボールを空間グリッドで粗選別し、昇順インデックスで
                // 正確距離フィルタ。候補集合・加算順序とも全走査と一致するためビット単位で同一結果。
                int nb = collectBallNeighbors(r.x, r.y);
                for (int ni = 0; ni < nb; ni++) {
                    core.BallState ball = balls.get(ballNeighborScratch[ni]);
                    if (ball.collected || ball.scored) continue;
                    // 比較のみ（<= 0.8）のため2乗距離化（厳密同値・件数のみで順序非依存）
                    double bdx = ball.x - r.x, bdy = ball.y - r.y;
                    if (bdx * bdx + bdy * bdy <= 0.64) {
                        nearbyBallCount++;
                        sumBallX += ball.x;
                        sumBallY += ball.y;
                    }
                }
                if (nearbyBallCount >= 2) {
                    double cxB = sumBallX / nearbyBallCount;
                    double cyB = sumBallY / nearbyBallCount;
                    double dirX = cxB - r.x, dirY = cyB - r.y;
                    double dirLen = dist(dirX, dirY);
                    if (dirLen > 1e-5) { dirX /= dirLen; dirY /= dirLen; }

                    double avoidFX = 0.0, avoidFY = 0.0;
                    if (r.x < 0.5)            avoidFX += (0.5 - r.x) * 2.0;
                    else if (7.0 - r.x < 0.5) avoidFX -= (0.5 - (7.0 - r.x)) * 2.0;
                    if (r.y < 0.5)            avoidFY += (0.5 - r.y) * 2.0;
                    else if (7.0 - r.y < 0.5) avoidFY -= (0.5 - (7.0 - r.y)) * 2.0;

                    for (FieldObstacle obs : obstacles) {
                        if ("tape".equals(obs.category())) continue;
                        CollisionResult res = obs.collideCircle(r.x, r.y, 0.50);
                        if (res != null) {
                            double mLen = dist(res.mtvX, res.mtvY);
                            if (mLen > 1e-6) {
                                double weight = res.depth / 0.50;
                                avoidFX += (res.mtvX / mLen) * weight * 1.5;
                                avoidFY += (res.mtvY / mLen) * weight * 1.5;
                            }
                        }
                    }

                    double finalDX = dirX + avoidFX;
                    double finalDY = dirY + avoidFY;
                    double finalLen = dist(finalDX, finalDY);
                    if (finalLen > 1e-5) { finalDX /= finalLen; finalDY /= finalLen; }

                    targetX = r.x + finalDX * 0.5;
                    targetY = r.y + finalDY * 0.5;
                    targetHeading = normalizeAngle(r.heading + normalizeAngle(Math.atan2(finalDY, finalDX) - r.heading) * 0.3);
                }
                // 改善2: Uターン回収 - 非囲まれ時、保持量に応じてゴール方向へ先回りブレンド
                if (nearbyBallCount < 2 && r.heldBallCount > 0) {
                    double approxMinHold = MIN_SHOOT_HOLD_RATIO * r.getIntakeCapacity();
                    double blendProgress = Math.min(1.0, Math.max(0.0,
                        (r.heldBallCount - approxMinHold * 0.4) / Math.max(0.5, approxMinHold * 0.6)));
                    double blendRatio = blendProgress * 0.25;
                    if (blendRatio > 0.02) {
                        boolean rIsBlue = r.getAlliance() == CompetitionRobot.Alliance.BLUE;
                        double approxGoalX = rIsBlue ? 5.0 : 2.0;
                        double approxGoalY = 1.0;
                        double goalAngle = Math.atan2(approxGoalY - r.y, approxGoalX - r.x);
                        targetHeading = normalizeAngle(targetHeading + normalizeAngle(goalAngle - targetHeading) * blendRatio);
                    }
                }
            }

            // --- 回避ステアリング計算 ---
            // 経路計画: 有効なウェイポイントを即時移動目標として使用
            double moveToX = targetX, moveToY = targetY;
            boolean usingWaypoint = false;
            if (!Double.isNaN(r.pathWaypointX) && !Double.isNaN(r.pathWaypointY)) {
                // 比較のみ（> 0.4）のため2乗距離化（厳密同値）
                double wpdx = r.pathWaypointX - r.x, wpdy = r.pathWaypointY - r.y;
                if (wpdx * wpdx + wpdy * wpdy > 0.16) {
                    moveToX = r.pathWaypointX;
                    moveToY = r.pathWaypointY;
                    usingWaypoint = true;
                } else {
                    r.pathWaypointX = Double.NaN;
                    r.pathWaypointY = Double.NaN;
                }
            }
            // 改善1: 経路への動的侵入回避 - ウェイポイント経路上に他ロボットが侵入したらクリアして経路を引き直す
            if (usingWaypoint) {
                double wpDx = moveToX - r.x, wpDy = moveToY - r.y;
                double wpLen2 = wpDx * wpDx + wpDy * wpDy;
                if (wpLen2 > 1e-6) {
                    for (CompetitionRobot other : robots) {
                        if (other == r) continue;
                        double t = Math.max(0.0, Math.min(1.0,
                            ((other.x - r.x) * wpDx + (other.y - r.y) * wpDy) / wpLen2));
                        double closestX = r.x + t * wpDx;
                        double closestY = r.y + t * wpDy;
                        // 比較のみ（< 0.55）のため2乗距離化（厳密同値）
                        double ocx = other.x - closestX, ocy = other.y - closestY;
                        if (ocx * ocx + ocy * ocy < 0.3025) {
                            r.pathWaypointX = Double.NaN;
                            r.pathWaypointY = Double.NaN;
                            moveToX = targetX;
                            moveToY = targetY;
                            r.decisionTimer = Math.min(r.decisionTimer, r.decisionInterval * 0.5);
                            break;
                        }
                    }
                }
            }
            double toTargetDx = moveToX - r.x;
            double toTargetDy = moveToY - r.y;
            double toTargetDist = dist(toTargetDx, toTargetDy);

            double avoidX = 0.0, avoidY = 0.0;
            double sepX   = 0.0, sepY   = 0.0;

            // 障害物（Fire Shield, Suppression Unit, brace）への回避
            double obsAvoidRadius = 0.70; // ロボットの半径0.25m + 回避マージン0.45m
            for (FieldObstacle obs : obstacles) {
                if ("tape".equals(obs.category())) continue; // スタートテープは除外

                double currentAvoidRadius = obsAvoidRadius;
                boolean isTargetShield = false;
                if ("WHITE".equals(chosenAction)) {
                    boolean isLeftWhite = (targetX < 3.5);
                    if (isLeftWhite && "fire_shield_left".equals(obs.id())) isTargetShield = true;
                    if (!isLeftWhite && "fire_shield_right".equals(obs.id())) isTargetShield = true;
                }
                if (isTargetShield) {
                    currentAvoidRadius = 0.30; // 接近を許可するため回避半径を縮小
                }

                CollisionResult res = obs.collideCircle(r.x, r.y, currentAvoidRadius);
                if (res != null) {
                    double mLen = dist(res.mtvX, res.mtvY);
                    if (mLen > 1e-6) {
                        double nx = res.mtvX / mLen;
                        double ny = res.mtvY / mLen;
                        double weight = res.depth / currentAvoidRadius;

                        // 進行方向の前方に障害物がある場合、迂回方向（側方）のベクトルをブレンド
                        double rCosH = Math.cos(r.heading);
                        double rSinH = Math.sin(r.heading);
                        double toObsX = -nx;
                        double toObsY = -ny;
                        double dot = toObsX * rCosH + toObsY * rSinH;

                        if (dot > 0.1) {
                            double cross = rCosH * toObsY - rSinH * toObsX;
                            double side = cross >= 0.0 ? 1.0 : -1.0;
                            double orthoX = -side * rSinH;
                            double orthoY = side * rCosH;
                            
                            avoidX += (nx * 1.5 + orthoX * 1.2) * weight;
                            avoidY += (ny * 1.5 + orthoY * 1.2) * weight;
                        } else {
                            avoidX += nx * weight;
                            avoidY += ny * weight;
                        }
                    }
                }
            }

            double PREDICT_TIME = 0.8;
            double rCosH = Math.cos(r.heading);
            double rSinH = Math.sin(r.heading);
            double vxGlobal = r.v * rCosH - r.vy * rSinH;
            double vyGlobal = r.v * rSinH + r.vy * rCosH;

            double myFutureX = r.x + vxGlobal * PREDICT_TIME;
            double myFutureY = r.y + vyGlobal * PREDICT_TIME;

            if (toTargetDist > 0.05) {
                for (CompetitionRobot other : robots) {
                    if (other == r) continue;

                    // 妨害標的の敵には回避・分散を切る（接触したいから）
                    boolean isInterfereTarget = (r.interfereTargetId >= 0)
                        && (r.interfereTargetId == other.getId())
                        && ("BODYBLOCK".equals(chosenAction) || "DEFENSE".equals(chosenAction));
                    if (isInterfereTarget) continue;

                    boolean isAlly = other.getAlliance() == r.getAlliance();
                    
                    double otherCosH = Math.cos(other.heading);
                    double otherSinH = Math.sin(other.heading);
                    double otherVxGlobal = other.v * otherCosH - other.vy * otherSinH;
                    double otherVyGlobal = other.v * otherSinH + other.vy * otherCosH;
                    
                    double otherFutureX = other.x + otherVxGlobal * PREDICT_TIME;
                    double otherFutureY = other.y + otherVyGlobal * PREDICT_TIME;

                    double adx = r.x - other.x;
                    double ady = r.y - other.y;
                    double currentDist = dist(adx, ady);

                    double fdx = myFutureX - otherFutureX;
                    double fdy = myFutureY - otherFutureY;
                    double predictedDist = dist(fdx, fdy);

                    if (currentDist > 1e-6) {
                        // 正面衝突判定
                        double relVx = vxGlobal - otherVxGlobal;
                        double relVy = vyGlobal - otherVyGlobal;
                        double toOtherX = other.x - r.x;
                        double toOtherY = other.y - r.y;
                        
                        // お互いに接近中（相対速度と相手方向の内積が正）
                        double approachSpeed = (relVx * toOtherX + relVy * toOtherY) / currentDist;
                        
                        // 自分の向きと相手への方向の角度差
                        double toOtherAngle = Math.atan2(toOtherY, toOtherX);
                        double angleDiffSelf = Math.abs(normalizeAngle(r.heading - toOtherAngle));
                        
                        // 相手の向きと自分への方向の角度差
                        double angleDiffOther = Math.abs(normalizeAngle(other.heading - (toOtherAngle + Math.PI)));

                        boolean approaching = approachSpeed > 0.1;
                        boolean facingEachOther = (angleDiffSelf < Math.toRadians(45.0)) && (angleDiffOther < Math.toRadians(45.0));
                        boolean inAvoidRange = (currentDist < AVOID_RADIUS) || (predictedDist < AVOID_RADIUS);

                        if (approaching && facingEachOther && inAvoidRange) {
                            // 非対称回避（横に避ける）
                            double crossVal = rCosH * toOtherY - rSinH * toOtherX;
                            double side = crossVal >= 0.0 ? 1.0 : -1.0;
                            // 相手と逆側に90度回転した方向
                            double avoidDirX = side * rSinH;
                            double avoidDirY = -side * rCosH;
                            
                            double weight = (AVOID_RADIUS - Math.min(currentDist, predictedDist)) / AVOID_RADIUS;
                            if (isAlly) weight *= ALLY_AVOID_MULT;
                            
                            avoidX += avoidDirX * weight * 1.5;
                            avoidY += avoidDirY * weight * 1.5;
                        } 
                        else if (inAvoidRange) {
                            // 予測衝突または現在近接（非正面）
                            double weight = (AVOID_RADIUS - Math.min(currentDist, predictedDist)) / AVOID_RADIUS;
                            if (isAlly) weight *= ALLY_AVOID_MULT;
                            
                            double repX = (currentDist < AVOID_RADIUS) ? (adx / currentDist) : (fdx / predictedDist);
                            double repY = (currentDist < AVOID_RADIUS) ? (ady / currentDist) : (fdy / predictedDist);
                            double repLen = dist(repX, repY);
                            if (repLen > 1e-6) {
                                repX /= repLen;
                                repY /= repLen;
                            }
                            
                            avoidX += repX * weight;
                            avoidY += repY * weight;
                        }

                        // 広範囲分散
                        if (currentDist < SEPARATION_RADIUS) {
                            double sw = (SEPARATION_RADIUS - currentDist) / SEPARATION_RADIUS;
                            sepX += (adx / currentDist) * sw;
                            sepY += (ady / currentDist) * sw;
                        }
                    }
                }
            }

            // 目標方向ベクトルに回避・分散ベクトルを合成し正規化
            double moveDirX = 0.0;
            double moveDirY = 0.0;
            if (toTargetDist > 0.05) {
                // 分散強度：目標近接時は弱め、シュートコミット/接触妨害中も弱める
                boolean reduceSep = r.shootCommitActive || "BODYBLOCK".equals(chosenAction);
                double nearFactor = (toTargetDist < SEPARATION_NEAR_DIST)
                    ? SEPARATION_NEAR_SCALE + (1.0 - SEPARATION_NEAR_SCALE) * (toTargetDist / SEPARATION_NEAR_DIST)
                    : 1.0;
                // 改善3: 周囲の密度が高いほど分散力を強める（密集時だけ強く・疎なら通常）
                int localDensity = 0;
                for (CompetitionRobot densityOther : robots) {
                    if (densityOther == r) continue;
                    // 比較のみ（< 2.0）のため2乗距離化（数学的に厳密同値・丸め差なし）
                    double ddx = r.x - densityOther.x, ddy = r.y - densityOther.y;
                    if (ddx * ddx + ddy * ddy < 4.0) localDensity++;
                }
                double densityScale = 1.0 + Math.min(localDensity, 4) * SEPARATION_DENSITY_FACTOR;
                double effectiveSep = SEPARATION_STRENGTH * nearFactor * (reduceSep ? SEPARATION_COMMIT_SCALE : 1.0) * densityScale;

                // 分散貢献を上限クランプ（目標方向を完全に打ち消さない）
                double sepContribX = sepX * effectiveSep;
                double sepContribY = sepY * effectiveSep;
                double scLen = dist(sepContribX, sepContribY);
                if (scLen > SEPARATION_MAX_CLAMP) {
                    sepContribX = (sepContribX / scLen) * SEPARATION_MAX_CLAMP;
                    sepContribY = (sepContribY / scLen) * SEPARATION_MAX_CLAMP;
                }

                moveDirX = (toTargetDx / toTargetDist) + avoidX * AVOID_STRENGTH + sepContribX;
                moveDirY = (toTargetDy / toTargetDist) + avoidY * AVOID_STRENGTH + sepContribY;
                double len = dist(moveDirX, moveDirY);
                if (len > 1e-6) { moveDirX /= len; moveDirY /= len; }
            }

            // --- 膠着検知・脱出 ---
            r.stallCheckTimer += dt;
            if (Double.isNaN(r.stallCheckX)) { r.stallCheckX = r.x; r.stallCheckY = r.y; }
            boolean inRecovery = (r.stallRecoveryTimer > 0.0);
            if (inRecovery) {
                r.stallRecoveryTimer = Math.max(0.0, r.stallRecoveryTimer - dt);
                inRecovery = (r.stallRecoveryTimer > 0.0);
            } else if (r.stallCheckTimer >= STALL_CHECK_INTERVAL) {
                // moved は閾値比較のみ（< STALL_DIST_THRESHOLD）のため2乗距離で厳密同値比較
                double movedDx = r.x - r.stallCheckX, movedDy = r.y - r.stallCheckY;
                double moved2 = movedDx * movedDx + movedDy * movedDy;
                if (moved2 < STALL_DIST_THRESHOLD * STALL_DIST_THRESHOLD && toTargetDist > 0.2 && engine.isTimerStarted()) {
                    r.stallRecoveryTimer = STALL_RECOVERY_DURATION;
                    r.stallCount++;

                    // 膠着脱出開始時は現在の運動量を打ち消す。
                    // 前進中の慣性が残っていると加速度制限により後退指令が相殺され、
                    // バックに転じるまで余計な時間がかかる（壁を押し続けてしまう）。
                    // 速度をゼロに落とすことで次tickから確実に後退へ移行できる。
                    r.v = 0.0;
                    r.omega = 0.0;

                    // 1秒程度（STALL_CHECK_INTERVAL × 2回）進めなければターゲット切り替えを強制
                    if (r.stallCount >= 2) {
                        r.stallCount = 0;
                        r.decisionTimer = 0.0;         // 即時再評価
                        r.lastChosenAction = "WANDER"; // ヒステリシスをリセットして別行動を選びやすくする
                        r.collectTargetBallId = -1;    // ボールターゲットをリセット
                        r.shootCommitActive = false;   // シュートコミットも解除
                    }

                    double escapeAngle = r.heading + Math.PI;
                    // 周辺の障害物から離れる方向のMTVを合成して脱出角度を決定
                    double forceX = 0.0, forceY = 0.0;
                    for (FieldObstacle obs : obstacles) {
                        if ("tape".equals(obs.category())) continue;
                        CollisionResult res = obs.collideCircle(r.x, r.y, 1.2); // 1.2m範囲
                        if (res != null) {
                            forceX += res.mtvX;
                            forceY += res.mtvY;
                        }
                    }
                    if (forceX * forceX + forceY * forceY > 1e-10) { // 1e-5^2、比較のみで厳密同値
                        escapeAngle = Math.atan2(forceY, forceX);
                    }
                    
                    r.stallRecoveryAngle = escapeAngle + (r.getRandom().nextDouble() - 0.5) * Math.PI * 0.25;
                    inRecovery = true;
                } else {
                    r.stallCount = 0; // 進めていれば連続カウントをリセット
                }
                r.stallCheckTimer = 0.0;
                r.stallCheckX = r.x;
                r.stallCheckY = r.y;
            }
            if (inRecovery) {
                moveDirX = Math.cos(r.stallRecoveryAngle);
                moveDirY = Math.sin(r.stallRecoveryAngle);
            }

            // --- 運動指令生成 (実行層) ---
            double cmdV = 0.0;
            double cmdVy = 0.0;
            double cmdOmega = 0.0;

            if (engine.isTimerStarted() && !engine.isTimeLimitReached()) {
                double distToTarget = toTargetDist;
                double angleDiff = normalizeAngle(targetHeading - r.heading);

                if (r.getDriveType() == CompetitionRobot.DriveType.OMNI) {
                    double error = angleDiff;
                    double errorDot = 0.0;
                    if (Double.isNaN(r.prevAngleError) || dt < 1e-9) {
                        errorDot = 0.0;
                    } else {
                        errorDot = normalizeAngle(error - r.prevAngleError) / dt;
                    }
                    r.prevAngleError = error;

                    double diffKp = 5.0; // OMNI Kp
                    double diffKd = 1.0; // OMNI Kd
                    
                    if (Math.abs(error) < DIFF_ANGLE_DEADBAND) {
                        cmdOmega = 0.0;
                    } else {
                        cmdOmega = Math.max(-r.getConfig().maxOmega, Math.min(r.getConfig().maxOmega, error * diffKp + errorDot * diffKd));
                    }

                    if (distToTarget > 0.05) {
                        double kp = 3.0;
                        double targetSpeed = Math.min(r.getConfig().maxSpeed, distToTarget * kp);
                        double globalVx = moveDirX * targetSpeed;
                        double globalVy = moveDirY * targetSpeed;
                        double cosH = Math.cos(r.heading);
                        double sinH = Math.sin(r.heading);
                        cmdV = globalVx * cosH + globalVy * sinH;
                        cmdVy = -globalVx * sinH + globalVy * cosH;
                        double speed = Math.hypot(cmdV, cmdVy);
                        if (speed > r.getConfig().maxSpeed) {
                            cmdV = (cmdV / speed) * r.getConfig().maxSpeed;
                            cmdVy = (cmdVy / speed) * r.getConfig().maxSpeed;
                        }
                    }
                } else { // 差動2輪
                    // heading目標：純目標方向70%＋分散込み移動方向30%でブレンドし、分散振動の影響を抑制
                    double tLen = Math.max(1e-6, toTargetDist);
                    double headDirX = DIFF_HEAD_PURE_BLEND * (moveToX - r.x) / tLen + (1.0 - DIFF_HEAD_PURE_BLEND) * moveDirX;
                    double headDirY = DIFF_HEAD_PURE_BLEND * (moveToY - r.y) / tLen + (1.0 - DIFF_HEAD_PURE_BLEND) * moveDirY;
                    double headLen  = dist(headDirX, headDirY);
                    double moveHeading = (distToTarget < 0.15) ? targetHeading
                        : (headLen > 1e-6 ? Math.atan2(headDirY / headLen, headDirX / headLen) : r.heading);

                    // 膠着脱出中は目標方向ブレンドを無視し、純粋に脱出方向を移動方向とする
                    // （これにより脱出角度が後方なら確実に後退判定に入る）
                    if (inRecovery) {
                        moveHeading = Math.atan2(moveDirY, moveDirX);
                    }

                    // 改善2: Uターン回収（差動2輪、遠距離）- moveHeadingをゴール方向へ弧状にブレンド
                    if (!inRecovery && "COLLECT".equals(chosenAction) && r.heldBallCount > 0 && distToTarget > 0.15) {
                        double approxMinHoldD = MIN_SHOOT_HOLD_RATIO * r.getIntakeCapacity();
                        double blendProgressD = Math.min(1.0, Math.max(0.0,
                            (r.heldBallCount - approxMinHoldD * 0.4) / Math.max(0.5, approxMinHoldD * 0.6)));
                        double goalLeanD = blendProgressD * 0.25;
                        if (goalLeanD > 0.02) {
                            boolean rIsBlueD = r.getAlliance() == CompetitionRobot.Alliance.BLUE;
                            double approxGoalXD = rIsBlueD ? 5.0 : 2.0;
                            double approxGoalYD = 1.0;
                            double goalAngleD = Math.atan2(approxGoalYD - r.y, approxGoalXD - r.x);
                            moveHeading = normalizeAngle(moveHeading + normalizeAngle(goalAngleD - moveHeading) * goalLeanD);
                        }
                    }

                    double moveAngleDiff = normalizeAngle(moveHeading - r.heading);
                    double absAngle = Math.abs(moveAngleDiff);

                    // 膠着脱出（inRecovery）中で、かつ目標への角度差が90度を超える場合は旋回せずに後退
                    boolean doBackup = inRecovery && (absAngle > Math.PI * 0.5);
                    r.dbgAbsAngle = absAngle; r.dbgDoBackup = doBackup; r.dbgInRecovery = inRecovery; r.dbgDistToTarget = distToTarget;
                    if (doBackup) {
                        moveHeading = r.heading;
                        moveAngleDiff = 0.0;
                        absAngle = 0.0;
                    }

                    double error = moveAngleDiff;
                    double errorDot = 0.0;
                    if (Double.isNaN(r.prevAngleError) || dt < 1e-9) {
                        errorDot = 0.0;
                    } else {
                        errorDot = normalizeAngle(error - r.prevAngleError) / dt;
                    }
                    r.prevAngleError = error;

                    // デッドバンド内は旋回停止（首振り防止）、それ以外はPD制御
                    if (absAngle < DIFF_ANGLE_DEADBAND) {
                        cmdOmega = 0.0;
                    } else {
                        cmdOmega = Math.max(-r.getConfig().maxOmega,
                                   Math.min( r.getConfig().maxOmega, error * DIFF_KP_OMEGA + errorDot * DIFF_KD_OMEGA));
                    }

                    // 囲まれ状態（周辺ボールカウント）の再判定
                    boolean isSurrounded = false;
                    if ("COLLECT".equals(chosenAction)) {
                        int nearbyCount = 0;
                        // 半径0.8m近傍を空間グリッドで粗選別＋正確距離フィルタ（件数のみ＝順序非依存で全走査と一致）。
                        int nbc = collectBallNeighbors(r.x, r.y);
                        for (int ni = 0; ni < nbc; ni++) {
                            core.BallState ball = balls.get(ballNeighborScratch[ni]);
                            if (ball.collected || ball.scored) continue;
                            // 比較のみ（<= 0.8）のため2乗距離化（厳密同値）
                            double bdx = ball.x - r.x, bdy = ball.y - r.y;
                            if (bdx * bdx + bdy * bdy <= 0.64) {
                                nearbyCount++;
                            }
                        }
                        if (nearbyCount >= 2) {
                            isSurrounded = true;
                        }
                    }

                    if (doBackup) {
                        // 旋回せず直進後退
                        cmdV = -Math.min(r.getConfig().maxSpeed * 0.6, distToTarget * 3.0);
                    } else {
                        // 角度差が閾値以内なら段階的に前進（完全正対を待たない。囲まれ時は前進を最大化）
                        double forwardAngleLimit = isSurrounded ? (Math.PI / 2.0) : DIFF_FORWARD_ANGLE;
                        if (absAngle < forwardAngleLimit && distToTarget > 0.05) {
                            double alignFactor = isSurrounded ? 1.0 : (1.0 - absAngle / forwardAngleLimit);
                            cmdV = Math.min(r.getConfig().maxSpeed, distToTarget * 3.0) * alignFactor;
                        }
                    }
                }
            }

            // WHITE後退タイマー発動中は後退する（同じ白線への再接近を促す）
            if ("WHITE".equals(chosenAction) && r.ejectBackupTimer > 0.0) {
                cmdV = -r.getConfig().maxSpeed * 0.5;
                cmdOmega = 0.0;
            } else if (r.getDriveType() == CompetitionRobot.DriveType.DIFF && cmdV > 0) {
                // NPC壁・障害物整列（プレイヤーと同仕様の接触角ピボット整列）
                if (!r.isPlayer()) {
                    alignToWallAndObstacle(r, cmdV, obstacles, dt);
                }
                // 壁または障害物に接触しつつ前進しようとしている場合は即座に後退して旋回
                double cosH_w = Math.cos(r.heading);
                double sinH_w = Math.sin(r.heading);
                double half_w = r.getConfig().robotSize / 2.0 * (Math.abs(cosH_w) + Math.abs(sinH_w));
                boolean pushingWall = false;

                // 壁接触＋前進チェック
                double wallMargin = 0.02;
                if (r.x < half_w + wallMargin && cosH_w < 0) pushingWall = true;
                if (r.x > 7.0 - half_w - wallMargin && cosH_w > 0) pushingWall = true;
                if (r.y < half_w + wallMargin && sinH_w < 0) pushingWall = true;
                if (r.y > 7.0 - half_w - wallMargin && sinH_w > 0) pushingWall = true;

                // 障害物接触＋前進チェック
                if (!pushingWall) {
                    double halfSize = r.getConfig().robotSize / 2.0;
                    boolean isLeftWhite = "WHITE".equals(chosenAction) && (targetX < 3.5);
                    boolean isRightWhite = "WHITE".equals(chosenAction) && (targetX >= 3.5);
                    for (FieldObstacle obs : obstacles) {
                        if ("tape".equals(obs.category())) continue;
                        // WHITEアクション中のターゲット Fire Shield への接近は除外（意図的な接近）
                        if (isLeftWhite  && "fire_shield_left".equals(obs.id()))  continue;
                        if (isRightWhite && "fire_shield_right".equals(obs.id())) continue;
                        CollisionResult res = obs.collideOBB(r.x, r.y, r.heading, halfSize);
                        if (res != null) {
                            double mLen = dist(res.mtvX, res.mtvY);
                            if (mLen > 1e-6) {
                                // MTV逆方向 = 障害物方向。前進方向との内積 > 閾値 → 障害物に向かって前進中
                                double obsNx = -res.mtvX / mLen;
                                double obsNy = -res.mtvY / mLen;
                                if (cosH_w * obsNx + sinH_w * obsNy > 0.3) {
                                    pushingWall = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (pushingWall) {
                    // 改善1: COLLECT中でインテーク範囲にボールがある場合は後退せず停止（壁際回収対応）
                    boolean suppressBackup = false;
                    if ("COLLECT".equals(chosenAction) && r.collectTargetBallId >= 0) {
                        for (core.BallState cb : balls) {
                            if (cb.id != r.collectTargetBallId) continue;
                            if (cb.collected || cb.scored) break;
                            double intakeReach = r.getConfig().robotSize + r.getConfig().intakeFrontDepth + 0.10;
                            // 比較のみ（< intakeReach）のため2乗距離化（厳密同値）
                            double cbdx = cb.x - r.x, cbdy = cb.y - r.y;
                            if (cbdx * cbdx + cbdy * cbdy < intakeReach * intakeReach) {
                                suppressBackup = true;
                            }
                            break;
                        }
                    }
                    if (!suppressBackup) {
                        cmdV = -r.getConfig().maxSpeed * 0.6;
                    }
                    cmdOmega = Math.max(-r.getConfig().maxOmega,
                               Math.min( r.getConfig().maxOmega,
                                   normalizeAngle(targetHeading - r.heading) * DIFF_KP_OMEGA));
                }
            }

            r.dbgCmdV = cmdV;
            r.integrate(cmdV, cmdVy, cmdOmega, dt);
            r.dbgDistToTarget = r.v; // 一時: integrate直後の v を記録

            ballSim.processIntake(r.getId(), r.getOBB(), r.intakeActive, r.getIntakeCapacity());
            // 改善（タスクB-3）: シュート成功率の動的化。
            // 固定の shootSuccessProb を、目標までの距離・射線角度・遮蔽・
            // ゴール前混雑に応じて補正した実効成功率に置き換える。
            double dynShootProb = computeDynamicShootProb(r, goalPosX, goalPosY, obstacles);
            ballSim.processShoot(r.getId(), r.getOBB(), r.shootActive, r.getAlliance() == CompetitionRobot.Alliance.BLUE,
                                 r.getShootRange(), r.getShootBPS(), dynShootProb, r.getConfig().shootMissRadius,
                                 r.collisionFlag, dt);
            ballSim.processEject(r.getId(), r.getOBB(), r.ejectActive, dt);

            r.heldBallCount = ballSim.getHeldBallCount(r.getId());
            r.scoreCount = ballSim.getScoreCount(r.getId());
            r.shotAttemptCount = ballSim.getShotAttemptCount(r.getId());
            r.whiteScoreCount = ballSim.getWhiteScoreCount(r.getId());
        }
    }

    @Override
    public void resolveCollisions(SimulationEngine engine, double dt) {
        int n = robots.size();
        List<FieldObstacle> obstacles = engine.getFieldObstacles();

        // 各フラグをリセット
        for (CompetitionRobot r : robots) {
            r.collisionFlag = false;
            r.obstacleCollisionFlag = false;
            r.hitWallFlag = false;
        }

        // 1. ロボットとフィールド障害物の衝突解決 (3回反復)
        for (int iter = 0; iter < 3; iter++) {
            for (CompetitionRobot r : robots) {
                double halfSize = r.getConfig().robotSize / 2.0;
                double accumulatedDx = 0.0;
                double accumulatedDy = 0.0;
                boolean hitObs = false;
                String hitObsId = null;
                double obsCX = 0.0, obsCY = 0.0;

                for (FieldObstacle obs : obstacles) {
                    if ("tape".equals(obs.category())) continue; // スタートテープは除外

                    CollisionResult res = obs.collideOBB(r.x, r.y, r.heading, halfSize);
                    if (res != null) {
                        hitObs = true;
                        hitObsId = obs.id();
                        obsCX = (res.contactPoint != null && res.contactPoint.length > 0) ? res.contactPoint[0] : 0.0;
                        obsCY = (res.contactPoint != null && res.contactPoint.length > 1) ? res.contactPoint[1] : 0.0;

                        // ロボットを押し戻す
                        r.x += res.mtvX;
                        r.y += res.mtvY;
                        accumulatedDx += res.mtvX;
                        accumulatedDy += res.mtvY;
                    }
                }

                if (hitObs) {
                    r.obstacleCollisionFlag = true;
                    r.obstacleCollisionId = hitObsId;
                    r.clampVelocity(accumulatedDx, accumulatedDy);

                    // 車軸ポーズ再計算
                    double cosH = Math.cos(r.heading);
                    double sinH = Math.sin(r.heading);
                    if (r.getDriveType() == CompetitionRobot.DriveType.OMNI) {
                        r.xA = r.x;
                        r.yA = r.y;
                    } else {
                        r.xA = r.x + r.getConfig().pivotOffsetD * cosH;
                        r.yA = r.y + r.getConfig().pivotOffsetD * sinH;
                    }
                    r.clampToFieldBoundaries();

                    // プレイヤーの障害物衝突統計を更新
                    if (r.isPlayer()) {
                        engine.updateObstacleCollisionState(true, hitObsId, obsCX, obsCY, dt);
                    }
                }
            }
        }

        // 2. ロボット同士の衝突判定および押し戻し
        for (int i = 0; i < n; i++) {
            CompetitionRobot rA = robots.get(i);
            RobotOBB obbA = rA.getOBB();

            for (int j = i + 1; j < n; j++) {
                CompetitionRobot rB = robots.get(j);
                RobotOBB obbB = rB.getOBB();

                PracticeManager.CollisionResult res = PracticeManager.checkObbCollision(
                    obbA.cx, obbA.cy, obbA.heading, rA.getConfig().robotSize,
                    obbB.cx, obbB.cy, obbB.heading, rB.getConfig().robotSize
                );

                if (res.collided) {
                    // 衝突応答：MTV（最小押し出しベクトル）方向にロボットを押し戻す
                    double ratioA = 0.5;
                    double ratioB = 0.5;

                    if (rA.isPlayer()) {
                        ratioA = rA.getConfig().playerPushRatio;
                        ratioB = 1.0 - ratioA;
                    } else if (rB.isPlayer()) {
                        ratioB = rB.getConfig().playerPushRatio;
                        ratioA = 1.0 - ratioB;
                    }

                    // Aを押し戻す (ratioA分)
                    rA.overridePosition(rA.x + res.mtvX * ratioA, rA.y + res.mtvY * ratioA, res.mtvX, res.mtvY);
                    // Bを押し戻す (ratioB分、逆方向)
                    rB.overridePosition(rB.x - res.mtvX * ratioB, rB.y - res.mtvY * ratioB, -res.mtvX, -res.mtvY);

                    rA.collisionFlag = true;
                    rB.collisionFlag = true;

                    // プレイヤーの衝突統計更新（YOUが衝突に関与した場合）
                    if (rA.isPlayer() || rB.isPlayer()) {
                        int collidedNpcId = rA.isPlayer() ? rB.getId() : rA.getId();
                        engine.updateCollisionState(true, collidedNpcId, res.cPointX, res.cPointY, 
                            engine.getCurrentState().x, engine.getCurrentState().y,
                            engine.getCurrentState().xA, engine.getCurrentState().yA,
                            res.mtvX, res.mtvY, dt);
                    }
                }
            }
        }
    }

    @Override
    public List<RobotOBB> getNpcOBBs() {
        List<RobotOBB> obbs = new ArrayList<>();
        for (CompetitionRobot r : robots) {
            if (!r.isPlayer()) {
                obbs.add(r.getOBB());
            }
        }
        return obbs;
    }

    /**
     * プレイヤーのロボットインスタンスを取得します。
     */
    public CompetitionRobot getPlayerRobot() {
        for (CompetitionRobot r : robots) {
            if (r.isPlayer()) return r;
        }
        return null;
    }

    /** プレイヤー個人の赤青ゴール得点 */
    public int getPlayerScore() {
        for (CompetitionRobot r : robots) {
            if (r.isPlayer()) return r.scoreCount;
        }
        return 0;
    }

    /** プレイヤーのアライアンス合計（プレイヤー自身を含む3台）の赤青ゴール得点 */
    public int getAllyTeamScore() {
        CompetitionRobot.Alliance pa = CompetitionRobot.Alliance.RED;
        for (CompetitionRobot r : robots) {
            if (r.isPlayer()) { pa = r.getAlliance(); break; }
        }
        int total = 0;
        for (CompetitionRobot r : robots) {
            if (r.getAlliance() == pa) total += r.scoreCount;
        }
        return total;
    }

    /** 敵アライアンス3台の赤青ゴール合計得点 */
    public int getEnemyTeamScore() {
        CompetitionRobot.Alliance pa = CompetitionRobot.Alliance.RED;
        for (CompetitionRobot r : robots) {
            if (r.isPlayer()) { pa = r.getAlliance(); break; }
        }
        int total = 0;
        for (CompetitionRobot r : robots) {
            if (r.getAlliance() != pa) total += r.scoreCount;
        }
        return total;
    }

    /** 全ロボット合算の白ゴール得点 */
    public int getTotalWhiteScore() {
        int total = 0;
        for (CompetitionRobot r : robots) total += r.whiteScoreCount;
        return total;
    }

    private boolean isRayBlocked(double px, double py, double sx, double sy, List<FieldObstacle> obstacles) {
        if (obstacles == null) return false;
        // 遅延キャッシュ構築（フィールド障害物は静的＝同一リスト参照のまま不変）。
        // リスト参照が変わった場合のみ再構築して安全側に倒す。
        if (!rayCacheBuilt || rayCacheSource != obstacles) {
            buildRayCache(obstacles);
        }
        // レイ（線分 px,py - sx,sy）のAABB。エッジAABBと重ならないエッジは交差し得ないため早期棄却。
        double rayMinX = (px < sx) ? px : sx;
        double rayMaxX = (px < sx) ? sx : px;
        double rayMinY = (py < sy) ? py : sy;
        double rayMaxY = (py < sy) ? sy : py;
        final int n = edgeCount;
        final double[] eX1 = edgeX1, eY1 = edgeY1, eX2 = edgeX2, eY2 = edgeY2;
        final double[] eMinX = edgeMinX, eMaxX = edgeMaxX, eMinY = edgeMinY, eMaxY = edgeMaxY;
        for (int k = 0; k < n; k++) {
            // AABB事前棄却（レイAABBとエッジAABBが分離していれば交差不能）
            if (rayMaxX < eMinX[k] || rayMinX > eMaxX[k]
                || rayMaxY < eMinY[k] || rayMinY > eMaxY[k]) {
                continue;
            }
            double vx1 = eX1[k], vy1 = eY1[k];
            double vx2 = eX2[k], vy2 = eY2[k];

            // 狙い点 (sx, sy) がこの線分上にある場合は、標的そのものなので遮蔽物とみなさない
            double cp = (vx2 - vx1) * (sy - vy1) - (vy2 - vy1) * (sx - vx1);
            if (cp < 0) cp = -cp;
            if (cp < 1e-5) {
                double minX = eMinX[k];
                double maxX = eMaxX[k];
                double minY = eMinY[k];
                double maxY = eMaxY[k];
                if (sx >= minX - 1e-5 && sx <= maxX + 1e-5 && sy >= minY - 1e-5 && sy <= maxY + 1e-5) {
                    continue;
                }
            }

            if (lineSegmentsIntersect(px, py, sx, sy, vx1, vy1, vx2, vy2)) {
                return true;
            }
        }
        return false;
    }

    private boolean lineSegmentsIntersect(double ax, double ay, double bx, double by,
                                          double cx, double cy, double dx, double dy) {
        double cp1 = crossProduct(ax, ay, bx, by, cx, cy);
        double cp2 = crossProduct(ax, ay, bx, by, dx, dy);
        double cp3 = crossProduct(cx, cy, dx, dy, ax, ay);
        double cp4 = crossProduct(cx, cy, dx, dy, bx, by);
        
        return ((cp1 * cp2 < 0) && (cp3 * cp4 < 0));
    }

    private double crossProduct(double x1, double y1, double x2, double y2, double x3, double y3) {
        return (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
    }

    /**
     * NPC用壁・障害物接触角ピボット整列（RobotPhysicsの壁整列と同仕様）。
     * 接触コーナー／接触点を支点にheadingを壁・障害物に沿う方向へ補正します。
     */
    private void alignToWallAndObstacle(CompetitionRobot r, double cmdV,
                                         List<FieldObstacle> obstacles, double dt) {
        core.RobotConfig config = r.getConfig();
        double L = config.robotSize;
        double halfSize = L / 2.0;
        double cosH = Math.cos(r.heading);
        double sinH = Math.sin(r.heading);

        double[][] localCorners = {
            {-L/2, -L/2}, {-L/2, L/2}, {L/2, L/2}, {L/2, -L/2}
        };

        // 壁への最大貫通量を探索（クランプ済みのため -0.01 以上を有効とする）
        double maxWallDelta = -Double.MAX_VALUE;
        int maxWallCornerIdx = -1;
        int maxWallIdx = -1; // 0:左, 1:右, 2:下, 3:上

        for (int i = 0; i < 4; i++) {
            double cx = r.x + localCorners[i][0] * cosH - localCorners[i][1] * sinH;
            double cy = r.y + localCorners[i][0] * sinH + localCorners[i][1] * cosH;

            double dLeft = 0.0 - cx;
            if (dLeft > maxWallDelta) { maxWallDelta = dLeft; maxWallCornerIdx = i; maxWallIdx = 0; }
            double dRight = cx - 7.0;
            if (dRight > maxWallDelta) { maxWallDelta = dRight; maxWallCornerIdx = i; maxWallIdx = 1; }
            double dBottom = 0.0 - cy;
            if (dBottom > maxWallDelta) { maxWallDelta = dBottom; maxWallCornerIdx = i; maxWallIdx = 2; }
            double dTop = cy - 7.0;
            if (dTop > maxWallDelta) { maxWallDelta = dTop; maxWallCornerIdx = i; maxWallIdx = 3; }
        }

        // 障害物への最大貫通量を探索
        double maxObsDelta = -Double.MAX_VALUE;
        core.CollisionResult maxObsResult = null;

        if (obstacles != null) {
            for (core.FieldObstacle obs : obstacles) {
                if ("tape".equals(obs.category())) continue;
                core.CollisionResult res = obs.collideOBB(r.x, r.y, r.heading, halfSize);
                if (res != null && res.depth > maxObsDelta) {
                    maxObsDelta = res.depth;
                    maxObsResult = res;
                }
            }
        }

        boolean preferWall = maxWallDelta >= maxObsDelta && maxWallDelta > -0.01;
        boolean preferObs  = !preferWall && maxObsDelta > 0.0;

        double driveDir = Math.signum(cmdV);
        if (driveDir == 0.0) driveDir = 1.0;

        if (preferWall) {
            double h_push_dot_n_out = 0.0;
            if      (maxWallIdx == 0) h_push_dot_n_out = driveDir * (-cosH);
            else if (maxWallIdx == 1) h_push_dot_n_out = driveDir * ( cosH);
            else if (maxWallIdx == 2) h_push_dot_n_out = driveDir * (-sinH);
            else if (maxWallIdx == 3) h_push_dot_n_out = driveDir * ( sinH);

            if (h_push_dot_n_out > 0.0) {
                double target = Math.round(r.heading / (Math.PI / 2.0)) * (Math.PI / 2.0);
                double diffTheta = normalizeAngle(target - r.heading);
                double dphi = config.k_align * diffTheta * h_push_dot_n_out;
                double limit = config.omegaAlignMax * dt;
                dphi = Math.max(-limit, Math.min(limit, dphi));

                if (Math.abs(dphi) > 1e-9) {
                    double cornerX = r.x + localCorners[maxWallCornerIdx][0] * cosH - localCorners[maxWallCornerIdx][1] * sinH;
                    double cornerY = r.y + localCorners[maxWallCornerIdx][0] * sinH + localCorners[maxWallCornerIdx][1] * cosH;
                    double dx = r.x - cornerX, dy = r.y - cornerY;
                    double cosRot = Math.cos(dphi), sinRot = Math.sin(dphi);
                    r.x = cornerX + dx * cosRot - dy * sinRot;
                    r.y = cornerY + dx * sinRot + dy * cosRot;
                    r.heading = normalizeAngle(r.heading + dphi);
                    double cn = Math.cos(r.heading), sn = Math.sin(r.heading);
                    r.xA = r.x + config.pivotOffsetD * cn;
                    r.yA = r.y + config.pivotOffsetD * sn;
                    r.omega += dphi / dt;
                }
            }
        } else if (preferObs && maxObsResult != null && maxObsResult.isEdgeOrigin) {
            double h_push_x = driveDir * cosH;
            double h_push_y = driveDir * sinH;
            double push = -(h_push_x * maxObsResult.mtvX + h_push_y * maxObsResult.mtvY) / maxObsResult.depth;

            if (push > 0.0) {
                double phi_edge = maxObsResult.edgeAngle;
                double minDiff = Double.MAX_VALUE;
                double targetAngle = phi_edge;
                double[] candidates = {phi_edge, phi_edge - Math.PI/2, phi_edge + Math.PI/2, phi_edge + Math.PI};
                for (double cand : candidates) {
                    double diff = Math.abs(normalizeAngle(cand - r.heading));
                    if (diff < minDiff) { minDiff = diff; targetAngle = cand; }
                }

                double diffTheta = normalizeAngle(targetAngle - r.heading);
                double dphi = config.k_align * diffTheta * push;
                double limit = config.omegaAlignMax * dt;
                dphi = Math.max(-limit, Math.min(limit, dphi));

                if (Math.abs(dphi) > 1e-9) {
                    double cx = maxObsResult.contactPoint[0];
                    double cy = maxObsResult.contactPoint[1];
                    double dx = r.x - cx, dy = r.y - cy;
                    double cosRot = Math.cos(dphi), sinRot = Math.sin(dphi);
                    r.x = cx + dx * cosRot - dy * sinRot;
                    r.y = cy + dx * sinRot + dy * cosRot;
                    r.heading = normalizeAngle(r.heading + dphi);
                    double cn = Math.cos(r.heading), sn = Math.sin(r.heading);
                    r.xA = r.x + config.pivotOffsetD * cn;
                    r.yA = r.y + config.pivotOffsetD * sn;
                    r.omega += dphi / dt;
                }
            }
        }

        r.clampToFieldBoundaries();
    }

    /**
     * 経路計画: 現在地→目標地点への直線が障害物に塞がれている場合、
     * 障害物コーナーを経由する迂回ウェイポイントを返します。
     * 直線で行けるなら null、迂回が必要なら [x, y] を返します。
     */
    private double[] computeMovementWaypoint(double rx, double ry, double tx, double ty, List<FieldObstacle> obstacles) {
        if (obstacles == null || !isRayBlocked(rx, ry, tx, ty, obstacles)) {
            return null;
        }
        // 1. まず全コーナー候補点を列挙
        List<double[]> corners = collectCornerCandidates(obstacles);

        // 2. 単一ウェイポイント（ロボット→候補→目標が両方通れる）の最良を探す
        double bestX = Double.NaN, bestY = Double.NaN;
        double bestCost = Double.MAX_VALUE;
        for (double[] c : corners) {
            double cx = c[0], cy = c[1];
            if (!isRayBlocked(rx, ry, cx, cy, obstacles) &&
                !isRayBlocked(cx, cy, tx, ty, obstacles)) {
                double cost = dist(cx - rx, cy - ry) + dist(tx - cx, ty - cy);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = cx;
                    bestY = cy;
                }
            }
        }
        if (!Double.isNaN(bestX)) {
            return new double[]{bestX, bestY};
        }

        // 3. 改善（タスクB-2）: 単一点で抜けられない密な局面では2ウェイポイント経路を探索。
        //    ロボットから到達可能な第1コーナー(w1) と、そこから目標へ抜けられる第2コーナー(w2)
        //    の組を探し、全長最小の経路の「次に向かうべき点 w1」を返す。
        double best2Cost = Double.MAX_VALUE;
        double best2X = Double.NaN, best2Y = Double.NaN;
        for (double[] c1 : corners) {
            double w1x = c1[0], w1y = c1[1];
            // ロボットから到達できる第1経由点のみ
            if (isRayBlocked(rx, ry, w1x, w1y, obstacles)) continue;
            double leg1 = dist(w1x - rx, w1y - ry);
            for (double[] c2 : corners) {
                double w2x = c2[0], w2y = c2[1];
                if (w2x == w1x && w2y == w1y) continue;
                // w1→w2 と w2→目標 が両方通れること
                if (isRayBlocked(w1x, w1y, w2x, w2y, obstacles)) continue;
                if (isRayBlocked(w2x, w2y, tx, ty, obstacles)) continue;
                double cost = leg1 + dist(w2x - w1x, w2y - w1y)
                            + dist(tx - w2x, ty - w2y);
                if (cost < best2Cost) {
                    best2Cost = cost;
                    best2X = w1x;
                    best2Y = w1y;
                }
            }
        }
        if (!Double.isNaN(best2X)) {
            return new double[]{best2X, best2Y};
        }

        // 4. それでも解が無ければ、せめてロボットから到達可能で目標に最も近い経由点を返す
        //    （簡易回避ステアリングの足がかり。完全停止より前進を促す）
        double fbCost = Double.MAX_VALUE;
        double fbX = Double.NaN, fbY = Double.NaN;
        for (double[] c : corners) {
            if (isRayBlocked(rx, ry, c[0], c[1], obstacles)) continue;
            double toTarget = dist(tx - c[0], ty - c[1]);
            if (toTarget < fbCost) {
                fbCost = toTarget;
                fbX = c[0];
                fbY = c[1];
            }
        }
        return Double.isNaN(fbX) ? null : new double[]{fbX, fbY};
    }

    /** 全障害物のコーナー外側クリアランス点を列挙する（経路計画の候補点）。 */
    private List<double[]> collectCornerCandidates(List<FieldObstacle> obstacles) {
        double clearance = 0.35; // コーナー通過時のクリアランス（ロボット半径0.25m＋マージン）
        List<double[]> out = new ArrayList<>();
        for (FieldObstacle obs : obstacles) {
            if ("tape".equals(obs.category()) || "brace".equals(obs.category())) continue;
            double[][] verts = obs.outlinePolygon();
            if (verts == null || verts.length < 2) continue;
            int numV = verts.length;
            for (int vi = 0; vi < numV; vi++) {
                double vx = verts[vi][0], vy = verts[vi][1];
                int prev = (vi - 1 + numV) % numV;
                int next = (vi + 1) % numV;
                double e1x = verts[vi][0] - verts[prev][0], e1y = verts[vi][1] - verts[prev][1];
                double e2x = verts[next][0] - verts[vi][0], e2y = verts[next][1] - verts[vi][1];
                double n1x = e1y, n1y = -e1x, n2x = e2y, n2y = -e2x;
                double nl1 = dist(n1x, n1y), nl2 = dist(n2x, n2y);
                double outNx = (nl1 > 1e-6 ? n1x / nl1 : 0) + (nl2 > 1e-6 ? n2x / nl2 : 0);
                double outNy = (nl1 > 1e-6 ? n1y / nl1 : 0) + (nl2 > 1e-6 ? n2y / nl2 : 0);
                double outLen = dist(outNx, outNy);
                if (outLen < 1e-6) continue;
                for (int sign : new int[]{1, -1}) {
                    double cx = vx + sign * (outNx / outLen) * clearance;
                    double cy = vy + sign * (outNy / outLen) * clearance;
                    cx = Math.max(0.3, Math.min(6.7, cx));
                    cy = Math.max(0.3, Math.min(6.7, cy));
                    out.add(new double[]{cx, cy});
                }
            }
        }
        return out;
    }
}
