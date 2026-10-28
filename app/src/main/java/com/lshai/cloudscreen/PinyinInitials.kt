package com.lshai.cloudscreen

/**
 * GB2312 汉字 -> 拼音声母 的查找表（离线生成，无需第三方分词库）。
 *
 * 覆盖 GB2312 全部 6763 个汉字（一级 + 二级），按区位码顺序排列：
 * `TABLE[(b1 - 0xB0) * 94 + (b2 - 0xA1)]` 即该字的声母，空格 表示没有对应读音。
 *
 * 为什么不用 pinyin4j / TinyPinyin：一个 6KB 的字符串就能覆盖全部常用字，
 * 不增加任何依赖，老盒子加载也快。多音字取 pypinyin 的默认读音（最常用读法）。
 */
internal object PinyinInitials {

    const val TABLE =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbpbbbbbbbbb" +
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbpbbbbbbbbbbbbcccccccccccccccccccc" +
        "ccccccccccccccccccccccccccccccccccccccccccccczcccccccccccccccccccccccccccccccccc" +
        "ccccccccccccccccsccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" +
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccddddddddddddddddddddddddddd" +
        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd" +
        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddeee" +
        "eeeeeeeeeeeeeeeeeeefffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
        "fffffffffffffffffffffffffffffffffffffffffffpffffffffffffffffffffgggggggggggggggg" +
        "ggggggggggggggggggggggggggggggggghgggggggggggggggggggggggggggggggggggggggggggggg" +
        "ggggggggggggggggggggggggggggggggggggggggggggggggggggggggggghhhhhhhhhhhhhhhhhhhhh" +
        "hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh" +
        "hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh" +
        "jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj" +
        "jjjjjjjjjjjjjjkjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj" +
        "jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj" +
        "jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjkkkgkkkkkkkkkkkkkkkkkkkkk" +
        "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkgkkkkkkkkkkklllll" +
        "llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll" +
        "llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll" +
        "llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll" +
        "lllmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm" +
        "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmnnnnnn" +
        "nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnooooo" +
        "oooppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp" +
        "pppppppppppppppppppppppppppppppppppppppppppppqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
        "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqjqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
        "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqgqqqqqqqqrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr" +
        "rrrrrrrrrrrrrrrrrrrrrsssssssssssssssssssssssssssssssssssssssssssssssssssssssssss" +
        "ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss" +
        "ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss" +
        "sssssssssssssscssssssssssssssssssssssssssssssssssssssssssssssssssssttttttttttttt" +
        "tttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttt" +
        "tttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttwwwwwwwwwwwwwwwww" +
        "wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww" +
        "wwwwwwwwwwwwwwwwwwwwwwwxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxsxxxxxxxxxxx" +
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxcxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
        "xxxxxxyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy" +
        "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy" +
        "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy" +
        "yyyyyyyyyyyyyyxyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyzzzzzzzz" +
        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzczzzzzyzzzzzzzzzzzzzzzzzzzzzznzzzzzzzzzzzzzzzzzzz" +
        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" +
        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" +
        "zzzzzzczzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz     " +
        "cjwgnspgcgnegypbtyyzdxykygtzjnmjqmbsgzscyjsyyfpgkbzgydywjkgkljswkpjqhyjwrdzlsgmr" +
        "ypywwcckznkyygttngjeykkzytcjnmcylqlypyqfqrpzslwbtgkjfyxjwzltbncxjjjjtxdttsqzycdx" +
        "xhgckbphffsstybgmxlpbyllbhlxsmzmyjhsojnghdzqyklgjhsgqzhxqgkezzwyscscjxyeyxadzpmd" +
        "ssmzjzqjyzcjjfwqjbdzbxgznzcpwhkxhqkmwfbpbydtjzzkqhylygxfptyjyyzpszlfchmqshgmxxsx" +
        "jyqdcsbbqbefsjyhwwgzkpylqbgldlcctnmayddkssngycsgxlyzaypnptsdkdylhgymylcxpycjndqj" +
        "wqqxfyyfjlejpzrxccqwqqsbzkymgplbmjrqcflnymyqmsqtrbcjthztqfrxqhxmjjcjlxxgjmshzkbs" +
        "wyemyltxfsydsglycjqxsjnqbsctyhbftdcyjdjwyghqfrxwckqkxebptlpxjzsrmebwhjlbjslyysmd" +
        "xlclqkxlhxjrzjmfqhxhwywsbhtrxxglhqhfnmgykldyxzpylggsmtcfpajjzyljtyanjgbjplqgdzyq" +
        "yaxbkysecjsznslyzhzxlzcghpxzhznytdsbcjkdlzyyfwydlebbgqyzkggldndnyskjshdlyxbcghxy" +
        "pkdjmmzngmmclgwzszxzjfznmlzzthcsydbdllscddnlkjykjsycjlkohqasdknhcsganhdaashtcplc" +
        "pqybsdmpjlpcjoqlcdhjjysprchnwjnlhlyyqyhwzptczgwwmzffjqqqqyxaclbhkdjxdgmmydjxzlls" +
        "ygxgkjrywzwyclzmssjzldbydcpcxyhlxchyzjqsqqagmnyxpfrkssbjlyxysyglnscmhcwwmnzjjlxx" +
        "hchsyzsttxrycyxbyhcsmxjsznpwgpxxtaybgajcxlyxdccwzocwkccsbnhcpdyznfcyytyckxkybsqk" +
        "kytqqxfcwchcykelzqbsqyjqcclmthsywhmktlkjlycxwheqqhtqhqpqsqscfymmdmgbwhwlgsllystl" +
        "mlxpthmjhwljzyhzjxhtxjlhxrswlwzjcbxmhzqxsdzpmgfcsglsxymjshxpjxwmyqksmyplrthbxftp" +
        "mhyxlchlhlzylxgsssstclsldclrpbhzhxyyfhbmgdmycnqqwlqhjjcywjzyejjdhpblqxtqkwhlchqx" +
        "agtlxljxmsljhtzkzjecxjcjnmfbycsfywybjzgnysdzsqyrsljpclpwxsdwejbjcbcnaytwgmpapcly" +
        "qpclzxsbnmsggfnzjjbzsfzyndxhplqkzczwalsbccjxsyzgwkypsgxfzfcdkhjgxtlqfsgdslqwzkxt" +
        "mhsbgzmjzrglyjbpmlmsxlzjqqhzyjczydjwbwjklddpmjegxyhylxhlqyqhkycwcjmyyxnatjhyccxz" +
        "pcqlbzwwytwbqcmlpmyrjcccxfpznzzljplxxyztzlgdldcklyrzzgqtgjhhgjljaxfgfjzslcfdqzlc" +
        "lgjdjzsnzlljpjqdcclcjxmyzftsxgcgsbrzxjqqctzhgyqtjqqlzxjylylbcyamcstylpdjbyregklz" +
        "yzhlyszqlznwczcllwjqjjjkdgjzolbbzppglghtgzxyjhzmycnqcycyhbhgxkamtxyxnbskyzzgjzlq" +
        "jdfcjxdygjqjjpmgwgjjjpkqsbgbmmcjssclpqpdxcdyykypcjddyygywrhjrtgznyqldkljszzgzqzj" +
        "gdykshpzmtlcpwnjyfyzdjcnmwescyglbtzcgmssllyxysxsbsjsbbsgghfjlypmzjnlyywdqshzxtyy" +
        "whmcyhywdbxbtlmsyyyfsxjcbdxxlhjhfssxzqhfzmzcztqcxzxrttdjhnnyzqqmtqdmmgyydxmjgdhc" +
        "dyzbffallztdltfxmxqzdngwqdbdcdjdxbzgsqqddjcmbkzffxmkdmdsyyszcmljdsynsprskmkmpckl" +
        "gtbqtfzswtfgglyplljzhgjjgypzltcsmcnbtjbqfkthbyzgkpbbymtdssxtbnpdkleycjnyddykzddh" +
        "qhsdzsctarlltkzlgecllkjlqjaqnbdkkghpjtzqksecshalqfmmgjnlyjbbtmlyzxdcjpldlpcqdhzy" +
        "cbzsczbzmsljflkrzjsnfrgjhxpdhyjybzgdlqcsezgxlblgyxtwmabchecmwyjyzlljjyhlgndjlsly" +
        "gkdzpzxjyyzlwcxszfgwyydlyhcljscmbjhblyzlycblydpdqysxqzbytdkyxjyycnrjmpdjgklcljbc" +
        "tbjddbblblczqrppxjcjlzcshltoljnmdddlngkathqhjhykheznmshrphqqjchgmfprxhjgdychghly" +
        "rzqlcyqjnzsqtkqjymszswlcfqqqxyfggyptqwlmcrnfkkfsyylqbmqammmyxctpshcptxxzzsmphpsh" +
        "mclmldqfyqxszyjdjjzzhqpdszglstjbckbxyqzysgpsxqzqzrqtbdkyxzkhhgflbcsmdldgdzdblzyy" +
        "cxnncsybzbfglzzxswmsccmqnjqsbdqsjtxxmbltxzclzshzcxrqjgjylxzfjphymzqqydfqjjlzznzj" +
        "sdgzygctxmzysctlkphtxhtlbjxjlxscdqxcbbtjfqzfsltjbtkqbxxjjljchczdbzjdczjdcprnpqcj" +
        "pfczlclzxzdmxmphjsgzgszzqjylwtjpfsyaxmcjbtzkycwmytzsjjlqcqlwzmalbxyfbpnlsfhtgjwe" +
        "jjxxglljstgshjqlzfkcgnndszfdeqfhbsaqtgylbxmmygszldydqmjjrgbjtkgdhgkblqkbdmbylxwc" +
        "xyttybkmrtjzxqjbhlmhmjjzmqasldcyxyqdlqcafywyxqhz"

/** 单个字符的声母；不是汉字（或表里没有）返回 null，ASCII 字母数字返回自身小写 */
    fun initialOf(c: Char): Char? {
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return c.lowercaseChar()
        if (c.code < 0x2E80) return null   // 中日韩字符区之外不再尝试编码
        val gb = try {
            String(charArrayOf(c)).toByteArray(charset("GB2312"))
        } catch (_: Exception) {
            return null
        }
        if (gb.size != 2) return null
        val b1 = gb[0].toInt() and 0xFF
        val b2 = gb[1].toInt() and 0xFF
        if (b1 < 0xB0 || b1 > 0xF7 || b2 < 0xA1 || b2 > 0xFE) return null
        val initial = TABLE[(b1 - 0xB0) * 94 + (b2 - 0xA1)]
        return if (initial == ' ') null else initial
    }

    /**
     * 取一串文字的拼音首字母：「超级汽车」→ "cjcq"。
     * ASCII 字母数字原样保留（「超级宝贝JOJO」→ "cjbbjojo"），其余字符跳过。
     */
    fun initialsOf(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (c in text) initialOf(c)?.let { sb.append(it) }
        return sb.toString()
    }
}
