package dev.rstminecraft;

import baritone.api.BaritoneAPI;
import dev.rstminecraft.utils.MsgLevel;
import dev.rstminecraft.utils.RSTTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static dev.rstminecraft.RSTFireballProtect.FireballProtector;
import static dev.rstminecraft.RustElytraClient.*;
import static dev.rstminecraft.utils.RSTConfig.setBoolean;
import static dev.rstminecraft.utils.RSTTask.scheduleTask;

public class TaskThread extends Thread {
    private static @Nullable TaskThread ModThread = null;
    private final int TargetX, TargetZ;
    private final boolean isAutoLog, isAutoLogOnSeg1;
    private final boolean isXP;

    private TaskThread(boolean isXP, boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ) {
        this.isXP = isXP;
        this.isAutoLog = isAutoLog;
        this.isAutoLogOnSeg1 = isAutoLogOnSeg1;
        this.TargetX = TargetX;
        this.TargetZ = TargetZ;
    }

    public static @Nullable TaskThread getModThread() {
        return ModThread;
    }

    public static boolean isThreadRunning() {
        return ModThread != null;
    }

    private static @NotNull String threadDesc(@Nullable Thread thread) {
        if (thread == null) {
            return "null";
        }
        return thread.getName() + "#" + thread.getId() + "/" + thread.getState();
    }

    public static void StartModThread_ELY(boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ) {
        TaskThread oldThread = ModThread;
        if (oldThread != null) {
            MODLOGGER.warn("[RST TaskTrace] StartModThread_ELY replacing existing thread old={} target=({}, {})", threadDesc(oldThread), TargetX, TargetZ);
        }
        TaskThread newThread = new TaskThread(false, isAutoLog, isAutoLogOnSeg1, TargetX, TargetZ);
        newThread.setName("RST-ELY-" + System.nanoTime());
        ModThread = newThread;
        MODLOGGER.info("[RST TaskTrace] StartModThread_ELY new={} target=({}, {})", threadDesc(newThread), TargetX, TargetZ);
        newThread.start();
    }

    public static void StartModThread_XP(boolean isAutoLog, boolean isAutoLogOnSeg1, int TargetX, int TargetZ) {
        TaskThread oldThread = ModThread;
        if (oldThread != null) {
            MODLOGGER.warn("[RST TaskTrace] StartModThread_XP replacing existing thread old={} target=({}, {})", threadDesc(oldThread), TargetX, TargetZ);
        }
        TaskThread newThread = new TaskThread(true, isAutoLog, isAutoLogOnSeg1, TargetX, TargetZ);
        newThread.setName("RST-XP-" + System.nanoTime());
        ModThread = newThread;
        MODLOGGER.info("[RST TaskTrace] StartModThread_XP new={} target=({}, {})", threadDesc(newThread), TargetX, TargetZ);
        newThread.start();
    }

    public static void delay(int ticks) {
        if (!(Thread.currentThread() instanceof TaskThread)) {
            return;
        }
        if (ModStatus == ModStatuses.canceled) {
            RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
            MinecraftClient.getInstance().options.forwardKey.setPressed(false);
            ModStatus = ModStatuses.idle;
            throw new TaskCanceled();
        }
        for (int i = 0; i < ticks; i++) {
            try {
                synchronized (ThreadLock) {
                    ThreadLock.wait(2147483647);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static <T> T RunAsMainThread(@NotNull Supplier<T> lambda) {
        return runAsMainThreadInternal(lambda);
    }

    public static <T> T RunAsMainThread2(@NotNull Supplier<T> lambda) {
        return runAsMainThreadInternal(lambda);
    }

    private static <T> T runAsMainThreadInternal(@NotNull Supplier<T> lambda) {
        if (Thread.currentThread() != ModThread) {
            return lambda.get();
        }

        CountDownLatch latch = new CountDownLatch(1);
        TaskHolder<T> holder = new TaskHolder<>(lambda, latch);

        if (!currentTask.compareAndSet(null, holder)) {
            throw new TaskException("同时只能存在一个任务");
        }
        try {
            latch.await();
            return holder.getResult();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskException("任务执行异常");
        }
    }

    public static void RunAsMainThread(@NotNull Runnable lambda) {
        RunAsMainThread(() -> {
            lambda.run();
            return null;
        });
    }

    public void taskFailed(@NotNull MinecraftClient client, @NotNull String str, int seg) {
        MinecraftClient.getInstance().options.forwardKey.setPressed(false);
        MinecraftClient.getInstance().options.jumpKey.setPressed(false);
        MinecraftClient.getInstance().options.useKey.setPressed(false);

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive()
                || BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()) {
            RunAsMainThread(() -> BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop"));
        }
        if (seg == -1 && isAutoLogOnSeg1 || seg != -1 && isAutoLog) {
            MutableText text = Text.literal("[RSTAutoLog] ");
            text.append(Text.literal(str));
            if (client.player != null) {
                client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
            }
        } else if (client.player != null) {
            MsgSender.SendMsg(client.player, "任务结束。" + str, MsgLevel.fatal);
        }
        ModStatus = ModStatuses.idle;
    }

    @Override
    public void run() {
        MODLOGGER.info("[RST TaskTrace] run-enter thread={} target=({}, {}) mode={}", threadDesc(this), TargetX, TargetZ, isXP ? "xp" : "elytra");
        try {
            RealRun();
        } finally {
            timerMultiplier = 1;
            cameraMixinSwitch = false;
            if (ModThread == this) {
                ModThread = null;
                MODLOGGER.info("[RST TaskTrace] run-exit cleared ModThread for {}", threadDesc(this));
            } else {
                MODLOGGER.warn("[RST TaskTrace] run-exit thread mismatch exitThread={} currentModThread={}", threadDesc(this), threadDesc(ModThread));
            }
        }
    }

    private void RealRun() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        ModStatus = ModStatuses.running;
        for (int nowSeg = 0; ; nowSeg++) {
            try {
                MsgSender.SendMsg(client.player, "第" + nowSeg + "段补给任务开始！", MsgLevel.info);
                float h = client.player.getHealth();
                int finalNowSeg = nowSeg;
                RSTTask fireballTask = scheduleTask((self, args) -> {
                    if (!FireballProtector(client)) {
                        ModStatus = ModStatuses.canceled;
                        self.repeatTimes = 0;
                        MsgSender.SendMsg(client.player, "无法拦截火球", MsgLevel.fatal);
                        taskFailed(client, "补给任务失败！自动退出！", finalNowSeg - 1);
                        return;
                    }
                    if (client.player.getHealth() < h) {
                        self.repeatTimes = 0;
                        taskFailed(client, "补给过程受伤！紧急！", finalNowSeg - 1);
                        ModStatus = ModStatuses.canceled;
                    }
                }, 1, -1, 1, 100);

                try {
                    RustSupplyTask.SupplyTask(client, isXP);
                    synchronized (fireballTask) {
                        fireballTask.repeatTimes = -2;
                    }
                    delay(1);
                } catch (TaskException e) {
                    synchronized (fireballTask) {
                        fireballTask.repeatTimes = -2;
                    }
                    e.printStackTrace();
                    MsgSender.SendMsg(client.player, e.getMessage(), MsgLevel.error);
                    MsgSender.SendMsg(client.player, "补给任务失败", MsgLevel.fatal);
                    taskFailed(client, "补给任务失败！自动退出！", nowSeg - 1);
                    return;
                } catch (TaskCanceled e) {
                    synchronized (fireballTask) {
                        fireballTask.repeatTimes = -2;
                    }
                    MsgSender.SendMsg(client.player, "任务中止！", MsgLevel.warning);
                    return;
                }

                MsgSender.SendMsg(client.player, "第" + nowSeg + "段飞行任务开始！", MsgLevel.info);

                RSTTask autoLogTask = scheduleTask((self, args) -> {
                    if (client.player != null && autoLogEnabled && TaskThread.isThreadRunning()) {
                        if (client.player.getHealth() < 3.5) {
                            int count = 0;
                            for (int i = 0; i < 45; i++) {
                                if (client.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                                    count += client.player.getInventory().getStack(i).getCount();
                                }
                            }
                            if (count <= 1) {
                                autoLogEnabled = false;
                                setBoolean("autoLogEnabled", false);
                                ModStatus = ModStatuses.canceled;
                                self.repeatTimes = 0;
                                taskFailed(client, "AutoLog图腾数量过少且血量过低！", finalNowSeg);
                            }
                        }
                    }
                }, 1, -1, 1, 100);

                try {
                    if (RustElytraTask.ElytraTask(client, this.TargetX, this.TargetZ, isXP)) {
                        MsgSender.SendMsg(client.player, "到达目的地！圆满完成！！！", MsgLevel.warning);
                        if (isAutoLog) {
                            MutableText text = Text.literal("[RSTAutoLog] ");
                            text.append(Text.literal("已经到达目的地"));
                            if (client.player != null) {
                                client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
                            }
                        }
                        ModStatus = ModStatuses.idle;
                        synchronized (autoLogTask) {
                            autoLogTask.repeatTimes = -2;
                        }
                        return;
                    }
                    synchronized (autoLogTask) {
                        autoLogTask.repeatTimes = -2;
                    }
                    delay(1);
                } catch (TaskException e) {
                    MsgSender.SendMsg(client.player, e.getMessage(), MsgLevel.error);
                    e.printStackTrace();
                    taskFailed(client, e.getMessage(), nowSeg);
                    synchronized (autoLogTask) {
                        autoLogTask.repeatTimes = -2;
                    }
                    return;
                } catch (TaskCanceled e) {
                    MsgSender.SendMsg(client.player, "任务中止！", MsgLevel.warning);
                    synchronized (autoLogTask) {
                        autoLogTask.repeatTimes = -2;
                    }
                    return;
                }
            } catch (NullPointerException e) {
                e.printStackTrace();
                taskFailed(client, e.getMessage(), nowSeg);
                return;
            }
        }
    }

    public static class TaskException extends RuntimeException {
        public TaskException(String reason) {
            super(reason);
        }
    }

    public static class TaskCanceled extends RuntimeException {
        public TaskCanceled() {
            super("任务已经取消");
        }
    }
}
