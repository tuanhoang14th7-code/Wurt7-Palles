/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.autofarm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Harvests mature CustomCrops plants by right-clicking them with an empty
 * main hand, the same way a legitimate player would.
 *
 * <p>
 * This class is intentionally standalone and does not depend on
 * {@link net.wurstclient.hacks.AutoFarmHack AutoFarmHack}.
 */
public final class CustomCropHarvester
{
	private static final Minecraft MC = WurstClient.MC;
	
	private static final double MAX_REACH = 4.5;
	private static final long MIN_HARVEST_DELAY_MS = 50;
	
	// timestamp of the last successful harvest, used to throttle calls
	private static long lastHarvestTime;
	
	private CustomCropHarvester()
	{}
	
	/**
	 * Attempts to harvest the CustomCrops plant at the given position by
	 * right-clicking it with an empty hand. Returns {@code true} if the
	 * interaction was performed.
	 */
	public static boolean harvest(BlockPos pos)
	{
		return harvest(pos, false);
	}
	
	/**
	 * Attempts to harvest the CustomCrops plant at the given position by
	 * right-clicking it with an empty hand. Returns {@code true} if the
	 * interaction was performed.
	 *
	 * @param antiCheatMode
	 *            if {@code true}, adds a small random rotation jitter and
	 *            uses a randomized 50-100ms delay (instead of a fixed
	 *            50ms)
	 *            to make the interaction look less robotic.
	 */
	public static boolean harvest(BlockPos pos, boolean antiCheatMode)
	{
		// enforce a minimum delay between harvests, randomized in
		// anti-cheat mode so the timing isn't perfectly consistent
		long now = System.currentTimeMillis();
		long minDelay = antiCheatMode
			? MIN_HARVEST_DELAY_MS + ThreadLocalRandom.current().nextInt(51)
			: MIN_HARVEST_DELAY_MS;
		if(now - lastHarvestTime < minDelay)
			return false;
		
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return false;
			
		// the block may have been broken (e.g. by another player) between
		// when it was scanned and when this method is called
		if(!MC.level.getBlockState(pos).is(Blocks.TRIPWIRE))
			return false;
		
		Vec3 center = Vec3.atCenterOf(pos);
		if(player.position().distanceTo(center) > MAX_REACH)
			return false;
		
		Inventory inventory = player.getInventory();
		int oldSlot = -1;
		
		// free up the main hand by switching to an empty hotbar slot
		if(!player.getMainHandItem().isEmpty())
		{
			int emptySlot = findEmptyHotbarSlot(inventory);
			if(emptySlot == -1)
				return false;
			
			oldSlot = inventory.selected;
			inventory.selected = emptySlot;
		}
		
		try
		{
			// face the crop before interacting with it, with a small random
			// angle offset in anti-cheat mode to avoid perfectly pixel-exact
			// rotations
			Rotation rotation = RotationUtils.getNeededRotations(center);
			float yaw = rotation.yaw();
			float pitch = rotation.pitch();
			if(antiCheatMode)
			{
				ThreadLocalRandom random = ThreadLocalRandom.current();
				yaw += random.nextFloat(-2f, 2f);
				pitch += random.nextFloat(-2f, 2f);
			}
			player.setYRot(yaw);
			player.setXRot(pitch);
			
			BlockHitResult hitResult =
				new BlockHitResult(center, Direction.UP, pos, false);
			
			MC.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
			player.swing(InteractionHand.MAIN_HAND);
			
		}finally
		{
			// restore the original hotbar slot, if it was changed
			if(oldSlot != -1)
				inventory.selected = oldSlot;
		}
		
		lastHarvestTime = now;
		return true;
	}
	
	/**
	 * Returns the index (0-8) of the first empty hotbar slot, or {@code -1} if
	 * the hotbar is completely full.
	 */
	private static int findEmptyHotbarSlot(Inventory inventory)
	{
		for(int i = 0; i < 9; i++)
			if(inventory.getItem(i).isEmpty())
				return i;
			
		return -1;
	}
}
