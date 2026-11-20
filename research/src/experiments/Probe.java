// Prints the hidden SurfaceControl APIs needed to set display mode specs.
//   adb shell su -c 'CLASSPATH=/data/local/tmp/probe.dex app_process / Probe'
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class Probe {
    public static void main(String[] args) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        for (Method m : sc.getDeclaredMethods()) {
            String n = m.getName();
            if (n.contains("DisplayModeSpecs") || n.contains("PhysicalDisplay") || n.equals("getDynamicDisplayInfo")
                    || n.equals("getStaticDisplayInfo") || n.contains("BootDisplayMode")) {
                System.out.println("SC." + m);
            }
        }
        for (String c : new String[] {"android.view.SurfaceControl$DesiredDisplayModeSpecs",
                "android.view.SurfaceControl$RefreshRateRanges", "android.view.SurfaceControl$RefreshRateRange",
                "android.view.SurfaceControl$IdleScreenRefreshRateConfig"}) {
            try {
                Class<?> k = Class.forName(c);
                for (Constructor<?> ctor : k.getDeclaredConstructors()) System.out.println("CTOR " + ctor);
            } catch (ClassNotFoundException e) {
                System.out.println("missing " + c);
            }
        }
        Object ids = sc.getMethod("getPhysicalDisplayIds").invoke(null);
        for (long id : (long[]) ids) System.out.println("physical id " + id);
    }
}
