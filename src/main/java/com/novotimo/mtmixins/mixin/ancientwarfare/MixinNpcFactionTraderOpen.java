package com.novotimo.mtmixins.mixin.ancientwarfare;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.shadowmage.ancientwarfare.npc.container.ContainerNpcFactionTradeView;
import net.shadowmage.ancientwarfare.npc.entity.faction.NpcFactionTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B33 &mdash; Ancient Warfare's faction traders never open their trade window on a dedicated
 * server.
 *
 * <h2>The bug</h2>
 *
 * <p>Ancient Warfare 2.9.20, {@code NpcFactionTrader.processInteract}:
 *
 * <pre>
 *   if (!player.world.isRemote &amp;&amp; trader == null &amp;&amp; !GuiScreen.isShiftKeyDown()) {
 *       startTrade(player);
 *       NetworkHandler.INSTANCE.openGui(player, GUI_NPC_FACTION_TRADE_VIEW, getEntityId(), 0, 0);
 *   }
 * </pre>
 *
 * <p>The shift-key test reads the keyboard through {@code GuiScreen}, a client class, on the server
 * branch. A dedicated server has no such class, so every right-click on a faction trader throws
 * {@code NoClassDefFoundError: net/minecraft/client/gui/GuiScreen} before the window opens. A failed
 * link is never retried, so it fails the same way every time: 223 times on prod between 4 and 13
 * October, and faction trading did not work at all.
 *
 * <p>Separately, {@code trader} is set when the window opens and cleared only by the container's
 * {@code onContainerClosed}. Player-owned traders release a trader that has died; faction traders do
 * not, so a trader left set by a window that was never closed properly locks the NPC until its chunk
 * reloads. Harmless while nothing could open the window; it would start to matter once this fix is in.
 *
 * <h2>The fix</h2>
 *
 * <ul>
 *   <li>The keyboard test becomes {@code player.isSneaking()}, the server's own record of the same
 *       shift key. Ancient Warfare's intent is kept: a sneaking right-click still does not open
 *       the window.</li>
 *   <li>Before the test, a recorded trader is released if they are dead or no longer have this NPC's
 *       trade window open.</li>
 * </ul>
 *
 * <p>The repeat-trade shift-click inside the window ({@code GuiNpcFactionTradeView}) is client code
 * and works as it is. Its own config file, {@code mixins.mtmixins.awtrade.json}, so it can be
 * blacklisted in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(value = NpcFactionTrader.class, remap = false)
public abstract class MixinNpcFactionTraderOpen {

    @Shadow
    private EntityPlayer trader;

    // processInteract is inherited from vanilla, so it is SRG-named in production while this mixin
    // has remap = false: list both names (see "The invoke-owner trap" in the README).
    @Inject(method = {"processInteract", "func_184645_a"}, at = @At("HEAD"))
    private void mtmixins$releaseStaleTrader(EntityPlayer player, EnumHand hand,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (trader == null || player.world.isRemote) {
            return;
        }
        if (trader.isDead
                || !(trader.openContainer instanceof ContainerNpcFactionTradeView)
                || ((ContainerNpcFactionTradeView) trader.openContainer).entity != (Object) this) {
            trader = null;
        }
    }

    @Redirect(method = {"processInteract", "func_184645_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;func_146272_n()Z",
                    remap = false))
    private boolean mtmixins$sneakingInsteadOfKeyboard(EntityPlayer player, EnumHand hand) {
        return player.isSneaking();
    }
}
