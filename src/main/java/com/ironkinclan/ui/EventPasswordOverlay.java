package com.ironkinclan.ui;

import com.ironkinclan.model.TrackedEventGroup;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Lists the password(s) for every currently tracked event that has one configured. Renders
 * nothing when there are none, so the overlay stays registered but invisible rather than being
 * added/removed from the {@link net.runelite.client.ui.overlay.OverlayManager} repeatedly.
 */
public class EventPasswordOverlay extends OverlayPanel
{
	// Long passwords blow out the panel's width (it's sized to fit its widest line), so anything
	// past this is cut off with an ellipsis rather than stretching the overlay across the screen.
	private static final int MAX_PASSWORD_LENGTH = 25;
	// Wide enough to comfortably fit a truncated MAX_PASSWORD_LENGTH-character line without the
	// panel's normal fit-to-content sizing squeezing it.
	private static final int PANEL_WIDTH = 150;

	private List<TrackedEventGroup> passwordEvents = Collections.emptyList();

	@Inject
	public EventPasswordOverlay()
	{
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
	}

	public void setPasswordEvents(List<TrackedEventGroup> passwordEvents)
	{
		this.passwordEvents = passwordEvents;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();

		if (passwordEvents.isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Event Passwords")
			.color(Color.ORANGE)
			.build());

		for (TrackedEventGroup event : passwordEvents)
		{
			panelComponent.getChildren().add(TitleComponent.builder()
				.text(truncate(event.eventPassword))
				.color(Color.WHITE)
				.build());
		}

		return super.render(graphics);
	}

	private static String truncate(String password)
	{
		if (password.length() <= MAX_PASSWORD_LENGTH)
		{
			return password;
		}
		return password.substring(0, MAX_PASSWORD_LENGTH) + "...";
	}
}
