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

package xyz.acrylicstyle.maxPlayerCounter.listener

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.event.EventHandler
import xyz.acrylicstyle.maxPlayerCounter.MaxPlayerCounter
import java.util.concurrent.TimeUnit

object JoinQuitEventsListener: Listener {
    @EventHandler
    fun onServerConnected(e: ServerConnectedEvent) {
        ProxyServer.getInstance().scheduler.schedule(MaxPlayerCounter.getPlugin(), {
            MaxPlayerCounter.getPlugin().connection.updatePlayerCount()
        }, 10, TimeUnit.MILLISECONDS)
    }

    @EventHandler
    fun onQuit(e: PlayerDisconnectEvent) {
        ProxyServer.getInstance().scheduler.schedule(MaxPlayerCounter.getPlugin(), {
            MaxPlayerCounter.getPlugin().connection.updatePlayerCount()
        }, 10, TimeUnit.MILLISECONDS)
    }
}