package com.mobstrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the plugin knows about fighting one monster or boss,
 * taken from its strategy page on the OSRS Wiki.
 */
class MonsterGuide
{
	/** Name shown in the panel, e.g. "Vorkath". */
	String name;

	/** Wiki page it came from, e.g. "Vorkath/Strategies". */
	String page;

	/** One entry per gear table on the page (Melee, Ranged, Magic...). */
	List<GearSetup> setups = new ArrayList<>();

	/** One entry per recommended inventory on the page. */
	List<InventorySetup> inventories = new ArrayList<>();

	/** Text from sections about gear and inventory (shown in the Notes tab). */
	List<GuideSection> gearNotes = new ArrayList<>();

	/** Text from every other section: mechanics, phases, tips... */
	List<GuideSection> tactics = new ArrayList<>();
}
