package com.example.donutvisualbalance;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonutVisualBalanceClient implements ClientModInitializer {
    private static double balance = 1_000_000_000d;
    private static boolean enabled = true;

    private static final int GREEN = 0xFF55FF55;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BG = 0x66000000;

    // Item name overrides (client-side display only)
    // Ctrl + 1-9 -> amethyst shard name, Alt + 1-9 -> echo shard name,
    // Ctrl/Alt + 0 -> reset that item to its real name.
    private static String amethystName = null;
    private static String echoName = null;
    private static final boolean[] wasDown = new boolean[10];

    // /fpay tab-completion: names come from this online list. If it hasn't
    // loaded (or the download failed), it falls back to the server's players.
    private static final String PLAYER_LIST_URL =
        "https://raw.githubusercontent.com/Latefear/mod/refs/heads/main/"
        + "donutsmp_expanded_player_list.txt";

    // Pulls each "name" out of the file, ignoring anything malformed
    private static final Pattern NAME_PATTERN =
        Pattern.compile("\"([A-Za-z0-9_]{1,16})\"");

    private static volatile List<String> playerList = List.of();

    private static final SuggestionProvider<FabricClientCommandSource>
        PLAYER_SUGGESTIONS = (ctx, builder) -> {
            List<String> names = playerList.isEmpty()
                ? new ArrayList<>(ctx.getSource().getPlayerNames())
                : playerList;
            return CommandSource.suggestMatching(names, builder);
        };

    /** Downloads the player list once in the background at startup. */
    private static void loadPlayerList() {
        Thread t = new Thread(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest
                    .newBuilder(URI.create(PLAYER_LIST_URL))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() == 200) {
                    List<String> names = new ArrayList<>();
                    Matcher m = NAME_PATTERN.matcher(response.body());
                    while (m.find()) {
                        names.add(m.group(1));
                    }
                    playerList = List.copyOf(names);
                }
            } catch (Exception e) {
                // Keep the fallback (server player list)
            }
        }, "fpay-player-list");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onInitializeClient() {
        loadPlayerList();

        HudElementRegistry.replaceElement(
            VanillaHudElements.SCOREBOARD,
            original -> (context, tickCounter) -> {
                MinecraftClient mc = MinecraftClient.getInstance();

                if (!enabled) {
                    original.render(context, tickCounter);
                    return;
                }

                if (mc.player == null || mc.options.hudHidden) return;

                TextRenderer tr = mc.textRenderer;
                String name = mc.player.getName().getString();
                String amount = formatCompact(balance);
                String dollar = "$";
                String gap = " ";

                int nameW = tr.getWidth(name);
                int numW = tr.getWidth(amount);
                int lineW = tr.getWidth(dollar + gap) + numW;
                int blockW = Math.max(nameW, lineW);

                int sw = context.getScaledWindowWidth();
                int sh = context.getScaledWindowHeight();
                int left = sw - 3 - blockW;
                int y1 = sh / 2 - 15;
                int y2 = y1 + tr.fontHeight;

                context.fill(
                    left - 2, y1 - 1, sw,
                    y2 + tr.fontHeight, BG
                );

                context.drawTextWithShadow(
                    tr, name,
                    left + (blockW - nameW) / 2,
                    y1, WHITE
                );

                int lineX = left + (blockW - lineW) / 2;

                context.drawTextWithShadow(
                    tr, dollar, lineX, y2, GREEN
                );

                context.drawTextWithShadow(
                    tr, amount,
                    lineX + tr.getWidth(dollar + gap),
                    y2, WHITE
                );
            }
        );

        // Item name override: key polling + tooltip rewrite
        ClientTickEvents.END_CLIENT_TICK.register(
            DonutVisualBalanceClient::nameKeyTick
        );

        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (stack.isOf(Items.AMETHYST_SHARD) && amethystName != null) {
                setTooltipName(lines, amethystName, Formatting.WHITE);
            } else if (stack.isOf(Items.ECHO_SHARD) && echoName != null) {
                setTooltipName(lines, echoName, Formatting.YELLOW);
            }
        });

        // Stop Ctrl/Alt + 1-9 from switching the hotbar slot in the world
        // (runs before the game handles input for the tick)
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.getWindow() == null) return;
            long handle = mc.getWindow().getHandle();
            if (modifierHeld(handle)) {
                for (KeyBinding kb : mc.options.hotbarKeys) {
                    while (kb.wasPressed()) {
                        // swallow the press
                    }
                }
            }
        });

        // Stop Ctrl/Alt + 1-9 from swapping the hovered item into the hotbar
        // while a container (like the dispenser) is open
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
            ScreenKeyboardEvents.allowKeyPress(screen).register(
                (s, input) -> {
                    if (client.getWindow() == null) return true;
                    long handle = client.getWindow().getHandle();
                    int key = input.key();
                    boolean number = key >= GLFW.GLFW_KEY_1
                        && key <= GLFW.GLFW_KEY_9;
                    return !(number && modifierHeld(handle));
                }
            )
        );

        ClientCommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess) -> {

                dispatcher.register(
                    ClientCommandManager.literal("fakebalance")
                        .then(ClientCommandManager.literal("off")
                            .executes(ctx -> {
                                enabled = false;
                                ctx.getSource().sendFeedback(
                                    Text.literal(
                                        "Fake balance off (real scoreboard shown)"
                                    )
                                );
                                return 1;
                            })
                        )
                        .then(ClientCommandManager.literal("on")
                            .executes(ctx -> {
                                enabled = true;
                                ctx.getSource().sendFeedback(
                                    Text.literal("Fake balance on")
                                );
                                return 1;
                            })
                        )
                        .then(ClientCommandManager.argument(
                            "amount",
                            StringArgumentType.greedyString()
                        ).executes(ctx -> {
                            Double v = parse(
                                StringArgumentType.getString(ctx, "amount")
                            );

                            if (v == null) {
                                ctx.getSource().sendFeedback(
                                    Text.literal(
                                        "Invalid amount. Try 100M or 5.5B."
                                    )
                                );
                                return 0;
                            }

                            balance = v;
                            enabled = true;

                            ctx.getSource().sendFeedback(
                                Text.literal(
                                    "Fake balance set to $ "
                                    + formatCompact(balance)
                                )
                            );

                            return 1;
                        }))
                );

                // /fpay <name> <amount>
                // Local-only payment simulation.
                dispatcher.register(
                    ClientCommandManager.literal("fpay")
                        .then(ClientCommandManager.argument(
                            "name",
                            StringArgumentType.word()
                        ).suggests(PLAYER_SUGGESTIONS)
                        .then(ClientCommandManager.argument(
                            "amount",
                            StringArgumentType.word()
                        ).executes(ctx -> {
                            String target = StringArgumentType.getString(
                                ctx, "name"
                            );

                            Double v = parse(
                                StringArgumentType.getString(ctx, "amount")
                            );

                            if (v == null || v <= 0) {
                                ctx.getSource().sendFeedback(
                                    Text.literal("Invalid amount.")
                                        .formatted(Formatting.GRAY)
                                );
                                return 0;
                            }

                            if (v > balance) {
                                ctx.getSource().sendFeedback(
                                    Text.literal(
                                        "Not enough fake balance."
                                    ).formatted(Formatting.GRAY)
                                );
                                return 0;
                            }

                            balance -= v;

                            MutableText msg = Text.literal(
                                "You paid " + target + " "
                            ).formatted(Formatting.WHITE)
                                .append(
                                    Text.literal("$")
                                        .formatted(Formatting.GREEN)
                                )
                                .append(
                                    Text.literal(
                                        " " + formatCompact(v)
                                    ).formatted(Formatting.WHITE)
                                );

                            MinecraftClient mc =
                                MinecraftClient.getInstance();

                            mc.inGameHud.getChatHud().addMessage(msg);
                            mc.inGameHud.setOverlayMessage(msg, false);

                            return 1;
                        })))
                );
            }
        );
    }

    // ---------------------------------------------------------------
    // Item name override helpers
    // ---------------------------------------------------------------

    private static void nameKeyTick(MinecraftClient mc) {
        if (mc.getWindow() == null) return;
        long handle = mc.getWindow().getHandle();

        boolean ctrl = isKeyDown(handle, GLFW.GLFW_KEY_LEFT_CONTROL)
            || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = isKeyDown(handle, GLFW.GLFW_KEY_LEFT_ALT)
            || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_ALT);

        for (int n = 0; n <= 9; n++) {
            boolean down = isKeyDown(handle, GLFW.GLFW_KEY_0 + n);
            boolean pressedNow = down && !wasDown[n];
            wasDown[n] = down;

            if (!pressedNow) continue;

            if (ctrl && !alt) {
                amethystName = (n == 0) ? null : String.valueOf(n);
            } else if (alt && !ctrl) {
                echoName = (n == 0) ? null : String.valueOf(n);
            }
        }
    }

    private static boolean isKeyDown(long handle, int key) {
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }

    /**
     * Used by ItemStackMixin. Returns the fake name for this stack, or null
     * if the item has no override (so the real name is used).
     */
    public static Text getFakeName(ItemStack stack) {
        if (stack.isOf(Items.AMETHYST_SHARD) && amethystName != null) {
            return Text.literal(amethystName).formatted(Formatting.WHITE);
        }
        if (stack.isOf(Items.ECHO_SHARD) && echoName != null) {
            return Text.literal(echoName).formatted(Formatting.YELLOW);
        }
        return null;
    }

    private static boolean modifierHeld(long handle) {
        return isKeyDown(handle, GLFW.GLFW_KEY_LEFT_CONTROL)
            || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_CONTROL)
            || isKeyDown(handle, GLFW.GLFW_KEY_LEFT_ALT)
            || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    /** The first tooltip line is the item name, so swap just that line. */
    private static void setTooltipName(
        List<Text> lines, String nameValue, Formatting color
    ) {
        if (lines.isEmpty()) return;
        lines.set(0, Text.literal(nameValue).formatted(color));
    }

    // ---------------------------------------------------------------
    // Balance parsing / formatting
    // ---------------------------------------------------------------

    /**
     * Parses amounts such as 12, 1.5K, 250M, 2B, and $1,000.
     */
    private static Double parse(String input) {
        String s = input.trim()
            .replace("$", "")
            .replace(",", "")
            .replace(" ", "")
            .toUpperCase();

        if (s.isEmpty()) return null;

        double mult = 1;
        char last = s.charAt(s.length() - 1);

        switch (last) {
            case 'K' -> mult = 1e3;
            case 'M' -> mult = 1e6;
            case 'B' -> mult = 1e9;
            case 'T' -> mult = 1e12;
            default -> { }
        }

        if (mult != 1) {
            s = s.substring(0, s.length() - 1);
        }

        try {
            double v = Double.parseDouble(s) * mult;

            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                return null;
            }

            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Formats values compactly. Millions and above are rounded to whole
     * numbers (30.9B becomes 31B); below that, one decimal is kept (1.5K).
     */
    private static String formatCompact(double v) {
        String[] suffix = {"", "K", "M", "B", "T"};
        int i = 0;

        while (v >= 1000 && i < suffix.length - 1) {
            v /= 1000;
            i++;
        }

        // Millions and up: whole numbers only
        if (i >= 2) {
            long rounded = Math.round(v);

            // 999.6M rounds to 1000M, so roll over to 1B
            if (rounded >= 1000 && i < suffix.length - 1) {
                rounded = 1;
                i++;
            }

            return rounded + suffix[i];
        }

        String num = (i == 0 && v == Math.floor(v))
            ? String.valueOf((long) v)
            : String.format(java.util.Locale.US, "%.1f", v);

        if (num.endsWith(".0")) {
            num = num.substring(0, num.length() - 2);
        }

        return num + suffix[i];
    }
}
