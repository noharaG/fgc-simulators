package practice;

/**
 * NPCロボットの意思決定（挙動）を定義するインターフェース。
 * 新しい挙動を追加する際は、このインターフェースを実装したクラスを作成します。
 */
public interface RobotBehavior {
    /**
     * 現在の状態と環境コンテキストから、次のステップでの移動指令（DriveCommand）を決定します。
     *
     * @param self 更新対象のNPCの状態
     * @param ctx フィールドサイズや他機体の情報を含む環境コンテキスト
     * @param dt タイムステップ（秒）
     * @return 決定された並進速度および角速度の指令値
     */
    DriveCommand update(NpcState self, PracticeContext ctx, double dt);
}
