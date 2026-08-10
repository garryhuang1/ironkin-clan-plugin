package com.ironkinclan.ui;

import com.ironkinclan.model.TrackedEventGroup;
import com.ironkinclan.model.TrackedItem;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class IronkinClanPanel extends PluginPanel
{
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int MAX_LOG_ENTRIES = 50;
	private static final int ICON_SLOT_SIZE = 36;
	private static final double TRACKED_ITEMS_WEIGHT_WITH_LOG = 0.7;
	private static final double LOG_WEIGHT_WITH_LOG = 0.3;

	private final ItemManager itemManager;

	private final JPanel trackedItemsContainer = new JPanel();
	private final JPanel topSection = new JPanel(new BorderLayout());
	private final JPanel logPanel = new JPanel();
	private final JPanel logSection = new JPanel(new BorderLayout());
	private final JLabel trackedItemsPlaceholder = new JLabel("No items loaded yet");
	private final GridBagConstraints topSectionConstraints = new GridBagConstraints();
	private final GridBagConstraints logSectionConstraints = new GridBagConstraints();
	private final List<String> logEntries = new ArrayList<>();
	private final JButton exportLogButton = new JButton("Export");
	// Which event sections the user has collapsed, kept across setTrackedItems() rebuilds so a
	// refresh doesn't silently re-expand sections the user chose to hide.
	private final Set<String> collapsedEventIds = new HashSet<>();

	private Runnable onActivate;

	public IronkinClanPanel(ItemManager itemManager)
	{
		super(false);
		this.itemManager = itemManager;

		setLayout(new GridBagLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topSection.add(sectionHeader("Tracked Items"), BorderLayout.NORTH);

		trackedItemsContainer.setLayout(new BoxLayout(trackedItemsContainer, BoxLayout.Y_AXIS));
		trackedItemsContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		trackedItemsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
		trackedItemsPlaceholder.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		trackedItemsContainer.add(trackedItemsPlaceholder);

		JScrollPane trackedItemsScroll = new JScrollPane(trackedItemsContainer);
		trackedItemsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		topSection.add(trackedItemsScroll, BorderLayout.CENTER);

		logPanel.setLayout(new BoxLayout(logPanel, BoxLayout.Y_AXIS));
		logPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		logPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		JScrollPane logScroll = new JScrollPane(logPanel);
		logScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		logScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		logScroll.getVerticalScrollBar().setUnitIncrement(16);
		logScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		exportLogButton.setFocusPainted(false);
		exportLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		exportLogButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		exportLogButton.setFont(exportLogButton.getFont().deriveFont(Font.PLAIN, 11f));
		exportLogButton.setMargin(new Insets(1, 6, 1, 6));
		exportLogButton.setToolTipText("Copy the log to your clipboard");
		exportLogButton.addActionListener(e -> exportLog());

		logSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		logSection.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		logSection.add(sectionHeader("Log", exportLogButton), BorderLayout.NORTH);
		logSection.add(logScroll, BorderLayout.CENTER);

		topSectionConstraints.gridx = 0;
		topSectionConstraints.gridy = 0;
		topSectionConstraints.weightx = 1;
		topSectionConstraints.fill = GridBagConstraints.BOTH;

		logSectionConstraints.gridx = 0;
		logSectionConstraints.gridy = 1;
		logSectionConstraints.weightx = 1;
		logSectionConstraints.fill = GridBagConstraints.BOTH;

		add(topSection, topSectionConstraints);
		add(logSection, logSectionConstraints);

		updateSectionWeights(true);
	}

	public void setLogVisible(boolean visible)
	{
		SwingUtilities.invokeLater(() ->
		{
			logSection.setVisible(visible);
			updateSectionWeights(visible);
		});
	}

	private void updateSectionWeights(boolean logVisible)
	{
		GridBagLayout layout = (GridBagLayout) getLayout();
		topSectionConstraints.weighty = logVisible ? TRACKED_ITEMS_WEIGHT_WITH_LOG : 1.0;
		logSectionConstraints.weighty = logVisible ? LOG_WEIGHT_WITH_LOG : 0.0;
		layout.setConstraints(topSection, topSectionConstraints);
		layout.setConstraints(logSection, logSectionConstraints);
		revalidate();
		repaint();
	}

	public void setOnActivate(Runnable onActivate)
	{
		this.onActivate = onActivate;
	}

	@Override
	public void onActivate()
	{
		if (onActivate != null)
		{
			onActivate.run();
		}
	}

	private static JPanel sectionHeader(String text)
	{
		return sectionHeader(text, null);
	}

	private static JPanel sectionHeader(String text, JComponent trailing)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		wrapper.add(label, BorderLayout.WEST);
		if (trailing != null)
		{
			wrapper.add(trailing, BorderLayout.EAST);
		}
		return wrapper;
	}

	public void setTrackedItems(List<TrackedEventGroup> events)
	{
		SwingUtilities.invokeLater(() ->
		{
			trackedItemsContainer.removeAll();

			if (events.isEmpty())
			{
				trackedItemsContainer.add(trackedItemsPlaceholder);
			}
			else
			{
				for (TrackedEventGroup event : events)
				{
					trackedItemsContainer.add(createEventSection(event));
				}
			}

			trackedItemsContainer.revalidate();
			trackedItemsContainer.repaint();
		});
	}

	private JPanel createEventSection(TrackedEventGroup event)
	{
		// BoxLayout will stretch a child beyond its content's actual needs if there's leftover
		// vertical space in the scroll viewport and the child's maximum size allows it; capping
		// the maximum height to the (dynamic) preferred height stops a section from ever
		// reserving more room than its header + current item grid actually need.
		JPanel section = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

		JPanel itemsPanel = new WrappingFlowPanel(FlowLayout.LEFT, 4, 4, trackedItemsContainer);
		itemsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		itemsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		itemsPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		for (TrackedItem item : event.items)
		{
			itemsPanel.add(createItemSlot(item));
		}
		itemsPanel.setVisible(!collapsedEventIds.contains(event.eventId));

		JLabel headerLabel = new JLabel(collapsibleHeaderText(event.eventId, !itemsPanel.isVisible()));
		headerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel headerBar = new JPanel(new BorderLayout());
		headerBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		headerBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		headerBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		headerBar.add(headerLabel, BorderLayout.WEST);
		headerBar.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean collapsing = itemsPanel.isVisible();
				itemsPanel.setVisible(!collapsing);
				headerLabel.setText(collapsibleHeaderText(event.eventId, collapsing));

				if (collapsing)
				{
					collapsedEventIds.add(event.eventId);
				}
				else
				{
					collapsedEventIds.remove(event.eventId);
				}

				trackedItemsContainer.revalidate();
				trackedItemsContainer.repaint();
			}
		});

		section.add(headerBar, BorderLayout.NORTH);
		section.add(itemsPanel, BorderLayout.CENTER);
		return section;
	}

	private static String collapsibleHeaderText(String eventId, boolean collapsed)
	{
		return (collapsed ? "▶ " : "▼ ") + eventId;
	}

	private JPanel createItemSlot(TrackedItem item)
	{
		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setVerticalAlignment(SwingConstants.CENTER);
		itemManager.getImage(item.id).addTo(icon);

		JPanel slot = new JPanel(new BorderLayout());
		slot.setPreferredSize(new Dimension(ICON_SLOT_SIZE, ICON_SLOT_SIZE));
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		slot.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		slot.setToolTipText(item.name);
		slot.add(icon, BorderLayout.CENTER);
		return slot;
	}

	public void addLogEntry(String text, boolean success)
	{
		SwingUtilities.invokeLater(() ->
		{
			String time = TIME_FORMAT.format(Instant.now());
			String line = "[" + time + "] " + text;

			logEntries.add(0, line);
			while (logEntries.size() > MAX_LOG_ENTRIES)
			{
				logEntries.remove(logEntries.size() - 1);
			}

			JTextArea entry = new JTextArea(line);
			entry.setEditable(false);
			entry.setLineWrap(true);
			entry.setWrapStyleWord(true);
			entry.setOpaque(false);
			entry.setFont(UIManager.getFont("Label.font"));
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

	private void exportLog()
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(String.join(System.lineSeparator(), logEntries)), null);

		exportLogButton.setText("Copied!");
		Timer resetLabel = new Timer(1200, e -> exportLogButton.setText("Export"));
		resetLabel.setRepeats(false);
		resetLabel.start();
	}

	/**
	 * FlowLayout's preferredLayoutSize() ignores wrapping (it reports the width/height needed
	 * for a single row), so a FlowLayout panel nested in a BoxLayout parent never grows tall
	 * enough to show wrapped rows. This recomputes the preferred height for the panel's actual
	 * current width so multi-row content isn't clipped.
	 *
	 * Width is measured off an explicitly-supplied reference component (the scrollable
	 * BoxLayout container that ultimately hosts these panels), rather than any ancestor looked up
	 * dynamically. This panel sits nested inside a per-event section (a BorderLayout cell), whose
	 * own size depends on asking this panel for its preferred size - measuring against that parent,
	 * or against an ancestor found by walking up at layout time, produced circular/stale readings
	 * (the true width isn't settled yet at that point), which was inflating the reserved height for
	 * every event section. The reference container's width is set independently by its own parent
	 * viewport, so it's already reliable by the time this is asked to compute a height for it.
	 */
	private static class WrappingFlowPanel extends JPanel
	{
		private final Component widthReference;

		WrappingFlowPanel(int align, int hgap, int vgap, Component widthReference)
		{
			super(new FlowLayout(align, hgap, vgap));
			this.widthReference = widthReference;
		}

		@Override
		public Dimension getPreferredSize()
		{
			int width = widthReference.getWidth() > 0 ? widthReference.getWidth() : PANEL_WIDTH;

			FlowLayout layout = (FlowLayout) getLayout();
			int hgap = layout.getHgap();
			int vgap = layout.getVgap();
			Insets insets = getInsets();
			int availableWidth = width - insets.left - insets.right;

			int rowWidth = 0;
			int rowHeight = 0;
			int totalHeight = 0;
			int rows = 0;

			for (Component c : getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}

				Dimension d = c.getPreferredSize();
				if (rowWidth > 0 && rowWidth + hgap + d.width > availableWidth)
				{
					totalHeight += rowHeight + vgap;
					rowWidth = 0;
					rowHeight = 0;
				}

				rowWidth += (rowWidth > 0 ? hgap : 0) + d.width;
				rowHeight = Math.max(rowHeight, d.height);
				rows++;
			}

			if (rows > 0)
			{
				totalHeight += rowHeight;
			}

			return new Dimension(width, totalHeight + insets.top + insets.bottom + vgap * 2);
		}
	}
}
