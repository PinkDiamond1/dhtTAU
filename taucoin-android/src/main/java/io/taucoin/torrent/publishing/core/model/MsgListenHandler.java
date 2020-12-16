package io.taucoin.torrent.publishing.core.model;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import androidx.work.Data;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.taucoin.torrent.publishing.MainApplication;
import io.taucoin.torrent.publishing.core.storage.sqlite.RepositoryHelper;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.ChatMsg;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.ChatMsgType;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.Friend;
import io.taucoin.torrent.publishing.core.storage.sqlite.repo.ChatRepository;
import io.taucoin.torrent.publishing.core.storage.sqlite.repo.CommunityRepository;
import io.taucoin.torrent.publishing.core.storage.sqlite.repo.FriendRepository;
import io.taucoin.torrent.publishing.core.utils.DateUtil;
import io.taucoin.torrent.publishing.service.WorkerManager;
import io.taucoin.types.Message;
import io.taucoin.util.ByteUtil;

/**
 * MsgListener处理程序
 */
class MsgListenHandler {
    private static final Logger logger = LoggerFactory.getLogger("MsgListenHandler");
    private CompositeDisposable disposables = new CompositeDisposable();
    private ChatRepository chatRepo;
    private CommunityRepository communityRepo;
    private FriendRepository friendRepo;
    private TauDaemon daemon;

    MsgListenHandler(Context appContext, TauDaemon daemon){
        this.daemon = daemon;
        chatRepo = RepositoryHelper.getChatRepository(appContext);
        communityRepo = RepositoryHelper.getCommunityRepository(appContext);
        friendRepo = RepositoryHelper.getFriendsRepository(appContext);
    }
    /**
     * 处理新的消息
     * 0、如果没和朋友建立Chat, 创建Chat
     * 1、更新朋友状态
     * 2、保存Chat的聊天信息
     * @param friendPk byte[] 朋友公钥
     * @param message Message
     */
    void onNewMessage(byte[] friendPk, Message message) {
        logger.debug("onNewMessage friendPk::{}，Hash::{}",
                ByteUtil.toHexString(friendPk),
                ByteUtil.toHexString(message.getHash()));
        Data data = new Data.Builder()
                .putByteArray("friendPk", friendPk)
                .putByteArray("hash", message.getHash())
                .putLong("timestamp", message.getTimestamp().longValue())
                .putByteArray("content", message.getContent())
                .putInt("type", message.getType().ordinal())
                .build();
        WorkerManager.startMsgListenHandlerWorker(data);
    }

    /**
     * 消息已被接收
     * @param friendPk byte[] 朋友公钥
     * @param root 消息root
     */
    void onReceivedMessageRoot(byte[] friendPk, byte[] root) {
        logger.debug("onReceivedMessageRoot friendPk::{}，MessageRoot::{}",
                ByteUtil.toHexString(friendPk), root);
        Disposable disposable = Flowable.create(emitter -> {
            try {
                String friendPkStr = ByteUtil.toHexString(friendPk);
                String hash = ByteUtil.toHexString(root);
                ChatMsg msg = chatRepo.queryChatMsg(friendPkStr, hash);
                if (msg != null) {
                    msg.status = ChatMsgType.RECEIVED.ordinal();
                    chatRepo.updateChatMsg(msg);
                }
            } catch (Exception e) {
                logger.error("onNewMessage error", e);
            }
            emitter.onComplete();
        }, BackpressureStrategy.LATEST)
                .subscribeOn(Schedulers.io())
                .subscribe();
        disposables.add(disposable);
    }

    /**
     * 销毁处理程序
     */
    void destroy() {
        disposables.clear();
    }

    /**
     * 发现朋友
     * @param friendPk 朋友公钥
     */
    void onDiscoveryFriend(byte[] friendPk) {
        logger.debug("onDiscoveryFriend friendPk::{}",
                ByteUtil.toHexString(friendPk));
        Disposable disposable = Flowable.create(emitter -> {
            try {
                String userPk = MainApplication.getInstance().getPublicKey();
                String friendPkStr = ByteUtil.toHexString(friendPk);
                Friend friend = friendRepo.queryFriend(userPk, friendPkStr);
                if (friend != null) {
                    friend.state = 2;
                    friend.lastSeenTime = DateUtil.getTime();
                    friendRepo.updateFriend(friend);
                }
            } catch (Exception e) {
                logger.error("onDiscoveryFriend error", e);
            }
            emitter.onComplete();
        }, BackpressureStrategy.LATEST)
                .subscribeOn(Schedulers.io())
                .subscribe();
        disposables.add(disposable);
    }
}
