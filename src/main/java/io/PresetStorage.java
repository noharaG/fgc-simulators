package io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import core.CompetitionRobot;
import core.RobotConfig;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * プリセットおよびプレイヤー機体設定の読み書きを行うストレージクラス。
 */
public class PresetStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PRESETS_PATH = "presets/robot_presets.json";
    private static final String PLAYER_CONFIG_PATH = "config/player_config.json";

    /**
     * プリセット保存用のシリアライズ可能なデータ構造
     */
    public static class RobotPresetData {
        public String driveType;
        public String behaviorType;
        public int intakeCapacity;
        public double shootSuccessProb;
        public double shootRange;
        public double shootBPS;
        public double disturbWeight;
        public RobotConfig config;

        public RobotPresetData() {}

        public RobotPresetData(CompetitionRobot robot) {
            this.driveType = robot.getDriveType().name();
            this.behaviorType = robot.getBehaviorType().name();
            this.intakeCapacity = robot.getIntakeCapacity();
            this.shootSuccessProb = robot.getShootSuccessProb();
            this.shootRange = robot.getShootRange();
            this.shootBPS = robot.getShootBPS();
            this.disturbWeight = robot.getDisturbWeight();
            this.config = robot.getConfig();
        }

        public void applyTo(CompetitionRobot robot) {
            try {
                robot.setDriveType(CompetitionRobot.DriveType.valueOf(driveType));
                robot.setBehaviorType(CompetitionRobot.BehaviorType.valueOf(behaviorType));
            } catch (Exception ignored) {}
            robot.setIntakeCapacity(intakeCapacity);
            robot.setShootSuccessProb(shootSuccessProb);
            robot.setShootRange(shootRange);
            robot.setShootBPS(shootBPS);
            robot.setDisturbWeight(disturbWeight);
            if (config != null) {
                robot.setConfig(config);
            }
        }
    }

    /**
     * プレイヤー設定保存用のシリアライズ可能なデータ構造
     */
    public static class PlayerConfigData {
        public String alliance; // RED or BLUE
        public RobotPresetData preset;

        public PlayerConfigData() {}

        public PlayerConfigData(String alliance, CompetitionRobot robot) {
            this.alliance = alliance;
            this.preset = new RobotPresetData(robot);
        }
    }

    /**
     * ロボット設定プリセットを保存します。
     */
    public static void savePreset(String name, CompetitionRobot robot) throws IOException {
        Map<String, RobotPresetData> presets = loadPresets();
        presets.put(name, new RobotPresetData(robot));

        File file = new File(PRESETS_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(presets, writer);
        }
    }

    /**
     * 全てのロボット設定プリセットをロードします。
     */
    public static Map<String, RobotPresetData> loadPresets() {
        File file = new File(PRESETS_PATH);
        if (!file.exists()) {
            return new HashMap<>();
        }

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, RobotPresetData>>() {}.getType();
            Map<String, RobotPresetData> map = GSON.fromJson(reader, type);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            System.err.println("プリセットの読み込みに失敗しました。新規作成します: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * プレイヤー設定を保存します。
     */
    public static void savePlayerConfig(String alliance, CompetitionRobot robot) throws IOException {
        PlayerConfigData data = new PlayerConfigData(alliance, robot);

        File file = new File(PLAYER_CONFIG_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(data, writer);
        }
    }

    /**
     * プレイヤー設定をロードします。
     */
    public static PlayerConfigData loadPlayerConfig() {
        File file = new File(PLAYER_CONFIG_PATH);
        if (!file.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, PlayerConfigData.class);
        } catch (Exception e) {
            System.err.println("プレイヤー設定の読み込みに失敗しました: " + e.getMessage());
            return null;
        }
    }
}
