// Run as the adb shell user via app_process:
//   adb push hires.dex /data/local/tmp/
//   adb shell CLASSPATH=/data/local/tmp/hires.dex app_process / HiRes on|off
// Calls Samsung's MultiTaskingBinder.enableHighResolutionsForExternalDesktop(),
// which makes DexController write 0 to /sys/class/dp_sec/dex (no DeX mode restriction).
public class HiRes {
    public static void main(String[] args) throws Exception {
        boolean on = args.length == 0 || !args[0].equals("off");
        Class<?> atm = Class.forName("android.app.ActivityTaskManager");
        Object service = atm.getMethod("getService").invoke(null);
        Object mt = service.getClass().getMethod("getMultiTaskingBinder").invoke(service);
        if (mt == null) {
            System.out.println("getMultiTaskingBinder returned null");
            System.exit(1);
        }
        mt.getClass().getMethod("enableHighResolutionsForExternalDesktop", boolean.class).invoke(mt, on);
        System.out.println("enableHighResolutionsForExternalDesktop(" + on + ") OK");
    }
}
