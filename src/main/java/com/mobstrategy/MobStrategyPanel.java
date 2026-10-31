package com.mobstrategy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel: a search box with live results, a dropdown of every monster,
 * and Gear / Tactics tabs for the chosen monster.
 */
class MobStrategyPanel extends PluginPanel
{
	private static final String WIKI_URL = "https://oldschool.runescape.wiki/";
	private static final int RESULT_ROW_HEIGHT = 30; // tall enough for the monster pictures
	private static final int MAX_VISIBLE_RESULTS = 8;
	// Swing draws HTML text about 1.33x wider than this number, so 130 really fills ~173px
	private static final int TEXT_WIDTH = 130;

	private static final String ORANGE = toHex(ColorScheme.BRAND_ORANGE);
	private static final String WHITE = toHex(Color.WHITE);
	private static final String GRAY = toHex(ColorScheme.LIGHT_GRAY_COLOR);

	private final GuideStore store;
	private final MobStrategyConfig config;
	private final List<String> monsters;

	// search
	private final IconTextField searchBar = new IconTextField();
	private final DefaultListModel<String> resultsModel = new DefaultListModel<>();
	private final JList<String> resultsList = new JList<>(resultsModel);
	private final JScrollPane resultsScroll = new JScrollPane(resultsList);
	private final JLabel noMatchesLabel = new JLabel("No monsters found");
	private final JComboBox<String> monsterDropdown = new JComboBox<>();

	// guide display
	private final JLabel titleLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JLabel wikiLink = new JLabel();
	private final JPanel tabArea = new JPanel(new BorderLayout(0, 6));
	private final GearView gearView;
	private final JLabel notesLabel = new JLabel();
	private final JLabel tacticsLabel = new JLabel();

	private MonsterGuide currentGuide;
	private String currentName;

	/** Asks RuneLite's Slayer plugin for the current task name, or null if there isn't one. */
	private final Supplier<String> slayerTask;
	private final JButton slayerButton = new JButton("Open my Slayer task");

	MobStrategyPanel(GuideStore store, MobStrategyConfig config, ItemManager itemManager, Supplier<String> slayerTask)
	{
		super();
		this.store = store;
		this.config = config;
		this.monsters = store.names();
		this.gearView = new GearView(store, itemManager);
		this.slayerTask = slayerTask;

		setLayout(new BorderLayout(0, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildSearchArea(), BorderLayout.NORTH);
		add(buildInfoArea(), BorderLayout.CENTER);
		add(buildCredits(), BorderLayout.SOUTH);

		showNothingSelected();
	}

	// ---------------------------------------------------------------- search area

	private JPanel buildSearchArea()
	{
		JPanel area = new JPanel();
		area.setLayout(new BoxLayout(area, BoxLayout.Y_AXIS));
		area.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Search box: every key typed updates the results list below it
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 30));
		searchBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		searchBar.setAlignmentX(LEFT_ALIGNMENT);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateResults();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateResults();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateResults();
			}
		});
		// Enter picks the highlighted result
		searchBar.addActionListener(e -> pickHighlighted());
		// Up/Down arrows move the highlight while you keep typing
		searchBar.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				int size = resultsModel.getSize();
				if (size == 0)
				{
					return;
				}
				int index = resultsList.getSelectedIndex();
				if (e.getKeyCode() == KeyEvent.VK_DOWN)
				{
					index = Math.min(index + 1, size - 1);
				}
				else if (e.getKeyCode() == KeyEvent.VK_UP)
				{
					index = Math.max(index - 1, 0);
				}
				else
				{
					return;
				}
				resultsList.setSelectedIndex(index);
				resultsList.ensureIndexIsVisible(index);
				e.consume();
			}
		});

		// Live results list: hidden until you start typing
		resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		resultsList.setFixedCellHeight(RESULT_ROW_HEIGHT);
		resultsList.setFont(FontManager.getRunescapeSmallFont());
		resultsList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		resultsList.setForeground(Color.WHITE);
		resultsList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
		resultsList.setSelectionForeground(Color.WHITE);
		resultsList.setFocusable(false); // keep typing focus in the search box
		resultsList.setCellRenderer(new MonsterRenderer());
		resultsList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		resultsList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int index = resultsList.locationToIndex(e.getPoint());
				if (index >= 0)
				{
					pick(resultsModel.get(index));
				}
			}
		});
		resultsScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		resultsScroll.setAlignmentX(LEFT_ALIGNMENT);
		resultsScroll.setVisible(false);

		noMatchesLabel.setFont(FontManager.getRunescapeSmallFont());
		noMatchesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		noMatchesLabel.setAlignmentX(LEFT_ALIGNMENT);
		noMatchesLabel.setVisible(false);

		// Dropdown: always lists every monster, for browsing
		monsterDropdown.setModel(new DefaultComboBoxModel<>(monsters.toArray(new String[0])));
		monsterDropdown.setSelectedIndex(-1);
		monsterDropdown.setPreferredSize(new Dimension(150, 30));
		monsterDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		monsterDropdown.setAlignmentX(LEFT_ALIGNMENT);
		monsterDropdown.setFocusable(false);
		monsterDropdown.setMaximumRowCount(12);
		monsterDropdown.setRenderer(new MonsterRenderer());
		monsterDropdown.addActionListener(e ->
		{
			String picked = (String) monsterDropdown.getSelectedItem();
			if (picked == null)
			{
				showNothingSelected();
			}
			else
			{
				openGuide(picked);
			}
		});

		area.add(searchBar);
		area.add(resultsScroll);
		area.add(noMatchesLabel);
		area.add(Box.createVerticalStrut(8));
		area.add(monsterDropdown);

		// Opens the guide for whatever Slayer task you have right now
		slayerButton.setFont(FontManager.getRunescapeSmallFont());
		slayerButton.setFocusable(false);
		slayerButton.setAlignmentX(LEFT_ALIGNMENT);
		slayerButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
		slayerButton.setPreferredSize(new Dimension(150, 25));
		slayerButton.addActionListener(e -> openSlayerTask());
		area.add(Box.createVerticalStrut(6));
		area.add(slayerButton);
		return area;
	}

	/**
	 * Rebuild the live results from what's typed. Names that START with the
	 * search text come first, then names that just contain it somewhere.
	 */
	private void updateResults()
	{
		String search = searchBar.getText().trim().toLowerCase();
		resultsModel.clear();

		if (search.isEmpty())
		{
			resultsScroll.setVisible(false);
			noMatchesLabel.setVisible(false);
			refreshLayout();
			return;
		}

		List<String> startsWith = new ArrayList<>();
		List<String> contains = new ArrayList<>();
		for (String monster : monsters)
		{
			String name = monster.toLowerCase();
			if (name.startsWith(search))
			{
				startsWith.add(monster);
			}
			else if (name.contains(search))
			{
				contains.add(monster);
			}
		}
		startsWith.forEach(resultsModel::addElement);
		contains.forEach(resultsModel::addElement);

		boolean anyMatches = !resultsModel.isEmpty();
		if (anyMatches)
		{
			int rows = Math.min(resultsModel.getSize(), MAX_VISIBLE_RESULTS);
			Dimension size = new Dimension(PANEL_WIDTH - 20, rows * RESULT_ROW_HEIGHT + 2);
			resultsScroll.setPreferredSize(size);
			resultsScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));
			resultsList.setSelectedIndex(0);
		}
		resultsScroll.setVisible(anyMatches);
		noMatchesLabel.setVisible(!anyMatches);
		refreshLayout();
	}

	private void pickHighlighted()
	{
		String highlighted = resultsList.getSelectedValue();
		if (highlighted != null)
		{
			pick(highlighted);
		}
	}

	/** Show a monster and reset the search box. */
	private void pick(String monster)
	{
		monsterDropdown.setSelectedItem(monster); // this also opens its guide
		searchBar.setText(""); // clearing the box hides the results list
	}

	// ---------------------------------------------------------------- guide area

	private JPanel buildInfoArea()
	{
		JPanel area = new JPanel();
		area.setLayout(new BoxLayout(area, BoxLayout.Y_AXIS));
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setAlignmentX(LEFT_ALIGNMENT);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);

		wikiLink.setText("<html><u>Open full page on the wiki</u></html>");
		wikiLink.setFont(FontManager.getRunescapeSmallFont());
		wikiLink.setForeground(ColorScheme.BRAND_ORANGE);
		wikiLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		wikiLink.setAlignmentX(LEFT_ALIGNMENT);
		wikiLink.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (currentGuide != null)
				{
					LinkBrowser.browse(WikiApi.pageLink(currentGuide.page));
				}
			}
		});

		// Notes tab: the wiki's text about gear and inventory
		JPanel notesTab = new JPanel();
		notesTab.setLayout(new BoxLayout(notesTab, BoxLayout.Y_AXIS));
		notesTab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		styleText(notesLabel);
		notesTab.add(notesLabel);

		// Tactics tab: every other section of the page
		JPanel tacticsTab = new JPanel();
		tacticsTab.setLayout(new BoxLayout(tacticsTab, BoxLayout.Y_AXIS));
		tacticsTab.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		styleText(tacticsLabel);
		tacticsTab.add(tacticsLabel);

		JPanel tabDisplay = new JPanel(new BorderLayout());
		tabDisplay.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		MaterialTabGroup tabGroup = new MaterialTabGroup(tabDisplay);
		MaterialTab gear = new MaterialTab("Gear", tabGroup, gearView);
		MaterialTab notes = new MaterialTab("Notes", tabGroup, notesTab);
		MaterialTab tactics = new MaterialTab("Tactics", tabGroup, tacticsTab);
		tabGroup.addTab(gear);
		tabGroup.addTab(notes);
		tabGroup.addTab(tactics);
		tabGroup.select(gear);

		tabArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabArea.setAlignmentX(LEFT_ALIGNMENT);
		tabArea.add(tabGroup, BorderLayout.NORTH);
		tabArea.add(tabDisplay, BorderLayout.CENTER);

		area.add(titleLabel);
		area.add(Box.createVerticalStrut(4));
		area.add(statusLabel);
		area.add(Box.createVerticalStrut(4));
		area.add(wikiLink);
		area.add(Box.createVerticalStrut(8));
		area.add(tabArea);
		return area;
	}

	private static void styleText(JLabel label)
	{
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setVerticalAlignment(JLabel.TOP);
	}

	private void showNothingSelected()
	{
		currentGuide = null;
		currentName = null;
		titleLabel.setText("Pick a monster");
		titleLabel.setIcon(null);
		if (monsters.isEmpty())
		{
			statusLabel.setText(html("No guide info found. Run GuideGenerator to build guides.json.", GRAY));
		}
		else
		{
			statusLabel.setText(html(monsters.size() + " monsters and bosses. Search above or use the dropdown.", GRAY));
		}
		wikiLink.setVisible(false);
		tabArea.setVisible(false);
		refreshLayout();
	}

	/** Shows a guide right away, then grabs a fresh copy from the wiki if that setting is on. */
	private void openGuide(String name)
	{
		currentName = name;
		MonsterGuide guide = store.get(name);
		if (guide == null)
		{
			showNothingSelected();
			return;
		}
		showGuide(guide);

		if (!config.liveWikiLookups())
		{
			setStatus("Built-in info from " + store.builtInDate() + ".");
		}
		else if (store.hasLive(name))
		{
			setStatus("Up to date with the wiki.");
		}
		else
		{
			setStatus("Getting the latest info from the wiki...");
			store.fetchLive(name,
				fresh -> SwingUtilities.invokeLater(() ->
				{
					if (name.equals(currentName))
					{
						showGuide(fresh);
						setStatus("Up to date with the wiki.");
					}
				}),
				() -> SwingUtilities.invokeLater(() ->
				{
					if (name.equals(currentName))
					{
						setStatus("Couldn't reach the wiki. Showing built-in info.");
					}
				}));
		}
	}

	private void showGuide(MonsterGuide guide)
	{
		currentGuide = guide;
		// wrapped, so long names like "Fossil Island wyverns (Slayer task)" can't stretch the panel
		titleLabel.setText(wrapHtml(escape(guide.name)));
		titleLabel.setIcon(store.picture(guide.name));
		wikiLink.setVisible(true);
		tabArea.setVisible(true);

		// Gear tab
		gearView.show(guide);

		// Notes tab
		notesLabel.setText(guide.gearNotes.isEmpty()
			? html("No gear notes on the wiki for this one.", GRAY)
			: sectionsHtml(guide.gearNotes));

		// Tactics tab
		tacticsLabel.setText(guide.tactics.isEmpty()
			? html("No tactics written on the wiki for this one yet.", GRAY)
			: sectionsHtml(guide.tactics));

		refreshLayout();
	}

	/** The "Open my Slayer task" button: find the guide for the current task and open it. */
	private void openSlayerTask()
	{
		String task = slayerTask.get();
		if (task == null || task.trim().isEmpty())
		{
			showNothingSelected();
			setStatus("No Slayer task found. Log in with a task, and make sure RuneLite's Slayer plugin is turned on.");
			return;
		}
		String guide = store.findGuideFor(task);
		if (guide == null)
		{
			showNothingSelected();
			setStatus("Your task is " + task + ", but there's no strategy guide for it on the wiki.");
			return;
		}
		pick(guide);
	}

	/** Draws a monster's picture next to its name in the search results and dropdown. */
	private class MonsterRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused)
		{
			super.getListCellRendererComponent(list, value, index, selected, focused);
			setIcon(value == null ? null : store.picture(value.toString()));
			setIconTextGap(6);
			return this;
		}
	}

	private void setStatus(String text)
	{
		statusLabel.setText(html(text, GRAY));
	}

	private JLabel buildCredits()
	{
		JLabel credits = new JLabel(wrapHtml("Info from the <u>Old School RuneScape Wiki</u> (CC BY-NC-SA 3.0)"));
		credits.setFont(FontManager.getRunescapeSmallFont());
		credits.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		credits.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		credits.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(WIKI_URL);
			}
		});
		return credits;
	}

	private void refreshLayout()
	{
		revalidate();
		repaint();
	}

	// ---------------------------------------------------------------- text formatting

	/** Turns guide sections into wrapped HTML: orange headings, then the text. */
	private static String sectionsHtml(List<GuideSection> sections)
	{
		StringBuilder sb = new StringBuilder();
		for (GuideSection section : sections)
		{
			String color = section.level <= 2 ? ORANGE : WHITE;
			sb.append("<span style='color:").append(color).append("'><b>")
				.append(escape(section.heading)).append("</b></span><br>");
			for (String line : section.text.split("\n"))
			{
				// leading spaces = indent for sub-bullets
				int spaces = 0;
				while (spaces < line.length() && line.charAt(spaces) == ' ')
				{
					spaces++;
				}
				for (int i = 0; i < spaces; i++)
				{
					sb.append("&nbsp;&nbsp;");
				}
				sb.append(escape(line.substring(spaces))).append("<br>");
			}
			sb.append("<br>");
		}
		return wrapHtml(sb.toString());
	}

	private static String html(String text, String color)
	{
		return wrapHtml("<span style='color:" + color + "'>" + escape(text) + "</span>");
	}

	/** Wraps text so it fits the panel width instead of running off the side. */
	private static String wrapHtml(String body)
	{
		return "<html><body style='width: " + TEXT_WIDTH + "px'>" + body + "</body></html>";
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String toHex(Color c)
	{
		return String.format("#%06x", c.getRGB() & 0xFFFFFF);
	}
}
