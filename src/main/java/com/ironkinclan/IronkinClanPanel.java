package com.ironkinclan;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

class IronkinClanPanel extends PluginPanel
{
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int MAX_LOG_ENTRIES = 50;
	private static final int ICON_SLOT_SIZE = 36;

	private final ItemManager itemManager;

	private final JPanel trackedItemsPanel = new JPanel();
	private final JPanel logPanel = new JPanel();
	private final JPanel logSection = new JPanel(new BorderLayout());
	private final JLabel trackedItemsPlaceholder = new JLabel("No items loaded yet");

	@Inject
	IronkinClanPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel topSection = new JPanel();
		topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
		topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		topSection.add(sectionHeader("Tracked Items"));
		topSection.add(Box.createRigidArea(new Dimension(0, 4)));

		trackedItemsPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 4));
		trackedItemsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		trackedItemsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		trackedItemsPlaceholder.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		trackedItemsPanel.add(trackedItemsPlaceholder);
		topSection.add(trackedItemsPanel);

		add(topSection, BorderLayout.NORTH);

		logPanel.setLayout(new BoxLayout(logPanel, BoxLayout.Y_AXIS));
		logPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		logPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JScrollPane logScroll = new JScrollPane(logPanel);
		logScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		logScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		logScroll.getVerticalScrollBar().setUnitIncrement(16);
		logScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		logSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		logSection.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		logSection.add(sectionHeader("Upload Log"), BorderLayout.NORTH);
		logSection.add(logScroll, BorderLayout.CENTER);

		add(logSection, BorderLayout.CENTER);
	}

	void setLogVisible(boolean visible)
	{
		SwingUtilities.invokeLater(() -> logSection.setVisible(visible));
	}

	private static JPanel sectionHeader(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		wrapper.add(label, BorderLayout.WEST);
		return wrapper;
	}

	void setTrackedItems(List<IronkinClanPlugin.TrackedItem> items)
	{
		SwingUtilities.invokeLater(() ->
		{
			trackedItemsPanel.removeAll();

			if (items.isEmpty())
			{
				trackedItemsPanel.add(trackedItemsPlaceholder);
			}
			else
			{
				for (IronkinClanPlugin.TrackedItem item : items)
				{
					trackedItemsPanel.add(createItemSlot(item));
				}
			}

			trackedItemsPanel.revalidate();
			trackedItemsPanel.repaint();
		});
	}

	private JPanel createItemSlot(IronkinClanPlugin.TrackedItem item)
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setVerticalAlignment(SwingConstants.CENTER);
		itemManager.getImage(item.id).addTo(icon);

		JPanel slot = new JPanel(new BorderLayout());
		slot.setPreferredSize(new Dimension(ICON_SLOT_SIZE, ICON_SLOT_SIZE));
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		slot.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		slot.setToolTipText(item.name + " (" + item.id + ")");
		slot.add(icon, BorderLayout.CENTER);
		return slot;
	}

	void addLogEntry(String text, boolean success)
	{
		SwingUtilities.invokeLater(() ->
		{
			String time = TIME_FORMAT.format(Instant.now());
			JLabel entry = new JLabel("<html>[" + time + "] " + escapeHtml(text) + "</html>");
			entry.setForeground(success ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
			entry.setAlignmentX(Component.LEFT_ALIGNMENT);
			entry.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

			logPanel.add(entry, 0);

			while (logPanel.getComponentCount() > MAX_LOG_ENTRIES)
			{
				logPanel.remove(logPanel.getComponentCount() - 1);
			}

			logPanel.revalidate();
			logPanel.repaint();
		});
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
