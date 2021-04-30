package io.taucoin.core;

import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.taucoin.types.GossipItem;
import io.taucoin.util.HashUtil;
import io.taucoin.util.RLP;
import io.taucoin.util.RLPList;

public class OnlineSignal {
    private byte[] deviceID;
    byte[] hashPrefixArray;
    FriendInfo friendInfo = null;
    byte[] chattingFriend; // 当前正在交谈的朋友
    BigInteger timestamp;
    private List<GossipItem> gossipItemList = new CopyOnWriteArrayList<>(); // 打听到的信息

    private byte[] hash;
    private byte[] rlpEncoded; // 编码数据
    private boolean parsed = false; // 解析标志

    public OnlineSignal(byte[] hashPrefixArray, byte[] chattingFriend,
                        BigInteger timestamp, List<GossipItem> gossipItemList) {
        this.hashPrefixArray = hashPrefixArray;
        this.chattingFriend = chattingFriend;
        this.timestamp = timestamp;
        this.gossipItemList = gossipItemList;

        this.parsed = true;
    }

    public OnlineSignal(byte[] deviceID, byte[] hashPrefixArray, FriendInfo friendInfo,
                        byte[] chattingFriend, BigInteger timestamp, List<GossipItem> gossipItemList) {
        this.deviceID = deviceID;
        this.hashPrefixArray = hashPrefixArray;
        this.friendInfo = friendInfo;
        this.chattingFriend = chattingFriend;
        this.timestamp = timestamp;
        this.gossipItemList = gossipItemList;

        this.parsed = true;
    }

    public OnlineSignal(byte[] rlpEncoded) {
        this.rlpEncoded = rlpEncoded;
    }

    public byte[] getDeviceID() {
        if (!parsed) {
            parseRLP();
        }

        return deviceID;
    }

    public byte[] getHashPrefixArray() {
        if (!parsed) {
            parseRLP();
        }

        return hashPrefixArray;
    }

    public FriendInfo getFriendInfo() {
        if (!parsed) {
            parseRLP();
        }

        return friendInfo;
    }

    public byte[] getChattingFriend() {
        if (!parsed) {
            parseRLP();
        }

        return chattingFriend;
    }

    public BigInteger getTimestamp() {
        if (!parsed) {
            parseRLP();
        }

        return timestamp;
    }

    public List<GossipItem> getGossipItemList() {
        if (!parsed) {
            parseRLP();
        }

        return gossipItemList;
    }

    public byte[] getHash() {
        if (null == this.hash) {
            this.hash = HashUtil.bencodeHash(getEncoded());
        }

        return this.hash;
    }

    /**
     * parse rlp encode
     */
    private void parseRLP() {
        RLPList params = RLP.decode2(this.rlpEncoded);
        RLPList list = (RLPList) params.get(0);

        this.deviceID = list.get(0).getRLPData();

        this.hashPrefixArray = list.get(1).getRLPData();

        byte[] friendInfoBytes = list.get(2).getRLPData();
        if (null != friendInfoBytes) {
            this.friendInfo = new FriendInfo(friendInfoBytes);
        }

        this.chattingFriend = list.get(3).getRLPData();

        byte[] timeBytes = list.get(4).getRLPData();
        this.timestamp = (null == timeBytes) ? BigInteger.ZERO: new BigInteger(1, timeBytes);

        parseGossipList((RLPList) list.get(5));

        this.parsed = true;
    }

    private void parseGossipList(RLPList list) {
        for (int i = 0; i < list.size(); i++) {
            byte[] encode = list.get(i).getRLPData();
            this.gossipItemList.add(new GossipItem(encode));
        }
    }

    public byte[] getGossipListEncoded() {
        byte[][] gossipListEncoded = new byte[this.gossipItemList.size()][];
        int i = 0;
        for (GossipItem gossipItem : this.gossipItemList) {
            gossipListEncoded[i] = gossipItem.getEncoded();
            ++i;
        }
        return RLP.encodeList(gossipListEncoded);
    }

    /**
     * get encoded hash list
     * @return encode
     */
    public byte[] getEncoded(){
        if (null == rlpEncoded) {
            byte[] deviceID = RLP.encodeElement(this.deviceID);
            byte[] hashPrefixArray = RLP.encodeElement(this.hashPrefixArray);
            byte[] friendInfo = RLP.encodeElement(null);
            if (null != this.friendInfo) {
                friendInfo = RLP.encodeElement(this.friendInfo.getEncoded());
            }
            byte[] chattingFriend = RLP.encodeElement(this.chattingFriend);
            byte[] timestamp = RLP.encodeBigInteger(this.timestamp);
            byte[] gossipListEncoded = getGossipListEncoded();

            this.rlpEncoded = RLP.encodeList(deviceID, hashPrefixArray, friendInfo,
                    chattingFriend, timestamp, gossipListEncoded);
        }

        return rlpEncoded;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OnlineSignal onlineSignal = (OnlineSignal) o;
        return Arrays.equals(getHash(), onlineSignal.getHash());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(getHash());
    }

    @Override
    public String toString() {
        byte[] deviceID = getDeviceID();
        byte[] hashPrefixArray = getHashPrefixArray();
        FriendInfo friendInfo = getFriendInfo();
        BigInteger time = getTimestamp();
        byte[] chattingFriend = getChattingFriend();

        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("OnlineSignal{");
        if (null != deviceID) {
            stringBuilder.append("device id=");
            stringBuilder.append(Hex.toHexString(deviceID));
        }

        if (null != hashPrefixArray) {
            stringBuilder.append(", hash prefix array=");
            stringBuilder.append(Hex.toHexString(hashPrefixArray));
        }
        if (null != friendInfo) {
            stringBuilder.append(", friend info=");
            stringBuilder.append(friendInfo.toString());
        }
        if (null != time) {
            stringBuilder.append(", time=");
            stringBuilder.append(time);
        }
        if (null != chattingFriend) {
            stringBuilder.append(", chatting friend=");
            stringBuilder.append(Hex.toHexString(chattingFriend));
        }

        stringBuilder.append("}");

        return stringBuilder.toString();
    }
}
