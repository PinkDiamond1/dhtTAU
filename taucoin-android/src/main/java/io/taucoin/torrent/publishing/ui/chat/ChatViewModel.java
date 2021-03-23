package io.taucoin.torrent.publishing.ui.chat;

import android.app.Application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.torrent.publishing.core.model.TauDaemon;
import io.taucoin.torrent.publishing.core.model.data.ChatMsgAndUser;
import io.taucoin.torrent.publishing.core.model.data.ChatMsgStatus;
import io.taucoin.torrent.publishing.core.model.data.Result;
import io.taucoin.torrent.publishing.core.storage.sqlite.RepositoryHelper;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.ChatMsg;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.ChatMsgLog;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.User;
import io.taucoin.torrent.publishing.core.storage.sqlite.repo.ChatRepository;
import io.taucoin.torrent.publishing.core.storage.sqlite.repo.UserRepository;
import io.taucoin.torrent.publishing.core.utils.DateUtil;
import io.taucoin.torrent.publishing.core.utils.HashUtil;
import io.taucoin.torrent.publishing.core.utils.MsgSplitUtil;
import io.taucoin.torrent.publishing.core.utils.StringUtil;
import io.taucoin.torrent.publishing.core.utils.Utils;
import io.taucoin.torrent.publishing.ui.constant.Page;
import io.taucoin.types.Message;
import io.taucoin.types.MessageType;
import io.taucoin.util.ByteUtil;

/**
 * 聊天相关的ViewModel
 */
public class ChatViewModel extends AndroidViewModel {

    private static final Logger logger = LoggerFactory.getLogger("ChatViewModel");
    private ChatRepository chatRepo;
    private UserRepository userRepo;
    private CompositeDisposable disposables = new CompositeDisposable();
    private MutableLiveData<Result> chatResult = new MutableLiveData<>();
    private TauDaemon daemon;
    private Disposable visitFriend;
    private ChatSourceFactory sourceFactory;
    public ChatViewModel(@NonNull Application application) {
        super(application);
        chatRepo = RepositoryHelper.getChatRepository(getApplication());
        userRepo = RepositoryHelper.getUserRepository(getApplication());
        daemon = TauDaemon.getInstance(application);
        sourceFactory = new ChatSourceFactory(chatRepo);
    }

    public MutableLiveData<Result> getChatResult() {
        return chatResult;
    }

    public void observeNeedStartDaemon () {
        disposables.add(daemon.observeNeedStartDaemon()
                .subscribeOn(Schedulers.io())
                .filter((needStart) -> needStart)
                .subscribe((needStart) -> daemon.start()));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        disposables.clear();
        if (sourceFactory != null) {
            sourceFactory.onCleared();
            sourceFactory = null;
        }
    }

    /**
     * 观察和朋友聊天
     * @param friendPK 朋友公钥
     * @return LiveData
     */
    LiveData<PagedList<ChatMsgAndUser>> observerChat(String friendPK) {
        sourceFactory.setFriendPk(friendPK);
        return new LivePagedListBuilder<>(sourceFactory, Page.getPageListConfig())
                .setInitialLoadKey(Page.PAGE_SIZE)
                .build();
    }

    /**
     * 异步给朋友发信息任务
     * @param friendPk 朋友公钥
     * @param msg 消息
     * @param type 消息类型
     */
    void sendMessage(String friendPk, String msg, int type) {
        Disposable disposable = Flowable.create((FlowableOnSubscribe<Result>) emitter -> {
            Result result = syncSendMessageTask(friendPk, msg, type);
            emitter.onNext(result);
            emitter.onComplete();
        }, BackpressureStrategy.LATEST)
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> chatResult.postValue(result));
        disposables.add(disposable);
    }

    /**
     * 批量测试入口
     * 异步给朋友发信息任务
     * @param friendPk 朋友公钥
     */
    void sendBatchDebugMessage(String friendPk, int time) {
        Disposable disposable = Flowable.create((FlowableOnSubscribe<Boolean>) emitter -> {
            InputStream inputStream = null;
            try {
                for (int i = 0; i < time; i++) {
                    if (emitter.isCancelled()) {
                        break;
                    }
                    if (null == inputStream) {
                        inputStream = getApplication().getAssets().open("tianlongbabu1-4.txt");
                    } else {
                        int availableSize = inputStream.available();
                        if (availableSize < 10 * 1024) {
                            inputStream = getApplication().getAssets().open("tianlongbabu1-4.txt");
                        }
                    }
                    byte[] bytes = new byte[10 * 1024];
                    int read = inputStream.read(bytes);
                    if (read > 0) {
                        String msg = (i + 1) + "、" + new String(bytes, StandardCharsets.UTF_8);
                        sendMessage(friendPk, msg, MessageType.TEXT.ordinal());
                        Thread.sleep(1000);
                    }
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e) {
                if (inputStream != null) {
                    inputStream.close();
                }
            }
            emitter.onNext(true);
            emitter.onComplete();
        }, BackpressureStrategy.LATEST)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe();
        disposables.add(disposable);
    }

    void sendBatchDebugDigitMessage(String friendPk, int time) {
        Disposable disposable = Flowable.create((FlowableOnSubscribe<Boolean>) emitter -> {
            try {
                for (int i = 0; i < time; i++) {
                    if (emitter.isCancelled()) {
                        break;
                    }
                    syncSendMessageTask(friendPk, String.valueOf(i + 1), MessageType.TEXT.ordinal());
//                    Thread.sleep(1000);
                }
            } catch (Exception ignore) {
            }
            emitter.onNext(true);
            emitter.onComplete();
        }, BackpressureStrategy.LATEST)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe();
        disposables.add(disposable);
    }

    /**
     * 同步给朋友发信息任务
     * @param friendPkStr 朋友公钥
     * @param msg 消息
     * @param type 消息类型
     */
    public Result syncSendMessageTask(String friendPkStr, String msg, int type) {
        Result result = new Result();
        try {
            List<byte[]> contents;
            String logicMsgHashStr;
            if (type == MessageType.PICTURE.ordinal()) {
                String progressivePath = MsgSplitUtil.compressAndScansPic(msg);
                logicMsgHashStr = HashUtil.makeFileSha1HashWithTimeStamp(progressivePath);
                contents = MsgSplitUtil.splitPicMsg(progressivePath);
            } else if(type == MessageType.TEXT.ordinal()) {
                logicMsgHashStr = HashUtil.makeSha1HashWithTimeStamp(msg);
                contents = MsgSplitUtil.splitTextMsg(msg);
            } else {
                throw new Exception("Unknown message type");
            }
            byte[] logicMsgHash = ByteUtil.toByte(logicMsgHashStr);
            User user = userRepo.getCurrentUser();
            String senderPkStr = user.publicKey;
            byte[] senderPk = ByteUtil.toByte(senderPkStr);
            byte[] friendPk = ByteUtil.toByte(friendPkStr);
            ChatMsg[] messages = new ChatMsg[contents.size()];
            int contentSize = contents.size();
            for (int nonce = 0; nonce < contentSize; nonce++) {
                byte[] content = contents.get(nonce);
                long millisTime = DateUtil.getMillisTime();
                long timestamp = millisTime / 1000;
                Message message;
                if (type == MessageType.TEXT.ordinal()) {
                    message = Message.createTextMessage(BigInteger.valueOf(timestamp), senderPk,
                            friendPk, logicMsgHash, BigInteger.valueOf(nonce), content);
                } else {
                    message = Message.createPictureMessage(BigInteger.valueOf(timestamp), senderPk,
                            friendPk, logicMsgHash, BigInteger.valueOf(nonce), content);
                }
                byte[] key = Utils.keyExchange(friendPkStr, user.seed);
                message.encrypt(key);
                String hash = ByteUtil.toHexString(message.getHash());
                byte[] encryptedContent = message.getEncryptedContent();
                logger.debug("sendMessageTask newMsgHash::{}, contentType::{}, " +
                                "nonce::{}, rawLength::{}, encryptedLength::{}, " +
                                "encodedLength::{}, logicMsgHash::{}, millisTime::{}",
                        hash, type, nonce, content.length,
                        null == encryptedContent ? 0 : encryptedContent.length,
                        message.getEncoded().length,
                        logicMsgHashStr, DateUtil.format(millisTime, DateUtil.pattern9));

                // 组织Message的结构，并发送到DHT和数据入库
                String contentStr = ByteUtil.toHexString(encryptedContent);
                ChatMsg chatMsg = new ChatMsg(hash, senderPkStr, friendPkStr, contentStr, type,
                        timestamp, nonce, logicMsgHashStr);
                messages[nonce] = chatMsg;

                // 更新消息日志信息
                ChatMsgLog chatMsgLog = new ChatMsgLog(chatMsg.hash,
                        ChatMsgStatus.UNSENT.getStatus(), millisTime);
                chatRepo.addChatMsgLog(chatMsgLog);

                // 更新链端消息列表
//                daemon.updateMessagesList(friendPk, message);
            }
            // 批量添加到数据库
            chatRepo.addChatMessages(messages);
        } catch (Exception e) {
            logger.error("sendMessageTask error", e);
            result.setFailMsg(e.getMessage());
        }
        return result;
    }

    /**
     * 观察消息日志信息
     */
    Observable<List<ChatMsgLog>> observerMsgLogs(String hash) {
        return chatRepo.observerMsgLogs(hash);
    }

    /**
     * 当留在该朋友聊天页面时，只访问该朋友
     * @param friendPk 要访问的朋友
     */
    void startVisitFriend(String friendPk) {
        if (StringUtil.isEmpty(friendPk)) {
            return;
        }
        visitFriend = daemon.observeDaemonRunning()
            .subscribeOn(Schedulers.io())
            .subscribe((isRunning) -> {
                if (isRunning) {
                    daemon.startVisitFriend(friendPk);
                }
            });
    }

    /**
     * 当离开朋友聊天页面时，取消对朋友的单独访问
     */
    void stopVisitFriend() {
        if (visitFriend != null && !visitFriend.isDisposed()) {
            visitFriend.dispose();
        }
        daemon.stopVisitFriend();
    }
}