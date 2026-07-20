package ui;

import core.BallState;
import core.CompetitionRobot;
import core.RobotConfig;
import core.RobotState;
import core.SimulationEngine;
import input.GamepadInputAdapter;
import input.InputSource;
import input.KeyboardInputAdapter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.*;
import practice.CompetitionManager;

/**
 * 差動2輪ロボットシミュレータのメインアプリケーションクラス。
 * 50Hzの更新ループ、キーボード/ゲームパッドの入力選択、通常走行とリプレイのモード管理を行います。
 */
public class SimulatorApp extends JFrame implements ControlPanel.ControlListener, SimulationEngine.EngineListener {

    private enum Mode {
        SIMULATING, // 通常走行シミュレーションモード
        REPLAYING   // 過去ログリプレイ再生モード
    }

    private volatile Mode currentMode = Mode.SIMULATING;

    private volatile RobotConfig currentConfig;
    private volatile SimulationEngine simulationEngine;
    private GamepadInputAdapter gamepadAdapter;
    private KeyboardInputAdapter keyboardAdapter;
    private volatile InputSource activeInputSource;

    // 練習モード
    private volatile boolean practiceEnabled = false;
    private volatile Long practiceSeed = null;
    private practice.PracticeManager practiceManager = null;

    // UIコンポーネント
    private FieldPanel fieldPanel;
    private ControlPanel controlPanel;
    private CompetitionControlPanel competitionControlPanel;
    private JPanel rightCardPanel;
    private CustomTabPanel tabbedPane;
    private practice.CompetitionManager competitionManager = null;

    // スレッド制御
    private volatile boolean running = true;
    private volatile double replayIndex = 0.0;
    private volatile double replaySpeed = 1.0;
    private volatile boolean isReplayPlaying = false;

    // パフォーマンス計測用
    private int perfFrameCount = 0;
    private double perfAiSum = 0.0;
    private double perfBallSum = 0.0;
    private double perfDrawSum = 0.0;

    // onStateUpdated スロットリング用（ナノ秒）
    // FieldPanel への描画プッシュは ~30fps (33ms)、ControlPanel.updateStatus は ~10fps (100ms) に制限する
    private volatile long lastRenderPushNs = 0L;
    private volatile long lastStatusPushNs = 0L;
    private static final long RENDER_INTERVAL_NS = 33_000_000L;  // 33ms ≒ 30fps
    private static final long STATUS_INTERVAL_NS = 100_000_000L; // 100ms ≒ 10fps

    public SimulatorApp() {
        super("差動2輪ロボット ドライブ練習シミュレータ (FGC/FTC)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(true);

        // クールなダークUIテーマ
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // コア設定の初期化
        currentConfig = new RobotConfig();

        // 入力アダプタの初期化
        gamepadAdapter = new GamepadInputAdapter();
        keyboardAdapter = new KeyboardInputAdapter();
        activeInputSource = keyboardAdapter; // デフォルトはキーボード

        // UIレイアウト
        setLayout(new BorderLayout());
        
        fieldPanel = new FieldPanel();
        add(fieldPanel, BorderLayout.CENTER);

        // シミュレーションエンジンの初期化
        initSimulationEngine();

        // 右側パネルを CardLayout で作成
        rightCardPanel = new JPanel(new CardLayout());
        
        controlPanel = new ControlPanel();
        controlPanel.setControlListener(this);
        controlPanel.updateConfigUI(currentConfig);
        
        competitionControlPanel = new CompetitionControlPanel();
        competitionControlPanel.setCompetitionListener(new CompetitionControlPanel.CompetitionListener() {
            @Override
            public void onAllianceToggled(boolean isBlue) {
                if (competitionManager != null) {
                    competitionManager.setBlueAlliance(isBlue);
                    if (simulationEngine != null) {
                        simulationEngine.setBlueAlliance(isBlue);
                    }
                    fieldPanel.setBlueView(isBlue);
                    // 陣営変更を即座に永続化（次回 setManager() で正しく復元されるように）
                    try {
                        CompetitionRobot player = competitionManager.getPlayerRobot();
                        if (player != null) {
                            io.PresetStorage.savePlayerConfig(isBlue ? "BLUE" : "RED", player);
                        }
                    } catch (java.io.IOException ignored) {}
                }
            }

            @Override
            public void onStartCompetition() {
                if (simulationEngine != null) {
                    onSimulationReset();
                }
            }

            @Override
            public void onResetCompetition() {
                if (competitionManager != null) {
                    competitionManager.resetRobotPositions();
                    fieldPanel.clearTrail();
                    fieldPanel.repaint();
                }
            }
        });

        rightCardPanel.add(controlPanel, "PRACTICE");
        rightCardPanel.add(competitionControlPanel, "COMPETITION");
        add(rightCardPanel, BorderLayout.EAST);

        // 画面上部にタブを設置
        tabbedPane = new CustomTabPanel();
        
        tabbedPane.addChangeListener(e -> {
            int selected = tabbedPane.getSelectedIndex();
            CardLayout cl = (CardLayout) rightCardPanel.getLayout();
            if (selected == 0) {
                cl.show(rightCardPanel, "PRACTICE");
                switchToPracticeMode();
            } else {
                cl.show(rightCardPanel, "COMPETITION");
                switchToCompetitionMode();
            }
        });
        add(tabbedPane, BorderLayout.NORTH);

        // キーボード入力を受け付けるためのグローバルイベント設定（フォーカス状態にかかわらず操作可能にする）
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboardAdapter);

        // ウィンドウがアクティブになったときにフォーカスを奪い返す
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                requestFocusInWindow();
            }
            
            @Override
            public void windowClosing(WindowEvent e) {
                running = false;
                gamepadAdapter.close();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    private void initSimulationEngine() {
        simulationEngine = new SimulationEngine(currentConfig, new InputSource() {
            @Override
            public double getTranslationInput() {
                return activeInputSource.getTranslationInput();
            }

            @Override
            public double getRotationInput() {
                return activeInputSource.getRotationInput();
            }

            @Override
            public void update() {
                // 親のループで処理するため空
            }

            @Override
            public boolean isIntakePressed() {
                return activeInputSource.isIntakePressed();
            }

            @Override
            public boolean isEjectPressed() {
                return activeInputSource.isEjectPressed();
            }

            @Override
            public boolean isShootPressed() {
                return activeInputSource.isShootPressed();
            }
        });

        // アライアンスの初期状態をコントロールパネルから同期
        boolean isBlue = false;
        if (controlPanel != null) {
            isBlue = controlPanel.isBlueView();
        }
        simulationEngine.setBlueAlliance(isBlue);

        simulationEngine.addListener(this);
        
        // 障害物を描画パネルに設定
        if (fieldPanel != null) {
            fieldPanel.setFieldObstacles(simulationEngine.getFieldObstacles());
        }
    }

    /**
     * アプリケーションのメインループを実行します。
     * 約20ms (50Hz) 間隔で状態を更新します。
     */
    public void startLoop() {
        long targetIntervalNs = 20_000_000; // 20ms in nanoseconds
        long lastTime = System.nanoTime();

        while (running) {
            long now = System.nanoTime();
            long elapsed = now - lastTime;

            if (elapsed >= targetIntervalNs) {
                lastTime = now;

                // 1. 入力ソースのポーリング
                gamepadAdapter.update();
                keyboardAdapter.update();

                // 入力ソースの動的な切り替え（ゲームパッド接続の有無）
                if (gamepadAdapter.isConnected()) {
                    activeInputSource = gamepadAdapter;
                } else {
                    activeInputSource = keyboardAdapter;
                }

                // 2. モード別の更新処理
                if (currentMode == Mode.SIMULATING) {
                    // 通常走行更新 (50Hz) - resolver を渡すことで NPC挙動・衝突解決を確実に呼び出す
                    if (practiceEnabled && practiceManager != null) {
                        simulationEngine.tick(0.02, practiceManager);
                    } else if (simulationEngine.isCompetitionMode() && competitionManager != null) {
                        simulationEngine.tick(0.02, competitionManager);
                    } else {
                        simulationEngine.tick(0.02);
                    }

                    // パフォーマンス計測（対戦モード時のみ）
                    if (simulationEngine.isCompetitionMode()) {
                        perfAiSum += simulationEngine.lastAiTimeMs;
                        perfBallSum += simulationEngine.lastBallTimeMs;
                        perfDrawSum += fieldPanel.getLastDrawTimeMs();
                        perfFrameCount++;
                        if (perfFrameCount >= 50) {
                            System.out.printf("[PERF] Ball: %.2fms, AI: %.2fms, Draw: %.2fms%n",
                                perfBallSum / perfFrameCount,
                                perfAiSum / perfFrameCount,
                                perfDrawSum / perfFrameCount);
                            perfFrameCount = 0;
                            perfAiSum = 0.0;
                            perfBallSum = 0.0;
                            perfDrawSum = 0.0;
                        }
                    }
                } else if (currentMode == Mode.REPLAYING) {
                    // リプレイ更新
                    tickReplay();
                }
            }

            // 次 tick までの残り時間を計算してスリープ（省電力化）。
            // 残り > 2ms なら (残り - 1ms) 眠り、それ以外は 1ms だけ眠って CPU を解放する。
            long remaining = targetIntervalNs - (System.nanoTime() - lastTime);
            try {
                if (remaining > 2_000_000L) {
                    Thread.sleep((remaining - 1_000_000L) / 1_000_000L);
                } else {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * リプレイ再生時の更新
     */
    private void tickReplay() {
        List<RobotState> states = controlPanel.getLoadedReplayStates();
        if (states == null || states.isEmpty()) return;

        if (isReplayPlaying) {
            // 再生速度に応じてインデックスを進める (50Hz = 0.02秒ステップ)
            replayIndex += replaySpeed;
            if (replayIndex >= states.size() - 1) {
                replayIndex = states.size() - 1;
                isReplayPlaying = false;
                controlPanel.setReplayPlaying(false);
            }
        }

        int idx = (int) replayIndex;
        RobotState state = states.get(idx);
        
        // 描画とUIステータスの更新
        fieldPanel.setReplayData(states, idx, controlPanel.getLoadedReplayConfig());
        controlPanel.updateReplayStatus(state, idx, states.size() - 1);
    }

    // --- SimulationEngine.EngineListener 実装 ---

    @Override
    public void onTimerStarted() {
        // タイマーが走り始めたらログクリア
        fieldPanel.clearTrail();
        System.out.println("走行タイマーが自動起動しました。ログの記録を開始します。");
    }

    @Override
    public void onStateUpdated(RobotState state) {
        // このメソッドはシミュレーションスレッド (SimulationLoopThread) から呼ばれる。
        // Swing コンポーネントへのアクセスはすべて invokeLater でEDTに委譲する。
        // スロットリングにより FieldPanel 更新は ~30fps、ControlPanel.updateStatus は ~10fps に制限し、
        // EDT 負荷を抑制する。
        // 注意: 走行軌跡のサンプリングは setRobotState 内で行われるため 50Hz→30Hz に下がるが、
        //       軌跡は 0.02m 以上移動したときのみ点を打つ仕様のため、最高速 2.36m/s でも
        //       セグメント長が 0.047m→0.078m 程度になるに過ぎず、見た目への影響は軽微。

        long nowNs = System.nanoTime();

        // FieldPanel への描画プッシュ（~30fps）
        if (nowNs - lastRenderPushNs >= RENDER_INTERVAL_NS) {
            lastRenderPushNs = nowNs;
            // BallSimulation の参照をキャプチャ（スレッドセーフに取得）
            final java.util.List<BallState> ballStates =
                (simulationEngine != null && simulationEngine.getBallSimulation() != null)
                    ? simulationEngine.getBallSimulation().getBallStates()
                    : null;
            final RobotConfig capturedConfig = currentConfig;
            SwingUtilities.invokeLater(() -> {
                fieldPanel.setBallStates(ballStates);
                fieldPanel.setRobotState(state, capturedConfig);
            });
        }

        // ControlPanel のステータス更新（~10fps）
        // タイマー表示は 100ms 間隔になるが、元々 10ms 精度の表示（%05.2f = 10ms 刻み）
        // であったため実質的な表示精度の劣化はない。
        if (nowNs - lastStatusPushNs >= STATUS_INTERVAL_NS) {
            lastStatusPushNs = nowNs;
            final boolean gpConnected = gamepadAdapter.isConnected();
            final boolean timerStarted = simulationEngine.isTimerStarted();
            final double simTime = simulationEngine.getSimTime();
            SwingUtilities.invokeLater(() ->
                controlPanel.updateStatus(state, gpConnected, timerStarted, simTime)
            );
        }
    }

    @Override
    public void onTimeLimitReached() {
        // 150秒強制停止イベント
        JOptionPane.showMessageDialog(this,
            "150秒（2分30秒）経過しました。機体を強制停止します。\n走行ログが保存されました。",
            "試合終了", JOptionPane.WARNING_MESSAGE
        );
        requestFocusInWindow();
    }

    // --- ControlPanel.ControlListener 実装 ---

    @Override
    public void onConfigApplied(RobotConfig newConfig) {
        currentConfig = newConfig;
        currentMode = Mode.SIMULATING;
        isReplayPlaying = false;
        controlPanel.setReplayPlaying(false);
        
        // エンジンのリセットと新しい設定のロード
        initSimulationEngine();
        fieldPanel.clearTrail();
        
        // 練習モードまたは対戦モードの再初期化
        if (tabbedPane.getSelectedIndex() == 1) {
            // 対戦モード
            practiceManager = null;
            fieldPanel.setPracticeManager(null);
            boolean isBlue = (competitionManager != null) ? competitionManager.isBlueAlliance() : false;
            competitionManager = new practice.CompetitionManager(currentConfig, isBlue);
            simulationEngine.setCompetitionMode(true, competitionManager);
            fieldPanel.setCompetitionManager(competitionManager);
            competitionControlPanel.setManager(competitionManager);
            
            // 視点の同期 (setManagerで読み込まれた保存設定を反映)
            isBlue = competitionManager.isBlueAlliance();
            fieldPanel.setBlueView(isBlue);
            simulationEngine.setBlueAlliance(isBlue);
        } else {
            // 練習モード
            simulationEngine.setCompetitionMode(false, null);
            fieldPanel.setCompetitionManager(null);
            if (practiceEnabled) {
                practiceManager = new practice.PracticeManager(currentConfig, practiceSeed);
                simulationEngine.setActivePracticeSeed(practiceManager.getCurrentSeed());
                fieldPanel.setPracticeManager(practiceManager);
            } else {
                practiceManager = null;
                fieldPanel.setPracticeManager(null);
            }
        }

        // 初期状態の描画更新
        if (simulationEngine != null && simulationEngine.getBallSimulation() != null) {
            fieldPanel.setBallStates(simulationEngine.getBallSimulation().getBallStates());
        } else {
            fieldPanel.setBallStates(null);
        }
        fieldPanel.setRobotState(simulationEngine.getCurrentState(), currentConfig);
        controlPanel.updateStatus(simulationEngine.getCurrentState(), gamepadAdapter.isConnected(), false, 0.0);
        
        System.out.println("新しい機体設定を適用しました。");
        requestFocusInWindow();
    }

    @Override
    public void onSimulationReset() {
        currentMode = Mode.SIMULATING;
        isReplayPlaying = false;
        controlPanel.setReplayPlaying(false);

        // エンジンの初期化
        initSimulationEngine();
        fieldPanel.clearTrail();

        // 練習モードまたは対戦モードの再初期化
        if (tabbedPane.getSelectedIndex() == 1) {
            // 対戦モード
            practiceManager = null;
            fieldPanel.setPracticeManager(null);
            boolean isBlue = (competitionManager != null) ? competitionManager.isBlueAlliance() : false;
            if (competitionManager == null) {
                // 初回：設定画面で選択された陣営で新規作成
                competitionManager = new practice.CompetitionManager(currentConfig, isBlue);
            } else {
                // 2回目以降：SpawnPosition等の設定を保持したまま位置・状態のみリセット
                competitionManager.softReset();
            }
            fieldPanel.setCompetitionManager(competitionManager);
            // setManager() が PresetStorage から陣営を復元して resetRobotPositions() を呼ぶため、
            // その後に setCompetitionMode() でプレイヤーの確定位置を currentState に同期する
            competitionControlPanel.setManager(competitionManager);
            simulationEngine.setCompetitionMode(true, competitionManager);

            // デバッグ：プレイヤーのスポーン座標をログ出力
            CompetitionRobot player = competitionManager.getPlayerRobot();
            if (player != null) {
                System.out.println("[COMP START] player alliance=" + player.getAlliance()
                    + " startPos=" + player.getSpawnPosition()
                    + " spawn=(" + String.format("%.3f", player.x) + "," + String.format("%.3f", player.y) + ")"
                    + " heading=" + String.format("%.3f", player.heading));
            }

            isBlue = competitionManager.isBlueAlliance();
            fieldPanel.setBlueView(isBlue);
            simulationEngine.setBlueAlliance(isBlue);
        } else {
            // 練習モード
            simulationEngine.setCompetitionMode(false, null);
            fieldPanel.setCompetitionManager(null);
            if (practiceEnabled) {
                practiceManager = new practice.PracticeManager(currentConfig, practiceSeed);
                simulationEngine.setActivePracticeSeed(practiceManager.getCurrentSeed());
                fieldPanel.setPracticeManager(practiceManager);
            } else {
                practiceManager = null;
                fieldPanel.setPracticeManager(null);
            }
        }

        if (simulationEngine != null && simulationEngine.getBallSimulation() != null) {
            fieldPanel.setBallStates(simulationEngine.getBallSimulation().getBallStates());
        } else {
            fieldPanel.setBallStates(null);
        }
        fieldPanel.setRobotState(simulationEngine.getCurrentState(), currentConfig);
        controlPanel.updateStatus(simulationEngine.getCurrentState(), gamepadAdapter.isConnected(), false, 0.0);

        System.out.println("シミュレーション位置をリセットしました。");
        requestFocusInWindow();
    }

    @Override
    public void onSimulationStopRequested() {
        if (currentMode == Mode.SIMULATING && simulationEngine.isTimerStarted()) {
            simulationEngine.stopAndSave();
            String path = simulationEngine.getLogFilePath();
            JOptionPane.showMessageDialog(this,
                "走行を終了しました。走行サマリーが保存されました：\n" + (path != null ? path : "logs/ディレクトリ"),
                "走行停止", JOptionPane.INFORMATION_MESSAGE
            );
        }
        requestFocusInWindow();
    }

    @Override
    public void onPracticeModeToggled(boolean enabled, Long seed) {
        this.practiceEnabled = enabled;
        this.practiceSeed = seed;

        if (enabled) {
            // 練習マネージャを初期化してNPCを生成
            practiceManager = new practice.PracticeManager(currentConfig, seed);
            simulationEngine.setActivePracticeSeed(practiceManager.getCurrentSeed());
            fieldPanel.setPracticeManager(practiceManager);
            System.out.println("練習モードを開始しました。シード値: " + practiceManager.getCurrentSeed());
        } else {
            // 練習マネージャを破棄
            practiceManager = null;
            simulationEngine.setActivePracticeSeed(null);
            fieldPanel.setPracticeManager(null);
            System.out.println("練習モードを終了しました。");
        }
        requestFocusInWindow();
    }

    @Override
    public void onViewAngleChanged(boolean isBlueView) {
        if (fieldPanel != null) {
            fieldPanel.setBlueView(isBlueView);
        }
        if (simulationEngine != null) {
            simulationEngine.setBlueAlliance(isBlueView);
        }
        requestFocusInWindow();
    }

    @Override
    public void onReplayStateChanged(boolean playing) {
        currentMode = Mode.REPLAYING;
        this.isReplayPlaying = playing;
        requestFocusInWindow();
    }

    @Override
    public void onReplayIndexChanged(int index) {
        currentMode = Mode.REPLAYING;
        this.replayIndex = index;
        
        // 再生が止まった状態でシークされた場合は一時停止状態を維持
        tickReplay();
        requestFocusInWindow();
    }

    @Override
    public void onReplaySpeedChanged(double speed) {
        this.replaySpeed = speed;
        requestFocusInWindow();
    }

    private void switchToPracticeMode() {
        // コントロールパネルの練習モード設定を適用
        this.practiceEnabled = controlPanel.isPracticeModeEnabled();
        this.practiceSeed = controlPanel.getPracticeSeed();
        this.competitionManager = null;
        
        if (simulationEngine != null) {
            simulationEngine.setCompetitionMode(false, null);
            simulationEngine.setActivePracticeSeed(null);
        }
        if (fieldPanel != null) {
            fieldPanel.setCompetitionManager(null);
        }
        
        onSimulationReset();
        System.out.println("シングル走行（練習）モードに切り替えました。");
    }

    private void switchToCompetitionMode() {
        // 対戦モードに切り替える際、練習モードの状態を初期化
        this.practiceEnabled = false;
        this.practiceManager = null;
        if (simulationEngine != null) {
            simulationEngine.setActivePracticeSeed(null);
        }
        if (fieldPanel != null) {
            fieldPanel.setPracticeManager(null);
        }

        // 前回のプレイヤー設定から陣営をロード
        boolean isBlue = false;
        io.PresetStorage.PlayerConfigData playerConfig = io.PresetStorage.loadPlayerConfig();
        if (playerConfig != null) {
            isBlue = "BLUE".equals(playerConfig.alliance);
        }

        this.competitionManager = new practice.CompetitionManager(currentConfig, isBlue);
        if (simulationEngine != null) {
            simulationEngine.setCompetitionMode(true, competitionManager);
        }
        if (fieldPanel != null) {
            fieldPanel.setCompetitionManager(competitionManager);
        }
        competitionControlPanel.setManager(competitionManager);

        onSimulationReset();
        System.out.println("3v3対戦モードに切り替えました。");
    }

    // --- エントリーポイント ---
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SimulatorApp app = new SimulatorApp();
            app.setVisible(true);
            
            // ループスレッドの立ち上げ
            new Thread(app::startLoop, "SimulationLoopThread").start();
        });
    }

    // カスタムタブバー用のコンポーネント
    private class CustomTabPanel extends JPanel {
        private final TabButton[] tabs;
        private int selectedIndex = 0;
        private final java.util.List<javax.swing.event.ChangeListener> listeners = new java.util.ArrayList<>();

        public CustomTabPanel() {
            setBackground(new Color(25, 28, 33)); // メインより少し暗いトーンでプレミアム感を演出
            setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(45, 50, 60))); // 下境界線

            tabs = new TabButton[2];
            tabs[0] = new TabButton("シングル走行 (練習)", 0);
            tabs[1] = new TabButton("3v3 対戦", 1);

            add(tabs[0]);
            add(tabs[1]);
        }

        public int getSelectedIndex() {
            return selectedIndex;
        }

        public void setSelectedIndex(int index) {
            if (index >= 0 && index < tabs.length && index != selectedIndex) {
                tabs[selectedIndex].setActive(false);
                selectedIndex = index;
                tabs[selectedIndex].setActive(true);
                fireStateChanged();
            }
        }

        public void addChangeListener(javax.swing.event.ChangeListener l) {
            listeners.add(l);
        }

        private void fireStateChanged() {
            javax.swing.event.ChangeEvent e = new javax.swing.event.ChangeEvent(this);
            for (javax.swing.event.ChangeListener l : listeners) {
                l.stateChanged(e);
            }
        }

        private class TabButton extends JComponent {
            private final String text;
            private final int index;
            private boolean active = false;
            private boolean hovered = false;

            public TabButton(String text, int index) {
                this.text = text;
                this.index = index;
                this.active = (index == 0);
                
                setFont(new Font("MS Gothic", Font.BOLD, 13));
                setPreferredSize(new Dimension(180, 40));
                setCursor(new Cursor(Cursor.HAND_CURSOR));

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

                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        setSelectedIndex(index);
                    }
                });
            }

            public void setActive(boolean active) {
                this.active = active;
                repaint();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                // 背景色塗りつぶし
                if (active) {
                    g2.setColor(new Color(33, 37, 43)); // アクティブ時はメイン背景と同じ
                } else if (hovered) {
                    g2.setColor(new Color(40, 44, 52)); // ホバー時は中間トーン
                } else {
                    g2.setColor(new Color(25, 28, 33)); // 非アクティブ時
                }
                g2.fillRect(0, 0, w, h);

                // 下部のアクティブインジケーター線（黄色の美しいグラデーション）
                if (active) {
                    GradientPaint gp = new GradientPaint(0, h - 4, new Color(239, 159, 39), w, h - 4, new Color(255, 190, 70));
                    g2.setPaint(gp);
                    g2.fillRect(0, h - 4, w, 4);
                }

                // テキスト描画
                FontMetrics fm = g2.getFontMetrics(getFont());
                int textW = fm.stringWidth(text);
                int textH = fm.getAscent();
                int x = (w - textW) / 2;
                int y = (h - fm.getHeight()) / 2 + textH;

                if (active) {
                    g2.setColor(new Color(239, 159, 39)); // アクティブ時はアクセントカラーの黄
                } else if (hovered) {
                    g2.setColor(Color.WHITE); // ホバー時は白
                } else {
                    g2.setColor(new Color(150, 160, 170)); // 通常は落ち着いたグレー
                }
                g2.setFont(getFont());
                g2.drawString(text, x, y);

                g2.dispose();
            }
        }
    }
}
