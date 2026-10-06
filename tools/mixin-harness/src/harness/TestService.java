package harness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.*;

/** Minimal Mixin service: classes come from TransformingLoader's roots, JDK classes from the system loader. */
public class TestService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
    public static TransformingLoader LOADER;
    public static IMixinTransformerFactory FACTORY;

    @Override public String getName() { return "MtHarness"; }
    @Override public boolean isValid() { return true; }
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return null; }
    @Override public IClassTracker getClassTracker() { return null; }
    @Override public IMixinAuditTrail getAuditTrail() { return null; }
    @Override public IAdviceProvider getAdviceProvider() { return IAdviceProvider.GENERIC; }
    @Override public IFeatureValidator getFeatureValidator() { return IFeatureValidator.ALLOW_ALL; }
    @Override public Collection<String> getPlatformAgents() { return Collections.emptyList(); }
    @Override public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual(getName()); }
    @Override public InputStream getResourceAsStream(String name) { return LOADER.getResourceAsStream(name); }
    @Override public CompatibilityLevel getMaxCompatibilityLevel() { return CompatibilityLevel.JAVA_8; }
    @Override public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory) FACTORY = (IMixinTransformerFactory) internal;
        super.offer(internal);
    }

    @Override public URL[] getClassPath() { return new URL[0]; }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException { return Class.forName(name, true, LOADER); }
    @Override public Class<?> findClass(String name, boolean init) throws ClassNotFoundException { return Class.forName(name, init, LOADER); }
    @Override public Class<?> findAgentClass(String name, boolean init) throws ClassNotFoundException { return Class.forName(name, init, TestService.class.getClassLoader()); }

    @Override public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, true); }
    @Override public ClassNode getClassNode(String name, boolean rt) throws ClassNotFoundException, IOException { return getClassNode(name, rt, 0); }
    @Override public ClassNode getClassNode(String name, boolean rt, int flags) throws ClassNotFoundException, IOException {
        byte[] b = LOADER.getBytes(name.replace('/', '.'));
        if (b == null) throw new ClassNotFoundException(name);
        ClassNode node = new ClassNode();
        new ClassReader(b).accept(node, flags);
        return node;
    }
}
