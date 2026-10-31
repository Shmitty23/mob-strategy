package com.mobstrategy;

/** One item in an inventory slot. */
class InventoryItem
{
	String name;

	/** How many, or 0 if the wiki didn't say. */
	int quantity;

	/** True if the item is noted (shown as a bank note). */
	boolean noted;
}
