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

package xyz.acrylicstyle.maxPlayerCounter

import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.TabExecutor
import util.kt.promise.rewrite.catch
import util.kt.promise.rewrite.component1
import util.kt.promise.rewrite.component2
import util.promise.rewrite.Promise
import util.ref.DataCache
import xyz.acrylicstyle.maxPlayerCounter.util.Util
import xyz.acrylicstyle.maxPlayerCounter.util.Util.convertMonth
import xyz.acrylicstyle.maxPlayerCounter.util.Util.getBeginAndEndOfMonth
import xyz.acrylicstyle.maxPlayerCounter.util.Util.send
import xyz.acrylicstyle.sql.options.FindOptions
import xyz.acrylicstyle.sql.options.InsertOptions
import xyz.acrylicstyle.sql.options.UpsertOptions
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

object MaxPlayerCounterCommand: Command("maxplayercounter", "maxplayercounter.command.maxplayercounter", "mpc"), TabExecutor {
    private val commands = listOf("list", "show", "creategroup", "deletegroup", "group", "g")
    private val groupCommands = listOf("add", "remove", "info")
    private val list12 = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).map { it.toString() }
    private var cachedGroups = DataCache<List<String>>()
    @Volatile
    private var updatingCache = false

    private fun CommandSender.sendHelp() {
        send("${ChatColor.YELLOW}----------------------------------------")
        send("${ChatColor.AQUA}/mpc ${ChatColor.GRAY}- ${ChatColor.GREEN}this")
        send("${ChatColor.AQUA}/mpc list [月] ${ChatColor.GRAY}- ${ChatColor.GREEN}全グループと、グループに含まれていないサーバーの指定した月の最高人数と達成日時を列挙する。月指定なしで先月を表示")
        send("${ChatColor.AQUA}/mpc show <グループ名/鯖名> [月] ${ChatColor.GRAY}- ${ChatColor.GREEN}指定したグループ(存在しない場合はサーバー)の指定した月の最高人数と達成日時を表示する。月指定なしで先月を表示")
        send("${ChatColor.AQUA}/mpc creategroup <グループ名> ${ChatColor.GRAY}- ${ChatColor.GREEN}グループを作成")
        send("${ChatColor.AQUA}/mpc deletegroup <グループ名> ${ChatColor.GRAY}- ${ChatColor.GREEN}グループを削除")
        send("${ChatColor.AQUA}/mpc group <グループ名> add <鯖名> ${ChatColor.GRAY}- ${ChatColor.GREEN}指定した鯖をグループに追加")
        send("${ChatColor.AQUA}/mpc group <グループ名> remove <鯖名> ${ChatColor.GRAY}- ${ChatColor.GREEN}指定した鯖をグループから除外")
        send("${ChatColor.AQUA}/mpc group <グループ名> info ${ChatColor.GRAY}- ${ChatColor.GREEN}グループに所属しているサーバーを表示")
        send("${ChatColor.YELLOW}----------------------------------------")
    }

    override fun execute(sender: CommandSender, args: Array<String>) {
        if (args.isEmpty()) return sender.sendHelp()
        when (args[0]) {
            "list" -> {
                val m = try {
                    max(0, min(11, Integer.parseInt(args[1]) - 1))
                } catch (e: RuntimeException) {
                    val m2 = Calendar.getInstance()[Calendar.MONTH] - 1
                    if (m2 < 0) m2 + 12 else m2
                }
                val cal = Calendar.getInstance()
                cal.convertMonth(m)
                val pair = cal.getBeginAndEndOfMonth()
                Promise.create<Unit> { (resolve, _) ->
                    val s = MaxPlayerCounter.getPlugin().connection.connection.prepareStatement("SELECT * FROM `players` WHERE `timestamp` >= ? AND `timestamp` <= ?")
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
                    val groups = MaxPlayerCounter.getPlugin().connection.getAllServerGroups().complete()
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
                        sender.send("${ChatColor.DARK_GRAY}[${ChatColor.GREEN}G${ChatColor.DARK_GRAY}] ${ChatColor.GOLD}${groupName}${ChatColor.GREEN}: ${ChatColor.YELLOW}${bestPC}人 @ ${Util.formatDateTime(bestTS)}")
                    }
                    serversNotInGroup.forEach { (serverName, list) ->
                        if (list.isEmpty()) return@forEach
                        val p = list.maxByOrNull { pair -> pair.second }!!
                        sender.send("${ChatColor.DARK_GRAY}[${ChatColor.AQUA}S${ChatColor.DARK_GRAY}] ${ChatColor.GOLD}${serverName}${ChatColor.GREEN}: ${ChatColor.YELLOW}${p.second}人 @ ${Util.formatDateTime(p.first)}")
                    }
                    s.close()
                    resolve(null)
                }
            }
            "show" -> {
                if (args.size <= 1) return sender.sendHelp()
                val m = try {
                    max(0, min(11, Integer.parseInt(args[2]) - 1))
                } catch (e: RuntimeException) {
                    val m2 = Calendar.getInstance()[Calendar.MONTH] - 1
                    if (m2 < 0) m2 + 12 else m2
                }
                val cal = Calendar.getInstance()
                cal.convertMonth(m)
                val pair = cal.getBeginAndEndOfMonth()
                MaxPlayerCounter.getPlugin().connection.groups.findOne(FindOptions.Builder().addWhere("id", args[1]).setLimit(1).build())
                    .then {
                        if (it == null) {
                            val server = ProxyServer.getInstance().servers.values.find { info -> info.name.equals(args[1]) }?.name ?: throw Exception()
                            val s = MaxPlayerCounter.getPlugin().connection.connection.prepareStatement("SELECT `timestamp`, `playerCount` FROM `players` WHERE `timestamp` >= ? AND `timestamp` <= ? AND `server` = ?")
                            s.setLong(1, pair.first)
                            s.setLong(2, pair.second)
                            s.setString(3, server)
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
                        val servers = MaxPlayerCounter.getPlugin().connection.getServersByGroup(group).complete().map { server -> server.name }
                        if (servers.isEmpty()) return@then emptyList<Pair<Long, Int>>()
                        var a = ""
                        servers.forEach { _ ->
                            if (a != "") a += " OR "
                            a += "`server` = ?"
                        }
                        val s = MaxPlayerCounter.getPlugin().connection.connection.prepareStatement("SELECT * FROM `players` WHERE `timestamp` >= ? AND `timestamp` <= ? AND ($a)")
                        s.setLong(1, pair.first)
                        s.setLong(2, pair.second)
                        servers.forEachIndexed { index, server ->
                            s.setString(index + 3, server)
                        }
                        val result = s.executeQuery()
                        val map = mutableMapOf<String, MutableList<Pair<Long, Int>>>()
                        while (result.next()) {
                            val server = result.getString("server")
                            val first = result.getLong("timestamp")
                            val second = result.getInt("playerCount")
                            map.getOrPut(server) { mutableListOf() }.add(first to second)
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
                        if (list.isEmpty()) return@then sender.send("${ChatColor.RED}データがありません。")
                        val p = list.maxByOrNull { it.second }!!
                        sender.send("${ChatColor.GOLD}${args[1]}${ChatColor.GREEN}:")
                        sender.send("${ChatColor.GOLD}- 最大人数: ${ChatColor.AQUA}${p.second}")
                        sender.send("${ChatColor.GOLD}- 達成日時: ${ChatColor.AQUA}${Util.formatDateTime(p.first)}")
                    }
                    .catch {
                        if (it::class.java == Exception::class.java) return@catch
                        it.printStackTrace()
                    }
            }
            "creategroup" -> {
                if (args.size <= 1) return sender.sendHelp()
                val groupName = args[1]
                if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
                    return sender.send("${ChatColor.RED}この名前は使用できません。")
                }
                MaxPlayerCounter.getPlugin().connection.getAllGroups()
                    .then { list ->
                        if (list.any { it.equals(groupName, true) }) {
                            sender.send("${ChatColor.RED}この名前はすでに使用されています。")
                            throw Exception()
                        }
                    }
                    .then(MaxPlayerCounter.getPlugin().connection.groups.insert(InsertOptions.Builder().addValue("id", groupName).build()))
                    .then { sender.send("${ChatColor.GREEN}グループ「${ChatColor.GOLD}$groupName${ChatColor.GREEN}」を作成しました。") }
                    .catch {
                        if (it::class.java == Exception::class.java) return@catch
                        sender.send("${ChatColor.RED}グループの作成に失敗しました。")
                        it.printStackTrace()
                    }
            }
            "deletegroup" -> {
                if (args.size <= 1) return sender.sendHelp()
                val groupName = args[1]
                if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
                    return sender.send("${ChatColor.RED}無効なグループ名です。")
                }
                MaxPlayerCounter.getPlugin().connection.getAllGroups()
                    .then { list ->
                        if (!list.any { it == groupName }) {
                            sender.send("${ChatColor.RED}無効なグループ名です。")
                            throw Exception()
                        }
                    }
                    .then(MaxPlayerCounter.getPlugin().connection.groups.delete(FindOptions.Builder().addWhere("id", groupName).build()))
                    .then(MaxPlayerCounter.getPlugin().connection.serverGroup.delete(FindOptions.Builder().addWhere("group", groupName).build()))
                    .then { sender.send("${ChatColor.GREEN}グループ「${ChatColor.GOLD}$groupName${ChatColor.GREEN}」を削除しました。") }
                    .catch {
                        if (it::class.java == Exception::class.java) return@catch
                        sender.send("${ChatColor.RED}グループの削除に失敗しました。")
                        it.printStackTrace()
                    }
            }
            "g", "group" -> {
                if (args.size <= 2 || !groupCommands.contains(args[2])) return sender.sendHelp()
                val groupName = args[1]
                if (!groupName.matches(MaxPlayerCounter.GROUP_PATTERN)) {
                    return sender.send("${ChatColor.RED}無効なグループ名です。")
                }
                MaxPlayerCounter.getPlugin().connection.getAllGroups()
                    .then { list ->
                        if (!list.any { it == groupName }) {
                            return@then sender.send("${ChatColor.RED}無効なグループ名です。")
                        }
                        if (args[2] == "add" || args[2] == "remove") {
                            if (args.size < 3) {
                                return@then sender.sendHelp()
                            } else if (!ProxyServer.getInstance().servers.map { it.value.name }.any { it == args[3] }) {
                                return@then sender.send("${ChatColor.RED}無効なサーバー名です。")
                            }
                        }
                        when (args[2]) {
                            "add" -> {
                                val server = args[3]
                                MaxPlayerCounter.getPlugin().connection.serverGroup.upsert(
                                    UpsertOptions.Builder()
                                        .addWhere("server", server)
                                        .addValue("group", groupName)
                                        .addValue("server", server)
                                        .build()
                                ).complete()
                                sender.send("${ChatColor.GREEN}グループにサーバー($server)を追加しました。")
                            }
                            "remove" -> {
                                val server = args[3]
                                MaxPlayerCounter.getPlugin().connection.serverGroup.delete(
                                    FindOptions.Builder()
                                        .addWhere("group", groupName)
                                        .addWhere("server", server)
                                        .build()
                                ).complete()
                                sender.send("${ChatColor.GREEN}グループからサーバーを(そのグループに入っている場合は)除外しました。")
                            }
                            "info" -> {
                                val servers = MaxPlayerCounter.getPlugin().connection.getServersByGroup(args[1]).complete()
                                sender.send("${ChatColor.AQUA}グループ: ${ChatColor.RESET}$groupName")
                                sender.send("- ${ChatColor.AQUA}サーバー:")
                                servers.forEach { server ->
                                    sender.send("   - ${ChatColor.GREEN}${server.name}")
                                }
                            }
                            else -> sender.sendHelp()
                        }
                    }
            }
            else -> sender.sendHelp()
        }
    }

    override fun onTabComplete(sender: CommandSender, args: Array<String>): Iterable<String> {
        if (args.isEmpty()) return emptyList()
        if (args.size == 1) return commands.filter(args[0])
        if (args[0] == "list" && args.size == 2) return list12.filter(args[1])
        if (args[0] == "show") {
            if (args.size == 2) {
                val servers = ProxyServer.getInstance().servers.values.map { it.name }.toMutableList()
                val groups = getGroups() ?: return servers.filter(args[1])
                return servers.apply { addAll(groups) }.distinct().filter(args[1])
            }
            if (args.size == 3) return list12.filter(args[2])
        }
        if (args[0] == "deletegroup" && args.size == 2) {
            getGroups()?.let { return it.filter(args[1]) }
        }
        if (args[0] == "group" || args[0] == "g") {
            if (args.size == 2) getGroups()?.let { return it.filter(args[1]) }
            if (args.size == 3) return groupCommands.filter(args[2])
            if (args.size == 4 && (args[2] == "add" || args[2] == "remove")) return ProxyServer.getInstance().servers.values.map { it.name }.filter(args[3])
        }
        return emptyList()
    }

    private fun getGroups(): List<String>? {
        val groups = cachedGroups.get()
        if (groups == null || cachedGroups.ttl - System.currentTimeMillis() < 10000) { // update if cache is expired or expiring in under 10 seconds
            if (!updatingCache) {
                updatingCache = true
                MaxPlayerCounter.getPlugin().connection.getAllGroups().then {
                    cachedGroups = DataCache(it, System.currentTimeMillis() + 1000 * 60)
                    updatingCache = false
                }
            }
        }
        return groups
    }

    private fun List<String>.filter(s: String): List<String> = filter { s1 -> s1.lowercase().startsWith(s.lowercase()) }
}
