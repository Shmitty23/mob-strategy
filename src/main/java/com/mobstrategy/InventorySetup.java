package com.mobstrategy;

import java.util.ArrayList;
import java.util.List;

/** A recommended inventory: 28 slots, empty slots are null. */
class InventorySetup
{
	/** Name of the inventory, e.g. "Melee" or "Inventory 1". */
	String name;

	List<InventoryItem> slots = new ArrayList<>();
}
