package io.taucoin.torrent.publishing.core.utils;

import android.content.Context;

import com.google.common.primitives.Ints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;

import androidx.annotation.NonNull;
import io.taucoin.torrent.publishing.MainApplication;
import io.taucoin.torrent.publishing.R;
import io.taucoin.torrent.publishing.core.model.Interval;
import io.taucoin.torrent.publishing.core.settings.SettingsRepository;
import io.taucoin.torrent.publishing.core.storage.sqlite.RepositoryHelper;

/**
 * 网络流量设置相关工具类
 */
public class NetworkSetting {
    private static final Logger logger = LoggerFactory.getLogger("NetworkSetting");
    private static final int METERED_LIMITED;                  // 单位MB
    private static final int WIFI_LIMITED;                     // 单位MB

    private static SettingsRepository settingsRepo;
    static {
        Context context = MainApplication.getInstance();
        settingsRepo = RepositoryHelper.getSettingsRepository(context);
        METERED_LIMITED = context.getResources().getIntArray(R.array.metered_limit)[1];
        WIFI_LIMITED = context.getResources().getIntArray(R.array.wifi_limit)[0];
    }

    /**
     * 获取计费网络流量限制值
     * @return long
     */
    public static int getMeteredLimit() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getIntValue(context.getString(R.string.pref_key_metered_limit), METERED_LIMITED);
    }

    /**
     * 设置计费网络流量限制值
     * @param limited
     */
    public static void setMeteredLimit(int limited) {
        Context context = MainApplication.getInstance();
        settingsRepo.setIntValue(context.getString(R.string.pref_key_metered_limit), limited);
    }

    /**
     * 获取WiFi网络流量限制值
     * @return long
     */
    public static int getWiFiLimit() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getIntValue(context.getString(R.string.pref_key_wifi_limit), WIFI_LIMITED);
    }

    /**
     * 设置WiFi网络流量限制值
     * @param limited
     */
    public static void setWiFiLimit(int limited) {
        Context context = MainApplication.getInstance();
        settingsRepo.setIntValue(context.getString(R.string.pref_key_wifi_limit), limited);
    }

    /**
     * 设置当前是否为计费网络
     */
    public static void setMeteredNetwork(boolean isMetered) {
        Context context = MainApplication.getInstance();
        settingsRepo.setBooleanValue(context.getString(R.string.pref_key_is_metered_network), isMetered);
    }

    /**
     * 返回当前是否为计费网络
     */
    public static boolean isMeteredNetwork() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getBooleanValue(context.getString(R.string.pref_key_is_metered_network),
                false);
    }

    /**
     * 更新网络速度
     */

    public synchronized static void updateNetworkSpeed(@NonNull SessionStatistics statistics) {
        Context context = MainApplication.getInstance();
        long currentSpeed = statistics.getDownloadRate() + statistics.getUploadRate();
        settingsRepo.setLongValue(context.getString(R.string.pref_key_current_speed), currentSpeed);

        // 更新APP在前台的时间前台
        updateForegroundRunningTime();
        updateMeteredSpeedLimit();
        updateWiFiSpeedLimit();
        logger.trace("updateSpeed, CurrentSpeed::{}s", getCurrentSpeed());
    }

    /**
     * APP是否在前台运行
     */
    private static boolean isForegroundRunning() {
        Context appContext = MainApplication.getInstance();
        String foregroundRunningKey = appContext.getString(R.string.pref_key_foreground_running);
        return settingsRepo.getBooleanValue(foregroundRunningKey);
    }

    /**
     * 更新APP前台运行时间
     */
    private static void updateForegroundRunningTime() {
        if (!isForegroundRunning()) {
            return;
        }
        updateForegroundRunningTime(getForegroundRunningTime() + 1);
    }

    /**
     * 更新APP前台运行时间
     */
    public static void updateForegroundRunningTime(int foregroundRunningTime) {
        Context appContext = MainApplication.getInstance();
        String foregroundRunningTimeKey = appContext.getString(R.string.pref_key_foreground_running_time);
        settingsRepo.setIntValue(foregroundRunningTimeKey, foregroundRunningTime);
    }

    /**
     * 获取APP前台运行时间
     */
    private static int getForegroundRunningTime() {
        Context appContext = MainApplication.getInstance();
        String foregroundRunningTimeKey = appContext.getString(R.string.pref_key_foreground_running_time);
        return settingsRepo.getIntValue(foregroundRunningTimeKey, 0);
    }

    /**
     * 获取当前网络网速
     */
    public static long getCurrentSpeed() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_current_speed),
                0);
    }

    /**
     * 更新计费网络网速限制值
     */
    public static void updateMeteredSpeedLimit() {
        Context context = MainApplication.getInstance();
        long usage = TrafficUtil.getMeteredTrafficTotal();
        long limit =  getMeteredLimit();
        long screenTimeAverageSpeed = 0;
        long backgroundAverageSpeed = 0;
        long availableData = 0;

        BigInteger bigUnit = new BigInteger("1024");
        BigInteger bigLimit = BigInteger.valueOf(limit).multiply(bigUnit).multiply(bigUnit);
        BigInteger bigUsage = BigInteger.valueOf(usage);

        long today24HLastSeconds = DateUtil.getTodayLastSeconds(); // 今天剩余的秒数(24h)
        long todayLastSeconds = 0; // 今天剩余的秒数
        // 根据APP在前后台而不同
        if (isForegroundRunning()) {
            todayLastSeconds = getScreenTimeLimitSecond(true) - getForegroundRunningTime();
        }
        if (todayLastSeconds <= 0 || today24HLastSeconds <= 0) {
            todayLastSeconds = today24HLastSeconds;
        }
        if (bigLimit.compareTo(bigUsage) > 0) {
            availableData = bigLimit.subtract(bigUsage).longValue();
            if (todayLastSeconds > 0) {
                screenTimeAverageSpeed = availableData / todayLastSeconds;
            }
            if (today24HLastSeconds > 0) {
                backgroundAverageSpeed = availableData / today24HLastSeconds;
            }
        }
        settingsRepo.setLongValue(context.getString(R.string.pref_key_metered_available_data), availableData);
        settingsRepo.setLongValue(context.getString(R.string.pref_key_metered_screen_time_average_speed),
                screenTimeAverageSpeed);
        settingsRepo.setLongValue(context.getString(R.string.pref_key_metered_background_average_speed),
                backgroundAverageSpeed);
    }

    /**
     * 获取每天APP高速前台时间限制 单位h
     */
    private static int getScreenTimeLimitSecond(boolean isMeteredNetwork) {
        return getScreenTimeLimitHours(isMeteredNetwork) * 60 * 60;
    }

    /**
     * 获取每天APP高速前台时间限制 单位s
     */
    public static int getScreenTimeLimitHours() {
        boolean isMeteredNetwork = NetworkSetting.isMeteredNetwork();
        return getScreenTimeLimitHours(isMeteredNetwork);
    }

    /**
     * 获取每天APP高速前台时间限制 单位s
     */
    private static int getScreenTimeLimitHours(boolean isMeteredNetwork) {
        Context context = MainApplication.getInstance();
        int selectIndex;
        int[] screenTimes;
        if (isMeteredNetwork) {
            int selectLimit = NetworkSetting.getMeteredLimit();
            int[] meteredLimits = context.getResources().getIntArray(R.array.metered_limit);
            List<Integer> meteredList = Ints.asList(meteredLimits);
            selectIndex = meteredList.indexOf(selectLimit);
            screenTimes = context.getResources().getIntArray(R.array.metered_screen_time);
        } else {
            int selectLimit = NetworkSetting.getWiFiLimit();
            int[] wifiLimits = context.getResources().getIntArray(R.array.wifi_limit);
            List<Integer> wifiList = Ints.asList(wifiLimits);
            selectIndex = wifiList.indexOf(selectLimit);
            screenTimes = context.getResources().getIntArray(R.array.wifi_screen_time);
        }
        if (selectIndex >= screenTimes.length) {
            selectIndex = 0;
        }
        return screenTimes[selectIndex];
    }

    /**
     * 获取计费网络可用数据
     */
    public static long getMeteredAvailableData() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_metered_available_data));
    }

    /**
     * 获取计费网络在前台平均网速
     */
    public static long getMeteredScreenTimeAverageSpeed() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_metered_screen_time_average_speed));
    }

    /**
     * 获取计费网络在后台平均网速
     */
    public static long getMeteredBackgroundAverageSpeed() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_metered_background_average_speed));
    }

    /**
     * 获取WiFi网络在前台平均网速
     */
    public static long getWifiScreenTimeAverageSpeed() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_wifi_screen_time_average_speed));
    }

    /**
     * 获取计费网络在后台平均网速
     */
    public static long getWifiBackgroundAverageSpeed() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_wifi_background_average_speed));
    }


    /**
     * 更新WiFi网络网速限制值
     */
    public static void updateWiFiSpeedLimit() {
        Context context = MainApplication.getInstance();
        long total = TrafficUtil.getTrafficUploadTotal() + TrafficUtil.getTrafficDownloadTotal();
        long usage = total - TrafficUtil.getMeteredTrafficTotal();
        logger.trace("updateWiFiSpeedLimit total::{}, MeteredTotal::{}, wifiUsage::{}", total,
                TrafficUtil.getMeteredTrafficTotal(), usage);
        long limit = getWiFiLimit();
        long screenTimeAverageSpeed = 0;
        long backgroundAverageSpeed = 0;
        long availableData = 0;

        BigInteger bigUnit = new BigInteger("1024");
        BigInteger bigLimit = BigInteger.valueOf(limit).multiply(bigUnit).multiply(bigUnit);
        BigInteger bigUsage = BigInteger.valueOf(usage);
        logger.trace("updateWiFiSpeedLimit bigLimit::{}, bigUsage::{}, compareTo::{}",
                bigLimit.longValue(),
                bigUsage.longValue(),
                bigLimit.compareTo(bigUsage));

        long today24HLastSeconds = DateUtil.getTodayLastSeconds(); // 今天剩余的秒数(24h)
        long todayLastSeconds = 0; // 今天剩余的秒数
        // 根据APP在前后台而不同
        if (isForegroundRunning()) {
            todayLastSeconds = getScreenTimeLimitSecond(false) - getForegroundRunningTime();
        }
        if (todayLastSeconds <= 0 || today24HLastSeconds <= 0) {
            todayLastSeconds = DateUtil.getTodayLastSeconds();
        }
        if (bigLimit.compareTo(bigUsage) > 0) {
            availableData = bigLimit.subtract(bigUsage).longValue();
            if (todayLastSeconds > 0) {
                screenTimeAverageSpeed = availableData / todayLastSeconds;
            }
            if (today24HLastSeconds > 0) {
                backgroundAverageSpeed = availableData / today24HLastSeconds;
            }
        }
        settingsRepo.setLongValue(context.getString(R.string.pref_key_wifi_available_data), availableData);
        settingsRepo.setLongValue(context.getString(R.string.pref_key_wifi_screen_time_average_speed),
                screenTimeAverageSpeed);
        settingsRepo.setLongValue(context.getString(R.string.pref_key_wifi_background_average_speed),
                backgroundAverageSpeed);
    }
    /**
     * 获取WiFi网络可用数据
     */
    public static long getWiFiAvailableData() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getLongValue(context.getString(R.string.pref_key_wifi_available_data));
    }

    /**
     * 获取DHT Sessions数
     */
    public static int getDHTSessions() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getIntValue(context.getString(R.string.pref_key_sessions),
                0);
    }

    /**
     * 更新DHT Sessions数
     */
    public static void updateDHTSessions(int sessions) {
        Context context = MainApplication.getInstance();
        settingsRepo.setIntValue(context.getString(R.string.pref_key_sessions),
                sessions);
    }

    /**
     * 设置是否启动后台数据模式
     */
    public static void enableBackgroundMode(boolean enable) {
        Context context = MainApplication.getInstance();
        settingsRepo.setBooleanValue(context.getString(R.string.pref_key_bg_data_mode), enable);
    }

    /**
     * 获取是否启动后台数据模式
     */
    public static boolean backgroundMode() {
        Context context = MainApplication.getInstance();
        return settingsRepo.getBooleanValue(context.getString(R.string.pref_key_bg_data_mode),
                false);
    }

    /**
     * 当前网络是否还有剩余可用流量
     */
    public static boolean isHaveAvailableData() {
        boolean isHaveAvailableData;
        if (NetworkSetting.isMeteredNetwork()) {
            isHaveAvailableData = getMeteredAvailableData() > 0;
        } else {
            isHaveAvailableData = getWiFiAvailableData() > 0;
        }
        return isHaveAvailableData;
    }

    /**
     * 计算主循环时间间隔
     * @return 返回计算的时间间隔
     */
    public static void calculateMainLoopInterval() {
        Interval mainLoopMin;
        Interval mainLoopMax;

        boolean enableBackgroundMode = backgroundMode();
        // 前台运行，并且没有启动后台数据模式
        boolean foregroundRunning = isForegroundRunning() && !enableBackgroundMode;
        if (foregroundRunning) {
            mainLoopMin = Interval.FORE_MAIN_LOOP_MIN;
            mainLoopMax = Interval.FORE_MAIN_LOOP_MAX;
        } else {
            mainLoopMin = Interval.BACK_MAIN_LOOP_MIN;
            mainLoopMax = Interval.BACK_MAIN_LOOP_MAX;
        }
        int timeInterval = mainLoopMax.getInterval();
        boolean isUpdate = true;
        // 无可用剩余流量直接取最大时间间隔
        if (isHaveAvailableData()) {
            // 无网络；不更新链端时间间隔
            if (settingsRepo.internetState()) {
                long averageSpeed;
                if (isMeteredNetwork()) {
                    // 当前网络为计费网络
                    // 是否启动后台数据模式
                    if (enableBackgroundMode) {
                        averageSpeed = NetworkSetting.getMeteredBackgroundAverageSpeed();
                    } else {
                        averageSpeed = NetworkSetting.getMeteredScreenTimeAverageSpeed();
                    }
                } else {
                    // 当前网络为非计费网络
                    // 是否启动后台数据模式
                    if (enableBackgroundMode) {
                        averageSpeed = NetworkSetting.getWifiBackgroundAverageSpeed();
                    } else {
                        averageSpeed = NetworkSetting.getWifiScreenTimeAverageSpeed();
                    }
                }
                long currentSpeed = NetworkSetting.getCurrentSpeed();
                if (averageSpeed > 0) {
                    double rate = currentSpeed * 1.0f / averageSpeed;
                    timeInterval = calculateTimeInterval(rate, mainLoopMin, mainLoopMax);
                    int lastTimeInterval = FrequencyUtil.getMainLoopAverageInterval();
                    logger.debug("calculateMainLoopInterval currentSpeed::{}, averageSpeed::{}, " +
                                    "rate::{}, timeInterval::{}, lastTimeInterval::{}, mainLoopMax::{}",
                            currentSpeed, averageSpeed, rate, timeInterval, lastTimeInterval,
                            mainLoopMax.getInterval());
                } else {
                    timeInterval = Interval.MAIN_LOOP_NO_AVERAGE_SPEED.getInterval();
                }
            } else {
                isUpdate = false;
            }
        }
        if (isUpdate) {
            FrequencyUtil.updateMainLoopInterval(timeInterval);
        }
    }

    /**
     * 计算时间间隔
     * @param rate 平均网速和网速限制的比率
     * @param min 最小值
     * @param max 最大值
     * @return 返回计算的时间间隔
     */
    private static int calculateTimeInterval(double rate, Interval min, Interval max) {
        int lastTimeInterval = FrequencyUtil.getMainLoopAverageInterval();
        int timeInterval = Math.max(min.getInterval(), (int)(lastTimeInterval * rate));
        timeInterval = Math.min(timeInterval, max.getInterval());
        return timeInterval;
    }
}