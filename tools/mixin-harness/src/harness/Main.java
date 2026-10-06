package harness;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

/** args: config-name test-class [root dirs...] */
public class Main {
    public static void main(String[] args) throws Throwable {
        List<Path> roots = new ArrayList<>();
        for (int i = 2; i < args.length; i++) roots.add(Paths.get(args[i]));
        TestService.LOADER = new TransformingLoader(roots, Main.class.getClassLoader());
        MixinBootstrap.init();
        Mixins.addConfiguration(args[0]);
        TestService.LOADER.transformer = TestService.FACTORY.createTransformer();
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        Method goTo = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        goTo.setAccessible(true);
        goTo.invoke(null, MixinEnvironment.Phase.INIT);
        goTo.invoke(null, MixinEnvironment.Phase.DEFAULT);
        Class<?> test = Class.forName(args[1], true, TestService.LOADER);
        test.getMethod("run").invoke(null);
    }
}
