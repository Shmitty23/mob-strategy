package com.mobstrategy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel: a search box with live results, a dropdown of every monster,
 * and an area that will show gear and tactics for the chosen monster.
 */
class MobStrategyPanel extends PluginPanel
{
	private static final String WIKI_URL = "https://oldschool.runescape.wiki/";
	private static final int RESULT_ROW_HEIGHT = 20;
	private static final int MAX_VISIBLE_RESULTS = 8;

	// Placeholder list for now. In the next step this comes from the wiki data.
	private static final List<String> MONSTERS = Arrays.asList(
		"Abyssal demon",
		"Bloodveld",
		"Cerberus",
		"Dust devil",
		"Gargoyle",
		"General Graardor",
		"Kraken",
		"Nechryael",
		"Vorkath",
		"Zulrah"
	);

	private final IconTextField searchBar = new IconTextField();
	private final DefaultListModel<String> resultsModel = new DefaultListModel<>();
	private final JList<String> resultsList = new JList<>(resultsModel);
	private final JScrollPane resultsScroll = new JScrollPane(resultsList);
	private final JLabel noMatchesLabel = new JLabel("No monsters found");
	private final JComboBox<String> monsterDropdown = new JComboBox<>();
	private final JLabel titleLabel = new JLabel();
	private final JLabel infoLabel = new JLabel();

	MobStrategyPanel()
	{
		super();
		setLayout(new BorderLayout(0, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildSearchArea(), BorderLayout.NORTH);
		add(buildInfoArea(), BorderLayout.CENTER);
		add(buildCredits(), BorderLayout.SOUTH);

		showNothingSelected();
	}

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
		monsterDropdown.setModel(new DefaultComboBoxModel<>(MONSTERS.toArray(new String[0])));
		monsterDropdown.setSelectedIndex(-1);
		monsterDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		monsterDropdown.setAlignmentX(LEFT_ALIGNMENT);
		monsterDropdown.setFocusable(false);
		monsterDropdown.addActionListener(e ->
		{
			String picked = (String) monsterDropdown.getSelectedItem();
			if (picked == null)
			{
				showNothingSelected();
			}
			else
			{
				showMonster(picked);
			}
		});

		area.add(searchBar);
		area.add(resultsScroll);
		area.add(noMatchesLabel);
		area.add(Box.createVerticalStrut(8));
		area.add(monsterDropdown);
		return area;
	}

	private JPanel buildInfoArea()
	{
		JPanel area = new JPanel();
		area.setLayout(new BoxLayout(area, BoxLayout.Y_AXIS));
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);

		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		area.add(titleLabel);
		area.add(Box.createVerticalStrut(6));
		area.add(infoLabel);
		return area;
	}

	private JLabel buildCredits()
	{
		JLabel credits = new JLabel("<html><body style='width: 160px'>Info from the "
			+ "<u>Old School RuneScape Wiki</u> (CC BY-NC-SA 3.0)</body></html>");
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
		for (String monster : MONSTERS)
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
		monsterDropdown.setSelectedItem(monster); // this also shows its info
		searchBar.setText(""); // clearing the box hides the results list
	}

	private void refreshLayout()
	{
		revalidate();
		repaint();
	}

	private void showNothingSelected()
	{
		titleLabel.setText("Pick a monster");
		infoLabel.setText(wrap("Search above or choose from the dropdown."));
	}

	private void showMonster(String monster)
	{
		titleLabel.setText(monster);
		infoLabel.setText(wrap("Gear and tactics for " + monster + " will show here once wiki info is hooked up."));
	}

	/** Wraps text so it fits the panel width instead of running off the side. */
	private static String wrap(String text)
	{
		return "<html><body style='width: 150px'>" + text + "</body></html>";
	}
}
