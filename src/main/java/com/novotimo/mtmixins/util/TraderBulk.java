package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.bridge.CurrencyBridge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * One shift-click on a CustomNPCs trade (register B56): the trade repeated until it cannot go on. B56 runs
 * CustomNPCs' own single trade once per round, so its checks, scripts and payment are unchanged, and routes what the
 * trader hands over here instead of onto the cursor.
 *
 * <p>Each round starts from a copy of the player's whole inventory (CustomNPCs takes payment from all 41 slots). What
 * the trader gives goes into the inventory; coins, as Civilizations values them, are added up and paid once at the
 * end in the highest denominations, so a round only needs that total to fit. A round whose result does not fit puts
 * the inventory back as it was and ends the click, so nothing is dropped or lost.
 *
 * <p>B57 reports here what each round paid in coins and why a trade was refused ({@link #paid}, {@link #refused}), so
 * the closing chat line can say what the click cost and why it stopped. Change from one round pays for the next, so
 * a click that bought 2 things at 500 gold each out of a 1,000-gold sack leaves no change; the line says it paid 1,000.
 */
public final class TraderBulk {

    /** Most trades one shift-click makes. */
    public static final int MAX_ROUNDS = 1000;

    private enum Stop { NONE, FULL, LIMIT }

    /** Why CustomNPCs' {@code canBuy} last said no, as B57 saw it. */
    private enum Refusal { NONE, CANNOT_PAY, NO_ROOM_FOR_CHANGE }

    private static final String NO_ROOM_FOR_CHANGE = "You have the money, but your change would not fit in your "
            + "inventory. Free a slot or two and try again.";

    /** The shift-click in progress. Trades run on the server thread only, one click at a time. */
    private static TraderBulk current;

    private final EntityPlayerMP player;
    private final List<ItemStack> coinsTraded = new ArrayList<>();
    private ItemStack[] snapshot;
    private boolean delivered;
    private Stop stop = Stop.NONE;
    private int rounds;
    private long owed;
    private long paid;
    private long paidThisRound;
    private Refusal refusal = Refusal.NONE;

    public TraderBulk(EntityPlayerMP player) {
        this.player = player;
        current = this;
    }

    /**
     * B57: a trade was refused. {@code noRoomForChange} means the player's coins cover the price but the change would
     * not fit. During a shift-click this is kept for the closing line; on a plain click that reason is told at once,
     * since otherwise the click just does nothing.
     */
    public static void refused(EntityPlayerMP player, boolean noRoomForChange) {
        TraderBulk bulk = current;
        if (bulk != null && bulk.player == player) {
            bulk.refusal = noRoomForChange ? Refusal.NO_ROOM_FOR_CHANGE : Refusal.CANNOT_PAY;
        } else if (noRoomForChange) {
            player.sendMessage(new TextComponentString(TextFormatting.RED + NO_ROOM_FOR_CHANGE));
        }
    }

    /** B57: a trade's price was taken by value. */
    public static void paid(EntityPlayerMP player, long value) {
        TraderBulk bulk = current;
        if (bulk != null && bulk.player == player) {
            bulk.paid += value;
            bulk.paidThisRound += value;
        }
    }

    /** Before each of CustomNPCs' single trades. False once the click is over. */
    public boolean beginRound() {
        if (stop != Stop.NONE) {
            return false;
        }
        if (rounds >= MAX_ROUNDS) {
            stop = Stop.LIMIT;
            return false;
        }
        int size = player.inventory.getSizeInventory();
        snapshot = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            snapshot[i] = player.inventory.getStackInSlot(i).copy();
        }
        delivered = false;
        paidThisRound = 0L;
        refusal = Refusal.NONE;
        return true;
    }

    /**
     * After it. False ends the click: the trade did not happen (the player could not pay, or a script cancelled it)
     * or its result did not fit and was undone.
     */
    public boolean endRound() {
        if (!delivered || stop != Stop.NONE) {
            return false;
        }
        rounds++;
        return true;
    }

    /** Takes the place of CustomNPCs' {@code givePlayer}, which put the item on the cursor. */
    public void deliver(ItemStack item) {
        if (item == null || item.isEmpty()) {
            delivered = true;
            return;
        }
        long value = CurrencyBridge.stackValue(item);
        if (value > 0L) {
            if (CurrencyBridge.canGive(player, owed + value)) {
                owed += value;
                coinsTraded.add(item.copy());
                delivered = true;
            } else {
                undo();
            }
            return;
        }
        if (insertAll(item.copy())) {
            delivered = true;
        } else {
            undo();
        }
    }

    /**
     * Puts the whole stack into the main inventory, or changes nothing and returns false. Not
     * {@code addItemStackToInventory}: in creative mode that reports success and deletes what does not fit, so on
     * 2026-11-03 a creative test with one free slot "fitted" 816 shovels and Civilizations' change, and lost the
     * player's 64 gold sacks. Same rule as Civilizations' {@code InventoryCurrencyService.insertAll}.
     */
    private boolean insertAll(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        List<ItemStack> main = player.inventory.mainInventory;
        int limit = Math.min(stack.getMaxStackSize(), player.inventory.getInventoryStackLimit());
        long room = 0L;
        for (ItemStack slot : main) {
            if (slot.isEmpty()) {
                room += limit;
            } else if (stack.isStackable() && sameKind(slot, stack)) {
                room += Math.max(0, limit - slot.getCount());
            }
        }
        if (room < stack.getCount()) {
            return false;
        }
        int left = stack.getCount();
        if (stack.isStackable()) {
            for (int i = 0; i < main.size() && left > 0; i++) {
                ItemStack slot = main.get(i);
                if (!slot.isEmpty() && sameKind(slot, stack) && slot.getCount() < limit) {
                    int moved = Math.min(left, limit - slot.getCount());
                    slot.grow(moved);
                    left -= moved;
                }
            }
        }
        for (int i = 0; i < main.size() && left > 0; i++) {
            if (main.get(i).isEmpty()) {
                int moved = Math.min(left, limit);
                ItemStack put = stack.copy();
                put.setCount(moved);
                main.set(i, put);
                left -= moved;
            }
        }
        stack.setCount(0);
        return true;
    }

    /** Vanilla's test for merging two stacks ({@code InventoryPlayer.stackEqualExact}). */
    private static boolean sameKind(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && (!a.getHasSubtypes() || a.getMetadata() == b.getMetadata())
                && ItemStack.areItemStackTagsEqual(a, b);
    }

    private void undo() {
        for (int i = 0; i < snapshot.length; i++) {
            player.inventory.setInventorySlotContents(i, snapshot[i]);
        }
        paid -= paidThisRound;
        stop = Stop.FULL;
    }

    /** Pays what the rounds earned in coins and tells the player what happened. */
    public void finish() {
        if (current == this) {
            current = null;
        }
        if (owed > 0L && !CurrencyBridge.give(player, owed)) {
            // Checked every round, so this should not happen; hand over the coins as traded rather than lose them.
            for (ItemStack coins : coinsTraded) {
                if (!insertAll(coins)) {
                    player.dropItem(coins, false);
                }
            }
        }
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        if (player.openContainer != null) {
            player.openContainer.detectAndSendChanges();
        }

        if (rounds == 0) {
            if (stop == Stop.FULL) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "Your inventory is full."));
            } else if (refusal == Refusal.NO_ROOM_FOR_CHANGE) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + NO_ROOM_FOR_CHANGE));
            } else if (refusal == Refusal.CANNOT_PAY) {
                player.sendMessage(new TextComponentString(TextFormatting.RED + "You cannot pay for this trade."));
            }
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(TextFormatting.GOLD).append("Traded ").append(rounds).append(rounds == 1 ? " time" : " times");
        if (paid > 0L) {
            text.append(", paid ").append(TextFormatting.WHITE).append(CurrencyBridge.format(paid)).append(TextFormatting.GOLD);
        }
        if (owed > 0L) {
            text.append(paid > 0L ? " and got " : " for ").append(TextFormatting.WHITE).append(CurrencyBridge.format(owed))
                    .append(TextFormatting.GOLD);
        }
        if (stop == Stop.FULL) {
            text.append("; your inventory is full");
        } else if (stop == Stop.LIMIT) {
            text.append("; ").append(MAX_ROUNDS).append(" is the most one click makes");
        } else if (refusal == Refusal.NO_ROOM_FOR_CHANGE) {
            text.append("; the change for another would not fit, so free a slot or two to buy more");
        } else if (refusal == Refusal.CANNOT_PAY) {
            text.append("; you cannot pay for another");
        }
        text.append('.');
        player.sendMessage(new TextComponentString(text.toString()));
    }
}
