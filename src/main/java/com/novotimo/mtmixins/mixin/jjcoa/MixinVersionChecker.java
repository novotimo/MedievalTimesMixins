package com.novotimo.mtmixins.mixin.jjcoa;

import com.novotimo.mtmixins.MedievalTimesMixins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B10 &mdash; JJ Coats of Arms' update check is an unthrottled loop, so every client
 * fetches the same file from GitHub about twice a second for as long as it is running.
 *
 * <h2>What it does</h2>
 *
 * <p>{@code VersionChecker.run} is, in shape:
 *
 * <pre>
 * while (!this.shutdown) {
 *     InputStream in = new URL(".../JJ-Coats-of-Arms/master/update.json").openStream();
 *     String latest = parse(IOUtils.toString(in));
 *     IOUtils.closeQuietly(in);
 *     is_ultima_versione = "1.0.11".equals(latest);
 *     if (player != null &amp;&amp; !is_ultima_versione) { tell the player; shutdown(); }
 * }
 * </pre>
 *
 * <p>The loop's back edge is a bare {@code goto} to the top &mdash; <b>there is no sleep</b>. The only
 * exits are an exception, or {@code shutdown()} on the "you are out of date" branch. So on the happy
 * path, where the installed version matches the published one, it never stops: measured on this pack,
 * 955 requests in 31 minutes, about 1,850 an hour, forever.
 *
 * <p>It is also started from {@code PacketSettingAlClient$Handler.onMessage} with no guard against
 * already having one, so each join spawns another thread doing the same thing in parallel.
 *
 * <h2>What this is and is not</h2>
 *
 * <p><b>Not a memory leak.</b> The stream is closed properly, and sampling the client's live set over
 * 94 minutes showed the JDK connection objects rising and then falling back
 * ({@code KeepAliveStream} 770 &rarr; 1725 &rarr; 1543), with the total live set flat at ~2.85 GB. What
 * it is, is a permanent source of allocation churn and TLS handshakes on the client, and roughly 44,000
 * requests a day per player against raw.githubusercontent.com.
 *
 * <h2>Why cancelling outright</h2>
 *
 * <p>Nothing outside the class reads {@code ultima_versione}, {@code is_ultima_versione} or
 * {@code player} &mdash; the only external reference to the class is the handler that constructs and
 * starts it &mdash; so making {@code run} a no-op is inert beyond stopping the polling.
 *
 * <p>Throttling instead of cancelling was the obvious alternative, but the notification it exists to
 * deliver is "a newer JJ Coats of Arms is out", which a player on a pinned modpack cannot act on. The
 * useful audience for that message is whoever assembles the pack, not the people playing it.
 *
 * <p>Upstream would want a {@code Thread.sleep} in that loop and a single-instance guard. Worth
 * reporting if the author is still active; this does not depend on it.
 */
@Pseudo
@Mixin(targets = "it.jdijack.jjcoa.util.VersionChecker", remap = false)
public abstract class MixinVersionChecker {

    @Unique
    private static boolean mtmixins$logged = false;

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void mtmixins$dontPollGithubForever(CallbackInfo ci) {
        ci.cancel();
        if (!mtmixins$logged) {
            mtmixins$logged = true;
            MedievalTimesMixins.LOG.info(
                    "Suppressed JJ Coats of Arms' version-check thread. Its loop has no sleep, so it "
                            + "re-fetched update.json from GitHub roughly twice a second for the whole "
                            + "session, and a fresh thread was started on every join.");
        }
    }
}
