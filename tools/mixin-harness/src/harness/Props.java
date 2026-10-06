package harness;

import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

public class Props implements IGlobalPropertyService {
    static final class Key implements IPropertyKey { final String n; Key(String n) { this.n = n; } public String toString() { return n; } }
    private final Map<String, Object> map = new HashMap<>();
    public IPropertyKey resolveKey(String name) { return new Key(name); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey k) { return (T) map.get(k.toString()); }
    public void setProperty(IPropertyKey k, Object v) { map.put(k.toString(), v); }
    @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey k, T d) { Object v = map.get(k.toString()); return v == null ? d : (T) v; }
    public String getPropertyString(IPropertyKey k, String d) { Object v = map.get(k.toString()); return v == null ? d : v.toString(); }
}
