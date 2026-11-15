package input;

import java.awt.KeyEventDispatcher;
import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;
import javax.swing.text.JTextComponent;

/**
 * キーボード入力 (WASD または 矢印キー) をロボットの操縦入力に変換するアダプタ。
 * キーボード特有のデジタルなオン・オフ入力を、適度な慣性（ローパスフィルタ）で滑らかなアナログ値に変換します。
 * グローバルな KeyEventDispatcher として動作し、フォーカスがいずれのコンポーネントにあっても入力を取得できます。
 */
public class KeyboardInputAdapter implements InputSource, KeyEventDispatcher {

    private final Set<Integer> pressedKeys = new HashSet<>();

    private double currentTranslation = 0.0;
    private double currentRotation = 0.0;

    // 入力追従の速さ (値が大きいほどクイックに反応する)
    private static final double SMOOTHING_FACTOR = 0.15;

    @Override
    public double getTranslationInput() {
        return currentTranslation;
    }

    @Override
    public double getRotationInput() {
        return currentRotation;
    }

    @Override
    public void update() {
        double targetTranslation = 0.0;
        double targetRotation = 0.0;

        synchronized (this) {
            // 前進 / 後退
            if (pressedKeys.contains(KeyEvent.VK_W) || pressedKeys.contains(KeyEvent.VK_UP)) {
                targetTranslation += 1.0;
            }
            if (pressedKeys.contains(KeyEvent.VK_S) || pressedKeys.contains(KeyEvent.VK_DOWN)) {
                targetTranslation -= 1.0;
            }

            // 旋回 (A/左矢印は反時計回り=左旋回で正、D/右矢印は時計回り=右旋回で負)
            if (pressedKeys.contains(KeyEvent.VK_A) || pressedKeys.contains(KeyEvent.VK_LEFT)) {
                targetRotation += 1.0;
            }
            if (pressedKeys.contains(KeyEvent.VK_D) || pressedKeys.contains(KeyEvent.VK_RIGHT)) {
                targetRotation -= 1.0;
            }
        }

        // ローパスフィルタによる入力の平滑化 (デジタルキーボードの操作性を向上)
        currentTranslation += (targetTranslation - currentTranslation) * SMOOTHING_FACTOR;
        currentRotation += (targetRotation - currentRotation) * SMOOTHING_FACTOR;

        // 微小な値は0にする
        if (Math.abs(currentTranslation) < 0.001) currentTranslation = 0.0;
        if (Math.abs(currentRotation) < 0.001) currentRotation = 0.0;
    }

    @Override
    public boolean isIntakePressed() {
        synchronized (this) {
            return pressedKeys.contains(KeyEvent.VK_SHIFT);
        }
    }

    @Override
    public boolean isEjectPressed() {
        synchronized (this) {
            return pressedKeys.contains(KeyEvent.VK_SPACE);
        }
    }

    @Override
    public boolean isShootPressed() {
        synchronized (this) {
            return pressedKeys.contains(KeyEvent.VK_ENTER);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // テキスト入力中のキー操作は操縦指示に反映させない
        if (e.getSource() instanceof JTextComponent) {
            return false;
        }

        synchronized (this) {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                pressedKeys.add(e.getKeyCode());
            } else if (e.getID() == KeyEvent.KEY_RELEASED) {
                pressedKeys.remove(e.getKeyCode());
            }
        }
        return false; // イベントを後続のコンポーネント（フォーカス制御など）にも流す
    }
}
