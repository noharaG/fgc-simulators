package ui;

import core.CompetitionRobot;
import core.RobotConfig;
import io.PresetStorage;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.TitledBorder;

/**
 * 3v3対戦モードの設定を行うコントロールパネルクラス。
 */
public class CompetitionControlPanel extends JPanel {

    public interface CompetitionListener {
        void onAllianceToggled(boolean isBlue);
        void onStartCompetition();
        void onResetCompetition();
    }

    private CompetitionListener listener;
    private practice.CompetitionManager manager;

    private CustomAllianceButton btnAllianceRed;
    private CustomAllianceButton btnAllianceBlue;
    private JPanel listPanel;
    private final List<RobotRowUI> robotRowUIs = new ArrayList<>();

    public CompetitionControlPanel() {
        setBackground(new Color(33, 37, 43));
        setLayout(new BorderLayout(5, 5));
        setPreferredSize(new Dimension(450, 600));

        // 最上部：アライアンス選択トグル
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(33, 37, 43));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JLabel lblAlliance = new JLabel("あなたの陣営:");
        lblAlliance.setForeground(Color.WHITE);
        lblAlliance.setFont(new Font("MS Gothic", Font.BOLD, 14));
        topPanel.add(lblAlliance, BorderLayout.WEST);

        JPanel togglePanel = new JPanel(new GridLayout(1, 2, 5, 0));
        togglePanel.setBackground(new Color(33, 37, 43));

        btnAllianceRed = new CustomAllianceButton("赤アライアンス", false);
        btnAllianceBlue = new CustomAllianceButton("青アライアンス", true);

        ButtonGroup allianceGroup = new ButtonGroup();
        allianceGroup.add(btnAllianceRed);
        allianceGroup.add(btnAllianceBlue);

        btnAllianceRed.addActionListener(e -> {
            if (listener != null) listener.onAllianceToggled(false);
            refreshRobotList();
        });
        btnAllianceBlue.addActionListener(e -> {
            if (listener != null) listener.onAllianceToggled(true);
            refreshRobotList();
        });

        // 永続化された前回の状態をロードするフック用
        btnAllianceRed.setSelected(true);

        togglePanel.add(btnAllianceRed);
        togglePanel.add(btnAllianceBlue);
        topPanel.add(togglePanel, BorderLayout.CENTER);

        add(topPanel, BorderLayout.NORTH);

        // 中央：ロボット一覧パネル
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(new Color(33, 37, 43));

        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // 最下部：アクションボタン
        JPanel bottomPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        bottomPanel.setBackground(new Color(33, 37, 43));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton btnStart = createStyledButton("対戦開始", new Color(40, 167, 69), 14);
        btnStart.addActionListener(e -> {
            if (listener != null) listener.onStartCompetition();
        });

        JButton btnReset = createStyledButton("全リセット", new Color(220, 53, 69), 14);
        btnReset.addActionListener(e -> {
            if (listener != null) listener.onResetCompetition();
        });

        bottomPanel.add(btnStart);
        bottomPanel.add(btnReset);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    public void setCompetitionListener(CompetitionListener listener) {
        this.listener = listener;
    }

    public void setManager(practice.CompetitionManager manager) {
        this.manager = manager;
        // 前回の陣営記憶をロード
        io.PresetStorage.PlayerConfigData playerConfig = io.PresetStorage.loadPlayerConfig();
        if (playerConfig != null) {
            boolean isBlue = "BLUE".equals(playerConfig.alliance);
            if (isBlue) btnAllianceBlue.setSelected(true);
            else btnAllianceRed.setSelected(true);
            manager.setBlueAlliance(isBlue);
            
            // プレイヤーロボットの設定復元
            CompetitionRobot player = manager.getPlayerRobot();
            if (player != null && playerConfig.preset != null) {
                playerConfig.preset.applyTo(player);
            }
        } else {
            manager.setBlueAlliance(btnAllianceBlue.isSelected());
        }
        refreshRobotList();
    }

    public void refreshRobotList() {
        if (manager == null) return;
        listPanel.removeAll();
        robotRowUIs.clear();

        // 自陣グループと敵陣グループを分ける
        JPanel alliedGroupPanel = new JPanel();
        alliedGroupPanel.setLayout(new BoxLayout(alliedGroupPanel, BoxLayout.Y_AXIS));
        setupDarkTitledBorder(alliedGroupPanel, "自陣 (プレイヤー + 味方NPC)");

        JPanel enemyGroupPanel = new JPanel();
        enemyGroupPanel.setLayout(new BoxLayout(enemyGroupPanel, BoxLayout.Y_AXIS));
        setupDarkTitledBorder(enemyGroupPanel, "敵陣 (敵NPC)");

        for (CompetitionRobot r : manager.getRobots()) {
            RobotRowUI row = new RobotRowUI(r);
            robotRowUIs.add(row);
            
            // 所属グループへの振り分け
            boolean isAllied = r.isPlayer() || r.getId() == 2 || r.getId() == 3;
            if (isAllied) {
                alliedGroupPanel.add(row);
                alliedGroupPanel.add(Box.createVerticalStrut(5));
            } else {
                enemyGroupPanel.add(row);
                enemyGroupPanel.add(Box.createVerticalStrut(5));
            }
        }

        listPanel.add(alliedGroupPanel);
        listPanel.add(Box.createVerticalStrut(10));
        listPanel.add(enemyGroupPanel);

        listPanel.revalidate();
        listPanel.repaint();
    }

    private void setupDarkTitledBorder(JPanel panel, String title) {
        panel.setBackground(new Color(33, 37, 43));
        TitledBorder border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(75, 85, 95), 1),
            title,
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("MS Gothic", Font.BOLD, 12),
            Color.WHITE
        );
        panel.setBorder(border);
    }

    /**
     * 各アライアンス内でスタート位置をスワップ(重複防止)します。
     */
    private void handlePositionChange(CompetitionRobot robot, CompetitionRobot.SpawnPosition newPos) {
        if (manager == null) return;
        boolean isAllied = robot.isPlayer() || robot.getId() == 2 || robot.getId() == 3;
        
        // 陣営内の他のロボットとスワップ
        for (CompetitionRobot other : manager.getRobots()) {
            if (other == robot) continue;
            boolean otherAllied = other.isPlayer() || other.getId() == 2 || other.getId() == 3;
            if (isAllied == otherAllied) {
                if (other.getSpawnPosition() == newPos) {
                    // 他のロボットの古い位置をロボットの元の位置にスワップ
                    other.setSpawnPosition(robot.getSpawnPosition());
                    break;
                }
            }
        }
        robot.setSpawnPosition(newPos);
        manager.resetRobotPositions();
        
        // UIリストを再読み込みせずに各コンボボックスの選択値のみ同期
        for (RobotRowUI row : robotRowUIs) {
            row.syncPositionUI();
        }
    }

    // --- ロボット行UIコンポーネント ---
    private class RobotRowUI extends JPanel {
        private final CompetitionRobot robot;
        private final JPanel titlePanel;
        private final JPanel contentPanel;
        private final JLabel lblSummary;
        private final JComboBox<CompetitionRobot.SpawnPosition> comboPos;
        private JSlider sliderDisturb;
        private final JLabel lblArrow;

        private boolean expanded = false;

        public RobotRowUI(CompetitionRobot robot) {
            this.robot = robot;
            setLayout(new BorderLayout());
            setBackground(new Color(45, 50, 60));
            setBorder(BorderFactory.createLineBorder(new Color(60, 70, 80)));

            // 1. タイトルパネル (クリックで折りたたみ展開)
            titlePanel = new JPanel(new GridBagLayout());
            titlePanel.setBackground(new Color(40, 44, 52));
            titlePanel.setCursor(new Cursor(Cursor.HAND_CURSOR));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 5, 4, 5);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            // 色マーカー
            JPanel colorMarker = new JPanel();
            colorMarker.setPreferredSize(new Dimension(10, 10));
            if (robot.isPlayer()) {
                colorMarker.setBackground(new Color(239, 159, 39)); // 黄
            } else if (robot.getAlliance() == CompetitionRobot.Alliance.RED) {
                colorMarker.setBackground(new Color(226, 75, 74));  // 赤
            } else {
                colorMarker.setBackground(new Color(55, 138, 221));  // 青
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
            titlePanel.add(colorMarker, gbc);

            // バッジまたは名前
            String displayName = robot.getName();
            if (robot.isPlayer()) {
                displayName = "YOU [YOU]";
            }
            JLabel lblName = new JLabel(displayName);
            lblName.setForeground(Color.WHITE);
            lblName.setFont(new Font("MS Gothic", Font.BOLD, 12));
            gbc.gridx = 1; gbc.weightx = 0.2;
            titlePanel.add(lblName, gbc);

            // 要約表示
            lblSummary = new JLabel();
            lblSummary.setForeground(new Color(200, 200, 200));
            lblSummary.setFont(new Font("Monospaced", Font.PLAIN, 10));
            updateSummaryText();
            gbc.gridx = 2; gbc.weightx = 0.7;
            titlePanel.add(lblSummary, gbc);

            // 矢印インジケーター
            lblArrow = new JLabel("▼");
            lblArrow.setForeground(new Color(150, 160, 170));
            lblArrow.setFont(new Font("MS Gothic", Font.BOLD, 12));
            gbc.gridx = 3; gbc.weightx = 0.0;
            titlePanel.add(lblArrow, gbc);

            // 折りたたみ状態切替イベント
            titlePanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    toggleExpand();
                }
            });

            add(titlePanel, BorderLayout.NORTH);

            // 2. 展開時のコンテンツパネル
            contentPanel = new JPanel(new GridBagLayout());
            contentPanel.setBackground(new Color(45, 50, 60));
            contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            contentPanel.setVisible(false);

            GridBagConstraints cgbc = new GridBagConstraints();
            cgbc.fill = GridBagConstraints.HORIZONTAL;
            cgbc.insets = new Insets(3, 4, 3, 4);
            cgbc.weightx = 1.0;
            int rowIdx = 0;

            // A. スタート位置
            cgbc.gridx = 0; cgbc.gridy = rowIdx; cgbc.weightx = 0.3;
            contentPanel.add(createDarkLabel("スタート位置:"), cgbc);
            cgbc.gridx = 1; cgbc.weightx = 0.7;
            comboPos = new JComboBox<>(CompetitionRobot.SpawnPosition.values());
            comboPos.setSelectedItem(robot.getSpawnPosition());
            setupDarkComboBox(comboPos);
            comboPos.addActionListener(e -> {
                CompetitionRobot.SpawnPosition newPos = (CompetitionRobot.SpawnPosition) comboPos.getSelectedItem();
                if (newPos != null && newPos != robot.getSpawnPosition()) {
                    handlePositionChange(robot, newPos);
                }
            });
            contentPanel.add(comboPos, cgbc);
            rowIdx++;

            // B. 駆動方式 (NPCのみ、プレイヤーはDIFF固定)
            cgbc.gridx = 0; cgbc.gridy = rowIdx; cgbc.weightx = 0.3;
            contentPanel.add(createDarkLabel("駆動方式:"), cgbc);
            cgbc.gridx = 1; cgbc.weightx = 0.7;
            if (robot.isPlayer()) {
                JLabel lblDrive = new JLabel("差動2輪 (固定)");
                lblDrive.setForeground(Color.WHITE);
                contentPanel.add(lblDrive, cgbc);
            } else {
                JComboBox<CompetitionRobot.DriveType> comboDrive = new JComboBox<>(CompetitionRobot.DriveType.values());
                comboDrive.setSelectedItem(robot.getDriveType());
                setupDarkComboBox(comboDrive);
                comboDrive.addActionListener(e -> {
                    robot.setDriveType((CompetitionRobot.DriveType) comboDrive.getSelectedItem());
                    updateSummaryText();
                });
                contentPanel.add(comboDrive, cgbc);
            }
            rowIdx++;

            // C. 優先行動 (NPCのみ)
            if (!robot.isPlayer()) {
                cgbc.gridx = 0; cgbc.gridy = rowIdx; cgbc.weightx = 0.3;
                contentPanel.add(createDarkLabel("優先行動:"), cgbc);
                cgbc.gridx = 1; cgbc.weightx = 0.7;
                JComboBox<CompetitionRobot.BehaviorType> comboBehave = new JComboBox<>(CompetitionRobot.BehaviorType.values());
                comboBehave.setSelectedItem(robot.getBehaviorType());
                setupDarkComboBox(comboBehave);
                comboBehave.addActionListener(e -> {
                    robot.setBehaviorType((CompetitionRobot.BehaviorType) comboBehave.getSelectedItem());
                    updateSummaryText();
                });
                contentPanel.add(comboBehave, cgbc);
                rowIdx++;
            }

            // D. 回収個数 (1〜100)
            cgbc.gridx = 0; cgbc.gridy = rowIdx;
            contentPanel.add(createDarkLabel("回収上限:"), cgbc);
            cgbc.gridx = 1;
            JSlider sliderIntake = new JSlider(1, 100, robot.getIntakeCapacity());
            setupDarkSlider(sliderIntake);
            sliderIntake.addChangeListener(e -> {
                robot.setIntakeCapacity(sliderIntake.getValue());
                updateSummaryText();
            });
            contentPanel.add(sliderIntake, cgbc);
            rowIdx++;

            // E. 射出成功率 (0〜100)
            cgbc.gridx = 0; cgbc.gridy = rowIdx;
            contentPanel.add(createDarkLabel("射出成功率(%):"), cgbc);
            cgbc.gridx = 1;
            JSlider sliderShoot = new JSlider(0, 100, (int)(robot.getShootSuccessProb() * 100));
            setupDarkSlider(sliderShoot);
            sliderShoot.addChangeListener(e -> {
                robot.setShootSuccessProb(sliderShoot.getValue() / 100.0);
                updateSummaryText();
            });
            contentPanel.add(sliderShoot, cgbc);
            rowIdx++;

            // F. BPS (1〜10)
            cgbc.gridx = 0; cgbc.gridy = rowIdx;
            contentPanel.add(createDarkLabel("秒間射出数(BPS):"), cgbc);
            cgbc.gridx = 1;
            JSlider sliderBPS = new JSlider(1, 10, (int)robot.getShootBPS());
            setupDarkSlider(sliderBPS);
            sliderBPS.addChangeListener(e -> {
                robot.setShootBPS(sliderBPS.getValue());
                updateSummaryText();
            });
            contentPanel.add(sliderBPS, cgbc);
            rowIdx++;

            // G. 妨害重み (NPCのみ)
            if (!robot.isPlayer()) {
                cgbc.gridx = 0; cgbc.gridy = rowIdx;
                contentPanel.add(createDarkLabel("妨害重み(%):"), cgbc);
                cgbc.gridx = 1;
                sliderDisturb = new JSlider(0, 100, (int)(robot.getDisturbWeight() * 100));
                setupDarkSlider(sliderDisturb);
                sliderDisturb.addChangeListener(e -> {
                    robot.setDisturbWeight(sliderDisturb.getValue() / 100.0);
                    updateSummaryText();
                });
                contentPanel.add(sliderDisturb, cgbc);
                rowIdx++;
            }

            // H. 詳細設定ボタン & プリセットセーブ・ロード
            cgbc.gridx = 0; cgbc.gridy = rowIdx; cgbc.gridwidth = 2;
            JPanel actionRow = new JPanel(new BorderLayout(5, 0));
            actionRow.setBackground(new Color(45, 50, 60));

            JButton btnDetails = createStyledButton("詳細設定", new Color(108, 117, 125), 11);
            btnDetails.addActionListener(e -> showDetailsDialog());
            actionRow.add(btnDetails, BorderLayout.WEST);

            // プリセット用UI
            JPanel presetPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            presetPanel.setBackground(new Color(45, 50, 60));

            JComboBox<String> comboPresets = new JComboBox<>();
            comboPresets.addItem("--- プリセット ---");
            refreshPresetCombo(comboPresets);
            setupDarkComboBox(comboPresets);

            JButton btnSavePreset = createStyledButton("保存", new Color(23, 162, 184), 11);
            btnSavePreset.addActionListener(e -> {
                String presetName = JOptionPane.showInputDialog(this, "プリセット名を入力してください:", "プリセット保存", JOptionPane.QUESTION_MESSAGE);
                if (presetName != null && !presetName.trim().isEmpty()) {
                    try {
                        PresetStorage.savePreset(presetName.trim(), robot);
                        refreshPresetCombo(comboPresets);
                        if (robot.isPlayer() && listener != null) {
                            // プレイヤー設定を即永続化
                            PresetStorage.savePlayerConfig(manager.isBlueAlliance() ? "BLUE" : "RED", robot);
                        }
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(this, "保存に失敗しました: " + ex.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });

            comboPresets.addActionListener(e -> {
                String selected = (String) comboPresets.getSelectedItem();
                if (selected != null && !"--- プリセット ---".equals(selected)) {
                    Map<String, PresetStorage.RobotPresetData> data = PresetStorage.loadPresets();
                    PresetStorage.RobotPresetData p = data.get(selected);
                    if (p != null) {
                        p.applyTo(robot);
                        updateSummaryText();
                        // 各スライダーなどの同期
                        sliderIntake.setValue(robot.getIntakeCapacity());
                        sliderShoot.setValue((int)(robot.getShootSuccessProb() * 100));
                        sliderBPS.setValue((int)robot.getShootBPS());
                        if (!robot.isPlayer()) {
                            sliderDisturb.setValue((int)(robot.getDisturbWeight() * 100));
                        }
                        if (robot.isPlayer() && listener != null) {
                            try {
                                PresetStorage.savePlayerConfig(manager.isBlueAlliance() ? "BLUE" : "RED", robot);
                            } catch (IOException ignored) {}
                        }
                    }
                }
            });

            presetPanel.add(comboPresets);
            presetPanel.add(btnSavePreset);
            actionRow.add(presetPanel, BorderLayout.CENTER);

            contentPanel.add(actionRow, cgbc);

            add(contentPanel, BorderLayout.CENTER);
        }

        public void syncPositionUI() {
            comboPos.setSelectedItem(robot.getSpawnPosition());
            updateSummaryText();
        }

        private void refreshPresetCombo(JComboBox<String> combo) {
            // "--- プリセット ---" を除いて全て削除
            while (combo.getItemCount() > 1) {
                combo.removeItemAt(1);
            }
            Map<String, PresetStorage.RobotPresetData> data = PresetStorage.loadPresets();
            for (String key : data.keySet()) {
                combo.addItem(key);
            }
        }

        private void toggleExpand() {
            expanded = !expanded;
            contentPanel.setVisible(expanded);
            lblArrow.setText(expanded ? "▲" : "▼");
            revalidate();
            repaint();
        }

        private void updateSummaryText() {
            String SummaryText = String.format("[%s] [%s] In:%d Sh:%.0f%% BPS:%.0f",
                robot.getSpawnPosition(),
                robot.getDriveType(),
                robot.getIntakeCapacity(),
                robot.getShootSuccessProb() * 100,
                robot.getShootBPS()
            );
            lblSummary.setText(SummaryText);
        }

        private JLabel createDarkLabel(String text) {
            JLabel label = new JLabel(text);
            label.setForeground(new Color(200, 200, 200));
            label.setFont(new Font("MS Gothic", Font.BOLD, 11));
            return label;
        }

        private void setupDarkComboBox(JComboBox<?> combo) {
            combo.setBackground(new Color(45, 50, 60));
            combo.setForeground(Color.WHITE);
            combo.setFont(new Font("MS Gothic", Font.BOLD, 11));
            combo.setBorder(BorderFactory.createLineBorder(new Color(80, 85, 95), 1));
            
            // ドロップダウンの矢印ボタンをカスタマイズして白飛びを防止、さらに本体背景描画を強制上書き
            combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                @Override
                protected JButton createArrowButton() {
                    JButton button = new javax.swing.plaf.basic.BasicArrowButton(
                        javax.swing.plaf.basic.BasicArrowButton.SOUTH,
                        new Color(45, 50, 60), // 背景色
                        new Color(239, 159, 39), // 矢印の色 (黄アクセント)
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
                        label.setFont(new Font("MS Gothic", Font.PLAIN, 11));

                        String text = (value != null) ? value.toString() : "";
                        if (value instanceof CompetitionRobot.SpawnPosition) {
                            switch ((CompetitionRobot.SpawnPosition) value) {
                                case UP: text = "上"; break;
                                case MID: text = "中"; break;
                                case DOWN: text = "下"; break;
                            }
                        } else if (value instanceof CompetitionRobot.DriveType) {
                            switch ((CompetitionRobot.DriveType) value) {
                                case DIFF: text = "差動2輪"; break;
                                case OMNI: text = "全方向 (オムニ)"; break;
                            }
                        } else if (value instanceof CompetitionRobot.BehaviorType) {
                            switch ((CompetitionRobot.BehaviorType) value) {
                                case SHOOT: text = "通常 (赤/青狙い)"; break;
                                case WHITE: text = "白ゴール狙い"; break;
                            }
                        }
                        label.setText(text);

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
                        label.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
                    }
                    return c;
                }
            });
        }

        private void setupDarkSlider(JSlider slider) {
            slider.setBackground(new Color(45, 50, 60));
            slider.setForeground(Color.WHITE);
            slider.setFont(new Font("Monospaced", Font.PLAIN, 10));
            slider.setPaintLabels(false);
        }

        /**
         * 詳細機体スペックを編集するダイアログを表示します。
         */
        private void showDetailsDialog() {
            RobotConfig cfg = robot.getConfig();
            JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "詳細スペック - " + robot.getName(), true);
            dlg.setLayout(new GridBagLayout());
            dlg.getContentPane().setBackground(new Color(33, 37, 43));
            dlg.setSize(400, 500);
            dlg.setLocationRelativeTo(this);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 8, 5, 8);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            int row = 0;

            // 1. トレッド幅
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.4;
            dlg.add(createDarkLabel("トレッド幅 w (m):"), gbc);
            gbc.gridx = 1; gbc.weightx = 0.6;
            JSlider sliderWidth = new JSlider(30, 60, (int)(cfg.trackWidth * 100));
            setupDarkSlider(sliderWidth);
            dlg.add(sliderWidth, gbc);
            row++;

            // 2. ピボットオフセット
            gbc.gridx = 0; gbc.gridy = row;
            dlg.add(createDarkLabel("ピボットオフセット d (m):"), gbc);
            gbc.gridx = 1;
            JSlider sliderPivot = new JSlider(-25, 25, (int)(cfg.pivotOffsetD * 100));
            setupDarkSlider(sliderPivot);
            dlg.add(sliderPivot, gbc);
            row++;

            // 3. 静止摩擦係数
            gbc.gridx = 0; gbc.gridy = row;
            dlg.add(createDarkLabel("静止摩擦係数 (muS):"), gbc);
            gbc.gridx = 1;
            JSlider sliderMuS = new JSlider(10, 200, (int)(cfg.muStatic * 100));
            setupDarkSlider(sliderMuS);
            dlg.add(sliderMuS, gbc);
            row++;

            // 4. 動摩擦係数
            gbc.gridx = 0; gbc.gridy = row;
            dlg.add(createDarkLabel("動摩擦係数 (muK):"), gbc);
            gbc.gridx = 1;
            JSlider sliderMuK = new JSlider(10, 200, (int)(cfg.muKinetic * 100));
            setupDarkSlider(sliderMuK);
            dlg.add(sliderMuK, gbc);
            row++;

            // 5. expo設定
            gbc.gridx = 0; gbc.gridy = row;
            dlg.add(createDarkLabel("Expo 前後方向:"), gbc);
            gbc.gridx = 1;
            JSlider sliderExpoV = new JSlider(0, 100, (int)(cfg.expoDrive * 100));
            setupDarkSlider(sliderExpoV);
            dlg.add(sliderExpoV, gbc);
            row++;

            gbc.gridx = 0; gbc.gridy = row;
            dlg.add(createDarkLabel("Expo 旋回方向:"), gbc);
            gbc.gridx = 1;
            JSlider sliderExpoW = new JSlider(0, 100, (int)(cfg.expoTurn * 100));
            setupDarkSlider(sliderExpoW);
            dlg.add(sliderExpoW, gbc);
            row++;

            // 保存ボタン
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
            JButton btnSave = createStyledButton("設定適用", new Color(40, 167, 69), 12);
            btnSave.addActionListener(ev -> {
                // 新しい RobotConfig を適用
                RobotConfig newCfg = new RobotConfig(
                    cfg.mass,
                    cfg.gearRatio,
                    cfg.wheelDiameter,
                    sliderWidth.getValue() / 100.0,
                    sliderPivot.getValue() / 100.0,
                    sliderMuS.getValue() / 100.0,
                    sliderMuK.getValue() / 100.0,
                    cfg.initX,
                    cfg.initY,
                    cfg.initHeading,
                    cfg.practiceSeed
                );
                newCfg.expoDrive = sliderExpoV.getValue() / 100.0;
                newCfg.expoTurn = sliderExpoW.getValue() / 100.0;
                robot.setConfig(newCfg);
                
                if (robot.isPlayer() && listener != null) {
                    try {
                        PresetStorage.savePlayerConfig(manager.isBlueAlliance() ? "BLUE" : "RED", robot);
                    } catch (IOException ignored) {}
                }
                dlg.dispose();
            });
            dlg.add(btnSave, gbc);

            dlg.setVisible(true);
        }
    }

    private JButton createStyledButton(String text, Color bg, int fontSize) {
        JButton btn = new JButton(text);
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setFont(new Font("MS Gothic", Font.BOLD, fontSize));
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        btn.setFocusable(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                btn.setBackground(bg.brighter());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setBackground(bg);
            }
        });
        return btn;
    }

    // ペイントを完全にオーバーライドするカスタムアライアンス選択ボタン
    private static class CustomAllianceButton extends JToggleButton {
        private final boolean isBlueBtn;
        private boolean hovered = false;

        public CustomAllianceButton(String text, boolean isBlueBtn) {
            super(text);
            this.isBlueBtn = isBlueBtn;
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusable(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setFont(new Font("MS Gothic", Font.BOLD, 12));

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            boolean selected = isSelected();

            // 背景色決定
            Color bg;
            Color fg;
            if (selected) {
                if (isBlueBtn) {
                    bg = new Color(55, 138, 221); // 鮮やかな青
                    fg = Color.WHITE;
                } else {
                    bg = new Color(226, 75, 74);  // 鮮やかな赤
                    fg = Color.WHITE;
                }
            } else {
                if (hovered) {
                    bg = new Color(55, 60, 70);   // 非選択時のホバー（明るめグレー）
                    fg = new Color(200, 210, 220);
                } else {
                    bg = new Color(45, 50, 60);   // 非選択時の通常（ダークグレー）
                    fg = new Color(130, 140, 150);
                }
            }

            // 背景描画（綺麗な角丸）
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, w, h, 6, 6);

            // 境界線（選択時は少し明るい枠線を）
            if (selected) {
                g2.setColor(isBlueBtn ? new Color(100, 180, 255) : new Color(255, 120, 120));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
            } else {
                g2.setColor(new Color(65, 70, 80));
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
            }

            // テキスト描画
            FontMetrics fm = g2.getFontMetrics(getFont());
            int textW = fm.stringWidth(getText());
            int textH = fm.getAscent();
            int x = (w - textW) / 2;
            int y = (h - fm.getHeight()) / 2 + textH;

            g2.setColor(fg);
            g2.drawString(getText(), x, y);

            g2.dispose();
        }
    }
}
