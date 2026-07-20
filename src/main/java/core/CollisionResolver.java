package core;

import java.util.List;
import java.util.Collections;

/**
 * 練習モードなど、外部モジュールでの挙動更新と衝突解消処理をフックするためのインターフェース。
 * 循環参照を避けるため、coreパッケージ内に配置します。
 */
public interface CollisionResolver {
    /**
     * NPC等の自律挙動を更新します（運動積分前）。
     */
    void updateBehaviors(SimulationEngine engine, double dt);

    /**
     * 衝突判定および押し戻し処理を実行します（運動積分後）。
     */
    void resolveCollisions(SimulationEngine engine, double dt);

    /**
     * NPCロボットの現在のOBB情報のリストを返します。
     * デフォルトでは空リストを返します。
     */
    default List<RobotOBB> getNpcOBBs() {
        return Collections.emptyList();
    }
}
