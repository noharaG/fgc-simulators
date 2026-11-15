package core;

import java.util.List;

/**
 * ボール物理シミュレーションを管理するインターフェース。
 * 将来のGPU移植等を考慮した拡張ポイントです。
 */
public interface IBallSimulation {
    /** シミュレーションを初期化し、ボールを放出します。 */
    void init(RobotConfig config, List<FieldObstacle> obstacles);
    /**
     * 1サブステップ(dt秒)物理を更新します。
     * 
     * @param dt タイムステップ (秒)
     * @param playerOBB プレイヤーロボットのOBB情報
     * @param npcOBBs NPCロボットのOBB情報リスト
     */
    void update(double dt, RobotOBB playerOBB, List<RobotOBB> npcOBBs);
    /** 全ボールの現在状態を取得します。 */
    List<BallState> getBallStates();
    /** ロボットとボールの累積接触回数を取得します。 */
    int getContactCount();
    /** 現在のフレームで接触があったかどうかを取得します。 */
    boolean hasContactInFrame();
    /** フレームごとの接触フラグをリセットします。 */
    void resetContactFlag();
    /**
     * インテーク（回収）処理を実行します。
     * Aボタン（スペースキー）が押され、機体前面に対象ボールがある場合、上限に達するまで回収します。
     * 
     * @param playerOBB プレイヤーロボットの現在のOBB情報
     * @param intakeActive インテークが動作中かどうか
     */
    void processIntake(RobotOBB playerOBB, boolean intakeActive);
    /** 現在回収して保持しているボール数を返します。 */
    int getHeldBallCount();
    /** これまでに回収した累積ボール数を返します。 */
    int getTotalCollectedCount();
    /** 現在インテーク動作中かどうかを返します。 */
    boolean isIntakeActive();
    
    /**
     * 保持しているボールをゴールに向けて射出します。
     * 
     * @param playerOBB プレイヤーロボットの現在のOBB情報
     * @param shootActive 射出ボタン（RT/Enter）が押されているかどうか
     * @param isBlueAlliance プレイヤーが現在青アライアンスかどうか（視点切替と連動）
     * @param dt タイムステップ (秒)
     */
    void processShoot(RobotOBB playerOBB, boolean shootActive, boolean isBlueAlliance, double dt);

    /** 現在の累積得点数を返します。 */
    int getScoreCount();

    /** 射出の累積試行数を返します。 */
    int getShotAttemptCount();

    /** 現在射出中（ビーム描画用）かどうかを返します。 */
    boolean isShootActive();

    /** 白ゴール（Fire Shield 斜辺）の累積白得点を返します。 */
    int getWhiteScoreCount();

    // --- 3v3 対戦用の複数ロボット管理メソッド ---
    void processIntake(int robotId, RobotOBB robotOBB, boolean intakeActive, int capacity);
    void processShoot(int robotId, RobotOBB robotOBB, boolean shootActive, boolean isBlueAlliance,
                      double shootRange, double shootBPS, double shootSuccessProb, double shootMissRadius,
                      boolean isContacting, double dt);
    int getHeldBallCount(int robotId);
    int getScoreCount(int robotId);
    int getShotAttemptCount(int robotId);
    int getWhiteScoreCount(int robotId);
    void checkWhiteGoalRobotContact(int robotId, RobotOBB robotOBB);
    void processEject(RobotOBB playerOBB, boolean ejectActive, double dt);
    void processEject(int robotId, RobotOBB robotOBB, boolean ejectActive, double dt);

    /** 禁止エリアワープの有効/無効を切り替えます（デフォルトは無効）。 */
    void setWarpEnabled(boolean enabled);
    /** 禁止エリアワープが有効かどうかを返します。 */
    boolean isWarpEnabled();
}
