package com.emberlily.app.fe

import java.util.Collections

enum class FeCapability {
    CHAPTER_AND_CURSOR, LIVE_UNITS, ITEM_DATA, TEXT_NAMES, NORMAL_INVENTORY, RESEARCH_EDIT,
}

enum class SupportState { UNVERIFIED, AVAILABLE, UNSUPPORTED }

data class CapabilitySupport(val state: SupportState, val explanation: String, val evidence: String? = null) {
    init {
        require(explanation.isNotBlank())
        require(state != SupportState.AVAILABLE || !evidence.isNullOrBlank()) { "Available capabilities require evidence" }
    }
}

data class RomIdentity(
    val sha256: String,
    val size: Int,
    val headerTitle: String,
    val gameCode: String,
    val label: String,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(sha256))
        require(size in 0xC0..(32 * 1024 * 1024))
        require(headerTitle.length <= 12 && gameCode.length == 4 && label.isNotBlank())
    }
}

/** Recognition, decoding and commands are separate capabilities. A known header never enables an adapter. */
class RomProfile(
    val id: String,
    val version: Int,
    val identity: RomIdentity,
    capabilities: Map<FeCapability, CapabilitySupport>,
    val layout: FeLayout = FeLayouts.fe8j,
    val nameEncoding: FeNameEncoding = FeNameEncoding.SHIFT_JIS,
) {
    val capabilities: Map<FeCapability, CapabilitySupport> = Collections.unmodifiableMap(capabilities.toMap())
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(id) && version > 0)
        require(this.capabilities.keys == FeCapability.entries.toSet()) { "Every capability needs an explicit status" }
    }
    fun supports(capability: FeCapability): Boolean = capabilities.getValue(capability).state == SupportState.AVAILABLE
}

object RomProfiles {
    const val FE8J_SHA256 = "44fd343625ab9e6b90f63a80758c15066d526e6873fae91474006314a5ead464"
    const val FE8J_VOICE_SHA256 = "4853c904c1c180f837678f3d0032dbbb86049bc710fb1993fb51bad8c49b1076"
    const val FE7J_SHA256 = "7ec691a7411864ab51765028156c5b8a3c23a317d23c95fbf07ac201212e25c0"
    const val FE7J_CHINESE_SHA256 = "828b8bb1bff54a66b89cb1e43901d88bb783a4da6e543ac31aa6edc17e162d25"
    private fun pending() = FeCapability.entries.associateWith {
        CapabilitySupport(SupportState.UNVERIFIED, "文件已登记，当前能力尚待该构建的布局与运行验证", "docs/M0_TARGETS.md")
    }
    private fun available(explanation: String) = CapabilitySupport(SupportState.AVAILABLE, explanation, "docs/FE8J_EVIDENCE.md; docs/M1_VALIDATION.md")
    private fun fe7Available(explanation: String) = CapabilitySupport(SupportState.AVAILABLE, explanation,
        "docs/FE7J_EVIDENCE.md; docs/M2_VALIDATION.md")
    private fun fe7Runtime() = mapOf(
        FeCapability.CHAPTER_AND_CURSOR to fe7Available("已核验本构建序章标题、对话、选择与玩家空闲样本；尚非全部章节/路线"),
        FeCapability.LIVE_UNITS to fe7Available("已核验本构建序章琳及敌军的部署槽、坐标与属性；其他角色和特殊状态仍待验证"),
        FeCapability.ITEM_DATA to fe7Available("本构建静态物品表及序章单位物品原值已核验；正常背包操作尚未开放"),
        FeCapability.RESEARCH_EDIT to fe7Available("仅实验分支 HP 1..最大 HP；序章玩家空闲的编辑、重开、恢复与主进度隔离已验证"),
    )

    val fe8j = RomProfile("fe8j-r03", 1, RomIdentity(
        FE8J_SHA256, 16_777_216, "FIREEMBLEM8", "BE8J", "圣魔之光石（日版 R03）",
    ), pending() + mapOf(
        FeCapability.ITEM_DATA to available("静态物品表与物品 1 样本已核验；运行背包和全部场景尚待该构建验证"),
        FeCapability.TEXT_NAMES to available("原版 Huffman 名称短样本已核验；控制符及非名称文本仍可能未解析"),
    ))
    val fe8jVoice = RomProfile("fe8j-voice-r04", 1, RomIdentity(
        FE8J_VOICE_SHA256, 17_928_212, "FIREEMBLEM8", "BE8J", "圣魔之光石（中文语音 2.0 R04）",
    ), pending() + mapOf(
        FeCapability.CHAPTER_AND_CURSOR to available("序章开场、移动选择和玩家空闲样本的章节、回合与光标已核验；尚非全部章节/场景"),
        FeCapability.LIVE_UNITS to available("序章样本中 2 名我方、3 名敌方的部署槽、坐标与 HP 已核验；尚无 NPC、救出、死亡及切章样本"),
        FeCapability.ITEM_DATA to available("静态物品表及序章 5 名单位物品原值已核验；中文名称、全部物品规则与背包操作仍待适配"),
        FeCapability.TEXT_NAMES to CapabilitySupport(SupportState.UNVERIFIED, "此中文构建采用自定义字库；仅显示名称 ID，不能套用日版名称", "docs/FE8J_EVIDENCE.md"),
        FeCapability.RESEARCH_EDIT to available("仅 HP 1..最大 HP 的实验分支编辑；已验证序章玩家空闲时编辑、重开、恢复及主进度隔离，其他字段/章节尚待验证"),
    ), nameEncoding = FeNameEncoding.UNVERIFIED_CUSTOM)

    val fe7j = RomProfile("fe7j-r02", 1, RomIdentity(
        FE7J_SHA256, 16_777_216, "FIREEMBLEM7", "AE7J", "烈火之剑（日版 R02）",
    ), pending() + fe7Runtime() + mapOf(FeCapability.TEXT_NAMES to fe7Available(
        "日版 Huffman 名称与实时琳/职业/武器短样本已核验，控制符及长文本仍可能未解析")), layout = FeLayouts.fe7j)
    val fe7jChinese = RomProfile("fe7j-chinese-r06", 1, RomIdentity(
        FE7J_CHINESE_SHA256, 16_777_216, "FIREEMBLEM7", "AE7J", "烈火之剑（狼组／火花汉化 R06）",
    ), pending() + fe7Runtime() + mapOf(FeCapability.TEXT_NAMES to CapabilitySupport(SupportState.UNVERIFIED,
        "此中文构建的名称编码尚未核验；仅显示名称 ID", "docs/FE7J_EVIDENCE.md")),
        layout = FeLayouts.fe7j, nameEncoding = FeNameEncoding.UNVERIFIED_CUSTOM)

    val all: List<RomProfile> = Collections.unmodifiableList(listOf(fe8j, fe8jVoice, fe7j, fe7jChinese))

    fun identify(sha256: String): RomProfile? = all.firstOrNull { it.identity.sha256 == sha256.lowercase(java.util.Locale.ROOT) }
}
