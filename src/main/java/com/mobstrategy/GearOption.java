package com.mobstrategy;

import java.util.ArrayList;
import java.util.List;

/** One ranked choice for a slot. Usually one item, sometimes "Item A / Item B". */
class GearOption
{
	/** The text as written on the wiki, e.g. "Armadyl helmet / Void ranger helm". */
	String text;

	/** The item names in that text, used to find their icons. The first one is the main icon. */
	List<String> items = new ArrayList<>();
}
