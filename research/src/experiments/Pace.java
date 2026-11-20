// Probe / control SurfaceFlinger's forced pacesetter (root via app_process):
//   CLASSPATH=pace.dex app_process / Pace probe|set <displayId>|reset
import android.os.IBinder;
import java.lang.reflect.Method;

public class Pace {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "probe";
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "SurfaceFlingerAIDL");
        Class<?> stub = Class.forName("android.gui.ISurfaceComposer$Stub");
        Object sc = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        if (mode.equals("probe")) {
            for (Method m : sc.getClass().getMethods())
                if (m.getName().toLowerCase().contains("pacesetter")) System.out.println(m);
            return;
        }
        if (mode.equals("set")) {
            sc.getClass().getMethod("forcePacesetter", long.class).invoke(sc, Long.parseLong(args[1]));
            System.out.println("forcePacesetter(" + args[1] + ") OK");
        } else if (mode.equals("reset")) {
            sc.getClass().getMethod("resetForcedPacesetter").invoke(sc);
            System.out.println("resetForcedPacesetter OK");
        }
    }
}
