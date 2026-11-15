package practice;

/**
 * NPCロボットに対する並進および角速度の目標値指令。
 */
public class DriveCommand {
    /** 目標並進速度 (m/s) */
    public final double v;
    /** 目標角速度 (rad/s) */
    public final double omega;

    /**
     * @param v 目標並進速度 (m/s)
     * @param omega 目標角速度 (rad/s)
     */
    public DriveCommand(double v, double omega) {
        this.v = v;
        this.omega = omega;
    }
}
