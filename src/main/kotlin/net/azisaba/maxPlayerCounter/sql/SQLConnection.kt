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

package net.azisaba.maxPlayerCounter.sql

import com.velocitypowered.api.proxy.server.RegisteredServer
import net.azisaba.maxPlayerCounter.MaxPlayerCounter
import util.promise.rewrite.Promise
import xyz.acrylicstyle.sql.DataType
import xyz.acrylicstyle.sql.Sequelize
import xyz.acrylicstyle.sql.Table
import xyz.acrylicstyle.sql.TableDefinition
import xyz.acrylicstyle.sql.options.FindOptions
import xyz.acrylicstyle.sql.options.InsertOptions
import java.sql.Driver
import java.util.Properties

class SQLConnection(host: String, name: String, user: String, password: String): Sequelize(host, name, user, password) {
    companion object {
        fun logSql(s: String, time: Long) {
            if (time > 500) {
                MaxPlayerCounter.instance.logger.warn("[MaxPlayerCounter] Executed SQL: $s ($time ms)")
            } else {
                if (false) {
                    MaxPlayerCounter.instance.logger.info("[MaxPlayerCounter] Executed SQL: $s ($time ms)")
                }
            }
        }
    }

    private lateinit var players: Table
    lateinit var groups: Table
    lateinit var serverGroup: Table
    lateinit var records: Table

    fun isConnected() = connection != null && !connection.isClosed

    fun connect(properties: Properties) {
        if (isConnected()) return
        this.authenticate(Class.forName("net.azisaba.maxPlayerCounter.libs.org.mariadb.jdbc.Driver").newInstance() as Driver, properties)
        players = this.define(
            "players",
            arrayOf(
                TableDefinition.Builder("server", DataType.STRING).setAllowNull(false).build(),
                TableDefinition.Builder("timestamp", DataType.BIGINT).setAllowNull(false).build(),
                TableDefinition.Builder("playerCount", DataType.INT).setAllowNull(false).build(),
            ),
        ).setupEventListener()
        groups = this.define(
            "groups",
            arrayOf(
                TableDefinition.Builder("id", DataType.STRING).setAllowNull(false).setPrimaryKey(true).build(),
            ),
        ).setupEventListener()
        serverGroup = this.define(
            "serverGroup",
            arrayOf(
                TableDefinition.Builder("server", DataType.STRING).setAllowNull(false).setPrimaryKey(true).build(),
                TableDefinition.Builder("group", DataType.STRING).setAllowNull(false).build(),
            ),
        ).setupEventListener()
        records = this.define(
            "records",
            arrayOf(
                TableDefinition.Builder("date", DataType.DATE).setAllowNull(false).setPrimaryKey(true).build(),
                TableDefinition.Builder("server", DataType.STRING).setAllowNull(false).build(),
                TableDefinition.Builder("timestamp", DataType.BIGINT).setAllowNull(false).build(),
                TableDefinition.Builder("player_count", DataType.INT).setAllowNull(false).build(),
            ),
        ).setupEventListener()
        this.sync()
    }

    private fun Table.setupEventListener(): Table {
        eventEmitter.on(Table.Events.EXECUTED) {
            val sql = it[0] as String
            logSql(sql, it[1] as Long)
        }
        return this
    }

    fun updatePlayerCountAll() = Promise.create<Unit> { context ->
        if (isConnected()) {
            val ts = System.currentTimeMillis()
            Promise.allUntyped(*MaxPlayerCounter.instance.server.allServers.map { server ->
                players.insert(
                    InsertOptions.Builder()
                        .addValue("server", server.serverInfo.name)
                        .addValue("timestamp", ts)
                        .addValue("playerCount", server.playersConnected.size)
                        .build()
                )
            }.toTypedArray())
        }
        context.resolve()
    }

    fun getServersByGroup(groupId: String): Promise<List<RegisteredServer>> =
        serverGroup.findAll(FindOptions.Builder().addWhere("group", groupId).build())
            .then { it.mapNotNull { td -> MaxPlayerCounter.instance.server.getServer(td.getString("server")).orElse(null) } }

    fun getAllServerGroups(): Promise<Map<String, List<String>>> =
        serverGroup.findAll(FindOptions.ALL).then {
            val map = mutableMapOf<String, MutableList<String>>()
            it.forEach { td ->
                val group = td.getString("group")
                val l = map.getOrPut(group) { mutableListOf() }
                l.add(td.getString("server"))
            }
            return@then map
        }

    fun getAllGroups(): Promise<List<String>> =
        groups.findAll(FindOptions.ALL).then { it.map { td -> td.getString("id") } }
}
