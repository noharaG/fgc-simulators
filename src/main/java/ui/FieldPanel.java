package ui;

import core.RobotConfig;
import core.RobotState;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

/**
 * 7m x 7m のフィールドとロボット、および走行軌跡を描画するパネル。
 * 幾何モデルの修正に基づき、車軸 A を回転中心とし、ボディ中心 G をオフセット描画します。
 */
public class FieldPanel extends JPanel {

    private static final double FIELD_SIZE = 7.0; // フィールド一辺のサイズ (m)
    private static final int PADDING = 30;         // パネル縁の余白 (px)

    // ----------------------------------------------------------------
    // 定数: Font / Color / Stroke  (項目5)
    // ----------------------------------------------------------------
    private static final Font   FONT_HUD_14      = new Font("MS Gothic", Font.BOLD, 14);
    private static final Font   FONT_HUD_12      = new Font("MS Gothic", Font.BOLD, 12);
    private static final Font   FONT_HUD_13      = new Font("MS Gothic", Font.BOLD, 13);
    private static final Color  COLOR_SHOOT_READY   = new Color(50, 255, 50);
    private static final Color  COLOR_TRAIL_NORMAL  = new Color(80, 180, 255);
    private static final Color  COLOR_TRAIL_SLIP    = new Color(255, 80, 80);
    private static final Color  COLOR_FIELD_FILL    = new Color(33, 37, 43);
    private static final Color  COLOR_GRID_BORDER   = new Color(100, 110, 120);
    private static final Color  COLOR_GRID_CENTER   = new Color(75, 85, 95);
    private static final Color  COLOR_GRID_MINOR    = new Color(45, 50, 60);
    private static final Color  COLOR_FIELD_OUTLINE = new Color(130, 140, 150);
    private static final Color  COLOR_OBS_SUP_FILL  = new Color(77, 128, 77, 140);
    private static final Color  COLOR_OBS_SUP_LINE  = new Color(77, 128, 77, 220);
    private static final Color  COLOR_OBS_FIRE_FILL = new Color(204, 77, 77, 130);
    private static final Color  COLOR_OBS_FIRE_LINE = new Color(204, 77, 77, 210);
    private static final Color  COLOR_OBS_BRACE_FILL= new Color(77, 166, 204, 130);
    private static final Color  COLOR_OBS_BRACE_LINE= new Color(77, 166, 204, 210);
    private static final Color  COLOR_GOAL_RED      = new Color(255, 50, 50, 220);
    private static final Color  COLOR_GOAL_GREY     = new Color(150, 150, 150, 180);
    private static final Color  COLOR_GOAL_BLUE     = new Color(0, 140, 255, 220);
    private static final Color  COLOR_ROBOT_ALARM   = new Color(255, 60, 60, 240);
    private static final Color  COLOR_ROBOT_NORMAL  = new Color(97, 218, 251, 200);
    private static final Color  COLOR_SCORE_HUD_BG  = new Color(0, 0, 0, 140);
    private static final Color  COLOR_SCORE_SELF    = new Color(239, 159, 39);
    private static final Color  COLOR_SCORE_DIVIDER = new Color(160, 160, 160);
    private static final Color  COLOR_SCORE_ALLY_BLUE  = new Color(100, 180, 255);
    private static final Color  COLOR_SCORE_ALLY_RED   = new Color(255, 120, 120);
    private static final Color  COLOR_SCORE_WHITE   = new Color(200, 200, 200);
    private static final Color  COLOR_WHEEL         = new Color(50, 50, 50);
    private static final Color  COLOR_AXLE          = new Color(100, 100, 100);
    private static final Color  COLOR_ROBOT_ORANGE  = new Color(255, 165, 0, 180);
    private static final Color  COLOR_INTAKE_FILL   = new Color(97, 218, 251, 80);
    private static final Color  COLOR_INTAKE_LINE   = new Color(97, 218, 251, 150);
    private static final Color  COLOR_FILL_COLLISION= new Color(220, 50, 50, 130);
    private static final Color  COLOR_FILL_SLIP     = new Color(120, 30, 30, 100);
    private static final Color  COLOR_FILL_WALL     = new Color(240, 180, 50, 110);
    private static final Color  COLOR_FILL_NORMAL   = new Color(30, 60, 90, 150);
    private static final Color  COLOR_BEAM_RED      = new Color(255, 50, 50, 220);
    private static final Color  COLOR_BEAM_BLUE     = new Color(0, 191, 255, 220);
    private static final Color  COLOR_GUIDE_GREEN   = new Color(50, 255, 50, 200);
    private static final Color  COLOR_GUIDE_BLUE    = new Color(0, 191, 255, 160);
    private static final Color  COLOR_GUIDE_RED_DIM = new Color(255, 80, 80, 160);
    private static final Color  COLOR_GUIDE_GREY    = new Color(200, 200, 200, 80);
    private static final Color  COLOR_TRAIL_GHOST   = new Color(255, 255, 255, 40);
    private static final Color  COLOR_BALL          = new Color(216, 90, 48);
    private static final Color  COLOR_FS_HIGHLIGHT  = Color.WHITE;
    private static final BasicStroke STROKE_1F      = new BasicStroke(1.0f);
    private static final BasicStroke STROKE_15F     = new BasicStroke(1.5f);
    private static final BasicStroke STROKE_2F      = new BasicStroke(2.0f);
    private static final BasicStroke STROKE_25F     = new BasicStroke(2.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_3F      = new BasicStroke(3.0f);
    private static final BasicStroke STROKE_4F      = new BasicStroke(4.0f);
    private static final BasicStroke STROKE_45F     = new BasicStroke(4.5f);
    private static final BasicStroke STROKE_4F_HL   = new BasicStroke(4.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_5F      = new BasicStroke(5.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_6F      = new BasicStroke(6.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_2F_ROUND= new BasicStroke(2.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_15F_ROUND = new BasicStroke(1.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_3F_DASH = new BasicStroke(3.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0);
    private static final BasicStroke STROKE_2F_DASH = new BasicStroke(2.0f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0);
    private static final BasicStroke STROKE_15F_DASH = new BasicStroke(1.5f,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{4, 4}, 0);
    private static final BasicStroke STROKE_3F_GREY_DASH = new BasicStroke(3.0f,
            BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4}, 0);
    private static final BasicStroke STROKE_2F_TAPE_DASH = new BasicStroke(2.0f,
            BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{8.0f, 6.0f}, 0.0f);

    // ----------------------------------------------------------------

    private RobotState robotState;
    private RobotConfig robotConfig;
    private practice.PracticeManager practiceManager = null;
    private practice.CompetitionManager competitionManager = null;
    private List<core.FieldObstacle> fieldObstacles = new ArrayList<>();
    private List<core.BallState> ballStates = null;
    private boolean blueView = false;

    // 描画処理時間計測用（ミリ秒単位）
    private volatile double lastDrawTimeMs = 0.0;

    /**
     * 最後のpaintComponent呼び出しの描画時間（ミリ秒）を返します。
     */
    public double getLastDrawTimeMs() {
        return lastDrawTimeMs;
    }

    // 走行軌跡 (ボディ中心 G の軌跡を描画)
    private final List<TrailPoint> trail = new ArrayList<>();

    // リプレイ用の全軌跡データ
    private List<RobotState> replayStates = null;
    private int replayCurrentIndex = -1;

    // 項目1: 軌跡バッチ描画用 Path2D (フィールドレベルで再利用)
    private final Path2D.Double trailPathNormal = new Path2D.Double();
    private final Path2D.Double trailPathSlip   = new Path2D.Double();

    // 項目2: ボール描画用 Ellipse2D 再利用インスタンス
    private final Ellipse2D.Double reusableBallShape = new Ellipse2D.Double();

    // 項目4: suppressionUnit 頂点キャッシュ
    private double[] cachedSuppressionVertices = null; // null = キャッシュ無効

    private static class TrailPoint {
        final double x, y;
        final boolean isSlipping;

        TrailPoint(double x, double y, boolean isSlipping) {
            this.x = x;
            this.y = y;
            this.isSlipping = isSlipping;
        }
    }

    public FieldPanel() {
        setBackground(new Color(24, 26, 32)); // ダークテーマ背景
        setPreferredSize(new Dimension(600, 600));
    }

    public void setRobotState(RobotState state, RobotConfig config) {
        this.robotState = state;
        this.robotConfig = config;

        if (state != null) {
            // 走行軌跡に追加 (ボディ中心 G の位置を記録)
            if (trail.isEmpty() || Math.hypot(state.x - trail.get(trail.size() - 1).x, state.y - trail.get(trail.size() - 1).y) > 0.02) {
                trail.add(new TrailPoint(state.x, state.y, state.slipFlag));
            }
        }
        repaint();
    }

    public void setReplayData(List<RobotState> states, int currentIndex, RobotConfig config) {
        this.replayStates = states;
        this.replayCurrentIndex = currentIndex;
        this.robotConfig = config;

        if (states != null && currentIndex >= 0 && currentIndex < states.size()) {
            this.robotState = states.get(currentIndex);
            trail.clear();
            for (int i = 0; i <= currentIndex; i++) {
                RobotState s = states.get(i);
                trail.add(new TrailPoint(s.x, s.y, s.slipFlag));
            }
        }
        repaint();
    }

    public void setFieldObstacles(List<core.FieldObstacle> obstacles) {
        this.fieldObstacles = obstacles != null ? obstacles : new ArrayList<>();
        // 項目4: 障害物リストが変わったのでキャッシュを無効化
        cachedSuppressionVertices = null;
        repaint();
    }

    public void setBlueView(boolean blueView) {
        this.blueView = blueView;
        repaint();
    }

    public void clearTrail() {
        trail.clear();
        replayStates = null;
        replayCurrentIndex = -1;
        ballStates = null;
        repaint();
    }

    public void setBallStates(List<core.BallState> states) {
        this.ballStates = states;
        repaint();
    }

    public void setPracticeManager(practice.PracticeManager pm) {
        this.practiceManager = pm;
        repaint();
    }

    public void setCompetitionManager(practice.CompetitionManager cm) {
        this.competitionManager = cm;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        long drawStart = System.nanoTime();
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int panelSize = Math.min(getWidth(), getHeight());
        int fieldPixels = panelSize - 2 * PADDING;
        double scale = (double) fieldPixels / FIELD_SIZE; // px / m

        int offsetX = (getWidth() - fieldPixels) / 2;
        int offsetY = (getHeight() - fieldPixels) / 2;

        // 1. グリッドの描画
        drawFieldGrid(g2, offsetX, offsetY, fieldPixels, scale);

        // ゴールの描画（赤・青・灰色）
        drawGoals(g2, offsetX, offsetY, scale);

        // 項目3: calculateShootTargetInfo を1回だけ計算 (練習モード時のみ)
        ShootTargetInfo cachedTargetInfo = null;
        boolean needsTargetInfo = (competitionManager == null && replayStates == null
                && robotState != null && robotConfig != null);
        if (needsTargetInfo) {
            cachedTargetInfo = calculateShootTargetInfo(
                    robotState.x, robotState.y, robotState.heading, blueView);
        }

        // シューターガイドおよび射出ビームの描画
        drawShooterGuides(g2, offsetX, offsetY, scale, cachedTargetInfo);

        // 2. 走行軌跡の描画 (ボディ中心の軌跡)
        drawTrail(g2, offsetX, offsetY, scale);

        // ボールの描画（走行軌跡より前面に表示する）
        drawBalls(g2, offsetX, offsetY, scale);

        // 3. ロボットの描画
        if (competitionManager != null && replayStates == null) {
            drawCompetitionRobots(g2, offsetX, offsetY, scale);
            drawCompetitionScoreHUD(g2);
        } else if (robotState != null && robotConfig != null) {
            drawRobot(g2, robotState, robotConfig, offsetX, offsetY, scale);
        }

        // 1.5 障害物の描画
        drawObstacles(g2, offsetX, offsetY, scale);

        // 4. 練習モードのNPCの描画
        if (practiceManager != null && replayStates == null) {
            practiceManager.render(g2, scale, offsetX, offsetY, FIELD_SIZE, blueView);
        }

        // 5. ボール保持数および得点状況のUIテキスト描画（練習モードのみ）
        if (competitionManager == null && robotState != null && robotConfig != null) {
            g2.setColor(Color.WHITE);
            g2.setFont(FONT_HUD_14);
            String text = String.format("保持: %d / %d 個", robotState.heldBallCount, robotConfig.intakeCapacity);
            g2.drawString(text, 15, 25);

            String scoreText = String.format("得点: 赤青 %d / 白 %d  (射出: 試行 %d / 成功 %d)", robotState.scoreCount, robotState.whiteScoreCount, robotState.shotAttemptCount, robotState.scoreCount);
            g2.drawString(scoreText, 15, 45);

            // ロボットの画面上頭上に "SHOOT READY" を表示する
            double screenX = toScreenX(robotState.x, robotState.y);
            double screenY = toScreenY(robotState.x, robotState.y);

            // 項目3: 既に計算済みの cachedTargetInfo を再利用
            if (cachedTargetInfo != null && cachedTargetInfo.canShoot && robotState.heldBallCount > 0) {
                g2.setColor(COLOR_SHOOT_READY);
                g2.setFont(FONT_HUD_12);
                String readyText = "SHOOT READY";
                int textW = g2.getFontMetrics().stringWidth(readyText);
                g2.drawString(readyText, (int)(screenX - textW / 2.0), (int)(screenY - (robotConfig.robotSize * scale / 2.0) - 15));
            }
        }
        lastDrawTimeMs = (System.nanoTime() - drawStart) / 1_000_000.0;
    }

    private void drawObstacles(Graphics2D g2, int offsetX, int offsetY, double scale) {
        if (fieldObstacles == null) return;

        Stroke origStroke = g2.getStroke();
        Color origColor = g2.getColor();

        for (core.FieldObstacle obs : fieldObstacles) {
            // カテゴリごとの配色決定
            Color fillCol;
            Color lineCol;
            String cat = obs.category();
            if ("suppression_unit".equals(cat)) {
                fillCol = COLOR_OBS_SUP_FILL;
                lineCol = COLOR_OBS_SUP_LINE;
            } else if ("fire_shield".equals(cat)) {
                fillCol = COLOR_OBS_FIRE_FILL;
                lineCol = COLOR_OBS_FIRE_LINE;
            } else { // "brace" 等
                fillCol = COLOR_OBS_BRACE_FILL;
                lineCol = COLOR_OBS_BRACE_LINE;
            }

            g2.setColor(fillCol);

            if (obs instanceof core.CircleObstacle) {
                core.CircleObstacle circ = (core.CircleObstacle) obs;
                double cx = toScreenX(circ.getCenterX(), circ.getCenterY());
                double cy = toScreenY(circ.getCenterX(), circ.getCenterY());
                double r = circ.getRadius() * scale;
                g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));

                g2.setColor(lineCol);
                g2.setStroke(STROKE_2F);
                g2.draw(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            } else if (obs instanceof core.CapsuleObstacle) {
                core.CapsuleObstacle cap = (core.CapsuleObstacle) obs;
                double ax1 = toScreenX(cap.getP1()[0], cap.getP1()[1]);
                double ay1 = toScreenY(cap.getP1()[0], cap.getP1()[1]);
                double ax2 = toScreenX(cap.getP2()[0], cap.getP2()[1]);
                double ay2 = toScreenY(cap.getP2()[0], cap.getP2()[1]);

                // カプセルは線分を太さ2*radiusで丸端ストロークで描画
                g2.setStroke(new BasicStroke((float)(2 * cap.getRadius() * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Double(ax1, ay1, ax2, ay2));

                // 輪郭線を追加で細い線として重ね書き
                g2.setColor(lineCol);
                g2.setStroke(STROKE_15F);
                g2.draw(new Line2D.Double(ax1, ay1, ax2, ay2));
            } else {
                // ポリゴン
                double[][] vertices = obs.outlinePolygon();
                Path2D.Double path = new Path2D.Double();
                path.moveTo(toScreenX(vertices[0][0], vertices[0][1]), toScreenY(vertices[0][0], vertices[0][1]));
                for (int i = 1; i < vertices.length; i++) {
                    path.lineTo(toScreenX(vertices[i][0], vertices[i][1]), toScreenY(vertices[i][0], vertices[i][1]));
                }
                path.closePath();
                if ("tape".equals(obs.category())) {
                    // スタートテープ：塗りつぶしは薄い半透明、外枠はアライアンスカラーの破線
                    boolean isRed = obs.id().contains("RED");
                    Color tapeColor = isRed ? new Color(226, 75, 74, 40) : new Color(55, 138, 221, 40);
                    Color outlineColor = isRed ? new Color(226, 75, 74, 180) : new Color(55, 138, 221, 180);

                    g2.setColor(tapeColor);
                    g2.fill(path);

                    g2.setColor(outlineColor);
                    g2.setStroke(STROKE_2F_TAPE_DASH);
                    g2.draw(path);
                } else {
                    g2.fill(path);

                    g2.setColor(lineCol);
                    g2.setStroke(STROKE_2F);
                    g2.draw(path);
                }

                // 白ゴール斜辺のハイライト描画
                String obsId = obs.id();
                if ("fire_shield_left".equals(obsId) || "fire_shield_right".equals(obsId)) {
                    // ポリゴンの簡易重心を計算
                    double gx = 0;
                    double gy = 0;
                    for (double[] v : vertices) {
                        gx += v[0];
                        gy += v[1];
                    }
                    gx /= vertices.length;
                    gy /= vertices.length;

                    double bestScore = -1.0;
                    int bestIdx = 0;
                    for (int i = 0; i < vertices.length; i++) {
                        int j = (i + 1) % vertices.length;
                        double edgeDx = vertices[j][0] - vertices[i][0];
                        double edgeDy = vertices[j][1] - vertices[i][1];
                        double len = Math.hypot(edgeDx, edgeDy);
                        if (len < 1e-9) continue;

                        // 斜め度 (0.0〜1.0)
                        double absDx = Math.abs(edgeDx);
                        double absDy = Math.abs(edgeDy);
                        double maxD = Math.max(absDx, absDy);
                        double diagonality = (maxD > 1e-9) ? Math.min(absDx, absDy) / maxD : 0.0;

                        // 仮の法線
                        double nx = edgeDy / len;
                        double ny = -edgeDx / len;

                        // 重心から辺の中点へのベクトル
                        double mx = (vertices[i][0] + vertices[j][0]) / 2.0;
                        double my = (vertices[i][1] + vertices[j][1]) / 2.0;
                        double gmx = mx - gx;
                        double gmy = my - gy;

                        // もし仮の法線が重心方向（内側）を向いていたら反転して外向きにする
                        if (nx * gmx + ny * gmy < 0) {
                            nx = -nx;
                            ny = -ny;
                        }

                        // 辺の中点からフィールド中央 (3.5, 3.5) へのベクトルを正規化
                        double toCenterX = 3.5 - mx;
                        double toCenterY = 3.5 - my;
                        double toCenterLen = Math.hypot(toCenterX, toCenterY);
                        if (toCenterLen > 1e-9) {
                            toCenterX /= toCenterLen;
                            toCenterY /= toCenterLen;
                        }

                        // 中央向きか
                        double facing = nx * toCenterX + ny * toCenterY;
                        if (facing > 0) {
                            double score = len * (0.5 + diagonality);
                            if (score > bestScore) {
                                bestScore = score;
                                bestIdx = i;
                            }
                        }
                    }
                    int j = (bestIdx + 1) % vertices.length;

                    double ax = toScreenX(vertices[bestIdx][0], vertices[bestIdx][1]);
                    double ay = toScreenY(vertices[bestIdx][0], vertices[bestIdx][1]);
                    double bx = toScreenX(vertices[j][0], vertices[j][1]);
                    double by = toScreenY(vertices[j][0], vertices[j][1]);

                    g2.setColor(COLOR_FS_HIGHLIGHT);
                    g2.setStroke(STROKE_4F_HL);
                    g2.draw(new Line2D.Double(ax, ay, bx, by));
                }
            }
        }

        g2.setStroke(origStroke);
        g2.setColor(origColor);
    }

    private void drawFieldGrid(Graphics2D g2, int offsetX, int offsetY, int fieldPixels, double scale) {
        g2.setColor(COLOR_FIELD_FILL);
        g2.fillRect(offsetX, offsetY, fieldPixels, fieldPixels);

        g2.setStroke(STROKE_1F);
        for (int i = 0; i <= FIELD_SIZE; i++) {
            double gridX = toScreenX(0, i);
            double gridY = toScreenY(i, 0);

            if (i == 0 || i == (int)FIELD_SIZE) {
                g2.setColor(COLOR_GRID_BORDER);
            } else if (i == 3.5) {
                g2.setColor(COLOR_GRID_CENTER);
            } else {
                g2.setColor(COLOR_GRID_MINOR);
            }
            g2.drawLine((int)gridX, offsetY, (int)gridX, offsetY + fieldPixels);
            g2.drawLine(offsetX, (int)gridY, offsetX + fieldPixels, (int)gridY);
        }

        g2.setColor(COLOR_FIELD_OUTLINE);
        g2.setStroke(STROKE_25F);
        g2.drawRect(offsetX, offsetY, fieldPixels, fieldPixels);
    }

    private void drawTrail(Graphics2D g2, int offsetX, int offsetY, double scale) {
        if (trail.size() < 2) return;

        // 過去ログ全体の軌跡を薄く描画
        if (replayStates != null) {
            g2.setStroke(STROKE_15F);
            g2.setColor(COLOR_TRAIL_GHOST);
            Path2D.Double fullPath = new Path2D.Double();

            double startX = toScreenX(replayStates.get(0).x, replayStates.get(0).y);
            double startY = toScreenY(replayStates.get(0).x, replayStates.get(0).y);
            fullPath.moveTo(startX, startY);
            for (int i = 1; i < replayStates.size(); i++) {
                double px = toScreenX(replayStates.get(i).x, replayStates.get(i).y);
                double py = toScreenY(replayStates.get(i).x, replayStates.get(i).y);
                fullPath.lineTo(px, py);
            }
            g2.draw(fullPath);
        }

        // 項目1: 通常セグメントとスリップセグメントを別々の Path2D に蓄積し、2回の draw() で描画
        trailPathNormal.reset();
        trailPathSlip.reset();

        for (int i = 1; i < trail.size(); i++) {
            TrailPoint p1 = trail.get(i - 1);
            TrailPoint p2 = trail.get(i);

            double x1 = toScreenX(p1.x, p1.y);
            double y1 = toScreenY(p1.x, p1.y);
            double x2 = toScreenX(p2.x, p2.y);
            double y2 = toScreenY(p2.x, p2.y);

            if (p2.isSlipping) {
                trailPathSlip.moveTo(x1, y1);
                trailPathSlip.lineTo(x2, y2);
            } else {
                trailPathNormal.moveTo(x1, y1);
                trailPathNormal.lineTo(x2, y2);
            }
        }

        g2.setStroke(STROKE_25F);
        g2.setColor(COLOR_TRAIL_NORMAL);
        g2.draw(trailPathNormal);

        g2.setColor(COLOR_TRAIL_SLIP);
        g2.draw(trailPathSlip);
    }

    private void drawRobot(Graphics2D g2, RobotState state, RobotConfig config, int offsetX, int offsetY, double scale) {
        // ロボットの回転中心は「車軸中心 A (xA, yA)」
        double sax = toScreenX(state.xA, state.yA);
        double say = toScreenY(state.xA, state.yA);
        double sHeading = toScreenHeading(state.heading);

        AffineTransform old = g2.getTransform();
        g2.translate(sax, say);
        g2.rotate(sHeading);

        // 左右車輪の描画 (車軸位置上の左右 w/2 の位置)
        double wheelLength = 0.12 * scale; // ホイール長
        double wheelWidth = 0.03 * scale;  // ホイール幅
        double wPx = (config.trackWidth / 2.0) * scale;

        g2.setColor(COLOR_WHEEL);
        // 左車輪
        g2.fill(new Rectangle2D.Double(-wheelLength / 2.0, -wPx - wheelWidth / 2.0, wheelLength, wheelWidth));
        // 右車輪
        g2.fill(new Rectangle2D.Double(-wheelLength / 2.0, wPx - wheelWidth / 2.0, wheelLength, wheelWidth));

        // 車軸の描画 (左右車輪を繋ぐ細い棒)
        g2.setColor(COLOR_AXLE);
        g2.setStroke(STROKE_15F);
        g2.draw(new Line2D.Double(0, -wPx, 0, wPx));

        // ボディの描画
        // ボディの中心は車軸 A から d だけ後ろ（ローカル座標系で -d の位置）
        double dPx = config.pivotOffsetD * scale;
        double sizePx = config.robotSize * scale;

        // 輪郭色の決定 (衝突時またはスリップ時は赤、通常時は水色)
        boolean isAlarm = state.collisionFlag || state.slipFlag;
        g2.setColor(isAlarm ? COLOR_ROBOT_ALARM : COLOR_ROBOT_NORMAL);
        g2.setStroke(isAlarm ? STROKE_45F : STROKE_3F); // アラーム時は太くする

        // 中心 (-dPx, 0) からサイズ sizePx の矩形を描く
        g2.draw(new Rectangle2D.Double(-dPx - sizePx / 2.0, -sizePx / 2.0, sizePx, sizePx));

        // 塗りつぶし色の決定 (衝突時は半透明赤、スリップ時は濃い赤、壁接触時は半透明黄色、通常時は半透明紺)
        Color fillColor;
        if (state.collisionFlag) {
            fillColor = COLOR_FILL_COLLISION;
        } else if (state.slipFlag) {
            fillColor = COLOR_FILL_SLIP;
        } else if (state.hitWallFlag) {
            fillColor = COLOR_FILL_WALL;
        } else {
            fillColor = COLOR_FILL_NORMAL;
        }
        g2.setColor(fillColor);
        g2.fill(new Rectangle2D.Double(-dPx - sizePx / 2.0, -sizePx / 2.0, sizePx, sizePx));

        // ボディ中心 G の位置のデバッグ描画 (半透明の小さな丸)
        g2.setColor(COLOR_ROBOT_ORANGE);
        g2.fill(new Rectangle2D.Double(-dPx - 3, -3, 6, 6));

        // 進行方向 (ボディ前面) を示す矢印の描画 (ボディの右端近くに描画)
        Path2D.Double arrow = new Path2D.Double();
        double arrowSize = 0.15 * scale;
        // フロント端は -dPx + sizePx / 2.0
        double frontEdge = -dPx + sizePx / 2.0;
        arrow.moveTo(frontEdge - 5, 0);
        arrow.lineTo(frontEdge - arrowSize - 5, -arrowSize / 2.0);
        arrow.lineTo(frontEdge - arrowSize - 5, arrowSize / 2.0);
        arrow.closePath();
        g2.setColor(new Color(255, 255, 255, 220));
        g2.fill(arrow);

        // インテーク（回収）矩形のハイライト描画
        if (state.intakeActive) {
            g2.setColor(COLOR_INTAKE_FILL); // 薄い半透明シアン
            double halfSize = config.robotSize / 2.0;
            double fEdge = (halfSize - config.pivotOffsetD) * scale;
            double depthPx = config.intakeFrontDepth * scale;
            double widthPx = config.intakeFrontWidth * scale;

            // F から見てローカル座標系で、x方向に [-depthPx/2, depthPx/2]、y方向に [-widthPx/2, widthPx/2]
            g2.fill(new Rectangle2D.Double(fEdge - depthPx / 2.0, -widthPx / 2.0, depthPx, widthPx));

            // 枠線も薄く描画して視認性を高める
            g2.setColor(COLOR_INTAKE_LINE);
            g2.setStroke(STROKE_15F);
            g2.draw(new Rectangle2D.Double(fEdge - depthPx / 2.0, -widthPx / 2.0, depthPx, widthPx));
        }

        g2.setTransform(old);
    }

    private void drawBalls(Graphics2D g2, int offsetX, int offsetY, double scale) {
        if (replayStates != null || ballStates == null || ballStates.isEmpty()) return;

        g2.setColor(COLOR_BALL); // #D85A30 (実機のオレンジ色)
        double r = 0.05 * scale;
        double d = r * 2.0;

        // 項目2: Ellipse2D.Double インスタンスを1個だけ生成し、public フィールドを直接書き換えて再利用
        for (core.BallState ball : ballStates) {
            if (ball.collected) continue;
            double bx = toScreenX(ball.x, ball.y);
            double by = toScreenY(ball.x, ball.y);
            reusableBallShape.x = bx - r;
            reusableBallShape.y = by - r;
            reusableBallShape.width  = d;
            reusableBallShape.height = d;
            g2.fill(reusableBallShape);
        }
    }

    private static class GoalSegment {
        final double ax, ay;
        final double bx, by;
        final double normalAngle;

        GoalSegment(double ax, double ay, double bx, double by, double normalAngle) {
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
            this.normalAngle = normalAngle;
        }
    }

    private static class ShootTargetInfo {
        double closestX;
        double closestY;
        double minDistance;
        boolean rayIntersected;
        boolean canShoot;
    }

    private double[] getSuppressionUnitVertices() {
        // 項目4: キャッシュが有効なら即返す
        if (cachedSuppressionVertices != null) {
            return cachedSuppressionVertices;
        }

        double xBL = 1.627, yBL = 0.0;
        double xBR = 5.373, yBR = 0.0;
        double xTL = 2.054, yTL = 0.747;
        double xTR = 4.946, yTR = 0.747;

        if (fieldObstacles != null) {
            for (core.FieldObstacle obs : fieldObstacles) {
                if ("suppression_unit".equals(obs.id()) && obs instanceof core.PolygonObstacle) {
                    core.PolygonObstacle poly = (core.PolygonObstacle) obs;
                    double[][] vertices = poly.getVertices();
                    if (vertices.length >= 4) {
                        List<double[]> sortedByY = new ArrayList<>();
                        for (double[] v : vertices) {
                            sortedByY.add(v);
                        }
                        sortedByY.sort((v1, v2) -> Double.compare(v1[1], v2[1]));

                        double[] b1 = sortedByY.get(0);
                        double[] b2 = sortedByY.get(1);
                        double[] t1 = sortedByY.get(2);
                        double[] t2 = sortedByY.get(3);

                        if (b1[0] < b2[0]) {
                            xBL = b1[0]; yBL = b1[1];
                            xBR = b2[0]; yBR = b2[1];
                        } else {
                            xBL = b2[0]; yBL = b2[1];
                            xBR = b1[0]; yBR = b1[1];
                        }

                        if (t1[0] < t2[0]) {
                            xTL = t1[0]; yTL = t1[1];
                            xTR = t2[0]; yTR = t2[1];
                        } else {
                            xTL = t2[0]; yTL = t2[1];
                            xTR = t1[0]; yTR = t1[1];
                        }
                    }
                    break;
                }
            }
        }
        cachedSuppressionVertices = new double[]{xBL, yBL, xBR, yBR, xTL, yTL, xTR, yTR};
        return cachedSuppressionVertices;
    }

    private ShootTargetInfo calculateShootTargetInfo(double rx, double ry, double heading, boolean isBlue) {
        double[] vertices = getSuppressionUnitVertices();
        double xBL = vertices[0], yBL = vertices[1];
        double xBR = vertices[2], yBR = vertices[3];
        double xTL = vertices[4], yTL = vertices[5];
        double xTR = vertices[6], yTR = vertices[7];

        double L = xTR - xTL;
        double xWL = xTL + L / 3.0;
        double xWR = xTL + 2.0 * L / 3.0;

        List<GoalSegment> targetSegments = new ArrayList<>();
        if (isBlue) {
            // 青アライアンス: 右斜辺 + 上辺右1/3 (xが大きい側 = 青ゴール)
            double normal1 = Math.atan2(-(xBR - xTR), yBR - yTR);
            targetSegments.add(new GoalSegment(xTR, yTR, xBR, yBR, normal1));
            targetSegments.add(new GoalSegment(xWR, yTR, xTR, yTR, -Math.PI / 2.0));
        } else {
            // 赤アライアンス: 左斜辺 + 上辺左1/3 (xが小さい側 = 赤ゴール)
            double normal1 = Math.atan2(xTL - xBL, -(yTL - yBL));
            targetSegments.add(new GoalSegment(xBL, yBL, xTL, yTL, normal1));
            targetSegments.add(new GoalSegment(xTL, yTL, xWL, yTL, -Math.PI / 2.0));
        }

        double halfSize = (robotConfig != null) ? robotConfig.robotSize / 2.0 : 0.25;
        double cosT = Math.cos(heading);
        double sinT = Math.sin(heading);
        double fx = rx + halfSize * cosT;
        double fy = ry + halfSize * sinT;

        double closestX = 0.0;
        double closestY = 0.0;
        double minDistance = Double.MAX_VALUE;
        boolean rayIntersected = false;

        for (GoalSegment seg : targetSegments) {
            double vx = seg.bx - seg.ax;
            double vy = seg.by - seg.ay;
            double det = vx * sinT - vy * cosT;
            if (Math.abs(det) > 1e-9) {
                double t = ((seg.ay - fy) * vx - (seg.ax - fx) * vy) / det;
                double u = (cosT * (seg.ay - fy) - sinT * (seg.ax - fx)) / det;
                if (t >= 0.0 && u >= 0.0 && u <= 1.0) {
                    if (t < minDistance) {
                        minDistance = t;
                        closestX = fx + t * cosT;
                        closestY = fy + t * sinT;
                        rayIntersected = true;
                    }
                }
            }
        }

        double shootRange = (robotConfig != null) ? robotConfig.shootRange : 2.0;
        double shootAngleThreshold = (robotConfig != null) ? robotConfig.shootAngleThreshold : 0.2618;

        boolean canShoot = false;
        if (rayIntersected && minDistance <= shootRange) {
            canShoot = true;
        } else {
            // heading方向のレイそのものは交差しないが、扇形が交差するかサンプリング
            double bestDiff = Double.MAX_VALUE;
            double bestX = 0.0;
            double bestY = 0.0;
            double bestDist = Double.MAX_VALUE;
            boolean sectorIntersected = false;

            for (GoalSegment seg : targetSegments) {
                int samples = 30;
                for (int i = 0; i <= samples; i++) {
                    double u = (double) i / samples;
                    double px = seg.ax + u * (seg.bx - seg.ax);
                    double py = seg.ay + u * (seg.by - seg.ay);
                    double dist = Math.hypot(px - fx, py - fy);
                    if (dist <= shootRange) {
                        double angle = Math.atan2(py - fy, px - fx);
                        double diff = angle - heading;
                        while (diff > Math.PI) diff -= 2.0 * Math.PI;
                        while (diff < -Math.PI) diff += 2.0 * Math.PI;

                        if (Math.abs(diff) <= shootAngleThreshold) {
                            sectorIntersected = true;
                            if (Math.abs(diff) < bestDiff) {
                                bestDiff = Math.abs(diff);
                                bestX = px;
                                bestY = py;
                                bestDist = dist;
                            }
                        }
                    }
                }
            }

            if (sectorIntersected) {
                canShoot = true;
                closestX = bestX;
                closestY = bestY;
                minDistance = bestDist;
            }
        }

        ShootTargetInfo info = new ShootTargetInfo();
        info.closestX = closestX;
        info.closestY = closestY;
        info.minDistance = minDistance;
        info.rayIntersected = rayIntersected;
        info.canShoot = canShoot;
        return info;
    }

    private void drawGoals(Graphics2D g2, int offsetX, int offsetY, double scale) {
        double[] vertices = getSuppressionUnitVertices();
        double xBL = vertices[0], yBL = vertices[1];
        double xBR = vertices[2], yBR = vertices[3];
        double xTL = vertices[4], yTL = vertices[5];
        double xTR = vertices[6], yTR = vertices[7];

        double L = xTR - xTL;
        double xWL = xTL + L / 3.0;
        double xWR = xTL + 2.0 * L / 3.0;

        // 赤ゴール: 左斜辺 (xBL, yBL)->(xTL, yTL) ＋ 上辺左1/3 (xTL, yTL)->(xWL, yTL)
        g2.setColor(COLOR_GOAL_RED);
        g2.setStroke(STROKE_4F);
        g2.draw(new Line2D.Double(
            toScreenX(xBL, yBL), toScreenY(xBL, yBL),
            toScreenX(xTL, yTL), toScreenY(xTL, yTL)
        ));
        g2.draw(new Line2D.Double(
            toScreenX(xTL, yTL), toScreenY(xTL, yTL),
            toScreenX(xWL, yTL), toScreenY(xWL, yTL)
        ));

        // 白/灰色ゴール: 上辺中央1/3 (xWL, yTL)->(xWR, yTL)
        g2.setColor(COLOR_GOAL_GREY);
        g2.setStroke(STROKE_3F_GREY_DASH);
        g2.draw(new Line2D.Double(
            toScreenX(xWL, yTL), toScreenY(xWL, yTL),
            toScreenX(xWR, yTL), toScreenY(xWR, yTL)
        ));

        // 青ゴール: 右斜辺 (xTR, yTR)->(xBR, yBR) ＋ 上辺右1/3 (xWR, yTL)->(xTR, yTR)
        g2.setColor(COLOR_GOAL_BLUE);
        g2.setStroke(STROKE_4F);
        g2.draw(new Line2D.Double(
            toScreenX(xTR, yTR), toScreenY(xTR, yTR),
            toScreenX(xBR, yBR), toScreenY(xBR, yBR)
        ));
        g2.draw(new Line2D.Double(
            toScreenX(xWR, yTL), toScreenY(xWR, yTL),
            toScreenX(xTR, yTR), toScreenY(xTR, yTR)
        ));
    }

    // 項目3: drawShooterGuides に cachedTargetInfo を引数で渡す
    private void drawShooterGuides(Graphics2D g2, int offsetX, int offsetY, double scale,
                                   ShootTargetInfo targetInfo) {
        if (robotState == null || robotConfig == null) return;

        double halfSize = robotConfig.robotSize / 2.0;
        double cosT = Math.cos(robotState.heading);
        double sinT = Math.sin(robotState.heading);
        double fx_w = robotState.x + halfSize * cosT;
        double fy_w = robotState.y + halfSize * sinT;

        double fx = toScreenX(fx_w, fy_w);
        double fy = toScreenY(fx_w, fy_w);

        // targetInfo が null（競技モードなど）の場合は計算する
        if (targetInfo == null) {
            targetInfo = calculateShootTargetInfo(robotState.x, robotState.y, robotState.heading, blueView);
        }

        double tx = toScreenX(targetInfo.closestX, targetInfo.closestY);
        double ty = toScreenY(targetInfo.closestX, targetInfo.closestY);

        if (robotState.shootActive) {
            // 射出ビームの描画（射出位置から交点まで）
            g2.setColor(blueView ? COLOR_BEAM_BLUE : COLOR_BEAM_RED);
            g2.setStroke(STROKE_6F);
            g2.draw(new Line2D.Double(fx, fy, tx, ty));

            g2.setColor(Color.WHITE);
            g2.setStroke(STROKE_2F_ROUND);
            g2.draw(new Line2D.Double(fx, fy, tx, ty));
        } else if (robotState.heldBallCount > 0) {
            if (targetInfo.rayIntersected && targetInfo.canShoot) {
                // レイがゴールにヒットしている状態（緑の太破線）
                g2.setColor(COLOR_GUIDE_GREEN);
                g2.setStroke(STROKE_3F_DASH);
                g2.draw(new Line2D.Double(fx, fy, tx, ty));
            } else if (targetInfo.canShoot) {
                // 扇形は交差しているが、射線そのものは外れている（アライアンスカラーの細破線）
                g2.setColor(blueView ? COLOR_GUIDE_BLUE : COLOR_GUIDE_RED_DIM);
                g2.setStroke(STROKE_2F_DASH);
                g2.draw(new Line2D.Double(fx, fy, tx, ty));
            } else {
                // 射出不可（射出位置からheading方向へshootRange分の灰色細破線）
                double shootRange = robotConfig.shootRange;
                double gx_w = fx_w + shootRange * cosT;
                double gy_w = fy_w + shootRange * sinT;
                double gx = toScreenX(gx_w, gy_w);
                double gy = toScreenY(gx_w, gy_w);

                g2.setColor(COLOR_GUIDE_GREY);
                g2.setStroke(STROKE_15F_DASH);
                g2.draw(new Line2D.Double(fx, fy, gx, gy));
            }
        }
    }

    /**
     * 試合中スコアHUDを左上に描画します。
     * 表示: 自分 N ｜ 味方チーム N ｜ 敵チーム N  （白 N）
     */
    private void drawCompetitionScoreHUD(Graphics2D g2) {
        if (competitionManager == null) return;

        int playerScore  = competitionManager.getPlayerScore();
        int allyScore    = competitionManager.getAllyTeamScore();
        int enemyScore   = competitionManager.getEnemyTeamScore();
        int whiteScore   = competitionManager.getTotalWhiteScore();

        // 背景帯
        g2.setColor(COLOR_SCORE_HUD_BG);
        g2.fillRoundRect(8, 8, 460, 26, 6, 6);

        // 「自分」（黄色）
        g2.setFont(FONT_HUD_13);
        int x = 16;
        g2.setColor(COLOR_SCORE_SELF);
        String selfLabel = "自分 " + playerScore;
        g2.drawString(selfLabel, x, 26);
        x += g2.getFontMetrics().stringWidth(selfLabel) + 6;

        // 区切り
        g2.setColor(COLOR_SCORE_DIVIDER);
        g2.drawString("｜", x, 26);
        x += g2.getFontMetrics().stringWidth("｜") + 6;

        // 「味方チーム」（アライアンスカラー）
        boolean playerIsBlue = false;
        core.CompetitionRobot player = competitionManager.getPlayerRobot();
        if (player != null) playerIsBlue = player.getAlliance() == core.CompetitionRobot.Alliance.BLUE;
        g2.setColor(playerIsBlue ? COLOR_SCORE_ALLY_BLUE : COLOR_SCORE_ALLY_RED);
        String allyLabel = "味方チーム " + allyScore;
        g2.drawString(allyLabel, x, 26);
        x += g2.getFontMetrics().stringWidth(allyLabel) + 6;

        g2.setColor(COLOR_SCORE_DIVIDER);
        g2.drawString("｜", x, 26);
        x += g2.getFontMetrics().stringWidth("｜") + 6;

        // 「敵チーム」（敵色）
        g2.setColor(playerIsBlue ? COLOR_SCORE_ALLY_RED : COLOR_SCORE_ALLY_BLUE);
        String enemyLabel = "敵チーム " + enemyScore;
        g2.drawString(enemyLabel, x, 26);
        x += g2.getFontMetrics().stringWidth(enemyLabel) + 6;

        // 区切り
        g2.setColor(COLOR_SCORE_DIVIDER);
        g2.drawString("｜", x, 26);
        x += g2.getFontMetrics().stringWidth("｜") + 6;

        // 「白」（灰色、独立表示）
        g2.setColor(COLOR_SCORE_WHITE);
        g2.drawString("白 " + whiteScore, x, 26);
    }

    private void drawCompetitionRobots(Graphics2D g2, int offsetX, int offsetY, double scale) {
        if (competitionManager == null) return;

        Font textFont = FONT_HUD_12;

        for (core.CompetitionRobot r : competitionManager.getRobots()) {
            double sax = toScreenX(r.xA, r.yA);
            double say = toScreenY(r.xA, r.yA);
            double sHeading = toScreenHeading(r.heading);

            AffineTransform old = g2.getTransform();
            g2.translate(sax, say);
            g2.rotate(sHeading);

            double wPx = (r.getConfig().trackWidth / 2.0) * scale;
            double wheelLength = 0.12 * scale;
            double wheelWidth = 0.03 * scale;

            // 1. 車輪
            g2.setColor(COLOR_WHEEL);
            g2.fill(new Rectangle2D.Double(-wheelLength / 2.0, -wPx - wheelWidth / 2.0, wheelLength, wheelWidth));
            g2.fill(new Rectangle2D.Double(-wheelLength / 2.0, wPx - wheelWidth / 2.0, wheelLength, wheelWidth));

            // 車軸
            g2.setColor(COLOR_AXLE);
            g2.setStroke(STROKE_15F);
            g2.draw(new Line2D.Double(0, -wPx, 0, wPx));

            // 2. ボディ
            double dPx = (r.getDriveType() == core.CompetitionRobot.DriveType.OMNI) ? 0.0 : r.getConfig().pivotOffsetD * scale;
            double sizePx = r.getConfig().robotSize * scale;

            Color bodyColor;
            if (r.isPlayer()) {
                bodyColor = new Color(239, 159, 39, 200); // 黄 (#EF9F27)
            } else if (r.getAlliance() == core.CompetitionRobot.Alliance.RED) {
                bodyColor = new Color(226, 75, 74, 200);  // 赤 (#E24B4A)
            } else {
                bodyColor = new Color(55, 138, 221, 200);  // 青 (#378ADD)
            }

            g2.setColor(bodyColor);
            g2.setStroke(r.collisionFlag ? STROKE_45F : STROKE_3F);
            g2.draw(new Rectangle2D.Double(-dPx - sizePx / 2.0, -sizePx / 2.0, sizePx, sizePx));

            Color fillColor = r.collisionFlag ? COLOR_FILL_COLLISION : new Color(bodyColor.getRed(), bodyColor.getGreen(), bodyColor.getBlue(), 80);
            g2.setColor(fillColor);
            g2.fill(new Rectangle2D.Double(-dPx - sizePx / 2.0, -sizePx / 2.0, sizePx, sizePx));

            // 3. 矢印
            Path2D.Double arrow = new Path2D.Double();
            double arrowSize = 0.13 * scale;
            double frontEdge = -dPx + sizePx / 2.0;
            arrow.moveTo(frontEdge - 4, 0);
            arrow.lineTo(frontEdge - arrowSize - 4, -arrowSize / 2.0);
            arrow.lineTo(frontEdge - arrowSize - 4, arrowSize / 2.0);
            arrow.closePath();
            g2.setColor(Color.WHITE);
            g2.fill(arrow);

            // 4. インテーク
            if (r.intakeActive) {
                g2.setColor(COLOR_INTAKE_FILL);
                double halfSize = r.getConfig().robotSize / 2.0;
                double fEdge = (halfSize - ((r.getDriveType() == core.CompetitionRobot.DriveType.OMNI) ? 0.0 : r.getConfig().pivotOffsetD)) * scale;
                double depthPx = r.getConfig().intakeFrontDepth * scale;
                double widthPx = r.getConfig().intakeFrontWidth * scale;
                g2.fill(new Rectangle2D.Double(fEdge - depthPx / 2.0, -widthPx / 2.0, depthPx, widthPx));
            }

            g2.setTransform(old);

            // 5. ラベル
            double gx = toScreenX(r.x, r.y);
            double gy = toScreenY(r.x, r.y);

            g2.setColor(Color.WHITE);
            g2.setFont(textFont);
            int textW = g2.getFontMetrics(textFont).stringWidth(r.getName());
            g2.drawString(r.getName(), (int)(gx - textW / 2.0), (int)(gy - sizePx / 2.0 - 5));

            // 6. 残弾数（機体右隣）
            Color ballCountColor;
            if (r.isPlayer()) {
                ballCountColor = COLOR_SCORE_SELF;
            } else if (r.getAlliance() == core.CompetitionRobot.Alliance.RED) {
                ballCountColor = new Color(255, 140, 140);
            } else {
                ballCountColor = new Color(140, 200, 255);
            }
            g2.setColor(ballCountColor);
            g2.setFont(FONT_HUD_13);
            String countStr = String.valueOf(r.heldBallCount);
            g2.drawString(countStr, (int)(gx + sizePx / 2.0 + 4), (int)(gy + 5));

            // 7. 射出ビーム（shootActive のとき、前面からshootRange分の線）
            if (r.shootActive) {
                boolean npcIsBlue = r.getAlliance() == core.CompetitionRobot.Alliance.BLUE;
                double halfS = r.getConfig().robotSize / 2.0;
                double cosH = Math.cos(r.heading);
                double sinH = Math.sin(r.heading);
                double bfxW = r.x + halfS * cosH;
                double bfyW = r.y + halfS * sinH;
                double btxW = bfxW + r.getShootRange() * cosH;
                double btyW = bfyW + r.getShootRange() * sinH;
                double beamFx = toScreenX(bfxW, bfyW);
                double beamFy = toScreenY(bfxW, bfyW);
                double beamTx = toScreenX(btxW, btyW);
                double beamTy = toScreenY(btxW, btyW);
                Stroke savedStroke = g2.getStroke();
                Color beamColor = npcIsBlue ? COLOR_BEAM_BLUE : COLOR_BEAM_RED;
                g2.setColor(beamColor);
                g2.setStroke(STROKE_5F);
                g2.draw(new Line2D.Double(beamFx, beamFy, beamTx, beamTy));
                g2.setColor(new Color(255, 255, 255, 180));
                g2.setStroke(STROKE_15F_ROUND);
                g2.draw(new Line2D.Double(beamFx, beamFy, beamTx, beamTy));
                g2.setStroke(savedStroke);
            }
        }
    }

    private double toScreenX(double dataX, double dataY) {
        int panelSize = Math.min(getWidth(), getHeight());
        int fieldPixels = panelSize - 2 * PADDING;
        double scale = (double) fieldPixels / FIELD_SIZE;
        double offsetX = (getWidth() - fieldPixels) / 2.0;
        if (blueView) {
            return offsetX + (FIELD_SIZE - dataY) * scale;
        } else {
            return offsetX + dataY * scale;
        }
    }

    private double toScreenY(double dataX, double dataY) {
        int panelSize = Math.min(getWidth(), getHeight());
        int fieldPixels = panelSize - 2 * PADDING;
        double scale = (double) fieldPixels / FIELD_SIZE;
        double offsetY = (getHeight() - fieldPixels) / 2.0;
        if (blueView) {
            return offsetY + dataX * scale;
        } else {
            return offsetY + (FIELD_SIZE - dataX) * scale;
        }
    }

    private double toScreenHeading(double heading) {
        if (blueView) {
            return Math.atan2(Math.cos(heading), -Math.sin(heading));
        } else {
            return Math.atan2(-Math.cos(heading), Math.sin(heading));
        }
    }
}
