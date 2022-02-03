/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */

package net.azisaba.maxPlayerCounter

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.velocitypowered.api.command.CommandSource
import net.azisaba.library.velocity.command.AbstractBrigadierCommand
import net.azisaba.maxPlayerCounter.util.Util
import net.azisaba.maxPlayerCounter.util.Util.convertMonth
import net.azisaba.maxPlayerCounter.util.Util.getBeginAndEndOfMonth
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.jetbrains.annotations.Range
import util.concurrent.ref.DataCache
import util.promise.rewrite.Promise
import util.kt.promise.rewrite.*
import xyz.acrylicstyle.sql.options.FindOptions
import xyz.acrylicstyle.sql.options.InsertOptions
import xyz.acrylicstyle.sql.options.UpsertOptions
import java.util.Calendar
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

object MaxPlayerCounterCommand: AbstractBrigadierCommand() {
    private val months = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).map(Int::toString)
    private var cachedGroups = DataCache<List<String>>()
    @Volatile
    private var updatingCache = false

    override fun createBuilder(): LiteralArgumentBuilder<CommandSource> =
        literal("mpc")
            .requires { source -> source.hasPermission("maxplayercounter.command.maxplayercounter") }
            .executes { context -> executeRoot(context.source) }
            .then(literal("list")
                .executes { context -> executeList(context.source, 0) }
                .then(argument("month", IntegerArgumentType.integer(1, 12))
                    .suggests { _, builder -> suggest(months.stream(), builder) }
                    .executes { context -> executeList(context.source, IntegerArgumentType.getInteger(context, "month")) }
                )
            )
            .then(literal("show")
                .then(argument("server_or_group", StringArgumentType.string())
                    .suggests { _, builder -> suggest(getGroupsAndServers().stream(), builder) }
                    .executes { context -> executeShow(context.source, getString(context, "server_or_group"), 0) }
                    .then(argument("month", IntegerArgumentType.integer(1, 12))
                        .suggests { _, builder -> suggest(months.stream(), builder) }
                        .executes { context -> executeShow(context.source, getString(context, "server_or_group"), IntegerArgumentType.getInteger(context, "month")) }
                    )
                )
            )
            .then(literal("creategroup")
                .then(argument("name", StringArgumentType.string())
                    .executes { context -> executeCreateGroup(context.source, getString(context, "name")) }
                )
            )
            .then(literal("deletegroup")
                .then(argument("name", StringArgumentType.string())
                    .suggests { _, builder -> suggest((getGroups() ?: Collections.emptyList()).stream(), builder) }
                    .executes { context -> executeDeleteGroup(context.source, getString(context, "name")) }
                )
            )
            .then(literal("group")
                .then(argument("name", StringArgumentType.string())
                    .suggests { _, builder -> suggest((getGroups() ?: Collections.emptyList()).stream(), builder) }
                    .then(literal("add")
                        .then(argument("server", StringArgumentType.string())
                            .suggests(suggestServers())
                            .executes { context -> executeGroupAddServer(context.source, getString(context, "name"), getString(context, "server")) }
                        )
                    )
                    .then(literal("remove")
                        .then(argument("server", StringArgumentType.string())
                            .suggests(suggestServers())
                            .executes { context -> executeGroupRemoveServer(context.source, getString(context, "name"), getString(context, "server")) }
                        )
                    )
                    .then(literal("info")
                        .executes { context -> executeGroupInfo(context.source, getString(context, "name")) }
                    )
                )
            )

    private fun executeRoot(source: CommandSource): Int {
        val sendCommandUsage = { command: String, desc: String ->
            source.sendMessage(Component.text("")
                .append(Component.text("/$command ").color(NamedTextColor.AQUA))
                .append(Component.text("- ").color(NamedTextColor.GRAY))
                .append(Component.text(desc).color(NamedTextColor.GREEN)))
        }
        source.sendMessage(Component.text("----------------------------------------").color(NamedTextColor.YELLOW))
        sendCommandUsage("mpc", "this")
        sendCommandUsage("mpc list [月]", "全グループと、グループに含まれていないサーバーの指定した月の最高人数と達成日時を列挙する。月指定なしで先月を表示")
        sendCommandUsage("mpc show <グループ名/鯖名> [月]", "指定したグループ(存在しない場合はサーバー)の指定した月の最高人数と達成日時を表示する。月指定なしで先月を表示")
        sendCommandUsage("mpc creategroup <グループ名>", "グループを作成")
        sendCommandUsage("mpc deletegroup <グループ名>", "グループを削除")
        sendCommandUsage("mpc group <グループ名> add <鯖名>", "指定した鯖をグループに追加")
        sendCommandUsage("mpc group <グループ名> remove <鯖名>", "指定した鯖をグループから除外")
        sendCommandUsage("mpc group <グループ名> info", "グループに所属しているサーバーを表示")
        source.sendMessage(Component.text("----------------------------------------").color(NamedTextColor.YELLOW))
        return 0
    }

    private fun executeList(source: CommandSource, month: @Range(from = 0, to = 12) Int): Int {
        val m = if (month in 1..12) {
            max(0, min(11, month - 1))
        } else {
            val m2 = Calendar.getInstance()[Calendar.MONTH] - 1
            if (m2 < 0) m2 + 12 else m2
        }
        val cal = Calendar.getInstance()
        cal.convertMonth(m)
        val pair = cal.getBeginAndEndOfMonth()
        Promise.create<Unit>("MaxPlayerCounter Thread Pool #%d") { (resolve, _) ->
            val s = MaxPlayerCounter.instance.connection.connection.prepareStatement("SELECT * FROM `players` WHERE `timestamp` >= ? AND `timestamp` <= ?")
            s.setLong(1, pair.first)
            s.setLong(2, pair.second)
            val result = s.executeQuery()
            val map = mutableMapOf<String, MutableList<Pair<Long, Int>>>()
            while (result.next()) {
                val server = result.getString("server")
                val first = result.getLong("timestamp")
                val second = result.getInt("playerCount")
                (map.getOrPut(server) { mutableListOf() }).add(first to second)
            }
            val groups = MaxPlayerCounter.instance.connection.getAllServerGroups().complete()
            val serversNotInGroup = if (groups.isEmpty()) map else map.filterKeys { groups.values.all { l -> !l.contains(it) } }
            groups.forEach { (groupName, servers) ->
                val theServers = servers.map { it to map[it]!! }
                val timestamps = theServers.map { it.second }.flatMap { it.map { p -> p.first } }.distinct()
                var bestTS = 0L
                var bestPC = 0
                timestamps.forEach { ts ->
                    var total = 0
                    theServers.forEach { (_, list) ->
                        total += list.filter { (time) -> time <= ts }.maxByOrNull { it.first }?.second ?: 0
                    }
                    if (bestPC < total) {
                        bestTS = ts
                        bestPC = total
                    }
                }
                source.sendMessage(Component.text()
                    .append(Component.text("[").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text("G").color(NamedTextColor.GREEN))
                    .append(Component.text("] ").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text(groupName).color(NamedTextColor.GOLD))
                    .append(Component.text(": ").color(NamedTextColor.GREEN))
                    .append(Component.text("${bestPC}人 @ ${Util.formatDateTime(bestTS)}").color(NamedTextColor.YELLOW)))
            }
            serversNotInGroup.forEach { (serverName, list) ->
                if (list.isEmpty()) return@forEach
                val p = list.maxByOrNull { pair -> pair.second }!!
                source.sendMessage(Component.text()
                    .append(Component.text("[").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text("S").color(NamedTextColor.AQUA))
                    .append(Component.text("] ").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text(serverName).color(NamedTextColor.GOLD))
                    .append(Component.text(": ").color(NamedTextColor.GREEN))
                    .append(Component.text("${p.second}人 @ ${Util.formatDateTime(p.first)}").color(NamedTextColor.YELLOW)))
            }
            s.close()
            resolve(null)
        }
        return 0
    }

    private fun executeShow(source: CommandSource, server: String, month: @Range(from = 0, to = 12) Int): Int {
        val m = if (month in 1..12) {
            max(0, min(11, month - 1))
        } else {
            val m2 = Calendar.getInstance()[Calendar.MONTH] - 1
            if (m2 < 0) m2 + 12 else m2
        }
        val cal = Calendar.getInstance()
        cal.convertMonth(m)
        val pair = cal.getBeginAndEndOfMonth()
        MaxPlayerCounter.instance.connection.groups.findOne(FindOptions.Builder().addWhere("id", server).setLimit(1).build())
            .then {
                if (it == null) {
                    val registeredServer = MaxPlayerCounter.instance.server.getServer(server)?.orElse(null)?.serverInfo?.name ?: throw Exception()
                    val s = MaxPlayerCounter.instance.connection.connection.prepareStatement("SELECT `timestamp`, `playerCount` FROM `players` WHERE `timestamp` >= ? AND `timestamp` <= ? AND `server` = ?")
                    s.setLong(1, pair.first)
                    s.setLong(2, pair.second)
                    s.setString(3, registeredServer)
                    val result = s.executeQuery()
                    val list = mutableListOf<Pair<Long, Int>>()
                    while (result.next()) {
                        val first = result.getLong("timestamp")
                        val second = result.getInt("playerCount")
                        list.add(first to second)
                    }
                    s.close()
                    return@then list
                }
                val group = it.getString("id")
                val servers = MaxPlayerCounter.instance.connection.getServersByGroup(group).complete().map { server -> server.serverInfo.name }
                if (servers.isEmpty()) return@then emptyList<Pair<Long, Int>>()
                var a = ""
                servers.forEach { _ ->
                    if (a != "") a += " OR "
                    a += "`server` = ?"
                }
                val s = MaxPlayerCounter.instance.connection.connection.prepareStatement("SELECT * FROM `players` WHERE `timestamp` >= ? AND `timestamp` <= ? AND ($a)")
                s.setLong(1, pair.first)
                s.setLong(2, pair.second)
                servers.forEachIndexed { index, server ->
                    s.setString(index + 3, server)
                }
                val result = s.executeQuery()
                val map = mutableMapOf<String, MutableList<Pair<Long, Int>>>()
                while (result.next()) {
                    val serverName = result.getString("server")
                    val timestamp = result.getLong("timestamp")
                    val playerCount = result.getInt("playerCount")
                    map.getOrPut(serverName) { mutableListOf() }.add(timestamp to playerCount)
                }
                val timestamps = map.values.flatMap { l -> l.map { p -> p.first } }.distinct()
                var epicTS = 0L
                var epicPC = 0
                timestamps.forEach { ts ->
                    var total = 0
                    map.keys.forEach { server ->
                        total += map[server]!!.filter { (time) -> time <= ts }.maxByOrNull { p -> p.first }?.second ?: 0
                    }
                    if (epicPC < total) {
                        epicTS = ts
                        epicPC = total
                    }
                }
                s.close()
                return@then listOf(epicTS to epicPC)
            }
            .then { list ->
                if (list.isEmpty()) return@then source.sendMessage(Component.text("データがありません。").color(NamedTextColor.RED))
                val p = list.maxByOrNull { it.second }!!
                source.sendMessage(Component.text()
                    .append(Component.text(server).color(NamedTextColor.GOLD))
                    .append(Component.text(":").color(NamedTextColor.GREEN)))
                source.sendMessage(Component.text()
                    .append(Component.text("- 最大人数: ").color(NamedTextColor.GOLD))
                    .append(Component.text(p.second).color(NamedTextColor.AQUA)))
                source.sendMessage(Component.text()
                    .append(Component.text("- 達成日時: ").color(NamedTextColor.GOLD))
                    .append(Component.text(Util.formatDateTime(p.first)).color(NamedTextColor.AQUA)))
            }
            .catch {
                if (it::class.java == Exception::class.java) {
                    source.sendMessage(Component.text("サーバーが見つかりません。").color(NamedTextColor.RED))
                    return@catch
                }
                it.printStackTrace()
            }
        return 0
    }

    private fun executeCreateGroup(source: CommandSource, groupName: String): Int {
        if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
            source.sendMessage(Component.text("指定されたグループ名は使用できません。").color(NamedTextColor.RED))
            return 0
        }
        MaxPlayerCounter.instance.connection.getAllGroups()
            .then { list ->
                if (list.any { it.equals(groupName, true) }) {
                    source.sendMessage(Component.text("この名前はすでに使用されています。").color(NamedTextColor.RED))
                    throw Exception()
                }
            }
            .then(MaxPlayerCounter.instance.connection.groups.insert(InsertOptions.Builder().addValue("id", groupName).build()))
            .then {
                source.sendMessage(Component.text()
                    .append(Component.text("グループ「").color(NamedTextColor.GREEN))
                    .append(Component.text(groupName).color(NamedTextColor.GOLD))
                    .append(Component.text("」を作成しました。").color(NamedTextColor.GREEN)))
            }
            .catch {
                if (it::class.java == Exception::class.java) return@catch
                source.sendMessage(Component.text("グループの作成に失敗しました。").color(NamedTextColor.RED))
                it.printStackTrace()
            }
        return 0
    }

    private fun executeDeleteGroup(source: CommandSource, groupName: String): Int {
        if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
            source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
            return 0
        }
        MaxPlayerCounter.instance.connection.getAllGroups()
            .then { list ->
                if (!list.any { it == groupName }) {
                    source.sendMessage(Component.text("指定されたグループは存在しません。").color(NamedTextColor.RED))
                    throw Exception()
                }
            }
            .then(MaxPlayerCounter.instance.connection.groups.delete(FindOptions.Builder().addWhere("id", groupName).build()))
            .then(MaxPlayerCounter.instance.connection.serverGroup.delete(FindOptions.Builder().addWhere("group", groupName).build()))
            .then {
                source.sendMessage(Component.text()
                    .append(Component.text("グループ「").color(NamedTextColor.GREEN))
                    .append(Component.text(groupName).color(NamedTextColor.GOLD))
                    .append(Component.text("」を削除しました。").color(NamedTextColor.GREEN)))
            }
            .catch {
                if (it::class.java == Exception::class.java) return@catch
                source.sendMessage(Component.text("グループの削除に失敗しました。").color(NamedTextColor.RED))
                it.printStackTrace()
            }
        return 0
    }

    private fun executeGroupAddServer(source: CommandSource, groupName: String, serverName: String): Int {
        if (!MaxPlayerCounter.instance.server.allServers.map { it.serverInfo.name }.any { it == serverName }) {
            source.sendMessage(Component.text("無効なサーバー名です。").color(NamedTextColor.RED))
            return 0
        }
        if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
            source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
            return 0
        }
        MaxPlayerCounter.instance.connection.getAllGroups()
            .then { list ->
                if (!list.any { it == groupName }) {
                    return@then source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
                }
                MaxPlayerCounter.instance.connection.serverGroup.upsert(
                    UpsertOptions.Builder()
                        .addWhere("server", serverName)
                        .addValue("group", groupName)
                        .addValue("server", serverName)
                        .build()
                ).complete()
                source.sendMessage(Component.text("グループにサーバー($serverName)を追加しました。").color(NamedTextColor.GREEN))
            }
        return 0
    }

    private fun executeGroupRemoveServer(source: CommandSource, groupName: String, serverName: String): Int {
        if (!MaxPlayerCounter.instance.server.allServers.map { it.serverInfo.name }.any { it == serverName }) {
            source.sendMessage(Component.text("無効なサーバー名です。").color(NamedTextColor.RED))
            return 0
        }
        if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
            source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
            return 0
        }
        MaxPlayerCounter.instance.connection.getAllGroups()
            .then { list ->
                if (!list.any { it == groupName }) {
                    return@then source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
                }
                MaxPlayerCounter.instance.connection.serverGroup.delete(
                    FindOptions.Builder()
                        .addWhere("group", groupName)
                        .addWhere("server", serverName)
                        .build()
                ).complete()
                source.sendMessage(Component.text("グループにサーバー($serverName)を(そのグループに入っている場合は)除外しました。").color(NamedTextColor.GREEN))
            }
        return 0
    }

    private fun executeGroupInfo(source: CommandSource, groupName: String): Int {
        if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
            source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
            return 0
        }
        MaxPlayerCounter.instance.connection.getAllGroups()
            .then { list ->
                if (!list.any { it == groupName }) {
                    return@then source.sendMessage(Component.text("無効なグループ名です。").color(NamedTextColor.RED))
                }
                val servers = MaxPlayerCounter.instance.connection.getServersByGroup(groupName).complete()
                source.sendMessage(Component.text()
                    .append(Component.text("グループ: ").color(NamedTextColor.AQUA))
                    .append(Component.text(groupName)))
                source.sendMessage(Component.text()
                    .append(Component.text(" "))
                    .append(Component.text(" "))
                    .append(Component.text("サーバー:").color(NamedTextColor.AQUA)))
                servers.forEach { server ->
                    source.sendMessage(Component.text()
                        .append(Component.text(" "))
                        .append(Component.text(" "))
                        .append(Component.text(" - "))
                        .append(Component.text(server.serverInfo.name).color(NamedTextColor.GREEN)))
                }
            }
        return 0
    }

    private fun getGroupsAndServers(): List<String> {
        val servers = MaxPlayerCounter.instance.server.allServers.map { it.serverInfo.name }.toMutableList()
        val groups = getGroups() ?: return servers
        return servers.apply { addAll(groups) }.distinct()
    }

    private fun getGroups(): List<String>? {
        val groups = cachedGroups.get()
        if (groups == null || cachedGroups.ttl - System.currentTimeMillis() < 10000) { // update if cache is expired or expiring in under 10 seconds
            if (!updatingCache) {
                updatingCache = true
                MaxPlayerCounter.instance.connection.getAllGroups().then {
                    cachedGroups = DataCache(it, System.currentTimeMillis() + 1000 * 60)
                    updatingCache = false
                }
            }
        }
        return groups
    }
}
