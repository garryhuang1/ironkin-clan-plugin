package com.ironkinclan.model;

import java.util.List;

public class TrackedEventGroup
{
	public final String eventId;
	public final List<TrackedItem> items;
	public final String eventPassword;

	public TrackedEventGroup(String eventId, List<TrackedItem> items, String eventPassword)
	{
		this.eventId = eventId;
		this.items = items;
		this.eventPassword = eventPassword;
	}
}
