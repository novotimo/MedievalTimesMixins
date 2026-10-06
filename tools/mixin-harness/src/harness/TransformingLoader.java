package harness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Child-first for the game/mod packages; every such class goes through the real Mixin transformer. */
public class TransformingLoader extends ClassLoader {
    private final List<Path> roots;
    public volatile IMixinTransformer transformer;

    public TransformingLoader(List<Path> roots, ClassLoader parent) { super(parent); this.roots = roots; }

    static boolean ours(String n) {
        return n.startsWith("net.minecraft.") || n.startsWith("net.minecraftforge.") || n.startsWith("com.novotimo.")
                || n.startsWith("com.lycanitesmobs.") || n.startsWith("com.alcatrazescapee.") || n.startsWith("tests.");
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (ours(name)) {
                    byte[] b = getBytes(name);
                    if (b == null && transformer != null) b = transformer.generateClass(MixinEnvironment.getCurrentEnvironment(), name);
                    if (b == null) throw new ClassNotFoundException(name);
                    if (transformer != null && !name.startsWith("tests.")) b = transformer.transformClassBytes(name, name, b);
                    c = defineClass(name, b, 0, b.length);
                } else {
                    c = getParent().loadClass(name);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    public byte[] getBytes(String name) {
        String path = name.replace('.', '/') + ".class";
        try {
            for (Path r : roots) {
                Path p = r.resolve(path);
                if (Files.isRegularFile(p)) return Files.readAllBytes(p);
            }
            InputStream in = ClassLoader.getSystemResourceAsStream(path);
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    @Override public InputStream getResourceAsStream(String name) {
        try {
            for (Path r : roots) { Path p = r.resolve(name); if (Files.isRegularFile(p)) return Files.newInputStream(p); }
        } catch (IOException e) { throw new RuntimeException(e); }
        return getParent().getResourceAsStream(name);
    }
}
