package com.fakeworth;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side only. Rewrites the worth line of item tooltips and the
 * total line of the /sell button. Nothing is sent to the server.
 *
 * In game: /fakeworth debug   prints the tooltip lines of whatever you hover into chat.
 */
public class FakeWorthClient implements ClientModInitializer {
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*[KkMmBbTt]?");
    // A worth line = some symbol(s) (like $ or ~$, or a custom glyph) followed by only a number: "$ 999K", "~$ 67"
    private static final Pattern WORTH_LINE = Pattern.compile("^[^A-Za-z0-9\\s]+\\s*\\d[\\d,.]*\\s*[KkMmBbTt]?\\s*$");

    private static FakeWorthConfig cfg;
    private static String lastDump = "";

    /** item id -> real worth per single item, learned from real tooltips you hover. */
    private static final Map<String, Double> REAL_UNIT = new HashMap<>();

    @Override
    public void onInitializeClient() {
        cfg = FakeWorthConfig.load();
        ItemTooltipCallback.EVENT.register(FakeWorthClient::onTooltip);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("fakeworth")
                        .then(ClientCommandManager.literal("debug").executes(ctx -> {
                            cfg.debug = !cfg.debug;
                            cfg.save();
                            ctx.getSource().sendFeedback(Text.literal("[fakeworth] debug " + (cfg.debug ? "ON - hover an item" : "OFF")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                            cfg.enabled = !cfg.enabled;
                            cfg.save();
                            ctx.getSource().sendFeedback(Text.literal("[fakeworth] " + (cfg.enabled ? "ON" : "OFF")));
                            return 1;
                        }))
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal("[fakeworth] loaded. /fakeworth debug  /fakeworth toggle"));
                            return 1;
                        })));
    }

    private static boolean isWorthLine(String s) {
        String t = s.trim();
        if (WORTH_LINE.matcher(t).matches()) return true;
        return t.contains("$") && !t.toLowerCase().contains("click") && NUMBER.matcher(t).find();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 32 && c < 127) sb.append(c);
            else sb.append(String.format("\\u%04X", (int) c));
        }
        return sb.toString();
    }

    private static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipType type, List<Text> lines) {
        if (cfg == null) return;

        if (cfg.debug) {
            StringBuilder sb = new StringBuilder(FakeWorthConfig.id(stack)).append(" x").append(stack.getCount()).append(": ");
            for (Text t : lines) sb.append("[").append(escape(t.getString())).append("] ");
            String dump = sb.toString();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!dump.equals(lastDump) && mc.player != null) {
                lastDump = dump;
                mc.player.sendMessage(Text.literal("[fakeworth] " + dump), false);
            }
        }

        if (!cfg.enabled) return;

        int worthLine = -1;
        boolean sellButton = false;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).getString();
            String lower = s.toLowerCase();
            if (lower.contains("sell items")) sellButton = true;
            if (worthLine == -1 && i > 0 && isWorthLine(s)) worthLine = i;
        }
        if (worthLine == -1) return;
        if (lines.get(worthLine).getString().trim().startsWith("~")) sellButton = true;

        Double realShown = WorthFormat.parse(lines.get(worthLine).getString());

        if (sellButton) {
            if (realShown == null) return;
            Double total = fakeSellTotal(realShown);
            if (total == null) return;
            lines.set(worthLine, replaceNumber(lines.get(worthLine), WorthFormat.format(total)));
            return;
        }

        Double configured = cfg.valueFor(stack);
        if (configured == null) return;

        // Remember the real worth before we overwrite it (used for the sell button total).
        if (realShown != null && stack.getCount() > 0) {
            // the real tooltip shows the worth of the whole stack, so divide to get one item
            REAL_UNIT.put(FakeWorthConfig.id(stack), realShown / stack.getCount());
        }

        double shown = cfg.stackValue(configured, stack);
        lines.set(worthLine, replaceNumber(lines.get(worthLine), WorthFormat.format(shown)));
    }

    /** Total for the sell button: fake items count at their fake value. Returns null if no faked items are in the sell menu. */
    private static Double fakeSellTotal(double realTotal) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !(mc.currentScreen instanceof HandledScreen<?> screen)) return null;

        double fake = 0;
        double realOfFake = 0;
        boolean any = false;
        boolean allKnown = true;

        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory == mc.player.getInventory()) continue; // only the sell container
            ItemStack s = slot.getStack();
            if (s.isEmpty()) continue;
            Double configured = cfg.valueFor(s);
            if (configured == null) continue;

            any = true;
            fake += cfg.stackValue(configured, s);
            Double unit = REAL_UNIT.get(FakeWorthConfig.id(s));
            if (unit == null) {
                allKnown = false;
            } else {
                realOfFake += unit * s.getCount();
            }
        }
        if (!any) return null;

        // If we never saw the real tooltip of a faked item, assume the whole real total came from them.
        double rest = allKnown ? Math.max(0, realTotal - realOfFake) : 0;
        return fake + rest;
    }

    /** Replaces the first number in the line, keeping every colour/style. */
    private static Text replaceNumber(Text line, String replacement) {
        MutableText out = Text.empty();
        boolean[] done = {false};
        line.<Boolean>visit((style, str) -> {
            Matcher m = NUMBER.matcher(str);
            if (!done[0] && m.find()) {
                done[0] = true;
                String r = str.substring(0, m.start()) + replacement + str.substring(m.end());
                out.append(Text.literal(r).setStyle(style));
            } else {
                out.append(Text.literal(str).setStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }
}
