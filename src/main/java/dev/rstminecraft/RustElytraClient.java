package dev.rstminecraft;

// 提示：本代码完全由RSTminecraft 编写，部分内容可能不符合编程规范，有意愿者请修改。
// 关于有人质疑后门的事，请自行阅读代码，你要是能找出后门，我把电脑吃了。
// 本模组永不收费，永远开源，许可证相关事项正在考虑。
// 文件解释：本文件为模组主文件。

import baritone.api.BaritoneAPI;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.rstminecraft.utils.BaritoneControlChecker;
import dev.rstminecraft.utils.MsgLevel;
import dev.rstminecraft.utils.RSTMsgSender;
import dev.rstminecraft.utils.TrajectoryRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static dev.rstminecraft.utils.RSTConfig.getBoolean;
import static dev.rstminecraft.utils.RSTConfig.loadConfig;
import static dev.rstminecraft.utils.RSTTask.scheduleTask;
import static dev.rstminecraft.utils.RSTTask.tick;

public class RustElytraClient implements ClientModInitializer {
    public static final Logger MODLOGGER = LoggerFactory.getLogger("rust-elytra-client");
    public static final AtomicReference<TaskHolder<?>> currentTask = new AtomicReference<>();
    public static final Item[] FoodList = {
            Items.GOLDEN_CARROT,
            Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN
    };
    static final Object ThreadLock = new Object();
    public static int currentTick = 0;
    public static boolean autoLogEnabled = false;
    public static boolean cameraMixinSwitch = false;
    public static float fixedYaw = 0f, fixedPitch = 0f;
    public static float timerMultiplier = 1f;
    public static RSTMsgSender MsgSender;
    static @NotNull ModStatuses ModStatus = ModStatuses.idle;
    private static final String AUTO_ELYTRA_USAGE = "/RSTAutoElytra [elytra|xp] [x] [z]";
    private static KeyBinding openCustomScreenKey;

    FabricLoader loader = FabricLoader.getInstance();

    @Override
    public void onInitializeClient() {
        boolean hasBaritone = loader.isModLoaded("baritone") || loader.isModLoaded("baritone-meteor");
        if (!hasBaritone) {
            MODLOGGER.error(" [MyMod] 需要安装 Baritone（baritone / baritone-meteor 任选其一）");
        }
        loadConfig(FabricLoader.getInstance().getConfigDir().resolve("RSTConfig.json"));
        MsgSender = new RSTMsgSender(getBoolean("DisplayDebug", false) ? MsgLevel.debug : MsgLevel.info);
        autoLogEnabled = getBoolean("autoLogEnabled", false);

        openCustomScreenKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("RST Auto Elytra Mod主界面", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "RST Auto Elytra Mod")
        );
        TrajectoryRenderer.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            currentTick++;
            if (TaskThread.isThreadRunning()) {
                synchronized (ThreadLock) {
                    ThreadLock.notify();
                }
                try {
                    while (TaskThread.getModThread() != null
                            && !(TaskThread.getModThread().getState() == Thread.State.TERMINATED
                            || TaskThread.getModThread().getState() == Thread.State.TIMED_WAITING)) {
                        TaskHolder<?> task = currentTask.get();
                        if (task != null) {
                            task.execute();
                            currentTask.set(null);
                        }
                    }
                } catch (NullPointerException e) {
                    if (e.getMessage() == null || !e.getMessage().contains("TaskThread.getState")) {
                        throw e;
                    }
                }
            }
            tick();

            if (client.player != null && openCustomScreenKey.isPressed()) {
                client.setScreen(new RSTScr(MinecraftClient.getInstance().currentScreen, getBoolean("FirstUse", true)));
            }

            BaritoneControlChecker.lookFlag = false;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("RSTAutoElytraMenu").executes(context -> {
                    scheduleTask((s, a) -> MinecraftClient.getInstance().setScreen(
                            new RSTScr(MinecraftClient.getInstance().currentScreen, getBoolean("FirstUse", true))
                    ), 1, 0, 2, 100000);
                    return 1;
                })
        ));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("RSTDebug-IDLE").executes(context -> {
                    ModStatus = ModStatuses.idle;
                    TrajectoryRenderer.path.clear();
                    return 1;
                })
        ));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("RSTAutoElytra")
                        .executes(context -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            return tryStartElytraTaskFromGoal(client, FlightMode.ELYTRA) ? 1 : 0;
                        })
                        .then(ClientCommandManager.literal("elytra")
                                .executes(context -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    return tryStartElytraTaskFromGoal(client, FlightMode.ELYTRA) ? 1 : 0;
                                })
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> {
                                                    MinecraftClient client = MinecraftClient.getInstance();
                                                    int targetX = IntegerArgumentType.getInteger(context, "x");
                                                    int targetZ = IntegerArgumentType.getInteger(context, "z");
                                                    return tryStartElytraTask(client, targetX, targetZ, FlightMode.ELYTRA, false) ? 1 : 0;
                                                }))))
                        .then(ClientCommandManager.literal("xp")
                                .executes(context -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    return tryStartElytraTaskFromGoal(client, FlightMode.XP) ? 1 : 0;
                                })
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> {
                                                    MinecraftClient client = MinecraftClient.getInstance();
                                                    int targetX = IntegerArgumentType.getInteger(context, "x");
                                                    int targetZ = IntegerArgumentType.getInteger(context, "z");
                                                    return tryStartElytraTask(client, targetX, targetZ, FlightMode.XP, false) ? 1 : 0;
                                                }))))
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            MinecraftClient client = MinecraftClient.getInstance();
                                            int targetX = IntegerArgumentType.getInteger(context, "x");
                                            int targetZ = IntegerArgumentType.getInteger(context, "z");
                                            return tryStartElytraTask(client, targetX, targetZ, FlightMode.ELYTRA, false) ? 1 : 0;
                                        })))
        ));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (ModStatus != ModStatuses.idle) {
                ModStatus = ModStatuses.canceled;
            }
        });
    }

    enum ModStatuses {
        idle, running, canceled
    }

    private static boolean tryStartElytraTaskFromGoal(@NotNull MinecraftClient client, @NotNull FlightMode mode) {
        int[] goalXZ = resolveBaritoneGoalXZ();
        if (goalXZ == null) {
            if (client.player != null) {
                MsgSender.SendMsg(client.player, "未找到可用的 Baritone 坐标目标，请先设置 goal。用法: " + AUTO_ELYTRA_USAGE, MsgLevel.warning);
            }
            return false;
        }
        return tryStartElytraTask(client, goalXZ[0], goalXZ[1], mode, true);
    }

    private static boolean tryStartElytraTask(@NotNull MinecraftClient client, int targetX, int targetZ, @NotNull FlightMode mode, boolean fromBaritoneGoal) {
        if (client.player == null) {
            return false;
        }
        if (TaskThread.getModThread() != null) {
            MsgSender.SendMsg(client.player, "已有任务正在运行", MsgLevel.warning);
            return false;
        }
        if (fromBaritoneGoal) {
            MsgSender.SendMsg(client.player, "无坐标模式：使用 Baritone goal 坐标 X=" + targetX + " Z=" + targetZ + "，模式=" + mode.displayName, MsgLevel.info);
        }
        MsgSender.SendMsg(client.player, "任务开始！", MsgLevel.warning);
        if (mode == FlightMode.XP) {
            TaskThread.StartModThread_XP(getBoolean("isAutoLog", true), getBoolean("isAutoLogOnSeg1", false), targetX, targetZ);
        } else {
            TaskThread.StartModThread_ELY(getBoolean("isAutoLog", true), getBoolean("isAutoLogOnSeg1", false), targetX, targetZ);
        }
        return true;
    }

    private enum FlightMode {
        ELYTRA("elytra"),
        XP("xp");

        private final String displayName;

        FlightMode(String displayName) {
            this.displayName = displayName;
        }
    }

    private static int[] resolveBaritoneGoalXZ() {
        Object primaryBaritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (primaryBaritone == null) {
            return null;
        }
        Object customGoalProcess = invokeNoArg(primaryBaritone, "getCustomGoalProcess");
        if (customGoalProcess == null) {
            return null;
        }
        Object goal = invokeNoArg(customGoalProcess, "getGoal");
        if (goal == null) {
            goal = invokeNoArg(customGoalProcess, "mostRecentGoal");
        }
        if (goal == null) {
            return null;
        }

        Integer x = readGoalCoordinate(goal, "X");
        Integer z = readGoalCoordinate(goal, "Z");
        if (x == null || z == null) {
            return null;
        }
        return new int[]{x, z};
    }

    private static @Nullable Integer readGoalCoordinate(@NotNull Object goal, @NotNull String axisNameUpper) {
        Object value = invokeNoArg(goal, "get" + axisNameUpper);
        if (value == null) {
            value = invokeNoArg(goal, axisNameUpper.toLowerCase());
        }
        if (!(value instanceof Integer)) {
            value = readField(goal, axisNameUpper.toLowerCase());
        }
        if (!(value instanceof Integer)) {
            value = readField(goal, axisNameUpper);
        }
        return value instanceof Integer integerValue ? integerValue : null;
    }

    private static @Nullable Object invokeNoArg(@NotNull Object target, @NotNull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Object readField(@NotNull Object target, @NotNull String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    public static class TaskHolder<T> {
        private final Supplier<T> lambda;
        private final CountDownLatch latch;
        private T result;
        private Throwable error;

        TaskHolder(Supplier<T> lambda, CountDownLatch latch) {
            this.lambda = lambda;
            this.latch = latch;
        }

        void execute() {
            try {
                this.result = lambda.get();
            } catch (Throwable t) {
                this.error = t;
            } finally {
                latch.countDown();
            }
        }

        T getResult() {
            if (error != null) {
                throw new TaskThread.TaskException(error.getMessage());
            }
            return result;
        }
    }
}
