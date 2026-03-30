package dev.rstminecraft;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

public class NoBaritone extends Screen {
    private final NoBaritoneReason reason;
    private final boolean mustQuit;

    public NoBaritone(NoBaritoneReason reason, boolean mustQuit) {
        super(Text.literal("缺少核心依赖"));
        this.reason = reason;
        this.mustQuit = mustQuit;
    }

    @Override
    protected void init() {
        if (mustQuit) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("退出游戏"), button -> {
                if (this.client != null) {
                    this.client.scheduleStop();
                }
            }).dimensions(this.width / 2 - 100, this.height / 2 + 20, 200, 20).build());
        } else {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("退出游戏"), button -> {
                if (this.client != null) {
                    this.client.scheduleStop();
                }
            }).dimensions(this.width / 3 - 60, this.height / 2 + 20, 120, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> {
                if (this.client != null) {
                    this.client.setScreen(null);
                }
            }).dimensions(this.width / 3 * 2 - 60, this.height / 2 + 20, 120, 20).build());
        }
    }

    @Override
    public void render(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        switch (reason) {
            case NoModId -> {
                context.drawCenteredTextWithShadow(this.textRenderer, "Rust Elytra Client 未检测到 Baritone 模组！", this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
                context.drawCenteredTextWithShadow(this.textRenderer, "请安装原生 Baritone 或 Meteor 版以运行模组！", this.width / 2, this.height / 2 - 30, 0xFFAAAAAA);
                context.drawCenteredTextWithShadow(this.textRenderer, "手机版用户可使用 BaritonePE。（详见QQ群）", this.width / 2, this.height / 2 - 10, 0xFFAAAAAA);
            }
            case NoAPI -> {
                context.drawCenteredTextWithShadow(this.textRenderer, "您似乎使用了错误的 Baritone（Standalone 版本）", this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
                context.drawCenteredTextWithShadow(this.textRenderer, "此版本不包含可供模组调用的 API，请使用 API 版、unoptimized 版或 Meteor 版。", this.width / 2, this.height / 2 - 30, 0xFFAAAAAA);
                context.drawCenteredTextWithShadow(this.textRenderer, "如果不确定用哪个版本，请使用群文件里的推荐版本。", this.width / 2, this.height / 2 - 10, 0xFFAAAAAA);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !mustQuit;
    }

    public enum NoBaritoneReason {
        NoModId, NoAPI
    }
}
