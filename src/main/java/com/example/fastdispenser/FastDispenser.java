package com.example.fastdispenser;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Dispenser que solta um stack inteiro por vez, de forma continua enquanto
 * estiver ligado. Compativel com DispenserRobot: so assume o controle quando
 * nenhum outro plugin cancelou o evento (ou seja, quando o item seria
 * apenas dropado no chao).
 */
public class FastDispenser extends JavaPlugin implements Listener {

    private final Set<Location> active = new HashSet<>();
    private int interval;
    private int counter = 0;
    private boolean simulating = false; // true enquanto disparamos nosso proprio evento

    @Override
    public void onEnable() {
        saveDefaultConfig();
        interval = Math.max(1, getConfig().getInt("interval-ticks", 1));

        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            if (++counter < interval) return;
            counter = 0;

            Iterator<Location> it = active.iterator();
            while (it.hasNext()) {
                Block b = it.next().getBlock();
                if (!(b.getState() instanceof Dispenser) || !b.isBlockPowered()) {
                    it.remove();
                    continue;
                }
                tickDispenser(b);
            }
        }, 1L, 1L);
    }

    /**
     * HIGH + ignoreCancelled: roda DEPOIS do DispenserRobot (NORMAL).
     * Se ele ja tratou o disparo (colocar bloco, quebrar, cauldron etc.)
     * e cancelou o evento, nao fazemos nada.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (simulating) return; // evento nosso, deixa passar
        Block b = e.getBlock();
        if (!(b.getState() instanceof Dispenser)) return; // ignora droppers

        e.setCancelled(true);
        active.add(b.getLocation());
        dropStack(b);
    }

    /**
     * Nos ticks seguintes, dispara um BlockDispenseEvent de verdade para que
     * outros plugins (DispenserRobot) possam tratar o item. So fazemos o
     * drop do stack se ninguem cancelar.
     */
    private void tickDispenser(Block b) {
        Dispenser disp = (Dispenser) b.getState();
        Inventory inv = disp.getInventory();

        ItemStack first = null;
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir()) { first = it; break; }
        }
        if (first == null) return;

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

    private void dropStack(Block b) {
        Dispenser disp = (Dispenser) b.getState();
        Inventory inv = disp.getInventory();

        BlockFace facing = ((Directional) b.getBlockData()).getFacing();
        Location loc = b.getRelative(facing).getLocation().add(0.5, 0.5, 0.5);

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            Item dropped = b.getWorld().dropItem(loc, item.clone());
            dropped.setVelocity(new Vector(facing.getModX(), facing.getModY(), facing.getModZ()).multiply(0.25));

            inv.setItem(i, null);
            break; // um stack por execucao
        }
    }
}
