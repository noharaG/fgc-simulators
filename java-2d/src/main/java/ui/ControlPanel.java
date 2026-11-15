package ui;

import core.RobotConfig;
import core.RobotState;
import io.LogReader;
import io.ReplayData;
import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;
import javax.swing.border.TitledBorder;

/**
 * シミュレータのコントロール・設定・リプレイを制御するUIパネル。
 */
public class ControlPanel extends JPanel {

    public interface ControlListener {
        void onConfigApplied(RobotConfig newConfig);
        void onSimulationReset();
        void onSimulationStopRequested();
        void onReplayStateChanged(boolean playing);
        void onReplayIndexChanged(int index);
        void onReplaySpeedChanged(double speed);
        void onPracticeModeToggled(boolean enabled, Long seed);
        void onViewAngleChanged(boolean isBlueView); // 視点切替イベント
    }

    private ControlListener listener;

    // --- パラメータ設定コンポーネント ---
    private JSlider sliderMass;
    private JComboBox<String> comboGearRatio;
    private JSlider sliderTrackWidth;
    private JSlider sliderPivotD;
    private JSlider sliderMuStatic;
    private JSlider sliderMuKinetic;
    private JSlider sliderInitX;
    private JSlider sliderInitY;
    private JSlider sliderInitHeading;
    private JCheckBox chkWallAlign;
    private JSlider sliderExpoDrive;
    private JSlider sliderExpoTurn;
    private JSlider sliderBrakeGain;
    private JCheckBox chkBrakeEnabled;
    private JButton btnApplyConfig;
    private JButton btnResetSim;
    private JButton btnStopSim;
    private JComboBox<String> comboViewMode; // 視点切替

    // --- ボール物理設定コンポーネント ---
    private JSlider sliderBallRestitution;
    private JSlider sliderBallFriction;
    private JTextField txtBallSeed;
    private JSlider sliderBallSubSteps;
    private JSlider sliderBallEmitRadius;
    private JSlider sliderBallRollingDamping;
    private JSlider sliderIntakeCapacity;
    private JSlider sliderShootRange;
    private JSlider sliderShootSuccessProb;
    private JSlider sliderShootBPS;
    private JSlider sliderWhiteGoalSuccessProb;
    private JSlider sliderWhiteGoalMissRadius;

    // --- 練習モード設定コンポーネント ---
    private JCheckBox chkPracticeMode;
    private JTextField txtPracticeSeed;

    // --- ステータス表示コンポーネント ---
    private JLabel lblTimer;
    private JLabel lblPosition;
    private JLabel lblVelocity;
    private JLabel lblGamepadStatus;
    private JPanel panelSlipIndicator;
    private JLabel lblSlipText;
    private JLabel lblBallContact;
    private JLabel lblIntakeStatus;
    private JLabel lblScoreStatus;

    // --- リプレイ制御コンポーネント ---
    private JButton btnLoadLog;
    private JButton btnPlayPauseReplay;
    private JSlider sliderReplayScrubber;
    private JComboBox<Double> comboReplaySpeed;
    private JLabel lblReplayTime;

    private boolean isReplayPlaying = false;
    private List<RobotState> loadedReplayStates = null;
    private RobotConfig loadedReplayConfig = null; // リプレイ用メタデータからロードした設定
    private RobotConfig currentConfig = null; // 実行中の機体設定

    public ControlPanel() {
        setBackground(new Color(33, 37, 43)); // ダークテーマ背景
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(450, 600));

        JPanel mainContainer = new JPanel();
        mainContainer.setLayout(new BoxLayout(mainContainer, BoxLayout.Y_AXIS));
        mainContainer.setBackground(new Color(33, 37, 43));

        mainContainer.add(createConfigPanel());
        mainContainer.add(Box.createVerticalStrut(10));
        mainContainer.add(createBallPanel()); // ボール物理設定を追加
        mainContainer.add(Box.createVerticalStrut(10));
        mainContainer.add(createDisplayPanel()); // 表示設定を新規追加
        mainContainer.add(Box.createVerticalStrut(10));
        mainContainer.add(createPracticePanel());
        mainContainer.add(Box.createVerticalStrut(10));
        mainContainer.add(createStatusPanel());
        mainContainer.add(Box.createVerticalStrut(10));
        mainContainer.add(createReplayPanel());

        add(new JScrollPane(mainContainer), BorderLayout.CENTER);
    }

    public void setControlListener(ControlListener listener) {
        this.listener = listener;
    }

    private JPanel createConfigPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        setupDarkTitledBorder(panel, "機体・環境パラメータ設定");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.weightx = 1.0;

        int row = 0;

        // 1. 質量
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("質量 (kg, 挙動未反映):"), gbc);
        gbc.gridx = 1;
        sliderMass = new JSlider(120, 150, 135);
        setupDarkSlider(sliderMass);
        panel.add(sliderMass, gbc);
        row++;

        // 2. ギア比
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("ギア比 (標準):"), gbc);
        gbc.gridx = 1;
        comboGearRatio = new JComboBox<>(new String[]{"1/12 (高速・低トルク)", "1/15 (バランス)", "1/20 (低速・高トルク)"});
        comboGearRatio.setSelectedIndex(1);
        setupDarkComboBox(comboGearRatio);
        panel.add(comboGearRatio, gbc);
        row++;

        // 3. トレッド幅 w
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("トレッド幅 w (m):"), gbc);
        gbc.gridx = 1;
        sliderTrackWidth = new JSlider(30, 60, 45); // 0.30m ~ 0.60m
        setupDarkSlider(sliderTrackWidth);
        panel.add(sliderTrackWidth, gbc);
        row++;

        // 4. ピボット d
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("ピボット d (m):"), gbc);
        gbc.gridx = 1;
        sliderPivotD = new JSlider(-25, 25, 0); // -0.25m ~ 0.25m
        setupDarkSlider(sliderPivotD);
        panel.add(sliderPivotD, gbc);
        row++;

        // 5. 静止摩擦 muStatic
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("静止摩擦 (μS):"), gbc);
        gbc.gridx = 1;
        sliderMuStatic = new JSlider(40, 120, 80); // 0.40 ~ 1.20
        setupDarkSlider(sliderMuStatic);
        panel.add(sliderMuStatic, gbc);
        row++;

        // 6. 動摩擦 muKinetic
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("動摩擦 (μK):"), gbc);
        gbc.gridx = 1;
        sliderMuKinetic = new JSlider(30, 110, 70); // 0.30 ~ 1.10
        setupDarkSlider(sliderMuKinetic);
        panel.add(sliderMuKinetic, gbc);
        row++;

        // 7. 初期位置 X
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("初期位置 X (m):"), gbc);
        gbc.gridx = 1;
        sliderInitX = new JSlider(5, 65, 35);
        setupDarkSlider(sliderInitX);
        panel.add(sliderInitX, gbc);
        row++;

        // 8. 初期位置 Y
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("初期位置 Y (m):"), gbc);
        gbc.gridx = 1;
        sliderInitY = new JSlider(5, 65, 35);
        setupDarkSlider(sliderInitY);
        panel.add(sliderInitY, gbc);
        row++;

        // 9. 初期向き
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("初期向き (度):"), gbc);
        gbc.gridx = 1;
        sliderInitHeading = new JSlider(-180, 180, 90);
        setupDarkSlider(sliderInitHeading);
        panel.add(sliderInitHeading, gbc);
        row++;

        // 10. 壁整列挙動 (トグル)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("壁整列挙動:"), gbc);
        gbc.gridx = 1;
        chkWallAlign = new JCheckBox("有効 (壁密着回転)", true);
        chkWallAlign.setBackground(new Color(33, 37, 43));
        chkWallAlign.setForeground(new Color(200, 210, 220));
        chkWallAlign.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        panel.add(chkWallAlign, gbc);
        row++;

        // 11. expo前後
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("expo前後 (0-1.0):"), gbc);
        gbc.gridx = 1;
        sliderExpoDrive = new JSlider(0, 100, 100);
        setupDarkSlider(sliderExpoDrive);
        panel.add(sliderExpoDrive, gbc);
        row++;

        // 12. expo旋回
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("expo旋回 (0-1.0):"), gbc);
        gbc.gridx = 1;
        sliderExpoTurn = new JSlider(0, 100, 100);
        setupDarkSlider(sliderExpoTurn);
        panel.add(sliderExpoTurn, gbc);
        row++;

        // 13. ブレーキ制動力
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("ブレーキ制動力 (1/s):"), gbc);
        gbc.gridx = 1;
        sliderBrakeGain = new JSlider(0, 250, 100); // 0.0 ~ 25.0
        setupDarkSlider(sliderBrakeGain);
        panel.add(sliderBrakeGain, gbc);
        row++;

        // 14. アクティブブレーキトグル
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("アクティブブレーキ:"), gbc);
        gbc.gridx = 1;
        chkBrakeEnabled = new JCheckBox("有効 (ゼロ横切り)", true);
        chkBrakeEnabled.setBackground(new Color(33, 37, 43));
        chkBrakeEnabled.setForeground(new Color(200, 210, 220));
        chkBrakeEnabled.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        panel.add(chkBrakeEnabled, gbc);
        row++;

        // ボタン群
        gbc.gridx = 0; gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(8, 4, 4, 4);

        JPanel btnPanel = new JPanel(new GridLayout(1, 3, 5, 5));
        btnPanel.setBackground(new Color(33, 37, 43));

        btnApplyConfig = createStyledButton("設定適用", new Color(79, 110, 194));
        btnApplyConfig.addActionListener(e -> applyConfigValues());
        btnPanel.add(btnApplyConfig);

        btnResetSim = createStyledButton("位置リセット", new Color(97, 218, 251));
        btnResetSim.addActionListener(e -> {
            if (listener != null) listener.onSimulationReset();
        });
        btnPanel.add(btnResetSim);

        btnStopSim = createStyledButton("走行停止", new Color(219, 68, 85));
        btnStopSim.addActionListener(e -> {
            if (listener != null) listener.onSimulationStopRequested();
        });
        btnStopSim.setEnabled(false);
        btnPanel.add(btnStopSim);

        panel.add(btnPanel, gbc);

        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new GridLayout(9, 1, 3, 3));
        setupDarkTitledBorder(panel, "走行ステータス");

        lblTimer = createStatusLabel("タイマー: 00:00.00 / 02:30.00 (待機中)");
        panel.add(lblTimer);

        lblPosition = createStatusLabel("座標: x = --.--- m, y = --.--- m");
        panel.add(lblPosition);

        lblVelocity = createStatusLabel("速度: v = 0.00 m/s, ω = 0.0°/s");
        panel.add(lblVelocity);

        lblBallContact = createStatusLabel("ボール接触: 0 回");
        panel.add(lblBallContact);

        lblIntakeStatus = createStatusLabel("ボール保持: 0 / 10 個");
        panel.add(lblIntakeStatus);

        lblScoreStatus = createStatusLabel("得点: 0, 射出: 試行 0 / 成功 0");
        panel.add(lblScoreStatus);

        panelSlipIndicator = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        panelSlipIndicator.setBackground(new Color(33, 37, 43));
        lblSlipText = new JLabel("タイヤグリップ: 安定");
        lblSlipText.setForeground(new Color(150, 160, 170));
        lblSlipText.setFont(new Font("MS Gothic", Font.BOLD, 12));
        
        JPanel light = new JPanel();
        light.setPreferredSize(new Dimension(12, 12));
        light.setBackground(Color.DARK_GRAY);
        panelSlipIndicator.add(light);
        panelSlipIndicator.add(lblSlipText);
        panel.add(panelSlipIndicator);

        lblGamepadStatus = createStatusLabel("操作: ゲームパッド未接続 (キーボード有効)");
        panel.add(lblGamepadStatus);

        return panel;
    }

    private JPanel createReplayPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        setupDarkTitledBorder(panel, "過去ログ再生 (リプレイ)");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0;
        btnLoadLog = createStyledButton("CSV過去ログを読み込む", new Color(240, 173, 78));
        btnLoadLog.addActionListener(e -> loadLogFile());
        panel.add(btnLoadLog, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        btnPlayPauseReplay = createStyledButton("再生", new Color(92, 184, 92));
        btnPlayPauseReplay.setEnabled(false);
        btnPlayPauseReplay.addActionListener(e -> toggleReplayPlayPause());
        panel.add(btnPlayPauseReplay, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        JPanel speedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        speedPanel.setBackground(new Color(33, 37, 43));
        speedPanel.add(createDarkLabel("再生速度:"));
        comboReplaySpeed = new JComboBox<>(new Double[]{0.25, 0.5, 1.0, 2.0, 4.0});
        comboReplaySpeed.setSelectedItem(1.0);
        setupDarkComboBox(comboReplaySpeed);
        comboReplaySpeed.addActionListener(e -> {
            if (listener != null) {
                listener.onReplaySpeedChanged((Double) comboReplaySpeed.getSelectedItem());
            }
        });
        speedPanel.add(comboReplaySpeed);
        panel.add(speedPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        sliderReplayScrubber = new JSlider(0, 100, 0);
        setupDarkSlider(sliderReplayScrubber);
        sliderReplayScrubber.setEnabled(false);
        sliderReplayScrubber.addChangeListener(e -> {
            if (sliderReplayScrubber.getValueIsAdjusting() && listener != null) {
                listener.onReplayIndexChanged(sliderReplayScrubber.getValue());
            }
        });
        panel.add(sliderReplayScrubber, gbc);

        gbc.gridx = 0; gbc.gridy = 4;
        lblReplayTime = createStatusLabel("再生時間: --:--.--");
        panel.add(lblReplayTime, gbc);

        return panel;
    }

    public void updateConfigUI(RobotConfig config) {
        this.currentConfig = config;
        sliderMass.setValue((int)(config.mass * 10));
        sliderTrackWidth.setValue((int)(config.trackWidth * 100));
        sliderPivotD.setValue((int)(config.pivotOffsetD * 100));
        sliderMuStatic.setValue((int)(config.muStatic * 100));
        sliderMuKinetic.setValue((int)(config.muKinetic * 100));
        sliderInitX.setValue((int)(config.initX * 10));
        sliderInitY.setValue((int)(config.initY * 10));
        sliderInitHeading.setValue((int)Math.toDegrees(config.initHeading));
        
        if (chkWallAlign != null) {
            chkWallAlign.setSelected(config.wallAlignEnabled);
        }
        if (sliderExpoDrive != null) {
            sliderExpoDrive.setValue((int)(config.expoDrive * 100));
        }
        if (sliderExpoTurn != null) {
            sliderExpoTurn.setValue((int)(config.expoTurn * 100));
        }
        if (sliderBrakeGain != null) {
            sliderBrakeGain.setValue((int)(config.brakeGain * 10));
        }
        if (chkBrakeEnabled != null) {
            chkBrakeEnabled.setSelected(config.brakeEnabled);
        }

        if (sliderBallRestitution != null) {
            sliderBallRestitution.setValue((int)(config.ballRestitution * 100.0));
        }
        if (sliderBallFriction != null) {
            sliderBallFriction.setValue((int)(config.ballFriction * 100.0));
        }
        if (txtBallSeed != null) {
            txtBallSeed.setText(config.ballSeed != null ? String.valueOf(config.ballSeed) : "");
        }
        if (sliderBallSubSteps != null) {
            sliderBallSubSteps.setValue(config.ballSubSteps);
        }
        if (sliderBallEmitRadius != null) {
            sliderBallEmitRadius.setValue((int)(config.ballEmitRadius * 100.0));
        }
        if (sliderBallRollingDamping != null) {
            sliderBallRollingDamping.setValue((int)(config.ballRollingDamping * 10.0));
        }
        if (sliderIntakeCapacity != null) {
            sliderIntakeCapacity.setValue(config.intakeCapacity);
        }
        if (sliderShootRange != null) {
            sliderShootRange.setValue((int)(config.shootRange * 10.0));
        }
        if (sliderShootSuccessProb != null) {
            sliderShootSuccessProb.setValue((int)(config.shootSuccessProb * 100.0));
        }
        if (sliderShootBPS != null) {
            sliderShootBPS.setValue((int)(config.shootBPS * 10.0));
        }
        if (sliderWhiteGoalSuccessProb != null) {
            sliderWhiteGoalSuccessProb.setValue((int)(config.whiteGoalSuccessProb * 100.0));
        }
        if (sliderWhiteGoalMissRadius != null) {
            sliderWhiteGoalMissRadius.setValue((int)(config.whiteGoalMissRadius * 10.0));
        }

        double ratio = config.gearRatio;
        if (Math.abs(ratio - 1.0/12.0) < 0.001) {
            comboGearRatio.setSelectedIndex(0);
        } else if (Math.abs(ratio - 1.0/15.0) < 0.001) {
            comboGearRatio.setSelectedIndex(1);
        } else if (Math.abs(ratio - 1.0/20.0) < 0.001) {
            comboGearRatio.setSelectedIndex(2);
        }
    }

    public void updateStatus(RobotState state, boolean gamepadConnected, boolean isRunning, double simTime) {
        btnStopSim.setEnabled(isRunning);

        if (isRunning) {
            int mins = (int)(simTime / 60);
            double secs = simTime % 60;
            lblTimer.setText(String.format("タイマー: %02d:%05.2f / 02:30.00 (走行中/RUNNING)", mins, secs));
            lblTimer.setForeground(new Color(255, 100, 100));
        } else if (simTime >= 150.0) {
            lblTimer.setText("タイマー: 02:30.00 / 02:30.00 (強制停止中/FINISHED)");
            lblTimer.setForeground(new Color(255, 80, 80));
        } else {
            lblTimer.setText("タイマー: 00:00.00 / 02:30.00 (準備中/SETUP)");
            lblTimer.setForeground(new Color(97, 218, 251));
        }

        if (gamepadConnected) {
            lblGamepadStatus.setText("操作: ゲームパッド接続済 (Xbox)");
            lblGamepadStatus.setForeground(new Color(92, 184, 92));
        } else {
            lblGamepadStatus.setText("操作: ゲームパッド未接続 (キーボード有効)");
            lblGamepadStatus.setForeground(new Color(240, 173, 78));
        }

        if (state != null) {
            lblPosition.setText(String.format(
                "座標(G): x = %.3f m, y = %.3f m, θ = %.1f°%s",
                state.x, state.y, Math.toDegrees(state.heading),
                state.hitWallFlag ? " [壁接触]" : ""
            ));
            lblVelocity.setText(String.format("速度: v = %.2f m/s, ω = %.1f°/s", state.v, Math.toDegrees(state.omega)));
            
            if (lblBallContact != null) {
                lblBallContact.setText(String.format("ボール接触: %d 回%s", state.cumulativeBallContactCount, state.ballContactFlag ? " [接触中]" : ""));
                if (state.ballContactFlag) {
                    lblBallContact.setForeground(new Color(255, 165, 0));
                } else {
                    lblBallContact.setForeground(new Color(180, 190, 200));
                }
            }

            if (lblIntakeStatus != null) {
                int capacity = (currentConfig != null) ? currentConfig.intakeCapacity : 10;
                lblIntakeStatus.setText(String.format("ボール保持: %d / %d 個%s", state.heldBallCount, capacity, state.intakeActive ? " [回収中]" : ""));
                if (state.intakeActive) {
                    lblIntakeStatus.setForeground(new Color(97, 218, 251));
                } else {
                    lblIntakeStatus.setForeground(new Color(180, 190, 200));
                }
            }

            if (lblScoreStatus != null) {
                lblScoreStatus.setText(String.format("得点: 赤青 %d / 白 %d  (射出: 試行 %d / 成功 %d)%s",
                    state.scoreCount, state.whiteScoreCount, state.shotAttemptCount, state.scoreCount, state.shootActive ? " [射出中]" : ""));
                if (state.shootActive) {
                    lblScoreStatus.setForeground(new Color(255, 100, 100));
                } else if (state.scoreCount > 0 || state.whiteScoreCount > 0) {
                    lblScoreStatus.setForeground(new Color(92, 184, 92));
                } else {
                    lblScoreStatus.setForeground(new Color(180, 190, 200));
                }
            }

            JPanel light = (JPanel) panelSlipIndicator.getComponent(0);
            if (state.slipFlag) {
                light.setBackground(new Color(255, 80, 80));
                lblSlipText.setText(String.format("タイヤグリップ: スリップ中! (%.2f m/s)", state.slipAmount));
                lblSlipText.setForeground(new Color(255, 80, 80));
            } else {
                light.setBackground(new Color(50, 150, 50));
                lblSlipText.setText("タイヤグリップ: 安定");
                lblSlipText.setForeground(new Color(92, 184, 92));
            }
        }
    }

    public void updateReplayStatus(RobotState state, int currentIndex, int maxIndex) {
        if (state == null) return;
        
        sliderReplayScrubber.setMaximum(maxIndex);
        if (!sliderReplayScrubber.getValueIsAdjusting()) {
            sliderReplayScrubber.setValue(currentIndex);
        }

        int mins = (int)(state.time / 60);
        double secs = state.time % 60;
        lblReplayTime.setText(String.format("再生時間: %02d:%05.2f (フレーム: %d/%d)", mins, secs, currentIndex, maxIndex));

        lblPosition.setText(String.format(
            "座標(G): x = %.3f m, y = %.3f m, θ = %.1f°%s",
            state.x, state.y, Math.toDegrees(state.heading),
            state.hitWallFlag ? " [壁接触]" : ""
        ));
        lblVelocity.setText(String.format("速度: v = %.2f m/s, ω = %.1f°/s", state.v, Math.toDegrees(state.omega)));

        if (lblIntakeStatus != null) {
            int capacity = (loadedReplayConfig != null) ? loadedReplayConfig.intakeCapacity : 10;
            lblIntakeStatus.setText(String.format("ボール保持: %d / %d 個%s", state.heldBallCount, capacity, state.intakeActive ? " [回収中]" : ""));
            if (state.intakeActive) {
                lblIntakeStatus.setForeground(new Color(97, 218, 251));
            } else {
                lblIntakeStatus.setForeground(new Color(180, 190, 200));
            }
        }

        if (lblScoreStatus != null) {
            lblScoreStatus.setText(String.format("得点: 赤青 %d / 白 %d  (射出: 試行 %d / 成功 %d)%s",
                state.scoreCount, state.whiteScoreCount, state.shotAttemptCount, state.scoreCount, state.shootActive ? " [射出中]" : ""));
            if (state.shootActive) {
                lblScoreStatus.setForeground(new Color(255, 100, 100));
            } else if (state.scoreCount > 0 || state.whiteScoreCount > 0) {
                lblScoreStatus.setForeground(new Color(92, 184, 92));
            } else {
                lblScoreStatus.setForeground(new Color(180, 190, 200));
            }
        }

        JPanel light = (JPanel) panelSlipIndicator.getComponent(0);
        if (state.slipFlag) {
            light.setBackground(new Color(255, 80, 80));
            lblSlipText.setText(String.format("リプレイ: スリップ中 (%.2f m/s)", state.slipAmount));
            lblSlipText.setForeground(new Color(255, 80, 80));
        } else {
            light.setBackground(new Color(100, 100, 100));
            lblSlipText.setText("リプレイ: グリップ安定");
            lblSlipText.setForeground(new Color(150, 160, 170));
        }
    }

    private void applyConfigValues() {
        double mass = sliderMass.getValue() / 10.0;
        double trackWidth = sliderTrackWidth.getValue() / 100.0;
        double pivotD = sliderPivotD.getValue() / 100.0;
        double muStatic = sliderMuStatic.getValue() / 100.0;
        double muKinetic = sliderMuKinetic.getValue() / 100.0;
        double initX = sliderInitX.getValue() / 10.0;
        double initY = sliderInitY.getValue() / 10.0;
        double initHeading = Math.toRadians(sliderInitHeading.getValue());

        double gearRatio = 1.0 / 15.0;
        int gearIndex = comboGearRatio.getSelectedIndex();
        if (gearIndex == 0) gearRatio = 1.0 / 12.0;
        else if (gearIndex == 2) gearRatio = 1.0 / 20.0;

        RobotConfig newConfig = new RobotConfig(
            mass, gearRatio, 0.09, trackWidth, pivotD, muStatic, muKinetic, initX, initY, initHeading, null
        );
        newConfig.wallAlignEnabled = chkWallAlign.isSelected();
        newConfig.expoDrive = sliderExpoDrive.getValue() / 100.0;
        newConfig.expoTurn = sliderExpoTurn.getValue() / 100.0;
        newConfig.brakeGain = sliderBrakeGain.getValue() / 10.0;
        newConfig.brakeEnabled = chkBrakeEnabled.isSelected();
        newConfig.brakeThreshold = 0.05;
        newConfig.turnBrakeGain = sliderBrakeGain.getValue() / 10.0;
        newConfig.turnBrakeEnabled = chkBrakeEnabled.isSelected();
        newConfig.turnBrakeThreshold = 0.05;

        // ボール物理設定の適用
        newConfig.ballCount = 500;
        newConfig.ballMass = 0.062; // 62g = 0.062kg 固定
        newConfig.ballRestitution = sliderBallRestitution.getValue() / 100.0;
        newConfig.ballFriction = sliderBallFriction.getValue() / 100.0;
        newConfig.ballSubSteps = sliderBallSubSteps.getValue();
        if (sliderBallEmitRadius != null) {
            newConfig.ballEmitRadius = sliderBallEmitRadius.getValue() / 100.0;
        }
        if (sliderBallRollingDamping != null) {
            newConfig.ballRollingDamping = sliderBallRollingDamping.getValue() / 10.0;
        }
        if (sliderIntakeCapacity != null) {
            newConfig.intakeCapacity = sliderIntakeCapacity.getValue();
        }
        if (sliderShootRange != null) {
            newConfig.shootRange = sliderShootRange.getValue() / 10.0;
        }
        if (sliderShootSuccessProb != null) {
            newConfig.shootSuccessProb = sliderShootSuccessProb.getValue() / 100.0;
        }
        if (sliderShootBPS != null) {
            newConfig.shootBPS = sliderShootBPS.getValue() / 10.0;
        }
        if (sliderWhiteGoalSuccessProb != null) {
            newConfig.whiteGoalSuccessProb = sliderWhiteGoalSuccessProb.getValue() / 100.0;
        }
        if (sliderWhiteGoalMissRadius != null) {
            newConfig.whiteGoalMissRadius = sliderWhiteGoalMissRadius.getValue() / 10.0;
        }
        
        Long bSeed = null;
        String bSeedText = txtBallSeed.getText().trim();
        if (!bSeedText.isEmpty()) {
            try {
                bSeed = Long.parseLong(bSeedText);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "ボールのシード値には有効な数値を入力してください。", "エラー", JOptionPane.ERROR_MESSAGE);
                txtBallSeed.setText("");
            }
        }
        newConfig.ballSeed = bSeed;

        this.currentConfig = newConfig;

        if (listener != null) {
            listener.onConfigApplied(newConfig);
        }
    }

    private void loadLogFile() {
        JFileChooser fileChooser = new JFileChooser(new File("logs"));
        fileChooser.setDialogTitle("時系列CSVログファイルをロード");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV ログファイル (*.csv)", "csv"));

        int userSelection = fileChooser.showOpenDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToLoad = fileChooser.getSelectedFile();
            try {
                ReplayData data = LogReader.readTimeSeries(fileToLoad);
                loadedReplayStates = data.states;
                loadedReplayConfig = data.config;

                if (loadedReplayStates.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "ロードされたログデータが空です。", "エラー", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                isReplayPlaying = false;
                btnPlayPauseReplay.setText("再生");
                btnPlayPauseReplay.setEnabled(true);
                sliderReplayScrubber.setEnabled(true);
                sliderReplayScrubber.setMinimum(0);
                sliderReplayScrubber.setMaximum(loadedReplayStates.size() - 1);
                sliderReplayScrubber.setValue(0);

                if (listener != null) {
                    listener.onReplayStateChanged(false);
                    listener.onReplayIndexChanged(0);
                }

                JOptionPane.showMessageDialog(this,
                    "過去ログの読み込みに成功しました。\n" +
                    loadedReplayStates.size() + " フレームロード。\n" +
                    "設定復元: d=" + loadedReplayConfig.pivotOffsetD + "m, w=" + loadedReplayConfig.trackWidth + "m",
                    "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "ログの読み込みに失敗しました:\n" + e.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void toggleReplayPlayPause() {
        isReplayPlaying = !isReplayPlaying;
        if (isReplayPlaying) {
            btnPlayPauseReplay.setText("一時停止");
            btnPlayPauseReplay.setBackground(new Color(219, 68, 85));
        } else {
            btnPlayPauseReplay.setText("再生");
            btnPlayPauseReplay.setBackground(new Color(92, 184, 92));
        }

        if (listener != null) {
            listener.onReplayStateChanged(isReplayPlaying);
        }
    }

    public void setReplayPlaying(boolean playing) {
        this.isReplayPlaying = playing;
        if (playing) {
            btnPlayPauseReplay.setText("一時停止");
            btnPlayPauseReplay.setBackground(new Color(219, 68, 85));
        } else {
            btnPlayPauseReplay.setText("再生");
            btnPlayPauseReplay.setBackground(new Color(92, 184, 92));
        }
    }

    public List<RobotState> getLoadedReplayStates() {
        return loadedReplayStates;
    }

    public RobotConfig getLoadedReplayConfig() {
        return loadedReplayConfig;
    }

    // --- Swing コンポーネントカスタマイズヘルパー ---

    private JLabel createDarkLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(200, 210, 220));
        label.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        return label;
    }

    private JLabel createStatusLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(180, 190, 200));
        label.setFont(new Font("MS Gothic", Font.BOLD, 12));
        label.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        return label;
    }

    private JButton createStyledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setFont(new Font("MS Gothic", Font.BOLD, 12));
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        btn.setFocusable(false);
        return btn;
    }

    private void setupDarkSlider(JSlider slider) {
        slider.setBackground(new Color(33, 37, 43));
        slider.setForeground(new Color(150, 160, 170));
    }

    private void setupDarkComboBox(JComboBox<?> combo) {
        combo.setBackground(new Color(45, 50, 60));
        combo.setForeground(Color.WHITE);
        combo.setFont(new Font("MS Gothic", Font.BOLD, 12));
        combo.setBorder(BorderFactory.createLineBorder(new Color(80, 85, 95), 1));
        
        // ドロップダウン矢印ボタンを明るい青に変更して視認性を劇的に向上、さらに本体背景描画を強制上書き
        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton button = new javax.swing.plaf.basic.BasicArrowButton(
                    javax.swing.plaf.basic.BasicArrowButton.SOUTH,
                    new Color(45, 50, 60), // 背景色
                    new Color(97, 218, 251), // 矢印色（明るいシアンブルー）
                    Color.WHITE,
                    new Color(60, 65, 75)
                );
                button.setBorder(BorderFactory.createEmptyBorder());
                return button;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(new Color(45, 50, 60)); // 強制的にダークグレー背景で塗りつぶす
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        });

        // ドロップダウンリストおよびコンボボックス本体のレンダラーをカスタマイズ
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (c instanceof JLabel) {
                    JLabel label = (JLabel) c;
                    label.setOpaque(true);
                    label.setFont(new Font("MS Gothic", Font.PLAIN, 12));
                    
                    if (index == -1) {
                        // コンボボックス本体（現在選択されている項目）のスタイル
                        label.setBackground(new Color(45, 50, 60));
                        label.setForeground(Color.WHITE);
                    } else {
                        // ポップアップリスト内の項目スタイル
                        if (isSelected) {
                            label.setBackground(new Color(79, 110, 194));
                            label.setForeground(Color.WHITE);
                        } else {
                            label.setBackground(new Color(45, 50, 60));
                            label.setForeground(new Color(200, 210, 220));
                        }
                    }
                    label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                }
                return c;
            }
        });
    }

    private void setupDarkTitledBorder(JPanel panel, String title) {
        panel.setBackground(new Color(33, 37, 43));
        TitledBorder border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(60, 65, 75)), title
        );
        border.setTitleColor(new Color(97, 218, 251));
        border.setTitleFont(new Font("MS Gothic", Font.BOLD, 13));
        panel.setBorder(border);
    }

    private JPanel createPracticePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        setupDarkTitledBorder(panel, "操縦練習モード");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.weightx = 1.0;

        // 1. 練習モード有効トグル
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(createDarkLabel("練習モード:"), gbc);
        gbc.gridx = 1;
        chkPracticeMode = new JCheckBox("有効 (ランダムNPC x5)", false);
        chkPracticeMode.setBackground(new Color(33, 37, 43));
        chkPracticeMode.setForeground(new Color(200, 210, 220));
        chkPracticeMode.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        chkPracticeMode.addActionListener(e -> firePracticeModeChanged());
        panel.add(chkPracticeMode, gbc);

        // 2. 乱数シード入力
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(createDarkLabel("シード値 (数字のみ/空でランダム):"), gbc);
        gbc.gridx = 1;
        txtPracticeSeed = new JTextField();
        txtPracticeSeed.setBackground(new Color(45, 50, 60));
        txtPracticeSeed.setForeground(Color.WHITE);
        txtPracticeSeed.setCaretColor(Color.WHITE);
        txtPracticeSeed.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        txtPracticeSeed.addActionListener(e -> firePracticeModeChanged());
        panel.add(txtPracticeSeed, gbc);

        return panel;
    }

    private JPanel createDisplayPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        setupDarkTitledBorder(panel, "表示設定");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.weightx = 1.0;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(createDarkLabel("視点切替 (180°回転):"), gbc);
        
        gbc.gridx = 1;
        comboViewMode = new JComboBox<>(new String[]{"赤視点 (Default)", "青視点 (180°回転)"});
        comboViewMode.setSelectedIndex(0);
        setupDarkComboBox(comboViewMode);
        comboViewMode.addActionListener(e -> {
            if (listener != null) {
                boolean isBlue = comboViewMode.getSelectedIndex() == 1;
                listener.onViewAngleChanged(isBlue);
            }
        });
        panel.add(comboViewMode, gbc);

        return panel;
    }

    private void firePracticeModeChanged() {
        if (listener != null) {
            boolean enabled = chkPracticeMode.isSelected();
            Long seed = null;
            String seedText = txtPracticeSeed.getText().trim();
            if (!seedText.isEmpty()) {
                try {
                    seed = Long.parseLong(seedText);
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(this, "シード値には有効な数値を入力してください。", "エラー", JOptionPane.ERROR_MESSAGE);
                    txtPracticeSeed.setText("");
                }
            }
            listener.onPracticeModeToggled(enabled, seed);
        }
    }

    private JPanel createBallPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        setupDarkTitledBorder(panel, "ボール物理設定");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.weightx = 1.0;

        int row = 0;

        // 3. 反発係数
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("反発係数 (0-1.0):"), gbc);
        gbc.gridx = 1;
        sliderBallRestitution = new JSlider(0, 100, 20); // 0.2 default
        setupDarkSlider(sliderBallRestitution);
        panel.add(sliderBallRestitution, gbc);
        row++;

        // 4. 摩擦係数
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("摩擦係数 (0-1.0):"), gbc);
        gbc.gridx = 1;
        sliderBallFriction = new JSlider(0, 100, 40); // 0.4 default
        setupDarkSlider(sliderBallFriction);
        panel.add(sliderBallFriction, gbc);
        row++;

        // 5. 乱数シード
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("シード値 (空でランダム):"), gbc);
        gbc.gridx = 1;
        txtBallSeed = new JTextField();
        txtBallSeed.setBackground(new Color(45, 50, 60));
        txtBallSeed.setForeground(Color.WHITE);
        txtBallSeed.setCaretColor(Color.WHITE);
        txtBallSeed.setFont(new Font("MS Gothic", Font.PLAIN, 12));
        panel.add(txtBallSeed, gbc);
        row++;

        // 6. 物理サブステップ数
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("サブステップ数 (1-8):"), gbc);
        gbc.gridx = 1;
        sliderBallSubSteps = new JSlider(1, 8, 4);
        setupDarkSlider(sliderBallSubSteps);
        panel.add(sliderBallSubSteps, gbc);
        row++;

        // 7. 放出半径 (0.05 - 1.50 m)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("放出半径 (m, 0.05-1.5):"), gbc);
        gbc.gridx = 1;
        sliderBallEmitRadius = new JSlider(5, 150, 25);
        setupDarkSlider(sliderBallEmitRadius);
        panel.add(sliderBallEmitRadius, gbc);
        row++;

        // 8. 転がり摩擦 (0.5 - 8.0 /s)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("転がり摩擦 (0.5-8.0):"), gbc);
        gbc.gridx = 1;
        sliderBallRollingDamping = new JSlider(5, 80, 15); // デフォルト 1.5/s
        setupDarkSlider(sliderBallRollingDamping);
        panel.add(sliderBallRollingDamping, gbc);
        row++;

        // 9. ボール回収上限 (1 - 100)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("回収上限 (1-100):"), gbc);
        gbc.gridx = 1;
        sliderIntakeCapacity = new JSlider(1, 100, 10); // デフォルト 10
        setupDarkSlider(sliderIntakeCapacity);
        panel.add(sliderIntakeCapacity, gbc);
        row++;

        // 10. 射程 (0.5 - 5.0 m)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("射程 (m, 0.5-5.0):"), gbc);
        gbc.gridx = 1;
        sliderShootRange = new JSlider(5, 50, 20); // デフォルト 2.0m (20)
        setupDarkSlider(sliderShootRange);
        panel.add(sliderShootRange, gbc);
        row++;

        // 11. 射出成功確率 (0.0 - 1.0)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("射出成功率 (0.0-1.0):"), gbc);
        gbc.gridx = 1;
        sliderShootSuccessProb = new JSlider(0, 100, 70); // デフォルト 0.7 (70)
        setupDarkSlider(sliderShootSuccessProb);
        panel.add(sliderShootSuccessProb, gbc);
        row++;

        // 12. 射出速度 BPS (1.0 - 10.0)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("射出速度 (BPS, 1-10):"), gbc);
        gbc.gridx = 1;
        sliderShootBPS = new JSlider(10, 100, 50); // デフォルト 5.0 (50)
        setupDarkSlider(sliderShootBPS);
        panel.add(sliderShootBPS, gbc);
        row++;

        // 13. 白ゴール成功確率 (0.0 - 1.0)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("白ゴール成功率 (0.0-1.0):"), gbc);
        gbc.gridx = 1;
        sliderWhiteGoalSuccessProb = new JSlider(0, 100, 50); // デフォルト 0.5 (50)
        setupDarkSlider(sliderWhiteGoalSuccessProb);
        panel.add(sliderWhiteGoalSuccessProb, gbc);
        row++;

        // 14. 白ゴール散布半径 (0.5 - 5.0 m)
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(createDarkLabel("白ゴール散布半径 (m, 0.5-5.0):"), gbc);
        gbc.gridx = 1;
        sliderWhiteGoalMissRadius = new JSlider(5, 50, 20); // デフォルト 2.0m (20)
        setupDarkSlider(sliderWhiteGoalMissRadius);
        panel.add(sliderWhiteGoalMissRadius, gbc);
        row++;

        return panel;
    }

    public boolean isBlueView() {
        return comboViewMode != null && comboViewMode.getSelectedIndex() == 1;
    }

    public boolean isPracticeModeEnabled() {
        return chkPracticeMode != null && chkPracticeMode.isSelected();
    }

    public Long getPracticeSeed() {
        if (txtPracticeSeed == null) return null;
        String seedText = txtPracticeSeed.getText().trim();
        if (seedText.isEmpty()) return null;
        try {
            return Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

