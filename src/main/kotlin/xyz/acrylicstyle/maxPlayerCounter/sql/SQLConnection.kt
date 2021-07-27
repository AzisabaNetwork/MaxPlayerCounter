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

package xyz.acrylicstyle.maxPlayerCounter.sql

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.config.ServerInfo
import util.promise.rewrite.Promise
import xyz.acrylicstyle.sql.DataType
import xyz.acrylicstyle.sql.Sequelize
import xyz.acrylicstyle.sql.Table
import xyz.acrylicstyle.sql.TableDefinition
import xyz.acrylicstyle.sql.options.FindOptions
import xyz.acrylicstyle.sql.options.InsertOptions
import java.util.Properties

class SQLConnection(host: String, name: String, user: String, password: String): Sequelize(host, name, user, password) {
    lateinit var players: Table
    lateinit var groups: Table
    lateinit var serverGroup: Table

    fun isConnected() = connection != null && !connection.isClosed

    fun connect(properties: Properties) {
        if (isConnected()) return
        this.authenticate(getMariaDBDriver(), properties)
        players = this.define(
            "players",
            arrayOf(
                TableDefinition.Builder("server", DataType.STRING).setAllowNull(false).build(),
                TableDefinition.Builder("timestamp", DataType.BIGINT).setAllowNull(false).build(),
                TableDefinition.Builder("playerCount", DataType.INT).setAllowNull(false).build(),
            ),
        )
        groups = this.define(
            "groups",
            arrayOf(
                TableDefinition.Builder("id", DataType.STRING).setAllowNull(false).setPrimaryKey(true).build(),
            )
        )
        serverGroup = this.define(
            "serverGroup",
            arrayOf(
                TableDefinition.Builder("server", DataType.STRING).setAllowNull(false).setPrimaryKey(true).build(),
                TableDefinition.Builder("group", DataType.STRING).setAllowNull(false).build(),
            )
        )
        this.sync()
    }

    private var lastUpdated = 0

    fun updatePlayerCount(): Promise<Unit> = Promise.create {
        if (isConnected() && System.currentTimeMillis() - lastUpdated > 950) {
            val ts = System.currentTimeMillis()
            Promise.allUntyped(*ProxyServer.getInstance().servers.values.map { server ->
                players.insert(
                    InsertOptions.Builder()
                        .addValue("server", server.name)
                        .addValue("timestamp", ts)
                        .addValue("playerCount", server.players.size)
                        .build()
                )
            }.toTypedArray())
        }
        it.resolve()
    }

    fun getServersByGroup(groupId: String): Promise<List<ServerInfo>> =
        serverGroup.findAll(FindOptions.Builder().addWhere("group", groupId).build())
            .then { it.mapNotNull { td -> ProxyServer.getInstance().getServerInfo(td.getString("server")) } }

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

    fun getGroupByServer(server: String): Promise<String?> =
        serverGroup.findOne(FindOptions.Builder().addWhere("server", server).setLimit(1).build())
            .then { it?.getString("group") }

    fun getAllGroups(): Promise<List<String>> =
        groups.findAll(FindOptions.ALL).then { it.map { td -> td.getString("id") } }
}
