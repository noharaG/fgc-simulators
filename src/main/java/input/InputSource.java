package input;

/**
 * ロボット操縦の入力を取得するためのインターフェース。
 */
public interface InputSource {
    /**
     * 並進（前後移動）の指令値を取得します。
     * 
     * @return 前進が +1.0、後退が -1.0 の範囲の数値
     */
    double getTranslationInput();

    /**
     * 旋回（角速度）の指令値を取得します。
     * 座標系（反時計回りが正）に合わせ、左旋回（反時計回り）が +1.0、右旋回が -1.0 の範囲の数値となります。
     * 
     * @return 旋回入力
     */
    double getRotationInput();

    /**
     * 毎フレームの更新処理（ポーリング等）を行います。
     */
    void update();

    /**
     * インテーク（回収）ボタンが押されているかを取得します。
     * Xboxコントローラーの左トリガー (LT)、キーボードの Shift キーに割り当てられます。
     * 
     * @return 押されている場合は true、そうでない場合は false
     */
    boolean isIntakePressed();

    /**
     * シューター（射出）ボタンが押されているかを取得します。
     * Xboxコントローラーの右トリガー (RT)、キーボードの Enter キーに割り当てられます。
     * 
     * @return 押されている場合は true、そうでない場合は false
     */
    boolean isShootPressed();

    /**
     * 排出（インテークからボールを前面に放出する）ボタンが押されているかを取得します。
     * Xboxコントローラーの A ボタン、キーボードの Space キーに割り当てられます。
     * 
     * @return 押されている場合は true、そうでない場合は false
     */
    boolean isEjectPressed();
}
