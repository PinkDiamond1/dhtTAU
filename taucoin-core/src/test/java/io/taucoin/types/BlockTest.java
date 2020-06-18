package io.taucoin.types;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;

import io.taucoin.util.ByteUtil;

public class BlockTest {
    private static final Logger log = LoggerFactory.getLogger("blockTest");
    private static final byte version = 1;
    private static final String chainid = "TAUcoin#300#3938383036366633393364383365393338373434";
    private static final String transaction = "f9019f01b4544155636f696e233330302333393338333833303336333636363333333933333634333833333635333933333338333733343334880000000008f0d1801e84ffffffc4b84063353839373836356538636437356434616563376665393538336138363963386239363239323163633661656632626635656433666632616564306562323363880000000000000000f889809a736861726520796f7572206661766f7572697465206d75736963b86b687474703a2f2f7777772e6b75676f752e636f6d2f736f6e672f366e6e796162622e68746d6c3f66726f6d62616964753f66726f6d626169647523686173683d423036413434304234433231453239423334414338303338453330384530324626616c62756d5f69643d30b88236353238316633633266653330393638336337343736326639363566333862643866383931306438646265636131646139303464363832316538313031303735373736323433333739613465666466646338633130616533346265373637613832356637373065366136326235343330633033306631373962373430353765373437";
    private static final String pblockhash = "c5897865e8cd75d4aec7fe9583a869c8b962921cc6aef2bf5ed3ff2aed0eb23c";
    private static final String imblockhash = "b7516c32e5ff8144bb919a141ce051de00b09b01d79e2217ba94dc567242e6ce";
    private static final BigInteger basetarget = new BigInteger("21D0369D036978",16);
    private static final BigInteger cummulativediff = BigInteger.ZERO;
    private static final String generationSig = "c178f0713ef498e88def4156a9425e8469cdb0b7138a21e20d4be7e4836a8d66";
    private static final String signature = "65281f3c2fe309683c74762f965f38bd8f8910d8dbeca1da904d6821e8101075776243379a4efdfdc8c10ae34be767a825f770e6a62b5430c030f179b74057e747";
    private static final String bk = "f9036101b4544155636f696e233330302333393338333833303336333636363333333933333634333833333635333933333338333733343334880000000008f0d180880000000000000001b84063353839373836356538636437356434616563376665393538336138363963386239363239323163633661656632626635656433666632616564306562323363b840623735313663333265356666383134346262393139613134316365303531646530306230396230316437396532323137626139346463353637323432653663658721d0369d03697880b84063313738663037313365663439386538386465663431353661393432356538343639636462306237313338613231653230643462653765343833366138643636f9019f01b4544155636f696e233330302333393338333833303336333636363333333933333634333833333635333933333338333733343334880000000008f0d1801e84ffffffc4b84063353839373836356538636437356434616563376665393538336138363963386239363239323163633661656632626635656433666632616564306562323363880000000000000000f889809a736861726520796f7572206661766f7572697465206d75736963b86b687474703a2f2f7777772e6b75676f752e636f6d2f736f6e672f366e6e796162622e68746d6c3f66726f6d62616964753f66726f6d626169647523686173683d423036413434304234433231453239423334414338303338453330384530324626616c62756d5f69643d30b882363532383166336332666533303936383363373437363266393635663338626438663839313064386462656361316461393034643638323165383130313037353737363234333337396134656664666463386331306165333462653736376138323566373730653661363262353433306330333066313739623734303537653734378800000000000186a08800000000000186a0880000000000013880880000000000000002b88236353238316633633266653330393638336337343736326639363566333862643866383931306438646265636131646139303464363832316538313031303735373736323433333739613465666466646338633130616533346265373637613832356637373065366136326235343330633033306631373962373430353765373437";
    @Test
    public void createBlock(){
        Transaction tx = new Transaction(ByteUtil.toByte(transaction));
        Block block = new Block(version,chainid,150000000,1,pblockhash,imblockhash,
                basetarget,cummulativediff,generationSig,tx,100000,100000,
                80000,2,signature);
        String str = ByteUtil.toHexString(block.getEncoded());
        log.debug(str);
        log.debug("block size is: "+ ByteUtil.toByte(str).length + " bytes");
    }

    @Test
    public void decodeBlock(){
        Block block = new Block(ByteUtil.toByte(bk));
        log.debug("version: "+block.getVersion());
        log.debug("chainid: "+block.getChainID());
        log.debug("timestamp: "+block.getTimeStamp());
        log.debug("blocknum: "+block.getBlockNum());
        log.debug("phash: "+block.getPreviousBlockHash());
        log.debug("imhash: "+block.getImmutableBlockHash());
        log.debug("basetarget: "+block.getBaseTarget().toString());
        log.debug("cummulativediff: "+block.getCumulativeDifficulty().toString());
    }
}
