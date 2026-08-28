package evo.soulboundspawners.config;

import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the {@code prices:} list ({@code TYPE:amount}, or {@code ALL:amount}). */
public final class Prices {

    private final Map<EntityType, Double> perType = new HashMap<>();
    private boolean allSame;
    private double global;

    public Prices(List<String> lines) {
        for (String line : lines) {
            int i = line.lastIndexOf(':');
            if (i <= 0) continue;
            String key = line.substring(0, i).trim();
            double val;
            try {
                val = Double.parseDouble(line.substring(i + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (key.equalsIgnoreCase("all")) {
                allSame = true;
                global = val;
                return;
            }
            try {
                perType.put(EntityType.valueOf(key.toUpperCase(Locale.ROOT)), val);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public double priceFor(EntityType type) {
        if (!allSame && type != null && perType.containsKey(type)) return perType.get(type);
        return allSame ? global : 0;
    }
}
