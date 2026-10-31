package com.mobstrategy;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Gear tab: a setup picker, an equipment grid laid out like the in-game
 * equipment screen, a special attack box, and a 4x7 inventory grid.
 * Clicking an equipment slot pops up every option for it, best to worst.
 */
class GearView extends JPanel
{
	private static final Dimension SLOT_SIZE = new Dimension(40, 40);
	private static final Dimension INVENTORY_SIZE = new Dimension(38, 34);
	/**
	 * Text widths for wrapped text. Swing draws HTML text about 1.33x wider than the
	 * number given, so these are kept small enough to fit inside the ~190px side panel.
	 */
	private static final int OPTION_TEXT_WIDTH = 125;
	private static final int HINT_WIDTH = 125;
	private static final Dimension ICON_SIZE = new Dimension(36, 32);

	/**
	 * Where each slot sits in the 3-wide, 5-tall equipment grid (row * 3 + column),
	 * matching the in-game equipment screen.
	 */
	private static final Map<String, Integer> GRID_POSITION = new HashMap<>();

	static
	{
		GRID_POSITION.put("head", 1);
		GRID_POSITION.put("cape", 3);
		GRID_POSITION.put("neck", 4);
		GRID_POSITION.put("ammo", 5);
		GRID_POSITION.put("weapon", 6);
		GRID_POSITION.put("2h", 6); // two-handed weapons share the weapon slot
		GRID_POSITION.put("body", 7);
		GRID_POSITION.put("shield", 8);
		GRID_POSITION.put("legs", 10);
		GRID_POSITION.put("hands", 12);
		GRID_POSITION.put("feet", 13);
		GRID_POSITION.put("ring", 14);
	}

	/** Slot names for empty tiles' tooltips. */
	private static final String[] POSITION_NAMES = {
		null, "Head", null,
		"Cape", "Neck", "Ammo",
		"Weapon", "Body", "Shield",
		null, "Legs", null,
		"Hands", "Feet", "Ring",
	};

	private final GuideStore store;
	private final ItemManager itemManager;

	private final JComboBox<String> setupDropdown = new JComboBox<>();
	private final JLabel noGearLabel = new JLabel();
	private final JPanel equipmentArea = new JPanel();
	private final JLabel[] gridTiles = new JLabel[15];
	private final JLabel specTile = newTile(SLOT_SIZE);
	private final JPanel specRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));

	private final JPanel inventoryArea = new JPanel();
	private final JComboBox<String> inventoryDropdown = new JComboBox<>();
	private final JLabel[] inventoryTiles = new JLabel[28];

	/** The list of options that opens under the grid when you click a slot. */
	private final JPanel optionsArea = new JPanel();
	private final JLabel hint = smallText("<html><body style='width: " + HINT_WIDTH + "px; text-align: center'>"
		+ "Click a slot to see every option, best to worst.</body></html>");
	private JLabel selectedTile;

	/** Which slots each equipment tile is currently showing. */
	private final Map<JLabel, List<GearSlot>> tileSlots = new HashMap<>();

	private MonsterGuide guide;
	/** True when there's one inventory per gear setup, so picking a setup also picks its inventory. */
	private boolean inventoryFollowsSetup;

	GearView(GuideStore store, ItemManager itemManager)
	{
		this.store = store;
		this.itemManager = itemManager;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// A fixed preferred width stops long setup names from stretching the whole panel
		setupDropdown.setPreferredSize(new Dimension(150, 25));
		setupDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
		setupDropdown.setAlignmentX(LEFT_ALIGNMENT);
		setupDropdown.setFocusable(false);
		setupDropdown.addActionListener(e -> showSelectedSetup());

		noGearLabel.setFont(FontManager.getRunescapeSmallFont());
		noGearLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noGearLabel.setAlignmentX(LEFT_ALIGNMENT);
		noGearLabel.setText("<html><body style='width: " + HINT_WIDTH + "px'>No gear table on the wiki for this one. "
			+ "Check the Notes tab or open the wiki page.</body></html>");

		buildEquipmentArea();
		buildInventoryArea();

		add(setupDropdown);
		add(Box.createVerticalStrut(6));
		add(noGearLabel);
		add(equipmentArea);
		add(Box.createVerticalStrut(10));
		add(inventoryArea);
	}

	// ---------------------------------------------------------------- building

	private void buildEquipmentArea()
	{
		JPanel grid = new JPanel(new GridLayout(5, 3, 4, 4));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < gridTiles.length; i++)
		{
			if (POSITION_NAMES[i] == null)
			{
				JPanel gap = new JPanel();
				gap.setOpaque(false);
				grid.add(gap);
				continue;
			}
			JLabel tile = newTile(SLOT_SIZE);
			addSlotClick(tile);
			gridTiles[i] = tile;
			grid.add(tile);
		}

		JLabel specLabel = smallText("Spec weapon");
		addSlotClick(specTile);
		specRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Everything in this column must line up the same way, or Swing shifts it sideways
		specRow.setAlignmentX(LEFT_ALIGNMENT);
		specRow.add(specLabel);
		specRow.add(specTile);

		equipmentArea.setLayout(new BoxLayout(equipmentArea, BoxLayout.Y_AXIS));
		equipmentArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		equipmentArea.setAlignmentX(LEFT_ALIGNMENT);
		equipmentArea.add(centered(grid));
		equipmentArea.add(Box.createVerticalStrut(6));
		equipmentArea.add(specRow);
		hint.setHorizontalAlignment(SwingConstants.CENTER);
		equipmentArea.add(Box.createVerticalStrut(4));
		equipmentArea.add(centered(hint));

		optionsArea.setLayout(new BoxLayout(optionsArea, BoxLayout.Y_AXIS));
		optionsArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
		optionsArea.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		optionsArea.setAlignmentX(LEFT_ALIGNMENT);
		optionsArea.setVisible(false);
		equipmentArea.add(Box.createVerticalStrut(6));
		equipmentArea.add(optionsArea);
	}

	private void buildInventoryArea()
	{
		inventoryArea.setLayout(new BoxLayout(inventoryArea, BoxLayout.Y_AXIS));
		inventoryArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		inventoryArea.setAlignmentX(LEFT_ALIGNMENT);

		JLabel header = new JLabel("Inventory");
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(Color.WHITE);
		header.setAlignmentX(LEFT_ALIGNMENT);

		inventoryDropdown.setPreferredSize(new Dimension(150, 25));
		inventoryDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
		inventoryDropdown.setAlignmentX(LEFT_ALIGNMENT);
		inventoryDropdown.setFocusable(false);
		inventoryDropdown.addActionListener(e ->
		{
			if (!inventoryFollowsSetup)
			{
				showInventory(inventoryDropdown.getSelectedIndex());
			}
		});

		JPanel grid = new JPanel(new GridLayout(7, 4, 2, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < inventoryTiles.length; i++)
		{
			inventoryTiles[i] = newTile(INVENTORY_SIZE);
			grid.add(inventoryTiles[i]);
		}

		inventoryArea.add(header);
		inventoryArea.add(Box.createVerticalStrut(4));
		inventoryArea.add(inventoryDropdown);
		inventoryArea.add(Box.createVerticalStrut(4));
		inventoryArea.add(centered(grid));
	}

	private static JLabel newTile(Dimension size)
	{
		JLabel tile = new JLabel();
		tile.setPreferredSize(size);
		tile.setMinimumSize(size);
		tile.setOpaque(true);
		tile.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tile.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		tile.setHorizontalAlignment(SwingConstants.CENTER);
		tile.setVerticalAlignment(SwingConstants.CENTER);
		tile.setFont(FontManager.getRunescapeSmallFont());
		tile.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return tile;
	}

	private static JLabel smallText(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	private static JPanel centered(Component c)
	{
		JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.add(c);
		return wrapper;
	}

	private void addSlotClick(JLabel tile)
	{
		tile.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				List<GearSlot> slots = tileSlots.get(tile);
				if (slots == null || slots.isEmpty())
				{
					return;
				}
				if (tile == selectedTile)
				{
					closeOptions(); // clicking the same slot again closes the list
				}
				else
				{
					openOptions(tile, slots);
				}
			}
		});
	}

	// ---------------------------------------------------------------- showing a guide

	void show(MonsterGuide guide)
	{
		this.guide = guide;

		DefaultComboBoxModel<String> styles = new DefaultComboBoxModel<>();
		for (GearSetup setup : guide.setups)
		{
			styles.addElement(setup.style);
		}
		inventoryFollowsSetup = guide.setups.size() > 1 && guide.inventories.size() == guide.setups.size();

		DefaultComboBoxModel<String> inventories = new DefaultComboBoxModel<>();
		for (InventorySetup inventory : guide.inventories)
		{
			inventories.addElement(inventory.name);
		}
		inventoryDropdown.setModel(inventories);
		inventoryDropdown.setVisible(guide.inventories.size() > 1 && !inventoryFollowsSetup);
		inventoryArea.setVisible(!guide.inventories.isEmpty());

		setupDropdown.setModel(styles);
		setupDropdown.setVisible(guide.setups.size() > 1);
		noGearLabel.setVisible(guide.setups.isEmpty());
		equipmentArea.setVisible(!guide.setups.isEmpty());

		if (!guide.setups.isEmpty())
		{
			setupDropdown.setSelectedIndex(0); // also shows the setup
		}
		if (!guide.inventories.isEmpty())
		{
			inventoryDropdown.setSelectedIndex(0);
			showInventory(0);
		}
		showSelectedSetup();
	}

	private void showSelectedSetup()
	{
		if (guide == null || guide.setups.isEmpty())
		{
			return;
		}
		int index = Math.max(0, Math.min(setupDropdown.getSelectedIndex(), guide.setups.size() - 1));
		GearSetup setup = guide.setups.get(index);

		closeOptions();

		// Group the setup's slots by which tile they belong on
		tileSlots.clear();
		for (GearSlot slot : setup.slots)
		{
			if (slot.key == null || slot.options == null || slot.options.isEmpty())
			{
				continue; // old or broken data
			}
			JLabel tile = slot.key.equals("special") ? specTile : tileFor(slot.key);
			if (tile != null)
			{
				tileSlots.computeIfAbsent(tile, t -> new ArrayList<>()).add(slot);
			}
		}

		for (int i = 0; i < gridTiles.length; i++)
		{
			if (gridTiles[i] != null)
			{
				fillSlotTile(gridTiles[i], POSITION_NAMES[i]);
			}
		}
		fillSlotTile(specTile, "Special attack");
		specRow.setVisible(tileSlots.containsKey(specTile));

		if (inventoryFollowsSetup)
		{
			showInventory(index);
		}
		revalidate();
		repaint();
	}

	private JLabel tileFor(String slotKey)
	{
		Integer position = GRID_POSITION.get(slotKey);
		return position == null ? null : gridTiles[position];
	}

	/** Shows the best option's icon on a tile, with a tooltip. */
	private void fillSlotTile(JLabel tile, String slotName)
	{
		clearTile(tile);
		List<GearSlot> slots = tileSlots.get(tile);
		if (slots == null || slots.isEmpty())
		{
			tile.setToolTipText(slotName + ": nothing recommended");
			tile.setCursor(Cursor.getDefaultCursor());
			return;
		}

		GearOption best = slots.get(0).options.get(0);
		int bestId = firstKnownId(best);
		if (bestId > 0)
		{
			itemManager.getImage(bestId).addTo(tile);
		}
		else
		{
			tile.setText("<html><center>" + escape(shortName(best.text)) + "</center></html>");
		}

		int total = 0;
		for (GearSlot slot : slots)
		{
			total += slot.options.size();
		}
		tile.setToolTipText("<html><b>" + escape(slotName) + "</b><br>Best: " + escape(best.text) + anyVersionText(best)
			+ "<br><i>Click to see all " + total + " option" + (total == 1 ? "" : "s") + "</i></html>");
		tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	/** Opens the list under the grid: every option for the slot(s), best first, with icons. */
	private void openOptions(JLabel tile, List<GearSlot> slots)
	{
		closeOptions();
		selectedTile = tile;
		tile.setBorder(BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE));

		for (GearSlot slot : slots)
		{
			JLabel header = new JLabel("<html><b>" + escape(slot.slot) + "</b> - best to worst</html>");
			header.setFont(FontManager.getRunescapeSmallFont());
			header.setForeground(ColorScheme.BRAND_ORANGE);
			header.setAlignmentX(LEFT_ALIGNMENT);
			optionsArea.add(header);
			optionsArea.add(Box.createVerticalStrut(4));

			for (int i = 0; i < slot.options.size(); i++)
			{
				optionsArea.add(optionRow(i + 1, slot.options.get(i)));
				optionsArea.add(Box.createVerticalStrut(6));
			}
			optionsArea.add(Box.createVerticalStrut(4));
		}

		JLabel close = new JLabel("<html><u>Close</u></html>");
		close.setFont(FontManager.getRunescapeSmallFont());
		close.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		close.setAlignmentX(LEFT_ALIGNMENT);
		close.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				closeOptions();
			}
		});
		optionsArea.add(close);

		hint.setVisible(false);
		optionsArea.setVisible(true);
		revalidate();
		repaint();
	}

	/**
	 * One entry in the options list: "1. Item A + Item B" (wrapped to fit),
	 * then an icon for every item in it, so combos like "Ring of suffering (ri) + Bellator ring"
	 * show both rings. Hover an icon to see its name.
	 */
	private JPanel optionRow(int rank, GearOption option)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);

		JLabel text = new JLabel("<html><body style='width: " + OPTION_TEXT_WIDTH + "px'>"
			+ rank + ". " + escape(option.text) + anyVersionText(option) + "</body></html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(rank == 1 ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		text.setAlignmentX(LEFT_ALIGNMENT);
		row.add(text);

		// Up to 4 icons per line; more items wrap onto the next line
		JPanel icons = new JPanel(new GridLayout(0, 4, 2, 2));
		icons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		int shown = 0;
		for (String item : option.items)
		{
			int id = store.itemId(item);
			if (id <= 0)
			{
				continue;
			}
			JLabel icon = new JLabel();
			icon.setPreferredSize(ICON_SIZE);
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			String picture = store.pictureOf(item);
			icon.setToolTipText(picture == null ? item
				: item + " (" + store.anyVersionNote(item) + ", picture shows " + picture + ")");
			itemManager.getImage(id).addTo(icon);
			icons.add(icon);
			shown++;
		}
		if (shown > 0)
		{
			JPanel iconLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
			iconLine.setBackground(ColorScheme.DARK_GRAY_COLOR);
			iconLine.setAlignmentX(LEFT_ALIGNMENT);
			iconLine.add(icons);
			row.add(iconLine);
		}

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private void closeOptions()
	{
		if (selectedTile != null)
		{
			selectedTile.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
			selectedTile = null;
		}
		optionsArea.removeAll();
		optionsArea.setVisible(false);
		hint.setVisible(true);
		revalidate();
		repaint();
	}

	/** The icon to use for an option: the first item in it that we have an icon for. */
	private int firstKnownId(GearOption option)
	{
		for (String item : option.items)
		{
			int id = store.itemId(item);
			if (id > 0)
			{
				return id;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- inventory

	private void showInventory(int index)
	{
		if (guide == null || guide.inventories.isEmpty())
		{
			return;
		}
		InventorySetup inventory = guide.inventories.get(Math.max(0, Math.min(index, guide.inventories.size() - 1)));
		for (int i = 0; i < inventoryTiles.length; i++)
		{
			JLabel tile = inventoryTiles[i];
			clearTile(tile);
			InventoryItem item = i < inventory.slots.size() ? inventory.slots.get(i) : null;
			if (item == null)
			{
				tile.setToolTipText(null);
				continue;
			}
			showItem(tile, item.name, item.name, item.quantity);
			String tooltip = item.name + (item.quantity > 1 ? " x " + item.quantity : "") + (item.noted ? " (noted)" : "");
			tile.setToolTipText(tooltip);
		}
	}

	// ---------------------------------------------------------------- icons

	/** Draws an item's icon on a tile, or a short name if we don't know its icon. */
	private void showItem(JLabel tile, String itemName, String fallbackText, int quantity)
	{
		int id = store.itemId(itemName);
		if (id > 0)
		{
			itemManager.getImage(id, Math.max(quantity, 1), quantity > 1).addTo(tile);
		}
		else
		{
			tile.setText("<html><center>" + escape(shortName(fallbackText)) + "</center></html>");
		}
	}

	private static void clearTile(JLabel tile)
	{
		tile.setIcon(null);
		tile.setText("");
	}

	/** A few letters to show when there's no icon, e.g. "Prot.." */
	private static String shortName(String text)
	{
		if (text == null)
		{
			return "?";
		}
		String first = text.split("[ /(]")[0];
		return first.length() > 6 ? first.substring(0, 5) + ".." : first;
	}

	/**
	 * " (any god)" when the option is a group like "God cape" or "Blessed body", where any
	 * version works and the picture just shows one of them. Empty for normal items.
	 */
	private String anyVersionText(GearOption option)
	{
		for (String item : option.items)
		{
			String note = store.anyVersionNote(item);
			if (note != null)
			{
				return " <span style='color:#ff981f'>(" + note + ")</span>";
			}
		}
		return "";
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
