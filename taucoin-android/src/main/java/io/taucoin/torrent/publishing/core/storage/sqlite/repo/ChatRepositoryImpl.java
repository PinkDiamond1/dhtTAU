package io.taucoin.torrent.publishing.core.storage.sqlite.repo;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import io.taucoin.torrent.publishing.MainApplication;
import io.taucoin.torrent.publishing.core.model.data.ChatMsgAndUser;
import io.taucoin.torrent.publishing.core.storage.sqlite.AppDatabase;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.ChatMsg;
import io.taucoin.torrent.publishing.core.storage.sqlite.entity.ChatMsgLog;
import io.taucoin.torrent.publishing.core.utils.DateUtil;
import io.taucoin.torrent.publishing.core.utils.StringUtil;

/**
 * FriendRepository接口实现
 */
public class ChatRepositoryImpl implements ChatRepository{

    private static final int minChangeTime = 500; // 最小改变时间，防止频繁刷新，单位：ms
    private Context appContext;
    private AppDatabase db;
    private PublishSubject<String> dataSetChangedPublish = PublishSubject.create();
    private Disposable changeTimer;
    private boolean isNeedRefresh = false;
    private ExecutorService sender = Executors.newSingleThreadExecutor();

    /**
     * FriendRepositoryImpl 构造函数
     * @param appContext 上下文
     * @param db 数据库实例
     */
    public ChatRepositoryImpl(@NonNull Context appContext, @NonNull AppDatabase db) {
        this.appContext = appContext;
        this.db = db;
    }

    @Override
    public void addChatMsg(ChatMsg chat) {
        db.chatDao().addChat(chat);
        submitDataSetChanged(chat);
    }

    @Override
    public void addChatMessages(ChatMsg... chats) {
        db.chatDao().addChats(chats);
        if (chats != null && chats.length > 0) {
            submitDataSetChanged(chats[0]);
        }
    }

    @Override
    public void updateChatMsg(ChatMsg chat) {
        db.chatDao().updateChat(chat);
        submitDataSetChanged(chat);
    }

    @Override
    public void updateMsgSendStatus(ChatMsg chat) {
        db.chatDao().updateChat(chat);
    }

    @Override
    public ChatMsg queryChatMsg(String senderPk, String hash) {
        return db.chatDao().queryChatMsg(senderPk, hash);
    }

    /**
     * 查询ChatMsg
     * @param hash
     * @return
     */
    @Override
    public ChatMsg queryChatMsg(String hash) {
        return db.chatDao().queryChatMsg(hash);
    }

    @Override
    public Observable<String> observeDataSetChanged() {
        return dataSetChangedPublish;
    }

    @Override
    public void submitDataSetChanged(ChatMsg chat) {
        String usersPk = chat.senderPk + chat.receiverPk;
        submitDataSetChangedDirect(usersPk);
    }

    private void createChangeTimer(String usersPk) {
        changeTimer = Observable.timer(minChangeTime, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.io())
                .subscribe(aLong -> {
                    if (isNeedRefresh) {
                        isNeedRefresh = false;
                        changeTimer.dispose();
                        submitDataSetChangedDirect(usersPk);
                    }
                });
    }

    private void submitDataSetChangedDirect(String usersPk) {
        if (changeTimer != null && !changeTimer.isDisposed()) {
            isNeedRefresh = true;
            return;
        }
        createChangeTimer(usersPk);
        String changeResult = usersPk + DateUtil.getDateTime();
        sender.submit(() -> {
            dataSetChangedPublish.onNext(changeResult);
        });
    }

    @Override
    public int getNumMessages(String friendPk) {
        String senderPk = MainApplication.getInstance().getPublicKey();
        return db.chatDao().getNumMessages(senderPk, friendPk);
    }

    @Override
    public List<ChatMsgAndUser> getMessages(String friendPk, int startPosition, int loadSize) {
        String senderPk = MainApplication.getInstance().getPublicKey();
        return db.chatDao().getMessages(senderPk, friendPk, startPosition, loadSize);
    }

    /**
     * 获取未入队列的消息
     * @return
     */
    @Override
    public List<ChatMsg> getUnsentMessages() {
        return db.chatDao().getUnsentMessages();
    }

    /**
     * 添加消息日志
     * @param msgLog
     */
    @Override
    public long addChatMsgLog(ChatMsgLog msgLog) {
        return db.chatDao().addChatMsgLog(msgLog);
    }

    /**
     * 观察消息日志
     * @param hash
     * @return
     */
    @Override
    public Observable<List<ChatMsgLog>> observerMsgLogs(String hash) {
        return db.chatDao().observerMsgLogs(hash);
    }

    /**
     * 查询聊天消息日志
     * @param hash
     * @param status
     * @return
     */
    @Override
    public ChatMsgLog queryChatMsgLog(String hash, int status) {
        return db.chatDao().queryChatMsgLog(hash, status);
    }
}
