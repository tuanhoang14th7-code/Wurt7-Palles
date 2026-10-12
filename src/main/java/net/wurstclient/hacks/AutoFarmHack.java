/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.autofarm.AutoFarmPlantType;
import net.wurstclient.hacks.autofarm.AutoFarmPlantTypeManager;
import net.wurstclient.hacks.autofarm.AutoFarmRenderer;
import net.wurstclient.hacks.autofarm.CustomCropDetector;
import net.wurstclient.hacks.autofarm.CustomCropHarvester;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FaceTargetSetting;
import net.wurstclient.settings.FaceTargetSetting.FaceTarget;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.*;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;

@SearchTags({"auto farm", "AutoHarvest", "auto harvest"})
public final class AutoFarmHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"description.wurst.setting.autofarm.check_line_of_sight", false);
	
	private final FaceTargetSetting faceTarget =
		FaceTargetSetting.withoutPacketSpam(this, FaceTarget.SERVER);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.SERVER);
	
	private final SliderSetting tickDelay = new SliderSetting("Tick delay",
		"Only scans for blocks to harvest every N ticks, instead of every"
			+ " single tick. Higher values reduce CPU usage when"
			+ " farming large areas, at the cost of reaction speed.\n\n"
			+ "This also applies to CustomCrops scanning/harvesting,"
			+ " which always runs on the same schedule as the rest of"
			+ " AutoFarm.",
		1, 1, 20, 1, ValueDisplay.INTEGER);
	
	private int tickCounter;
	
	private final SliderSetting maxBlocksPerTick =
		new SliderSetting("Max blocks per tick",
			"Limits how many blocks AutoFarm can look at per tick when"
				+ " scanning its range for things to harvest or replant."
				+ " Lower values reduce CPU usage when farming large"
				+ " areas, at the cost of reaction speed.",
			64, 1, 512, 1, ValueDisplay.INTEGER);
	
	private final HashSet<BlockPos> processedThisTick = new HashSet<>();
	
	private final AutoFarmPlantTypeManager plantTypes =
		new AutoFarmPlantTypeManager();
	
	public enum CustomCropsMode
	{
		OFF("Off"),
		HARVEST_ONLY("Harvest only");
		
		private final String name;
		
		CustomCropsMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final EnumSetting<CustomCropsMode> customCropsMode =
		new EnumSetting<>("CustomCrops mode",
			"Detects and harvests CustomCrops plants that are disguised as"
				+ " Tripwire blocks.\n\n"
				+ "\u00a7lOff\u00a7r disables CustomCrops support entirely.\n"
				+ "\u00a7lHarvest only\u00a7r harvests mature CustomCrops"
				+ " plants, but never replants them.",
			CustomCropsMode.values(), CustomCropsMode.OFF);
	
	private final CheckboxSetting requireNoteBlockBelow =
		new CheckboxSetting("Require note block below",
			"Only treats a Tripwire block as a CustomCrops plant if the block"
				+ " below it is a Note Block. This prevents AutoFarm from"
				+ " accidentally interacting with real Redstone tripwire"
				+ " traps.",
			true);
	
	private final CheckboxSetting antiCheatMode =
		new CheckboxSetting("AntiCheatMode",
			"Adds random delays and small rotation jitter to CustomCrops"
				+ " harvesting, and scales its delay with your server ping, to"
				+ " make it look less robotic to anti-cheat plugins.",
			true);
	
	private final SliderSetting maxHarvestPerTick =
		new SliderSetting("Max harvest per tick",
			"Limits how many CustomCrops plants can be harvested in the same"
				+ " tick.",
			2, 1, 10, 1, ValueDisplay.INTEGER);
	
	private int harvestedCount;
	private int customCropFailCount;
	private long customCropsPauseUntil;
	private long nextCustomCropActionTime;
	
	private final HashMap<BlockPos, AutoFarmPlantType> replantingSpots =
		new HashMap<>();
	private final BlockBreakingCache cache = new BlockBreakingCache();
	private BlockPos currentlyMining;
	
	private final AutoFarmRenderer renderer = new AutoFarmRenderer();
	private final OverlayRenderer overlay = new OverlayRenderer();
	
	private boolean busy;
	
	public AutoFarmHack()
	{
		super("AutoFarm");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(checkLOS);
		addSetting(faceTarget);
		addSetting(swingHand);
		addSetting(tickDelay);
		addSetting(maxBlocksPerTick);
		addSetting(customCropsMode);
		addSetting(requireNoteBlockBelow);
		addSetting(antiCheatMode);
		addSetting(maxHarvestPerTick);
		renderer.getSettings().forEach(this::addSetting);
		plantTypes.getSettings().forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoMineHack.setEnabled(false);
		replantingSpots.clear();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		if(currentlyMining != null)
		{
			MC.gameMode.isDestroying = true;
			MC.gameMode.stopDestroyBlock();
			currentlyMining = null;
		}
		
		cache.reset();
		overlay.resetProgress();
		busy = false;
		tickCounter = 0;
		processedThisTick.clear();
		customCropFailCount = 0;
		customCropsPauseUntil = 0;
		nextCustomCropActionTime = 0;
	}
	
	@Override
	public void onUpdate()
	{
		// throttle block scanning/harvesting (including CustomCrops) to run
		// only once every tickDelay ticks
		tickCounter++;
		if(tickCounter < tickDelay.getValueI())
			return;
		tickCounter = 0;
		
		// edge case: standing in water/lava makes right-clicking unreliable,
		// so just go idle for this tick instead of fighting the current
		if(MC.player.isInWater() || MC.player.isInLava())
		{
			busy = false;
			return;
		}
		
		// edge case: a full inventory means harvested items would be lost,
		// so stop AutoFarm and let the user make space instead
		if(MC.player.getInventory().getFreeSlot() < 0)
		{
			ChatUtils.error("Inventory is full. AutoFarm has been disabled.");
			setEnabled(false);
			return;
		}
		
		currentlyMining = null;
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		// single outward scan replaces the previous two separate cube scans
		processedThisTick.clear();
		int maxBlocks = maxBlocksPerTick.getValueI();
		
		List<BlockPos> nonEmptyBlocks = new ArrayList<>();
		List<BlockPos> replantSurfaces = new ArrayList<>();
		
		// Manual 3-axis loop instead of BlockPos.iterateOutwards. To switch
		// back later, replace this block with iterateOutwards (and copy
		// each pos with .immutable()); the sort below becomes unnecessary.
		List<BlockPos> inRange = new ArrayList<>();
		for(int x = -blockRange; x <= blockRange; x++)
			for(int y = -blockRange; y <= blockRange; y++)
				for(int z = -blockRange; z <= blockRange; z++)
				{
					BlockPos mutablePos = eyesBlock.offset(x, y, z);
					if(mutablePos.distToCenterSqr(eyesVec) <= rangeSq)
						inRange.add(mutablePos);
				}
			
		// nearest first, so MaxBlocksPerTick keeps the closest blocks
		inRange.sort(
			Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)));
		
		for(BlockPos pos : inRange)
		{
			if(processedThisTick.size() >= maxBlocks)
				break;
			
			if(!processedThisTick.add(pos))
				continue;
			
			if(BlockUtils.canBeClicked(pos))
				nonEmptyBlocks.add(pos);
			
			if(BlockUtils.getState(pos).canBeReplaced())
				replantSurfaces.add(pos);
		}
		
		for(BlockPos pos : nonEmptyBlocks)
		{
			AutoFarmPlantType plantType = plantTypes.getReplantingSpotType(pos);
			if(plantType != null)
				replantingSpots.put(pos, plantType);
		}
		
		List<BlockPos> blocksToMine = List.of();
		List<BlockPos> blocksToInteract = List.of();
		List<BlockPos> blocksToReplant = List.of();
		
		if(!WURST.getHax().freecamHack.isEnabled())
		{
			blocksToMine = nonEmptyBlocks.stream()
				.filter(plantTypes::shouldHarvestByMining)
				.sorted(Comparator
					.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
				.toList();
			
			blocksToInteract = nonEmptyBlocks.stream()
				.filter(plantTypes::shouldHarvestByInteracting)
				.sorted(Comparator
					.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
				.toList();
			
			blocksToReplant = replantSurfaces.stream().filter(pos -> {
				AutoFarmPlantType plantType = replantingSpots.get(pos);
				return plantType != null && plantType.isReplantingEnabled()
					&& plantType.hasPlantingSurface(pos);
			}).sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
				.toList();
		}
		
		boolean replanting = replant(blocksToReplant);
		boolean interacting =
			replanting ? false : harvestByInteracting(blocksToInteract);
		if(!interacting && !replanting)
			harvestByMining(blocksToMine);
			
		// CustomCrops is a separate, vanilla-independent code path that never
		// touches replantingSpots/blocksToMine/blocksToInteract
		boolean harvestingCustomCrop = false;
		if(customCropsMode.getSelected() != CustomCropsMode.OFF)
			harvestingCustomCrop = harvestCustomCrops(nonEmptyBlocks);
		
		busy = replanting || interacting || harvestingCustomCrop
			|| currentlyMining != null;
		
		List<BlockPos> blocksToHarvest = Stream
			.of(blocksToMine, blocksToInteract).flatMap(List::stream).toList();
		renderer.update(replantingSpots.keySet(), blocksToHarvest,
			blocksToReplant);
	}
	
	/**
	 * Scans the given blocks for mature CustomCrops plants and harvests up to
	 * {@link #maxHarvestPerTick} of them. Does nothing if
	 * {@link #customCropsMode} is {@code OFF}.
	 */
	private boolean harvestCustomCrops(List<BlockPos> nonEmptyBlocks)
	{
		long now = System.currentTimeMillis();
		
		// edge case: pause CustomCrops for a while after repeated failures
		// (e.g. hotbar full) instead of retrying every single tick
		if(now < customCropsPauseUntil)
			return false;
			
		// edge case: scale the delay between actions with the server ping,
		// to avoid harvesting faster than the server can keep up with
		if(now < nextCustomCropActionTime)
			return false;
		
		boolean requireNoteBlock = requireNoteBlockBelow.isChecked();
		Vec3 eyesVec = RotationUtils.getEyesPos();
		
		List<BlockPos> candidates = nonEmptyBlocks.stream()
			.filter(pos -> CustomCropDetector.isMature(MC.level, pos,
				requireNoteBlock))
			// edge case: skip crops that are hidden behind other blocks
			.filter(pos -> !checkLOS.isChecked()
				|| BlockUtils.hasLineOfSight(Vec3.atCenterOf(pos)))
			.sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
			.toList();
		
		if(candidates.isEmpty())
			return false;
		
		int maxHarvests = maxHarvestPerTick.getValueI();
		boolean antiCheat = antiCheatMode.isChecked();
		boolean harvestedAny = false;
		int harvestsDone = 0;
		
		for(BlockPos pos : candidates)
		{
			if(harvestsDone >= maxHarvests)
				break;
			
			if(tryHarvestCustomCrop(pos, requireNoteBlock, antiCheat))
			{
				harvestedAny = true;
				harvestsDone++;
				
				// the dynamic/anti-cheat delay already applies to the next
				// call of this method, so one harvest per call is enough
				// unless multiple harvests per tick are explicitly allowed
				if(!antiCheat)
					break;
			}
		}
		
		return harvestedAny;
	}
	
	/**
	 * Attempts to harvest a single CustomCrops plant and updates the delay
	 * and failure-tracking state used by {@link #harvestCustomCrops}.
	 */
	private boolean tryHarvestCustomCrop(BlockPos pos, boolean requireNoteBlock,
		boolean antiCheat)
	{
		long now = System.currentTimeMillis();
		
		// edge case: the block may have been broken between scanning and
		// acting on it (e.g. another player destroyed the tripwire)
		if(!CustomCropDetector.isMature(MC.level, pos, requireNoteBlock))
			return false;
		
		boolean harvested = CustomCropHarvester.harvest(pos, antiCheat);
		
		if(!harvested)
		{
			// edge case: hotbar full (or otherwise unable to harvest) -
			// pause CustomCrops for 1 second after 5 consecutive failures
			customCropFailCount++;
			if(customCropFailCount >= 5)
			{
				customCropsPauseUntil = now + 1000;
				customCropFailCount = 0;
			}
			return false;
		}
		
		customCropFailCount = 0;
		harvestedCount++;
		
		// edge case: random delay (and, inside CustomCropHarvester, random
		// rotation jitter) to avoid robotic, inhuman timing patterns
		long pingDelay = getPingBasedDelay();
		long antiCheatDelay =
			antiCheat ? 50 + ThreadLocalRandom.current().nextInt(51) : 0;
		nextCustomCropActionTime = now + Math.max(pingDelay, antiCheatDelay);
		
		return true;
	}
	
	/**
	 * Returns a delay (in ms, capped at 500) based on the player's current
	 * ping, used to avoid harvesting faster than a laggy server can process.
	 */
	private long getPingBasedDelay()
	{
		if(MC.player == null || MC.player.connection == null)
			return 0;
		
		PlayerInfo info =
			MC.player.connection.getPlayerInfo(MC.player.getUUID());
		int ping = info != null ? info.getLatency() : 0;
		
		return Math.min(500, Math.max(0, ping));
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		renderer.render(matrixStack);
		
		if(renderer.drawBlocksToHarvest.isChecked())
			overlay.render(matrixStack, partialTicks, currentlyMining);
	}
	
	/**
	 * Returns true if AutoFarm is currently harvesting or replanting something.
	 */
	public boolean isBusy()
	{
		return busy;
	}
	
	private boolean replant(List<BlockPos> blocksToReplant)
	{
		if(MC.rightClickDelay > 0)
			return false;
		
		if(MC.gameMode.isDestroying() || MC.player.isHandsBusy())
			return false;
		
		Optional<Item> heldSeed =
			blocksToReplant.stream().map(replantingSpots::get)
				.filter(Objects::nonNull).map(AutoFarmPlantType::getSeedItem)
				.distinct().filter(MC.player::isHolding).findFirst();
		
		if(heldSeed.isPresent())
		{
			Item item = heldSeed.get();
			InteractionHand hand = MC.player.getMainHandItem().is(item)
				? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
			
			for(BlockPos pos : blocksToReplant)
			{
				AutoFarmPlantType plantType = replantingSpots.get(pos);
				if(plantType == null || plantType.getSeedItem() != item)
					continue;
				
				BlockPlacingParams params =
					BlockPlacer.getBlockPlacingParams(pos);
				if(params == null || params.distanceSq() > range.getValueSq())
					continue;
				
				if(checkLOS.isChecked() && !params.lineOfSight())
					continue;
				
				MC.rightClickDelay = 4;
				faceTarget.face(params.hitVec());
				InteractionSimulator.rightClickBlock(params.toHitResult(), hand,
					swingHand.getSelected());
				return true;
			}
		}
		
		for(BlockPos pos : blocksToReplant)
		{
			BlockPlacingParams params = BlockPlacer.getBlockPlacingParams(pos);
			if(params == null || params.distanceSq() > range.getValueSq())
				continue;
			
			AutoFarmPlantType plantType = replantingSpots.get(pos);
			if(plantType == null)
				continue;
			
			if(InventoryUtils.selectItem(plantType.getSeedItem())
				|| InventoryUtils.giveCreativeItem(plantType.getSeedItem()))
				return true;
		}
		
		return false;
	}
	
	private boolean harvestByInteracting(List<BlockPos> blocksToInteract)
	{
		if(MC.rightClickDelay > 0)
			return false;
		
		if(MC.gameMode.isDestroying() || MC.player.isHandsBusy())
			return false;
		
		for(BlockPos pos : blocksToInteract)
		{
			BlockBreakingParams params =
				BlockBreaker.getBlockBreakingParams(pos);
			if(params == null || params.distanceSq() > range.getValueSq())
				continue;
			
			if(checkLOS.isChecked() && !params.lineOfSight())
				continue;
			
			if(MC.player.getMainHandItem().is(Items.BONE_MEAL))
				return InventoryUtils.selectItem(s -> !s.is(Items.BONE_MEAL));
			
			MC.rightClickDelay = 4;
			faceTarget.face(params.hitVec());
			InteractionSimulator.rightClickBlock(params.toHitResult(),
				swingHand.getSelected());
			return true;
		}
		
		return false;
	}
	
	private void harvestByMining(List<BlockPos> blocksToMine)
	{
		double rangeSq = range.getValueSq();
		Stream<BlockBreakingParams> stream = blocksToMine.stream()
			.map(BlockBreaker::getBlockBreakingParams).filter(Objects::nonNull)
			.filter(params -> params.distanceSq() <= rangeSq);
		
		if(checkLOS.isChecked())
			stream = stream.filter(BlockBreakingParams::lineOfSight);
		
		stream = stream.sorted(BlockBreaker.comparingParams());
		
		// Break all blocks in creative mode
		if(MC.player.getAbilities().instabuild
			&& faceTarget.getSelected() == FaceTarget.OFF)
		{
			MC.gameMode.stopDestroyBlock();
			overlay.resetProgress();
			
			List<BlockPos> blocks = cache
				.filterOutRecentBlocks(stream.map(BlockBreakingParams::pos));
			if(blocks.isEmpty())
				return;
			
			currentlyMining = blocks.get(0);
			BlockBreaker.breakBlocksWithPacketSpam(blocks);
			swingHand.swing(InteractionHand.MAIN_HAND);
			return;
		}
		
		// Break the first valid block in survival mode
		currentlyMining = stream.filter(this::breakOneBlock)
			.map(BlockBreakingParams::pos).findFirst().orElse(null);
		
		if(currentlyMining == null)
		{
			MC.gameMode.stopDestroyBlock();
			overlay.resetProgress();
			return;
		}
		
		overlay.updateProgress();
	}
	
	private boolean breakOneBlock(BlockBreakingParams params)
	{
		faceTarget.face(params.hitVec());
		
		if(!MC.gameMode.continueDestroyBlock(params.pos(), params.side()))
			return false;
		
		swingHand.swing(InteractionHand.MAIN_HAND);
		return true;
	}
}
