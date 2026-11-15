package io;

import core.RobotConfig;
import core.RobotState;
import java.util.List;

/**
 * 走行ログファイルからロードされた、リプレイ用の設定情報と状態遷移リストを保持するクラス。
 */
public class ReplayData {
    public final RobotConfig config;
    public final List<RobotState> states;

    public ReplayData(RobotConfig config, List<RobotState> states) {
        this.config = config;
        this.states = states;
    }
}
