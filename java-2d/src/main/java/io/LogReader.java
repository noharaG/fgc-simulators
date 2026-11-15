package io;

import core.RobotConfig;
import core.RobotState;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存された時系列CSVファイルから走行ログデータを読み戻し、
 * メタデータ設定の復元およびリプレイデータリストの生成を行うクラス。
 * ヘッダーのカラム名をもとに自動的にマッピングしてパースするため、カラムの増減に対しても高い互換性を持ちます。
 */
public class LogReader {

    /**
     * 指定された時系列CSVログファイルを読み込み、RobotConfig と RobotState リストを含む ReplayData を返します。
     * 
     * @param file 読み込むCSVファイル
     * @return 読み込んだリプレイデータ
     * @throws IOException ファイルの読み込みやパースに失敗した場合
     */
    public static ReplayData readTimeSeries(File file) throws IOException {
        List<RobotState> states = new ArrayList<>();
        RobotConfig config = null; // CSVから復元する設定。見つからない場合はデフォルト

        // デフォルト設定値を用意しておく（メタデータがない場合のフォールバック用）
        double mass = 13.5;
        double trackWidth = 0.45;
        double wheelDiameter = 0.09;
        double motorNoLoadSpeed = 6000.0;
        double gearRatio = 1.0 / 15.0;
        double pivotOffsetD = 0.0;
        double muStatic = 0.8;
        double muKinetic = 0.7;
        double initX = 3.5;
        double initY = 3.5;
        double initHeading = Math.PI / 2;
        double maxSpeed = 1.88;
        double maxAccel = 3.0;
        double maxOmega = 4.0;
        double maxAlpha = 12.0;
        double frictionAccel = 0.5;
        Long practiceSeed = null;

        // ヘッダーのカラム名とインデックスの対応マップ
        Map<String, Integer> colMap = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNum = 0;

            while ((line = br.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 1. メタデータ行のパース (# Config: で始まる行)
                if (line.startsWith("# Config:")) {
                    try {
                        String metaContent = line.substring("# Config:".length()).trim();
                        String[] pairs = metaContent.split(",");
                        for (String pair : pairs) {
                            String[] kv = pair.split("=");
                            if (kv.length == 2) {
                                String key = kv[0].trim();
                                String valStr = kv[1].trim();

                                if (key.equals("practiceSeed")) {
                                    if (!valStr.equals("null")) {
                                        practiceSeed = Long.parseLong(valStr);
                                    }
                                    continue;
                                }

                                double val = Double.parseDouble(valStr);

                                switch (key) {
                                    case "mass": mass = val; break;
                                    case "trackWidth": trackWidth = val; break;
                                    case "wheelDiameter": wheelDiameter = val; break;
                                    case "motorNoLoadSpeed": motorNoLoadSpeed = val; break;
                                    case "gearRatio": gearRatio = val; break;
                                    case "pivotOffsetD": pivotOffsetD = val; break;
                                    case "muStatic": muStatic = val; break;
                                    case "muKinetic": muKinetic = val; break;
                                    case "initX": initX = val; break;
                                    case "initY": initY = val; break;
                                    case "initHeading": initHeading = val; break;
                                    case "maxSpeed": maxSpeed = val; break;
                                    case "maxAccel": maxAccel = val; break;
                                    case "maxOmega": maxOmega = val; break;
                                    case "maxAlpha": maxAlpha = val; break;
                                    case "frictionAccel": frictionAccel = val; break;
                                }
                            }
                        }
                        // 設定を構築
                        config = new RobotConfig(
                            mass, trackWidth, wheelDiameter, motorNoLoadSpeed, gearRatio, pivotOffsetD,
                            muStatic, muKinetic, initX, initY, initHeading, maxSpeed, maxAccel,
                            maxOmega, maxAlpha, frictionAccel, practiceSeed
                        );
                    } catch (Exception e) {
                        System.err.println("警告: メタデータのパースに失敗しました (行: " + lineNum + "): " + e.getMessage());
                    }
                    continue;
                }

                // 2. ヘッダー行の検出とマッピングの構築
                if (line.startsWith("Time")) {
                    String[] headers = line.split(",");
                    colMap.clear();
                    for (int i = 0; i < headers.length; i++) {
                        colMap.put(headers[i].trim(), i);
                    }
                    continue;
                }

                // 3. データ行のパース
                String[] tokens = line.split(",");
                // 基本的な列（Timeや座標）すらない行はスキップ
                if (tokens.length < 5) {
                    System.err.println("警告: カラム数が不足しているため行を無視します (行: " + lineNum + ")");
                    continue;
                }

                try {
                    double time = getDoubleVal(tokens, colMap, "Time(s)", 0.0);
                    String dateTime = getStringVal(tokens, colMap, "DateTime", "");
                    double x = getDoubleVal(tokens, colMap, "X(m)", 3.5);
                    double y = getDoubleVal(tokens, colMap, "Y(m)", 3.5);
                    double xA = getDoubleVal(tokens, colMap, "XA(m)", 3.5);
                    double yA = getDoubleVal(tokens, colMap, "YA(m)", 3.5);
                    double heading = getDoubleVal(tokens, colMap, "Heading(rad)", 0.0);
                    double leftWheelSpeed = getDoubleVal(tokens, colMap, "LeftWheelSpeed(m/s)", 0.0);
                    double rightWheelSpeed = getDoubleVal(tokens, colMap, "RightWheelSpeed(m/s)", 0.0);
                    double v = getDoubleVal(tokens, colMap, "V(m/s)", 0.0);
                    double omega = getDoubleVal(tokens, colMap, "Omega(rad/s)", 0.0);
                    double accelV = getDoubleVal(tokens, colMap, "AccelV(m/s^2)", 0.0);
                    double accelOmega = getDoubleVal(tokens, colMap, "AccelOmega(rad/s^2)", 0.0);
                    double rawInputY = getDoubleVal(tokens, colMap, "RawInputY", 0.0);
                    double rawInputX = getDoubleVal(tokens, colMap, "RawInputX", 0.0);
                    double cmdV = getDoubleVal(tokens, colMap, "CmdV(m/s)", 0.0);
                    double cmdOmega = getDoubleVal(tokens, colMap, "CmdOmega(rad/s)", 0.0);
                    boolean slipFlag = getBooleanVal(tokens, colMap, "SlipFlag", false);
                    double slipAmount = getDoubleVal(tokens, colMap, "SlipAmount(m/s)", 0.0);
                    double cumulativeDistance = getDoubleVal(tokens, colMap, "CumulativeDistance(m)", 0.0);
                    double deltaDistance = getDoubleVal(tokens, colMap, "DeltaDistance(m)", 0.0);
                    boolean isStopped = getBooleanVal(tokens, colMap, "IsStopped", false);
                    double cumulativeStopTime = getDoubleVal(tokens, colMap, "CumulativeStopTime(s)", 0.0);
                    boolean hitWall = getBooleanVal(tokens, colMap, "HitWall", false);

                    // 練習モード用の追加列 (ヘッダー名ベースで取得、存在しない場合はフォールバック値)
                    boolean collisionFlag = getBooleanVal(tokens, colMap, "Collision", false);
                    int collisionNpcId = getIntVal(tokens, colMap, "CollisionNpcID", -1);
                    double collisionPointX = getDoubleVal(tokens, colMap, "CollisionPX", 0.0);
                    double collisionPointY = getDoubleVal(tokens, colMap, "CollisionPY", 0.0);
                    int cumulativeCollisionCount = getIntVal(tokens, colMap, "CumulativeCollisionCount", 0);
                    double cumulativeCollisionTime = getDoubleVal(tokens, colMap, "CumulativeCollisionTime", 0.0);

                    // 障害物衝突用の追加列
                    boolean obstacleCollisionFlag = getBooleanVal(tokens, colMap, "ObstacleCollision", false);
                    String obstacleCollisionId = getStringVal(tokens, colMap, "ObstacleCollisionID", null);
                    double obstacleCollisionPointX = getDoubleVal(tokens, colMap, "ObstacleCollisionPX", 0.0);
                    double obstacleCollisionPointY = getDoubleVal(tokens, colMap, "ObstacleCollisionPY", 0.0);
                    int cumulativeObstacleCollisionCount = getIntVal(tokens, colMap, "CumulativeObstacleCollisionCount", 0);
                    double cumulativeObstacleCollisionTime = getDoubleVal(tokens, colMap, "CumulativeObstacleCollisionTime", 0.0);

                    // ボール接触用の追加列
                    boolean ballContactFlag = getBooleanVal(tokens, colMap, "BallContact", false);
                    int cumulativeBallContactCount = getIntVal(tokens, colMap, "BallContactCount", 0);

                    // ボール回収（インテーク）用の追加列
                    int heldBallCount = getIntVal(tokens, colMap, "HeldBallCount", 0);
                    boolean intakeActive = getBooleanVal(tokens, colMap, "IntakeActive", false);

                    // ボール射出（シューター）用の追加列
                    int scoreCount = getIntVal(tokens, colMap, "ScoreCount", 0);
                    boolean shootActive = getBooleanVal(tokens, colMap, "ShootActive", false);
                    int shotAttemptCount = getIntVal(tokens, colMap, "ShotAttemptCount", 0);

                    // 白ゴール（Fire Shield 斜辺）
                    int whiteScoreCount = getIntVal(tokens, colMap, "WhiteScoreCount", 0);

                    RobotState state = new RobotState(
                        time, dateTime, x, y, xA, yA, heading, v, omega,
                        leftWheelSpeed, rightWheelSpeed, accelV, accelOmega,
                        rawInputY, rawInputX, cmdV, cmdOmega,
                        slipFlag, slipAmount, cumulativeDistance, deltaDistance,
                        isStopped, cumulativeStopTime, hitWall,
                        collisionFlag, collisionNpcId, collisionPointX, collisionPointY,
                        cumulativeCollisionCount, cumulativeCollisionTime,
                        obstacleCollisionFlag, obstacleCollisionId, obstacleCollisionPointX, obstacleCollisionPointY,
                        cumulativeObstacleCollisionCount, cumulativeObstacleCollisionTime,
                        ballContactFlag, cumulativeBallContactCount,
                        heldBallCount, intakeActive,
                        scoreCount, shootActive, shotAttemptCount,
                        whiteScoreCount
                    );
                    states.add(state);
                } catch (Exception e) {
                    System.err.println("警告: パースエラーにより行を無視します (行: " + lineNum + "): " + e.getMessage());
                }
            }
        }

        // メタデータ行が一切存在しなかった場合は、デフォルト値で設定を生成
        if (config == null) {
            config = new RobotConfig(
                mass, gearRatio, wheelDiameter, trackWidth, pivotOffsetD, muStatic, muKinetic, initX, initY, initHeading, practiceSeed
            );
        }

        return new ReplayData(config, states);
    }

    // --- マッピングベースの値取得ヘルパー ---

    private static double getDoubleVal(String[] tokens, Map<String, Integer> colMap, String colName, double defaultVal) {
        Integer idx = colMap.get(colName);
        if (idx != null && idx < tokens.length) {
            try {
                return Double.parseDouble(tokens[idx]);
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        }
        return defaultVal;
    }

    private static String getStringVal(String[] tokens, Map<String, Integer> colMap, String colName, String defaultVal) {
        Integer idx = colMap.get(colName);
        if (idx != null && idx < tokens.length) {
            return tokens[idx];
        }
        return defaultVal;
    }

    private static boolean getBooleanVal(String[] tokens, Map<String, Integer> colMap, String colName, boolean defaultVal) {
        Integer idx = colMap.get(colName);
        if (idx != null && idx < tokens.length) {
            return Boolean.parseBoolean(tokens[idx]);
        }
        return defaultVal;
    }

    private static int getIntVal(String[] tokens, Map<String, Integer> colMap, String colName, int defaultVal) {
        Integer idx = colMap.get(colName);
        if (idx != null && idx < tokens.length) {
            try {
                return Integer.parseInt(tokens[idx]);
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        }
        return defaultVal;
    }
}
