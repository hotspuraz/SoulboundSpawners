package evo.soulboundspawners.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Optional Vault economy access, reached entirely by reflection so the plugin
 * has no compile-time dependency on Vault. Charging is off in the shipped
 * config; this exists so it works if an admin turns it on.
 */
public final class VaultHook {

    private final Logger log;
    private Object economy;
    private Method mHas;        // boolean has(OfflinePlayer, double)
    private Method mWithdraw;   // EconomyResponse withdrawPlayer(OfflinePlayer, double)
    private Method mBalance;    // double getBalance(OfflinePlayer)
    private Method mRespOk;     // boolean EconomyResponse.transactionSuccess()

    public VaultHook(Logger log) {
        this.log = log;
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public void setup() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) return;
            economy = rsp.getProvider();
            mHas = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            mWithdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            mBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            Class<?> respClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            mRespOk = respClass.getMethod("transactionSuccess");
            log.info("[SoulboundSpawners] Hooked Vault economy.");
        } catch (ReflectiveOperationException e) {
            economy = null;
            log.warning("[SoulboundSpawners] Vault present but economy hook failed: " + e.getMessage());
        }
    }

    public double balance(OfflinePlayer p) {
        if (economy == null) return 0;
        try {
            return ((Number) mBalance.invoke(economy, p)).doubleValue();
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    public boolean has(OfflinePlayer p, double amount) {
        if (economy == null) return true;
        try {
            return (boolean) mHas.invoke(economy, p, amount);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    /** @return true if the withdrawal succeeded (or economy is absent). */
    public boolean withdraw(OfflinePlayer p, double amount) {
        if (economy == null) return true;
        try {
            Object resp = mWithdraw.invoke(economy, p, amount);
            return (boolean) mRespOk.invoke(resp);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
