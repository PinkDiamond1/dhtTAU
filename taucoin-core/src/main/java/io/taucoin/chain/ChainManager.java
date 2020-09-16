package io.taucoin.chain;

import com.frostwire.jlibtorrent.Pair;
import io.taucoin.account.AccountManager;
import io.taucoin.core.AccountState;
import io.taucoin.core.TransactionPool;
import io.taucoin.types.BlockContainer;
import io.taucoin.db.BlockDB;
import io.taucoin.db.KeyValueDataBaseFactory;
import io.taucoin.db.StateDB;
import io.taucoin.db.StateDBImpl;
import io.taucoin.genesis.GenesisConfig;
import io.taucoin.genesis.GenesisItem;
import io.taucoin.genesis.TauGenesisConfig;
import io.taucoin.genesis.TauGenesisTransaction;
import io.taucoin.listener.TauListener;
import io.taucoin.param.ChainParam;
import io.taucoin.processor.StateProcessor;
import io.taucoin.processor.StateProcessorImpl;
import io.taucoin.torrent.DHT;
import io.taucoin.torrent.TorrentDHTEngine;
import io.taucoin.types.TypesConfig;
import io.taucoin.types.GenesisTx;
import io.taucoin.types.Transaction;
import io.taucoin.util.ByteArrayWrapper;
import io.taucoin.util.ByteUtil;
import io.taucoin.util.Repo;
import io.taucoin.types.Block;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.File;
import java.util.*;

/**
 * ChainManager manages all blockchains for tau multi-chain system.
 * It's responsible for creating chain, following chain, unfollow chain,
 * pause chain, resume chain and so on.
 */
public class ChainManager {

    private static final Logger logger = LoggerFactory.getLogger("ChainManager");

    private Chains chains;

    private TauListener listener;

    // state database
    private StateDBImpl stateDB;

    // state path
    private static final String STATE_PATH = "state";

    // block database
    private BlockDB blockDB;

    // block store path
    private static final String BLOCK_PATH = "block";

    /**
     * ChainManager constructor.
     *
     * @param listener CompositeTauListener
     */
    public ChainManager(TauListener listener, KeyValueDataBaseFactory dbFactory) {
        this.listener = listener;

        // create state and block database.
        // If database does not exist, directly load.
        // If not exist, create log
        this.stateDB = new StateDBImpl(dbFactory.newDatabase());
        this.blockDB = new BlockDB(dbFactory.newDatabase());

        chains = new Chains(this.blockDB, this.stateDB, this.listener);
    }

    public void openChainDB() throws Exception {
        try {
            this.stateDB.open(Repo.getRepoPath() + File.separator + STATE_PATH);
            this.blockDB.open(Repo.getRepoPath() + File.separator + BLOCK_PATH);
        } catch (Exception e) {
            throw e;
        }
    }

    public boolean initTauChain() throws Exception {
        // New TauConfig
        GenesisConfig tauConfig = TauGenesisConfig.getInstance();
        byte[] chainid = TauGenesisTransaction.ChainID;
        logger.info("Initial tau chain chainid: {}", new String(chainid));

        if(!this.stateDB.isChainFollowed(chainid)) {
            // New TauChain
            if (!createNewCommunity(tauConfig)) {
                return false;
            }
        }

        return true;
    }

    public void closeChainDB() {
        this.stateDB.close();
        this.blockDB.close();
    }

    /**
     * Start all followed and mined blockchains.
     * This method is called by TauController.
     */
    public void start() {

        // Open the db for repo and block
        try {

            openChainDB();
            
            initTauChain();

            Set<byte[]> chainIDs = this.stateDB.getAllFollowedChains();
            if (null != chainIDs) {
                for (byte[] chainID: chainIDs) {
                    chains.startChain(chainID);
                }
            }

            chains.start();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            // notify starting blockchains exception.
            listener.onChainManagerStarted(false, e.getMessage());
            return;
        }

        listener.onChainManagerStarted(true, "");
    }

    /**
     * Stop all followed and mined blockchains.
     * This method is called by TauController.
     */
    public void stop() {
        // stop chains
        chains.stop();
        // close db
        closeChainDB();

        listener.onChainManagerStopped();
    }

    /**
     * Follow some chain specified by chain id.
     *
     * @param chainID
     * @return boolean successful or not.
     */
    public boolean followChain(byte[] chainID, List<byte[]> peerList) {
        this.chains.followChain(chainID, peerList);

        return true;
    }

    /**
     * Unfollow some chain specified by chain id.
     *
     * @param chainID
     * @return boolean successful or not.
     */
    public boolean unfollowChain(byte[] chainID) {
        return this.chains.unFollowChain(chainID);
    }

    /**
     * update public key
     * @param pubKey
     */
    public void updateKey(byte[] pubKey) {
        Set<ByteArrayWrapper> chainIDs = chains.getAllChainIDs();
        for (ByteArrayWrapper chainID: chainIDs) {
            chains.getTransactionPool(chainID).updatePubKey(pubKey);
        }
    }

    /**
     * Create new community.
     *
     * @param communityName community name
     * @param genesisItems airdrop accounts balance and power
     * @return boolean true indicates creating successfully, or else false.
     */
    public boolean createNewCommunity(String communityName,
            HashMap<ByteArrayWrapper, GenesisItem> genesisItems) {

        GenesisConfig config = new GenesisConfig(this, communityName, genesisItems);
        return createNewCommunity(config);
    }

    /**
     * create new community.
     * @param cf
     * @return true/false.
     */
    public boolean createNewCommunity(GenesisConfig cf){
        byte[] chainID = cf.getChainID();
        
        Block genesis = cf.getBlock();
        BlockContainer genesisContainer = new BlockContainer(genesis, cf.getTransaction());

        // load genesis state
        if (!loadGenesisState(chainID, genesisContainer)) {
            return false;
        }

        Transaction tx = genesisContainer.getTx();

        if (null == tx) {
            logger.error("Genesis tx is null!");
            return false;
        }

        if (tx.getTxType() != TypesConfig.TxType.GenesisType.ordinal()) {
            logger.error("Genesis type mismatch!");
            return false;
        }

        HashMap<ByteArrayWrapper, GenesisItem> map = ((GenesisTx) tx).getGenesisAccounts();
        if (null == map || map.size() <= 0) {
            logger.error("Genesis account is empty.");
            return false;
        }

        try {
            logger.info("Save genesis block in block store. Chain ID:{}",
                    new String(chainID));
            blockDB.saveBlockContainer(chainID, genesisContainer,true);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }

        // follow chain
        List<byte[]> peerList = new ArrayList<>();

        for (Map.Entry<ByteArrayWrapper, GenesisItem> entry : map.entrySet()) {
            byte[] pubKey = entry.getKey().getData();
            logger.info("create new community pubkey: {}", Hex.toHexString(pubKey));
            peerList.add(pubKey);
        }

        this.chains.followChain(chainID, peerList);

        // put block to dht
        putBlockContainerToDHT(chainID, genesisContainer);

        return true;
    }

    /**
     * load genesis state to db
     * @param chainID chain id
     * @param genesisContainer genesis block container
     * @return
     */
    private boolean loadGenesisState(byte[] chainID, BlockContainer genesisContainer) {
        try {
            StateProcessor stateProcessor = new StateProcessorImpl(chainID);
            StateDB track = stateDB.startTracking(chainID);
            if (stateProcessor.backwardProcessGenesisBlock(genesisContainer, track)) {
                track.setBestBlockHash(chainID, genesisContainer.getBlock().getBlockHash());
                track.setSyncBlockHash(chainID, genesisContainer.getBlock().getBlockHash());
                track.commit();
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    /**
     * put block to dht
     * @param chainID chain id
     * @param blockContainer block to put
     */
    private void putBlockContainerToDHT(byte[] chainID, BlockContainer blockContainer) {

        if (null != blockContainer) {
            if (null != blockContainer.getTx()) {
                // put immutable tx
                DHT.ImmutableItem immutableItem =
                        new DHT.ImmutableItem(blockContainer.getTx().getEncoded());
                TorrentDHTEngine.getInstance().distribute(immutableItem);
            }

            // put immutable block
            DHT.ImmutableItem immutableItem = new DHT.ImmutableItem(blockContainer.getBlock().getEncoded());
            TorrentDHTEngine.getInstance().distribute(immutableItem);

            // put mutable item
            byte[] blockSalt = makeBlockSalt(chainID);
            Pair<byte[], byte[]> keyPair = AccountManager.getInstance().getKeyPair();
            DHT.MutableItem mutableItem = new DHT.MutableItem(keyPair.first, keyPair.second,
                    ByteUtil.getHashEncoded(blockContainer.getBlock().getBlockHash()), blockSalt);
            TorrentDHTEngine.getInstance().distribute(mutableItem);
        }
    }

    /**
     * make block salt
     * @return
     */
    private byte[] makeBlockSalt(byte[] chainID) {
        byte[] salt = new byte[chainID.length + ChainParam.BLOCK_TIP_CHANNEL.length];
        System.arraycopy(chainID, 0, salt, 0, chainID.length);
        System.arraycopy(ChainParam.BLOCK_TIP_CHANNEL, 0, salt, chainID.length,
                ChainParam.BLOCK_TIP_CHANNEL.length);
        return salt;
    }

    /**
     * according to chainid select a chain to sendTransaction.
     * @param tx
     * @return
     * todo
     */
    public void sendTransaction(Transaction tx){
        // get chainid
        ByteArrayWrapper chainid= new ByteArrayWrapper(tx.getChainID());

        TransactionPool  transactionPool = this.chains.getTransactionPool(chainid);

        if (null != transactionPool) {
            transactionPool.addTx(tx);
        }
    }

    /**
     * get account state according to chainid and pubkey
     * @param chainid
     * @param pubkey
     * @return
     * todo
     */
    public AccountState getAccountState(byte[] chainid, byte[] pubkey) throws Exception {

        AccountState account= null;

        try{
            account= this.stateDB.getAccount(chainid, pubkey);
        } catch (Exception e) {
            throw e;
        }

        return account;
    }

    /**
     * get best block
     * @return
     * todo
     */
    public ArrayList<Block> getAllBestBlocks(){

        ArrayList<Block> blocks= new ArrayList<Block>();

        Set<ByteArrayWrapper> chainIDs = chains.getAllChainIDs();
        for (ByteArrayWrapper chainID: chainIDs) {
            blocks.add(chains.getBestBlockContainer(chainID).getBlock());
        }

        return blocks;
    }

    /**
     * get block by hash
     * @param chainid: chain id
     * @param blockHash: hash of wanted block
     * @return
     */
    public Block getBlockByHash(byte[] chainid, byte[] blockHash) throws Exception {

        Block block = null;
        try {
            block = this.blockDB.getBlockByHash(chainid, blockHash);
        } catch (Exception e) {
            throw e;
        }
        return block;
    }

    /**
     * get transaction by hash
     * @param chainid: chain id
     * @param txHash: hash of wanted transaction
     * @return
     */
    public Transaction getTransactionByHash(byte[] chainid, byte[] txHash) throws Exception {

        Transaction tx = null;
        try {
            tx = this.blockDB.getTransactionByHash(chainid, txHash);
        } catch (Exception e) {
            throw e;
        }
        return tx;
    }
    /**
     * get transaction pool
     * @param chainid: chain id
     * @return
     */
    public List<Transaction> getTransactionsInPool(byte[] chainid) {
        List<Transaction> list = new ArrayList<>();
        TransactionPool transactionPool = this.chains.getTransactionPool(new ByteArrayWrapper(chainid));

        if (null != transactionPool) {
            list = transactionPool.getAllTransactions();
        }

        return list;
    }

    /**
     * get block by num
     * @param chainid: chain id
     * @param blockNum: number of wanted block
     * @return
     */
    public Block getBlockByNumber(byte[] chainid, long blockNum) throws Exception {

        Block block = null;
        try {
            block = this.blockDB.getMainChainBlockByNumber(chainid, blockNum);
        } catch (Exception e) {
            throw e;
        }
        return block;
    }

    /**
     * make all followed chain start mining.
     * @return
     * todo
     */
    public byte[][] startMining(){
        return null;
    }

    /**
     * start mining
     * @param chainID chain ID
     */
    public void startMining(byte[] chainID) {
        this.chains.startMining(chainID);
    }

    /**
     * stop mining, just sync
     * @param chainID chain ID
     */
    public void stopMining(byte[] chainID) {
        this.chains.stopMining(chainID);
    }
}
