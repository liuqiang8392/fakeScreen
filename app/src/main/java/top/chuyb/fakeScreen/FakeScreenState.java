package top.chuyb.fakeScreen;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * 假熄屏功能的业务层。
 *
 * <p>本类持有持久化的开关状态（保存在 {@link Settings.Global} 中）以及用于通知 system_server 的
 * IPC 广播协议。它不持有任何 UI 引用，由 {@link MainActivity}（表现层）与
 * {@link MainHook}（system_server 侧）共用。</p>
 *
 * @since 1.0
 * @author chuyb-re
 */
public final class FakeScreenState {


    /** 保存在 Settings.Global 中的功能开关状态键。 */
    public static final String KEY_FAKE_SCREEN_ENABLED = "FakeScreen_enable";

    /** 保存在 Settings.Global 中的目标亮度键：0 表示熄屏，1~255 表示按下电源键后降到的亮度。 */
    public static final String KEY_FAKE_SCREEN_BRIGHTNESS = "FakeScreen_brightness";

    /** 保存在 Settings.Global 中的极暗强度键：0 表示关闭，1~100 表示压暗强度。 */
    public static final String KEY_FAKE_SCREEN_DIM_LEVEL = "FakeScreen_dim_level";

    /** 亮度设定值的量程上限，与 {@link Settings.System#SCREEN_BRIGHTNESS} 保持一致。 */
    public static final int MAX_BRIGHTNESS = 255;

    /** 极暗强度的量程上限，与系统 reduce_bright_colors_level 保持一致。 */
    public static final int MAX_DIM_LEVEL = 100;

    /** 极暗强度未设置时使用的默认值：0，即不启用极暗。 */
    public static final int DEFAULT_DIM_LEVEL = 0;

    /** 亮度设定值未设置时使用的默认值：0，即按下电源键仅熄灭屏幕。 */
    public static final int DEFAULT_BRIGHTNESS = 0;

    /** 界面切换开关时发送的广播 Action。 */
    public static final String ACTION_TOGGLE_FAKE_SCREEN = "top.chuyb.fakeScreen.ACTION_TOGGLE";

    /** 界面修改目标亮度时发送的广播 Action。 */
    public static final String ACTION_SET_BRIGHTNESS = "top.chuyb.fakeScreen.ACTION_SET_BRIGHTNESS";

    /** 界面修改极暗强度时发送的广播 Action。 */
    public static final String ACTION_SET_DIM_LEVEL = "top.chuyb.fakeScreen.ACTION_SET_DIM_LEVEL";

    /** 广播中携带目标开关状态的 extra 键。 */
    public static final String EXTRA_ENABLED = "enabled";

    /** 广播中携带目标亮度的 extra 键。 */
    public static final String EXTRA_BRIGHTNESS = "brightness";

    /** 广播中携带极暗强度的 extra 键。 */
    public static final String EXTRA_DIM_LEVEL = "dim_level";

    private FakeScreenState() {
        // 工具类，禁止实例化。
    }

    /**
     * 从 Settings.Global 读取当前开关状态。
     *
     * @param context 任意可用的 Context。
     * @return 已开启返回 true，否则返回 false。
     */
    public static boolean isEnabled(Context context) {
        try {
            int currentState = Settings.Global.getInt(
                    context.getContentResolver(), KEY_FAKE_SCREEN_ENABLED, 0);
            return currentState == 1;
        } catch (Exception e) {
            XLog.error("Failed to read Settings.Global state", e);
            return false;
        }
    }

    /**
     * 广播状态变更请求，由 system_server 写入 Settings.Global。
     *
     * @param context 任意可用的 Context。
     * @param enabled 目标开关状态。
     */
    public static void setEnabled(Context context, boolean enabled) {
        Intent intent = new Intent(ACTION_TOGGLE_FAKE_SCREEN);
        intent.putExtra(EXTRA_ENABLED, enabled);
        context.sendBroadcast(intent);
    }

    /**
     * 从 Settings.Global 读取按下电源键时要降到的目标亮度。
     *
     * @param context 任意可用的 Context。
     * @return 0 表示熄屏，1~255 表示目标亮度。
     */
    public static int getBrightness(Context context) {
        try {
            int brightness = Settings.Global.getInt(
                    context.getContentResolver(), KEY_FAKE_SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS);
            return clampBrightness(brightness);
        } catch (Exception e) {
            XLog.error("Failed to read Settings.Global brightness", e);
            return DEFAULT_BRIGHTNESS;
        }
    }

    /**
     * 广播目标亮度变更请求，由 system_server 写入 Settings.Global。
     *
     * @param context    任意可用的 Context。
     * @param brightness 目标亮度，{@code 0} 表示熄屏。
     */
    public static void setBrightness(Context context, int brightness) {
        Intent intent = new Intent(ACTION_SET_BRIGHTNESS);
        intent.putExtra(EXTRA_BRIGHTNESS, clampBrightness(brightness));
        context.sendBroadcast(intent);
    }

    /**
     * 把亮度限制在 {@code 0 ~ }{@link #MAX_BRIGHTNESS} 之间。
     *
     * @param brightness 原始亮度值。
     * @return 合法的亮度值。
     */
    public static int clampBrightness(int brightness) {
        return Math.max(0, Math.min(MAX_BRIGHTNESS, brightness));
    }

    /**
     * 从 Settings.Global 读取极暗强度。
     *
     * @param context 任意可用的 Context。
     * @return 0 表示关闭，1~100 表示强度。
     */
    public static int getDimLevel(Context context) {
        try {
            int level = Settings.Global.getInt(
                    context.getContentResolver(), KEY_FAKE_SCREEN_DIM_LEVEL, DEFAULT_DIM_LEVEL);
            return clampDimLevel(level);
        } catch (Exception e) {
            XLog.error("Failed to read Settings.Global dim level", e);
            return DEFAULT_DIM_LEVEL;
        }
    }

    /**
     * 广播极暗强度变更请求，由 system_server 写入 Settings.Secure。
     *
     * @param context 任意可用的 Context。
     * @param level   极暗强度，0 表示关闭。
     */
    public static void setDimLevel(Context context, int level) {
        Intent intent = new Intent(ACTION_SET_DIM_LEVEL);
        intent.putExtra(EXTRA_DIM_LEVEL, clampDimLevel(level));
        context.sendBroadcast(intent);
    }

    /**
     * 把极暗强度限制在 {@code 0 ~ }{@link #MAX_DIM_LEVEL} 之间。
     *
     * @param level 原始强度值。
     * @return 合法的强度值。
     */
    public static int clampDimLevel(int level) {
        return Math.max(0, Math.min(MAX_DIM_LEVEL, level));
    }
}
