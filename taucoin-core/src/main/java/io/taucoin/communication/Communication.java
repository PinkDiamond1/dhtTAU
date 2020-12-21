package io.taucoin.communication;

import com.frostwire.jlibtorrent.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import io.taucoin.account.AccountManager;
import io.taucoin.core.DataIdentifier;
import io.taucoin.core.DataType;
import io.taucoin.core.FriendPair;
import io.taucoin.db.DBException;
import io.taucoin.db.MessageDB;
import io.taucoin.dht.DHT;
import io.taucoin.dht.DHTEngine;
import io.taucoin.listener.MsgListener;
import io.taucoin.param.ChainParam;
import io.taucoin.types.GossipItem;
import io.taucoin.types.GossipList;
import io.taucoin.types.GossipStatus;
import io.taucoin.types.Message;
import io.taucoin.util.ByteArrayWrapper;
import io.taucoin.util.ByteUtil;
import io.taucoin.util.HashUtil;

public class Communication implements DHT.GetDHTItemCallback {
    private static final Logger logger = LoggerFactory.getLogger("Communication");

    // 对UI使用的, Queue capability.
    public static final int QueueCapability = 1000;

    // 判断中间层队列的门槛值，中间层队列使用率超过0.8，则增加主循环时间间隔
    private final double THRESHOLD = 0.8;

    private final int SHORT_ADDRESS_LENGTH = 4;

    // 主循环间隔最小时间
    private final int MIN_LOOP_INTERVAL_TIME = 50; // 50 ms

    // 主循环间隔时间
    private int loopIntervalTime = MIN_LOOP_INTERVAL_TIME;

    // 发布gossip信息的时间间隔，默认10s
    private long gossipPublishIntervalTime = 10; // 10 s

    // 记录上一次发布gossip的时间
    private long lastGossipPublishTime = 0;

    private final MsgListener msgListener;

    // message db
    private final MessageDB messageDB;

    // UI相关请求存放的queue，统一收发所有的请求，包括UI以及内部算法产生的请求，LinkedHashSet确保队列的顺序性与唯一性
    private final LinkedHashSet<Object> queue = new LinkedHashSet<>();

    // 发现的gossip item集合，synchronizedSet是支持并发操作的集合
    private final Set<GossipItem> gossipItems = new CopyOnWriteArraySet<>();

    // 得到的消息集合（hash <--> Message），ConcurrentHashMap是支持并发操作的集合
    private final Map<ByteArrayWrapper, Message> messageMap = new ConcurrentHashMap<>();

    // 得到的消息与发送者对照表（Message hash <--> Sender）
    private final Map<ByteArrayWrapper, ByteArrayWrapper> messageSenderMap = new ConcurrentHashMap<>();

    // message content set
    private final Set<ByteArrayWrapper> messageContentSet = new CopyOnWriteArraySet<>();

    // 请求到的demand item
    private final Set<ByteArrayWrapper> demandItem = new CopyOnWriteArraySet<>();

    // 当前我加的朋友集合
    private final Set<ByteArrayWrapper> friends = new CopyOnWriteArraySet<>();

    // 当前发现的等待通知的在线的朋友集合
    private final Set<ByteArrayWrapper> onlineFriendsToNotify = new CopyOnWriteArraySet<>();

    // 我的朋友的最新消息的时间戳 <friend, timestamp>
    private final Map<ByteArrayWrapper, BigInteger> friendTimeStamp = new ConcurrentHashMap<>();

    // 我的朋友的最新消息的root <friend, root>
    private final Map<ByteArrayWrapper, byte[]> friendRoot = new ConcurrentHashMap<>();

    // 我的朋友的最新确认的root <friend, root>
    private final Map<ByteArrayWrapper, byte[]> friendConfirmationRoot = new ConcurrentHashMap<>();

    // 新发现的，等待通知UI的，我的朋友的最新确认的root <friend, root>
    private final Map<ByteArrayWrapper, byte[]> friendConfirmationRootToNotify = new ConcurrentHashMap<>();

    // 给我的朋友的最新消息的时间戳 <friend, timestamp>，发现对方新的确认信息root也会更新该时间
    private final Map<ByteArrayWrapper, BigInteger> timeStampToFriend = new ConcurrentHashMap<>();

    // 给我的朋友的最新消息的root <friend, root>
    private final Map<ByteArrayWrapper, byte[]> rootToFriend = new ConcurrentHashMap<>();

    // 当前我正在聊天的朋友集合，即用即弃
    private final Set<ByteArrayWrapper> writingFriends = new CopyOnWriteArraySet<>();

    // 当前发现的处于写状态的朋友集合，即用即弃，<friend, timestamp>
    private final Map<ByteArrayWrapper, Long> writingFriendsToVisit = new ConcurrentHashMap<>();

    // 发给朋友的confirmation root hash <friend, root>
//    private final Map<ByteArrayWrapper, byte[]> confirmationRootToFriend = new ConcurrentHashMap<>();

    // 我的需求 <friend, demand hash>，用于通知我的朋友我的需求
    private final Map<ByteArrayWrapper, byte[]> demandHash = new ConcurrentHashMap<>();

    // 我收到的需求hash
    private final Set<ByteArrayWrapper> friendDemandHash = new CopyOnWriteArraySet<>();

    // 通过gossip机制发现的跟我有新消息的朋友集合
    private final Set<ByteArrayWrapper> activeFriends = new CopyOnWriteArraySet<>();

    // 通过gossip机制发现的给朋友的最新时间 <FriendPair, timestamp>
    private final Map<FriendPair, BigInteger> gossipTimeStamp = Collections.synchronizedMap(new HashMap<>());

    // 通过gossip机制发现的给朋友的root <FriendPair, root>
    private final Map<FriendPair, byte[]> gossipRoot = Collections.synchronizedMap(new HashMap<>());

    // 通过gossip机制发现的给朋友的confirmation root <FriendPair, root>
    private final Map<FriendPair, byte[]> gossipConfirmationRoot = Collections.synchronizedMap(new HashMap<>());

    // 通过gossip机制发现的给朋友的demand immutable data hash <FriendPair, demand hash>
    private final Map<FriendPair, byte[]> gossipDemandImmutableDataHash = Collections.synchronizedMap(new HashMap<>());

    // 通过gossip机制发现的给朋友的gossip status <FriendPair, gossip status>
    private final Map<FriendPair, GossipStatus> gossipStatus = Collections.synchronizedMap(new HashMap<>());

    // Communication thread.
    private Thread communicationThread;

    public Communication(MessageDB messageDB, MsgListener msgListener) {
        this.messageDB = messageDB;
        this.msgListener = msgListener;
    }

    /**
     * 初始化，获取我以及朋友的最新root
     * @return true if success, false otherwise
     */
    private boolean init() {
        try {
            // get friend latest msg root
            Set<byte[]> friends = this.messageDB.getFriends();

            if (null != friends) {
                for (byte[] friend: friends) {
                    ByteArrayWrapper key = new ByteArrayWrapper(friend);
                    this.friends.add(key);

                    logger.debug("My friend:{}", key.toString());

                    // 获取朋友给我消息的最新root
                    byte[] root = this.messageDB.getFriendMessageRoot(friend);
                    if (null != root) {
                        this.friendRoot.put(key, root);
                    }

                    // 获取我发给朋友消息的最新root
                    root = this.messageDB.getMessageToFriendRoot(friend);
                    if (null != root) {
                        this.rootToFriend.put(key, root);
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    /**
     * 尝试发布gossip信息，看看时间间隔是否已到，每10s一次
     */
    private void tryToPublishGossipInfo() {
        long currentTime = System.currentTimeMillis() / 1000;

        if (currentTime - lastGossipPublishTime > gossipPublishIntervalTime) {
            publishGossipInfo();
        }
    }

    /**
     * 使用短地址来构建gossip item
     * @param sender sender public key
     * @param receiver receiver public key
     * @param timestamp timestamp
     * @param root msg root
     * @param confirmationRoot confirmation root
     * @param demandHash demand hash
     * @param gossipStatus gossip status
     * @return gossip item
     */
    private GossipItem makeGossipItemWithShortAddress(byte[] sender, byte[] receiver,
                                                      BigInteger timestamp, byte[] root,
                                                      byte[] confirmationRoot, byte[] demandHash,
                                                      GossipStatus gossipStatus) {
        byte[] shortSender = new byte[SHORT_ADDRESS_LENGTH];
        System.arraycopy(sender, 0, shortSender, 0, SHORT_ADDRESS_LENGTH);

        byte[] shortReceiver = new byte[SHORT_ADDRESS_LENGTH];
        System.arraycopy(receiver, 0, shortReceiver, 0, SHORT_ADDRESS_LENGTH);

        return new GossipItem(shortSender, shortReceiver, timestamp, root, confirmationRoot, demandHash, gossipStatus);
    }

    /**
     * 获取下一个gossip临时集合，从缓存（各个消息渠道的队列）获取gossip所需的各项数据
     * @return gossip set
     */
    private LinkedHashSet<GossipItem> getGossipList() {
        LinkedHashSet<GossipItem> gossipList = new LinkedHashSet<>();

        byte[] pubKey = AccountManager.getInstance().getKeyPair().first;

        // 1.统计我发给我朋友的gossip
        long currentTime = System.currentTimeMillis() / 1000;
        for (ByteArrayWrapper friend: this.friends) {
            BigInteger timeStamp = this.timeStampToFriend.get(friend);
            byte[] root = this.rootToFriend.get(friend);
//            byte[] confirmationRoot = this.confirmationRootToFriend.get(friend);
            byte[] confirmationRoot = this.friendRoot.get(friend);
            byte[] demandHash = this.demandHash.get(friend);

            // 如果有demand需求，时间戳用最新时间，以便被捕获
            if (null != demandHash || null == timeStamp) {
                timeStamp = BigInteger.valueOf(currentTime);
            }

            if (this.writingFriends.contains(friend)) {
                GossipItem gossipItem = makeGossipItemWithShortAddress(pubKey, friend.getData(),
                        timeStamp, root, confirmationRoot, demandHash, GossipStatus.ON_WRITING);
                gossipList.add(gossipItem);

                this.writingFriends.remove(friend);
            } else {
                GossipItem gossipItem = makeGossipItemWithShortAddress(pubKey, friend.getData(),
                        timeStamp, root, confirmationRoot, demandHash, GossipStatus.UNKNOWN);
                gossipList.add(gossipItem);
            }
        }

        // 2.统计其他人发给我朋友的消息
        for (Map.Entry<FriendPair, BigInteger> entry : this.gossipTimeStamp.entrySet()) {
            if (this.friends.contains(new ByteArrayWrapper(entry.getKey().getReceiver()))) {
                // 只添加一天以内有新消息的
                if (currentTime - entry.getValue().longValue() < ChainParam.ONE_DAY) {
                    byte[] root = this.gossipRoot.get(entry.getKey());
                    byte[] confirmationRoot = this.gossipConfirmationRoot.get(entry.getKey());
                    byte[] demandHash = this.gossipDemandImmutableDataHash.get(entry.getKey());
                    GossipStatus gossipStatus = this.gossipStatus.get(entry.getKey());
                    if (null != root) {
                        GossipItem gossipItem = makeGossipItemWithShortAddress(entry.getKey().getSender(),
                                entry.getKey().getReceiver(), entry.getValue(), root, confirmationRoot, demandHash, gossipStatus);
                        gossipList.add(gossipItem);
                    }
                }
            }
        }


        return gossipList;
    }

    /**
     * 获取gossip集合
     * @return gossip set
     */
    private Set<GossipItem> getGossipSet() {
        Set<GossipItem> gossipSet = new HashSet<>();

        byte[] pubKey = AccountManager.getInstance().getKeyPair().first;

        // 统计我发给我朋友的gossip
        long currentTime = System.currentTimeMillis() / 1000;
        for (ByteArrayWrapper friend: this.friends) {
            BigInteger timeStamp = this.timeStampToFriend.get(friend);
            byte[] root = this.rootToFriend.get(friend);
//            byte[] confirmationRoot = this.confirmationRootToFriend.get(friend);
            byte[] confirmationRoot = this.friendRoot.get(friend);
            byte[] demandHash = this.demandHash.get(friend);

            // 如果有demand需求，时间戳用最新时间，以便被捕获
            if (null != demandHash) {
                timeStamp = BigInteger.valueOf(currentTime);
            }

            if (null != timeStamp && null != root) {

                if (this.writingFriends.contains(friend)) {
                    GossipItem gossipItem = makeGossipItemWithShortAddress(pubKey, friend.getData(),
                            timeStamp, root, confirmationRoot, demandHash, GossipStatus.ON_WRITING);
                    gossipSet.add(gossipItem);

                    this.writingFriends.remove(friend);
                } else {
                    GossipItem gossipItem = makeGossipItemWithShortAddress(pubKey, friend.getData(),
                            timeStamp, root, confirmationRoot, demandHash, GossipStatus.UNKNOWN);
                    gossipSet.add(gossipItem);
                }
            }
        }

        // 统计其他人发给我朋友的消息
        for (Map.Entry<FriendPair, BigInteger> entry : this.gossipTimeStamp.entrySet()) {
            if (this.friends.contains(new ByteArrayWrapper(entry.getKey().getReceiver()))) {
                // 只添加一天以内有新消息的
                if (currentTime - entry.getValue().longValue() < ChainParam.ONE_DAY) {
                    byte[] root = this.gossipRoot.get(entry.getKey());
                    byte[] confirmationRoot = this.gossipConfirmationRoot.get(entry.getKey());
                    byte[] demandHash = this.gossipDemandImmutableDataHash.get(entry.getKey());
                    GossipStatus gossipStatus = this.gossipStatus.get(entry.getKey());
                    if (null != root) {
                        GossipItem gossipItem = makeGossipItemWithShortAddress(entry.getKey().getSender(),
                                entry.getKey().getReceiver(), entry.getValue(), root, confirmationRoot, demandHash, gossipStatus);
                        gossipSet.add(gossipItem);
                    }
                }
            }
        }


        return gossipSet;
    }

    /**
     * 尝试对gossip信息瘦身，通过移除一天前的旧数据的方法
     */
    private void tryToSlimDownGossip() {
        long currentTime = System.currentTimeMillis() / 1000;
        Iterator<Map.Entry<FriendPair, BigInteger>> it = this.gossipTimeStamp.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<FriendPair, BigInteger> entry = it.next();
            if (currentTime - entry.getValue().longValue() > ChainParam.ONE_DAY) {
                this.gossipRoot.remove(entry.getKey());
                this.gossipConfirmationRoot.remove(entry.getKey());
                this.gossipDemandImmutableDataHash.remove(entry.getKey());
                this.gossipStatus.remove(entry.getKey());

                this.gossipTimeStamp.remove(entry.getKey());
//                it.remove();
            }
        }
    }

    /**
     * 处理朋友相关的gossip item
     */
    private void dealWithGossipItem() {
        Iterator<GossipItem> it = this.gossipItems.iterator();

        byte[] pubKey = AccountManager.getInstance().getKeyPair().first;

        // 顶层gossip是按照朋友关系进行平等遍历，未来可能根据频率心跳信号的存在来调整
        while (it.hasNext()) {
            GossipItem gossipItem = it.next();
            byte[] sender = gossipItem.getSender();
            byte[] receiver = gossipItem.getReceiver();

            // 发送者是我自己的gossip信息直接忽略，因为我自己的信息不需要依赖gossip
            if (!ByteUtil.startsWith(pubKey, sender)) {
                // 首先，判断一下是否有涉及自己的gossip，有的话则加入待访问的活跃朋友集合
                if (ByteUtil.startsWith(pubKey, receiver)) {
                    this.activeFriends.add(new ByteArrayWrapper(receiver));
                } else {
                    // 寻找跟我朋友相关的gossip
                    for (ByteArrayWrapper friend : this.friends) {
                        if (ByteUtil.startsWith(friend.getData(), receiver)) { // 找到
                            FriendPair pair = FriendPair.create(sender, receiver);

                            BigInteger oldTimeStamp = this.gossipTimeStamp.get(pair);
                            BigInteger timeStamp = gossipItem.getTimestamp();

                            // 判断是否是新数据，若是，则记录下来以待发布
                            if (null == oldTimeStamp || oldTimeStamp.compareTo(timeStamp) < 0) {
                                // 朋友的root帮助记录，由其自己去验证
                                this.gossipRoot.put(pair, gossipItem.getMessageRoot());
                                this.gossipConfirmationRoot.put(pair, gossipItem.getConfirmationRoot());
                                this.gossipDemandImmutableDataHash.put(pair, gossipItem.getDemandImmutableDataHash());
                                this.gossipStatus.put(pair, gossipItem.getGossipStatus());

                                // 更新时间戳
                                this.gossipTimeStamp.put(pair, timeStamp);
                            }

                            break;
                        }
                    }
                }
            }

            this.gossipItems.remove(gossipItem);
//            it.remove();
        }
    }

    /**
     * 挑选一个活跃的peer访问
     */
    private void visitActivePeer() {
        Iterator<ByteArrayWrapper> it = this.activeFriends.iterator();
        // 如果有现成的peer，则挑选一个peer访问
        if (it.hasNext()) {
            ByteArrayWrapper peer = it.next();
            requestGossipInfoFromPeer(peer);

            this.activeFriends.remove(peer);
//            it.remove();
        } else {
            // 没有找到活跃的peer，则自己随机访问一个自己的朋友
            Iterator<ByteArrayWrapper> iterator = this.friends.iterator();
            if (iterator.hasNext()) {

                Random random = new Random(System.currentTimeMillis());
                int index = random.nextInt(this.friends.size());

                ByteArrayWrapper peer = null;
                int i = 0;
                while (iterator.hasNext()) {
                    peer = iterator.next();
                    if (i == index) {
                        break;
                    }

                    i++;
                }

                if (null != peer) {
                    requestGossipInfoFromPeer(peer);
                }
            }
        }
    }

    /**
     * 通知UI发现的还在线的朋友
     */
    private void notifyUIOnlineFriend() {
        for (ByteArrayWrapper friend: this.onlineFriendsToNotify) {
            logger.debug("Notify UI online friend:{}", friend.toString());
            this.msgListener.onDiscoveryFriend(friend.getData());

            this.onlineFriendsToNotify.remove(friend);
        }
    }

    /**
     * 通知UI新发现的朋友的已读消息的root
     */
    private void notifyUIReadMessageRoot() {
        Iterator<Map.Entry<ByteArrayWrapper, byte[]>> iterator = this.friendConfirmationRootToNotify.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ByteArrayWrapper, byte[]> entry = iterator.next();

            if (null != entry.getValue()) {
                logger.debug("Notify UI read message from friend:{}, root:{}",
                        entry.getKey().toString(), Hex.toHexString(entry.getValue()));

                this.msgListener.onReadMessageRoot(entry.getKey().getData(), entry.getValue());
            }

            this.friendConfirmationRootToNotify.remove(entry.getKey());
        }
    }

    /**
     * 访问当前正在写状态的朋友节点，发现写状态在10s以内的继续访问，超过10s的从访问清单删除
     */
    private void visitWritingFriends() {
        long currentTime = System.currentTimeMillis() / 1000;
        for (Map.Entry<ByteArrayWrapper, Long> entry: this.writingFriendsToVisit.entrySet()) {
            if (currentTime - entry.getValue() > ChainParam.GOSSIP_CHANNEL_TIME) {
                this.writingFriendsToVisit.remove(entry.getKey());
            } else {
                requestLatestMessageFromPeer(entry.getKey());
            }
        }
    }

    /**
     * 处理收到的消息
     * @throws DBException database exception
     */
    private void dealWithMessage() throws DBException {

        // 将消息的内容直接存入数据库
        Iterator<ByteArrayWrapper> it = this.messageContentSet.iterator();
        while (it.hasNext()) {
            ByteArrayWrapper messageContent = it.next();
            this.messageDB.putMessage(HashUtil.bencodeHash(messageContent.getData()), messageContent.getData());
            this.messageContentSet.remove(messageContent);
//            it.remove();
        }

        // 将消息存入数据库并通知UI，尝试获取下一条消息
        Iterator<Map.Entry<ByteArrayWrapper, Message>> iterator = this.messageMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<ByteArrayWrapper, Message> entry = iterator.next();
            Message message = entry.getValue();
            ByteArrayWrapper msgHash = entry.getKey();

            ByteArrayWrapper sender = this.messageSenderMap.get(msgHash);

            // save to db
            this.messageDB.putMessage(message.getHash(), message.getEncoded());

            logger.debug("Notify UI new message from friend:{}, hash:{}",
                    sender.toString(), Hex.toHexString(message.getHash()));

            // 通知UI新消息
            this.msgListener.onNewMessage(sender.getData(), message);

            // try to find next one
            byte[] hash = message.getPreviousMsgDAGRoot();
            if (null != hash) {
                // 先判断一下本地是否有，有的话则终止，没有则继续同步DAG，直至到连接到之前的数据
                if (null == this.messageDB.getMessageByHash(hash)) {
                    Message previousMsg = this.messageMap.get(new ByteArrayWrapper(hash));
                    if (null == previousMsg) {
                        // request next one
                        requestMessage(hash, sender);
                    }
                }
            }

            this.messageSenderMap.remove(msgHash);
            this.messageMap.remove(msgHash);
//            iterator.remove();
        }
    }

    /**
     * 回应远端的需求，一次回应连续10s的消息
     * @throws DBException database exception
     */
    private void responseRemoteDemand() throws DBException {
        Iterator<ByteArrayWrapper> it = this.friendDemandHash.iterator();
        while (it.hasNext()) {
            ByteArrayWrapper hash = it.next();
            logger.info("Response demand:{}", hash.toString());

            byte[] data = this.messageDB.getMessageByHash(hash.getData());
            if (null != data) {
                Message msg = new Message(data);
                BigInteger timestamp = msg.getTimestamp();
                publishImmutableData(data);

                while (null != msg.getPreviousMsgDAGRoot()) {
                    data = this.messageDB.getMessageByHash(msg.getPreviousMsgDAGRoot());
                    if (null != data) {
                        msg = new Message(data);
                        publishImmutableData(data);
                        if (timestamp.longValue() - msg.getTimestamp().longValue() > 10) {
                            break;
                        }
                    }
                }
            }

            this.friendDemandHash.remove(hash);
//            it.remove();
        }
    }

    /**
     * 处理请求回来的数据，将其存储到数据库，并且demand hash集合中清除掉
     * @throws DBException database exception
     */
    private void dealWithDemandItem() throws DBException {
        Iterator<ByteArrayWrapper> it = this.demandItem.iterator();
        while (it.hasNext()) {
            ByteArrayWrapper demandItem = it.next();
            byte[] hash = HashUtil.bencodeHash(demandItem.getData());
            logger.debug("Save demand item:{}", Hex.toHexString(demandItem.getData()));
            // 数据存入数据库
            this.messageDB.putMessage(hash, demandItem.getData());

            // 从demand集合移除
            Iterator<Map.Entry<ByteArrayWrapper, byte[]>> iterator = this.demandHash.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ByteArrayWrapper, byte[]> entry = iterator.next();
                byte[] value = entry.getValue();
                if (Arrays.equals(hash, value)) {
                    logger.debug("Remove hash[{}] from demand hash set", Hex.toHexString(hash));
                    this.demandHash.remove(entry.getKey());
//                    iterator.remove();
                }
            }

            this.demandItem.remove(demandItem);
//            it.remove();
        }
    }

    /**
     * 请求immutable data
     */
    private void requestDemandHash() throws DBException {
        for (Map.Entry<ByteArrayWrapper, byte[]> entry: this.demandHash.entrySet()) {
            logger.trace("Request demand hash:{}", Hex.toHexString(entry.getValue()));
            requestImmutableData(entry.getValue());
        }
    }

    /**
     * 死循环
     */
    private void mainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {

                notifyUIOnlineFriend();

                notifyUIReadMessageRoot();

                visitWritingFriends();

                dealWithMessage();

                dealWithGossipItem();

                dealWithDemandItem();

                responseRemoteDemand();

                tryToSlimDownGossip();

                tryToPublishGossipInfo();

                visitActivePeer();

                requestDemandHash();

                tryToSendAllRequest();

                adjustIntervalTime();

                try {
                    Thread.sleep(this.loopIntervalTime);
                } catch (InterruptedException e) {
                    logger.info(e.getMessage(), e);
                    Thread.currentThread().interrupt();
                }
            } catch (DBException e) {
                this.msgListener.onMsgError("Data Base Exception!");
                logger.error(e.getMessage(), e);

                try {
                    Thread.sleep(this.MIN_LOOP_INTERVAL_TIME);
                } catch (InterruptedException ex) {
                    logger.info(ex.getMessage(), ex);
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);

                try {
                    Thread.sleep(this.MIN_LOOP_INTERVAL_TIME);
                } catch (InterruptedException ex) {
                    logger.info(ex.getMessage(), ex);
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 向某个peer请求gossip数据，同时在邻近的未来/现在/将来三个时间片频道上获取
     * @param pubKey public key
     */
    private void requestGossipInfoFromPeer(ByteArrayWrapper pubKey) {
        if (null != pubKey) {
            logger.trace("Request gossip info from peer:{}", pubKey.toString());

            // 在当前时间对应的频道上获取数据
            byte[] salt = makeGossipSalt();
            DHT.GetMutableItemSpec spec = new DHT.GetMutableItemSpec(pubKey.getData(), salt);
            DataIdentifier dataIdentifier = new DataIdentifier(DataType.GOSSIP_FROM_PEER, pubKey);
            DHT.MutableItemRequest mutableItemRequest = new DHT.MutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(mutableItemRequest);

            // 在当前时间对应的下一个频道上获取数据
            salt = makeNextGossipSalt();
            spec = new DHT.GetMutableItemSpec(pubKey.getData(), salt);
            dataIdentifier = new DataIdentifier(DataType.GOSSIP_FROM_PEER, pubKey);
            mutableItemRequest = new DHT.MutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(mutableItemRequest);

            // 在当前时间对应的上一个频道上获取数据
            salt = makePreviousGossipSalt();
            spec = new DHT.GetMutableItemSpec(pubKey.getData(), salt);
            dataIdentifier = new DataIdentifier(DataType.GOSSIP_FROM_PEER, pubKey);
            mutableItemRequest = new DHT.MutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(mutableItemRequest);
        }
    }

    /**
     * 向某个peer直接请求最新的message数据
     * @param pubKey public key
     */
    private void requestLatestMessageFromPeer(ByteArrayWrapper pubKey) {
        if (null != pubKey) {
            logger.trace("Request latest message from peer:{}", pubKey.toString());

            byte[] salt = getReceivingSalt(pubKey.getData());
            DHT.GetMutableItemSpec spec = new DHT.GetMutableItemSpec(pubKey.getData(), salt);
            DataIdentifier dataIdentifier = new DataIdentifier(DataType.LATEST_MESSAGE, pubKey);

            DHT.MutableItemRequest mutableItemRequest = new DHT.MutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(mutableItemRequest);
        }
    }

    /**
     * 请求gossip list, pubKey用于辅助识别是否我的朋友
     * @param hash gossip list hash
     * @param pubKey public key
     */
    private void requestGossipList(byte[] hash, ByteArrayWrapper pubKey) {
        if (null != hash) {
            DHT.GetImmutableItemSpec spec = new DHT.GetImmutableItemSpec(hash);
            DataIdentifier dataIdentifier = new DataIdentifier(DataType.GOSSIP_LIST, pubKey, new ByteArrayWrapper(hash));

            DHT.ImmutableItemRequest immutableItemRequest = new DHT.ImmutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(immutableItemRequest);
        }
    }

    /**
     * 请求对应哈希值的消息
     * @param hash msg hash
     * @param pubKey public key
     */
    private void requestMessage(byte[] hash, ByteArrayWrapper pubKey) {
        if (null != hash) {
            logger.debug("Message root:{}, public key:{}", Hex.toHexString(hash), pubKey.toString());
            DHT.GetImmutableItemSpec spec = new DHT.GetImmutableItemSpec(hash);
            DataIdentifier dataIdentifier = new DataIdentifier(DataType.MESSAGE, pubKey, new ByteArrayWrapper(hash));

            DHT.ImmutableItemRequest immutableItemRequest = new DHT.ImmutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(immutableItemRequest);
        }
    }

    /**
     * 请求消息携带的内容
     * @param hash 内容哈希
     */
    private void requestMessageContent(byte[] hash, ByteArrayWrapper pubKey) throws DBException {
        if (null != hash && null == this.messageDB.getMessageByHash(hash)) {
            DHT.GetImmutableItemSpec spec = new DHT.GetImmutableItemSpec(hash);
            DataIdentifier dataIdentifier = new DataIdentifier(DataType.MESSAGE_CONTENT, pubKey, new ByteArrayWrapper(hash));

            DHT.ImmutableItemRequest immutableItemRequest = new DHT.ImmutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(immutableItemRequest);
        }
    }

    /**
     * 请求immutable数据
     * @param hash 数据哈希
     */
    public void requestImmutableData(byte[] hash) throws DBException {
        if (null != hash && null == this.messageDB.getMessageByHash(hash)) {
            DHT.GetImmutableItemSpec spec = new DHT.GetImmutableItemSpec(hash);
            DataIdentifier dataIdentifier = new DataIdentifier(DataType.IMMUTABLE_DATA);

            DHT.ImmutableItemRequest immutableItemRequest = new DHT.ImmutableItemRequest(spec, this, dataIdentifier);
            this.queue.add(immutableItemRequest);
        }
    }

    /**
     * 发布消息
     * @param message msg to publish
     */
    private void publishMessage(Message message) {
        if (null != message) {
            DHT.ImmutableItem immutableItem = new DHT.ImmutableItem(message.getEncoded());

            DHT.ImmutableItemDistribution immutableItemDistribution = new DHT.ImmutableItemDistribution(immutableItem, null, null);
            this.queue.add(immutableItemDistribution);
        }
    }

    /**
     * 发布immutable data
     * @param list immutable data list
     */
    public void publishImmutableData(List<byte[]> list) {
        if (null != list) {
            for (byte[] data: list) {
                publishImmutableData(data);
            }
        }
    }

    /**
     * 发布immutable data
     * @param data immutable data
     */
    public void publishImmutableData(byte[] data) {
        if (null != data) {
            DHT.ImmutableItem immutableItem = new DHT.ImmutableItem(data);

            DHT.ImmutableItemDistribution immutableItemDistribution = new DHT.ImmutableItemDistribution(immutableItem, null, null);
            this.queue.add(immutableItemDistribution);
        }
    }

    /**
     * 发布gossip数据
     * @param gossipList gossip list
     */
    private void publishGossipList(GossipList gossipList) {
        if (null != gossipList) {
            DHT.ImmutableItem immutableItem = new DHT.ImmutableItem(gossipList.getEncoded());

            DHT.ImmutableItemDistribution immutableItemDistribution = new DHT.ImmutableItemDistribution(immutableItem, null, null);
            this.queue.add(immutableItemDistribution);
        }
    }

    /**
     * 构造GOSSIP频道对应的salt
     * @return salt
     */
    public static byte[] makeGossipSalt() {
        // 可能是个叠加频率，发送方频率是双频率发送
        // 单一salt，两个频率写的频率可以提高，接收方可以根据自己的工作状况决定频率
        // 目前暂定固定时间片10s
        long time = System.currentTimeMillis() / 1000 / ChainParam.GOSSIP_CHANNEL_TIME;
        byte[] timeBytes = ByteUtil.longToBytes(time);

        byte[] salt = new byte[ChainParam.GOSSIP_CHANNEL.length + timeBytes.length];
        System.arraycopy(ChainParam.GOSSIP_CHANNEL, 0, salt, 0, ChainParam.GOSSIP_CHANNEL.length);
        System.arraycopy(ChainParam.GOSSIP_CHANNEL, 0, salt, ChainParam.GOSSIP_CHANNEL.length, timeBytes.length);
        return salt;
    }

    /**
     * 构造GOSSIP频道对应的上一个salt
     * @return salt
     */
    private static byte[] makePreviousGossipSalt() {
        long time = System.currentTimeMillis() / 1000 / ChainParam.GOSSIP_CHANNEL_TIME - 1;
        byte[] timeBytes = ByteUtil.longToBytes(time);

        byte[] salt = new byte[ChainParam.GOSSIP_CHANNEL.length + timeBytes.length];
        System.arraycopy(ChainParam.GOSSIP_CHANNEL, 0, salt, 0, ChainParam.GOSSIP_CHANNEL.length);
        System.arraycopy(ChainParam.GOSSIP_CHANNEL, 0, salt, ChainParam.GOSSIP_CHANNEL.length, timeBytes.length);
        return salt;
    }

    /**
     * 构造GOSSIP频道对应的下一个salt
     * @return salt
     */
    private static byte[] makeNextGossipSalt() {
        long time = System.currentTimeMillis() / 1000 / ChainParam.GOSSIP_CHANNEL_TIME + 1;
        byte[] timeBytes = ByteUtil.longToBytes(time);

        byte[] salt = new byte[ChainParam.GOSSIP_CHANNEL.length + timeBytes.length];
        System.arraycopy(ChainParam.GOSSIP_CHANNEL, 0, salt, 0, ChainParam.GOSSIP_CHANNEL.length);
        System.arraycopy(ChainParam.GOSSIP_CHANNEL, 0, salt, ChainParam.GOSSIP_CHANNEL.length, timeBytes.length);
        return salt;
    }

    /**
     * 在gossip频道发布gossip信息
     * @param gossipList last gossip list
     */
    private void publishMutableGossip(GossipList gossipList) {
        // put mutable item
        Pair<byte[], byte[]> keyPair = AccountManager.getInstance().getKeyPair();

        byte[] salt = makeGossipSalt();
        byte[] encode = gossipList.getEncoded();
        if (null != encode) {
            DHT.MutableItem mutableItem = new DHT.MutableItem(keyPair.first,
                    keyPair.second, encode, salt);
            DHT.MutableItemDistribution mutableItemDistribution = new DHT.MutableItemDistribution(mutableItem, null, null);

            this.queue.add(mutableItemDistribution);
        }
    }

    /**
     * 在前一个gossip频道发布gossip信息
     * @param gossipList last gossip list
     */
    private void publishMutableGossipOnPreviousChannel(GossipList gossipList) {
        // put mutable item
        Pair<byte[], byte[]> keyPair = AccountManager.getInstance().getKeyPair();

        byte[] salt = makePreviousGossipSalt();
        byte[] encode = gossipList.getEncoded();
        if (null != encode) {
            DHT.MutableItem mutableItem = new DHT.MutableItem(keyPair.first,
                    keyPair.second, encode, salt);
            DHT.MutableItemDistribution mutableItemDistribution = new DHT.MutableItemDistribution(mutableItem, null, null);

            this.queue.add(mutableItemDistribution);
        }
    }

    /**
     * 在下一个gossip频道发布gossip信息
     * @param gossipList last gossip list
     */
    private void publishMutableGossipOnNextChannel(GossipList gossipList) {
        // put mutable item
        Pair<byte[], byte[]> keyPair = AccountManager.getInstance().getKeyPair();

        byte[] salt = makeNextGossipSalt();
        byte[] encode = gossipList.getEncoded();
        if (null != encode) {
            DHT.MutableItem mutableItem = new DHT.MutableItem(keyPair.first,
                    keyPair.second, encode, salt);
            DHT.MutableItemDistribution mutableItemDistribution = new DHT.MutableItemDistribution(mutableItem, null, null);

            this.queue.add(mutableItemDistribution);
        }
    }

    /**
     * 发布所有gossip信息
     */
    private void publishGossipInfo() {
        // put mutable item
        LinkedHashSet<GossipItem> gossipItemSet = getGossipList();

        logger.debug("----------------------start----------------------");
        for(GossipItem gossipItem: gossipItemSet) {
            logger.debug("Gossip set:{}", gossipItem.toString());
        }
        logger.debug("----------------------end----------------------");

        // 1.先发布gossip到当前的频道
        List<GossipItem> list = new ArrayList<>();

        Iterator<GossipItem> iterator = gossipItemSet.iterator();
        int i = 0;
        while (iterator.hasNext() && i <= ChainParam.GOSSIP_LIMIT_SIZE) {
            GossipItem gossipItem = iterator.next();
            list.add(gossipItem);
            i++;
        }

        if (!list.isEmpty()) {
            GossipList gossipList = new GossipList(list);
            while (gossipList.getEncoded().length >= ChainParam.DHT_ITEM_LIMIT_SIZE) {
                list.remove(list.size() - 1);
                gossipList = new GossipList(list);
            }

            logger.debug(gossipList.toString());
            publishMutableGossip(gossipList);
        }

        // 2.发布gossip到下一个频道（未来频道）
        for (GossipItem gossipItem: list) {
            gossipItemSet.remove(gossipItem);
        }

        list = new ArrayList<>();

        iterator = gossipItemSet.iterator();
        i = 0;
        while (iterator.hasNext() && i <= ChainParam.GOSSIP_LIMIT_SIZE) {
            GossipItem gossipItem = iterator.next();
            list.add(gossipItem);
            i++;
        }

        if (!list.isEmpty()) {
            GossipList gossipList = new GossipList(list);
            while (gossipList.getEncoded().length >= ChainParam.DHT_ITEM_LIMIT_SIZE) {
                list.remove(list.size() - 1);
                gossipList = new GossipList(list);
            }

            logger.debug(gossipList.toString());
            publishMutableGossipOnNextChannel(gossipList);
        }

        // 3.发布gossip到上一个频道（历史频道）
        for (GossipItem gossipItem: list) {
            gossipItemSet.remove(gossipItem);
        }

        list = new ArrayList<>();

        iterator = gossipItemSet.iterator();
        i = 0;
        while (iterator.hasNext() && i <= ChainParam.GOSSIP_LIMIT_SIZE) {
            GossipItem gossipItem = iterator.next();
            list.add(gossipItem);
            i++;
        }

        if (!list.isEmpty()) {
            GossipList gossipList = new GossipList(list);
            while (gossipList.getEncoded().length >= ChainParam.DHT_ITEM_LIMIT_SIZE) {
                list.remove(list.size() - 1);
                gossipList = new GossipList(list);
            }

            logger.debug(gossipList.toString());
            publishMutableGossipOnPreviousChannel(gossipList);
        }

        // 记录发布时间
        this.lastGossipPublishTime = System.currentTimeMillis() / 1000;
    }

    /**
     * 处理分发各种请求
     * @param req request
     */
    private void process(Object req) {

        if (req == null) {
            return;
        }

        // dispatch dht request
        if (req instanceof DHT.ImmutableItemRequest) {
            requestImmutableItem((DHT.ImmutableItemRequest)req);
        } else if (req instanceof DHT.MutableItemRequest) {
            requestMutableItem((DHT.MutableItemRequest)req);
        } else if (req instanceof DHT.ImmutableItemDistribution) {
            putImmutableItem((DHT.ImmutableItemDistribution)req);
        } else if (req instanceof DHT.MutableItemDistribution) {
            putMutableItem((DHT.MutableItemDistribution)req);
        }
    }

    private void requestImmutableItem(DHT.ImmutableItemRequest req) {
        logger.trace("requestImmutableItem:{}", req.getSpec().toString());
        DHTEngine.getInstance().request(req.getSpec(), req.getCallback(), req.getCallbackData());
    }

    private void requestMutableItem(DHT.MutableItemRequest req) {
        logger.trace("requestMutableItem:{}", req.getSpec().toString());
        DHTEngine.getInstance().request(req.getSpec(), req.getCallback(), req.getCallbackData());
    }

    private void putImmutableItem(DHT.ImmutableItemDistribution d) {
        logger.trace("putImmutableItem:{}", d.getItem().toString());
        DHTEngine.getInstance().distribute(d.getItem(), d.getCallback(), d.getCallbackData());
    }

    private void putMutableItem(DHT.MutableItemDistribution d) {
        logger.trace("putMutableItem:{}", d.getItem().toString());
        DHTEngine.getInstance().distribute(d.getItem(), d.getCallback(), d.getCallbackData());
    }

    /**
     * 将所有的请求一次发给中间层
     */
    private void tryToSendAllRequest() {
        int size = DHTEngine.getInstance().queueOccupation();
        // 0.2 * 10000是中间层剩余空间，大于本地队列最大长度1000，目前肯定能放下
        if ((double)size / DHTEngine.DHTQueueCapability < THRESHOLD) {
            Iterator<Object> it = this.queue.iterator();
            while (it.hasNext()) {
                Object request = it.next();

                if (null != request) {
                    process(request);
                }

                it.remove();
            }
        }
    }

    /**
     * 更新发给朋友的消息的时间和root等信息
     * @param friend friend to send msg
     * @param message msg
     */
    private void updateMessageInfoToFriend(byte[] friend, Message message) throws DBException {
        ByteArrayWrapper key = new ByteArrayWrapper(friend);
        logger.info("Update friend [{}] info.", key.toString());
        this.timeStampToFriend.put(key, message.getTimestamp());
        this.rootToFriend.put(key, message.getHash());
//        this.confirmationRootToFriend.put(key, message.getFriendLatestMessageRoot());

        this.messageDB.setMessageToFriendRoot(friend, message.getHash());
    }

    /**
     * save message data in database
     * @param message msg
     * @param list msg data
     * @throws DBException database exception
     */
    private void saveMessageDataInDB(Message message, List<byte[]> list) throws DBException {
        if (null != message) {
            this.messageDB.putMessage(message.getHash(), message.getEncoded());
        }

        if (null != list) {
            for (byte[] date: list) {
                this.messageDB.putMessage(HashUtil.bencodeHash(date), date);
            }
        }
    }

    /**
     * 向朋友发布新消息
     * @param friend 朋友公钥
     * @param message 新消息
     * @param data 新消息的其它相关数据，比如可能有多级文字或者图片结构，这些数据会一起发布到dht
     * @return true:接受该消息， false:拒绝该消息
     */
    public boolean publishNewMessage(byte[] friend, Message message, List<byte[]> data) {
        if (this.queue.size() <= QueueCapability) {
            try {
                if (null != message) {
                    logger.debug("Publish message:{}", message.toString());

                    saveMessageDataInDB(message, data);
                    updateMessageInfoToFriend(friend, message);
                    publishMessage(message);
                    publishImmutableData(data);
                    publishGossipInfo();
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            return true;
        } else {
            return false;
        }
    }

    /**
     * 获取聊天发送频道salt
     * @param friend 对方public key
     * @return salt
     */
    public byte[] getSendingSalt(byte[] friend) {
        byte[] pubKey = AccountManager.getInstance().getKeyPair().first;

        long time = System.currentTimeMillis() / 1000 / ChainParam.COMMUNICATION_CHANNEL_TIME;
        byte[] timeBytes = ByteUtil.longToBytes(time);

        byte[] salt = new byte[SHORT_ADDRESS_LENGTH * 2 + timeBytes.length];
        System.arraycopy(pubKey, 0, salt, 0, SHORT_ADDRESS_LENGTH);
        System.arraycopy(friend, 0, salt, SHORT_ADDRESS_LENGTH, SHORT_ADDRESS_LENGTH);
        System.arraycopy(timeBytes, 0, salt, SHORT_ADDRESS_LENGTH * 2, timeBytes.length);

        return salt;
    }

    /**
     * 获取聊天接收频道salt
     * @param friend 对方public key
     * @return salt
     */
    public byte[] getReceivingSalt(byte[] friend) {
        byte[] pubKey = AccountManager.getInstance().getKeyPair().first;

        long time = System.currentTimeMillis() / 1000 / ChainParam.COMMUNICATION_CHANNEL_TIME;
        byte[] timeBytes = ByteUtil.longToBytes(time);

        byte[] salt = new byte[SHORT_ADDRESS_LENGTH * 2 + timeBytes.length];
        System.arraycopy(friend, 0, salt, 0, SHORT_ADDRESS_LENGTH);
        System.arraycopy(pubKey, 0, salt, SHORT_ADDRESS_LENGTH, SHORT_ADDRESS_LENGTH);
        System.arraycopy(timeBytes, 0, salt, SHORT_ADDRESS_LENGTH * 2, timeBytes.length);

        return salt;
    }

    /**
     * 发布上次发送的消息到专用聊天频道
     * @param friend 当前聊天的朋友
     */
    public void publishLastMessage(byte[] friend) throws DBException {
        // put mutable item
        Pair<byte[], byte[]> keyPair = AccountManager.getInstance().getKeyPair();
        byte[] root = this.rootToFriend.get(new ByteArrayWrapper(friend));
        byte[] salt = getSendingSalt(friend);

        if (null != root) {
            byte[] encode = this.messageDB.getMessageByHash(root);
            if (null != encode) {
                DHT.MutableItem mutableItem = new DHT.MutableItem(keyPair.first,
                        keyPair.second, encode, salt);
                DHT.MutableItemDistribution mutableItemDistribution = new DHT.MutableItemDistribution(mutableItem, null, null);

                this.queue.add(mutableItemDistribution);
            }

            this.writingFriends.add(new ByteArrayWrapper(friend));
        }
    }

    /**
     * 调整间隔时间
     */
    private void adjustIntervalTime() {
        int size = DHTEngine.getInstance().queueOccupation();
        if ((double)size / DHTEngine.DHTQueueCapability > THRESHOLD) {
            increaseIntervalTime();
        } else {
            decreaseIntervalTime();
        }
    }

    /**
     * 增加间隔时间
     */
    private void increaseIntervalTime() {
        this.loopIntervalTime = this.loopIntervalTime * 2;
    }

    /**
     * 减少间隔时间
     */
    private void decreaseIntervalTime() {
        if (this.loopIntervalTime > this.MIN_LOOP_INTERVAL_TIME) {
            this.loopIntervalTime = this.loopIntervalTime / 2;
        }
    }


    /**
     * 添加新朋友
     * @param pubKey public key
     * @throws DBException database exception
     */
    public void addNewFriend(byte[] pubKey) throws DBException {
        byte[] myPubKey = AccountManager.getInstance().getKeyPair().first;

        // 朋友列表排除自己
        if (!Arrays.equals(myPubKey, pubKey)) {
            this.friends.add(new ByteArrayWrapper(pubKey));

            this.messageDB.addFriend(pubKey);
        }
    }

    /**
     * 删除朋友
     * @param pubKey public key
     * @throws DBException database exception
     */
    public void delFriend(byte[] pubKey) throws DBException {
        ByteArrayWrapper key = new ByteArrayWrapper(pubKey);
        this.friends.remove(key);
        this.friendTimeStamp.remove(key);
        this.friendRoot.remove(key);
        this.friendConfirmationRoot.remove(key);
        this.friendConfirmationRootToNotify.remove(key);
        this.timeStampToFriend.remove(key);
        this.rootToFriend.remove(key);
//        this.confirmationRootToFriend.remove(key);
        this.demandHash.remove(key);

        for (Map.Entry<FriendPair, BigInteger> entry: this.gossipTimeStamp.entrySet()) {
            if (Arrays.equals(pubKey, entry.getKey().getReceiver())) {
                this.gossipTimeStamp.remove(entry.getKey());
            }
        }

        for (Map.Entry<FriendPair, byte[]> entry: this.gossipRoot.entrySet()) {
            if (Arrays.equals(pubKey, entry.getKey().getReceiver())) {
                this.gossipRoot.remove(entry.getKey());
            }
        }

        for (Map.Entry<FriendPair, byte[]> entry: this.gossipConfirmationRoot.entrySet()) {
            if (Arrays.equals(pubKey, entry.getKey().getReceiver())) {
                this.gossipConfirmationRoot.remove(entry.getKey());
            }
        }

        for (Map.Entry<FriendPair, byte[]> entry: this.gossipDemandImmutableDataHash.entrySet()) {
            if (Arrays.equals(pubKey, entry.getKey().getReceiver())) {
                this.gossipDemandImmutableDataHash.remove(entry.getKey());
            }
        }

        for (Map.Entry<FriendPair, GossipStatus> entry: this.gossipStatus.entrySet()) {
            if (Arrays.equals(pubKey, entry.getKey().getReceiver())) {
                this.gossipStatus.remove(entry.getKey());
            }
        }

        this.messageDB.delFriend(pubKey);
    }

    /**
     * 获取所有的朋友
     * @return 所有朋友的公钥
     */
    public List<byte[]> getAllFriends() {
        List<byte[]> list = new ArrayList<>();
        for (ByteArrayWrapper friend: this.friends) {
            list.add(friend.getData());
        }

        return list;
    }

    /**
     * 获取队列容量
     * @return 容量
     */
    public int getQueueCapability() {
        return QueueCapability;
    }

    /**
     * 获取队列当前大小
     * @return 队列大小
     */
    public int getQueueSize() {
        return this.queue.size();
    }

    /**
     * get my latest msg root
     * @param pubKey public key
     * @return root
     */
    public byte[] getMyLatestMsgRoot(byte[] pubKey) {
        return this.rootToFriend.get(new ByteArrayWrapper(pubKey));
    }

    /**
     * 获取朋友最新的root
     * @param pubKey public key
     * @return root
     */
    public byte[] getFriendLatestRoot(byte[] pubKey) {
        return this.friendRoot.get(new ByteArrayWrapper(pubKey));
    }

    /**
     * 获取朋友最新的confirmation root
     * @param pubKey public key
     * @return confirmation root
     */
    public byte[] getFriendConfirmationRoot(byte[] pubKey) {
        return this.friendConfirmationRoot.get(new ByteArrayWrapper(pubKey));
    }

    /**
     * 设置gossip时间片
     * @param timeInterval 时间间隔，单位:s
     */
    public void setGossipTimeInterval(long timeInterval) {
        this.gossipPublishIntervalTime = timeInterval;
    }

    /**
     * Start thread
     *
     * @return boolean successful or not.
     */
    public boolean start() {

        if (!init()) {
            return false;
        }

        communicationThread = new Thread(this::mainLoop);
        communicationThread.start();

        return true;
    }

    /**
     * Stop thread
     */
    public void stop() {
        if (null != communicationThread) {
            communicationThread.interrupt();
        }
    }

    /**
     * 预处理受到的gossip list
     * @param gossipList 收到的gossip list
     * @param peer gossip发出的peer
     */
    private void preprocessGossipListFromNet(GossipList gossipList, ByteArrayWrapper peer) {
//        if (null != gossipList.getPreviousGossipListHash()) {
//            requestGossipList(gossipList.getPreviousGossipListHash(), peer);
//        }

        if (null != gossipList.getGossipList()) {

            // 信任发送方自己给的gossip信息
            byte[] pubKey = AccountManager.getInstance().getKeyPair().first;

            for (GossipItem gossipItem : gossipList.getGossipList()) {
                logger.trace("Got gossip: {} from peer[{}]", gossipItem.toString(), peer.toString());

                ByteArrayWrapper sender = new ByteArrayWrapper(gossipItem.getSender());

                // 发送者是我自己的gossip信息直接忽略，因为我自己的信息不需要依赖gossip
                if (ByteUtil.startsWith(pubKey, gossipItem.getSender())) {
                    logger.debug("Sender[{}] is me.", sender.toString());
                    continue;
                }

                // 如果是对方直接给我的gossip信息，也即gossip item的sender与请求gossip channel的peer一样，
                // 并且gossip item的receiver是我，那么会直接信任该gossip消息
                if (ByteUtil.startsWith(peer.getData(), gossipItem.getSender())
                        && ByteUtil.startsWith(pubKey, gossipItem.getReceiver())) {
                    logger.debug("Got trusted gossip:{} from online friend[{}]",
                            gossipItem.toString(), peer.toString());

                    // 发现的在线好友
                    this.onlineFriendsToNotify.add(peer);

                    BigInteger oldTimeStamp = this.friendTimeStamp.get(sender);
                    BigInteger timeStamp = gossipItem.getTimestamp();

                    if (null != oldTimeStamp) {
                        logger.debug("Old time stamp:{}", oldTimeStamp);
                    } else {
                        logger.debug("Old time stamp is null");
                    }

                    logger.debug("Gossip time stamp:{}", timeStamp);

                    // 如果是对方直接给我发的gossip消息，并且时间戳更新一些
                    if (null == oldTimeStamp || oldTimeStamp.compareTo(timeStamp) < 0) {
                        logger.debug("Got a new gossip item");

                        if (null != gossipItem.getDemandImmutableDataHash()) {
                            this.friendDemandHash.add(new ByteArrayWrapper(gossipItem.getDemandImmutableDataHash()));
                        }

                        if (GossipStatus.ON_WRITING == gossipItem.getGossipStatus()) {
                            this.writingFriendsToVisit.put(peer, System.currentTimeMillis() / 1000);
                        }

                        // 更新时间戳
                        this.friendTimeStamp.put(sender, timeStamp);

                        // 只要发现更新的gossip信息，则使用其confirmation root
                        if (null != gossipItem.getConfirmationRoot()) {
                            logger.debug("Got a friend[{}] confirmation root[{}]",
                                    peer.toString(), Hex.toHexString(gossipItem.getConfirmationRoot()));

                            byte[] confirmationRoot = this.friendConfirmationRoot.get(sender);
                            if (!Arrays.equals(confirmationRoot, gossipItem.getConfirmationRoot())) {
                                // 有信息要发，即确认信息要发，更新时间戳，确认收到该root
                                long currentTime = System.currentTimeMillis() / 1000;
                                this.timeStampToFriend.put(sender, BigInteger.valueOf(currentTime));
                                // 更新confirmation root
                                this.friendConfirmationRoot.put(sender, gossipItem.getConfirmationRoot());
                                // 加入待通知列表
                                this.friendConfirmationRootToNotify.put(sender, gossipItem.getConfirmationRoot());
                            }
                        }

                        // 发现更新时间戳的gossip消息，并且root与之前的不同，才更新并请求新数据
                        byte[] currentRoot = this.friendRoot.get(sender);
                        if (!Arrays.equals(currentRoot, gossipItem.getMessageRoot()) && null != gossipItem.getMessageRoot()) {
                            logger.debug("Got a new message root:{}", Hex.toHexString(gossipItem.getMessageRoot()));
                            // 如果该消息的root之前没拿到过，说明是新消息过来
                            // 更新朋友root信息
                            this.friendRoot.put(sender, gossipItem.getMessageRoot());
                            // 请求该root
                            requestMessage(gossipItem.getMessageRoot(), peer);
                        }
                    }

                    continue;
                }

                // 剩余的留给主循环处理
                this.gossipItems.add(gossipItem);
            }
        }
    }

    @Override
    public void onDHTItemGot(byte[] item, Object cbData) {
        DataIdentifier dataIdentifier = (DataIdentifier) cbData;
        switch (dataIdentifier.getDataType()) {
            case IMMUTABLE_DATA: {
                if (null == item) {
                    logger.debug("IMMUTABLE_DATA is empty");
                    return;
                }

                this.demandItem.add(new ByteArrayWrapper(item));

                break;
            }
            case MESSAGE: {
                if (null == item) {
                    logger.debug("MESSAGE[{}] from peer[{}] is empty",
                            dataIdentifier.getExtraInfo2().toString(), dataIdentifier.getExtraInfo1().toString());
                    this.demandHash.put(dataIdentifier.getExtraInfo1(), dataIdentifier.getExtraInfo2().getData());
                    return;
                }

                Message message = new Message(item);
                logger.debug("Got message hash:{}", Hex.toHexString(message.getHash()));
                this.messageMap.put(new ByteArrayWrapper(message.getHash()), message);
                this.messageSenderMap.put(new ByteArrayWrapper(message.getHash()), dataIdentifier.getExtraInfo1());

                // 暂时只考虑直接携带的文本
//                try {
//                    requestMessageContent(message.getContent(), dataIdentifier.getExtraInfo1());
//                } catch (Exception e) {
//                    logger.error(e.getMessage(), e);
//                }

                break;
            }
            case MESSAGE_CONTENT: {
                if (null == item) {
                    logger.debug("MESSAGE_CONTENT[{}] from peer[{}] is empty",
                            dataIdentifier.getExtraInfo2().toString(), dataIdentifier.getExtraInfo1().toString());
                    this.demandHash.put(dataIdentifier.getExtraInfo1(), dataIdentifier.getExtraInfo2().getData());
                    return;
                }

                this.messageContentSet.add(new ByteArrayWrapper(item));

                break;
            }
            case GOSSIP_FROM_PEER: {
                if (null == item) {
                    logger.debug("GOSSIP_FROM_PEER from peer[{}] is empty", dataIdentifier.getExtraInfo1().toString());
                    return;
                }

                GossipList gossipList = new GossipList(item);
                preprocessGossipListFromNet(gossipList, dataIdentifier.getExtraInfo1());

                break;
            }
            case GOSSIP_LIST: {
                if (null == item) {
                    logger.debug("GOSSIP_LIST[{}] from peer[{}] is empty",
                            dataIdentifier.getExtraInfo2().toString(), dataIdentifier.getExtraInfo1().toString());
                    this.demandHash.put(dataIdentifier.getExtraInfo1(), dataIdentifier.getExtraInfo2().getData());
                    return;
                }

                GossipList gossipList = new GossipList(item);
                preprocessGossipListFromNet(gossipList, dataIdentifier.getExtraInfo1());

                break;
            }
            case LATEST_MESSAGE: {
                if (null == item) {
                    logger.debug("LATEST_MESSAGE from peer[{}] is empty", dataIdentifier.getExtraInfo1().toString());
                    return;
                }

                Message message = new Message(item);
                logger.debug("Got message:{}", Hex.toHexString(message.getHash()));
                this.messageMap.put(new ByteArrayWrapper(message.getHash()), message);
                this.messageSenderMap.put(new ByteArrayWrapper(message.getHash()), dataIdentifier.getExtraInfo1());

                break;
            }
            default: {
                logger.info("Type mismatch.");
            }
        }
    }

}
