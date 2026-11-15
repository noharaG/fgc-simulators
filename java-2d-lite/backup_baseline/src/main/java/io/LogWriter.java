package io;

import core.RobotConfig;
import core.RobotState;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * 走行ログ（時系列データとサマリー）をCSVファイルに書き出すクラス。
 * 先頭メタデータ行の出力、および車軸座標・壁接触フラグを含めたCSV保存に対応します。
 */
public class LogWriter {

    private final PrintWriter writer;

    public LogWriter(File file) throws IOException {
        this.writer = new PrintWriter(new FileWriter(file));
    }

    /**
     * 時系列ログの先頭に機体設定（メタデータ）をコメント行として書き込みます。
     * リプレイ再生時に機体形状やピボット設定を正確に復元するために使用します。
     * 
     * @param config 書き出す機体設定
     */
    public void writeMetadata(RobotConfig config) {
        String seedStr = (config.practiceSeed != null) ? String.valueOf(config.practiceSeed) : "null";
        writer.println(String.format(Locale.US,
            "# Config:mass=%.2f,trackWidth=%.3f,wheelDiameter=%.3f,motorNoLoadSpeed=%.1f,gearRatio=%.6f,pivotOffsetD=%.3f,muStatic=%.3f,muKinetic=%.3f,initX=%.3f,initY=%.3f,initHeading=%.6f,maxSpeed=%.3f,maxAccel=%.3f,maxOmega=%.3f,maxAlpha=%.3f,frictionAccel=%.3f,practiceSeed=%s",
            config.mass,
            config.trackWidth,
            config.wheelDiameter,
            config.motorNoLoadSpeed,
            config.gearRatio,
            config.pivotOffsetD,
            config.muStatic,
            config.muKinetic,
            config.initX,
            config.initY,
            config.initHeading,
            config.maxSpeed,
            config.maxAccel,
            config.maxOmega,
            config.maxAlpha,
            config.frictionAccel,
            seedStr
        ));
        writer.flush();
    }

    /**
     * 時系列ログのヘッダー行を書き込みます。
     */
    public void writeHeader() {
        writer.println("Time(s),DateTime,X(m),Y(m),XA(m),YA(m),Heading(rad),LeftWheelSpeed(m/s),RightWheelSpeed(m/s),V(m/s),Omega(rad/s),AccelV(m/s^2),AccelOmega(rad/s^2),RawInputY,RawInputX,CmdV(m/s),CmdOmega(rad/s),SlipFlag,SlipAmount(m/s),CumulativeDistance(m),DeltaDistance(m),IsStopped,CumulativeStopTime(s),HitWall,Collision,CollisionNpcID,CollisionPX,CollisionPY,CumulativeCollisionCount,CumulativeCollisionTime,ObstacleCollision,ObstacleCollisionID,ObstacleCollisionPX,ObstacleCollisionPY,CumulativeObstacleCollisionCount,CumulativeObstacleCollisionTime,BallContact,HeldBallCount,IntakeActive,ScoreCount,ShootActive,ShotAttemptCount,WhiteScoreCount");
        writer.flush();
    }

    /**
     * ロボットの現在の状態を1行書き込みます。
     */
    public void writeTimeSeriesLine(RobotState state) throws IOException {
        writer.println(String.format(Locale.US,
            "%.3f,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f,%.6f,%.6f,%b,%.6f,%.6f,%.6f,%b,%.3f,%b,%b,%d,%.6f,%.6f,%d,%.3f,%b,%s,%.6f,%.6f,%d,%.3f,%b,%d,%b,%d,%b,%d,%d",
            state.time,
            state.dateTime,
            state.x, // ボディ中心 X
            state.y, // ボディ中心 Y
            state.xA, // 車軸中心 X
            state.yA, // 車軸中心 Y
            state.heading,
            state.leftWheelSpeed,
            state.rightWheelSpeed,
            state.v,
            state.omega,
            state.accelV,
            state.accelOmega,
            state.rawInputY,
            state.rawInputX,
            state.cmdV,
            state.cmdOmega,
            state.slipFlag,
            state.slipAmount,
            state.cumulativeDistance,
            state.deltaDistance,
            state.isStopped,
            state.cumulativeStopTime,
            state.hitWallFlag,
            state.collisionFlag,
            state.collisionNpcId,
            state.collisionPointX,
            state.collisionPointY,
            state.cumulativeCollisionCount,
            state.cumulativeCollisionTime,
            state.obstacleCollisionFlag,
            state.obstacleCollisionId != null ? state.obstacleCollisionId : "",
            state.obstacleCollisionPointX,
            state.obstacleCollisionPointY,
            state.cumulativeObstacleCollisionCount,
            state.cumulativeObstacleCollisionTime,
            state.ballContactFlag,
            state.heldBallCount,
            state.intakeActive,
            state.scoreCount,
            state.shootActive,
            state.shotAttemptCount,
            state.whiteScoreCount
        ));
        writer.flush();
    }

    public void writeComment(String comment) {
        if (writer != null) {
            writer.println("# " + comment);
            writer.flush();
        }
    }

    public void close() {
        if (writer != null) {
            writer.close();
        }
    }

    /**
     * 走行サマリーログファイルに1走行分のデータを追記します。
     */
    public static synchronized void writeSummaryLine(File file, String dateTime, String runId, double totalDistance,
                                                    double avgSpeed, double maxSpeed, double maxOmega,
                                                    double totalStopTime, int stopCount, int slipCount,
                                                    double totalSlipTime, int collisionCount, double totalCollisionTime,
                                                    Long practiceSeed, RobotConfig config, double operationTime,
                                                    int obstacleCollisionCount, double totalObstacleCollisionTime,
                                                    int ballCount, Long ballSeed, int ballContactCount,
                                                    int totalCollected, int intakeCapacity,
                                                    int scoreCount, int shotAttemptCount,
                                                    double shootSuccessProb, double shootBPS,
                                                    int whiteScoreCount) throws IOException {
        boolean isNewFile = !file.exists() || file.length() == 0;

        try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
            if (isNewFile) {
                pw.println("DateTime,RunID,TotalDistance(m),AvgSpeed(m/s),MaxSpeed(m/s),MaxOmega(rad/s),TotalStopTime(s),StopCount,SlipCount,TotalSlipTime(s),CollisionCount,TotalCollisionTime(s),PracticeSeed,GearRatio,Mass(kg),MuStatic,MuKinetic,PivotD(m),TrackWidth(m),InitX(m),InitY(m),InitHeading(rad),OperationTime(s),ObstacleCollisionCount,TotalObstacleCollisionTime(s),BallCount,BallSeed,BallContactCount,TotalCollected,IntakeCapacity,ScoreCount,ShotAttemptCount,ShootSuccessProb,ShootBPS,WhiteScoreCount");
            }
            String seedStr = (practiceSeed != null) ? String.valueOf(practiceSeed) : "";
            String ballSeedStr = (ballSeed != null) ? String.valueOf(ballSeed) : "null";
            pw.println(String.format(Locale.US,
                "%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%d,%.3f,%s,%.6f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%d,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%d",
                dateTime,
                runId,
                totalDistance,
                avgSpeed,
                maxSpeed,
                maxOmega,
                totalStopTime,
                stopCount,
                slipCount,
                totalSlipTime,
                collisionCount,
                totalCollisionTime,
                seedStr,
                config.gearRatio,
                config.mass,
                config.muStatic,
                config.muKinetic,
                config.pivotOffsetD,
                config.trackWidth,
                config.initX,
                config.initY,
                config.initHeading,
                operationTime,
                obstacleCollisionCount,
                totalObstacleCollisionTime,
                ballCount,
                ballSeedStr,
                ballContactCount,
                totalCollected,
                intakeCapacity,
                scoreCount,
                shotAttemptCount,
                shootSuccessProb,
                shootBPS,
                whiteScoreCount
            ));
            pw.flush();
        }
    }
}
