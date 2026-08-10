package com.ironkinclan.model;

import java.util.List;

public class TrackedEventGroup
{
	public final String eventId;
	public final List<TrackedItem> items;

	public TrackedEventGroup(String eventId, List<TrackedItem> items)
	{
		this.eventId = eventId;
		this.items = items;
	}
}
