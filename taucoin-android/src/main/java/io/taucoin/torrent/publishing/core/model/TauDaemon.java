package io.taucoin.torrent.publishing.core.model;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import com.frostwire.jlibtorrent.Ed25519;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.chain.ChainManager;
import io.taucoin.communication.CommunicationManager;
import io.taucoin.controller.TauController;
import io.taucoin.core.AccountState;
import io.taucoin.db.DBException;
import io.taucoin.genesis.GenesisConfig;
import io.taucoin.listener.MsgStatus;
import io.taucoin.torrent.publishing.MainApplication;
import io.taucoin.torrent.publishing.R;
import io.taucoin.torrent.publishing.core.settings.SettingsRepository;
import io.taucoin.torrent.publishing.core.storage.sqlite.RepositoryHelper;
import io.taucoin.torrent.publishing.core.storage.leveldb.AndroidLeveldbFactory;
import io.taucoin.torrent.publishing.core.utils.AppUtil;
import io.taucoin.torrent.publishing.core.utils.ChainLinkUtil;
import io.taucoin.torrent.publishing.core.utils.DateUtil;
import io.taucoin.torrent.publishing.core.utils.DeviceUtils;
import io.taucoin.torrent.publishing.core.utils.NetworkSetting;
import io.taucoin.torrent.publishing.core.utils.StringUtil;
import io.taucoin.torrent.publishing.core.utils.Utils;
import io.taucoin.torrent.publishing.receiver.ConnectionReceiver;
import io.taucoin.torrent.publishing.service.WorkerManager;
import io.taucoin.torrent.publishing.service.SystemServiceManager;
import io.taucoin.torrent.publishing.receiver.PowerReceiver;
import io.taucoin.torrent.publishing.service.Scheduler;
import io.taucoin.torrent.publishing.service.TauService;
import io.taucoin.torrent.publishing.ui.setting.TrafficTipsActivity;
import io.taucoin.types.BlockContainer;
import io.taucoin.types.Message;
import io.taucoin.types.Transaction;
import io.taucoin.util.ByteUtil;

/**
 * 区块链业务Daemon
 */
public class TauDaemon {
    private static final String TAG = TauDaemon.class.getSimpleName();
    private static final Logger logger = LoggerFactory.getLogger(TAG);
    private static final int sessions = 1; // 启动session数

    private Context appContext;
    private SettingsRepository settingsRepo;
    private CompositeDisposable disposables = new CompositeDisposable();
    private PowerReceiver powerReceiver = new PowerReceiver();
    private ConnectionReceiver connectionReceiver = new ConnectionReceiver();
    private TauController tauController;
    private PowerManager.WakeLock wakeLock;
    private SystemServiceManager systemServiceManager;
    private ExecutorService exec = Executors.newSingleThreadExecutor();
    private TauListenHandler tauListenHandler;
    private MsgListenHandler msgListenHandler;
    private TauInfoProvider tauInfoProvider;
    private volatile boolean isRunning = false;
    private volatile boolean trafficTips = true; // 剩余流量用完提示
    private volatile String seed;
    private long noRemainingDataTimes = 0; // 触发无剩余流量的次数

    private static volatile TauDaemon instance;

    public static TauDaemon getInstance(@NonNull Context appContext) {
        if (instance == null) {
            synchronized (TauDaemon.class) {
                if (instance == null)
                    instance = new TauDaemon(appContext);
            }
        }
        return instance;
    }

    /**
     * TauDaemon构造函数
     */
    private TauDaemon(@NonNull Context appContext) {
        this.appContext = appContext;
        settingsRepo = RepositoryHelper.getSettingsRepository(appContext);
        systemServiceManager = SystemServiceManager.getInstance();
        tauListenHandler = new TauListenHandler(appContext, this);
        msgListenHandler = new MsgListenHandler(appContext);
        tauInfoProvider = TauInfoProvider.getInstance(this);

        AndroidLeveldbFactory androidLeveldbFactory = new AndroidLeveldbFactory();
        String repoPath = appContext.getApplicationInfo().dataDir;
        String deviceID = DeviceUtils.getCustomDeviceID(appContext);
        logger.info("TauController deviceID::{}, repoPath::{}", deviceID, repoPath);
        tauController = new TauController(repoPath, androidLeveldbFactory, deviceID.getBytes());
        tauController.registerListener(daemonListener);
        tauController.registerMsgListener(msgListener);

        switchPowerReceiver();
        switchConnectionReceiver();
    }

    /**
     * 更新用户Seed
     * @param seed Seed
     */
    public void updateSeed(String seed) {
        if (StringUtil.isEmpty(seed) || StringUtil.isEquals(seed, this.seed)) {
            return;
        }
        // 清除消息处理，防止多Seed数据错乱
        msgListenHandler.onCleared();
        this.seed = seed;
        logger.debug("updateSeed ::{}", seed);
        byte[] bytesSeed = ByteUtil.toByte(seed);
        if (isRunning) {
            tauController.updateKey(bytesSeed);
        } else {
            tauController.updateKey(Ed25519.createKeypair(bytesSeed));
        }
    }

    /**
     * Daemon启动
     */
    public void start() {
        if (isRunning){
            return;
        }
        Intent intent = new Intent(appContext, TauService.class);
        Utils.startServiceBackground(appContext, intent);
    }

    /**
     * 观察是否需要启动Daemon
     * @return Flowable
     */
    public Flowable<Boolean> observeNeedStartDaemon() {
        return Flowable.create((emitter) -> {
            if (emitter.isCancelled()){
                return;
            }
            Runnable emitLoop = () -> {
                while (!Thread.interrupted()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (emitter.isCancelled() || isRunning){
                        return;
                    }
                    emitter.onNext(true);
                }
            };

            Disposable d = observeDaemonRunning()
                    .subscribeOn(Schedulers.io())
                    .subscribe((isRunning) -> {
                        if (emitter.isCancelled())
                            return;

                        if (!isRunning) {
                            emitter.onNext(true);
                            exec.submit(emitLoop);
                        }
                    });
            if (!emitter.isCancelled()) {
                emitter.onNext(!isRunning);
                emitter.setDisposable(d);
            }

        }, BackpressureStrategy.LATEST);
    }

    /**
     * 观察Daemon是否是在运行
     */
    public Flowable<Boolean> observeDaemonRunning() {
        return Flowable.create((emitter) -> {
            if (emitter.isCancelled())
                return;

            TauDaemonListener listener = new TauDaemonListener() {
                @Override
                public void onTauStarted(boolean success, String errMsg) {
                    if (!emitter.isCancelled() && success)
                        emitter.onNext(true);
                }

                @Override
                public void onTauStopped() {
                    if (!emitter.isCancelled())
                        emitter.onNext(false);
                }
            };

            if (!emitter.isCancelled()) {
                emitter.onNext(isRunning);
                registerListener(listener);
                emitter.setDisposable(Disposables.fromAction(() -> unregisterListener(listener)));
            }

        }, BackpressureStrategy.LATEST);
    }

    /**
     * 链端事件监听逻辑处理
     */
    private final MsgListener msgListener = new MsgListener() {

        @Override
        public void onNewMessage(byte[] friend, Message message) {
            msgListenHandler.onNewMessage(friend, message);
        }

        @Override
        public void onNewDeviceID(byte[] deviceID) {
            msgListenHandler.onNewDeviceID(deviceID);
        }

        @Override
        public void onNewFriend(byte[] friend) {
            msgListenHandler.onNewFriend(friend);
        }

        @Override
        public void onReadMessageRoot(byte[] friend, byte[] root) {
            msgListenHandler.onReceivedMessageRoot(friend, root);
        }

        @Override
        public void onDiscoveryFriend(byte[] friend) {
            msgListenHandler.onDiscoveryFriend(friend);
        }

        @Override
        public void onMessageStatus(byte[] friend, byte[] root, MsgStatus msgStatus) {
            msgListenHandler.onMessageStatus(friend, root, msgStatus);
        }
    };

    /**
     * 链端事件监听逻辑处理
     */
    private final TauDaemonListener daemonListener = new TauDaemonListener() {
        @Override
        public void onTauStarted(boolean success, String errMsg) {
            if (success) {
                logger.debug("Tau start successfully");
                isRunning = true;
                WorkerManager.startAllWorker();
                handleSettingsChanged(appContext.getString(R.string.pref_key_foreground_running));
                resetReadOnly();
            } else {
                logger.error("Tau failed to start::{}", errMsg);
            }
        }

        @Override
        public void onClearChainAllState(byte[] chainID) {
            tauListenHandler.handleClearChainAllState(chainID);
        }

        @Override
        public void onNewBlock(byte[] chainID, BlockContainer blockContainer) {
            tauListenHandler.handleNewBlock(chainID, blockContainer);
        }

        @Override
        public void onRollBack(byte[] chainID, BlockContainer blockContainer) {
            tauListenHandler.handleRollBack(chainID, blockContainer);
        }

        @Override
        public void onSyncBlock(byte[] chainID, BlockContainer blockContainer) {
            tauListenHandler.handleSyncBlock(chainID, blockContainer);
        }
    };

    /**
     * Only calls from TauService
     */
    public void doStart() {
        logger.info("doStart");
        if (isRunning)
            return;
        disposables.add(settingsRepo.observeSettingsChanged()
                .subscribe(this::handleSettingsChanged));
        disposables.add(tauInfoProvider.observeTrafficStatistics()
                .subscribeOn(Schedulers.newThread())
                .subscribe());

        rescheduleTAUBySettings();
        resetDHTSessions();
        tauController.start(NetworkSetting.getDHTSessions());
    }

    /**
     * Only calls from TauService
     */
    public void doStop() {
        if (!isRunning)
            return;
        isRunning = false;
        WorkerManager.cancelAllWork();
        disposables.clear();
        tauController.stop();
    }

    /**
     * 强制停止
     */
    public void forceStop() {
        Intent i = new Intent(appContext, TauService.class);
        i.setAction(TauService.ACTION_SHUTDOWN);
        Utils.startServiceBackground(appContext, i);
    }

    /**
     * 事件监听注册进TauController
     * @param listener TauDaemonListener
     */
    public void registerListener(TauDaemonListener listener) {
        tauController.registerListener(listener);
    }

    /**
     * 从TauController取消事件监听
     * @param listener TauDaemonListener
     */
    public void unregisterListener(TauDaemonListener listener) {
        tauController.unregisterListener(listener);
    }

    /**
     * 电源充电状态切换广播接受器
     */
    private void switchPowerReceiver() {
        settingsRepo.chargingState(systemServiceManager.isPlugged());
        try {
            appContext.unregisterReceiver(powerReceiver);
        } catch (IllegalArgumentException ignore) {
            /* Ignore non-registered receiver */
        }
        appContext.registerReceiver(powerReceiver, PowerReceiver.getCustomFilter());
    }

    /**
     * 网络连接切换广播接受器
     */
    private void switchConnectionReceiver() {
        settingsRepo.internetState(systemServiceManager.isHaveNetwork());
        NetworkSetting.clearSpeedList();
        NetworkSetting.setMeteredNetwork(systemServiceManager.isNetworkMetered());
        try {
            appContext.unregisterReceiver(connectionReceiver);
        } catch (IllegalArgumentException ignore) {
            /* Ignore non-registered receiver */
        }
        appContext.registerReceiver(connectionReceiver, ConnectionReceiver.getFilter());
    }

    /**
     * 处理设置的改变
     * @param key 存储key
     */
    private void handleSettingsChanged(String key) {
        if (key.equals(appContext.getString(R.string.pref_key_internet_state))) {
            logger.info("SettingsChanged, internet state::{}", settingsRepo.internetState());
            rescheduleTAUBySettings(true);
            enableServerMode(settingsRepo.serverMode());
        } else if (key.equals(appContext.getString(R.string.pref_key_charging_state))) {
            logger.info("SettingsChanged, charging state::{}", settingsRepo.chargingState());
            enableServerMode(settingsRepo.serverMode());
        } else if (key.equals(appContext.getString(R.string.pref_key_is_metered_network))) {
            logger.info("clearSpeedList, isMeteredNetwork::{}", NetworkSetting.isMeteredNetwork());
        } else if (key.equals(appContext.getString(R.string.pref_key_foreground_running))) {
            logger.info("foreground running::{}", settingsRepo.getBooleanValue(key));
        }
    }

    /**
     * 根据当前设置重新调度DHT
     */
    void rescheduleTAUBySettings() {
        rescheduleTAUBySettings(false);
    }

    private synchronized void rescheduleTAUBySettings(boolean isRestart) {
        if (!isRunning) {
            return;
        }
        try {
            // 判断有无网络连接
            if (settingsRepo.internetState()) {
                if (isRestart) {
                    resetDHTSessions();
                    tauController.restartSessions(NetworkSetting.getDHTSessions());
                    resetReadOnly();
                    logger.info("rescheduleTAUBySettings restartSessions::{}",
                            NetworkSetting.getDHTSessions());
                } else {
                    float rate = NetworkSetting.calculateIntervalRate();
                    if (rate > 0) {
                        int mainLoopInterval = NetworkSetting.calculateMainLoopInterval(rate);
                        int gossipInterval = NetworkSetting.calculateGossipInterval(rate);
                        int DHTOPInterval = NetworkSetting.calculateDHTOPInterval(rate);
                        tauController.getCommunicationManager().setIntervalTime(mainLoopInterval);
                        tauController.getCommunicationManager().setGossipTimeInterval(gossipInterval);
                        tauController.getDHTEngine().regulateDHTOPInterval(DHTOPInterval);
                        // 更新UI展示链端主循环、Gossip、DHT操作的时间间隔
                        updateUIInterval(mainLoopInterval, gossipInterval, DHTOPInterval);
                    } else if (rate == -1) {
                        resetReadOnly(true);
                        showNoRemainingDataTipsDialog();
                    }
                    if (rate != -1) {
                        trafficTips = true;
                        noRemainingDataTimes = 0;
                        resetReadOnly(false);
                    }
                }

            }
        } catch (Exception e) {
            logger.error("rescheduleDHTBySettings errors", e);
        }
    }

    /**
     * 更新链端主循环、Gossip、DHT操作的时间间隔以供UI显示
     */
    private void updateUIInterval(int mainLoopInterval, int gossipInterval, int DHTOPInterval) {
        settingsRepo.setLongValue(appContext.getString(R.string.pref_key_main_loop_interval),
                mainLoopInterval);
        settingsRepo.setLongValue(appContext.getString(R.string.pref_key_gossip_interval),
                gossipInterval);
        settingsRepo.setLongValue(appContext.getString(R.string.pref_key_dht_operation_interval),
                tauController.getDHTEngine().getDHTOPInterval());
        logger.info("Reschedule DHTSessions::{}, " +
                        "MainLoopInterval::{}ms, " +
                        "GossipInterval::{}s, " +
                        "DHTOPInterval::{}ms",
                NetworkSetting.getDHTSessions(),
                mainLoopInterval,
                gossipInterval,
                DHTOPInterval);
    }

    /**
     * 显示没有剩余流量提示对话框
     * 必须同时满足需要提示、触发次数大于等于网速采样数、APP在前台、目前没有打开的流量提示Activity
     */
    private void showNoRemainingDataTipsDialog() {
        if (trafficTips) {
            if (noRemainingDataTimes < NetworkSetting.current_speed_sample) {
                noRemainingDataTimes += 1;
                return;
            }
        }
        if (trafficTips && AppUtil.isOnForeground(appContext) &&
                !AppUtil.isForeground(appContext, TrafficTipsActivity.class)) {
            Intent intent = new Intent(appContext, TrafficTipsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        }
    }

    /**
     * 处理用户流量提示选择
     * @param updateDailyDataLimit 是否更新每日流量限制
     */
    public void handleUserSelected(boolean updateDailyDataLimit) {
        trafficTips = updateDailyDataLimit;
        resetReadOnly(!updateDailyDataLimit);
    }

    /**
     * 计费网络下设置DHTEngine为read only模式
     */
    private void resetReadOnly() {
        resetReadOnly(false);
    }

    /**
     * 计费网络下设置DHTEngine为read only模式
     *  或者没有剩余流量时，强迫为read only模式
     */
    private void resetReadOnly(boolean isForced) {
        if (!isRunning) {
            return;
        }
        boolean isReadOnly = NetworkSetting.isMeteredNetwork() || isForced;
        tauController.getDHTEngine().setReadOnly(isReadOnly);
    }

    /**
     * 重置DHT Sessions个数，固定单session
     */
    private void resetDHTSessions() {
        NetworkSetting.updateDHTSessions(sessions);
    }

    /**
     * 设置是否启动服务器模式
     */
    public void enableServerMode(boolean enable) {
        Utils.enableBootReceiver(appContext, enable);
        logger.info("EnableServerMode, enable::{}", enable);
        if (enable) {
            keepCPUWakeLock(true);
            Scheduler.cancelWakeUpAppAlarm(appContext);
        } else {
            // 设备在充电状态并网络可用的状态下，启动WakeLock
            logger.info("EnableServerMode, chargingState::{}, internetState::{}",
                    settingsRepo.chargingState(), settingsRepo.internetState());
            if(settingsRepo.chargingState() && settingsRepo.internetState()){
                keepCPUWakeLock(true);
                Scheduler.cancelWakeUpAppAlarm(appContext);
            }else{
                keepCPUWakeLock(false);
                Scheduler.setWakeUpAppAlarm(appContext);
            }
        }
    }

    /**
     * 保持CPU唤醒锁定
     */
    @SuppressLint("WakelockTimeout")
    public void keepCPUWakeLock(boolean enable) {
        if (enable) {
            if (wakeLock == null) {
                Context context = MainApplication.getInstance();
                PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            }
            if (!wakeLock.isHeld()){
                wakeLock.acquire();
            }
        } else {
            if (wakeLock == null){
                return;
            }
            if (wakeLock.isHeld()){
                wakeLock.release();
            }
        }
        settingsRepo.wakeLock(enable);
    }

    /**
     * 提交交易到链端交易池
     * @param tx 签过名的交易数据
     */
    public void submitTransaction(Transaction tx){
        if (isRunning) {
            getChainManager().sendTransaction(tx);
            logger.info("submitTransaction txID::{}, txType::{}",
                    ByteUtil.toHexString(tx.getTxID()), tx.getTxType());
        }
    }

    /**
     * 添加或创建社区
     * @param cf GenesisConfig
     */
    public void createNewCommunity(GenesisConfig cf) {
        if (isRunning) {
            boolean isSuccess = getChainManager().createNewCommunity(cf);
            logger.info("createNewCommunity CommunityName::{}, chainID::{}, isSuccess::{}",
                    cf.getCommunityName(),
                    Utils.toUTF8String(cf.getChainID()),
                    isSuccess);
        }
    }

    private ChainManager getChainManager() {
        return tauController.getChainManager();
    }

    /**
     * 获取用户Nonce值
     * @return long
     */
    public long getUserPower(String chainID, String publicKey) {
        try {
            AccountState accountState = tauController.getChainManager().getStateDB().getAccount(
                    chainID.getBytes(), ByteUtil.toByte(publicKey));
            if (accountState != null) {
                return accountState.getNonce().longValue();
            }
        } catch (Exception e) {
            logger.error("getUserPower error", e);
        }
        return 0L;
    }

    /**
     * 获取用户余额
     * @return long
     */
    public long getUserBalance(String chainID, String publicKey) {
        try {
            AccountState accountState = tauController.getChainManager().getStateDB().getAccount(
                    chainID.getBytes(), ByteUtil.toByte(publicKey));
            if (accountState != null) {
                return accountState.getBalance().longValue();
            }
        } catch (Exception e) {
            logger.error("getUserPower error", e);
        }
        return 0L;
    }

    /**
     * 跟随链/社区
     * @param chainLink
     */
    public void followCommunity(String chainLink) {
        if (isRunning) {
            ChainLinkUtil.ChainLink decode = ChainLinkUtil.decode(chainLink);
            if (decode.isValid()) {
                boolean isSuccess = tauController.followChain(decode.getBytesDn(),
                        decode.getBytesBootstraps());
                logger.info("followCommunity chainLink::{}, isSuccess::{}", chainLink, isSuccess);
            }
        }
    }

    /**
     * 取消跟随链/社区
     * @param chainID
     */
    public void unfollowCommunity(String chainID) {
        if (isRunning) {
            boolean isSuccess = tauController.unfollowChain(chainID.getBytes());
            logger.info("unfollowChain chainID::{}, isSuccess::{}", chainID, isSuccess);
        }
    }

    /**
     *
     * @param memo
     * @return
     */
    public byte[] putForumNote(String memo) {
        if (isRunning) { }
        return new byte[20];
    }

    private CommunicationManager getCommunicationManager() {
        return tauController.getCommunicationManager();
    }

    /**
     * 获取消息从levelDB
     * @param hash
     * @return msg
     * @throws DBException
     */
    public byte[] getMsg(byte[] hash) throws DBException {
        return  getCommunicationManager().getMessageDB().getMessageByHash(hash);
    }

    /**
     * 请求消息数据
     * @param hash
     * @param friendPK
     */
    public void requestMessageData(byte[] hash, byte[] friendPK) {
        getCommunicationManager().requestMessageData(hash, friendPK);
    }

    /**
     * 添加朋友
     * @param friendPk 朋友公钥
     */
    public void addNewFriend(byte[] friendPk) {
        if (!isRunning) {
            return;
        }
        getCommunicationManager().addNewFriend(friendPk);
        logger.info("addNewFriend friendPk::{}, timestamp::{}", ByteUtil.toHexString(friendPk),
                DateUtil.formatTime(DateUtil.getTime(), DateUtil.pattern6));
    }

    /**
     * 发送消息
     * @param friendPK
     * @param message
     * @return
     */
    public boolean sendMessage(byte[] friendPK, Message message) {
        if (!isRunning) {
            return false;
        }
        boolean isPublishSuccess = getCommunicationManager().publishNewMessage(friendPK, message);
        logger.debug("sendMessage isPublishSuccess{}", isPublishSuccess);
        String hash = ByteUtil.toHexString(message.getHash());
        logger.debug("TAU messaging sendMessage friendPk::{}, hash::{}, timestamp::{}",
                ByteUtil.toHexString(friendPK), hash,
                DateUtil.formatTime(DateUtil.getTime(), DateUtil.pattern6));
        return isPublishSuccess;
    }

    /**
     * 统计Sessions的nodes数
     */
    List<Long> getSessionNodes() {
        if (!isRunning) {
            return null;
        }
        return tauController.getDHTEngine().getSessionNodes();
    }
}