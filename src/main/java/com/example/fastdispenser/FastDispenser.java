package com.example.fastdispenser;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FastDispenser: o dispenser solta um stack inteiro por vez e continua
 * soltando enquanto estiver ligado.
 *
 * Compativel com DispenserRobot: so assume o controle quando nenhum outro
 * plugin cancelou o evento (ou seja, quando o item seria apenas dropado).
 */
public class FastDispenser extends JavaPlugin implements Listener {

    private static final String PERM = "fastdispenser.admin";

    private final Set<Location> active = new HashSet<>();
    private final Set<Material> ignored = EnumSet.noneOf(Material.class);

    private boolean enabledFlag;
    private boolean debug;
    private int interval = 1;

    private int counter = 0;        // conta ticks ate o proximo drop
    private int window = 0;         // janela de 1s para o resumo de debug
    private int stacksInWindow = 0; // stacks soltos na janela

    private boolean simulating = false; // true enquanto disparamos nosso proprio evento
    private boolean weCancelled = false; // marca se fomos nos que cancelamos o evento atual

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);

        Plugin dr = getServer().getPluginManager().getPlugin("DispenserRobot");
        getLogger().info("FastDispenser " + getPluginMeta().getVersion() + " ativo"
                + " | enabled=" + enabledFlag
                + " | intervalo=" + interval + " tick(s)"
                + " | debug=" + debug
                + " | DispenserRobot=" + (dr != null ? "detectado" : "nao encontrado"));
    }

    @Override
    public void onDisable() {
        active.clear();
    }

    private void loadSettings() {
        reloadConfig();
        enabledFlag = getConfig().getBoolean("enabled", true);
        interval = Math.max(1, getConfig().getInt("interval-ticks", 1));
        debug = getConfig().getBoolean("debug", true);

        ignored.clear();
        for (String s : getConfig().getStringList("ignored-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) ignored.add(m);
            else getLogger().warning("Material invalido em ignored-materials: " + s);
        }
    }

    private void dbg(String msg) {
        if (debug) getLogger().info("[debug] " + msg);
    }

    private static String fmt(Block b) {
        return b.getWorld().getName() + " " + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    // ------------------------------------------------------------------ eventos

    /**
     * HIGH + ignoreCancelled: roda DEPOIS do DispenserRobot (NORMAL).
     * Se ele ja tratou o disparo e cancelou o evento, nao fazemos nada.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        weCancelled = false;
        if (simulating || !enabledFlag) return;

        Block b = e.getBlock();
        if (b.getType() != Material.DISPENSER) return; // ignora droppers

        Material type = e.getItem().getType();
        if (ignored.contains(type)) {
            dbg("ignorando " + type + " em " + fmt(b) + " (ignored-materials)");
            return;
        }

        dbg("assumindo disparo em " + fmt(b) + " | item=" + type);
        e.setCancelled(true);
        weCancelled = true;
        active.add(b.getLocation());
        dropStack(b);
    }

    /** So diagnostico: mostra o estado final do evento. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispenseMonitor(BlockDispenseEvent e) {
        if (!debug || simulating) return;
        String quem = !e.isCancelled() ? "NAO cancelado (vanilla vai dropar 1 item)"
                : weCancelled ? "cancelado por nos"
                : "cancelado por OUTRO plugin (ex: DispenserRobot)";
        getLogger().info("[debug] [MONITOR] " + fmt(e.getBlock())
                + " tipo=" + e.getBlock().getType()
                + " item=" + e.getItem().getType()
                + " -> " + quem);
        weCancelled = false;
    }

    // ------------------------------------------------------------------ loop

    private void tick() {
        if (debug && ++window >= 20) {
            window = 0;
            if (!active.isEmpty() || stacksInWindow > 0) {
                getLogger().info("[debug] ativos=" + active.size() + " | stacks soltos no ultimo segundo=" + stacksInWindow);
            }
            stacksInWindow = 0;
        }

        if (!enabledFlag) return;
        if (++counter < interval) return;
        counter = 0;

        Iterator<Location> it = active.iterator();
        while (it.hasNext()) {
            Location loc = it.next();
            World w = loc.getWorld();
            if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                it.remove();
                continue;
            }
            Block b = loc.getBlock();
            if (b.getType() != Material.DISPENSER || !b.isBlockPowered()) {
                dbg("desativado (sem sinal ou removido) em " + fmt(b));
                it.remove();
                continue;
            }
            tickDispenser(b);
        }
    }

    /**
     * Nos ticks seguintes, dispara um BlockDispenseEvent de verdade para que
     * outros plugins (DispenserRobot) possam tratar o item. So fazemos o
     * drop do stack se ninguem cancelar.
     */
    private void tickDispenser(Block b) {
        if (!(b.getState() instanceof Container c)) return;

        ItemStack first = null;
        for (ItemStack it : c.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) { first = it; break; }
        }
        if (first == null) return;

        if (ignored.contains(first.getType())) return;

        BlockFace facing = ((Directional) b.getBlockData()).getFacing();
        Vector vel = new Vector(facing.getModX(), facing.getModY(), facing.getModZ()).multiply(0.25);
        ItemStack one = first.clone();
        one.setAmount(1);

        BlockDispenseEvent ev = new BlockDispenseEvent(b, one, vel);
        simulating = true;
        try {
            getServer().getPluginManager().callEvent(ev);
        } finally {
            simulating = false;
        }

        if (!ev.isCancelled()) {
            dropStack(b);
        }
        // se foi cancelado, outro plugin tratou o item e cuida do inventario
    }

    /** Solta o primeiro stack do dispenser na frente dele. Retorna a quantidade de itens soltos. */
    private int dropStack(Block b) {
        if (!(b.getState() instanceof Container c)) return 0;
        Inventory inv = c.getInventory();

        BlockFace facing = ((Directional) b.getBlockData()).getFacing();
        Location loc = b.getRelative(facing).getLocation().add(0.5, 0.5, 0.5);

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            int amount = item.getAmount();
            Item dropped = b.getWorld().dropItem(loc, item.clone());
            dropped.setVelocity(new Vector(facing.getModX(), facing.getModY(), facing.getModZ()).multiply(0.25));
            inv.setItem(i, null);

            stacksInWindow++;
            return amount;
        }
        return 0;
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) {
            s.sendMessage("§cVoce nao tem permissao.");
            return true;
        }
        if (a.length == 0) {
            status(s);
            return true;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "on" -> setEnabled(s, true);
            case "off" -> setEnabled(s, false);
            case "toggle" -> setEnabled(s, !enabledFlag);
            case "status" -> status(s);
            case "reload" -> {
                loadSettings();
                active.clear();
                s.sendMessage("§aConfig recarregada.");
                status(s);
            }
            case "debug" -> {
                boolean novo = a.length >= 2 ? a[1].equalsIgnoreCase("on") : !debug;
                debug = novo;
                getConfig().set("debug", novo);
                saveConfig();
                s.sendMessage("§eDebug: " + (novo ? "§aligado" : "§cdesligado"));
            }
            case "speed" -> {
                if (a.length < 2) {
                    s.sendMessage("§cUso: /" + label + " speed <ticks>  §7(1 = um stack por tick, 20 = um por segundo)");
                    return true;
                }
                int n;
                try {
                    n = Integer.parseInt(a[1]);
                } catch (NumberFormatException ex) {
                    s.sendMessage("§cNumero invalido: " + a[1]);
                    return true;
                }
                if (n < 1 || n > 1200) {
                    s.sendMessage("§cO intervalo tem que ser entre 1 e 1200 ticks.");
                    return true;
                }
                interval = n;
                counter = 0;
                getConfig().set("interval-ticks", n);
                saveConfig();
                s.sendMessage("§aVelocidade: §eum stack a cada " + n + " tick(s) §7(" + String.format(Locale.ROOT, "%.2f", 20.0 / n) + " stacks/s)");
            }
            case "test" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("§cSo jogadores podem usar /" + label + " test.");
                    return true;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || b.getType() != Material.DISPENSER) {
                    s.sendMessage("§cOlhe para um dispenser (ate 6 blocos) e use o comando de novo.");
                    return true;
                }
                int n = dropStack(b);
                s.sendMessage(n > 0 ? "§aTeste ok: soltou §e" + n + "§a itens." : "§eO dispenser esta vazio.");
            }
            default -> s.sendMessage("§cUso: /" + label + " [on|off|toggle|speed <ticks>|debug [on|off]|test|reload|status]");
        }
        return true;
    }

    private void setEnabled(CommandSender s, boolean v) {
        enabledFlag = v;
        getConfig().set("enabled", v);
        saveConfig();
        if (!v) active.clear();
        s.sendMessage("§eFastDispenser: " + (v ? "§aligado" : "§cdesligado"));
    }

    private void status(CommandSender s) {
        Plugin dr = getServer().getPluginManager().getPlugin("DispenserRobot");
        s.sendMessage("§6--- FastDispenser " + getPluginMeta().getVersion() + " ---");
        s.sendMessage("§eEstado: " + (enabledFlag ? "§aligado" : "§cdesligado"));
        s.sendMessage("§eVelocidade: §f1 stack a cada " + interval + " tick(s) §7("
                + String.format(Locale.ROOT, "%.2f", 20.0 / interval) + " stacks/s)");
        s.sendMessage("§eDebug: " + (debug ? "§aligado" : "§cdesligado"));
        s.sendMessage("§eDispensers ativos agora: §f" + active.size());
        s.sendMessage("§eDispenserRobot: " + (dr != null ? "§adetectado" : "§7nao encontrado"));
        if (!ignored.isEmpty()) s.sendMessage("§eIgnorados: §f" + ignored);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission(PERM)) return out;
        if (a.length == 1) {
            for (String o : List.of("on", "off", "toggle", "speed", "debug", "test", "reload", "status")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("speed")) {
            out.addAll(List.of("1", "2", "5", "10", "20"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("debug")) {
            out.addAll(List.of("on", "off"));
        }
        return out;
    }
}
