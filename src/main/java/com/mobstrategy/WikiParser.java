package com.mobstrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw text of a wiki strategy page into a {@link MonsterGuide}.
 *
 * The wiki is written in "wikitext": [[links]], {{templates}}, '''bold''' and so on.
 * Gear tables are written with the {{Recommended equipment}} template, which lists
 * items slot by slot (head1, head2, body1...), so they are easy to read reliably.
 * Everything else on the page is turned into plain text sections.
 */
final class WikiParser
{
	/** Template parameter name -> slot name shown in the panel, in equipment-screen order. */
	private static final String[][] SLOTS = {
		{"head", "Head"},
		{"cape", "Cape"},
		{"neck", "Neck"},
		{"ammo", "Ammo"},
		{"weapon", "Weapon"},
		{"2h", "Two-handed"},
		{"body", "Body"},
		{"shield", "Shield"},
		{"legs", "Legs"},
		{"hands", "Hands"},
		{"feet", "Feet"},
		{"ring", "Ring"},
		{"special", "Special attack"},
	};

	/** The template allows up to 5 items per slot. */
	private static final int MAX_RANK = 5;

	/** Sections that aren't useful in game. */
	private static final Set<String> SKIP_SECTIONS = new HashSet<>(Arrays.asList(
		"references", "gallery", "trivia", "changes", "see also", "external links",
		"footnotes", "update history", "notes and references"
	));

	/** Sections with these words in the heading go under the Gear tab instead of Tactics. */
	private static final Pattern GEAR_HEADING = Pattern.compile(
		"equipment|gear|setup|inventory|armou?r|weapon", Pattern.CASE_INSENSITIVE);

	private static final Pattern HEADING = Pattern.compile("^(={2,6})\\s*(.+?)\\s*\\1\\s*$");
	private static final Pattern LIST_LINE = Pattern.compile("^([*#:;]+)\\s*(.*)$");
	private static final Pattern TAB_NAME = Pattern.compile("^(?:\\|-\\|)?\\s*([^=|{}\\[\\]<>]{1,40})=\\s*$");
	private static final Pattern INVENTORY_AMOUNT = Pattern.compile("\\\\(\\d+)");
	private static final Pattern PLAIN_LINK = Pattern.compile("\\[\\[([^|\\]#]+)");
	private static final Pattern FIRST_NUMBER = Pattern.compile("^\\s*(\\d+)");
	private static final Pattern TAB_LABEL = Pattern.compile("^(\\|-\\|)?\\s*[^=]{0,40}=\\s*$");
	private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
	private static final Pattern REF = Pattern.compile(
		"<ref[^>]*/>|<ref[^>]*>.*?</ref>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern REMOVED_BLOCKS = Pattern.compile(
		"<(gallery|math|syntaxhighlight|references)[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
	private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
	private static final Pattern EXT_LINK = Pattern.compile("\\[(?:https?:)?//[^\\s\\]]+\\s*([^\\]]*)\\]");
	private static final Pattern MAGIC_WORD = Pattern.compile("__[A-Z]+__");
	private static final Pattern SPACES = Pattern.compile("[ \\t\\u00a0]+");

	/**
	 * Item links are wrapped in these invisible markers while cleaning, so two items
	 * written right next to each other ("Item AItem B") can be split into "Item A / Item B".
	 */
	private static final char ITEM_START = '\u0001';
	private static final char ITEM_END = '\u0002';
	private static final Pattern ITEM_NEXT_TO_ITEM = Pattern.compile("\u0002[ \\t\\u00a0]*\u0001");
	private static final Pattern ITEM_MARKER = Pattern.compile("[\u0001\u0002]");

	private WikiParser()
	{
	}

	/** "Vorkath/Strategies" -> "Vorkath", "Slayer task/Bloodveld" -> "Bloodveld". */
	static String displayName(String title)
	{
		List<String> parts = new ArrayList<>(Arrays.asList(title.split("/")));
		if (parts.size() > 1 && parts.get(0).equalsIgnoreCase("Slayer task"))
		{
			parts.remove(0);
		}
		parts.removeIf(p -> p.equalsIgnoreCase("Strategies") || p.equalsIgnoreCase("Strategy"));
		return parts.isEmpty() ? title : String.join(" - ", parts);
	}

	static MonsterGuide parse(String pageTitle, String wikitext)
	{
		MonsterGuide guide = new MonsterGuide();
		guide.page = pageTitle;
		guide.name = displayName(pageTitle);

		String text = COMMENT.matcher(wikitext).replaceAll("");
		text = REF.matcher(text).replaceAll("");
		text = REMOVED_BLOCKS.matcher(text).replaceAll("");

		collectTemplates(text, guide);
		readSections(text, guide);
		return guide;
	}

	// ---------------------------------------------------------------- gear tables and inventories

	/**
	 * Finds every {{Recommended equipment}} and {{Inventory}} template, even ones nested
	 * in other templates, and remembers the tab name ("Melee=") written just before each.
	 */
	private static void collectTemplates(String text, MonsterGuide guide)
	{
		int previousEnd = 0;
		String tabLabel = "";
		for (int[] span : findTemplates(text))
		{
			Template t = Template.parse(text.substring(span[0], span[1]));
			tabLabel = findTabLabel(text, previousEnd, span[0], tabLabel);
			previousEnd = span[1];

			if (t.name.equals("recommended equipment"))
			{
				GearSetup setup = toSetup(t, tabLabel, guide.setups.size() + 1);
				if (!setup.slots.isEmpty())
				{
					guide.setups.add(setup);
				}
			}
			else if (t.name.equals("inventory"))
			{
				InventorySetup inventory = toInventory(t, tabLabel, guide.inventories.size() + 1);
				if (inventory != null)
				{
					guide.inventories.add(inventory);
				}
			}
			else
			{
				for (String value : t.allValues())
				{
					collectTemplates(value, guide);
				}
			}
		}
	}

	/**
	 * Tabbed boxes on the wiki look like "Melee=" on its own line, between <tabber> tags.
	 * Returns the tab we're currently in, or "" when outside a tabbed box.
	 */
	private static String findTabLabel(String text, int from, int to, String current)
	{
		String label = current;
		for (String line : text.substring(from, to).split("\n"))
		{
			String trimmed = line.trim();
			if (trimmed.toLowerCase(Locale.ROOT).contains("tabber>"))
			{
				label = "";
			}
			Matcher m = TAB_NAME.matcher(trimmed);
			if (m.matches())
			{
				label = cleanInline(m.group(1), false);
			}
		}
		return label;
	}

	private static GearSetup toSetup(Template t, String tabLabel, int number)
	{
		GearSetup setup = new GearSetup();
		String style = cleanInline(t.named.getOrDefault("style", ""), false);
		if (style.isEmpty())
		{
			style = tabLabel;
		}
		setup.style = style.isEmpty() ? "Setup " + number : style;

		for (String[] slot : SLOTS)
		{
			GearSlot gearSlot = new GearSlot();
			gearSlot.key = slot[0];
			gearSlot.slot = slot[1];
			for (int rank = 1; rank <= MAX_RANK; rank++)
			{
				String raw = t.named.getOrDefault(slot[0] + rank, "");
				String text = cleanInline(raw, true);
				if (text.isEmpty() || text.replace(" ", "").equalsIgnoreCase("N/A"))
				{
					continue;
				}
				GearOption option = new GearOption();
				option.text = text;
				option.items = itemNames(raw);
				gearSlot.options.add(option);
			}
			if (!gearSlot.options.isEmpty())
			{
				setup.slots.add(gearSlot);
			}
		}
		return setup;
	}

	/**
	 * Reads {{Inventory|1=Shark|2=Prayer potion(4)\\3|...}}.
	 * Each slot is "Item name", optionally followed by ";link" and/or "\\amount".
	 */
	private static InventorySetup toInventory(Template t, String tabLabel, int number)
	{
		InventorySetup inventory = new InventorySetup();
		inventory.name = tabLabel.isEmpty() ? "Inventory " + number : tabLabel;
		boolean any = false;
		for (int i = 1; i <= 28; i++)
		{
			String raw = t.named.containsKey(String.valueOf(i))
				? t.named.get(String.valueOf(i))
				: t.positional(i - 1);
			InventoryItem item = toInventoryItem(raw);
			inventory.slots.add(item);
			any |= item != null;
		}
		return any ? inventory : null;
	}

	private static InventoryItem toInventoryItem(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String v = raw.replace("[", "").replace("]", "").trim();
		if (v.isEmpty())
		{
			return null;
		}
		String name = cleanInline(v.split("[;\\\\]")[0], false);
		if (name.isEmpty())
		{
			return null;
		}
		InventoryItem item = new InventoryItem();
		item.name = name;
		Matcher amount = INVENTORY_AMOUNT.matcher(v);
		item.quantity = amount.find() ? Integer.parseInt(amount.group(1)) : 0;
		item.noted = v.contains(";n");
		return item;
	}

	/** The item names linked in a gear cell (from {{plink|...}}, or plain [[links]] if there are none). */
	static List<String> itemNames(String raw)
	{
		List<String> names = new ArrayList<>();
		collectPlinks(COMMENT.matcher(raw).replaceAll(""), names);
		if (names.isEmpty())
		{
			Matcher m = PLAIN_LINK.matcher(raw);
			while (m.find())
			{
				String target = m.group(1).trim();
				String lower = target.toLowerCase(Locale.ROOT);
				if (!lower.startsWith("file:") && !lower.startsWith("image:") && !lower.startsWith("category:"))
				{
					names.add(target);
				}
			}
		}
		return names;
	}

	/** Every item and page linked anywhere in some wikitext ({{plink}}s and [[links]]), in page order. */
	static List<String> linkedNames(String wikitext)
	{
		String text = COMMENT.matcher(wikitext).replaceAll("");
		List<String> names = new ArrayList<>();
		collectPlinks(text, names);
		Matcher m = PLAIN_LINK.matcher(text);
		while (m.find())
		{
			String target = m.group(1).trim();
			String lower = target.toLowerCase(Locale.ROOT);
			if (!target.isEmpty() && !names.contains(target) && !lower.startsWith("file:")
				&& !lower.startsWith("image:") && !lower.startsWith("category:"))
			{
				names.add(target);
			}
		}
		return names;
	}

	private static void collectPlinks(String text, List<String> names)
	{
		for (int[] span : findTemplates(text))
		{
			Template t = Template.parse(text.substring(span[0], span[1]));
			if (t.name.startsWith("plink"))
			{
				String name = t.positional(0);
				int hash = name.indexOf('#');
				name = cleanInline(hash >= 0 ? name.substring(0, hash) : name, false);
				if (!name.isEmpty() && !names.contains(name))
				{
					names.add(name);
				}
			}
			else
			{
				for (String value : t.allValues())
				{
					collectPlinks(value, names);
				}
			}
		}
	}

	/**
	 * Reads the game item ID from an item's wiki page ({{Infobox Item|id=12006}}).
	 * Returns null if the page isn't an item (a prayer or spell, for example).
	 */
	static Integer findItemId(String wikitext)
	{
		return findItemIdIn(COMMENT.matcher(wikitext).replaceAll(""));
	}

	/** Looks for {{Infobox Item}}, also inside other templates (items with versions wrap it in a switch box). */
	private static Integer findItemIdIn(String text)
	{
		for (int[] span : findTemplates(text))
		{
			Template t = Template.parse(text.substring(span[0], span[1]));
			if (t.name.equals("infobox item"))
			{
				// "id", or "id1", "id2"... for items with several versions
				for (Map.Entry<String, String> param : t.named.entrySet())
				{
					if (param.getKey().matches("id\\d*"))
					{
						Matcher m = FIRST_NUMBER.matcher(param.getValue());
						if (m.find())
						{
							return Integer.parseInt(m.group(1));
						}
					}
				}
				continue;
			}
			for (String value : t.allValues())
			{
				Integer id = findItemIdIn(value);
				if (id != null)
				{
					return id;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- text sections

	private static void readSections(String text, MonsterGuide guide)
	{
		// links are cleaned on the whole page first, because image links can span several lines
		String prose = removeTables(cleanLinks(expandTemplates(text)));
		prose = MAGIC_WORD.matcher(prose).replaceAll("");

		String heading = "Overview";
		int level = 2;
		boolean parentIsGear = false;
		boolean currentIsGear = false;
		StringBuilder body = new StringBuilder();

		for (String rawLine : prose.split("\n"))
		{
			String line = rawLine.trim();

			Matcher h = HEADING.matcher(line);
			if (h.matches())
			{
				addSection(guide, heading, level, body, currentIsGear);
				heading = cleanInline(h.group(2), false);
				level = h.group(1).length();
				boolean gearHeading = GEAR_HEADING.matcher(heading).find();
				if (level <= 2)
				{
					parentIsGear = gearHeading;
					currentIsGear = gearHeading;
				}
				else
				{
					// sub-sections of a gear section count as gear too
					currentIsGear = parentIsGear || gearHeading;
				}
				body.setLength(0);
				continue;
			}

			if (line.isEmpty())
			{
				body.append('\n');
				continue;
			}
			// leftovers from tables and tabbed boxes
			if (line.startsWith("|") || line.startsWith("!") || TAB_LABEL.matcher(line).matches())
			{
				continue;
			}

			String out;
			Matcher list = LIST_LINE.matcher(line);
			if (list.matches())
			{
				String markers = list.group(1);
				String content = cleanInline(list.group(2), false);
				if (content.isEmpty())
				{
					continue;
				}
				char kind = markers.charAt(markers.length() - 1);
				int depth = markers.length();
				if (kind == '*' || kind == '#')
				{
					out = indent(depth - 1) + "\u2022 " + content;
				}
				else if (kind == ':')
				{
					out = indent(depth) + content;
				}
				else
				{
					out = content;
				}
			}
			else
			{
				out = cleanInline(line, false);
			}

			if (!out.isEmpty())
			{
				body.append(out).append('\n');
			}
		}
		addSection(guide, heading, level, body, currentIsGear);
	}

	private static void addSection(MonsterGuide guide, String heading, int level, StringBuilder body, boolean isGear)
	{
		String text = body.toString().replaceAll("\n{3,}", "\n\n").trim();
		if (text.isEmpty() || heading.isEmpty() || SKIP_SECTIONS.contains(heading.toLowerCase(Locale.ROOT)))
		{
			return;
		}

		GuideSection section = new GuideSection();
		section.heading = heading;
		section.level = level;
		section.text = text;

		(isGear ? guide.gearNotes : guide.tactics).add(section);
	}

	private static String indent(int depth)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++)
		{
			sb.append("  ");
		}
		return sb.toString();
	}

	/** Removes wiki tables: everything between a "{|" line and its "|}" line. */
	private static String removeTables(String text)
	{
		StringBuilder out = new StringBuilder();
		int depth = 0;
		for (String line : text.split("\n"))
		{
			String t = line.trim();
			if (t.startsWith("{|"))
			{
				depth++;
				continue;
			}
			if (depth > 0)
			{
				if (t.startsWith("|}"))
				{
					depth--;
				}
				continue;
			}
			out.append(line).append('\n');
		}
		return out.toString();
	}

	// ---------------------------------------------------------------- cleaning helpers

	/**
	 * Turns a piece of wikitext into plain text on one line.
	 * itemMode: line breaks become " / " (for gear cells listing several items).
	 */
	static String cleanInline(String s, boolean itemMode)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		s = COMMENT.matcher(s).replaceAll("");
		s = REF.matcher(s).replaceAll("");
		s = expandTemplates(s);
		s = cleanLinks(s);
		s = EXT_LINK.matcher(s).replaceAll("$1");
		s = s.replace("'''", "").replace("''", "");
		s = BR.matcher(s).replaceAll(itemMode ? " / " : " ");
		s = HTML_TAG.matcher(s).replaceAll("");
		s = decodeEntities(s);
		s = s.replace('\n', ' ');
		s = ITEM_NEXT_TO_ITEM.matcher(s).replaceAll(" / ");
		s = ITEM_MARKER.matcher(s).replaceAll("");
		s = SPACES.matcher(s).replaceAll(" ").trim();
		if (itemMode)
		{
			s = s.replaceAll("(\\s*/\\s*)+", " / ").replaceAll("^/\\s*|\\s*/$", "").trim();
			s = s.replaceAll("\\s*>\\s*/\\s*", " > ").replaceAll("\\s*>$", "");
		}
		return s;
	}

	/** Replaces each {{template}} with its plain-text meaning, or removes it. */
	private static String expandTemplates(String s)
	{
		List<int[]> spans = findTemplates(s);
		if (spans.isEmpty())
		{
			return s;
		}
		StringBuilder out = new StringBuilder();
		int last = 0;
		for (int[] span : spans)
		{
			out.append(s, last, span[0]);
			out.append(inlineTemplate(Template.parse(s.substring(span[0], span[1]))));
			last = span[1];
		}
		out.append(s.substring(last));
		return out.toString();
	}

	/** What a template should look like as text. Unknown templates are dropped. */
	private static String inlineTemplate(Template t)
	{
		switch (t.name)
		{
			case "plink":
			case "plinkt":
			case "plinkp":
			case "plinkp-nobr":
			case "plink-nobr":
				String item = t.named.containsKey("txt")
					? cleanInline(t.named.get("txt"), false)
					: cleanInline(t.positional(0), false);
				return ITEM_START + item + ITEM_END;
			case "scp":
			case "skill clickpic":
				// {{SCP|Slayer|85}} -> "85 Slayer"
				return (cleanInline(t.positional(1), false) + " " + cleanInline(t.positional(0), false)).trim();
			case "coins":
				return cleanInline(t.positional(0), false) + " coins";
			case "nowrap":
			case "small":
			case "big":
			case "fairycode":
			case "keypress":
			case "key press":
				return cleanInline(t.positional(0), false);
			case "colour":
			case "color":
				return cleanInline(t.positional(1), false);
			case "!":
				return "|";
			case "=":
				return "=";
			default:
				return "";
		}
	}

	/** [[Page|text]] -> text, [[Page]] -> Page, [[File:...]] -> removed. */
	private static String cleanLinks(String s)
	{
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < s.length())
		{
			if (s.startsWith("[[", i))
			{
				int end = matchPair(s, i, "[[", "]]");
				if (end < 0)
				{
					out.append(s.substring(i));
					break;
				}
				String inner = s.substring(i + 2, end - 2);
				String lower = inner.trim().toLowerCase(Locale.ROOT);
				if (!(lower.startsWith("file:") || lower.startsWith("image:")
					|| lower.startsWith("category:") || lower.startsWith("media:")))
				{
					int pipe = topLevelIndexOf(inner, '|');
					String shown = pipe >= 0 ? inner.substring(pipe + 1) : inner;
					if (shown.startsWith(":"))
					{
						shown = shown.substring(1);
					}
					int hash = shown.indexOf('#');
					if (pipe < 0 && hash >= 0)
					{
						// [[Page#Section]] -> "Page"
						shown = shown.substring(0, hash);
					}
					out.append(cleanLinks(shown));
				}
				i = end;
			}
			else
			{
				out.append(s.charAt(i));
				i++;
			}
		}
		return out.toString();
	}

	private static String decodeEntities(String s)
	{
		return s.replace("&nbsp;", " ")
			.replace("&ndash;", "\u2013")
			.replace("&mdash;", "\u2014")
			.replace("&times;", "\u00d7")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&amp;", "&");
	}

	// ---------------------------------------------------------------- template scanning

	/** Start/end positions of every top-level {{...}} in the text. */
	static List<int[]> findTemplates(String s)
	{
		List<int[]> spans = new ArrayList<>();
		int i = 0;
		while (i < s.length() - 1)
		{
			if (s.startsWith("{{", i))
			{
				int end = matchPair(s, i, "{{", "}}");
				if (end < 0)
				{
					i += 2; // unbalanced braces: skip them
					continue;
				}
				spans.add(new int[]{i, end});
				i = end;
			}
			else
			{
				i++;
			}
		}
		return spans;
	}

	/** Given an opener at `start`, returns the index just after its matching closer, or -1. */
	private static int matchPair(String s, int start, String open, String close)
	{
		int depth = 0;
		int i = start;
		while (i < s.length() - 1)
		{
			if (s.startsWith(open, i))
			{
				depth++;
				i += 2;
			}
			else if (s.startsWith(close, i))
			{
				depth--;
				i += 2;
				if (depth == 0)
				{
					return i;
				}
			}
			else
			{
				i++;
			}
		}
		return -1;
	}

	/** Index of `c` that isn't inside nested {{ }} or [[ ]], or -1. */
	private static int topLevelIndexOf(String s, char c)
	{
		int depth = 0;
		for (int i = 0; i < s.length(); i++)
		{
			if (s.startsWith("{{", i) || s.startsWith("[[", i))
			{
				depth++;
				i++;
			}
			else if (s.startsWith("}}", i) || s.startsWith("]]", i))
			{
				depth--;
				i++;
			}
			else if (s.charAt(i) == c && depth == 0)
			{
				return i;
			}
		}
		return -1;
	}

	/** Splits on '|' characters that aren't inside nested {{ }} or [[ ]]. */
	private static List<String> splitTopLevel(String s)
	{
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < s.length(); i++)
		{
			if (s.startsWith("{{", i) || s.startsWith("[[", i))
			{
				depth++;
				i++;
			}
			else if (s.startsWith("}}", i) || s.startsWith("]]", i))
			{
				depth--;
				i++;
			}
			else if (s.charAt(i) == '|' && depth == 0)
			{
				parts.add(s.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(s.substring(start));
		return parts;
	}

	/** A parsed {{template|...}}: its name plus named and numbered values. */
	private static final class Template
	{
		String name;
		final Map<String, String> named = new LinkedHashMap<>();
		final List<String> positional = new ArrayList<>();

		static Template parse(String full)
		{
			Template t = new Template();
			List<String> parts = splitTopLevel(full.substring(2, full.length() - 2));
			t.name = normalizeName(parts.get(0));
			for (int i = 1; i < parts.size(); i++)
			{
				String part = parts.get(i);
				int eq = topLevelIndexOf(part, '=');
				String key = eq > 0 ? part.substring(0, eq).trim() : "";
				if (eq > 0 && !key.isEmpty() && key.length() <= 40 && !key.contains("\n"))
				{
					t.named.put(key.toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
				}
				else
				{
					t.positional.add(part.trim());
				}
			}
			return t;
		}

		private static String normalizeName(String raw)
		{
			String n = raw.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
			if (n.startsWith("template:"))
			{
				n = n.substring("template:".length()).trim();
			}
			return SPACES.matcher(n).replaceAll(" ");
		}

		String positional(int index)
		{
			return index < positional.size() ? positional.get(index) : "";
		}

		List<String> allValues()
		{
			List<String> all = new ArrayList<>(positional);
			all.addAll(named.values());
			return all;
		}
	}
}
