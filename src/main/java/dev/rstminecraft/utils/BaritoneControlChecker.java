package dev.rstminecraft.utils;

import baritone.api.BaritoneAPI;
import baritone.api.process.IBaritoneProcess;
import net.minecraft.client.MinecraftClient;

public class BaritoneControlChecker {
    public static boolean lookFlag = false;

    public static boolean isControlPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean gliding = client.player != null && client.player.isGliding();
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            return lookFlag;
        }
        if (!gliding) {
            return lookFlag;
        }
        try {
            return baritone.getPathingControlManager().mostRecentInControl()
                    .map(process -> process == baritone.getElytraProcess())
                    .orElse(lookFlag);
        } catch (Throwable ignored) {
            return lookFlag;
        }
    }

    public static boolean isPathPaused() {
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            return false;
        }
        try {
            return baritone.getPathingControlManager().mostRecentInControl()
                    .filter(IBaritoneProcess::isTemporary)
                    .map(IBaritoneProcess::displayName)
                    .filter("Pause/Resume Commands"::equals)
                    .isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
