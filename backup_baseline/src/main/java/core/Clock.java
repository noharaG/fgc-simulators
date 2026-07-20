package core;

/**
 * シミュレーション内の経過時間を測定するためのクロックインターフェース。
 */
public interface Clock {
    /**
     * シミュレーション開始からの経過時間（秒）を返します。
     * @return 経過時間（秒）
     */
    double getElapsedTime();
}
