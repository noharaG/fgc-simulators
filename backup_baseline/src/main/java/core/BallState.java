package core;

/**
 * ボール1個の状態（位置、速度、ID）を表すクラス。
 */
public class BallState {
    public final int id;
    public double x;
    public double y;
    public double vx;
    public double vy;
    
    // ロボットとの接触回数を重複カウントしないための衝突フラグ
    public boolean wasCollidingWithRobot = false;
    
    // ボールが回収されたことを示すフラグ (trueの場合、物理シミュレーションおよび描画から除外される)
    public boolean collected = false;
    
    // ボールがゴールインして消滅したことを示すフラグ
    public boolean scored = false;

    // 禁止エリア（フィールド外・障害物内部）に滞在している累積時間（秒）
    public double forbiddenAreaTimer = 0.0;

    public BallState(int id, double x, double y, double vx, double vy) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
    }
}
