package evo.soulboundspawners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;

/**
 * Text helpers. Configured messages use legacy {@code &} colour codes
 * (optionally {@code &#rrggbb} hex), matching the original MineableSpawners
 * config, so that is what we parse.
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .hexCharacter('#')
            .build();

    private Text() {}

    /** Parse a legacy-coded string into a component. Never returns null. */
    public static Component color(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return LEGACY.deserialize(raw).decoration(TextDecoration.ITALIC, false);
    }

    /** Strip formatting to plain text. */
    public static String plain(String raw) {
        return PlainTextComponentSerializer.plainText().serialize(color(raw));
    }

    /** "IRON_GOLEM" -> "Iron Golem". Null/empty -> "Empty". */
    public static String prettyMob(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "Empty";
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? "Empty" : sb.toString();
    }
}
