package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.MuxStrategy
import io.nekohasekai.sagernet.MuxType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.RuleProvider
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.direct.DirectBean
import io.nekohasekai.sagernet.fmt.direct.buildSingBoxOutboundDirectBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireGuardBean
import io.nekohasekai.sagernet.ktx.asMap
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.Libcore
import moe.matsuri.nb4a.RuleItem
import moe.matsuri.nb4a.SingBoxOptions.BrutalOptions
import moe.matsuri.nb4a.SingBoxOptions.CacheFileOptions
import moe.matsuri.nb4a.SingBoxOptions.ClashAPIOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSFakeIPOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import moe.matsuri.nb4a.SingBoxOptions.ExperimentalOptions
import moe.matsuri.nb4a.SingBoxOptions.LogOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.NTPOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.SingBoxOptions.User
import moe.matsuri.nb4a.SingBoxOptionsUtil
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_Default
import moe.matsuri.nb4a.SingBoxOptions.Inbound_DirectOptions
import moe.matsuri.nb4a.SingBoxOptions.Inbound_HTTPMixedOptions
import moe.matsuri.nb4a.SingBoxOptions.Inbound_TunOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundMultiplexOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SelectorOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SocksOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_Default
import moe.matsuri.nb4a.SingBoxOptions.V2RayAPIOptions
import moe.matsuri.nb4a.SingBoxOptions.V2RayStatsServiceOptions
import moe.matsuri.nb4a.buildRuleSets
import moe.matsuri.nb4a.checkEmpty
import moe.matsuri.nb4a.makeCommonRule
import moe.matsuri.nb4a.parseRules
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

// Inbound
const val TAG_MIXED = "mixed-in"
const val TAG_TUN = "tun-in"
const val TAG_DNS_IN = "dns-in"

// Outbound
const val TAG_ANY = "any" // "special for outbound domain"
const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BLOCK = "block"
const val TAG_DNS_OUT = "dns-out"

// DNS
const val TAG_DNS_REMOTE = "dns-remote"
const val TAG_DNS_DIRECT = "dns-direct"
const val TAG_DNS_BLOCK = "dns-block"
const val TAG_DNS_LOCAL = "dns-local"
const val TAG_DNS_FAKE = "dns-fake"

const val LOCALHOST4 = "127.0.0.1"

// Note: You shouldn't set strategy and detour for "local"
const val LOCAL_DNS_SERVER = "local"

val FAKE_DNS_QUERY_TYPE: List<String> = listOf("A", "AAAA")

val ERR_NO_REMOTE_DNS by lazy { Exception("No remote DNS, check your settings!") }
val ERR_NO_DIRECT_DNS by lazy { Exception("No direct DNS, check your settings!") }

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false,
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == ConfigBean.TYPE_CONFIG) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id,
                mapOf(TAG_PROXY to listOf(proxy)),
                mapOf(proxy.id to TAG_PROXY),
                -1L,
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    val optionsToMerge = proxy.requireBean().customConfigJson ?: ""

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun selectorName(name: String): String {
        var newName = name
        var count = 0
        while (selectorNames.contains(newName)) {
            count++
            newName = "$name-$count"
        }
        selectorNames.add(newName)
        return newName
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_Default>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST4
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns by lazy { DataStore.enableFakeDns && !forTest }
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        if (!forTest) experimental = ExperimentalOptions().apply {
            if (!forExport) v2ray_api = V2RayAPIOptions().apply {
                listen = "$LOCALHOST4:0" // Never really listen
                stats = V2RayStatsServiceOptions().also {
                    it.enabled = true
                    it.outbounds = tagMap.values.toMutableList().also { list ->
                        list.add(TAG_PROXY)
                        list.add(TAG_DIRECT)
                    }
                }
            }
            clash_api = ClashAPIOptions().apply {
                external_controller = DataStore.clashAPIListen.ifBlank {
                    "$LOCALHOST4:${mkPort()}"
                }
                default_mode = RuleEntity.MODE_RULE
            }
            cache_file = CacheFileOptions().apply {
                enabled = true
                store_fakeip = true
                path = "../cache/cache.db"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "fatal"
                2 -> "error"
                3 -> "warn"
                4 -> "info"
                5 -> "debug"
                6 -> "trace"
                else -> "info"
            }
        }

        if (DataStore.ntpEnable) ntp = NTPOptions().apply {
            enabled = true
            server = DataStore.ntpAddress
            server_port = DataStore.ntpPort
            interval = DataStore.ntpInterval
            detour = TAG_DIRECT

            if (!server.isIpAddress()) {
                domainListDNSDirectForce.add("full:$server")
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = TAG_TUN
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                sniff = needSniff
                sniff_override_destination = needSniffOverride
                when (ipv6Mode) {
                    IPv6Mode.DISABLE -> {
                        address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    }

                    IPv6Mode.ONLY -> {
                        address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }

                    else -> {
                        address = listOf(
                            VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                            VpnService.PRIVATE_VLAN6_CLIENT + "/126",
                        )
                    }
                }
            })
            inbounds.add(Inbound_HTTPMixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                sniff = needSniff
                sniff_override_destination = needSniffOverride
                if (DataStore.inboundUsername.isNotBlank() || DataStore.inboundPassword.isNotBlank()) {
                    users = listOf(
                        User().apply {
                            username = DataStore.inboundUsername
                            password = DataStore.inboundPassword
                        }
                    )
                }
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity,
        ): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                addAll(profileList)
                add(entity)
            }

            var currentOutbound = mutableMapOf<String, Any>()
            lateinit var pastOutbound: MutableMap<String, Any>
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<MutableMap<String, Any>>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // For: test but not interrupt VPN service
                val outboundProtect =
                    forTest && !proxyEntity.needExternal() && DataStore.serviceState.started

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans.add(proxyEntity.requireBean())
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(bean.displayName())
                }


                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_Default().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST4
                        server_port = localPort
                    }.asMap()
                } else { // internal outbound
                    currentOutbound = when (bean) {
                        is ConfigBean -> gson.fromJson(bean.config, currentOutbound.javaClass)

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean).asMap()

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean).asMap()

                        is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean).asMap()

                        is TuicBean -> buildSingBoxOutboundTuicBean(bean).asMap()

                        is SOCKSBean -> buildSingBoxOutboundSocksBean(bean).asMap()

                        is ShadowsocksBean -> buildSingBoxOutboundShadowsocksBean(bean).asMap()

                        is WireGuardBean -> buildSingBoxOutboundWireGuardBean(bean).asMap()

                        is SSHBean -> buildSingBoxOutboundSSHBean(bean).asMap()

                        is DirectBean -> buildSingBoxOutboundDirectBean(bean).asMap()

                        else -> throw IllegalStateException("can't reach")
                    }

                    currentOutbound.apply {
//                        val keepAliveInterval = DataStore.tcpKeepAliveInterval
//                        val needKeepAliveInterval = keepAliveInterval !in intArrayOf(0, 15)

                        if (outboundProtect) {
                            this["protect_path"] = Libcore.ProtectPath
                        }

                        if (!muxApplied && proxyEntity.needCoreMux()) {
                            muxApplied = true
                            this["multiplex"] = OutboundMultiplexOptions().apply {
                                enabled = true
                                padding = bean.serverMuxPadding
                                protocol = when (bean.serverMuxType) {
                                    MuxType.H2MUX -> "h2mux"
                                    MuxType.SMUX -> "smux"
                                    MuxType.YAMUX -> "yamux"
                                    else -> throw IllegalArgumentException()
                                }
                                if (bean.serverBrutal) {
                                    max_connections = 1
                                    brutal = BrutalOptions().apply {
                                        enabled = true
                                        up_mbps = -1 // need kernel module
                                        down_mbps = DataStore.downloadSpeed
                                    }
                                } else when (bean.serverMuxStrategy) {
                                    MuxStrategy.MAX_CONNECTIONS -> {
                                        max_connections = bean.serverMuxNumber
                                    }

                                    MuxStrategy.MIN_STREAMS -> min_streams = bean.serverMuxNumber

                                    MuxStrategy.MAX_STREAMS -> max_streams = bean.serverMuxNumber

                                    else -> throw IllegalStateException()
                                }
                            }.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT == true) {
                            this["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // domain_strategy
                    this["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    // custom JSON merge
                    if (bean.customOutboundJson.isNotBlank()) {
                        Util.mergeJSON(bean.customOutboundJson, currentOutbound)
                    }
                }

                currentOutbound["tag"] = tagOut

                // External proxy need a direct inbound to forward the traffic
                // For external proxy software, their traffic must goes to sing-box to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    val mappingPort = mkPort()
                    bean.finalAddress = LOCALHOST4
                    bean.finalPort = mappingPort

                    inbounds.add(Inbound_DirectOptions().apply {
                        type = "direct"
                        listen = LOCALHOST4
                        listen_port = mappingPort
                        tag = "$chainTag-mapping-${proxyEntity.id}"

                        override_address = bean.serverAddress
                        override_port = bean.serverPort

                        pastInboundTag = tag

                        // no chain rule and not outbound, so need to set to direct
                        if (index == profileList.lastIndex) {
                            route.rules.add(Rule_Default().apply {
                                inbound = listOf(tag)
                                outbound = TAG_DIRECT
                            })
                        }
                    })
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group?.id?.let { SagerDatabase.proxyDao.getByGroup(it) }
            list?.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
                interrupt_exist_connections = DataStore.interruptSelector
            }.asMap())
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList = rule.packages.map {
                if (!isVPN) {
                    Toast.makeText(
                        SagerNet.application,
                        SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()

            val ruleObj = Rule_Default().apply {
                if (uidList.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList
                }
                var domainList: List<RuleItem> = listOf()
                if (rule.domains.isNotBlank()) {
                    domainList = parseRules(rule.domains.listByLineOrComma())
                    makeCommonRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeCommonRule(parseRules(rule.ip.listByLineOrComma()), true)
                }
                if (rule.port.isNotBlank()) {
                    port = mutableListOf()
                    port_range = mutableListOf()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf()
                    source_port_range = mutableListOf()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    val sourceIPs = mutableListOf<String>()
                    for (source in rule.source.listByLineOrComma()) {
                        if (source == RuleItem.CONTENT_PRIVATE) {
                            source_ip_is_private = true
                        } else {
                            sourceIPs.add(source)
                        }
                    }
                    if (sourceIPs.isNotEmpty()) source_ip_cidr = sourceIPs
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }
                if (rule.ssid.isNotBlank()) {
                    wifi_ssid = rule.ssid.listByLineOrComma()
                }
                if (rule.bssid.isNotBlank()) {
                    wifi_bssid = rule.bssid.listByLineOrComma()
                }
                if (rule.clientType.isNotBlank()) {
                    client = rule.clientType.listByLineOrComma()
                }
                if (rule.clashMode.isNotBlank()) {
                    clash_mode = rule.clashMode
                }

                fun makeDnsRuleObj(): DNSRule_Default {
                    return DNSRule_Default().apply {
                        if (uidList.isNotEmpty()) user_id = uidList
                        val ips = parseRules(rule.ip.listByLineOrComma()).filter {
                            it.dns
                        }
                        makeCommonRule(domainList + ips)
                    }
                }

                when (val outID = rule.outbound) {
                    RuleEntity.OUTBOUND_DIRECT -> {
                        userDNSRuleList.add(makeDnsRuleObj().apply {
                            server = TAG_DNS_DIRECT
                        })
                        outbound = TAG_DIRECT
                    }

                    RuleEntity.OUTBOUND_PROXY -> {
                        if (useFakeDns) userDNSRuleList.add(makeDnsRuleObj().apply {
                            server = TAG_DNS_FAKE
                            inbound = listOf(TAG_TUN)
                            query_type = FAKE_DNS_QUERY_TYPE
                        })
                        userDNSRuleList.add(makeDnsRuleObj().apply {
                            server = TAG_DNS_REMOTE
                        })
                        outbound = TAG_PROXY
                    }

                    RuleEntity.OUTBOUND_BLOCK -> {
                        userDNSRuleList.add(makeDnsRuleObj().apply {
                            server = TAG_DNS_BLOCK
                            disable_cache = true
                        })
                        outbound = TAG_BLOCK
                    }

                    else -> outbound = if (outID == proxy.id) {
                        TAG_PROXY
                    } else {
                        tagMap[outID] ?: ""
                    }
                }
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    route.rules.add(ruleObj)
                }
            }
        }

        outbounds.add(Outbound().apply {
            tag = TAG_DIRECT
            type = "direct"
        }.asMap())

        outbounds.add(Outbound().apply {
            tag = TAG_BLOCK
            type = TAG_BLOCK
        }.asMap())

        if (!forTest) {
            inbounds.add(0, Inbound_DirectOptions().apply {
                type = "direct"
                tag = TAG_DNS_IN
                listen = bind
                listen_port = DataStore.localDNSPort
                override_address = "8.8.8.8"
                override_port = 53
            })

            outbounds.add(Outbound().apply {
                type = "dns"
                tag = TAG_DNS_OUT
            }.asMap())
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            try {
                Libcore.parseURL("https://$address").apply {
                    if (!host.isIpAddress()) {
                        domainListDNSDirectForce.add("full:$host")
                    }
                }
            } catch (_: Exception) {
            }
        }

        // remote dns obj
        remoteDns.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw ERR_NO_REMOTE_DNS
                tag = TAG_DNS_REMOTE
                address_resolver = TAG_DNS_DIRECT
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
                detour = TAG_PROXY

                if (DataStore.ednsClientSubnet.isNotBlank()) {
                    client_subnet = DataStore.ednsClientSubnet
                }
            })
        }

        // add directDNS objects here
        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw ERR_NO_DIRECT_DNS
                tag = TAG_DNS_DIRECT
                if (address != LOCAL_DNS_SERVER) {
                    detour = TAG_DIRECT
                    address_resolver = TAG_DNS_LOCAL
                }
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))

                if (DataStore.ednsClientSubnet.isNotBlank()) {
                    client_subnet = DataStore.ednsClientSubnet
                }
            })
        }

        // underlyingDns
        dns.servers.add(DNSServerOptions().apply {
            address = LOCAL_DNS_SERVER
            tag = TAG_DNS_LOCAL
        })

        dns.servers.add(DNSServerOptions().apply {
            address = "rcode://success"
            tag = TAG_DNS_BLOCK
        })

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            // Always use system DNS for urlTest
            dns.servers = listOf(
                DNSServerOptions().apply {
                    address = LOCAL_DNS_SERVER
                    tag = TAG_DNS_LOCAL
                }
            )
            dns.rules = mutableListOf()
        } else {
            // clash mode
            route.rules.add(0, Rule_Default().apply {
                clash_mode = RuleEntity.MODE_GLOBAL
                outbound = TAG_PROXY
            })
            route.rules.add(0, Rule_Default().apply {
                clash_mode = RuleEntity.MODE_DIRECT
                outbound = TAG_DIRECT
            })
            route.rules.add(0, Rule_Default().apply {
                clash_mode = RuleEntity.MODE_BLOCK
                outbound = TAG_BLOCK
            })

            // built-in DNS rules
            route.rules.add(0, Rule_Default().apply {
                inbound = listOf(TAG_DNS_IN)
                outbound = TAG_DNS_OUT
            })
            route.rules.add(0, Rule_Default().apply {
                port = listOf(53)
                outbound = TAG_DNS_OUT
            })

            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_Default().apply {
                    outbound = TAG_DIRECT
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_Default().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                outbound = TAG_BLOCK
            })

            // FakeDNS obj
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                }
                dns.servers.add(DNSServerOptions().apply {
                    address = "fakeip"
                    tag = "dns-fake"
                })
                dns.rules.add(DNSRule_Default().apply {
                    inbound = listOf(TAG_TUN)
                    server = TAG_DNS_FAKE
                    disable_cache = true
                    query_type = FAKE_DNS_QUERY_TYPE
                })
            }

            // clash mode
            dns.rules.add(0, DNSRule_Default().apply {
                clash_mode = RuleEntity.MODE_GLOBAL
                server = TAG_DNS_REMOTE
            })
            dns.rules.add(0, DNSRule_Default().apply {
                clash_mode = RuleEntity.MODE_DIRECT
                server = TAG_DNS_DIRECT
            })
            dns.rules.add(0, DNSRule_Default().apply {
                clash_mode = RuleEntity.MODE_BLOCK
                server = TAG_DNS_BLOCK
            })
            // force bypass (always top DNS rule)
            dns.rules.add(0, DNSRule_Default().apply {
                outbound = listOf(TAG_ANY)
                server = TAG_DNS_DIRECT
            })
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_Default().apply {
                    makeCommonRule(
                        parseRules(domainListDNSDirectForce.distinct()),
                    )
                    server = TAG_DNS_DIRECT
                })
            }
        }
        if (!forTest) dns.final_ = TAG_DNS_REMOTE

        var ruleSetResource: String? = null
        var geositeLink: String? = null
        var geoipLink: String? = null
        if (forExport) {
            // "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"
            val pathPrefix = "https://raw.githubusercontent.com"
            val provider = DataStore.rulesProvider

            val normalBranch = "rule-set"
            val geoipBranch = normalBranch
            val geositeBranch = if (RuleProvider.hasUnstableBranch(provider)) {
                "rule-set-unstable"
            } else {
                normalBranch
            }

            when (provider) {
                RuleProvider.OFFICIAL -> {
                    geositeLink = "$pathPrefix/SagerNet/sing-geosite/$geositeBranch"
                    geoipLink = "$pathPrefix/SagerNet/sing-geoip/$geoipBranch"
                }

                RuleProvider.LOYALSOLDIER -> {
                    geositeLink = "$pathPrefix/xchacha20-poly1305/sing-geosite/$geositeBranch"
                    geoipLink = "$pathPrefix/xchacha20-poly1305/sing-geoip/$geoipBranch"
                }

                RuleProvider.CHOCOLATE4U -> {
                    geositeLink = "$pathPrefix/Chocolate4U/sing-geosite/$geositeBranch"
                    geoipLink = "$pathPrefix/Chocolate4U/sing-geoip/$geoipBranch"
                }

                RuleProvider.CUSTOM -> {} // Can't generate.
            }
        }
        if (geositeLink == null) {
            ruleSetResource = SagerNet.application.externalAssets.absolutePath + "/geo"
        }
        buildRuleSets(geoipLink, geositeLink, ruleSetResource)
    }.let {
        ConfigBuildResult(
            gson.toJson(it.asMap().apply {
                Util.mergeJSON(optionsToMerge, this)
            }),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group!!.id else -1L
        )
    }

}
