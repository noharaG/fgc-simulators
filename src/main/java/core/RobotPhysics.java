package core;

import java.util.List;

/**
 * 差動2輪ロボットの物理および運動学の計算を行うクラス。
 * 積分の対象を車軸ポーズ A (xA, yA, theta) とし、ボディ中心 G を幾何的に導出します。
 */
public class RobotPhysics {

    private static final double G_ACCEL = 9.81; // 重力加速度 (m/s^2)

    /**
     * デッドゾーンを適用します。
     */
    public static double applyDeadzone(double val, double deadzone) {
        if (Math.abs(val) < deadzone) {
            return 0.0;
        }
        return Math.signum(val) * (Math.abs(val) - deadzone) / (1.0 - deadzone);
    }

    /**
     * 前ステップの状態と現在の入力値から、次のステップのロボット状態を計算します。
     * 壁および障害物との衝突・整列・押し戻しを一元化して処理します。
     * 
     * @param prevState 前ステップのロボット状態
     * @param config ロボットの設定パラメータ
     * @param rawInputY 生の並進入力 (スティックY, 前後)
     * @param rawInputX 生の旋回入力 (スティックX, 左右)
     * @param dt タイムステップ (秒)
     * @param fieldObstacles フィールド上の物理障害物のリスト
     * @param currentDateTime 現在のタイムスタンプ文字列
     * @return 計算された新しいロボット状態
     */
    public static RobotState update(RobotState prevState, RobotConfig config,
                                    double rawInputY, double rawInputX,
                                    double dt, List<FieldObstacle> fieldObstacles, String currentDateTime) {

        // 1. デッドゾーンの適用
        double deadzone = 0.05;
        double inputY = applyDeadzone(rawInputY, deadzone);
        double inputX = applyDeadzone(rawInputX, deadzone);

        // 1.5. 入力カーブ (expo) の適用
        double shapedY = Math.signum(inputY) * (config.expoDrive * inputY * inputY + (1.0 - config.expoDrive) * Math.abs(inputY));
        double shapedX = Math.signum(inputX) * (config.expoTurn * inputX * inputX + (1.0 - config.expoTurn) * Math.abs(inputX));

        // 2. 指令速度目標
        double vTarget = shapedY * config.maxSpeed;
        double omegaTarget = shapedX * config.maxOmega;

        // 3. 加速度・角加速度制限による車軸速度 v, 角速度 omega の決定
        double realAccelV;
        double realAccelOmega;
        double vNew;
        double omegaNew;

        // 並進速度の更新
        double deltaV = vTarget - prevState.v;
        boolean isBrakingV = config.brakeEnabled && (Math.abs(rawInputY) <= config.brakeThreshold);

        if (isBrakingV) {
            double[] brakeRes = applyActiveBrake(prevState.v, config.brakeGain, dt);
            vNew = brakeRes[0];
            realAccelV = brakeRes[1];
        } else {
            if (inputY == 0.0 && inputX == 0.0) {
                // 自然摩擦減速を適用
                double frictionDecel = config.frictionAccel;
                if (Math.abs(prevState.v) < frictionDecel * dt) {
                    realAccelV = -prevState.v / dt;
                } else {
                    realAccelV = -Math.signum(prevState.v) * frictionDecel;
                }
            } else {
                double reqAccelV = deltaV / dt;
                realAccelV = clamp(reqAccelV, -config.maxAccel, config.maxAccel);
            }
            vNew = prevState.v + realAccelV * dt;
        }

        // 角速度の更新
        double deltaOmega = omegaTarget - prevState.omega;
        boolean isBrakingOmega = config.turnBrakeEnabled && (Math.abs(rawInputX) <= config.turnBrakeThreshold);

        if (isBrakingOmega) {
            double[] brakeRes = applyActiveBrake(prevState.omega, config.turnBrakeGain, dt);
            omegaNew = brakeRes[0];
            realAccelOmega = brakeRes[1];
        } else {
            if (inputY == 0.0 && inputX == 0.0) {
                // 自然摩擦減速を適用
                double frictionDecelOmega = config.frictionAccel * 2.0; // 角速度減衰を少し強めに
                if (Math.abs(prevState.omega) < frictionDecelOmega * dt) {
                    realAccelOmega = -prevState.omega / dt;
                } else {
                    realAccelOmega = -Math.signum(prevState.omega) * frictionDecelOmega;
                }
            } else {
                double reqAccelOmega = deltaOmega / dt;
                realAccelOmega = clamp(reqAccelOmega, -config.maxAlpha, config.maxAlpha);
            }
            omegaNew = prevState.omega + realAccelOmega * dt;
        }

        // 4. 簡易スリップモデル (摩擦円)
        double aLong = realAccelV;
        double aLat = vNew * omegaNew;
        double aReqTotal = Math.sqrt(aLong * aLong + aLat * aLat);
        double aGripMax = config.muStatic * G_ACCEL;

        boolean slipFlag = false;
        double vSlidePrev = (prevState.slipFlag) ? prevState.slipAmount * Math.signum(prevState.v * prevState.omega) : 0.0;
        double vSlideNew = 0.0;

        if (aReqTotal > aGripMax || Math.abs(vSlidePrev) > 0.01) {
            slipFlag = true;
            double scale = (aReqTotal > aGripMax) ? (aGripMax / aReqTotal) : 1.0;
            realAccelV = realAccelV * scale;
            vNew = prevState.v + realAccelV * dt;

            double aGripLatMax = Math.sqrt(Math.max(0.0, aGripMax * aGripMax - realAccelV * realAccelV));
            double excessCentrifugal = aLat - Math.signum(aLat) * aGripLatMax;
            double aSlide = -excessCentrifugal;

            double vSlideMid = vSlidePrev + aSlide * dt;
            double frictionSide = config.muKinetic * G_ACCEL * dt;

            if (Math.abs(vSlideMid) <= frictionSide) {
                vSlideNew = 0.0;
                slipFlag = false;
            } else {
                vSlideNew = vSlideMid - Math.signum(vSlideMid) * frictionSide;
            }
        } else {
            vSlideNew = 0.0;
            slipFlag = false;
        }

        // 5. 車軸ポーズ A (xA, yA, theta) の積分
        double headingNew = prevState.heading + omegaNew * dt;
        headingNew = normalizeAngle(headingNew);

        double xADot = vNew * Math.cos(headingNew) - vSlideNew * Math.sin(headingNew);
        double yADot = vNew * Math.sin(headingNew) + vSlideNew * Math.cos(headingNew);

        double xANew = prevState.xA + xADot * dt;
        double yANew = prevState.yA + yADot * dt;

        // 6. ボディ中心 G (x, y) の幾何導出
        double xGNew = xANew - config.pivotOffsetD * Math.cos(headingNew);
        double yGNew = yANew - config.pivotOffsetD * Math.sin(headingNew);

        // 物理積分直後の初期位置（進入速度クランプの補正ベクトル算出用）
        double xGStart = xGNew;
        double yGStart = yGNew;

        // 7. 壁整列および障害物壁整列の優先処理 (接触角ピボット整列)
        double halfSize = config.robotSize / 2.0;

        if (config.wallAlignEnabled && Math.abs(vTarget) > 0.01) {
            double L = config.robotSize;
            double cosH = Math.cos(headingNew);
            double sinH = Math.sin(headingNew);

            // 4隅のローカル座標
            double[][] localCorners = {
                {-L/2, -L/2},
                {-L/2, L/2},
                {L/2, L/2},
                {L/2, -L/2}
            };

            // 壁の最大貫通量の計算
            double maxWallDelta = -Double.MAX_VALUE;
            int maxWallCornerIdx = -1;
            int maxWallIdx = -1; // 0:左, 1:右, 2:下, 3:上

            for (int i = 0; i < 4; i++) {
                double cx = xGNew + localCorners[i][0] * cosH - localCorners[i][1] * sinH;
                double cy = yGNew + localCorners[i][0] * sinH + localCorners[i][1] * cosH;

                double dLeft = 0.0 - cx;
                if (dLeft > maxWallDelta) {
                    maxWallDelta = dLeft;
                    maxWallCornerIdx = i;
                    maxWallIdx = 0;
                }
                double dRight = cx - 7.0;
                if (dRight > maxWallDelta) {
                    maxWallDelta = dRight;
                    maxWallCornerIdx = i;
                    maxWallIdx = 1;
                }
                double dBottom = 0.0 - cy;
                if (dBottom > maxWallDelta) {
                    maxWallDelta = dBottom;
                    maxWallCornerIdx = i;
                    maxWallIdx = 2;
                }
                double dTop = cy - 7.0;
                if (dTop > maxWallDelta) {
                    maxWallDelta = dTop;
                    maxWallCornerIdx = i;
                    maxWallIdx = 3;
                }
            }

            // 障害物の最大貫通量の計算
            double maxObsDelta = -Double.MAX_VALUE;
            CollisionResult maxObsCollisionResult = null;
            FieldObstacle maxObsObstacle = null;

            if (fieldObstacles != null) {
                for (FieldObstacle obs : fieldObstacles) {
                    CollisionResult res = obs.collideOBB(xGNew, yGNew, headingNew, halfSize);
                    if (res != null && res.depth > maxObsDelta) {
                        maxObsDelta = res.depth;
                        maxObsCollisionResult = res;
                        maxObsObstacle = obs;
                    }
                }
            }

            // 壁と障害物の深い方を優先して整列に用いる
            boolean preferWall = false;
            boolean preferObs = false;

            if (maxWallDelta > 0.0 || maxObsDelta > 0.0) {
                if (maxWallDelta >= maxObsDelta) {
                    preferWall = true;
                } else {
                    preferObs = true;
                }
            }

            if (preferWall && maxWallDelta > 0.0) {
                double driveDir = Math.signum(vNew);
                if (driveDir == 0.0) {
                    driveDir = Math.signum(vTarget);
                }
                double h_push_dot_n_out = 0.0;
                if (maxWallIdx == 0) h_push_dot_n_out = driveDir * (-cosH);       // 左壁
                else if (maxWallIdx == 1) h_push_dot_n_out = driveDir * cosH;     // 右壁
                else if (maxWallIdx == 2) h_push_dot_n_out = driveDir * (-sinH);   // 下壁
                else if (maxWallIdx == 3) h_push_dot_n_out = driveDir * sinH;      // 上壁

                if (h_push_dot_n_out > 0.0) {
                    double target = Math.round(headingNew / (Math.PI / 2.0)) * (Math.PI / 2.0);
                    double diffTheta = normalizeAngle(target - headingNew);
                    double push = h_push_dot_n_out;

                    double dphi = config.k_align * diffTheta * push;
                    double limit = config.omegaAlignMax * dt;
                    dphi = clamp(dphi, -limit, limit);

                    if (Math.abs(dphi) > 1e-9) {
                        double cx = xGNew + localCorners[maxWallCornerIdx][0] * cosH - localCorners[maxWallCornerIdx][1] * sinH;
                        double cy = yGNew + localCorners[maxWallCornerIdx][0] * sinH + localCorners[maxWallCornerIdx][1] * cosH;

                        double dx = xGNew - cx;
                        double dy = yGNew - cy;
                        double cosRot = Math.cos(dphi);
                        double sinRot = Math.sin(dphi);

                        xGNew = cx + dx * cosRot - dy * sinRot;
                        yGNew = cy + dx * sinRot + dy * cosRot;
                        headingNew = normalizeAngle(headingNew + dphi);

                        xANew = xGNew + config.pivotOffsetD * Math.cos(headingNew);
                        yANew = yGNew + config.pivotOffsetD * Math.sin(headingNew);

                        omegaNew += dphi / dt;
                    }
                }
            } else if (preferObs && maxObsCollisionResult != null && maxObsCollisionResult.isEdgeOrigin) {
                // ポリゴンおよびカプセル障害物かつ衝突法線が辺・軸由来の場合のみ整列
                double driveDir = Math.signum(vNew);
                if (driveDir == 0.0) {
                    driveDir = Math.signum(vTarget);
                }
                double h_push_x = driveDir * cosH;
                double h_push_y = driveDir * sinH;

                // MTV方向（押し戻し方向）との内積の負値から押し付け量を求める
                double push = -(h_push_x * maxObsCollisionResult.mtvX + h_push_y * maxObsCollisionResult.mtvY) / maxObsCollisionResult.depth;

                if (push > 0.0) {
                    // 180°縛りと90°対称性を考慮した整列ターゲット角度の算出
                    double phi_edge = maxObsCollisionResult.edgeAngle;
                    double minDiff = Double.MAX_VALUE;
                    double targetAngle = phi_edge;
                    double[] candidates = {
                        phi_edge,
                        phi_edge - Math.PI / 2.0,
                        phi_edge + Math.PI / 2.0,
                        phi_edge + Math.PI
                    };
                    for (double cand : candidates) {
                        double diff = Math.abs(normalizeAngle(cand - headingNew));
                        if (diff < minDiff) {
                            minDiff = diff;
                            targetAngle = cand;
                        }
                    }

                    double diffTheta = normalizeAngle(targetAngle - headingNew);
                    double dphi = config.k_align * diffTheta * push;
                    double limit = config.omegaAlignMax * dt;
                    dphi = clamp(dphi, -limit, limit);

                    if (Math.abs(dphi) > 1e-9) {
                        // 接触点を支点とするピボット回転
                        double cx = maxObsCollisionResult.contactPoint[0];
                        double cy = maxObsCollisionResult.contactPoint[1];

                        double dx = xGNew - cx;
                        double dy = yGNew - cy;
                        double cosRot = Math.cos(dphi);
                        double sinRot = Math.sin(dphi);

                        xGNew = cx + dx * cosRot - dy * sinRot;
                        yGNew = cy + dx * sinRot + dy * cosRot;
                        headingNew = normalizeAngle(headingNew + dphi);

                        xANew = xGNew + config.pivotOffsetD * Math.cos(headingNew);
                        yANew = yGNew + config.pivotOffsetD * Math.sin(headingNew);

                        omegaNew += dphi / dt;
                    }
                }
            }
        }

        // 8. 位置の反復補正 (壁クランプ & 障害物押し戻しを3回反復)
        boolean hitWall = false;
        double cxWall = 0.0;
        double cyWall = 0.0;

        boolean obstacleCollisionFlag = false;
        String obstacleCollisionId = null;
        double obstacleCollisionPointX = 0.0;
        double obstacleCollisionPointY = 0.0;

        for (int iter = 0; iter < 3; iter++) {
            // 壁クランプ
            double cosH = Math.cos(headingNew);
            double sinH = Math.sin(headingNew);
            double robotRadius = config.robotSize / 2.0;
            double half = robotRadius * (Math.abs(cosH) + Math.abs(sinH));

            double xGClamped = clamp(xGNew, half, 7.0 - half);
            double deltaX = xGClamped - xGNew;
            if (deltaX != 0.0) {
                xGNew = xGClamped;
                xANew += deltaX;
                hitWall = true;
                cxWall = (deltaX > 0.0) ? 0.0 : 7.0;
                cyWall = yGNew;
            }

            double yGClamped = clamp(yGNew, half, 7.0 - half);
            double deltaY = yGClamped - yGNew;
            if (deltaY != 0.0) {
                yGNew = yGClamped;
                yANew += deltaY;
                hitWall = true;
                if (deltaX == 0.0) {
                    cxWall = xGNew;
                }
                cyWall = (deltaY > 0.0) ? 0.0 : 7.0;
            }

            // 障害物押し戻し
            if (fieldObstacles != null) {
                for (FieldObstacle obs : fieldObstacles) {
                    CollisionResult res = obs.collideOBB(xGNew, yGNew, headingNew, halfSize);
                    if (res != null) {
                        obstacleCollisionFlag = true;
                        obstacleCollisionId = obs.id();
                        obstacleCollisionPointX = res.contactPoint[0];
                        obstacleCollisionPointY = res.contactPoint[1];

                        xGNew += res.mtvX;
                        yGNew += res.mtvY;
                        xANew += res.mtvX;
                        yANew += res.mtvY;
                    }
                }
            }
        }

        // 9. A/v整合と進入方向速度クランプ
        double dxCorr = xGNew - xGStart;
        double dyCorr = yGNew - yGStart;
        double corrLen = Math.hypot(dxCorr, dyCorr);

        if (corrLen > 1e-6) {
            double nx = dxCorr / corrLen;
            double ny = dyCorr / corrLen;
            double ux = Math.cos(headingNew);
            double uy = Math.sin(headingNew);
            double dot = ux * nx + uy * ny;

            // 押し戻し方向と逆（めり込む方向）に移動しようとしている場合、速度を壁と平行な成分に射影（進入速度0化）
            if (vNew * dot < 0.0) {
                vNew = vNew * Math.sqrt(Math.max(0.0, 1.0 - dot * dot));
            }
        }

        // 最終的な車軸位置 A の再同期
        xANew = xGNew + config.pivotOffsetD * Math.cos(headingNew);
        yANew = yGNew + config.pivotOffsetD * Math.sin(headingNew);

        // 10. 走行距離と停止時間の計算
        double dxG = xGNew - prevState.x;
        double dyG = yGNew - prevState.y;
        double ds = Math.sqrt(dxG * dxG + dyG * dyG);
        double cumulativeDistanceNew = prevState.cumulativeDistance + ds;

        // 左右の実際の車輪速度の逆算
        double leftWheelSpeed = vNew - (omegaNew * config.trackWidth / 2.0);
        double rightWheelSpeed = vNew + (omegaNew * config.trackWidth / 2.0);

        // 停止判定
        boolean isStoppedNew = Math.abs(vNew) < 0.01 && Math.abs(omegaNew) < 0.01;
        double cumulativeStopTimeNew = prevState.cumulativeStopTime;
        if (isStoppedNew) {
            cumulativeStopTimeNew += dt;
        }

        // 累積統計のインクリメント
        int cumulativeObstacleCollisionCount = prevState.cumulativeObstacleCollisionCount;
        double cumulativeObstacleCollisionTime = prevState.cumulativeObstacleCollisionTime;
        if (obstacleCollisionFlag) {
            cumulativeObstacleCollisionTime += dt;
            if (!prevState.obstacleCollisionFlag) {
                cumulativeObstacleCollisionCount++;
            }
        }

        return new RobotState(
            prevState.time + dt,
            currentDateTime,
            xGNew,
            yGNew,
            xANew,
            yANew,
            headingNew,
            vNew,
            omegaNew,
            leftWheelSpeed,
            rightWheelSpeed,
            realAccelV,
            realAccelOmega,
            rawInputY,
            rawInputX,
            vTarget,
            omegaTarget,
            slipFlag,
            Math.abs(vSlideNew),
            cumulativeDistanceNew,
            ds,
            isStoppedNew,
            cumulativeStopTimeNew,
            hitWall,
            false, // collisionFlag (NPC衝突は後ほどManagerで判定)
            -1,    // collisionNpcId
            hitWall ? cxWall : 0.0, // 壁衝突時の接触点
            hitWall ? cyWall : 0.0,
            prevState.cumulativeCollisionCount,
            prevState.cumulativeCollisionTime,
            obstacleCollisionFlag,
            obstacleCollisionId,
            obstacleCollisionPointX,
            obstacleCollisionPointY,
            cumulativeObstacleCollisionCount,
            cumulativeObstacleCollisionTime,
            prevState.ballContactFlag,
            prevState.cumulativeBallContactCount,
            prevState.heldBallCount,
            prevState.intakeActive,
            prevState.scoreCount,
            prevState.shootActive,
            prevState.shotAttemptCount,
            prevState.whiteScoreCount
        );
    }

    /**
     * アクティブブレーキを適用し、制動後の速度と発生した制動加速度を返します。
     * ゼロ横切りガード（数値振動・逆走防止）を内包します。
     */
    private static double[] applyActiveBrake(double currentSpeed, double gain, double dt) {
        double aBrake = gain * Math.abs(currentSpeed);
        double decel = -Math.signum(currentSpeed) * aBrake;
        double nextSpeed = currentSpeed + decel * dt;
        double realAccel = decel;

        if ((nextSpeed * currentSpeed < 0.0) || Math.abs(nextSpeed) <= 0.001) {
            nextSpeed = 0.0;
            realAccel = (dt > 0) ? -currentSpeed / dt : 0.0;
        }
        return new double[]{nextSpeed, realAccel};
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}
