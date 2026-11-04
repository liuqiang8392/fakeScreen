package top.chuyb.fakeScreen;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 把屏幕亮度降到用户设定的目标值，并在再次按下电源键时还原。
 *
 * <p>实现方式是修改 {@link Settings.System#SCREEN_BRIGHTNESS}：PowerManagerService 监听该设置项，
 * 写入后会立即刷新显示亮度，因此在 Android 9 ~ 14 上均可使用，且不需要反射硬件接口。若用户开启了
 * 自动亮度，降亮期间会临时切到手动模式，还原时再切回，避免自动亮度把目标值覆盖掉。</p>
 *
 * <p>本类不安装任何 hook，状态只在 system_server 内维护（进程重启后自然回到未降亮状态）。</p>
 *
 * @since 2.1
 * @author chuyb-re
 */
final class BrightnessController {

    /** 尚未保存过亮度时使用的标记值。 */
    private static final int UNSAVED = -1;

    /** 读不到当前亮度时使用的兜底值。 */
    private static final int FALLBACK_BRIGHTNESS = 128;

    /** 保存降亮前亮度所使用的 Settings.System 键。 */
    private static final String KEY_OLD_BRIGHTNESS = "old_screen_brightness";

    /** 保存降亮前亮度模式所使用的 Settings.System 键。 */
    private static final String KEY_OLD_BRIGHTNESS_MODE = "old_screen_brightness_mode";

    /** 当前是否处于“已降亮”状态。 */
    private static volatile boolean sDimmed = false;

    /** 降亮前的系统亮度值。 */
    private static int sSavedBrightness = UNSAVED;

    /** 降亮前的亮度模式（自动 / 手动）。 */
    private static int sSavedBrightnessMode = UNSAVED;

    private BrightnessController() {
        // 工具类，禁止实例化。
    }

    /**
     * @return 当前屏幕是否已被本模块降亮。
     */
    static boolean isDimmed() {
        return sDimmed;
    }

    /**
     * 把屏幕亮度降到目标值；第一次降亮时先保存用户原本的亮度与亮度模式。
     *
     * @param module 用于框架日志的模块实例。
     * @param target 目标亮度，取值 1~255。
     */
    static void dim(XposedModule module, int target) {
        ContentResolver cr = requireContentResolver(module);
        if (cr == null) return;

        int brightness = FakeScreenState.clampBrightness(target);
        try {
            if (!sDimmed) {
                sSavedBrightness = Settings.System.getInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS, FALLBACK_BRIGHTNESS);
                sSavedBrightnessMode = Settings.System.getInt(
                        cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                // 把原亮度与亮度模式持久化：降亮期间若重启，开机时据此还原，
                // 避免屏幕一直停留在降亮值、自动亮度停留在手动模式。
                Settings.System.putInt(cr, KEY_OLD_BRIGHTNESS, sSavedBrightness);
                Settings.System.putInt(cr, KEY_OLD_BRIGHTNESS_MODE, sSavedBrightnessMode);
            }
            // 自动亮度会持续改写亮度值，降亮期间必须临时切到手动模式。
            if (sSavedBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness);
            sDimmed = true;
            XLog.log(Log.INFO, "Dimmed to brightness " + brightness
                    + " (saved " + sSavedBrightness + ")");
        } catch (Exception e) {
            XLog.error("Failed to dim screen brightness", e);
        }
    }

    /**
     * 降亮状态下，若用户在界面里调整了目标亮度，立即把新值应用到当前屏幕。
     *
     * @param module 用于框架日志的模块实例。
     * @param target 新的目标亮度，取值 1~255。
     */
    static void updateIfDimmed(XposedModule module, int target) {
        if (!sDimmed) return;
        ContentResolver cr = requireContentResolver(module);
        if (cr == null) return;

        if (target <= 0) {
            // 目标改成 0 表示改为熄屏模式，当前屏幕需要先恢复正常亮度。
            restoreIfDimmed(module);
            return;
        }
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                    FakeScreenState.clampBrightness(target));
            XLog.log(Log.INFO, "Dimmed brightness updated to " + target);
        } catch (Exception e) {
            XLog.error("Failed to update dimmed brightness", e);
        }
    }

    /**
     * 把亮度与亮度模式还原为降亮前的状态；未降亮时不做任何操作。
     *
     * @param module 用于框架日志的模块实例。
     */
    static void restoreIfDimmed(XposedModule module) {
        if (!sDimmed) return;
        ContentResolver cr = requireContentResolver(module);
        if (cr == null) return;

        try {
            int brightness = sSavedBrightness == UNSAVED ? FALLBACK_BRIGHTNESS : sSavedBrightness;
            applyRestore(cr, brightness, sSavedBrightnessMode);
            XLog.log(Log.INFO, "Brightness restored to " + brightness);
        } catch (Exception e) {
            XLog.error("Failed to restore screen brightness", e);
        } finally {
            sDimmed = false;
            sSavedBrightness = UNSAVED;
            sSavedBrightnessMode = UNSAVED;
        }
    }

    /**
     * 开机时还原上一次会话遗留的降亮状态。
     *
     * <p>降亮期间若手机重启或 system_server 重启，进程内的状态会丢失，但亮度设置已经被改成了降亮值；
     * 因此开机时按降亮时写入的 {@link #KEY_OLD_BRIGHTNESS} 标记还原，并清除标记。</p>
     *
     * @param module 用于框架日志的模块实例。
     */
    static void restoreLeftover(XposedModule module) {
        ContentResolver cr = requireContentResolver(module);
        if (cr == null) return;

        try {
            int savedBrightness = Settings.System.getInt(cr, KEY_OLD_BRIGHTNESS, UNSAVED);
            if (savedBrightness == UNSAVED) return;
            int savedMode = Settings.System.getInt(cr, KEY_OLD_BRIGHTNESS_MODE, UNSAVED);
            applyRestore(cr, savedBrightness, savedMode);
            XLog.log(Log.INFO, "Leftover dim state restored on boot, brightness=" + savedBrightness);
        } catch (Exception e) {
            XLog.error("Failed to restore leftover dim state", e);
        } finally {
            sDimmed = false;
            sSavedBrightness = UNSAVED;
            sSavedBrightnessMode = UNSAVED;
        }
    }

    /**
     * 写入保存的亮度与亮度模式，并清除持久化的保存标记。
     *
     * @param cr             系统 ContentResolver。
     * @param brightness     要还原的亮度值。
     * @param brightnessMode 要还原的亮度模式，{@link #UNSAVED} 表示不还原。
     */
    private static void applyRestore(ContentResolver cr, int brightness, int brightnessMode) {
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
                FakeScreenState.clampBrightness(brightness));
        if (brightnessMode != UNSAVED) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, brightnessMode);
        }
        Settings.System.putInt(cr, KEY_OLD_BRIGHTNESS, UNSAVED);
        Settings.System.putInt(cr, KEY_OLD_BRIGHTNESS_MODE, UNSAVED);
    }

    /**
     * @param module 用于框架日志的模块实例。
     * @return system_server 中捕获的系统 ContentResolver，尚未就绪时返回 null。
     */
    private static ContentResolver requireContentResolver(XposedModule module) {
        Context context = FakeScreenIpc.getSystemContext();
        if (context == null) {
            XLog.log(Log.ERROR, "System context is not available, skip brightness change");
            return null;
        }
        return context.getContentResolver();
    }
}
