package me.d1n0.saddle.debugger;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves command-text targets — entity selectors and (relative) block
 * coordinates — against a suspended frame's command source, powering editor
 * hovers and the "Command" scope. Server thread only.
 */
public final class DebugTargets {
    private static final String COORD = "(?:[~^]-?(?:\\d+(?:\\.\\d+)?)?|-?\\d+(?:\\.\\d+)?)";
    private static final Pattern COORD_TRIPLE =
            Pattern.compile("(?<![\\w~^.-])" + COORD + "\\s+" + COORD + "\\s+" + COORD + "(?![\\w.-])");
    private static final Pattern SELECTOR_START = Pattern.compile("@[a-z]");

    private DebugTargets() {}

    public static CommandSourceStack sourceOrServer(Object source) {
        return source instanceof CommandSourceStack css
                ? css
                : DebugSession.server().createCommandSourceStack();
    }

    public static List<? extends Entity> selectEntities(String selector, Object source)
            throws CommandSyntaxException {
        return new EntitySelectorParser(new StringReader(selector.trim()), true)
                .parse()
                .findEntities(sourceOrServer(source).withSuppressedOutput());
    }

    /** Resolves absolute, {@code ~} and {@code ^} coordinates like /setblock. */
    public static BlockPos resolveBlockPos(String coords, Object source) throws CommandSyntaxException {
        return BlockPosArgument.blockPos()
                .parse(new StringReader(coords.trim()))
                .getBlockPos(sourceOrServer(source));
    }

    /** Entity selectors appearing in a command line, e.g. {@code @e[type=pig]}. */
    public static List<String> findSelectors(String commandText) {
        List<String> selectors = new ArrayList<>();
        Matcher matcher = SELECTOR_START.matcher(commandText);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (end < commandText.length() && commandText.charAt(end) == '[') {
                int close = matchBrackets(commandText, end);
                if (close < 0) continue;
                end = close + 1;
            }
            selectors.add(commandText.substring(start, end));
        }
        return selectors;
    }

    /** Coordinate triples appearing in a command line, e.g. {@code ~ ~2 ~}. */
    public static List<String> findCoordTriples(String commandText) {
        List<String> triples = new ArrayList<>();
        Matcher matcher = COORD_TRIPLE.matcher(commandText);
        while (matcher.find()) {
            triples.add(matcher.group().trim());
        }
        return triples;
    }

    /** Index just past a balanced {@code [...]} region, honoring nesting and quotes. */
    private static int matchBrackets(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
