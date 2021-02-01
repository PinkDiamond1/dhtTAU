package io.taucoin.types;

import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.taucoin.util.RLP;
import io.taucoin.util.RLPList;

public class GossipMutableData {
    private byte[] deviceID;
    private BigInteger timestamp;
    private List<byte[]> friendList = new CopyOnWriteArrayList<>();
    private List<GossipItem> gossipItemList = new CopyOnWriteArrayList<>();

    private byte[] rlpEncoded;
    private boolean parsed = false;

    public GossipMutableData(byte[] deviceID, List<byte[]> friendList, List<GossipItem> gossipItemList) {
        this.deviceID = deviceID;
        this.timestamp = BigInteger.valueOf(System.currentTimeMillis() / 1000);
        this.friendList = friendList;
        this.gossipItemList = gossipItemList;

        this.parsed = true;
    }

    public GossipMutableData(byte[] encode) {
        this.rlpEncoded = encode;
    }

    public byte[] getDeviceID() {
        if (!parsed) {
            parseRLP();
        }

        return deviceID;
    }

    public BigInteger getTimestamp() {
        if (!parsed) {
            parseRLP();
        }

        return timestamp;
    }

    public List<byte[]> getFriendList() {
        if (!parsed) {
            parseRLP();
        }
        
        return friendList;
    }

    public List<GossipItem> getGossipItemList() {
        if (!parsed) {
            parseRLP();
        }
        
        return gossipItemList;
    }

    /**
     * parse rlp encode
     */
    private void parseRLP() {
        // TODO:: decode exception
        RLPList params = RLP.decode2(this.rlpEncoded);
        RLPList list = (RLPList) params.get(0);

        this.deviceID = list.get(0).getRLPData();

        byte[] timeBytes = list.get(1).getRLPData();
        this.timestamp = (null == timeBytes) ? BigInteger.ZERO: new BigInteger(1, timeBytes);

        parseFriendList((RLPList) list.get(2));
        parseGossipList((RLPList) list.get(3));

        this.parsed = true;
    }

    private void parseFriendList(RLPList list) {
        for (int i = 0; i < list.size(); i++) {
            byte[] encode = list.get(i).getRLPData();
            this.friendList.add(encode);
        }
    }

    private void parseGossipList(RLPList list) {
        for (int i = 0; i < list.size(); i++) {
            byte[] encode = list.get(i).getRLPData();
            this.gossipItemList.add(new GossipItem(encode));
        }
    }

    public byte[] getFriendListEncoded() {
        byte[][] friendListEncoded = new byte[this.friendList.size()][];
        int i = 0;
        for (byte[] friend : this.friendList) {
            friendListEncoded[i] = RLP.encodeElement(friend);
            ++i;
        }
        return RLP.encodeList(friendListEncoded);
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
            byte[] timestamp = RLP.encodeBigInteger(this.timestamp);
            byte[] friendListEncoded = getFriendListEncoded();
            byte[] gossipListEncoded = getGossipListEncoded();

            this.rlpEncoded = RLP.encodeList(deviceID, timestamp, friendListEncoded, gossipListEncoded);
        }

        return rlpEncoded;
    }

    @Override
    public String toString() {
        List<String> list = new ArrayList<>();

        byte[] deviceID = getDeviceID();
        if (null != deviceID) {
            list.add(new String(deviceID));
        }

        BigInteger timeStamp = getTimestamp();

        List<byte[]> friendList = getFriendList();
        if (null != friendList) {
            for (byte[] friend: friendList) {
                list.add("friend:" + Hex.toHexString(friend));
            }
        }

        List<GossipItem> gossipList = getGossipItemList();
        if (null != gossipList) {
            for (GossipItem gossipItem: gossipList) {
                list.add(gossipItem.toString());
            }
        }

        return "Gossip{ timestamp: " + timeStamp + ", " + list + '}';
    }

}
