/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.autofarm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Detects mature CustomCrops plants that are disguised as Tripwire blocks.
 *
 * <p>
 * CustomCrops (a popular datapack/plugin) represents crop growth stages using
 * the Tripwire block's boolean properties. Through experimentation, the
 * following combination of properties was confirmed to indicate a fully
 * grown/mature crop.
 */
public final class CustomCropDetector
{
	private CustomCropDetector()
	{}
	
	/**
	 * Checks whether the Tripwire block at the given position represents a
	 * mature CustomCrops plant.
	 *
	 * <p>
	 * This class is intentionally standalone and does not depend on
	 * {@link net.wurstclient.hacks.AutoFarmHack AutoFarmHack}.
	 */
	public static boolean isMature(Level world, BlockPos pos)
	{
		return isMature(world, pos, true);
	}
	
	/**
	 * Checks whether the Tripwire block at the given position represents a
	 * mature CustomCrops plant.
	 *
	 * @param requireNoteBlockBelow
	 *            if {@code true}, the block below must be a Note
	 *            Block. This
	 *            filters out ordinary Redstone tripwire traps,
	 *            which
	 *            CustomCrops doesn't place on Note Blocks.
	 *			
	 *            <p>
	 *            This class is intentionally standalone and does
	 *            not depend on
	 *            {@link net.wurstclient.hacks.AutoFarmHack
	 *            AutoFarmHack}.
	 */
	public static boolean isMature(Level world, BlockPos pos,
		boolean requireNoteBlockBelow)
	{
		// always reads the current world state, so a Tripwire that was
		// broken (e.g. by another player) since it was last scanned will
		// correctly report as not mature instead of using stale data
		BlockState state = world.getBlockState(pos);
		if(!state.is(Blocks.TRIPWIRE))
			return false;
		
		// confirmed maturity pattern for CustomCrops' Tripwire-based crops
		boolean maturityPattern = !state.getValue(BlockStateProperties.ATTACHED)
			&& !state.getValue(BlockStateProperties.EAST)
			&& state.getValue(BlockStateProperties.DISARMED)
			&& state.getValue(BlockStateProperties.NORTH)
			&& state.getValue(BlockStateProperties.POWERED)
			&& state.getValue(BlockStateProperties.SOUTH)
			&& state.getValue(BlockStateProperties.WEST);
		
		if(!maturityPattern)
			return false;
		
		if(!requireNoteBlockBelow)
			return true;
			
		// CustomCrops places these plants on Note Blocks, unlike real
		// Redstone tripwire traps
		BlockState belowState = world.getBlockState(pos.below());
		return belowState.is(Blocks.NOTE_BLOCK);
	}
}
