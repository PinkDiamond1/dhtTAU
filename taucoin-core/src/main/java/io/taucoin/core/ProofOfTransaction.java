package io.taucoin.core;

import io.taucoin.db.BlockStore;
import io.taucoin.types.Block;
import io.taucoin.util.HashUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;

import static java.lang.Math.*;

public class ProofOfTransaction {
    private static final Logger logger = LoggerFactory.getLogger("proofoftransaction");

    private final byte[] chainID;

    public final static int AverageCommunityChainBlockTime = 300;
    private final static BigInteger CommunityChainGenesisBaseTarget = new BigInteger("21D0369D036978", 16);
    private final static BigInteger DiffAdjustNumerator = new BigInteger("010000000000000000",16);
    private final static BigInteger DiffAdjustNumeratorHalf = new BigInteger("0100000000",16);
    private final static BigInteger DiffAdjustNumeratorCoe = new BigInteger("800000000000000",16); //2^59

    private final int averageBlockTime;

    private final int minRatio;
    private final int maxRatio;

    private final int minBlockTime;
//    private final int maxBlockTime;

    private final BigInteger genesisBaseTarget;

    public ProofOfTransaction(byte[] chainID) {
        this(chainID, AverageCommunityChainBlockTime);
    }

    public ProofOfTransaction(byte[] chainID, int averageBlockTime) {
        this.chainID = chainID;
        this.averageBlockTime = averageBlockTime;

        // minRatio = averageBlockTime * (1 - 7 / 60)
        this.minRatio = this.averageBlockTime - this.averageBlockTime * 7 / 60;
        // maxRatio = averageBlockTime * (1 + 7 / 60)
        this.maxRatio = this.averageBlockTime + this.averageBlockTime * 7 / 60;

        // minBlockTime : aAverageBlockTime : maxBlockTime = 1 : 5 : 9
        this.minBlockTime = this.averageBlockTime / 5;
//        this.maxBlockTime = this.averageBlockTime * 9 / 5;

        // BaseTarget and Time are in inverse proportion
        // genesisBaseTarget = CommunityChainGenesisBaseTarget * AverageCommunityChainBlockTime / averageBlockTime
        this.genesisBaseTarget = CommunityChainGenesisBaseTarget.
                multiply(BigInteger.valueOf(AverageCommunityChainBlockTime)).
                divide(BigInteger.valueOf(averageBlockTime));
    }

    /**
     * get required base target
     *
     * @param chainID chain ID
     * @param previousBlock previous block
     * @param blockStore block store
     * @return
     */
    public BigInteger calculateRequiredBaseTarget(byte[] chainID, Block previousBlock, BlockStore blockStore) {
        long blockNumber = previousBlock.getBlockNum();
        if (blockNumber <= 3) {
            return this.genesisBaseTarget;
        }

        Block ancestor1, ancestor2, ancestor3;
        try {
            ancestor1 = blockStore.getBlockByHash(chainID, previousBlock.getPreviousBlockHash());
            if (null == ancestor1) {
                logger.error("Chain ID:{}: Cannot find parent, hash:{}",
                        new String(this.chainID), Hex.toHexString(previousBlock.getPreviousBlockHash()));
                return null;
            }
            ancestor2 = blockStore.getBlockByHash(chainID, ancestor1.getPreviousBlockHash());
            if (null == ancestor2) {
                logger.error("Chain ID:{}: Cannot find parent, hash:{}",
                        new String(this.chainID), Hex.toHexString(ancestor1.getPreviousBlockHash()));
                return null;
            }
            ancestor3 = blockStore.getBlockByHash(chainID, ancestor2.getPreviousBlockHash());
            if (null == ancestor3) {
                logger.error("Chain ID:{}: Cannot find parent, hash:{}",
                        new String(this.chainID), Hex.toHexString(ancestor2.getPreviousBlockHash()));
                return null;
            }
        } catch (Exception e) {
            logger.info(new String(this.chainID) + ":" + e.getMessage(), e);
            return null;
        }

        long totalTimeInterval = 0;
        if (previousBlock.getTimeStamp() > ancestor3.getTimeStamp()) {
            totalTimeInterval = previousBlock.getTimeStamp() -ancestor3.getTimeStamp();
        }

        long timeAver = totalTimeInterval / 3;

        BigInteger previousBlockBaseTarget = previousBlock.getBaseTarget();
        BigInteger requiredBaseTarget;

        if (timeAver > this.averageBlockTime ) {
            long min = 0;

            if (timeAver < this.maxRatio) {
                min = timeAver;
            } else {
                min = this.maxRatio;
            }

            requiredBaseTarget = previousBlockBaseTarget.multiply(BigInteger.valueOf(min)).
                    divide(BigInteger.valueOf(this.averageBlockTime));
        } else {
            long max = 0;

            if (timeAver > this.minRatio) {
                max = timeAver;
            } else {
                max = this.minRatio;
            }

            // 注意计算顺序：在计算机中整数的乘除法的计算顺序，对最终结果是有影响的，比如：
            // 3 / 2 * 2 = 2, 而3 * 2 / 2 = 3, 因此下面1、2、3的计算结果是不一样的
            // 这里采用和公式中的顺序一样，即：
            // If 𝐼𝑛 > AverageBlockTime, 𝑇(𝑏,𝑛) = 𝑇(𝑏,𝑛−1) * (min(𝐼𝑛,𝑅𝑚𝑎𝑥) / AverageBlockTime).
            // If 𝐼𝑛 < AverageBlockTime, 𝑇(𝑏,𝑛) = 𝑇(𝑏,𝑛−1) * (1− 𝛾 * (AverageBlockTime−max(𝐼𝑛,𝑅𝑚𝑖𝑛)) / AverageBlockTime)
            BigInteger delta = previousBlockBaseTarget.multiply(BigInteger.valueOf(64)).
                    divide(BigInteger.valueOf(100)).
                    multiply(BigInteger.valueOf(this.averageBlockTime - max)).
                    divide(BigInteger.valueOf(this.averageBlockTime));
            requiredBaseTarget = previousBlockBaseTarget.subtract(delta);
        }

        return requiredBaseTarget;
    }


    /**
     * get next block generation signature
     *     Gn+1 = hash(Gn, pubkey)
     * @param preGenerationSignature
     * @param pubkey
     * @return
     */
    public byte[] calculateGenerationSignature(byte[] preGenerationSignature, byte[] pubkey){
        byte[] data = new byte[preGenerationSignature.length + pubkey.length];

        System.arraycopy(preGenerationSignature, 0, data, 0, preGenerationSignature.length);
        System.arraycopy(pubkey, 0, data, preGenerationSignature.length, pubkey.length);

        return HashUtil.sha1hash(data);
    }


    /**
     * get miner target value
     * target = base target * sqrt(power) * time
     * @param baseTarget
     * @param power
     * @param time
     * @return
     */
    public BigInteger calculateMinerTargetValue(BigInteger baseTarget, BigInteger power, long time){
        Double p = sqrt(power.doubleValue());
        BigInteger realPower = BigInteger.valueOf(p.longValue());
        return baseTarget.multiply(realPower).
                multiply(BigInteger.valueOf(time));
    }


    /**
     * calculate hit
     * Hit = pow(2, 59) * |ln(((first eight bytes of Gn+1) + 1) / pow(2, 64))|
     * @param generationSignature
     * @return
     */
    public BigInteger calculateRandomHit(byte[] generationSignature){
        byte[] headBytes = new byte[8];
        System.arraycopy(generationSignature,0,headBytes,0,8);

        BigInteger bhit = new BigInteger(1, headBytes);
        logger.info("Chain ID:{}: bhit:{}", new String(this.chainID), bhit);

        BigInteger bhitUzero = bhit.add(BigInteger.ONE);
        logger.info("Chain ID:{}: bhitUzero:{}", new String(this.chainID), bhitUzero);

        double logarithm = abs(log(bhitUzero.doubleValue()) - 2 * log(DiffAdjustNumeratorHalf.doubleValue()));
        // Values of logarithm are mostly distributed between (0, 0.1), and int64(logarithm) == 0
        // To make hit smoother, we use logarithm * 1000 instead
        logarithm = logarithm * 1000;
        logger.info("Chain ID:{}: logarithm:{}", new String(this.chainID), logarithm);

        long ulogarithm = (Double.valueOf(logarithm)).longValue();
        logger.info("Chain ID:{}: ulogarithm:{}", new String(this.chainID), ulogarithm);

        // To offset the impact, divide by 1000
        BigInteger adjustHit = DiffAdjustNumeratorCoe.multiply(BigInteger.valueOf(ulogarithm)).divide(BigInteger.valueOf(1000));
        logger.info("Chain ID:{}: adjustHit:{}", new String(this.chainID), adjustHit);

        return adjustHit;
    }

    /**
     * calculate cumulative difficulty
     * @param lastCumulativeDifficulty
     * @param baseTarget
     * @return
     */
    public BigInteger calculateCumulativeDifficulty(BigInteger lastCumulativeDifficulty, BigInteger baseTarget) {
        return DiffAdjustNumerator.divide(baseTarget).add(lastCumulativeDifficulty);
    }

    /**
     * calculate mining time interval
     * @param hit
     * @param baseTarget
     * @param power
     * @return
     */
    public long calculateMiningTimeInterval(BigInteger hit, BigInteger baseTarget, BigInteger power) {
        // we need 𝐻 < 𝑇 = 𝑇(𝑏,𝑛) × sqrt(𝑃𝑒) × C
        // when T = H, calc C
        Double p = sqrt(power.doubleValue());
        BigInteger realPower = BigInteger.valueOf(p.longValue());
        long timeInterval =
                hit.divide(baseTarget).divide(realPower).longValue();

        // C++ to make sure T > H
        timeInterval++;
        logger.info("Chain ID:{}: Time interval:{}", new String(this.chainID), timeInterval);

        if (timeInterval < this.minBlockTime) {
            timeInterval = this.minBlockTime;
        }/* else if (timeInterval > this.maxBlockTime) {
            timeInterval = this.maxBlockTime;
        }*/
        logger.info("Chain ID:{}: Final time interval:{}", new String(this.chainID), timeInterval);

        return timeInterval;
    }

    /**
     * verifyHit verifies that target is greater than hit or the time meets the requirements
     * @param hit hit
     * @param baseTarget base target
     * @param power power
     * @param timeInterval time interval
     * @return true/false
     */
    public boolean verifyHit(BigInteger hit, BigInteger baseTarget, BigInteger power, long timeInterval) {
        if (timeInterval < this.minBlockTime) {
            logger.error("Chain ID:{}: Time interval is less than MinBlockTime[{}]", new String(this.chainID), this.minBlockTime);
            return false;
        } /*else if (timeInterval >= this.maxBlockTime) {
            logger.info("Chain ID:{}: OK. Time interval is greater than MaxBlockTime[{}]", new String(this.chainID), this.maxBlockTime);
            return true;
        } */else {
            BigInteger target = this.calculateMinerTargetValue(baseTarget, power, timeInterval);
            if (target.compareTo(hit) <= 0) {
                logger.error("Chain ID:{}: Invalid POT: target[{}] <= hit[{}]", new String(this.chainID), target, hit);
                return false;
            }
        }
        return true;
    }

}

