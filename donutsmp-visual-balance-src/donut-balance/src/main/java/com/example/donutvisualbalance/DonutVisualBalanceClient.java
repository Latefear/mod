package com.example.donutvisualbalance;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class DonutVisualBalanceClient implements ClientModInitializer {
    private static String amount = "$1,000,000,000";

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int BG_COLOR = 0x90000000;

    @Override
    public void onInitializeClient() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.of("donutsmp_visual_balance", "balance"),
            (context, tickCounter) -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.options.hudHidden) return;

                String text = "Balance: " + amount;
                int w = mc.textRenderer.getWidth(text);
                int h = mc.textRenderer.fontHeight;
                int x = 6, y = 6;
                context.fill(x - 3, y - 3, x + w + 3, y + h + 3, BG_COLOR);
                context.drawTextWithShadow(mc.textRenderer, text, x, y, TEXT_COLOR);
            });

        // /fakebalance $5,000  -> changes the displayed text (visual only)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("fakebalance")
                .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        amount = StringArgumentType.getString(ctx, "text");
                        ctx.getSource().sendFeedback(Text.literal("Display set to: " + amount));
                        return 1;
                    }))));
    }
}
