package input;

import com.studiohartman.jamepad.ControllerManager;
import com.studiohartman.jamepad.ControllerState;

/**
 * Jamepad ライブラリ (内部で SDL2 を使用) を用いて Xbox コントローラーからの入力を取得するアダプタ。
 */
public class GamepadInputAdapter implements InputSource, AutoCloseable {

    private ControllerManager controllers;
    private double translationInput = 0.0;
    private double rotationInput = 0.0;
    private boolean connected = false;
    private boolean initialized = false;
    private boolean intakePressed = false;
    private boolean ejectPressed = false;
    private boolean shootPressed = false;

    public GamepadInputAdapter() {
        try {
            // SDLコントローラー管理の初期化
            controllers = new ControllerManager();
            controllers.initSDLGamepad();
            initialized = true;
            System.out.println("Jamepad SDL Gamepad 管理クラスの初期化に成功しました。");
        } catch (Exception e) {
            System.err.println("Jamepad の初期化に失敗しました。コントローラー入力は無効になります: " + e.getMessage());
            controllers = null;
            initialized = false;
        }
    }

    /**
     * コントローラーが接続されているかどうかを返します。
     */
    public boolean isConnected() {
        return connected && initialized;
    }

    @Override
    public double getTranslationInput() {
        return translationInput;
    }

    @Override
    public double getRotationInput() {
        return rotationInput;
    }

    @Override
    public boolean isIntakePressed() {
        return intakePressed;
    }

    @Override
    public boolean isEjectPressed() {
        return ejectPressed;
    }

    @Override
    public boolean isShootPressed() {
        return shootPressed;
    }

    @Override
    public void update() {
        if (!initialized || controllers == null) {
            connected = false;
            translationInput = 0.0;
            rotationInput = 0.0;
            intakePressed = false;
            ejectPressed = false;
            shootPressed = false;
            return;
        }

        try {
            // SDLコントローラー状態の更新
            controllers.update();
            ControllerState state = controllers.getState(0); // 1基目のコントローラー

            connected = state.isConnected;
            if (connected) {
                // 一般的なコントローラーの物理Y軸は上がマイナスになるため反転させる
                translationInput = -state.leftStickY;

                // 右スティックX: 通常は右に倒すと正(+1.0)、左に倒すと負(-1.0)
                // 反時計回り（左旋回）を正とするため、符号を反転させる (左に倒したときに正になるようにする)
                rotationInput = -state.rightStickX;
                
                // LT (左トリガー) で回収 - 0.5 閾値で判定
                intakePressed = state.leftTrigger > 0.5;

                // Aボタンで排出
                ejectPressed = state.a;

                // 右トリガー (RT) - 通常アナログ値 0.0 ~ 1.0 なので 0.5 閾値で判定
                shootPressed = state.rightTrigger > 0.5;
            } else {
                translationInput = 0.0;
                rotationInput = 0.0;
                intakePressed = false;
                ejectPressed = false;
                shootPressed = false;
            }
        } catch (Exception e) {
            // ポーリングエラー時の保護
            connected = false;
            translationInput = 0.0;
            rotationInput = 0.0;
            intakePressed = false;
            ejectPressed = false;
            shootPressed = false;
        }
    }

    @Override
    public void close() {
        if (initialized && controllers != null) {
            try {
                controllers.quitSDLGamepad();
                System.out.println("Jamepad SDL Gamepad 管理クラスをクローズしました。");
            } catch (Exception e) {
                System.err.println("Jamepad のクリーンアップ中にエラーが発生しました: " + e.getMessage());
            } finally {
                controllers = null;
                initialized = false;
            }
        }
    }
}
