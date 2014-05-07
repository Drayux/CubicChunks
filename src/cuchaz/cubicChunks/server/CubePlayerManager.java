/*******************************************************************************
 * Copyright (c) 2014 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.cubicChunks.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import com.google.common.collect.Maps;

import cuchaz.cubicChunks.util.AddressTools;
import cuchaz.cubicChunks.util.Coords;
import cuchaz.cubicChunks.visibility.CubeSelector;
import cuchaz.cubicChunks.visibility.CuboidalCubeSelector;
import cuchaz.cubicChunks.world.Column;
import cuchaz.cubicChunks.world.ColumnView;
import cuchaz.cubicChunks.world.Cube;

public class CubePlayerManager extends PlayerManager
{
	private static class PlayerInfo
	{
		public Set<Long> watchedAddresses;
		public LinkedList<Cube> outgoingCubes;
		public CubeSelector cubeSelector;
		public int blockX;
		public int blockY;
		public int blockZ;
		public long address;
		
		public PlayerInfo( )
		{
			this.watchedAddresses = new TreeSet<Long>();
			this.outgoingCubes = new LinkedList<Cube>();
			this.cubeSelector = new CuboidalCubeSelector();
			this.blockX = 0;
			this.blockY = 0;
			this.blockZ = 0;
			this.address = 0;
		}
		
		public void sortOutgoingCubes( )
		{
			// get the player chunk position
			final int cubeX = AddressTools.getX( address );
			final int cubeY = AddressTools.getY( address );
			final int cubeZ = AddressTools.getZ( address );
			
			// sort cubes so they load radially away from the player
			Collections.sort( outgoingCubes, new Comparator<Cube>( )
			{
				@Override
				public int compare( Cube a, Cube b )
				{
					return getManhattanDist( a ) - getManhattanDist( b );
				}
				
				private int getManhattanDist( Cube cube )
				{
					int dx = Math.abs( cube.getX() - cubeX );
					int dy = Math.abs( cube.getY() - cubeY );
					int dz = Math.abs( cube.getZ() - cubeZ );
					return dx + dy + dz;
				}
			} );
		}
		
		public void removeOutOfRangeOutgoingCubes( )
		{
			Iterator<Cube> iter = outgoingCubes.iterator();
			while( iter.hasNext() )
			{
				Cube cube = iter.next();
				if( !cubeSelector.isVisible( cube.getAddress() ) )
				{
					iter.remove();
				}
			}
		}
	}
	
	private WorldServer m_worldServer;
	private int m_viewDistance;
	private TreeMap<Long,CubeWatcher> m_watchers;
	private TreeMap<Integer,PlayerInfo> m_players;
	
	public CubePlayerManager( WorldServer worldServer, int viewDistance )
	{
		super( worldServer, viewDistance );
		
		m_worldServer = worldServer;
		m_viewDistance = viewDistance;
		m_watchers = Maps.newTreeMap();
		m_players = Maps.newTreeMap();
	}
	
	public void addPlayer( EntityPlayerMP player )
	{
		// make new player info
		PlayerInfo info = new PlayerInfo();
		m_players.put( player.getEntityId(), info );
		
		// set initial player position
		info.blockX = MathHelper.floor_double( player.posX );
		info.blockY = MathHelper.floor_double( player.posY );
		info.blockZ = MathHelper.floor_double( player.posZ );
		int cubeX = Coords.blockToCube( info.blockX );
		int cubeY = Coords.blockToCube( info.blockY );
		int cubeZ = Coords.blockToCube( info.blockZ );
		info.address = AddressTools.getAddress( cubeX, cubeY, cubeZ );
		
		// compute initial visibility
		info.cubeSelector.setPlayerPosition( info.address, m_viewDistance );
		
		// add player to watchers and collect the cubes to send over
		for( long address : info.cubeSelector.getVisibleCubes() )
		{
			CubeWatcher watcher = getOrCreateWatcher( address );
			if( watcher == null )
			{
				// no watcher means an empty cube
				continue;
			}
			
			watcher.addPlayer( player );
			info.watchedAddresses.add( address );
			info.outgoingCubes.add( watcher.getCube() );
		}
	}
	
	public void removePlayer( EntityPlayerMP player )
	{
		// get the player info
		PlayerInfo info = m_players.get( player.getEntityId() );
		if( info == null )
		{
			return;
		}
		
		// remove player from all its cubes
		for( long address : info.watchedAddresses )
		{
			// skip non-existent cubes
			if( !cubeExists( address ) )
			{
				continue;
			}
			
			// remove from the watcher
			CubeWatcher watcher = getWatcher( address );
			if( watcher != null )
			{
				watcher.removePlayer( player );
			}
			
			// cleanup empty watchers and cubes
			if( !watcher.hasPlayers() )
			{
				m_watchers.remove( address );
				getCubeProvider().unloadCubeIfNotNearSpawn( watcher.getCube() );
			}
		}
		
		// remove the info
		m_players.remove( player.getEntityId() );
	}
	
	public void updatePlayerInstances( ) // aka tick()
	{
		// responsibilities:
		//    update chunk properties
		//    send chunk updates to players
		
		for( CubeWatcher watcher : m_watchers.values() )
		{
			watcher.sendUpdates();
			watcher.tick();
		}
		
		// did all the players leave an alternate dimension?
		if( m_players.isEmpty() && !m_worldServer.provider.canRespawnHere() )
		{
			// unload everything
			getCubeProvider().unloadAllChunks();
		}
	}
	
	//     markBlockForUpdate
	public void func_151250_a( int blockX, int blockY, int blockZ )
	{
		// get the watcher
		int cubeX = Coords.blockToCube( blockX );
		int cubeY = Coords.blockToCube( blockY );
		int cubeZ = Coords.blockToCube( blockZ );
		CubeWatcher watcher = getWatcher( cubeX, cubeY, cubeZ );
		if( watcher == null )
		{
			return;
		}
		
		// pass off to watcher
		int localX = Coords.blockToLocal( blockX );
		int localY = Coords.blockToLocal( blockY );
		int localZ = Coords.blockToLocal( blockZ );
		watcher.setDirtyBlock( localX, localY, localZ );
	}
	
	@Override
	public void updateMountedMovingPlayer( EntityPlayerMP player )
	{
		// the player moved
		// if the player moved into a new chunk, update which chunks the player needs to know about
		// then update the list of chunks that need to be sent to the client
		
		// get the player info
		PlayerInfo info = m_players.get( player.getEntityId() );
		if( info == null )
		{
			return;
		}
		
		// did the player move far enough to matter?
		int newBlockX = MathHelper.floor_double( player.posX );
		int newBlockY = MathHelper.floor_double( player.posY );
		int newBlockZ = MathHelper.floor_double( player.posZ );
		int manhattanDistance = Math.abs( newBlockX - info.blockX )
				+ Math.abs( newBlockY - info.blockY )
				+ Math.abs( newBlockZ - info.blockZ );
		if( manhattanDistance < 8 )
		{
			return;
		}
		
		// did the player move into a new cube?
		int newCubeX = Coords.blockToCube( newBlockX );
		int newCubeY = Coords.blockToCube( newBlockY );
		int newCubeZ = Coords.blockToCube( newBlockZ );
		long newAddress = AddressTools.getAddress( newCubeX, newCubeY, newCubeZ );
		if( newAddress == info.address )
		{
			return;
		}
		
		// update player info
		info.blockX = newBlockX;
		info.blockY = newBlockY;
		info.blockZ = newBlockZ;
		info.address = newAddress;
		
		// calculate new visibility
		info.cubeSelector.setPlayerPosition( newAddress, m_viewDistance );
		
		// add to new watchers
		for( long address : info.cubeSelector.getNewlyVisibleCubes() )
		{
			CubeWatcher watcher = getOrCreateWatcher( address );
			if( watcher == null )
			{
				// no watcher means an empty cube
				continue;
			}
			
			watcher.addPlayer( player );
			info.outgoingCubes.add( watcher.getCube() );
		}
		
		// remove from old watchers
		for( long address : info.cubeSelector.getNewlyHiddenCubes() )
		{
			CubeWatcher watcher = getWatcher( address );
			if( watcher == null )
			{
				continue;
			}
			
			watcher.removePlayer( player );
			
			// cleanup empty watchers and cubes
			if( !watcher.hasPlayers() )
			{
				m_watchers.remove( address );
				getCubeProvider().unloadCubeIfNotNearSpawn( watcher.getCube() );
			}
		}
	}
	
	public boolean isPlayerWatchingChunk( EntityPlayerMP player, int cubeX, int cubeZ )
	{
		// get the info
		PlayerInfo info = m_players.get( player.getEntityId() );
		if( info == null )
		{
			return false;
		}
		
		// check the player's watched addresses
		for( long address : info.watchedAddresses )
		{
			int x = AddressTools.getX( address );
			int z = AddressTools.getZ( address );
			if( x == cubeX && z == cubeZ )
			{
				return true;
			}
		}
		return false;
	}
	
	public void onPlayerUpdate( EntityPlayerMP player )
	{
		// this method flushes outgoing chunks to the player
		
		// get the outgoing chunks
		PlayerInfo info = m_players.get( player.getEntityId() );
		if( info == null || info.outgoingCubes.isEmpty() )
		{
			return;
		}
		info.removeOutOfRangeOutgoingCubes();
		info.sortOutgoingCubes();
		
		// pull off enough cubes from the queue to fit in a packet
		final int MaxCubesToSend = 100;
		List<Cube> cubesToSend = new ArrayList<Cube>();
		List<TileEntity> tileEntitiesToSend = new ArrayList<TileEntity>();
		Iterator<Cube> iter = info.outgoingCubes.iterator();
		while( iter.hasNext() && cubesToSend.size() < MaxCubesToSend )
		{
			Cube cube = iter.next();
			
			// only send cubes in active columns, not from columns on the fringe of the world that have been generated, but not activated yet
			if( !cube.getColumn().func_150802_k() )
			{
				continue;
			}
			
			// add this cube to the send buffer
			cubesToSend.add( cube );
			iter.remove();
			
			// add tile entities too
			for( TileEntity tileEntity : cube.tileEntities() )
			{
				tileEntitiesToSend.add( tileEntity );
			}
		}
		
		if( cubesToSend.isEmpty() )
		{
			return;
		}
		
		// group the cubes into column views
		Map<Long,ColumnView> views = new TreeMap<Long,ColumnView>();
		for( Cube cube : cubesToSend )
		{
			// is there a column view for this cube?
			long columnAddress = AddressTools.getAddress( cube.getX(), cube.getZ() );
			ColumnView view = views.get( columnAddress );
			if( view == null )
			{
				view = new ColumnView( cube.getColumn() );
				views.put( columnAddress, view );
			}
			
			view.addCubeToView( cube );
		}
		List<Column> columnsToSend = new ArrayList<Column>( views.values() );
		
		// send the cube data
		player.playerNetServerHandler.sendPacket( new S26PacketMapChunkBulk( columnsToSend ) );
		
		// TEMP
		System.out.println( String.format( "Send %d cubes, %d remaining", cubesToSend.size(), info.outgoingCubes.size() ) );
		
		// send tile entity data
		for( TileEntity tileEntity : tileEntitiesToSend )
		{
			Packet packet = tileEntity.getDescriptionPacket();
			if( packet != null )
			{
				player.playerNetServerHandler.sendPacket( packet );
			}
		}
		
		// watch entities on the chunks we just sent
		for( Chunk chunk : columnsToSend )
		{
			m_worldServer.getEntityTracker().func_85172_a( player, chunk );
		}
	}
	
	public Iterable<Long> getVisibleCubeAddresses( EntityPlayerMP player )
	{
		// get the info
		PlayerInfo info = m_players.get( player.getEntityId() );
		if( info == null )
		{
			return null;
		}
		
		return info.cubeSelector.getVisibleCubes();
	}
	
	private CubeProviderServer getCubeProvider( )
	{
		return (CubeProviderServer)m_worldServer.theChunkProviderServer;
	}
	
	private CubeWatcher getWatcher( int cubeX, int cubeY, int cubeZ )
	{
		return getWatcher( AddressTools.getAddress( cubeX, cubeY, cubeZ ) );
	}
	
	private CubeWatcher getWatcher( long address )
	{
		return m_watchers.get( address );
	}
	
	private boolean cubeExists( long address )
	{
		int cubeX = AddressTools.getX( address );
		int cubeY = AddressTools.getY( address );
		int cubeZ = AddressTools.getZ( address );
		return getCubeProvider().cubeExists( cubeX, cubeY, cubeZ );
	}
	
	private CubeWatcher getOrCreateWatcher( long address )
	{
		CubeWatcher watcher = m_watchers.get( address );
		if( watcher == null )
		{
			// get the cube
			int cubeX = AddressTools.getX( address );
			int cubeY = AddressTools.getY( address );
			int cubeZ = AddressTools.getZ( address );
			Cube cube = getCubeProvider().loadCube( cubeX, cubeY, cubeZ );
			
			// if no cube was loaded, that means it's an empty cube
			if( cube == null )
			{
				return null;
			}
			
			// make a new watcher
			watcher = new CubeWatcher( cube );
			m_watchers.put( address, watcher );
		}
		return watcher;
	}
}