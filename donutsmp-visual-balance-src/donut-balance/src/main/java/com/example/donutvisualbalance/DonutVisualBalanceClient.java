package com.example.donutvisualbalance;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

public class DonutVisualBalanceClient implements ClientModInitializer {
    private static String amount = "1B";
    private static boolean enabled = true;

    private static final int GREEN = 0xFF55FF55;
    private static final int WHITE = 0xFFFFFFFF;

    @Override
    public void onInitializeClient() {
        // Replace the server scoreboard sidebar with our fake one (when enabled).
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, original -> (context, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!enabled) {
                original.render(context, tickCounter);
                return;
            }
            if (mc.player == null || mc.options.hudHidden) return;

            TextRenderer tr = mc.textRenderer;
            String name = mc.player.getName().getString();
            String dollar = "$";
            String gap = " ";

            int nameW = tr.getWidth(name);
            int numW = tr.getWidth(amount);
            int lineW = tr.getWidth(dollar + gap) + numW;
            int blockW = Math.max(nameW, lineW);

            int sw = context.getScaledWindowWidth();
            int sh = context.getScaledWindowHeight();
            int left = sw - 3 - blockW;
            int y1 = sh / 2 - tr.fontHeight;
            int y2 = y1 + tr.fontHeight + 2;

            // Username, centered over the balance line
            context.drawTextWithShadow(tr, name, left + (blockW - nameW) / 2, y1, WHITE);

            // Green "$" then the amount in white, centered as one line
            int lineX = left + (blockW - lineW) / 2;
            context.drawTextWithShadow(tr, dollar, lineX, y2, GREEN);
            context.drawTextWithShadow(tr, amount, lineX + tr.getWidth(dollar + gap), y2, WHITE);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("fakebalance")
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    enabled = false;
                    ctx.getSource().sendFeedback(Text.literal("Fake balance off (real scoreboard shown)"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    enabled = true;
                    ctx.getSource().sendFeedback(Text.literal("Fake balance on"));
                    return 1;
                }))
                .then(ClientCommandManager.argument("amount", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        amount = format(StringArgumentType.getString(ctx, "amount"));
                        enabled = true;
                        ctx.getSource().sendFeedback(Text.literal("Display set to: $ " + amount));
                        return 1;
                    }))));
    }

    /** "100M", "5.5b" -> uppercase as typed. Plain numbers like 1500000000 -> "1.5B". */
    private static String format(String input) {
        String s = input.trim().replace("$", "").replace(",", "").replace(" ", "");
        if (s.matches("\\d+(\\.\\d+)?")) {
            double v = Double.parseDouble(s);
            String[] suffix = {"", "K", "M", "B", "T"};
            int i = 0;
            while (v >= 1000 && i < suffix.length - 1) { v /= 1000; i++; }
            String num = String.format("%.1f", v);
            if (num.endsWith(".0")) num = num.substring(0, num.length() - 2);
            return num + suffix[i];
        }
        return s.toUpperCase();
    }
}
