package core;

/**
 * 将来の得点計算やイベント拡張用のコールバックインターフェース。
 */
public interface BallEventListener {
    /** ボールがゴールを通過したときに呼び出されます。 */
    void onBallEnterGoal(int ballId, String goalId);
    /** ボールが障害物に接触したときに呼び出されます。 */
    void onBallContact(int ballId, String obstacleId);
    /** ボールがインテーク回収されたときに呼び出されます。 */
    void onBallCollected(int ballId, int heldCount);
    /** ボールが発射されたときに呼び出されます。 */
    void onBallFired(int ballId);
}
