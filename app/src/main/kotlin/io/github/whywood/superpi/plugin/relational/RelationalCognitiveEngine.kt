package io.github.whywood.superpi.plugin.relational

object RelationalCognitiveEngine {

    fun generateBriefing(
        contact: ContactDossier,
        chatHistory: String,
    ): RelationalBriefing {
        val taboos = contact.taboos
        val (summary, hiddenIntent, tips) = analyzeDialogueContext(contact, chatHistory, taboos)

        return RelationalBriefing(
            contact = contact,
            recentSummary = summary,
            taboos = taboos,
            hiddenIntent = hiddenIntent,
            advisoryTips = tips,
        )
    }

    /**
     * 为大模型组装推演上下文。
     * 关键设计准则：
     * 1. 场景分类仅用于用户侧切换认知语境与管理联系人，绝不作为粗糙劣质的分类指令输入给 AI；
     * 2. AI 必须以【真实聊天记录】作为第一核心凭据，从中自然感知双方关系、语境与交流口吻；
     * 3. 严格遵循用户的真实底线意图，严禁捏造未发生事实，严禁擅自做决定，严格避开敏感雷区。
     */
    fun assembleAiPromptContext(
        contact: ContactDossier,
        chatHistory: String,
        userIntent: String,
        tonePreference: String? = null,
    ): RelationalPromptContext {
        val taboos = contact.taboos
        val cleanIntent = userIntent.trim()

        val guidelines = mutableListOf<String>()
        guidelines += "【聊天记录为第一核心凭据】判断沟通语境、双方关系与情绪基调必须完全以本机真实聊天记录为准，严禁套用虚浮的通用套话模板；角色记录（${contact.role.ifBlank { "联系人" }}）仅作为客观背景参考。"
        guidelines += "【真实性铁律】所有回复必须严格基于用户给出的真实意图（$cleanIntent），严禁虚构未发生的现场细节（如捏造未在记录中出现的事实），严禁越权擅自承诺处理结果。"
        if (taboos.isNotEmpty()) {
            guidelines += "【雷区避让】对话中必须严格避开已知敏感点：${taboos.joinToString("；")}。"
        }
        guidelines += "【多维风格推演】直接基于上述具体聊天记录的真实语境与用户意图，现场推演 3 种自然得体的回复表达供挑选："
        guidelines += "  1. 选项 1【温和共情版】：真诚肯定对方关注点，顾及情绪感受，降温并消除对立；"
        guidelines += "  2. 选项 2【专业客观版】：清晰陈述事实核实动作与推进节点，守住工作与权责边界；"
        guidelines += "  3. 选项 3【简明得体版】：简明扼要快速回应，礼貌得体，尊重双方时间。"

        if (!tonePreference.isNullOrBlank()) {
            guidelines += "【用户特定语气要求】：$tonePreference"
        }

        return RelationalPromptContext(
            contact = contact,
            userIntent = cleanIntent,
            taboosToCheck = taboos,
            recentChatExcerpt = chatHistory,
            guidelines = guidelines,
        )
    }

    private fun analyzeDialogueContext(
        contact: ContactDossier,
        chatHistory: String,
        taboos: List<String>,
    ): Triple<String, String, List<String>> {
        val lines = chatHistory.lines().filter { it.isNotBlank() }
        val recentContactMsgs = lines.filter { it.startsWith("[对方]") }

        val summary = if (recentContactMsgs.isEmpty()) {
            "近期暂无密集互动消息，沟通处于常态。"
        } else {
            val keyPoints = recentContactMsgs.takeLast(5).map { it.removePrefix("[对方]:").trim() }
            "近期关注重点记录：\n" + keyPoints.joinToString("\n") { "• $it" }
        }

        // 以聊天记录本身为主要凭据推断隐忧与关切，而非依赖死板的分类
        val hiddenIntent = when {
            chatHistory.contains("反光") || chatHistory.contains("看不清") || chatHistory.contains("座") ->
                "对方核心焦虑在于身体视力受损，并间接担忧座位与学习受影响，言辞客气但内藏急迫期待，渴望被认真排查与当面关照。"
            chatHistory.contains("受挫") || chatHistory.contains("罚站") || chatHistory.contains("批评") ->
                "对方担心自尊心受挫、产生抵触心理，渴望获得理解与当面关怀，而非单纯被归咎责备。"
            chatHistory.contains("进度") || chatHistory.contains("什么时候") || chatHistory.contains("方案") || chatHistory.contains("评审") ->
                "对方处于交付节点压力期，迫切需要明确的时间表（ETA）与确定的结论，注重专业高效，反感模糊与拖沓。"
            recentContactMsgs.isNotEmpty() ->
                "对方在对话中表达了明确关切，期望与你保持同频协同，注重具体事实与真诚反馈。"
            else ->
                "常态交流状态，注重互相尊重与就事论事。"
        }

        val tips = mutableListOf<String>()
        tips += "【肯定关切】先接纳对方的关切与出发点，避免使用辩解或反问语气造成对立。"
        tips += "【实事求是】仅基于客观事实与实际排查情况沟通，未亲眼核实的情况绝不盲目定性定责。"
        tips += "【守住边界】可以承诺实地了解与当面沟通，但切勿擅自提前许诺未经权衡的处置结果。"
        if (taboos.isNotEmpty()) {
            tips += "【敏感注意】注意避开已知敏感点：${taboos.joinToString("；")}"
        }

        return Triple(summary, hiddenIntent, tips)
    }
}
