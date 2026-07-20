package practice;

import java.util.List;
import java.util.Random;

/**
 * NPCが挙動の更新判断を行うために参照するコンテキスト情報。
 * フィールドサイズ、プレイヤー情報、自身以外のNPCの状態、およびシード管理された乱数ジェネレータを含みます。
 */
public class PracticeContext {
    /** フィールドの一辺のサイズ (m) */
    public final double fieldSize;
    /** プレイヤー機体の中心X座標 (m) */
    public final double playerX;
    /** プレイヤー機体の中心Y座標 (m) */
    public final double playerY;
    /** プレイヤー機体の向き (ラジアン) */
    public final double playerHeading;
    /** 自身を除く他すべてのNPCの現在の状態のリスト */
    public final List<NpcState> otherNpcs;
    /** シード固定対応された乱数生成器 */
    public final Random random;
    /** フィールド上の未回収ボールの状態リスト（練習モードのボール回収追従用。無い場合は空） */
    public final List<core.BallState> balls;

    /**
     * @param fieldSize フィールド一辺のサイズ (m)
     * @param playerX プレイヤーX
     * @param playerY プレイヤーY
     * @param playerHeading プレイヤーの向き
     * @param otherNpcs 他のNPCのリスト
     * @param random 乱数生成器
     */
    public PracticeContext(double fieldSize, double playerX, double playerY, double playerHeading,
                           List<NpcState> otherNpcs, Random random) {
        this(fieldSize, playerX, playerY, playerHeading, otherNpcs, random,
             java.util.Collections.emptyList());
    }

    /**
     * ボール状態リストを含む完全版コンストラクタ。
     * @param balls フィールド上のボール状態リスト
     */
    public PracticeContext(double fieldSize, double playerX, double playerY, double playerHeading,
                           List<NpcState> otherNpcs, Random random, List<core.BallState> balls) {
        this.fieldSize = fieldSize;
        this.playerX = playerX;
        this.playerY = playerY;
        this.playerHeading = playerHeading;
        this.otherNpcs = otherNpcs;
        this.random = random;
        this.balls = (balls != null) ? balls : java.util.Collections.emptyList();
    }
}
